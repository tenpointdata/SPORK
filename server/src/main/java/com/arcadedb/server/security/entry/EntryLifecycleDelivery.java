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
import com.arcadedb.server.audit.CanonicalJson;

import java.util.Arrays;

/**
 * Entry's {@code TLifecycleDelivery} v2, parsed by the rules of entry-lifecycle {@code parseDelivery.ts} and entry-contracts
 * {@code ValidateLifecycleRevocations.ts}: exact version, credential paths drawn from {@code session}, {@code pat},
 * {@code mcp}, revocations never newer than the delivery, and a restrictive state that revokes every required path at its own
 * sequence. Paths and revocations are kept sorted, so a payload's hash does not depend on the order Entry sent them in.
 */
public final class EntryLifecycleDelivery {
  /** Indexed by {@link #pathIndex}: the order Entry sorts paths in. */
  static final String[] PATHS = { "mcp", "pat", "session" };

  private final String   environmentId;
  private final String   serviceId;
  private final String   scope;
  private final String   orgId;
  private final String   desiredState;
  private final String   deliveryId;
  private final String   eventId;
  private final String   bindingId;
  private final int      bindingRevision;
  private final String   userId;
  private final String   consumerSubjectId;
  private final String   consumerContextId;
  private final int      sequence;
  private final String[] requiredPaths;
  /** The revocation sequence per {@link #PATHS} entry, 0 when the delivery carries none for that path. */
  private final int[]    revocations = new int[PATHS.length];

  private EntryLifecycleDelivery(final JSONObject row) {
    if (!(row.get("schemaVersion", null) instanceof Number version) || integral(version) != 2
        || !Boolean.FALSE.equals(row.get("recreateGrants", null)))
      throw invalid();
    environmentId = consumerId(row, "environmentId");
    serviceId = row.getString("serviceId", "");
    if (!EntryConfiguration.INSTALLATION.matcher(serviceId).matches())
      throw invalid();

    if (!(row.get("requiredPaths", null) instanceof JSONArray paths) || paths.length() < 1 || paths.length() > PATHS.length)
      throw invalid();
    requiredPaths = new String[paths.length()];
    for (int i = 0; i < requiredPaths.length; i++) {
      final int index = pathIndex(paths.get(i));
      if (index < 0 || Arrays.asList(requiredPaths).contains(PATHS[index]))
        throw invalid();
      requiredPaths[i] = PATHS[index];
    }
    Arrays.sort(requiredPaths);

    sequence = positiveInt(row, "sequence");
    if (!(row.get("revocations", null) instanceof JSONArray rows) || rows.length() > PATHS.length)
      throw invalid();
    for (int i = 0; i < rows.length(); i++) {
      if (!(rows.get(i) instanceof JSONObject revocation))
        throw invalid();
      final int index = pathIndex(revocation.get("path", null));
      if (index < 0 || !isRequired(PATHS[index]) || revocations[index] != 0)
        throw invalid();
      final long revoked = revocation.get("sequence", null) instanceof Number n ? integral(n) : -1L;
      if (revoked < 1 || revoked > sequence)
        throw invalid();
      revocations[index] = (int) revoked;
    }

    desiredState = row.getString("desiredState", "");
    if (!"active".equals(desiredState))
      for (final String path : requiredPaths)
        if (revocations[pathIndex(path)] != sequence)
          throw invalid();

    deliveryId = uuid(row, "deliveryId");
    eventId = uuid(row, "eventId");
    bindingId = uuid(row, "bindingId");
    userId = uuid(row, "userId");
    bindingRevision = positiveInt(row, "bindingRevision");
    consumerSubjectId = consumerId(row, "consumerSubjectId");
    consumerContextId = consumerId(row, "consumerContextId");

    scope = row.getString("scope", "");
    if ("account".equals(scope)) {
      if (!row.has("orgId") || !row.isNull("orgId") || !("active".equals(desiredState) || "disabled".equals(desiredState)))
        throw invalid();
      orgId = null;
    } else if ("enterprise-person".equals(scope)) {
      if (!("active".equals(desiredState) || "suspended".equals(desiredState)))
        throw invalid();
      orgId = uuid(row, "orgId");
    } else
      throw invalid();
  }

