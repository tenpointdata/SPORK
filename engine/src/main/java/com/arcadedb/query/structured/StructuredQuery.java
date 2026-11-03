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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One page of a structured search: the facets ANDed together, a window on the sort property, the direction, the page size,
 * where the previous page stopped, and how far past the page size the walk may read before it answers {@code partial}.
 * Immutable: every {@code with}-style method returns a copy, so a first page can be kept and resumed with {@link #after}.
 */
public final class StructuredQuery {
  /** {@code docs/spork/02-retrieval-speed.md} 2.4: the posted arm reads at most 40 x limit entries before it answers partial. */
  public static final int DEFAULT_OVER_READ      = 40;
  /** Index entries a count may read past the page before it answers a lower bound with {@code totalPartial}. */
  public static final int DEFAULT_COUNT_BUDGET   = 20_000;
  /** 2.4, P4v: entries the rank walk reads for one page before it falls back to the time arm. */
  public static final int DEFAULT_RANK_SCAN_CAP  = 10_000;
  /** 2.4, P4v: matches the fallback takes from the time arm and sorts by rank; more than this is {@code partial}. */
  public static final int DEFAULT_RANK_FALLBACK  = 10_000;

  private boolean                 descending;
  private int                     limit;
  private List<Facet>             facets;
  private long                    windowFrom;
  private long                    windowTo;
  private int                     overRead;
  private StructuredSearch.Keyset after;
  private int                     countCap;
  private int                     countBudget;
  private String                  rankProperty;
  private int                     rankScanCap;
  private int                     rankFallbackCap;
  private RecordFilter            filter;

  private StructuredQuery(final boolean descending, final int limit) {
    this.descending = descending;
    this.limit = limit;
    this.facets = List.of();
    this.windowFrom = Long.MIN_VALUE;
    this.windowTo = Long.MAX_VALUE;
    this.overRead = DEFAULT_OVER_READ;
    this.countBudget = DEFAULT_COUNT_BUDGET;
    this.rankScanCap = DEFAULT_RANK_SCAN_CAP;
    this.rankFallbackCap = DEFAULT_RANK_FALLBACK;
  }

  /** A copy to change before it is returned: every public method hands out a new query and never alters this one. */
  private StructuredQuery copy() {
    final StructuredQuery q = new StructuredQuery(descending, limit);
    q.facets = facets;
    q.windowFrom = windowFrom;
    q.windowTo = windowTo;
    q.overRead = overRead;
    q.after = after;
    q.countCap = countCap;
    q.countBudget = countBudget;
    q.rankProperty = rankProperty;
    q.rankScanCap = rankScanCap;
    q.rankFallbackCap = rankFallbackCap;
    q.filter = filter;
    return q;
  }

  public static StructuredQuery newestFirst(final int limit) {
    return create(true, limit);
  }

  public static StructuredQuery oldestFirst(final int limit) {
    return create(false, limit);
  }

  private static StructuredQuery create(final boolean descending, final int limit) {
    if (limit < 1)
      throw new IllegalArgumentException("limit must be at least 1, got " + limit);
    return new StructuredQuery(descending, limit);
  }

  public StructuredQuery where(final Facet facet) {
    if (facet == null)
      throw new IllegalArgumentException("facet is null");
    final List<Facet> more = new ArrayList<>(facets.size() + 1);
    more.addAll(facets);
    more.add(facet);
    final StructuredQuery q = copy();
    q.facets = Collections.unmodifiableList(more);
    return q;
  }

  /** Only records whose sort value is between {@code from} and {@code to}, both included. */
  public StructuredQuery window(final long from, final long to) {
    if (from > to)
      throw new IllegalArgumentException("window " + from + ".." + to + " is empty");
    final StructuredQuery q = copy();
    q.windowFrom = from;
    q.windowTo = to;
    return q;
  }

  /** The walk reads at most {@code factor} x limit index entries, then answers what it found with {@code partial = true}. */
  public StructuredQuery overRead(final int factor) {
    if (factor < 1)
      throw new IllegalArgumentException("over-read factor must be at least 1, got " + factor);
    final StructuredQuery q = copy();
    q.overRead = factor;
    return q;
  }

  /** The next page: everything after {@code keyset} in this query's order, from {@link StructuredSearch.Result#next()}. */
  public StructuredQuery after(final StructuredSearch.Keyset keyset) {
    if (rankProperty != null && keyset != null)
      throw new IllegalArgumentException("A rank-ordered search has no keyset: page it by limit");
    final StructuredQuery q = copy();
    q.after = keyset;
    return q;
  }

  /**
   * Also count the matches, up to {@code cap}: {@link StructuredSearch.Result#total()} is then exact up to the cap and
   * {@code totalCapped} says more match. 0, the default, counts nothing. With {@link #after}, the count covers this page and what
   * follows it.
   */
  public StructuredQuery count(final int cap) {
    if (cap < 0)
      throw new IllegalArgumentException("count cap must not be negative, got " + cap);
    final StructuredQuery q = copy();
    q.countCap = cap;
    return q;
  }

  /** The count reads at most this many index entries past the page, then answers a lower bound with {@code totalPartial}. */
  public StructuredQuery countBudget(final int entries) {
    if (entries < 1)
      throw new IllegalArgumentException("count budget must be at least 1, got " + entries);
    final StructuredQuery q = copy();
    q.countBudget = entries;
    return q;
  }

  /**
   * Order by {@code property} descending, then by the sort property descending, then RID: the {@code views_desc} order. The window
   * still bounds the sort property and is checked on each index key. Needs a {@code (facet, property, sort)} or
   * {@code (property, sort)} composite; newest-first only, and without a keyset.
   */
  public StructuredQuery rankBy(final String property) {
    if (property == null || property.isBlank())
      throw new IllegalArgumentException("rank property is blank");
    if (!descending)
      throw new IllegalArgumentException("A rank order is descending: start from newestFirst");
    if (after != null)
      throw new IllegalArgumentException("A rank-ordered search has no keyset: page it by limit");
    final StructuredQuery q = copy();
    q.rankProperty = property;
    return q;
  }

  /** Entries the rank walk reads before it falls back to the time arm (2.4, P4v). */
  public StructuredQuery rankScanCap(final int entries) {
    if (entries < 1)
      throw new IllegalArgumentException("rank scan cap must be at least 1, got " + entries);
    final StructuredQuery q = copy();
    q.rankScanCap = entries;
    return q;
  }

  /** Matches the rank fallback sorts in memory; a window holding more answers {@code partial}. */
  public StructuredQuery rankFallbackCap(final int matches) {
    if (matches < 1)
      throw new IllegalArgumentException("rank fallback cap must be at least 1, got " + matches);
    final StructuredQuery q = copy();
    q.rankFallbackCap = matches;
    return q;
  }

  /** A per-record condition on rows and counts; null removes it. */
  public StructuredQuery filter(final RecordFilter filter) {
    final StructuredQuery q = copy();
    q.filter = filter;
    return q;
  }

  /** The time-ordered search the rank order counts with and falls back to: no rank, no keyset, newest first. */
  StructuredQuery timeArm(final int pageLimit, final int cap) {
    final StructuredQuery q = copy();
    q.rankProperty = null;
    q.after = null;
    q.limit = pageLimit;
    q.countCap = cap;
    return q;
  }

  public boolean descending() {
    return descending;
  }

  public int limit() {
    return limit;
  }

  public List<Facet> facets() {
    return facets;
  }

  public long windowFrom() {
    return windowFrom;
  }

  public long windowTo() {
    return windowTo;
  }

  public int overReadFactor() {
    return overRead;
  }

  public StructuredSearch.Keyset after() {
    return after;
  }

  public int countCap() {
    return countCap;
  }

  public int countBudget() {
    return countBudget;
  }

  public String rankProperty() {
    return rankProperty;
  }

  public int rankScanCap() {
    return rankScanCap;
  }

  public int rankFallbackCap() {
    return rankFallbackCap;
  }

  public RecordFilter filter() {
    return filter;
  }

  /** The most index entries one page may read: limit x over-read, saturating. */
  long overReadCap() {
    final long cap = (long) Math.max(limit, 1) * overRead;
    return cap < 0 ? Long.MAX_VALUE : cap;
  }

  @Override
  public String toString() {
    return (rankProperty != null ? "by " + rankProperty + " desc" : descending ? "newest" : "oldest") + " " + limit + " where "
        + facets + (windowFrom != Long.MIN_VALUE || windowTo != Long.MAX_VALUE ? " window " + windowFrom + ".." + windowTo : "")
        + (after != null ? " after " + after : "") + " overRead " + overRead + (countCap > 0 ? " count " + countCap : "");
  }
}
