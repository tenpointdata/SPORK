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
package com.arcadedb.versioning;

import com.arcadedb.database.BaseRecord;
import com.arcadedb.database.Binary;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.LocalDatabase;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.lsm.LSMTreeIndexAbstract;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native record versioning: the engine keeps the transaction-time history of every record of a versioned type
 * ({@link DocumentType#setVersioned(boolean)}), the SQL:2011 system-versioned table model.
 * <ul>
 *   <li>The current version is the record itself, where it always was: reads that do not ask for history pay nothing.</li>
 *   <li>Each record of a versioned type carries {@link #TX_FROM_PROPERTY}, the engine's stamp of when its current version became
 *   current. The engine owns it: an update that changes it is refused, a value given on create is overwritten.</li>
 *   <li>Each superseded or deleted version is one entry of the internal {@link #HISTORY_TYPE} store: the record's committed
 *   serialized image, copied as is, with the interval it was valid on and, for an edge, its endpoints (indexed, so the edges of a
 *   vertex as of an instant are an index lookup, not a scan). The history is not part of the graph: no vertex, no edge.</li>
 * </ul>
 * Stamps are epoch microseconds, one per transaction (taken at its first versioned write) and strictly increasing per database.
 * The history entries ride the writing transaction: a rollback discards them, a commit replicates them with the rest of its
 * pages. Only the user-visible write path is versioned: the engine's own bookkeeping on a record (the edge-list heads of a
 * vertex) never creates a version.
 * <p>
 * Limits: transaction time only; EXTERNAL property values are not copied into the history; lightweight edges have no record to
 * version.
 */
public class RecordVersioning {
  /** The engine-owned property stamping when the current version of a record became current, epoch microseconds. */
  public static final String TX_FROM_PROPERTY = "_txFrom";
  /** The internal document type holding the superseded versions. */
  public static final String HISTORY_TYPE     = "_VersionHistory";

  static final String HISTORY_RID     = "rid";
  static final String HISTORY_TX_FROM = "txFrom";
  static final String HISTORY_TX_TO   = "txTo";
  static final String HISTORY_OUT     = "out";
  static final String HISTORY_IN      = "in";
  static final String HISTORY_DATA    = "data";

  // set only while purge() deletes entries: every other delete or update of the history store is a client's and is refused
  private static final ThreadLocal<Boolean> PURGING = new ThreadLocal<>();

  private final LocalDatabase database;
  private final AtomicLong    lastStamp = new AtomicLong();

  public RecordVersioning(final LocalDatabase database) {
    this.database = database;
  }

  public static RecordVersioning of(final Database database) {
    return ((LocalDatabase) ((DatabaseInternal) database).getEmbedded()).getVersioning();
  }

  /**
   * Creates the history store, its indexes and the stamp property of {@code type}. Called by {@link DocumentType#setVersioned}
   * before it flips the flag, outside the schema's write frame (an index build waits for the asynchronous writers).
   */
  public static void prepareType(final Schema schema, final DocumentType type) {
    if (!schema.existsType(HISTORY_TYPE)) {
      final DocumentType history = schema.createDocumentType(HISTORY_TYPE);
      history.createProperty(HISTORY_RID, Type.LINK);
      history.createProperty(HISTORY_TX_FROM, Type.LONG);
      history.createProperty(HISTORY_TX_TO, Type.LONG);
      history.createProperty(HISTORY_OUT, Type.LINK);
      history.createProperty(HISTORY_IN, Type.LINK);
      history.createProperty(HISTORY_DATA, Type.BINARY);
      for (final String property : new String[] { HISTORY_RID, HISTORY_OUT, HISTORY_IN })
        schema.buildTypeIndex(HISTORY_TYPE, new String[] { property }).withType(Schema.INDEX_TYPE.LSM_TREE).withUnique(false)
            .withNullStrategy(LSMTreeIndexAbstract.NULL_STRATEGY.SKIP).create();
    }
    if (!type.existsPolymorphicProperty(TX_FROM_PROPERTY))
      type.createProperty(TX_FROM_PROPERTY, Type.LONG);
  }

  // ---------------------------------------------------------------------------------------------------------------- WRITE PATH

  /** Stamps a record of a versioned type about to be created. Called with the transaction open, before the record is written. */
  public void onCreate(final Record record) {
    if (record instanceof MutableDocument document && document.getType().isVersioned())
      document.set(TX_FROM_PROPERTY, stamp());
  }

  /** Remembers a just-created record of a versioned type: it has no committed image, so an update in this transaction adds no version. */
  public void afterCreate(final Record record) {
    if (record instanceof MutableDocument document && document.getType().isVersioned())
      database.getTransaction().setVersionedRecord(record.getIdentity(), record);
  }

  /**
   * Keeps the committed image of a record about to be updated, the first time this transaction updates it, and stamps the new
   * version. Refuses a client write to the history store and a client change of the stamp.
   */
  public void beforeUpdate(final Record record) {
    if (!(record instanceof Document document))
      return;
    final DocumentType type = document.getType();
    if (!type.isVersioned()) {
      if (PURGING.get() == null && HISTORY_TYPE.equals(type.getName()))
        throw new ValidationException("The version history is maintained by the engine and cannot be updated");
      return;
    }

    final long stamp = stamp();
    final TransactionContext tx = database.getTransaction();
    final RID rid = record.getIdentity();
    final byte[] image = committedImage(record);
    final Object originalStamp = decode(type, rid, image).get(TX_FROM_PROPERTY);

    if (record instanceof MutableDocument mutable) {
      final Object given = mutable.get(TX_FROM_PROPERTY);
      if (given != null && !given.equals(originalStamp) && !given.equals(stamp))
        throw new ValidationException(
            "Property '" + TX_FROM_PROPERTY + "' of " + rid + " is maintained by the engine and cannot be altered");
    }

    if (!isHandled(tx, rid))
      tx.setVersionedRecord(rid, writeHistory(rid, record, image, originalStamp, stamp));

    if (record instanceof MutableDocument mutable)
      mutable.set(TX_FROM_PROPERTY, stamp);
  }

  /** Keeps the committed image of a record about to be deleted, unless an update of this transaction already kept it. */
  public void beforeDelete(final Record record) {
    if (!(record instanceof Document document))
      return;
    final DocumentType type = document.getType();
    if (!type.isVersioned()) {
      if (PURGING.get() == null && HISTORY_TYPE.equals(type.getName()))
        throw new ValidationException("The version history is maintained by the engine and can be removed only by a purge");
      return;
    }
    final RID rid = record.getIdentity();
    if (rid == null || rid.getPosition() < 0)
      // a lightweight edge: no record, nothing to keep
      return;

    final TransactionContext tx = database.getTransaction();
    if (isHandled(tx, rid))
      return;

    final long stamp = stamp();
    final byte[] image = committedImage(record);
    final Document original = decode(type, rid, image);
    tx.setVersionedRecord(rid, writeHistory(rid, original, image, original.get(TX_FROM_PROPERTY), stamp));
  }

  private static boolean isHandled(final TransactionContext tx, final RID rid) {
    // an entry rolled back by a statement savepoint lost its identity: the image has to be kept again
    final Record handledBy = tx.getVersionedRecord(rid);
    return handledBy != null && handledBy.getIdentity() != null;
  }

  private Record writeHistory(final RID rid, final Record record, final byte[] image, final Object from, final long to) {
    final MutableDocument entry = database.newDocument(HISTORY_TYPE);
    entry.set(HISTORY_RID, rid);
    entry.set(HISTORY_TX_FROM, from instanceof Number n ? n.longValue() : 0L);
    entry.set(HISTORY_TX_TO, to);
    entry.set(HISTORY_DATA, image);
    if (record instanceof Edge edge) {
      entry.set(HISTORY_OUT, edge.getOut());
      entry.set(HISTORY_IN, edge.getIn());
    }
    database.createRecordNoLock(entry, null, false);
    return entry;
  }

  /**
   * A copy of the image this transaction found the record in: the buffer it was loaded from, which a deferred update leaves
   * untouched until the commit, or the bucket's copy for a handle that carries none. Copied, never decoded in place: the record
   * reads its own buffer by position, and a decode would move it.
   */
  private byte[] committedImage(final Record record) {
    final Binary buffer = record instanceof BaseRecord base ? base.getBuffer() : null;
    if (buffer != null)
      return buffer.toByteArray();
    return database.getSchema().getBucketById(record.getIdentity().getBucketId()).getRecord(record.getIdentity()).toByteArray();
  }

  private Document decode(final DocumentType type, final RID rid, final byte[] image) {
    return (Document) database.getRecordFactory().newImmutableRecord(database, type, rid, new Binary(image), null);
  }

  /** The transaction's stamp, taken at its first versioned write: epoch microseconds, strictly increasing per database. */
  private long stamp() {
    final TransactionContext tx = database.getTransaction();
    long stamp = tx.getVersionStamp();
    if (stamp == 0L) {
      final long now = System.currentTimeMillis() * 1000L;
      stamp = lastStamp.accumulateAndGet(now, (previous, candidate) -> Math.max(previous + 1, candidate));
      tx.setVersionStamp(stamp);
    }
    return stamp;
  }

  // ----------------------------------------------------------------------------------------------------------------- READ PATH

  /** Every version of the record, oldest first, the current one (if the record exists) last. */
  public List<RecordVersion> history(final RID rid) {
    final List<RecordVersion> versions = new ArrayList<>();
    final TypeIndex byRid = historyIndex(HISTORY_RID);
    if (byRid != null) {
      final IndexCursor cursor = byRid.get(new Object[] { rid });
      while (cursor.hasNext()) {
        final Identifiable entry = cursor.next();
        if (entry != null)
          versions.add(fromEntry(entry.asDocument(true)));
      }
      versions.sort(Comparator.comparingLong(RecordVersion::getTxToMicros).thenComparingLong(RecordVersion::getTxFromMicros));
    }

    final Document current = loadCurrent(rid);
    if (current != null)
      versions.add(fromCurrent(current));

    for (int i = 0; i < versions.size(); i++)
      versions.get(i).setVersion(i + 1);
    return versions;
  }

  /** The version of the record valid at the END of the epoch millisecond {@code epochMillis}, or null if it did not exist then. */
  public RecordVersion asOf(final RID rid, final long epochMillis) {
    return asOfMicros(rid, toMicros(epochMillis));
  }

  /** The version of the record valid at the instant {@code micros} (epoch microseconds), or null if it did not exist then. */
  public RecordVersion asOfMicros(final RID rid, final long micros) {
    final List<RecordVersion> versions = history(rid);
    for (int i = versions.size() - 1; i >= 0; i--)
      if (versions.get(i).isValidAt(micros))
        return versions.get(i);
    return null;
  }

  /** The edges of {@code vertex} as they were at the end of the epoch millisecond {@code epochMillis}. */
  public List<RecordVersion> edgesAsOf(final RID vertex, final long epochMillis, final Vertex.DIRECTION direction,
      final String edgeType) {
    return edgesAsOfMicros(vertex, toMicros(epochMillis), direction, edgeType);
  }

  /**
   * The edges of {@code vertex} as they were at the instant {@code micros}: the current edges that already existed then, plus the
   * superseded and deleted versions valid then. {@code edgeType} null takes every edge type, otherwise the type and its subtypes.
   */
  public List<RecordVersion> edgesAsOfMicros(final RID vertex, final long micros, final Vertex.DIRECTION direction,
      final String edgeType) {
    final List<RecordVersion> result = new ArrayList<>();
    final Set<RID> seen = direction == Vertex.DIRECTION.BOTH ? new HashSet<>() : null;

    final Document current = loadCurrent(vertex);
    if (current instanceof Vertex v)
      for (final Edge edge : edgeType == null ? v.getEdges(direction) : v.getEdges(direction, edgeType)) {
        final RID rid = edge.getIdentity();
        if (rid.getPosition() < 0) {
          // a lightweight edge has no history and no stamp: it is reported as it is now
          result.add(new RecordVersion(rid, edge.getTypeName(), 0L, Long.MAX_VALUE, edge.getOut(), edge.getIn(), () -> edge));
          continue;
        }
        final RecordVersion version = fromCurrent(edge);
        if (version.isValidAt(micros) && (seen == null || seen.add(rid)))
          result.add(version);
      }

    if (direction == Vertex.DIRECTION.OUT || direction == Vertex.DIRECTION.BOTH)
      addHistoricalEdges(HISTORY_OUT, vertex, micros, edgeType, seen, result);
    if (direction == Vertex.DIRECTION.IN || direction == Vertex.DIRECTION.BOTH)
      addHistoricalEdges(HISTORY_IN, vertex, micros, edgeType, seen, result);
    return result;
  }

  private void addHistoricalEdges(final String side, final RID vertex, final long micros, final String edgeType, final Set<RID> seen,
      final List<RecordVersion> result) {
    final TypeIndex index = historyIndex(side);
    if (index == null)
      return;
    final IndexCursor cursor = index.get(new Object[] { vertex });
    while (cursor.hasNext()) {
      final Identifiable entry = cursor.next();
      if (entry == null)
        continue;
      final Document doc = entry.asDocument(true);
      final long from = doc.getLong(HISTORY_TX_FROM);
      final long to = doc.getLong(HISTORY_TX_TO);
      if (from > micros || micros >= to)
        continue;
      final RecordVersion version = fromEntry(doc);
      if (edgeType != null && !database.getSchema().getType(version.getTypeName()).isSubTypeOf(edgeType))
        continue;
      if (seen == null || seen.add(version.getIdentity()))
        result.add(version);
    }
  }

  /** The number of entries in the history store, superseded versions of every record. */
  public long historyEntryCount() {
    return database.getSchema().existsType(HISTORY_TYPE) ? database.countType(HISTORY_TYPE, false) : 0L;
  }

  /**
   * Removes the record's history (retention, right to erasure). The current record, if any, is untouched. Runs in the caller's
   * transaction.
   *
   * @return the number of versions removed
   */
  public long purge(final RID rid) {
    final TypeIndex byRid = historyIndex(HISTORY_RID);
    if (byRid == null)
      return 0L;
    final List<Identifiable> entries = new ArrayList<>();
    final IndexCursor cursor = byRid.get(new Object[] { rid });
    while (cursor.hasNext()) {
      final Identifiable entry = cursor.next();
      if (entry != null)
        entries.add(entry);
    }
    PURGING.set(Boolean.TRUE);
    try {
      for (final Identifiable entry : entries)
        database.deleteRecord(entry.getRecord());
    } finally {
      PURGING.remove();
    }
    return entries.size();
  }

  private RecordVersion fromEntry(final Document entry) {
    final RID rid = ((Identifiable) entry.get(HISTORY_RID)).getIdentity();
    final DocumentType type = database.getSchema().getTypeByBucketId(rid.getBucketId());
    final byte[] data = entry.getBinary(HISTORY_DATA);
    final Identifiable out = (Identifiable) entry.get(HISTORY_OUT);
    final Identifiable in = (Identifiable) entry.get(HISTORY_IN);
    return new RecordVersion(rid, type.getName(), entry.getLong(HISTORY_TX_FROM), entry.getLong(HISTORY_TX_TO),
        out == null ? null : out.getIdentity(), in == null ? null : in.getIdentity(), () -> decode(type, rid, data));
  }

  private static RecordVersion fromCurrent(final Document current) {
    final Object stamp = current.get(TX_FROM_PROPERTY);
    final RID out = current instanceof Edge edge ? edge.getOut() : null;
    final RID in = current instanceof Edge edge ? edge.getIn() : null;
    return new RecordVersion(current.getIdentity(), current.getTypeName(), stamp instanceof Number n ? n.longValue() : 0L,
        Long.MAX_VALUE, out, in, () -> current);
  }

  private Document loadCurrent(final RID rid) {
    try {
      return database.lookupByRID(rid, true).asDocument();
    } catch (final RecordNotFoundException e) {
      return null;
    }
  }

  private TypeIndex historyIndex(final String property) {
    if (!database.getSchema().existsType(HISTORY_TYPE))
      return null;
    return database.getSchema().getType(HISTORY_TYPE).getPolymorphicIndexByProperties(property);
  }

  private static long toMicros(final long epochMillis) {
    // the end of the millisecond: everything committed within it is visible
    return epochMillis * 1000L + 999L;
  }
}
