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
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.audit.AuditActor;
import com.arcadedb.server.audit.AuditChain;
import com.arcadedb.server.audit.AuditEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SPORK's side of Entry's lifecycle protocol, the Java counterpart of entry-lifecycle {@code receiver.ts}. One delivery is one
 * transaction: the stream's state, the {@code entry.lifecycle} audit entry and the receipt commit together or not at all, so
 * an acknowledgement Entry receives always names an enforced, audited change.
 * <p>
 * SPORK keeps no local accounts, so where Roca resolves an operator-reviewed mapping this receiver requires identity: the
 * consumer subject is the Entry account itself and an enterprise context is the Entry organization.
 */
public final class EntryLifecycleReceiver {
  public static final  String ACTION = "entry.lifecycle";
  private static final String ACTOR  = "system:entry-lifecycle";

  private final EntryConfiguration config;
  private final EntryLifecycleStore store;
  private final AuditChain         chain;
  private final Consumer<String>   onStandingChange;

  /** @param onStandingChange told the Entry account id after each applied delivery commits; evicts cached verdicts */
  public EntryLifecycleReceiver(final EntryConfiguration config, final EntryLifecycleStore store, final AuditChain chain,
      final Consumer<String> onStandingChange) {
    this.config = config;
    this.store = store;
    this.chain = chain;
    this.onStandingChange = onStandingChange;
  }

