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
package com.arcadedb.query.structured;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.utility.RidHashSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The P4 structured-search core of {@code docs/spork/02-retrieval-speed.md} 2.4, for the Recon plugin's search route and any
 * other caller that pages a type newest (or oldest) first under equality filters.
 * <p>
 * The SQL planner answers such a query badly: it is not cost-based, prefers an index whose key the predicate fully matches
 * over a prefix of a composite (#4600), and so for {@code services CONTAINS 'x' AND state = 'CA' ORDER BY tsPosted DESC LIMIT 50}
 * loads every record of a common service and sorts them. This walks a {@code (facet, sort)} composite in sort order instead,
 * so it can stop at the page size:
 * <ol>
 *   <li><b>Choose</b> the facet with a {@code (facet, sort[, ...])} composite whose values hold the fewest entries, from a
 *   per-index cardinality table (entries per leading key value) built by one walk of the index and kept for a TTL; with no
 *   such facet, the index on the sort property alone. Walking the most selective facet minimises the entries read per
 *   accepted row when the other facets are independent of it.</li>
 *   <li><b>Walk</b> one range cursor per value of that facet (an age band is several) bounded by the window and the keyset,
 *   merged in sort order, ties on the sort value broken by RID in the same direction, so pages are stable and resumable.</li>
 *   <li><b>Test</b> the other facets on each record and stop at {@code limit} accepted rows, or after
 *   {@link StructuredQuery#overReadCap()} entries with {@code partial = true} and a keyset that resumes after the last entry read.</li>
 * </ol>
 * Thread-safe; one instance per (type, sort property) is meant to be shared, since it holds the cardinality tables.
 * The sort property must be numeric (epoch milliseconds for {@code tsPosted}).
 */
public final class StructuredSearch {
  /** The design refreshes the cardinality table hourly on a live partition. */
  public static final long DEFAULT_CARDINALITY_TTL_MS = 3_600_000L;

  /** Where a page stopped: the sort value and RID of the last entry read; the next page starts strictly after it. */
  public record Keyset(long sortValue, RID rid) {
  }

  /**
   * One page.
   *
   * @param rows        the accepted records, in order
   * @param partial     the walk hit the over-read cap before filling the page; more matches may exist after {@code next}
   * @param scanned     index entries read for this page
   * @param walkedIndex the index the walk read
   * @param next        where the next page starts, or null when the walk ran out of entries
   */
  public record Result(List<Document> rows, boolean partial, long scanned, String walkedIndex, Keyset next) {
  }

  private record Candidate(TypeIndex index, Facet facet, int sortKeyPosition, boolean byItem, long estimate) {
  }

  private record Cardinality(Map<Object, Long> perValue, long builtAtMillis) {
  }

  private final Database                         database;
  private final String                           typeName;
  private final String                           sortProperty;
  private final long                             cardinalityTtlMillis;
  private final Map<String, Cardinality>         cardinalities = new ConcurrentHashMap<>();

  public StructuredSearch(final Database database, final String typeName, final String sortProperty) {
    this(database, typeName, sortProperty, DEFAULT_CARDINALITY_TTL_MS);
  }

  /** @param cardinalityTtlMillis how long a cardinality table is trusted; 0 rebuilds it on every search (tests only) */
  public StructuredSearch(final Database database, final String typeName, final String sortProperty, final long cardinalityTtlMillis) {
    this.database = database;
    this.typeName = typeName;
    this.sortProperty = sortProperty;
    this.cardinalityTtlMillis = cardinalityTtlMillis;
  }

  /** Drops every cardinality table, so the next search rebuilds what it needs: for a caller that knows the data moved. */
  public void refreshCardinality() {
    cardinalities.clear();
  }

  public Result search(final StructuredQuery query) {
    final DocumentType type = database.getSchema().existsType(typeName) ? database.getSchema().getType(typeName) : null;
    if (type == null)
      throw new IllegalArgumentException("Type '" + typeName + "' does not exist");

    final Candidate walked = choose(type, query);
    final List<Facet> residual = new ArrayList<>(query.facets().size());
    for (final Facet f : query.facets())
      if (f != walked.facet())
        residual.add(f);
    return walk(query, walked, residual);
  }

  // ---------------------------------------------------------------------------------------------------------------------

  private Candidate choose(final DocumentType type, final StructuredQuery query) {
    Candidate best = null;
    TypeIndex sortOnly = null;
    for (final TypeIndex index : type.getAllIndexes(true)) {
      if (!index.supportsOrderedIterations())
        continue;
      final List<String> props = index.getPropertyNames();
      if (props.size() == 1 && props.get(0).equals(sortProperty)) {
        sortOnly = index;
        continue;
      }
      if (props.size() < 2 || !props.get(1).equals(sortProperty))
        continue;
      final String leading = props.get(0);
      final boolean byItem = leading.toLowerCase(Locale.ROOT).endsWith(" by item");
      final String leadingProperty = byItem ? leading.substring(0, leading.length() - " by item".length()) : leading;
      for (final Facet facet : query.facets()) {
        if (!facet.property().equals(leadingProperty))
          continue;
        final Map<Object, Long> counts = cardinality(index);
        long estimate = 0;
        for (final Object v : facet.valuesArray())
          estimate += counts.getOrDefault(Facet.normalize(v), 0L);
        if (best == null || estimate < best.estimate())
          best = new Candidate(index, facet, 1, byItem, estimate);
      }
    }
    if (best != null)
      return best;
    if (sortOnly != null)
      return new Candidate(sortOnly, null, 0, false, Long.MAX_VALUE);
    throw new IllegalArgumentException("No index on type '" + typeName + "' can walk '" + sortProperty
        + "' in order: create one on (" + sortProperty + ") or on (<filter property>, " + sortProperty + ")");
  }

  /** Entries per leading key value of a composite, rebuilt by one ascending walk when older than the TTL. */
  private Map<Object, Long> cardinality(final TypeIndex index) {
    final long now = System.currentTimeMillis();
    final Cardinality cached = cardinalities.get(index.getName());
    if (cached != null && now - cached.builtAtMillis() < cardinalityTtlMillis)
      return cached.perValue();

    final Map<Object, Long> perValue = new HashMap<>();
    final IndexCursor cursor = index.iterator(true);
    try {
      while (cursor.hasNext()) {
        if (cursor.next() == null) // the LSM cursor's hasNext() is optimistic at the tail
          continue;
        final Object[] keys = cursor.getKeys();
        if (keys != null && keys.length > 0 && keys[0] != null)
          perValue.merge(Facet.normalize(keys[0]), 1L, Long::sum);
      }
    } finally {
      cursor.close();
    }
    cardinalities.put(index.getName(), new Cardinality(perValue, now));
    return perValue;
  }

  // ---------------------------------------------------------------------------------------------------------------------

  /** The head entry of one cursor of the merge. */
  private static final class Head {
    final IndexCursor cursor;
    final int         sortKeyPosition;
    long              sort;
    RID               rid;

    Head(final IndexCursor cursor, final int sortKeyPosition) {
      this.cursor = cursor;
      this.sortKeyPosition = sortKeyPosition;
    }

    /** Moves to the next entry with a numeric sort value; false when the cursor is exhausted. */
    boolean advance() {
      while (cursor.hasNext()) {
        final Identifiable next = cursor.next();
        if (next == null)
          continue;
        final Object[] keys = cursor.getKeys();
        if (keys == null || keys.length <= sortKeyPosition || !(keys[sortKeyPosition] instanceof final Number n))
          continue;
        sort = n.longValue();
        rid = next.getIdentity();
        return true;
      }
      cursor.close();
      return false;
    }
  }

  private Result walk(final StructuredQuery query, final Candidate walked, final List<Facet> residual) {
    final boolean desc = query.descending();
    // the keyset narrows the window on the side the walk comes from; entries at the keyset's own sort value are skipped by RID
    final Keyset after = query.after();
    long low = query.windowFrom(), high = query.windowTo();
    if (after != null) {
      if (desc)
        high = Math.min(high, after.sortValue());
      else
        low = Math.max(low, after.sortValue());
    }

    final PriorityQueue<Head> heads = new PriorityQueue<>((a, b) -> compare(a.sort, a.rid, b.sort, b.rid, desc));
    final Object[] values = walked.facet() != null ? walked.facet().valuesArray() : new Object[] { null };
    for (final Object value : values) {
      final Object[] lowKey = value == null ? new Object[] { low } : new Object[] { value, low };
      final Object[] highKey = value == null ? new Object[] { high } : new Object[] { value, high };
      final IndexCursor cursor = desc ? walked.index().range(false, highKey, true, lowKey, true) : walked.index().range(true, lowKey, true, highKey, true);
      final Head head = new Head(cursor, walked.sortKeyPosition());
      if (head.advance())
        heads.add(head);
    }

    // a BY ITEM composite walked on several values reaches a record once per matching item
    final RidHashSet seen = walked.byItem() && values.length > 1 ? new RidHashSet() : null;
    final long cap = query.overReadCap();
    final List<Document> rows = new ArrayList<>(Math.min(query.limit(), 1_024));
    final List<RID> tieGroup = new ArrayList<>();
    long scanned = 0;
    Keyset lastRead = null;

    try {
      while (!heads.isEmpty()) {
        // every entry with the head's sort value, from every cursor, ordered by RID in the walk's direction
        final long groupSort = heads.peek().sort;
        tieGroup.clear();
        while (!heads.isEmpty() && heads.peek().sort == groupSort) {
          final Head h = heads.poll();
          tieGroup.add(h.rid);
          if (h.advance())
            heads.add(h);
        }
        if (tieGroup.size() > 1)
          tieGroup.sort((a, b) -> compare(groupSort, a, groupSort, b, desc));

        for (final RID rid : tieGroup) {
          if (after != null && compare(groupSort, rid, after.sortValue(), after.rid(), desc) <= 0)
            continue; // at or before the keyset: read by an earlier page
          if (seen != null && !seen.add(rid))
            continue;
          if (scanned >= cap)
            return new Result(rows, true, scanned, walked.index().getName(), lastRead);
          scanned++;
          lastRead = new Keyset(groupSort, rid);

          final Document doc;
          try {
            doc = rid.asDocument();
          } catch (final RecordNotFoundException e) {
            continue; // deleted after the index entry was read
          }
          if (accepts(doc, residual)) {
            rows.add(doc);
            if (rows.size() == query.limit())
              return new Result(rows, false, scanned, walked.index().getName(), lastRead);
          }
        }
      }
      return new Result(rows, false, scanned, walked.index().getName(), null);
    } finally {
      for (final Head h : heads)
        h.cursor.close();
    }
  }

  private static boolean accepts(final Document doc, final List<Facet> residual) {
    for (final Facet f : residual)
      if (!f.matches(doc.get(f.property())))
        return false;
    return true;
  }

  /** The walk's order: by sort value, then by RID (bucket, position), both in the walk's direction. Negative = comes first. */
  private static int compare(final long sortA, final RID ridA, final long sortB, final RID ridB, final boolean desc) {
    int c = Long.compare(sortA, sortB);
    if (c == 0)
      c = Integer.compare(ridA.getBucketId(), ridB.getBucketId());
    if (c == 0)
      c = Long.compare(ridA.getPosition(), ridB.getPosition());
    return desc ? -c : c;
  }
}
