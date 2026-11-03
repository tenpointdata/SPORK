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

import com.arcadedb.TestHelper;
import com.arcadedb.database.Database;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.audit.AuditChain;
import com.arcadedb.server.security.ServerSecurityUser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class EntryAuthenticatorTest extends TestHelper {
  private static final String TOKEN = "entry-session-token.abc123";

  private final AtomicLong          clock    = new AtomicLong(EntryFixtures.NOW);
  private final AtomicInteger       lookups  = new AtomicInteger();
  private final EntryLifecycleStore store    = new EntryLifecycleStore(EntryFixtures.config());
  private       EntryPrincipal      answer   = EntryFixtures.principal(4, "spork.console");
  private       RuntimeException    failure;

  private EntryAuthenticator authenticator(final Database stateDatabase) {
    return new EntryAuthenticator(EntryFixtures.config(), token -> {
      lookups.incrementAndGet();
      if (failure != null)
        throw failure;
      return answer;
    }, store, name -> "identity".equals(name) ? stateDatabase : null,
        (principal, databases) -> new EntryServerSecurityUser(null, principal, databases), clock::get);
  }

  private static String refusal(final EntryAuthenticator authenticator, final String token) {
    try {
      authenticator.authenticate(token);
    } catch (final EntryAuthenticationException e) {
      return e.getCode();
    }
    throw new AssertionError("expected a refusal");
  }

  private void disable(final int sequence) {
    new EntryLifecycleReceiver(EntryFixtures.config(), store, new AuditChain(clock::get), userId -> {
    }).receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(sequence), sequence, false));
  }

  @Test
  void theEngineUserIsThePersonWithTheGroupsTheirPermissionsGrant() {
    final ServerSecurityUser user = authenticator(database).authenticate(TOKEN);

    assertThat(user).isInstanceOf(EntryServerSecurityUser.class);
    assertThat(user.getName()).isEqualTo("entry:" + EntryFixtures.USER_ID);
    assertThat(ServerSecurityUser.isServerAdministrator(user.getName())).isFalse();
    assertThat(((EntryServerSecurityUser) user).getPrincipal().credentialId()).isEqualTo(EntryFixtures.CREDENTIAL_ID);
    assertThat(user.toJSON().getJSONObject("databases").getJSONArray("*").toList()).containsExactly("console");
  }

  @Test
  void aVerdictIsReusedUntilItsTimeRunsOut() {
    final EntryAuthenticator authenticator = authenticator(database);
    final ServerSecurityUser first = authenticator.authenticate(TOKEN);
    assertThat(authenticator.authenticate(TOKEN)).isSameAs(first);
    assertThat(lookups.get()).isEqualTo(1);

    clock.addAndGet(30_001L);
    authenticator.authenticate(TOKEN);
    assertThat(lookups.get()).isEqualTo(2);
  }

  @Test
  void aVerdictNeverOutlivesTheEntryCredential() {
    answer = new EntryPrincipal(EntryFixtures.ENVIRONMENT, EntryFixtures.USER_ID, "idn", EntryFixtures.CREDENTIAL_ID, 4, "a@b.c",
        new String[] { "spork.console" }, EntryFixtures.NOW + 1_000L);
    final EntryAuthenticator authenticator = authenticator(database);
    authenticator.authenticate(TOKEN);
    clock.addAndGet(1_001L);
    authenticator.authenticate(TOKEN);
    assertThat(lookups.get()).isEqualTo(2);
  }

  @Test
  void aPersonWithoutASporkPermissionIsRefusedAndTheRefusalIsRemembered() {
    answer = EntryFixtures.principal(4, "other.app");
    final EntryAuthenticator authenticator = authenticator(database);
    assertThat(refusal(authenticator, TOKEN)).isEqualTo("no_permission");
    assertThat(refusal(authenticator, TOKEN)).isEqualTo("no_permission");
    assertThat(lookups.get()).isEqualTo(1);
  }

  @Test
  void aSessionEntryRejectsIsRememberedButAnOutageIsNot() {
    failure = new EntryAuthenticationException("session_rejected");
    final EntryAuthenticator authenticator = authenticator(database);
    assertThat(refusal(authenticator, TOKEN)).isEqualTo("session_rejected");
    assertThat(refusal(authenticator, TOKEN)).isEqualTo("session_rejected");
    assertThat(lookups.get()).isEqualTo(1);

    failure = new EntryAuthenticationException("proof_unavailable");
    assertThat(refusal(authenticator, "another-token")).isEqualTo("proof_unavailable");
    assertThat(refusal(authenticator, "another-token")).isEqualTo("proof_unavailable");
    assertThat(lookups.get()).isEqualTo(3);
  }

  @Test
  void aDisableIsEnforcedOnTheNextRequestEvenWhenTheVerdictIsCached() {
    final EntryAuthenticator authenticator = authenticator(database);
    authenticator.authenticate(TOKEN);

    disable(5);
    assertThat(refusal(authenticator, TOKEN)).isEqualTo("standing_denied");
    assertThat(lookups.get()).isEqualTo(1);
  }

  @Test
  void aProofOlderThanTheLastSessionRevocationIsRefused() {
    final EntryAuthenticator authenticator = authenticator(database);
    disable(5);
    // Restored at 6 after the disable at 5: a proof read before the disable (sequence 4) is stale.
    final JSONObject restore = EntryFixtures.accountDelivery(EntryFixtures.uuid(6), 6, true);
    restore.put("revocations", new JSONArray()
        .put(new JSONObject().put("path", "pat").put("sequence", 5))
        .put(new JSONObject().put("path", "session").put("sequence", 5)));
    new EntryLifecycleReceiver(EntryFixtures.config(), store, new AuditChain(clock::get), userId -> {
    }).receive(database, restore);

    assertThat(refusal(authenticator, TOKEN)).isEqualTo("stale_proof");

    answer = EntryFixtures.principal(6, "spork.console");
    assertThat(authenticator.authenticate(TOKEN).getName()).isEqualTo("entry:" + EntryFixtures.USER_ID);
  }

  @Test
  void invalidatingAPersonDropsTheirCachedVerdicts() {
    final EntryAuthenticator authenticator = authenticator(database);
    authenticator.authenticate(TOKEN);
    authenticator.invalidate(EntryFixtures.USER_ID);
    authenticator.authenticate(TOKEN);
    assertThat(lookups.get()).isEqualTo(2);
  }

  @Test
  void withoutItsStateDatabaseEntryAuthenticationFailsClosed() {
    final EntryAuthenticator authenticator = authenticator(null);
    assertThat(refusal(authenticator, TOKEN)).isEqualTo("state_unavailable");
  }

  @Test
  void onlyCredentialsArcadeDbDoesNotOwnGoToEntry() {
    assertThat(EntryAuthenticator.isEntryCredential("at-0123456789")).isFalse();
    assertThat(EntryAuthenticator.isEntryCredential("AU-0123456789")).isFalse();
    assertThat(EntryAuthenticator.isEntryCredential("")).isFalse();
    assertThat(EntryAuthenticator.isEntryCredential(null)).isFalse();
    assertThat(EntryAuthenticator.isEntryCredential("eyJhbGciOi.payload.sig")).isTrue();
  }
}
