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
package com.arcadedb.query.selection;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.JsonSerializer;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON contract of the {@code /api/v1/selection/{database}/*} routes (docs/spork/03-f1-selections-design.md, 2.4),
 * kept in the engine so every surface (HTTP today, MCP or gRPC later) accepts and refuses exactly the same requests.
 * <p>
 * Every operation runs as a Cypher {@code CALL selection.*}, which is what makes it correct under HA with no routing of its
 * own: on a follower {@code RaftReplicatedDatabase.command} forwards a write-procedure call to the leader, and a read runs
 * locally under the request's read-consistency. A write opens a transaction only when the caller has none.
 * <p>
 * A refusal anywhere in the procedure's cause chain is rethrown as the {@link IllegalArgumentException} it was, so the
 * HTTP layer answers it 400 with the procedure's own message.
 */
public final class SelectionService {
  /** Names the bytes {@code get} exports and {@code save} accepts: the {@link com.arcadedb.utility.RidBitmap} envelope. */
  public static final String BITMAP_FORMAT = "ridbitmap-v1";

  private static final String HANDLE_FIELDS = "name, cardinality, createdAt, expiresAt";

  private SelectionService() {
  }

  public static JSONObject save(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "save");
    final Map<String, Object> params = new HashMap<>();
    params.put("name", requiredName(body, "save"));

    final int sources = (present(body, "members") ? 1 : 0) + (present(body, "query") ? 1 : 0) + (present(body, "bitmap") ? 1 : 0);
    if (sources != 1)
      throw new IllegalArgumentException("selection save: exactly one of 'members', 'query' or 'bitmap' is required");
    if (present(body, "members"))
      params.put("source", body.getJSONArray("members").toList());
    else if (present(body, "query"))
      params.put("source", body.getString("query"));
    else
      params.put("source", Map.of("bitmap", body.getString("bitmap")));

    params.put("options", options(body, "ttl", "replace", "language", "params"));
    final Result row = write(database, "CALL selection.save($name, $source, $options) YIELD " + HANDLE_FIELDS + " RETURN " + HANDLE_FIELDS,
        params).getFirst();
    return handle(row);
  }

  public static JSONObject combine(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "combine");
    final Map<String, Object> params = new HashMap<>();
    params.put("op", body.getString("op", null));
    params.put("inputs", present(body, "inputs") ? body.getJSONArray("inputs").toList() : null);
    params.put("name", requiredName(body, "combine"));
    params.put("options", options(body, "ttl", "replace"));
    final Result row = write(database,
        "CALL selection.combine($op, $inputs, $name, $options) YIELD " + HANDLE_FIELDS + " RETURN " + HANDLE_FIELDS, params).getFirst();
    return handle(row);
  }

  public static JSONObject compare(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "compare");
    final Map<String, Object> params = new HashMap<>();
    params.put("selections", selections(body));
    params.put("options", options(body, "via", "direction", "minShared", "limit", "memberCap"));

    final JsonSerializer serializer = serializer();
    final JSONArray rows = new JSONArray();
    for (final Result row : read(database, "CALL selection.compare($selections, $options) "
        + "YIELD node, mask, selections, sharedBy, counts, members RETURN node, mask, selections, sharedBy, counts, members", params)) {
      final JSONObject json = record(serializer, row.getProperty("node"));
      json.put("mask", row.<Number>getProperty("mask").intValue());
      json.put("selections", new JSONArray(row.<List<?>>getProperty("selections")));
      json.put("sharedBy", row.<Number>getProperty("sharedBy").intValue());
      final List<?> counts = row.getProperty("counts");
      if (counts != null) {
        json.put("counts", new JSONArray(counts));
        final JSONArray members = new JSONArray();
        for (final Object perSelection : row.<List<?>>getProperty("members"))
          members.put(ridStrings((List<?>) perSelection));
        json.put("members", members);
      }
      rows.put(json);
    }
    return new JSONObject().put("count", rows.length()).put("rows", rows);
  }

  public static JSONObject venn(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "venn");
    final Map<String, Object> params = new HashMap<>();
    params.put("selections", selections(body));
    params.put("options", options(body, "via", "direction"));

    final JSONArray regions = new JSONArray();
    for (final Result row : read(database,
        "CALL selection.venn($selections, $options) YIELD mask, selections, count RETURN mask, selections, count", params))
      regions.put(new JSONObject()
          .put("mask", row.<Number>getProperty("mask").intValue())
          .put("selections", new JSONArray(row.<List<?>>getProperty("selections")))
          .put("count", row.<Number>getProperty("count").longValue()));
    return new JSONObject().put("regions", regions);
  }

  public static JSONObject get(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "get");
    final String name = requiredName(body, "get");
    // Through the procedure first: the read-consistency of the request and the expiry check apply exactly as on any read
    final Result row = read(database, "CALL selection.get($name) YIELD name, cardinality, createdAt, expiresAt, source, language, "
        + "params, createdBy RETURN name, cardinality, createdAt, expiresAt, source, language, params, createdBy",
        Map.of("name", name)).getFirst();

    final JSONObject json = handle(row)
        .put("source", row.<String>getProperty("source"))
        .put("language", row.<String>getProperty("language"))
        .put("params", row.getProperty("params") instanceof Map<?, ?> map ? new JSONObject(map) : null)
        .put("createdBy", row.<String>getProperty("createdBy"));
    if (body.getBoolean("bitmap", false)) {
      final byte[] bytes = new SelectionStore(database, "selection.get").require(name).memberBytes();
      json.put("bitmapFormat", BITMAP_FORMAT).put("bitmap", Base64.getEncoder().encodeToString(bytes));
    }
    return json;
  }

  public static JSONObject members(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "members");
    final Map<String, Object> params = new HashMap<>();
    params.put("name", requiredName(body, "members"));
    params.put("options", options(body, "skip", "limit"));
    final boolean withProperties = body.getBoolean("properties", true);

    final JsonSerializer serializer = serializer();
    final JSONArray members = new JSONArray();
    for (final Result row : read(database, "CALL selection.members($name, $options) YIELD node RETURN node", params)) {
      final Object node = row.getProperty("node");
      members.put(withProperties ? record(serializer, node) : new JSONObject().put("rid", ((Identifiable) node).getIdentity().toString()));
    }
    return new JSONObject().put("count", members.length()).put("members", members);
  }

  public static JSONObject drop(final Database database, final JSONObject request) {
    final JSONObject body = body(request, "drop");
    final Result row = write(database, "CALL selection.drop($name) YIELD name, dropped RETURN name, dropped",
        Map.of("name", requiredName(body, "drop"))).getFirst();
    return new JSONObject().put("name", row.<String>getProperty("name")).put("dropped", row.<Boolean>getProperty("dropped"));
  }

  /** The one operation without a body: {@code request} may be null or empty. */
  public static JSONObject purge(final Database database, final JSONObject request) {
    final Result row = write(database, "CALL selection.purge() YIELD purged RETURN purged", Map.of()).getFirst();
    return new JSONObject().put("purged", row.<Number>getProperty("purged").longValue());
  }

  // ---- plumbing ----

  private static List<Result> write(final Database database, final String cypher, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try {
      if (database.isTransactionActive())
        collect(database.command("opencypher", cypher, params), rows);
      else
        database.transaction(() -> collect(database.command("opencypher", cypher, params), rows));
    } catch (final RuntimeException e) {
      throw unwrap(e);
    }
    return rows;
  }

  private static List<Result> read(final Database database, final String cypher, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try {
      collect(database.query("opencypher", cypher, params), rows);
    } catch (final RuntimeException e) {
      throw unwrap(e);
    }
    return rows;
  }

  private static void collect(final ResultSet resultSet, final List<Result> rows) {
    try (final ResultSet rs = resultSet) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
  }

  /** The first {@link IllegalArgumentException} of the cause chain, the procedure's own refusal; otherwise {@code e} itself. */
  static RuntimeException unwrap(final RuntimeException e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause())
      if (t instanceof IllegalArgumentException refusal)
        return refusal;
    return e;
  }

  private static JSONObject body(final JSONObject request, final String operation) {
    if (request == null || request.isEmpty())
      throw new IllegalArgumentException("selection " + operation + ": a request body is required");
    return request;
  }

  private static String requiredName(final JSONObject body, final String operation) {
    if (!present(body, "name"))
      throw new IllegalArgumentException("selection " + operation + ": 'name' is required");
    return body.getString("name");
  }

  private static List<Object> selections(final JSONObject body) {
    return present(body, "selections") ? body.getJSONArray("selections").toList() : null;
  }

  /** The procedure options among {@code keys} that the body carries, converted to plain Java values. */
  private static Map<String, Object> options(final JSONObject body, final String... keys) {
    final Map<String, Object> options = new HashMap<>();
    for (final String key : keys)
      if (present(body, key)) {
        final Object value = body.get(key);
        options.put(key, value instanceof JSONObject object ? object.toMap() : value instanceof JSONArray array ? array.toList() : value);
      }
    return options;
  }

  private static boolean present(final JSONObject body, final String key) {
    return body.has(key) && !body.isNull(key);
  }

  private static JSONObject handle(final Result row) {
    final Number expiresAt = row.getProperty("expiresAt");
    return new JSONObject()
        .put("name", row.<String>getProperty("name"))
        .put("cardinality", row.<Number>getProperty("cardinality").longValue())
        .put("createdAt", row.<Number>getProperty("createdAt").longValue())
        .put("expiresAt", expiresAt != null ? expiresAt.longValue() : null);
  }

  private static JSONObject record(final JsonSerializer serializer, final Object node) {
    final JSONObject json = new JSONObject().put("rid", ((Identifiable) node).getIdentity().toString());
    if (node instanceof Document document)
      json.put("properties", serializer.serializeDocument(document));
    return json;
  }

  private static JSONArray ridStrings(final List<?> rids) {
    final JSONArray array = new JSONArray();
    for (final Object rid : rids)
      array.put(rid.toString());
    return array;
  }

  private static JsonSerializer serializer() {
    return JsonSerializer.createJsonSerializer()
        .setIncludeVertexEdges(false)
        .setUseCollectionSize(false)
        .setUseCollectionSizeForEdges(false);
  }
}
