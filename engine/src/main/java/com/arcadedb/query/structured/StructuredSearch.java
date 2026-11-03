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
   * @param rows          the accepted records, in order
   * @param partial       the walk hit a read cap before filling the page; more matches may exist after {@code next}
   * @param scanned       index entries read for this page and its count
   * @param walkedIndex   the index the walk read
   * @param next          where the next page starts, or null when the walk ran out of entries (always null in rank order)
   * @param total         matches counted, at most {@link StructuredQuery#countCap()}; -1 when no count was asked
   * @param totalCapped   more than the cap match
   * @param totalPartial  the count stopped at its budget (or the page at its over-read cap): {@code total} is a lower bound
   * @param totalEstimate when {@code totalPartial}, the count scaled from the walked facet's size; otherwise {@code total}
   * @param recordsRead   records read, for the page and the count
   */
  public record Result(List<Document> rows, boolean partial, long scanned, String walkedIndex, Keyset next, long total,
                       boolean totalCapped, boolean totalPartial, long totalEstimate, long recordsRead) {
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

    // an estimated total replaces the count: the page is searched without one
    final long tableTotal = query.estimatesTotal() ? tableTotal(type, query) : -1L;
    final StructuredQuery paged = tableTotal >= 0 && query.countCap() > 0 ? query.count(0) : query;

    final Result result;
    if (paged.rankProperty() != null)
      result = rank(type, paged);
    else {
      final Candidate walked = choose(type, paged);
      result = walk(paged, walked, residual(paged, walked));
    }
    if (tableTotal < 0)
      return result;
    return new Result(result.rows(), result.partial(), result.scanned(), result.walkedIndex(), result.next(), -1L, false, true, tableTotal,
        result.recordsRead());
  }

  /**
   * The search's total from statistics, or -1 when it has none: {@link StructuredQuery#estimateTotal()} lists when it does. No
   * facet: the type's record count (the buckets' cached counters). One facet: the entries per value of an ordered index the
   * facet leads, summed over its values, from the same cardinality table the walk chooses its index with.
   */
  private long tableTotal(final DocumentType type, final StructuredQuery query) {
    if (query.windowFrom() != Long.MIN_VALUE || query.windowTo() != Long.MAX_VALUE)
      return -1L;
    if (query.filter() != null && !query.filter().hidesNothing())
      return -1L;
    final List<Facet> facets = query.facets();
    if (facets.isEmpty())
      return database.countType(typeName, true);
    if (facets.size() > 1)
      return -1L;

    final Facet facet = facets.get(0);
    TypeIndex counted = null;
    boolean byItem = false;
    for (final TypeIndex index : type.getAllIndexes(true)) {
      if (!index.supportsOrderedIterations())
        continue;
      final String leading = index.getPropertyNames().get(0);
      final boolean item = leading.toLowerCase(Locale.ROOT).endsWith(" by item");
      final String leadingProperty = item ? leading.substring(0, leading.length() - " by item".length()) : leading;
      if (!leadingProperty.equals(facet.property()))
        continue;
      // prefer a table the walk has already built
      if (counted == null || cardinalities.containsKey(index.getName()) && !cardinalities.containsKey(counted.getName())) {
        counted = index;
        byItem = item;
      }
    }
    // a record listing two of the values is one entry per value: the sum would count it twice
    if (counted == null || byItem && facet.valuesArray().length > 1)
      return -1L;

    final Map<Object, Long> counts = cardinality(counted);
    long total = 0;
    for (final Object v : facet.valuesArray())
      total += counts.getOrDefault(Facet.normalize(v), 0L);
    return total;
  }

  private static List<Facet> residual(final StructuredQuery query, final Candidate walked) {
    final List<Facet> residual = new ArrayList<>(query.facets().size());
    for (final Facet f : query.facets())
      if (f != walked.facet())
        residual.add(f);
    return residual;
  }

  // ---------------------------------------------------------------------------------------------------------------------

  private Candidate choose(final DocumentType type, final StructuredQuery query) {
    Candidate best = null;
    TypeIndex sortOnly = null;
    for (final TypeIndex index : type.getAllIndexes(true)) {
      if (!index.supportsOrderedIterations())
        continue;
      final List<String> props = index.getPropertyNames();
      if (props.get(0).equals(sortProperty)) {
        // (sort) or (sort, ...): the unfiltered walk, the shortest such index preferred
        if (sortOnly == null || props.size() < sortOnly.getPropertyNames().size())
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
    final RecordFilter filter = query.filter();
    final long cap = query.overReadCap();
    final List<Document> rows = new ArrayList<>(Math.min(query.limit(), 1_024));
    final List<RID> tieGroup = new ArrayList<>();
    final Count count = query.countCap() > 0 ? new Count(query, residual, filter, seen) : null;
    long scanned = 0;
    long read = 0;
    Keyset lastRead = null;

    try {
      if (query.limit() == 0) {
        // a count alone (the rank order's): no page, so no order to keep
        if (count != null)
          count.drain(heads);
        return result(rows, false, scanned, walked, null, count, read, false);
      }
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

        for (int i = 0; i < tieGroup.size(); i++) {
          final RID rid = tieGroup.get(i);
          if (after != null && compare(groupSort, rid, after.sortValue(), after.rid(), desc) <= 0)
            continue; // at or before the keyset: read by an earlier page
          if (seen != null && !seen.add(rid))
            continue;
          if (scanned >= cap)
            return result(rows, true, scanned, walked, lastRead, count, read, true);
          scanned++;
          lastRead = new Keyset(groupSort, rid);

          final Document doc;
          try {
            doc = rid.asDocument();
            read++;
          } catch (final RecordNotFoundException e) {
            continue; // deleted after the index entry was read
          }
          if (accepts(doc, residual) && (filter == null || filter.accepts(doc))) {
            rows.add(doc);
            if (rows.size() == query.limit()) {
              if (count != null) {
                count.matched = rows.size();
                // the rest of this tie group, then every cursor drained in any order: a count needs no order
                for (int j = i + 1; j < tieGroup.size() && count.add(tieGroup.get(j)); j++) {
                }
                count.drain(heads);
              }
              return result(rows, false, scanned, walked, lastRead, count, read, false);
            }
          }
        }
      }
      if (count != null)
        count.matched = rows.size();
      return result(rows, false, scanned, walked, null, count, read, false);
    } finally {
      for (final Head h : heads)
        h.cursor.close();
    }
  }

  private static Result result(final List<Document> rows, final boolean partial, final long scanned, final Candidate walked,
      final Keyset next, final Count count, final long read, final boolean pageCut) {
    if (count == null)
      return new Result(rows, partial, scanned, walked.index().getName(), next, -1L, false, false, -1L, read);
    if (pageCut)
      count.matched = rows.size();
    final boolean totalPartial = pageCut || count.budgetSpent;
    final long total = Math.min(count.matched, count.cap);
    long estimate = total;
    final long entries = scanned + count.entries;
    if (totalPartial && walked.estimate() != Long.MAX_VALUE && entries > 0)
      estimate = Math.min(count.cap, Math.max(count.matched, count.matched * walked.estimate() / entries));
    return new Result(rows, partial, entries, walked.index().getName(), next, total, count.matched > count.cap, totalPartial, estimate,
        read + count.read);
  }

  /**
   * The count past the page: up to the cap, at most the budget of entries. An entry is counted on the index alone when no
   * residual facet and no filter's {@link RecordFilter#mustRead} asks for its record; otherwise its record is read and tested.
   */
  private static final class Count {
    final int          cap;
    final int          budget;
    final List<Facet>  residual;
    final RecordFilter filter;
    final RidHashSet   seen;
    final boolean      indexOnly;
    long               matched;
    long               entries;
    long               read;
    boolean            budgetSpent;

    Count(final StructuredQuery query, final List<Facet> residual, final RecordFilter filter, final RidHashSet seen) {
      this.cap = query.countCap();
      this.budget = query.countBudget();
      this.residual = residual;
      this.filter = filter;
      this.seen = seen;
      this.indexOnly = residual.isEmpty();
    }

    /** False once the count is done: past the cap or out of budget. */
    boolean add(final RID rid) {
      if (seen != null && !seen.add(rid))
        return true;
      if (++entries > budget) {
        budgetSpent = true;
        return false;
      }
      if (indexOnly && (filter == null || !filter.mustRead(rid)))
        matched++;
      else {
        final Document doc;
        try {
          doc = rid.asDocument();
          read++;
        } catch (final RecordNotFoundException e) {
          return true;
        }
        if (accepts(doc, residual) && (filter == null || filter.accepts(doc)))
          matched++;
      }
      return matched <= cap;
    }

    /** Each cursor's current entry, then the rest of it, reading no key: the range already bounds the window. */
    void drain(final PriorityQueue<Head> heads) {
      for (final Head h : heads) {
        if (!add(h.rid))
          return;
        while (h.cursor.hasNext()) {
          final Identifiable next = h.cursor.next();
          if (next != null && !add(next.getIdentity()))
            return;
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------

  /** A rank-order candidate: the {@code (facet, rank, sort)} composite of a facet, or {@code (rank, sort)} for none. */
  private Candidate chooseRank(final DocumentType type, final StructuredQuery query) {
    final String rank = query.rankProperty();
    Candidate best = null;
    TypeIndex rankOnly = null;
    for (final TypeIndex index : type.getAllIndexes(true)) {
      if (!index.supportsOrderedIterations())
        continue;
      final List<String> props = index.getPropertyNames();
      if (props.size() == 2 && props.get(0).equals(rank) && props.get(1).equals(sortProperty)) {
        rankOnly = index;
        continue;
      }
      if (props.size() < 3 || !props.get(1).equals(rank) || !props.get(2).equals(sortProperty))
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
    if (rankOnly != null)
      return new Candidate(rankOnly, null, 0, false, Long.MAX_VALUE);
    throw new IllegalArgumentException("No index on type '" + typeName + "' orders '" + rank + "' then '" + sortProperty
        + "': create one on (" + rank + ", " + sortProperty + ") or on (<filter property>, " + rank + ", " + sortProperty + ")");
  }

  /** The head entry of one rank cursor: the rank and the sort value from the key. */
  private static final class RankHead {
    final IndexCursor cursor;
    final int         rankKeyPosition;
    long              rank;
    long              sort;
    RID               rid;

    RankHead(final IndexCursor cursor, final int rankKeyPosition) {
      this.cursor = cursor;
      this.rankKeyPosition = rankKeyPosition;
    }

    boolean advance() {
      while (cursor.hasNext()) {
        final Identifiable next = cursor.next();
        if (next == null)
          continue;
        final Object[] keys = cursor.getKeys();
        if (keys == null || keys.length <= rankKeyPosition + 1 || !(keys[rankKeyPosition] instanceof final Number r)
            || !(keys[rankKeyPosition + 1] instanceof final Number t))
          continue;
        rank = r.longValue();
        sort = t.longValue();
        rid = next.getIdentity();
        return true;
      }
      cursor.close();
      return false;
    }
  }

  /**
   * The rank order (2.4, P4v): the {@code (facet, rank, sort)} composite descending, the window checked on each key's sort value,
   * until the page is full or {@link StructuredQuery#rankScanCap()} entries are read. Past that, the window's matches come from
   * the time arm, up to {@link StructuredQuery#rankFallbackCap()}, sorted here; {@code partial} when the window holds more. The
   * count is the time arm's.
   */
  private Result rank(final DocumentType type, final StructuredQuery query) {
    final Candidate walked = chooseRank(type, query);
    final List<Facet> residual = residual(query, walked);
    final RecordFilter filter = query.filter();
    final PriorityQueue<RankHead> heads = new PriorityQueue<>((a, b) -> compareRank(a, b));
    final Object[] values = walked.facet() != null ? walked.facet().valuesArray() : new Object[] { null };
    for (final Object value : values) {
      final IndexCursor cursor = value == null ? walked.index().iterator(false) :
          walked.index().range(false, new Object[] { value }, true, new Object[] { value }, true);
      final RankHead head = new RankHead(cursor, walked.sortKeyPosition());
      if (head.advance())
        heads.add(head);
    }
    final RidHashSet seen = walked.byItem() && values.length > 1 ? new RidHashSet() : null;

    final List<Document> rows = new ArrayList<>(Math.min(query.limit(), 1_024));
    long scanned = 0;
    long read = 0;
    boolean overflow = false;
    try {
      while (!heads.isEmpty() && rows.size() < query.limit()) {
        final RankHead h = heads.poll();
        final RID rid = h.rid;
        final long sort = h.sort;
        if (h.advance())
          heads.add(h);
        if (++scanned > query.rankScanCap()) {
          overflow = true;
          break;
        }
        if (sort < query.windowFrom() || sort > query.windowTo() || seen != null && !seen.add(rid))
          continue;
        final Document doc;
        try {
          doc = rid.asDocument();
          read++;
        } catch (final RecordNotFoundException e) {
          continue;
        }
        if (accepts(doc, residual) && (filter == null || filter.accepts(doc)))
          rows.add(doc);
      }
    } finally {
      for (final RankHead h : heads)
        h.cursor.close();
    }

    final Result counted = query.countCap() > 0 ? search(query.timeArm(0, query.countCap())) : null;
    if (!overflow)
      return withCount(rows, false, scanned, walked.index().getName(), read, counted);

    // a narrow window the rank order cannot find quickly: its matches by time, sorted by rank here
    final Result window = search(query.timeArm(query.rankFallbackCap() + 1, 0));
    final String rankProperty = query.rankProperty();
    final List<Document> all = new ArrayList<>(window.rows());
    all.sort((a, b) -> {
      int c = Long.compare(number(b.get(rankProperty)), number(a.get(rankProperty)));
      if (c == 0)
        c = Long.compare(number(b.get(sortProperty)), number(a.get(sortProperty)));
      return c != 0 ? c : -compare(0, a.getIdentity(), 0, b.getIdentity(), false);
    });
    final boolean partial = window.partial() || all.size() > query.rankFallbackCap();
    return withCount(new ArrayList<>(all.subList(0, Math.min(query.limit(), all.size()))), partial, scanned + window.scanned(),
        window.walkedIndex(), read + window.recordsRead(), counted);
  }

  private static Result withCount(final List<Document> rows, final boolean partial, final long scanned, final String walkedIndex,
      final long read, final Result counted) {
    if (counted == null)
      return new Result(rows, partial, scanned, walkedIndex, null, -1L, false, false, -1L, read);
    return new Result(rows, partial, scanned + counted.scanned(), walkedIndex, null, counted.total(), counted.totalCapped(),
        counted.totalPartial(), counted.totalEstimate(), read + counted.recordsRead());
  }

  /** Rank descending, then sort value descending, then RID descending. */
  private static int compareRank(final RankHead a, final RankHead b) {
    int c = Long.compare(b.rank, a.rank);
    if (c == 0)
      c = Long.compare(b.sort, a.sort);
    return c != 0 ? c : compare(0, a.rid, 0, b.rid, true);
  }

  private static long number(final Object value) {
    return value instanceof final Number n ? n.longValue() : Long.MIN_VALUE;
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