  public static EntryLifecycleDelivery parse(final JSONObject raw) {
    if (raw == null)
      throw invalid();
    try {
      return new EntryLifecycleDelivery(raw);
    } catch (final EntryLifecycleException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw invalid();
    }
  }

  /** The delivery as Entry defines it, with sorted paths and revocations. */
  public JSONObject toJSON() {
    return new JSONObject()//
        .put("schemaVersion", 2)//
        .put("deliveryId", deliveryId)//
        .put("eventId", eventId)//
        .put("environmentId", environmentId)//
        .put("bindingId", bindingId)//
        .put("bindingRevision", bindingRevision)//
        .put("serviceId", serviceId)//
        .put("userId", userId)//
        .put("consumerSubjectId", consumerSubjectId)//
        .put("consumerContextId", consumerContextId)//
        .put("sequence", sequence)//
        .put("requiredPaths", new JSONArray(requiredPaths))//
        .put("revocations", revocationsJSON())//
        .put("recreateGrants", false)//
        .put("scope", scope)//
        .put("orgId", orgId != null ? orgId : JSONObject.NULL)//
        .put("desiredState", desiredState);
  }

  /** Identifies the exact intent, so a replay of the same delivery id with another payload is told apart from a retry. */
  public String payloadHash() {
    return EntryConfiguration.sha256Hex(CanonicalJson.write(toJSON()));
  }

  public JSONArray revocationsJSON() {
    final JSONArray array = new JSONArray();
    for (int i = 0; i < PATHS.length; i++)
      if (revocations[i] > 0)
        array.put(new JSONObject().put("path", PATHS[i]).put("sequence", revocations[i]));
    return array;
  }

  public int getRevocationSequence(final String path) {
    final int index = pathIndex(path);
    return index >= 0 ? revocations[index] : 0;
  }

  public boolean isRequired(final String path) {
    for (final String required : requiredPaths)
      if (required != null && required.equals(path))
        return true;
    return false;
  }

  static int pathIndex(final Object path) {
    for (int i = 0; i < PATHS.length; i++)
      if (PATHS[i].equals(path))
        return i;
    return -1;
  }

  public String getEnvironmentId() {
    return environmentId;
  }

  public String getServiceId() {
    return serviceId;
  }

  public String getScope() {
    return scope;
  }

  public String getOrgId() {
    return orgId;
  }

  public String getDesiredState() {
    return desiredState;
  }

  public String getDeliveryId() {
    return deliveryId;
  }

  public String getEventId() {
    return eventId;
  }

  public String getBindingId() {
    return bindingId;
  }

  public int getBindingRevision() {
    return bindingRevision;
  }

  public String getUserId() {
    return userId;
  }

  public String getConsumerSubjectId() {
    return consumerSubjectId;
  }

  public String getConsumerContextId() {
    return consumerContextId;
  }

  public int getSequence() {
    return sequence;
  }

  public String[] getRequiredPaths() {
    return requiredPaths.clone();
  }

  private static EntryLifecycleException invalid() {
    return new EntryLifecycleException("invalid_delivery");
  }

  private static String uuid(final JSONObject row, final String key) {
    final String value = row.getString(key, null);
    if (value == null || !EntryConfiguration.UUID.matcher(value).matches())
      throw invalid();
    return value;
  }

  private static String consumerId(final JSONObject row, final String key) {
    final Object value = row.get(key, null);
    if (!(value instanceof String text) || !EntryConfiguration.isIdentifier(text))
      throw invalid();
    return text;
  }

  private static int positiveInt(final JSONObject row, final String key) {
    final long value = row.get(key, null) instanceof Number n ? integral(n) : -1L;
    if (value < 1 || value > Integer.MAX_VALUE)
      throw invalid();
    return (int) value;
  }

  private static long integral(final Number n) {
    final double d = n.doubleValue();
    return Double.isFinite(d) && d == Math.rint(d) ? n.longValue() : -1L;
  }
}
