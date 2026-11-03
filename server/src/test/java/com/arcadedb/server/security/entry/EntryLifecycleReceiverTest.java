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
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.audit.AuditChain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntryLifecycleReceiverTest extends TestHelper {
  private final List<String>          standingChanges = new ArrayList<>();
  private final EntryLifecycleStore   store           = new EntryLifecycleStore(EntryFixtures.config());
  private final EntryLifecycleReceiver receiver       = new EntryLifecycleReceiver(EntryFixtures.config(), store,
      new AuditChain(() -> EntryFixtures.NOW), standingChanges::add);

  private String refusal(final JSONObject raw) {
    try {
      receiver.receive(database, raw);
    } catch (final EntryLifecycleException e) {
      return e.getCode();
    }
    throw new AssertionError("expected a refusal");
  }

  private List<Result> auditEntries() {
    final List<Result> entries = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", "SELECT FROM AuditEntry ORDER BY seq")) {
      rs.forEachRemaining(entries::add);
    }
    return entries;
  }

  @Test
  void aDisableDeniesThePersonAndIsAuditedWithItsReceipt() {
    final JSONObject ack = receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));

    assertThat(ack.getInt("schemaVersion")).isEqualTo(2);
    assertThat(ack.getString("deliveryId")).isEqualTo(EntryFixtures.uuid(1));
    assertThat(ack.getInt("sequence")).isEqualTo(3);
    assertThat(ack.getString("desiredState")).isEqualTo("disabled");
    assertThat(ack.getString("consumerSubjectId")).isEqualTo(EntryFixtures.USER_ID);
    assertThat(ack.getJSONArray("appliedPaths").toList()).containsExactly("pat", "session");
    assertThat(ack.getJSONArray("revocations").length()).isEqualTo(2);
    assertThat(ack.getBoolean("grantsRecreated")).isFalse();
    assertThat(ack.getString("consumerReceiptId")).matches("[A-Za-z0-9_.:-]{1,128}");

    final EntryLifecycleStore.Standing standing = store.standing(database, EntryFixtures.USER_ID);
    assertThat(standing.denied()).isTrue();
    assertThat(standing.sessionRevocationSequence()).isEqualTo(3L);
    assertThat(standingChanges).containsExactly(EntryFixtures.USER_ID);

    final List<Result> audit = auditEntries();
    assertThat(audit).hasSize(1);
    assertThat((String) audit.get(0).getProperty("action")).isEqualTo("entry.lifecycle");
    assertThat((String) audit.get(0).getProperty("actor")).isEqualTo("system:entry-lifecycle");
    assertThat((String) audit.get(0).getProperty("subject")).isEqualTo(EntryFixtures.USER_ID);
    final JSONObject body = new JSONObject((String) audit.get(0).getProperty("body"));
    assertThat(body.getString("onBehalfOf")).isEqualTo(EntryFixtures.USER_ID);
    assertThat(body.getJSONObject("detail").getString("desiredState")).isEqualTo("disabled");
    assertThat(body.getJSONObject("detail").getString("retired")).isEqualTo("pat,session");
    assertThat(AuditChain.verify(database).ok()).isTrue();
  }

  @Test
  void aReplayReturnsTheSameReceiptAndChangesNothing() {
    final JSONObject first = receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));
    final JSONObject replay = receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));

    assertThat(replay.toString()).isEqualTo(first.toString());
    assertThat(auditEntries()).hasSize(1);
  }

  @Test
  void aReplayWithAnotherPayloadIsAConflict() {
    receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("bindingRevision", 2)))
        .isEqualTo("delivery_conflict");
  }

  @Test
  void sequencesOnlyMoveForward() {
    receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(2), 3, true))).isEqualTo("stale_event");
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(3), 2, true))).isEqualTo("stale_event");
  }

  @Test
  void restoringLiftsTheDenyButKeepsTheRevocationFence() {
    receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));
    final JSONObject restore = EntryFixtures.accountDelivery(EntryFixtures.uuid(2), 5, true);
    restore.put("revocations", new JSONArray().put(new JSONObject().put("path", "pat").put("sequence", 3))
        .put(new JSONObject().put("path", "session").put("sequence", 3)));
    final JSONObject ack = receiver.receive(database, restore);

    assertThat(ack.getString("desiredState")).isEqualTo("active");
    assertThat(ack.getBoolean("grantsRecreated")).isFalse();
    final EntryLifecycleStore.Standing standing = store.standing(database, EntryFixtures.USER_ID);
    assertThat(standing.denied()).isFalse();
    assertThat(standing.sessionRevocationSequence()).isEqualTo(3L);
    assertThat(auditEntries()).hasSize(2);
  }

  @Test
  void aMissedRestrictionIsCaughtUpBeforeActiveStanding() {
    // The disable at sequence 3 never arrived; the restore at 5 carries it as an outstanding revocation.
    final JSONObject restore = EntryFixtures.accountDelivery(EntryFixtures.uuid(2), 5, true);
    restore.put("revocations", new JSONArray().put(new JSONObject().put("path", "session").put("sequence", 3)));
    receiver.receive(database, restore);

    assertThat(store.standing(database, EntryFixtures.USER_ID).sessionRevocationSequence()).isEqualTo(3L);
    assertThat(standingChanges).containsExactly(EntryFixtures.USER_ID);
  }

  @Test
  void revocationsCannotGoBack() {
    receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false));
    final JSONObject regress = EntryFixtures.accountDelivery(EntryFixtures.uuid(2), 5, true);
    regress.put("revocations", new JSONArray().put(new JSONObject().put("path", "session").put("sequence", 2)));
    assertThat(refusal(regress)).isEqualTo("revocation_regressed");
  }

  @Test
  void theStreamKeepsItsBindingAndItsRevisionNeverGoesBack() {
    receiver.receive(database, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("bindingRevision", 2));
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(2), 4, true).put("bindingRevision", 1)))
        .isEqualTo("binding_changed");
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(3), 4, true).put("bindingRevision", 2)
        .put("bindingId", EntryFixtures.uuid(9)))).isEqualTo("binding_changed");
  }

  @Test
  void theSubjectMustBeTheEntryAccountItself() {
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("consumerSubjectId", "local-42")))
        .isEqualTo("binding_unreviewed");
    assertThat(refusal(EntryFixtures.enterpriseDelivery(EntryFixtures.uuid(2), 3, false).put("consumerContextId", "spork")))
        .isEqualTo("binding_unreviewed");
    assertThat(AuditChain.verify(database).entries()).isZero();
  }

  @Test
  void deliveriesForAnotherInstallationOrOrganisationAreRefused() {
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("serviceId", "roca")))
        .isEqualTo("wrong_consumer");
    assertThat(refusal(EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false).put("environmentId", "prod")))
        .isEqualTo("wrong_consumer");
    final String otherOrg = EntryFixtures.uuid(77);
    assertThat(refusal(EntryFixtures.enterpriseDelivery(EntryFixtures.uuid(2), 3, false).put("orgId", otherOrg)
        .put("consumerContextId", otherOrg))).isEqualTo("unsupported_scope");
  }

  @Test
  void anEnterpriseSuspensionDeniesThePersonInThatOrganisationOnly() {
    receiver.receive(database, EntryFixtures.enterpriseDelivery(EntryFixtures.uuid(1), 2, false));
    assertThat(store.standing(database, EntryFixtures.USER_ID).denied()).isTrue();
    // The account stream is independent: its fence is untouched by an enterprise-scope revocation.
    assertThat(store.standing(database, EntryFixtures.USER_ID).sessionRevocationSequence()).isZero();

    // Entry carries the satisfied restrictions forward on every later delivery of the stream.
    final JSONObject restore = EntryFixtures.enterpriseDelivery(EntryFixtures.uuid(2), 3, true);
    restore.put("revocations", new JSONArray().put(new JSONObject().put("path", "pat").put("sequence", 2))
        .put(new JSONObject().put("path", "session").put("sequence", 2)));
    receiver.receive(database, restore);
    assertThat(store.standing(database, EntryFixtures.USER_ID).denied()).isFalse();
  }

  @Test
  void aPersonEntryNeverMentionedIsNotDenied() {
    assertThat(store.standing(database, EntryFixtures.uuid(5)).denied()).isFalse();
  }
}
