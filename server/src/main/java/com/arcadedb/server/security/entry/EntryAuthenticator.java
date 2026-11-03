/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.server.security.entry;

import com.arcadedb.database.Database;
import com.arcadedb.log.LogManager;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.http.HttpAuthSessionManager;
import com.arcadedb.server.security.ApiTokenConfiguration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Authenticates an Entry credential to a per-person {@link EntryServerSecurityUser}.
 * <p>
 * The verdict for a credential (the person and their groups, or a refusal Entry is sure of) is kept for
 * {@code verdictCacheSeconds} and never past the credential's own expiry, keyed by the credential's SHA-256 so no token is
 * held. Remembering refusals keeps random bearers from turning the server into an amplifier against Entry, and at most
 * {@code maxConcurrentLookups} Entry reads run at once: excess callers are refused, not queued. An outage is never
 * remembered.
 * <p>
 * Standing is not part of the verdict: every request reads the person's lifecycle state afresh, so Entry's disablement
 * takes effect on the next request on every node, cached verdict or not.
 */
public final class EntryAuthenticator {
  /** Refusals that are about the credential or the person, not about reaching Entry. */
  private static final Set<String> REMEMBERED = Set.of("session_rejected", "proof_unsupported", "inactive_account", "account_mismatch",
      "no_permission");

  private record Verdict(EntryServerSecurityUser user, String refusal, long validUntil) {
  }

  private final EntryConfiguration                                             config;
  private final EntryPrincipalResolver                                         resolver;
  private final EntryLifecycleStore                                            store;
  private final Function<String, Database>                                     databases;
  private final BiFunction<EntryPrincipal, JSONObject, EntryServerSecurityUser> userFactory;
  private final LongSupplier                                                   clock;
  private final ConcurrentHashMap<String, Verdict>                             verdicts = new ConcurrentHashMap<>();
  private final Semaphore                                                      lookups;

  /**
   * @param databases resolves the configured state database by name, null when it does not exist
   */
  public EntryAuthenticator(final EntryConfiguration config, final EntryPrincipalResolver resolver, final EntryLifecycleStore store,
      final Function<String, Database> databases, final BiFunction<EntryPrincipal, JSONObject, EntryServerSecurityUser> userFactory,
      final LongSupplier clock) {
    this.config = config;
    this.resolver = resolver;
    this.store = store;
    this.databases = databases;
    this.userFactory = userFactory;
    this.clock = clock;
    this.lookups = new Semaphore(config.getMaxConcurrentLookups());
  }

  /** Whether a bearer belongs to Entry: anything ArcadeDB did not issue itself (API {@code at-} or session {@code AU-}). */
  public static boolean isEntryCredential(final String token) {
    return token != null && !token.isEmpty() && !ApiTokenConfiguration.isApiToken(token)
        && !token.startsWith(HttpAuthSessionManager.TOKEN_PREFIX);
  }

  public EntryServerSecurityUser authenticate(final String token) {
    if (!EntryClient.isWellFormed(token))
      throw new EntryAuthenticationException("invalid_credential");

    final String key = EntryConfiguration.sha256Hex(token);
    final long now = clock.getAsLong();
    Verdict verdict = verdicts.get(key);
    if (verdict == null || now >= verdict.validUntil())
      verdict = lookup(token, key, now);
    if (verdict.refusal() != null)
      throw new EntryAuthenticationException(verdict.refusal());

    checkStanding(verdict.user(), key);
    return verdict.user();
  }

  /** Drops every cached verdict of one Entry account, after its standing changed. */
  public void invalidate(final String sourceUserId) {
    verdicts.values().removeIf(v -> v.user() != null && v.user().getPrincipal().sourceUserId().equals(sourceUserId));
  }

  private Verdict lookup(final String token, final String key, final long now) {
    if (!lookups.tryAcquire())
      throw new EntryAuthenticationException("lookup_saturated");
    final EntryPrincipal principal;
    try {
      principal = resolver.resolve(token);
    } catch (final EntryAuthenticationException e) {
      if (REMEMBERED.contains(e.getCode()))
        remember(key, new Verdict(null, e.getCode(), now + config.getVerdictCacheMillis()));
      throw e;
    } finally {
      lookups.release();
    }

    final JSONObject granted = config.databasesFor(principal.permissions());
    if (granted.length() == 0) {
      final Verdict refusal = new Verdict(null, "no_permission", now + config.getVerdictCacheMillis());
      remember(key, refusal);
      return refusal;
    }
    final Verdict verdict = new Verdict(userFactory.apply(principal, granted), null,
        Math.min(now + config.getVerdictCacheMillis(), principal.expiresAtMillis()));
    remember(key, verdict);
    return verdict;
  }

  private void checkStanding(final EntryServerSecurityUser user, final String key) {
    final Database database = databases.apply(config.getDatabase());
    if (database == null)
      throw new EntryAuthenticationException("state_unavailable");
    final EntryPrincipal principal = user.getPrincipal();
    final EntryLifecycleStore.Standing standing;
    try {
      standing = store.standing(database, principal.sourceUserId());
    } catch (final RuntimeException e) {
      LogManager.instance().log(this, Level.WARNING, "Cannot read Entry standing from database '%s'", e, config.getDatabase());
      throw new EntryAuthenticationException("state_unavailable");
    }
    if (standing.denied()) {
      verdicts.remove(key);
      throw new EntryAuthenticationException("standing_denied");
    }
    if (principal.accountSequence() < standing.sessionRevocationSequence()) {
      // Entry proved this credential before the account's latest session revocation: ask again rather than trust it.
      verdicts.remove(key);
      throw new EntryAuthenticationException("stale_proof");
    }
  }

  private void remember(final String key, final Verdict verdict) {
    if (config.getVerdictCacheMillis() <= 0)
      return;
    if (verdicts.size() >= config.getMaxCachedVerdicts())
      verdicts.clear();
    verdicts.put(key, verdict);
  }
}
