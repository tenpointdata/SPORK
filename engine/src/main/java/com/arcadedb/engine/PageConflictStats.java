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
package com.arcadedb.engine;

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.index.Index;
import com.arcadedb.index.lsm.LSMTreeIndex;
import com.arcadedb.index.lsm.LSMTreeIndexAbstract;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.LocalSchema;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.VertexType;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Page-version conflicts of one database, attributed to the FILE they were detected on (issue 5 of the SPORK stress
 * report). The JVM-wide {@code concurrentModificationExceptions} counter says how many page versions mismatched at
 * commit, but not where: a bucket, the edge list of a hub vertex, a unique edge index or a full-text index all count
 * the same, and the cure for each is different.
 * <p>
 * Two counters per file:
 * <ul>
 *   <li>{@code conflicts}: a page of the file was at a newer version than the one the transaction read
 *   ({@link PageManager#checkPageVersion} / {@link PageManager#updatePageVersion}). Exactly the events the JVM-wide
 *   counter counts.</li>
 *   <li>{@code merged}: of those, the ones a commit-time merge (edge-append or slot merge) absorbed. The difference
 *   ({@code failed}) is the conflicts of commit attempts that did not complete: the page could not be merged, or the
 *   attempt failed on another page before this one was merged. One failed attempt therefore usually charges several
 *   files; how many attempts failed is {@code txRetries} in the profiler.</li>
 * </ul>
 * <p>
 * Read {@code merged} next to {@code conflicts} before drawing a conclusion from the JVM-wide counter: under concurrent
 * inserts into shared bucket pages and edge appends to shared vertices, nearly all of it is merged and costs no retry.
 * <p>
 * COST: nothing on a commit without conflicts. Every call is on the conflict path, which already builds an exception
 * message. The per-file entry is created (and its name and kind resolved from the schema) on the file's first conflict
 * only; after that a conflict is one array read and one {@link LongAdder} increment. File ids are small dense
 * integers, so the entries live in an array indexed by the id, grown under a lock on the rare first conflict of a
 * file beyond its length.
 * <p>
 * The counters live as long as the database instance is open; they are a diagnostic, not a durable metric.
 */
public final class PageConflictStats {
  /** File ids above this are folded into one overflow entry, so a pathological id cannot allocate a huge array. */
  static final int MAX_TRACKED_FILE_ID = 1 << 16;

  private volatile Entry[] entries  = new Entry[64];
  private final    Entry   overflow = new Entry(-1, "(other)", "other", null);

  public static final class Entry {
    public final  int       fileId;
    public final  String    fileName;
    public final  String    kind;
    public final  String    detail;
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder merged    = new LongAdder();

    Entry(final int fileId, final String fileName, final String kind, final String detail) {
      this.fileId = fileId;
      this.fileName = fileName;
      this.kind = kind;
      this.detail = detail;
    }

    public long getConflicts() {
      return conflicts.sum();
    }

    public long getMerged() {
      return merged.sum();
    }

    /** Conflicts no merge absorbed: each belongs to a commit attempt that failed. */
    public long getFailed() {
      return Math.max(0L, conflicts.sum() - merged.sum());
    }

    public JSONObject toJSON() {
      final JSONObject json = new JSONObject().put("fileId", fileId).put("file", fileName).put("kind", kind);
      if (detail != null)
        json.put("detail", detail);
      final long c = conflicts.sum();
      final long m = merged.sum();
      return json.put("conflicts", c).put("merged", m).put("failed", Math.max(0L, c - m));
    }
  }

  /** A page of {@code fileId} failed its version check. */
  public void recordConflict(final DatabaseInternal database, final int fileId) {
    entry(database, fileId).conflicts.increment();
  }

  /** A conflict on a page of {@code fileId} was absorbed by a commit-time merge. */
  public void recordMerged(final DatabaseInternal database, final int fileId) {
    entry(database, fileId).merged.increment();
  }

  public long getTotalConflicts() {
    long total = overflow.conflicts.sum();
    for (final Entry e : entries)
      if (e != null)
        total += e.conflicts.sum();
    return total;
  }

  /** The entries with at least one conflict, most conflicts first. */
  public List<Entry> getTop(final int limit) {
    final List<Entry> list = new ArrayList<>();
    for (final Entry e : entries)
      if (e != null && e.conflicts.sum() > 0)
        list.add(e);
    if (overflow.conflicts.sum() > 0)
      list.add(overflow);
    list.sort((a, b) -> Long.compare(b.conflicts.sum(), a.conflicts.sum()));
    return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
  }

  public JSONArray toJSON(final int limit) {
    final JSONArray array = new JSONArray();
    for (final Entry e : getTop(limit))
      array.put(e.toJSON());
    return array;
  }

  /** Forgets every counter (a test, or an operator starting a new measurement window). */
  public synchronized void reset() {
    entries = new Entry[entries.length];
    overflow.conflicts.reset();
    overflow.merged.reset();
  }

  private Entry entry(final DatabaseInternal database, final int fileId) {
    if (fileId < 0 || fileId >= MAX_TRACKED_FILE_ID)
      return overflow;

    final Entry[] current = entries;
    if (fileId < current.length) {
      final Entry e = current[fileId];
      if (e != null)
        return e;
    }
    return createEntry(database, fileId);
  }

  private synchronized Entry createEntry(final DatabaseInternal database, final int fileId) {
    Entry[] current = entries;
    if (fileId < current.length && current[fileId] != null)
      return current[fileId];

    final Entry e = describe(database, fileId);
    if (fileId >= current.length) {
      int newLength = current.length;
      while (newLength <= fileId)
        newLength <<= 1;
      current = Arrays.copyOf(current, newLength);
    } else
      current = current.clone();
    current[fileId] = e;
    entries = current;
    return e;
  }

  /**
   * Names the file and says what it holds. Resolved once per file, from the schema as it is at the first conflict;
   * never throws (an unresolvable file is reported by id).
   */
  public static Entry describe(final DatabaseInternal database, final int fileId) {
    String fileName = "file#" + fileId;
    String kind = "unknown";
    String detail = null;
    try {
      final ComponentFile file = database.getFileManager().getFile(fileId);
      if (file != null)
        fileName = file.getFileName();

      final LocalSchema schema = database.getSchema().getEmbedded();
      final Component component = schema.getFileByIdIfExists(fileId);
      if (component instanceof LocalBucket bucket) {
        final String name = bucket.getName();
        if (name.endsWith("_out_edges") || name.endsWith("_in_edges"))
          kind = "edge-list";
        else {
          final DocumentType type = schema.getTypeByBucketId(fileId);
          kind = type instanceof EdgeType ? "edge-bucket" : type instanceof VertexType ? "vertex-bucket" : "bucket";
          if (type != null)
            detail = type.getName();
        }
      } else if (component instanceof LSMTreeIndexAbstract lsm) {
        final LSMTreeIndex main = lsm.getMainIndex();
        kind = lsm.isUnique() ? "index-unique" : "index-notunique";
        if (main != null) {
          detail = main.getTypeName() + main.getPropertyNames();
          // A full-text index registers its wrapper under the name of the LSM tree it wraps
          final String indexName = main.getName();
          if (indexName != null && schema.existsIndex(indexName)) {
            final Index wrapper = schema.getIndexByName(indexName);
            if (wrapper.getType() == Schema.INDEX_TYPE.FULL_TEXT)
              kind = "index-fulltext";
          }
        }
      } else if (component != null)
        kind = component.getClass().getSimpleName();
    } catch (final RuntimeException e) {
      // A DIAGNOSTIC MUST NOT FAIL THE COMMIT IT DESCRIBES: KEEP WHAT WAS RESOLVED
    }
    return new Entry(fileId, fileName, kind, detail);
  }
}
