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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.ProtocolContext;
import com.arcadedb.graph.olap.GraphAlgorithms;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.security.ServerSecurityUser;
import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.keys.UpstreamRid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * {@code POST /records:hydrate} (01-architecture 2.5, 7.2, R1): up to {@value #MAX_IDS} ids in, the Record projections the person
 * may see out, in request order. Per id: one {@code recdir} HASH probe for an upstream RID (a {@code record_id} carries its
 * period), then one HASH probe and one Record page in the partition; an {@code imp:} id probes the {@code (tenantId, importedId)}
 * index of each partition. No edge, local vertex, Place or text read.
 * <p>
 * <b>Engine user.</b> Every database is read with the person bound as the engine user on the reading thread, so per-type grants
 * apply, and a database the person holds no grant on refuses the whole request (403) before it is read.
 * <p>
 * <b>Visibility.</b> A record whose markings the person does not hold, or an analyst import of another tenant, is absent, not
 * redacted: it is reported in {@code missing} exactly as an id that matches nothing, so its existence stays hidden (5.3).
 * <p>
 * <b>Parallelism.</b> Above {@value #CHUNK} ids the work splits into chunks of that size on the {@link HydratePool}; the request
 * thread runs the first chunk itself and then waits with reclaiming. Results land in per-index slots, so no chunk synchronizes
 * with another.
 */
final class HydrateService {
  public static final  int    MAX_IDS         = 5_000;
  static final         int    CHUNK           = 64;
  static final         String RECORD_TYPE     = "Record";
  static final         String DIRECTORY_TYPE  = "RecordDir";
  private static final byte   FOUND           = 0;
  private static final byte   MISSING         = 1;
  private static final byte   INVALID         = 2;
  private static final byte   UNAVAILABLE     = 3;
  private static final Object ABSENT          = new Object();

  private final ArcadeDBServer          server;
  private final SporkReconConfiguration config;
  private final HydratePool             pool;

  /** What a hydrate released, before it is audited and answered. */
  record Result(JSONArray records, JSONArray missing, JSONArray invalid, long[] recordIds, int requested, boolean partial) {
  }

  HydrateService(final ArcadeDBServer server, final SporkReconConfiguration config, final HydratePool pool) {
    this.server = server;
    this.config = config;
    this.pool = pool;
  }

  /** Validates the request body; every refusal here is a 400 that reads nothing. */
  static List<String> ids(final JSONObject payload) {
    final Object value = payload != null && payload.has("ids") ? payload.get("ids") : null;
    if (!(value instanceof JSONArray array))
      throw SporkRefusal.badRequest("idsRequired", "'ids' must be an array of record ids");
    if (array.length() > MAX_IDS)
      throw SporkRefusal.badRequest("tooManyIds", "A hydrate takes at most " + MAX_IDS + " ids; page larger sets");
    final Set<String> seen = new HashSet<>(array.length() * 2);
    final List<String> ids = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++) {
      final Object id = array.get(i);
      if (!(id instanceof String s))
        throw SporkRefusal.badRequest("idsRequired", "'ids' must hold strings: a record_id does not fit a JavaScript number");
      if (seen.add(s))
        ids.add(s);
    }
    return ids;
  }

  static RecordProjection[] projection(final JSONObject payload) {
    final Object value = payload.has("projection") ? payload.get("projection") : null;
    if (value == null || payload.isNull("projection"))
      return RecordProjection.defaults();
    if (!(value instanceof JSONArray array))
      throw SporkRefusal.badRequest("projectionInvalid", "'projection' must be an array of field names");
    final RecordProjection[] fields = new RecordProjection[array.length()];
    for (int i = 0; i < fields.length; i++) {
      final Object name = array.get(i);
      fields[i] = name instanceof String s ? RecordProjection.fromApiName(s) : null;
      if (fields[i] == null)
        throw SporkRefusal.badRequest("projectionInvalid", "'" + name + "' is not a hydrate field");
    }
    return fields;
  }

  Result hydrate(final ServerSecurityUser user, final long heldMarkings, final String tenant, final List<String> ids,
      final RecordProjection[] fields) {
    final int count = ids.size();
    final Request request = new Request(user, heldMarkings, tenant, fields, ids, count);

    if (count <= CHUNK)
      request.run(0, count);
    else {
      final int chunks = (count + CHUNK - 1) / CHUNK;
      final Future<?>[] futures = new Future<?>[chunks - 1];
      final Throwable[] errors = new Throwable[chunks];
      // the request thread's tag (http), carried onto the workers: they serve this client's request
      final String protocol = ProtocolContext.get();
      for (int c = 1; c < chunks; c++) {
        final int chunk = c;
        futures[c - 1] = pool.getExecutorService().submit(() -> HydratePool.runTagged(protocol, () -> {
          try {
            request.run(chunk * CHUNK, Math.min(count, (chunk + 1) * CHUNK));
          } catch (final Throwable t) {
            errors[chunk] = t;
          }
        }));
      }
      try {
        request.run(0, CHUNK);
      } catch (final Throwable t) {
        errors[0] = t;
      }
      GraphAlgorithms.awaitFutures(futures, futures.length, pool);
      for (final Throwable error : errors)
        if (error != null) {
          if (error instanceof RuntimeException e)
            throw e;
          if (error instanceof Error e)
            throw e;
          throw new IllegalStateException(error);
        }
    }
    return request.collect();
  }

  /** The per-request state the chunks share: read-only inputs, per-index output slots and the resolved database handles. */
  private final class Request {
    private final ServerSecurityUser  user;
    private final long                heldMarkings;
    private final String              tenant;
    private final RecordProjection[]  fields;
    private final String[]            ids;
    private final byte[]              status;
    private final JSONObject[]        records;
    private final long[]              recordIds;
    private final Map<String, Object> databases = new ConcurrentHashMap<>();
    private volatile String[]         partitionNames;

    Request(final ServerSecurityUser user, final long heldMarkings, final String tenant, final RecordProjection[] fields,
        final List<String> ids, final int count) {
      this.user = user;
      this.heldMarkings = heldMarkings;
      this.tenant = tenant;
      this.fields = fields;
      this.ids = ids.toArray(new String[count]);
      this.status = new byte[count];
      this.records = new JSONObject[count];
      this.recordIds = new long[count];
    }

    void run(final int from, final int to) {
      final Binding binding = new Binding(user);
      try {
        for (int i = from; i < to; i++)
          resolve(i, binding);
      } finally {
        binding.release();
      }
    }

    private void resolve(final int i, final Binding binding) {
      final RecordRef ref = RecordRef.parse(ids[i]);
      if (ref == null) {
        status[i] = INVALID;
        return;
      }
      Document record = null;
      switch (ref.kind()) {
      case RECORD_ID -> {
        final Database partition = binding.bind(database(config.partitionPrefix() + RecordId.period(ref.value())));
        if (partition == null) {
          status[i] = UNAVAILABLE;
          return;
        }
        record = first(partition.lookupByKey(RECORD_TYPE, "record_id", ref.value()));
      }
      case UPSTREAM_RID -> {
        final Database directory = binding.bind(database(config.directoryDatabase()));
        if (directory == null) {
          status[i] = UNAVAILABLE;
          return;
        }
        final Document entry = first(directory.lookupByKey(DIRECTORY_TYPE, "upstreamRid", ref.value()));
        if (entry != null && entry.get("period") instanceof Number period) {
          final Database partition = binding.bind(database(config.partitionPrefix() + period.intValue()));
          if (partition == null) {
            status[i] = UNAVAILABLE;
            return;
          }
          record = first(partition.lookupByKey(RECORD_TYPE, "upstreamRid", ref.value()));
        }
      }
      case IMPORTED -> {
        for (final String name : partitionNames()) {
          final Database partition = binding.bind(database(name));
          if (partition != null && hasImportIndex(partition)) {
            record = first(partition.lookupByKey(RECORD_TYPE, new String[] { "tenantId", "importedId" },
                new Object[] { tenant, ref.importedId() }));
            if (record != null)
              break;
          }
        }
      }
      }
      if (record == null || !visible(record)) {
        status[i] = MISSING;
        return;
      }

      final long recordId = record.getLong("record_id");
      final JSONObject out = new JSONObject();
      out.put("id", ids[i]);
      out.put("recordId", Long.toUnsignedString(recordId));
      if (record.get("upstreamRid") instanceof Number upstreamRid)
        out.put("upstreamRid", UpstreamRid.format(upstreamRid.longValue()));
      for (final RecordProjection field : fields)
        field.copy(record, out);
      records[i] = out;
      recordIds[i] = recordId;
      status[i] = FOUND;
    }

    /** Markings the person does not hold, or another tenant's import: absent (01-architecture 5.3). */
    private boolean visible(final Document record) {
      final String owner = record.getString("tenantId");
      if (owner != null && !owner.equals(tenant))
        return false;
      final long markings = record.get("markings") instanceof Number n ? n.longValue() : 0L;
      return (markings & ~heldMarkings) == 0L;
    }

    /** The open database of that name, or null when this node does not hold it (the response is then partial). */
    private Database database(final String name) {
      final Object cached = databases.computeIfAbsent(name, n -> server.existsDatabase(n) ? server.getDatabaseForClient(n) : ABSENT);
      return cached instanceof Database database ? database : null;
    }

    private String[] partitionNames() {
      String[] names = partitionNames;
      if (names == null) {
        final List<String> found = new ArrayList<>();
        for (final String name : server.getDatabaseNames())
          if (name.startsWith(config.partitionPrefix()) && name.length() > config.partitionPrefix().length() && name.substring(
              config.partitionPrefix().length()).chars().allMatch(Character::isDigit))
            found.add(name);
        names = partitionNames = found.toArray(new String[0]);
      }
      return names;
    }

    Result collect() {
      final JSONArray released = new JSONArray(records.length);
      final JSONArray missing = new JSONArray();
      final JSONArray invalid = new JSONArray();
      final long[] keys = new long[records.length];
      int found = 0;
      boolean partial = false;
      for (int i = 0; i < records.length; i++)
        switch (status[i]) {
        case FOUND -> {
          released.put(records[i]);
          keys[found++] = recordIds[i];
        }
        case INVALID -> invalid.put(ids[i]);
        case UNAVAILABLE -> {
          missing.put(ids[i]);
          partial = true;
        }
        default -> missing.put(ids[i]);
        }
      final long[] releasedIds = new long[found];
      System.arraycopy(keys, 0, releasedIds, 0, found);
      return new Result(released, missing, invalid, releasedIds, records.length, partial);
    }
  }

  private static Document first(final IndexCursor cursor) {
    return cursor.hasNext() ? cursor.next().asDocument() : null;
  }

  private static boolean hasImportIndex(final Database partition) {
    final Schema schema = partition.getSchema();
    if (!schema.existsType(RECORD_TYPE))
      return false;
    final DocumentType type = schema.getType(RECORD_TYPE);
    return type.getPolymorphicIndexByProperties("tenantId", "importedId") != null;
  }

  /**
   * The person bound as the engine user on this thread, per database, for as long as one chunk runs: grants are checked before the
   * first read of each database, and every binding is undone afterwards so nothing leaks onto a pooled worker or back onto the
   * request thread.
   */
  private static final class Binding {
    private final ServerSecurityUser                 user;
    private final Map<DatabaseInternal, SecurityDatabaseUser> previous = new HashMap<>(4);
    private final Set<DatabaseInternal>              created  = new HashSet<>(4);

    Binding(final ServerSecurityUser user) {
      this.user = user;
    }

    Database bind(final Database database) {
      if (database == null)
        return null;
      final DatabaseInternal internal = (DatabaseInternal) database;
      if (previous.containsKey(internal) || created.contains(internal))
        return database;
      if (!user.canAccessToDatabase(database.getName()))
        throw new SecurityException("User '" + user.getName() + "' is not allowed to access database '" + database.getName() + "'");
      DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(internal.getDatabasePath());
      if (context == null) {
        context = DatabaseContext.INSTANCE.init(internal);
        created.add(internal);
      } else
        previous.put(internal, context.getCurrentUser());
      context.setCurrentUser(user.getDatabaseUser(database));
      return database;
    }

    void release() {
      for (final Map.Entry<DatabaseInternal, SecurityDatabaseUser> entry : previous.entrySet()) {
        final DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(entry.getKey().getDatabasePath());
        if (context != null)
          context.setCurrentUser(entry.getValue());
      }
      for (final DatabaseInternal database : created) {
        final DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(database.getDatabasePath());
        if (context != null) {
          context.setCurrentUser(null);
          if (!context.hasActiveTransaction())
            DatabaseContext.INSTANCE.removeContext(database.getDatabasePath());
        }
      }
    }
  }
}
