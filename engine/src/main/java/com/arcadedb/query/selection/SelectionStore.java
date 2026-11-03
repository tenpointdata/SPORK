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
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.async.AsyncQuiesce;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.utility.RidBitmap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved selections (docs/spork/03-f1-selections-design.md, 2.3): one {@value #TYPE_NAME} document per name, holding the
 * members as a serialized {@link RidBitmap} plus their provenance and expiry. Being ordinary records, handles replicate through
 * Raft, travel in backups and are governed by the normal record security.
 * <p>
 * Expiry is lazy: an expired handle reads as expired, every write deletes up to {@value #PURGE_BATCH} expired handles as it
 * goes, and {@link #purgeExpired(int)} deletes them on request. There is no background thread, so deletes only happen on the
 * node a write already runs on.
 * <p>
 * Messages name {@code owner}, the procedure or route the store serves, so a refusal reads like the caller's own.
 */
public final class SelectionStore {
  public static final String TYPE_NAME           = "SelectionHandle";
  public static final long   DEFAULT_TTL_SECONDS = 86_400L;
  public static final int    MAX_NAME_LENGTH     = 256;
  static final        int    PURGE_BATCH         = 100;

  public static final String NAME        = "name";
  public static final String MEMBERS     = "members";
  public static final String CARDINALITY = "cardinality";
  public static final String CREATED_AT  = "createdAt";
  public static final String EXPIRES_AT  = "expiresAt";
  public static final String SOURCE      = "source";
  public static final String LANGUAGE    = "language";
  public static final String PARAMS      = "params";
  public static final String CREATED_BY  = "createdBy";

  /** A saved selection as stored; {@code expiresAt} null means it never expires. The members are decoded on demand. */
  public record Handle(RID rid, String name, long cardinality, long createdAt, Long expiresAt, String source, String language,
                       Map<String, Object> params, String createdBy, byte[] memberBytes) {
    public boolean isExpired(final long now) {
      return expiresAt != null && expiresAt <= now;
    }

    public RidBitmap members() {
      return RidBitmap.fromBytes(memberBytes);
    }
  }

  private final Database database;
  private final String   owner;

  public SelectionStore(final Database database, final String owner) {
    this.database = database;
    this.owner = owner;
  }

  /** The live handle of {@code name}, refused when there is none or it has expired. */
  public Handle require(final String name) {
    final Handle handle = find(name);
    if (handle == null)
      throw new IllegalArgumentException(owner + "(): no selection named '" + name + "'");
    if (handle.isExpired(now()))
      throw new IllegalArgumentException(
          owner + "(): selection '" + name + "' expired at " + Instant.ofEpochMilli(handle.expiresAt()));
    return handle;
  }

  /** The handle of {@code name}, expired or not, or null when there is none. */
  public Handle find(final String name) {
    if (name == null || !database.getSchema().existsType(TYPE_NAME))
      return null;
    checkType(database.getSchema().getType(TYPE_NAME));

    final IndexCursor cursor = database.lookupByKey(TYPE_NAME, NAME, name);
    if (!cursor.hasNext())
      return null;
    return toHandle(cursor.next().asDocument(true));
  }

  /**
   * Saves {@code members} under {@code name}. A live handle of that name is overwritten only with {@code replace}; an
   * expired one always is. {@code ttlSeconds} 0 means the handle never expires.
   */
  public Handle save(final String name, final RidBitmap members, final long ttlSeconds, final String source,
      final String language, final Map<String, Object> params, final boolean replace) {
    validateName(name);
    if (ttlSeconds < 0)
      throw new IllegalArgumentException(owner + "(): ttl must be 0 (never expires) or a number of seconds, got " + ttlSeconds);

    ensureType();
    purgeExpired(PURGE_BATCH);

    final long now = now();
    final Handle existing = find(name);
    final MutableDocument doc;
    if (existing != null) {
      if (!existing.isExpired(now) && !replace)
        throw new IllegalArgumentException(
            owner + "(): a selection named '" + name + "' already exists, pass {replace: true} to overwrite it");
      doc = existing.rid().asDocument(true).modify();
    } else
      doc = database.newDocument(TYPE_NAME);

    members.runOptimize();
    doc.set(NAME, name);
    doc.set(MEMBERS, members.toBytes());
    doc.set(CARDINALITY, members.cardinality());
    doc.set(CREATED_AT, now);
    doc.set(EXPIRES_AT, ttlSeconds == 0 ? null : now + ttlSeconds * 1000L);
    doc.set(SOURCE, source);
    doc.set(LANGUAGE, language);
    doc.set(PARAMS, params == null || params.isEmpty() ? null : new HashMap<>(params));
    doc.set(CREATED_BY, database.getCurrentUserName());
    doc.save();
    return toHandle(doc);
  }

  /** Deletes the handle of {@code name}, expired or not; false when there was none. */
  public boolean drop(final String name) {
    final Handle handle = find(name);
    purgeExpired(PURGE_BATCH);
    if (handle == null)
      return false;
    if (database.existsRecord(handle.rid()))
      database.deleteRecord(handle.rid().asDocument(true));
    return true;
  }

  /** Deletes up to {@code limit} expired handles (0 means all of them) through the {@code expiresAt} index. */
  public long purgeExpired(final int limit) {
    if (!database.getSchema().existsType(TYPE_NAME))
      return 0;
    checkType(database.getSchema().getType(TYPE_NAME));

    // COLLECTED FIRST, DELETED AFTER: THE QUERY'S CURSOR IS NOT WALKED WHILE ITS OWN INDEX CHANGES UNDER IT
    final List<RID> expired = new ArrayList<>();
    final String sql = "SELECT @rid AS rid FROM " + TYPE_NAME + " WHERE " + EXPIRES_AT + " < ?" + (limit > 0 ? " LIMIT " + limit : "");
    try (final ResultSet rs = database.query("sql", sql, now())) {
      while (rs.hasNext())
        expired.add(rs.next().getProperty("rid"));
    }
    for (final RID rid : expired)
      database.deleteRecord(rid.asDocument(true));
    return expired.size();
  }

  /**
   * Creates the type, its declared properties and both indexes in ONE outermost schema frame, so one schema entry under
   * HA, with the async executor quiesced first because an index build waits for it (see engine/CLAUDE.md).
   */
  private void ensureType() {
    final Schema schema = database.getSchema();
    if (!schema.existsType(TYPE_NAME)) {
      try (final AsyncQuiesce ignored = ((DatabaseInternal) database).quiesceAsync()) {
        schema.bulkChange(() -> {
          if (schema.existsType(TYPE_NAME))
            return;
          final DocumentType type = schema.createDocumentType(TYPE_NAME);
          type.createProperty(NAME, Type.STRING).setMandatory(true);
          type.createProperty(MEMBERS, Type.BINARY);
          type.createProperty(CARDINALITY, Type.LONG);
          type.createProperty(CREATED_AT, Type.LONG);
          type.createProperty(EXPIRES_AT, Type.LONG);
          type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, NAME);
          type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, EXPIRES_AT);
        });
      }
    }
    checkType(schema.getType(TYPE_NAME));
  }

  /** A user type that happens to carry the name is refused rather than written into. */
  private void checkType(final DocumentType type) {
    if (!hasIndex(type, NAME, true) || !hasIndex(type, EXPIRES_AT, false))
      throw new IllegalStateException(owner + "(): the type '" + TYPE_NAME
          + "' exists but is not a selection store (it needs a unique index on 'name' and an index on 'expiresAt')");
  }

  private static boolean hasIndex(final DocumentType type, final String property, final boolean unique) {
    for (final TypeIndex index : type.getIndexesByProperties(property))
      if (index.getPropertyNames().size() == 1 && (!unique || index.isUnique()))
        return true;
    return false;
  }

  private void validateName(final String name) {
    if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || !name.equals(name.strip()))
      throw new IllegalArgumentException(owner + "(): name must be 1 to " + MAX_NAME_LENGTH
          + " characters with no leading or trailing spaces, got '" + name + "'");
  }

  @SuppressWarnings("unchecked")
  private static Handle toHandle(final Document doc) {
    final Long cardinality = doc.getLong(CARDINALITY);
    final Long createdAt = doc.getLong(CREATED_AT);
    final Object params = doc.get(PARAMS);
    return new Handle(doc.getIdentity(), doc.getString(NAME), cardinality != null ? cardinality : 0L,
        createdAt != null ? createdAt : 0L, doc.getLong(EXPIRES_AT), doc.getString(SOURCE), doc.getString(LANGUAGE),
        params instanceof Map<?, ?> map ? (Map<String, Object>) map : null, doc.getString(CREATED_BY), doc.getBinary(MEMBERS));
  }

  private static long now() {
    return System.currentTimeMillis();
  }
}
