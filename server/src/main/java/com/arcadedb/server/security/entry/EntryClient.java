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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Asks Entry who a credential belongs to, with two fresh reads and no fallback:
 * <ol>
 *   <li>{@code GET /api/v1/consumer/session-proof}: Entry's {@code TConsumerSessionProof} v1, validated by the rules of
 *   entry-lifecycle's {@code consumerSessionProof.ts} (exact environment, field shapes, a 30 s clock window, unexpired);</li>
 *   <li>{@code GET /api/auth/user}: the account Recon's gateway already reads, which must be the proved account, enabled and
 *   verified, and carries the Entry permissions.</li>
 * </ol>
 * Entry's answers never reach the caller: every refusal is one of a fixed set of codes.
 */
public final class EntryClient implements EntryPrincipalResolver {
  static final         String  PROOF_PATH        = "/api/v1/consumer/session-proof";
  static final         String  ACCOUNT_PATH      = "/api/auth/user";
  private static final int     MAX_TOKEN         = 16_384;
  private static final int     MAX_PROOF_BYTES   = 4_096;
  private static final int     MAX_ACCOUNT_BYTES = 262_144;
  private static final int     MAX_PERMISSIONS   = 1_000;
  private static final long    CLOCK_WINDOW      = 30_000L;
  private static final Pattern CREDENTIAL_ID     = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern JSON_TYPE         = Pattern.compile("(?i)application/json(\\s*;.*)?");

  private final EntryConfiguration config;
  private final EntryTransport     transport;
  private final LongSupplier       clock;

  public EntryClient(final EntryConfiguration config, final EntryTransport transport, final LongSupplier clock) {
    this.config = config;
    this.transport = transport;
    this.clock = clock;
  }

  @Override
  public EntryPrincipal resolve(final String token) {
    if (!isWellFormed(token))
      throw new EntryAuthenticationException("invalid_credential");

    final long started = clock.getAsLong();
    final JSONObject proof = read(PROOF_PATH, token, MAX_PROOF_BYTES);
    final long finished = clock.getAsLong();

    final Object schemaVersion = proof.get("schemaVersion", null);
    final String sourceUserId = proof.getString("sourceUserId", null);
    final String identityId = proof.getString("identityId", null);
    final String credentialId = proof.getString("credentialId", null);
    final long accountSequence = integral(proof.get("accountSequence", null));
    final long checkedAt = instant(proof.get("checkedAt", null));
    final long expiresAt = instant(proof.get("expiresAt", null));
    if (!(schemaVersion instanceof Number version) || integral(version) != 1
        || !config.getEnvironmentId().equals(proof.getString("environmentId", null))
        || sourceUserId == null || !EntryConfiguration.UUID.matcher(sourceUserId).matches()
        || !EntryConfiguration.isIdentifier(identityId)
        || credentialId == null || !CREDENTIAL_ID.matcher(credentialId).matches()
        || accountSequence < 0 || accountSequence > Integer.MAX_VALUE
        || checkedAt == Long.MIN_VALUE || expiresAt == Long.MIN_VALUE || expiresAt <= checkedAt || expiresAt <= finished
        || checkedAt < started - CLOCK_WINDOW || checkedAt > finished + CLOCK_WINDOW)
      throw new EntryAuthenticationException("proof_unavailable");

    final JSONObject account = read(ACCOUNT_PATH, token, MAX_ACCOUNT_BYTES);
    if (!sourceUserId.equals(account.getString("id", null)))
      throw new EntryAuthenticationException("account_mismatch");
    final JSONObject api = account.get("api", null) instanceof JSONObject o ? o : null;
    final Object permissions = api != null ? api.get("permissions", null) : null;
    final String email = account.getString("email", null);
    if (!Boolean.FALSE.equals(account.get("disabled", null)) || !Boolean.TRUE.equals(account.get("verified", null))
        || !(permissions instanceof JSONArray held) || held.length() > MAX_PERMISSIONS || email == null || email.length() > 500)
      throw new EntryAuthenticationException("inactive_account");

    final String[] permissionNames = new String[held.length()];
    for (int i = 0; i < permissionNames.length; i++) {
      if (!(held.get(i) instanceof String permission))
        throw new EntryAuthenticationException("inactive_account");
      permissionNames[i] = permission;
    }
    return new EntryPrincipal(config.getEnvironmentId(), sourceUserId, identityId, credentialId, accountSequence, email,
        permissionNames, expiresAt);
  }

  private JSONObject read(final String path, final String token, final int maxBytes) {
    final EntryResponse response;
    try {
      response = transport.get(config.getOrigin() + path, token);
    } catch (final IOException | RuntimeException e) {
      throw new EntryAuthenticationException("proof_unavailable");
    }
    if (response.status() == 401 || response.status() == 403)
      throw new EntryAuthenticationException("session_rejected");
    if (response.status() == 409)
      throw new EntryAuthenticationException("proof_unsupported");
    if (response.status() != 200 || response.contentType() == null || !JSON_TYPE.matcher(response.contentType().strip()).matches()
        || response.body() == null || response.body().length > maxBytes)
      throw new EntryAuthenticationException("proof_unavailable");
    try {
      return new JSONObject(new String(response.body(), StandardCharsets.UTF_8));
    } catch (final RuntimeException e) {
      throw new EntryAuthenticationException("proof_unavailable");
    }
  }

  static boolean isWellFormed(final String token) {
    if (token == null || token.isEmpty() || token.length() > MAX_TOKEN)
      return false;
    for (int i = 0; i < token.length(); i++) {
      final char c = token.charAt(i);
      if (c <= 0x20 || c == 0x7f || Character.isWhitespace(c))
        return false;
    }
    return true;
  }

  private static long integral(final Object value) {
    if (value instanceof Integer || value instanceof Long)
      return ((Number) value).longValue();
    if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == Math.rint(n.doubleValue()))
      return n.longValue();
    return -1L;
  }

  private static long instant(final Object value) {
    if (!(value instanceof String text))
      return Long.MIN_VALUE;
    try {
      return Instant.parse(text).toEpochMilli();
    } catch (final DateTimeParseException e) {
      return Long.MIN_VALUE;
    }
  }
}
