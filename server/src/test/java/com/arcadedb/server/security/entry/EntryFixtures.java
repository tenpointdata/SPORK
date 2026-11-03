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

/**
 * Synthetic Entry identities and payloads shaped exactly like Entry's contracts (entry-contracts
 * {@code TConsumerSessionProof}, {@code TLifecycleDelivery}).
 */
final class EntryFixtures {
  static final String ENVIRONMENT        = "test:http://localhost:3002";
  static final String SERVICE            = "spork";
  static final String USER_ID            = "0b7c2f1e-5a4d-4c3b-9e8f-112233445566";
  static final String ORG_ID             = "6f1d0c2a-3b4c-4d5e-8f90-a1b2c3d4e5f6";
  static final String BINDING_ID         = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  static final String CREDENTIAL_ID      = "c".repeat(64);
  static final String LIFECYCLE_SECRET   = "lifecycle-installation-credential-0123456789abcdef";
  static final long   NOW                = 1_793_707_200_000L; // 2026-11-03T12:00:00.000Z

  private EntryFixtures() {
  }

  static JSONObject configJson() {
    return new JSONObject()//
        .put("origin", "http://localhost:3002")//
        .put("environmentId", ENVIRONMENT)//
        .put("serviceId", SERVICE)//
        .put("enterpriseOrgId", ORG_ID)//
        .put("database", "identity")//
        .put("lifecycleCredentialSha256", EntryConfiguration.sha256Hex(LIFECYCLE_SECRET))//
        .put("grants", new JSONObject()//
            .put("spork.console", new JSONObject().put("*", new JSONArray().put("console")))//
            .put("spork.ops", new JSONObject().put("identity", new JSONArray().put("ops")).put("recdir", new JSONArray().put("ops"))));
  }

  static EntryConfiguration config() {
    return EntryConfiguration.parse(configJson());
  }

  static EntryPrincipal principal(final long accountSequence, final String... permissions) {
    return new EntryPrincipal(ENVIRONMENT, USER_ID, "google-oauth2|1234", CREDENTIAL_ID, accountSequence, "analyst@example.org",
        permissions, NOW + 3_600_000L);
  }

  static JSONObject proofJson() {
    return new JSONObject()//
        .put("schemaVersion", 1)//
        .put("environmentId", ENVIRONMENT)//
        .put("sourceUserId", USER_ID)//
        .put("identityId", "google-oauth2|1234")//
        .put("accountSequence", 4)//
        .put("credentialId", CREDENTIAL_ID)//
        .put("expiresAt", "2026-11-03T20:00:00.000Z")//
        .put("checkedAt", "2026-11-03T12:00:00.000Z");
  }

  static JSONObject accountJson() {
    return new JSONObject()//
        .put("id", USER_ID)//
        .put("email", "analyst@example.org")//
        .put("disabled", false)//
        .put("verified", true)//
        .put("api", new JSONObject().put("permissions", new JSONArray().put("spork.console").put("other.app")));
  }

  /** An account-scope delivery; {@code active=false} carries the revocations a disable must carry. */
  static JSONObject accountDelivery(final String deliveryId, final int sequence, final boolean active) {
    final JSONArray revocations = new JSONArray();
    if (!active)
      revocations.put(new JSONObject().put("path", "pat").put("sequence", sequence))
          .put(new JSONObject().put("path", "session").put("sequence", sequence));
    return delivery(deliveryId, sequence).put("scope", "account").put("orgId", JSONObject.NULL)
        .put("desiredState", active ? "active" : "disabled").put("consumerContextId", "spork").put("revocations", revocations);
  }

  static JSONObject enterpriseDelivery(final String deliveryId, final int sequence, final boolean active) {
    final JSONArray revocations = new JSONArray();
    if (!active)
      revocations.put(new JSONObject().put("path", "pat").put("sequence", sequence))
          .put(new JSONObject().put("path", "session").put("sequence", sequence));
    return delivery(deliveryId, sequence).put("scope", "enterprise-person").put("orgId", ORG_ID)
        .put("desiredState", active ? "active" : "suspended").put("consumerContextId", ORG_ID).put("revocations", revocations);
  }

  static String uuid(final int n) {
    return String.format("00000000-0000-4000-8000-%012d", n);
  }

  private static JSONObject delivery(final String deliveryId, final int sequence) {
    return new JSONObject()//
        .put("schemaVersion", 2)//
        .put("deliveryId", deliveryId)//
        .put("eventId", uuid(1000 + sequence))//
        .put("environmentId", ENVIRONMENT)//
        .put("bindingId", BINDING_ID)//
        .put("bindingRevision", 1)//
        .put("serviceId", SERVICE)//
        .put("userId", USER_ID)//
        .put("consumerSubjectId", USER_ID)//
        .put("sequence", sequence)//
        .put("requiredPaths", new JSONArray().put("session").put("pat"))//
        .put("recreateGrants", false);
  }
}
