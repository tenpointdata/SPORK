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

import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryClientTest {
  private static final String TOKEN = "entry-session-token.abc123";

  /** Answers the two Entry reads from canned bodies and records what was asked. */
  private static final class FakeEntry implements EntryTransport {
    final List<String> urls = new ArrayList<>();
    EntryResponse proof   = json(200, EntryFixtures.proofJson());
    EntryResponse account = json(200, EntryFixtures.accountJson());

    @Override
    public EntryResponse get(final String url, final String bearerToken) {
      assertThat(bearerToken).isEqualTo(TOKEN);
      urls.add(url);
      return url.endsWith("/api/v1/consumer/session-proof") ? proof : account;
    }
  }

  private static EntryResponse json(final int status, final JSONObject body) {
    return new EntryResponse(status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static EntryClient client(final FakeEntry entry) {
    return new EntryClient(EntryFixtures.config(), entry, () -> EntryFixtures.NOW);
  }

  private static String refusal(final FakeEntry entry, final String token) {
    try {
      client(entry).resolve(token);
    } catch (final EntryAuthenticationException e) {
      return e.getCode();
    }
    throw new AssertionError("expected a refusal");
  }

  @Test
  void resolvesThePersonFromAFreshProofAndTheirAccount() {
    final FakeEntry entry = new FakeEntry();
    final EntryPrincipal principal = client(entry).resolve(TOKEN);

    assertThat(entry.urls).containsExactly("http://localhost:3002/api/v1/consumer/session-proof", "http://localhost:3002/api/auth/user");
    assertThat(principal.sourceUserId()).isEqualTo(EntryFixtures.USER_ID);
    assertThat(principal.identityId()).isEqualTo("google-oauth2|1234");
    assertThat(principal.credentialId()).isEqualTo(EntryFixtures.CREDENTIAL_ID);
    assertThat(principal.accountSequence()).isEqualTo(4L);
    assertThat(principal.email()).isEqualTo("analyst@example.org");
    assertThat(principal.permissions()).containsExactly("spork.console", "other.app");
    assertThat(principal.expiresAtMillis()).isEqualTo(EntryFixtures.NOW + 8 * 3_600_000L);
    assertThat(principal.userName()).isEqualTo("entry:" + EntryFixtures.USER_ID);
  }

  @Test
  void aMalformedTokenNeverLeavesTheProcess() {
    final FakeEntry entry = new FakeEntry();
    assertThat(refusal(entry, "two words")).isEqualTo("invalid_credential");
    assertThat(refusal(entry, "")).isEqualTo("invalid_credential");
    assertThat(refusal(entry, "x".repeat(16_385))).isEqualTo("invalid_credential");
    assertThat(entry.urls).isEmpty();
  }

  @Test
  void entryRefusalsKeepTheirMeaning() {
    final FakeEntry rejected = new FakeEntry();
    rejected.proof = json(401, new JSONObject().put("error", "invalid_session"));
    assertThat(refusal(rejected, TOKEN)).isEqualTo("session_rejected");
    assertThat(rejected.urls).hasSize(1);

    final FakeEntry unsupported = new FakeEntry();
    unsupported.proof = json(409, new JSONObject());
    assertThat(refusal(unsupported, TOKEN)).isEqualTo("proof_unsupported");

    final FakeEntry down = new FakeEntry();
    down.proof = json(502, new JSONObject());
    assertThat(refusal(down, TOKEN)).isEqualTo("proof_unavailable");
  }

  @Test
  void aProofForAnotherEnvironmentOrOutsideTheClockWindowIsRefused() {
    final FakeEntry otherEnvironment = new FakeEntry();
    otherEnvironment.proof = json(200, EntryFixtures.proofJson().put("environmentId", "prod"));
    assertThat(refusal(otherEnvironment, TOKEN)).isEqualTo("proof_unavailable");

    final FakeEntry stale = new FakeEntry();
    stale.proof = json(200, EntryFixtures.proofJson().put("checkedAt", "2026-11-03T11:59:00.000Z"));
    assertThat(refusal(stale, TOKEN)).isEqualTo("proof_unavailable");

    final FakeEntry expired = new FakeEntry();
    expired.proof = json(200, EntryFixtures.proofJson().put("expiresAt", "2026-11-03T12:00:00.000Z"));
    assertThat(refusal(expired, TOKEN)).isEqualTo("proof_unavailable");

    final FakeEntry badFingerprint = new FakeEntry();
    badFingerprint.proof = json(200, EntryFixtures.proofJson().put("credentialId", "not-a-digest"));
    assertThat(refusal(badFingerprint, TOKEN)).isEqualTo("proof_unavailable");
  }

  @Test
  void aBodyThatIsNotBoundedJsonIsRefused() {
    final FakeEntry html = new FakeEntry();
    html.proof = new EntryResponse(200, "text/html", "<html/>".getBytes(StandardCharsets.UTF_8));
    assertThat(refusal(html, TOKEN)).isEqualTo("proof_unavailable");

    final FakeEntry huge = new FakeEntry();
    huge.proof = json(200, EntryFixtures.proofJson().put("padding", "p".repeat(5_000)));
    assertThat(refusal(huge, TOKEN)).isEqualTo("proof_unavailable");
  }

  @Test
  void theAccountMustBeTheProvedOneAndStillActive() {
    final FakeEntry other = new FakeEntry();
    other.account = json(200, EntryFixtures.accountJson().put("id", EntryFixtures.uuid(7)));
    assertThat(refusal(other, TOKEN)).isEqualTo("account_mismatch");

    final FakeEntry disabled = new FakeEntry();
    disabled.account = json(200, EntryFixtures.accountJson().put("disabled", true));
    assertThat(refusal(disabled, TOKEN)).isEqualTo("inactive_account");

    final FakeEntry unverified = new FakeEntry();
    unverified.account = json(200, EntryFixtures.accountJson().put("verified", false));
    assertThat(refusal(unverified, TOKEN)).isEqualTo("inactive_account");

    final FakeEntry noPermissions = new FakeEntry();
    noPermissions.account = json(200, EntryFixtures.accountJson().put("api", new JSONObject()));
    assertThat(refusal(noPermissions, TOKEN)).isEqualTo("inactive_account");
  }
}
