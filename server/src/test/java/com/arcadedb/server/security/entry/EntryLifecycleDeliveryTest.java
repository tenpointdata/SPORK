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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of entry-lifecycle {@code parseDelivery.ts} and entry-contracts {@code ValidateLifecycleRevocations.ts}. */
class EntryLifecycleDeliveryTest {

  private static void assertInvalid(final JSONObject raw) {
    assertThatThrownBy(() -> EntryLifecycleDelivery.parse(raw)).isInstanceOf(EntryLifecycleException.class)
        .extracting(e -> ((EntryLifecycleException) e).getCode()).isEqualTo("invalid_delivery");
  }

  @Test
  void parsesAnAccountDisableWithSortedPathsAndRevocations() {
    final JSONObject raw = EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false);
    raw.put("requiredPaths", new JSONArray().put("session").put("pat"));

    final EntryLifecycleDelivery delivery = EntryLifecycleDelivery.parse(raw);
    assertThat(delivery.getScope()).isEqualTo("account");
    assertThat(delivery.getOrgId()).isNull();
    assertThat(delivery.getDesiredState()).isEqualTo("disabled");
    assertThat(delivery.getSequence()).isEqualTo(3);
    assertThat(delivery.getRequiredPaths()).containsExactly("pat", "session");
    assertThat(delivery.getRevocationSequence("session")).isEqualTo(3);
    assertThat(delivery.getRevocationSequence("mcp")).isZero();
  }

  @Test
  void parsesAnEnterpriseSuspension() {
    final EntryLifecycleDelivery delivery = EntryLifecycleDelivery.parse(EntryFixtures.enterpriseDelivery(EntryFixtures.uuid(1), 2, false));
    assertThat(delivery.getScope()).isEqualTo("enterprise-person");
    assertThat(delivery.getOrgId()).isEqualTo(EntryFixtures.ORG_ID);
    assertThat(delivery.getDesiredState()).isEqualTo("suspended");
  }

  @Test
  void refusesAnythingOutsideTheVersionedContract() {
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("schemaVersion", 1));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("recreateGrants", true));
    assertInvalid(EntryFixtures.accountDelivery("not-a-uuid", 3, false));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 0, true));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("serviceId", "bad service"));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("requiredPaths", new JSONArray().put("pat").put("pat")));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("requiredPaths", new JSONArray().put("email")));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("orgId", EntryFixtures.ORG_ID));
    assertInvalid(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("desiredState", "suspended"));
    assertInvalid(EntryFixtures.enterpriseDelivery(EntryFixtures.uuid(1), 3, false).put("orgId", "acme"));
  }

  @Test
  void aRestrictiveStateMustRevokeEveryRequiredPathAtItsOwnSequence() {
    // Disabled, but the session revocation is from an earlier event: the restriction is not complete.
    final JSONObject partial = EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false);
    partial.put("revocations", new JSONArray().put(new JSONObject().put("path", "pat").put("sequence", 3))
        .put(new JSONObject().put("path", "session").put("sequence", 2)));
    assertInvalid(partial);

    // A revocation can never be newer than its delivery, nor name a path the delivery does not require.
    final JSONObject future = EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, true);
    future.put("revocations", new JSONArray().put(new JSONObject().put("path", "pat").put("sequence", 4)));
    assertInvalid(future);
    final JSONObject foreign = EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, true);
    foreign.put("revocations", new JSONArray().put(new JSONObject().put("path", "mcp").put("sequence", 1)));
    assertInvalid(foreign);

    // Active standing may carry earlier, already-applied revocations.
    final JSONObject restore = EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 5, true);
    restore.put("revocations", new JSONArray().put(new JSONObject().put("path", "session").put("sequence", 3)));
    assertThat(EntryLifecycleDelivery.parse(restore).getRevocationSequence("session")).isEqualTo(3);
  }

  @Test
  void thePayloadHashIgnoresFieldOrderAndPathOrder() {
    final JSONObject a = EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false);
    final JSONObject b = new JSONObject(a.toString()).put("requiredPaths", new JSONArray().put("pat").put("session"));
    assertThat(EntryLifecycleDelivery.parse(a).payloadHash()).isEqualTo(EntryLifecycleDelivery.parse(b).payloadHash());
    assertThat(EntryLifecycleDelivery.parse(a).payloadHash())
        .isNotEqualTo(EntryLifecycleDelivery.parse(new JSONObject(a.toString()).put("bindingRevision", 2)).payloadHash());
  }
}