  /** @return Entry's {@code TLifecycleAcknowledgement} for the delivery, the stored one for a replay */
  public JSONObject receive(final Database database, final JSONObject raw) {
    final EntryLifecycleDelivery delivery = EntryLifecycleDelivery.parse(raw);
    if (!config.getEnvironmentId().equals(delivery.getEnvironmentId()) || !config.getServiceId().equals(delivery.getServiceId()))
      throw new EntryLifecycleException("wrong_consumer");
    final boolean enterprise = "enterprise-person".equals(delivery.getScope());
    if (enterprise && !delivery.getOrgId().equals(config.getEnterpriseOrgId()))
      throw new EntryLifecycleException("unsupported_scope");
    if (!delivery.getUserId().equals(delivery.getConsumerSubjectId())
        || (enterprise && !delivery.getOrgId().equals(delivery.getConsumerContextId())))
      throw new EntryLifecycleException("binding_unreviewed");

    store.ensureSchema(database);
    AuditChain.ensureSchema(database);

    final String streamKey = EntryLifecycleStore.streamKey(delivery.getEnvironmentId(), delivery.getServiceId(), delivery.getScope(),
        delivery.getOrgId(), delivery.getUserId());
    final String deliveryKey = EntryLifecycleStore.deliveryKey(delivery.getEnvironmentId(), delivery.getServiceId(),
        delivery.getDeliveryId());
    final String payloadHash = delivery.payloadHash();
    final JSONObject[] acknowledgement = new JSONObject[1];
    final boolean[] applied = new boolean[1];

    database.transaction(() -> {
      applied[0] = false;
      final Document receipt = store.findReceipt(database, deliveryKey);
      if (receipt != null) {
        if (!payloadHash.equals(receipt.getString("payloadHash")))
          throw new EntryLifecycleException("delivery_conflict");
        acknowledgement[0] = new JSONObject(receipt.getString("acknowledgement"));
        return;
      }

      final Document cursor = store.findState(database, streamKey);
      if (cursor != null) {
        if (!delivery.getBindingId().equals(cursor.getString("bindingId"))
            || !delivery.getConsumerSubjectId().equals(cursor.getString("consumerSubjectId"))
            || !delivery.getConsumerContextId().equals(cursor.getString("consumerContextId"))
            || cursor.getLong("bindingRevision") > delivery.getBindingRevision())
          throw new EntryLifecycleException("binding_changed");
        if (cursor.getLong("sequence") >= delivery.getSequence())
          throw new EntryLifecycleException("stale_event");
      }

      // entry-lifecycle planCredentialRetirement: catch up every outstanding revocation, never move one backwards.
      final long[] revoked = new long[EntryLifecycleDelivery.PATHS.length];
      if (cursor != null)
        for (int i = 0; i < revoked.length; i++)
          revoked[i] = cursor.getLong(EntryLifecycleStore.REVOKED[i]);
      final List<String> retired = new ArrayList<>(3);
      for (final String path : delivery.getRequiredPaths()) {
        final int index = EntryLifecycleDelivery.pathIndex(path);
        final long incoming = delivery.getRevocationSequence(path);
        if (incoming < revoked[index])
          throw new EntryLifecycleException("revocation_regressed");
        if (incoming > revoked[index]) {
          revoked[index] = incoming;
          retired.add(path);
        }
      }

      final MutableDocument state = cursor != null ? cursor.modify() : database.newDocument(EntryLifecycleStore.STATE_TYPE)//
          .set("streamKey", streamKey)//
          .set("environmentId", delivery.getEnvironmentId())//
          .set("serviceId", delivery.getServiceId())//
          .set("scope", delivery.getScope())//
          .set("orgId", delivery.getOrgId())//
          .set("userId", delivery.getUserId())//
          .set("consumerSubjectId", delivery.getConsumerSubjectId())//
          .set("consumerContextId", delivery.getConsumerContextId())//
          .set("bindingId", delivery.getBindingId());
      state.set("bindingRevision", (long) delivery.getBindingRevision());
      state.set("sequence", (long) delivery.getSequence());
      state.set("eventId", delivery.getEventId());
      state.set("desiredState", delivery.getDesiredState());
      for (int i = 0; i < revoked.length; i++)
        state.set(EntryLifecycleStore.REVOKED[i], revoked[i]);
      state.set("updatedAt", System.currentTimeMillis());
      state.save();

      final JSONObject detail = new JSONObject()//
          .put("scope", delivery.getScope())//
          .put("desiredState", delivery.getDesiredState())//
          .put("sequence", delivery.getSequence())//
          .put("eventId", delivery.getEventId())//
          .put("deliveryId", delivery.getDeliveryId())//
          .put("bindingRevision", delivery.getBindingRevision())//
          .put("retired", String.join(",", retired));
      if (delivery.getOrgId() != null)
        detail.put("orgId", delivery.getOrgId());
      final JSONObject entry = chain.append(database,
          new AuditEvent(AuditActor.system(ACTOR, delivery.getUserId()), ACTION).subject(delivery.getUserId()).detail(detail));

      final JSONObject ack = acknowledgement(delivery);
      database.newDocument(EntryLifecycleStore.RECEIPT_TYPE)//
          .set("deliveryKey", deliveryKey)//
          .set("payloadHash", payloadHash)//
          .set("acknowledgement", ack.toString())//
          .set("auditSeq", entry.getLong("seq"))//
          .set("receivedAt", System.currentTimeMillis())//
          .save();
      acknowledgement[0] = ack;
      applied[0] = true;
    }, false, 3);

    if (applied[0])
      onStandingChange.accept(delivery.getUserId());
    return acknowledgement[0];
  }

  /** {@code TLifecycleAcknowledgement}: every required path applied, nothing recreated. */
  private static JSONObject acknowledgement(final EntryLifecycleDelivery delivery) {
    return new JSONObject()//
        .put("schemaVersion", 2)//
        .put("revocations", delivery.revocationsJSON())//
        .put("deliveryId", delivery.getDeliveryId())//
        .put("eventId", delivery.getEventId())//
        .put("bindingId", delivery.getBindingId())//
        .put("bindingRevision", delivery.getBindingRevision())//
        .put("sequence", delivery.getSequence())//
        .put("desiredState", delivery.getDesiredState())//
        .put("consumerSubjectId", delivery.getConsumerSubjectId())//
        .put("consumerContextId", delivery.getConsumerContextId())//
        .put("consumerReceiptId", UUID.randomUUID().toString())//
        .put("appliedPaths", new JSONArray(delivery.getRequiredPaths()))//
        .put("grantsRecreated", false);
  }
}
