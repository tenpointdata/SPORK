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
import com.arcadedb.index.IndexCursor;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.audit.CanonicalJson;

/**
 * Entry standing as SPORK last received it, one {@value #STATE_TYPE} document per Entry lifecycle stream (environment,
 * service, scope, organization, account) and one {@value #RECEIPT_TYPE} per delivery. Both live in an ordinary database, so
 * they replicate with it and every node enforces the same standing; the deny check is a fresh indexed read, never cached.
 */
public final class EntryLifecycleStore {
  public static final String STATE_TYPE   = "EntryLifecycleState";
  public static final String RECEIPT_TYPE = "EntryLifecycleReceipt";

  /** Revocation sequences per credential path, in {@link EntryLifecycleDelivery#PATHS} order. */
  static final String[] REVOKED = { "revokedMcp", "revokedPat", "revokedSession" };

  /**
   * @param denied                    the account is disabled, or suspended in the configured enterprise
   * @param sessionRevocationSequence the account stream's latest session revocation; a proof below it is stale
   */
  public record Standing(boolean denied, long sessionRevocationSequence) {
  }

  private static final Standing UNKNOWN = new Standing(false, 0L);

  private final EntryConfiguration config;

  public EntryLifecycleStore(final EntryConfiguration config) {
    this.config = config;
  }

  /** Schema changes cannot run inside a transaction, so call this before one. */
  public void ensureSchema(final Database database) {
    final Schema schema = database.getSchema();
    if (schema.existsType(STATE_TYPE) && schema.existsType(RECEIPT_TYPE))
      return;

    final DocumentType state = schema.getOrCreateDocumentType(STATE_TYPE);
    for (final String name : new String[] { "streamKey", "environmentId", "serviceId", "scope", "orgId", "userId", "consumerSubjectId",
        "consumerContextId", "bindingId", "eventId", "desiredState" })
      state.getOrCreateProperty(name, Type.STRING);
    for (final String name : new String[] { "bindingRevision", "sequence", "updatedAt", REVOKED[0], REVOKED[1], REVOKED[2] })
      state.getOrCreateProperty(name, Type.LONG);
    schema.getOrCreateTypeIndex(Schema.INDEX_TYPE.HASH, true, STATE_TYPE, "streamKey");

    final DocumentType receipt = schema.getOrCreateDocumentType(RECEIPT_TYPE);
    for (final String name : new String[] { "deliveryKey", "payloadHash", "acknowledgement" })
      receipt.getOrCreateProperty(name, Type.STRING);
    receipt.getOrCreateProperty("auditSeq", Type.LONG);
    receipt.getOrCreateProperty("receivedAt", Type.LONG);
    schema.getOrCreateTypeIndex(Schema.INDEX_TYPE.HASH, true, RECEIPT_TYPE, "deliveryKey");
  }

  /** Two point lookups: the account stream, then the configured enterprise's stream. Nothing received means not denied. */
  public Standing standing(final Database database, final String userId) {
    if (!database.getSchema().existsType(STATE_TYPE))
      return UNKNOWN;

    final Document account = findState(database,
        streamKey(config.getEnvironmentId(), config.getServiceId(), "account", null, userId));
    final long fence = account != null ? account.getLong(REVOKED[2]) : 0L;
    boolean denied = account != null && "disabled".equals(account.getString("desiredState"));
    if (!denied && config.getEnterpriseOrgId() != null) {
      final Document enterprise = findState(database,
          streamKey(config.getEnvironmentId(), config.getServiceId(), "enterprise-person", config.getEnterpriseOrgId(), userId));
      denied = enterprise != null && "suspended".equals(enterprise.getString("desiredState"));
    }
    return denied || fence > 0 ? new Standing(denied, fence) : UNKNOWN;
  }

  Document findState(final Database database, final String streamKey) {
    return first(database.lookupByKey(STATE_TYPE, "streamKey", streamKey));
  }

  Document findReceipt(final Database database, final String deliveryKey) {
    return first(database.lookupByKey(RECEIPT_TYPE, "deliveryKey", deliveryKey));
  }

  /** As entry-lifecycle {@code lifecycleStreamKey}: one stream per environment, service, scope, organization and account. */
  public static String streamKey(final String environmentId, final String serviceId, final String scope, final String orgId,
      final String userId) {
    return EntryConfiguration.sha256Hex(CanonicalJson.write(
        new JSONArray().put(environmentId).put(serviceId).put(scope).put(orgId != null ? orgId : JSONObject.NULL).put(userId)));
  }

  static String deliveryKey(final String environmentId, final String serviceId, final String deliveryId) {
    return EntryConfiguration.sha256Hex(CanonicalJson.write(new JSONArray().put(environmentId).put(serviceId).put(deliveryId)));
  }

  private static Document first(final IndexCursor cursor) {
    return cursor.hasNext() ? cursor.next().asDocument() : null;
  }
}
