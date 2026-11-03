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
  public static final int DEFAULT_OVER_READ = 40;

  private final boolean                 descending;
  private final int                     limit;
  private final List<Facet>             facets;
  private final long                    windowFrom;
  private final long                    windowTo;
  private final int                     overRead;
  private final StructuredSearch.Keyset after;

  private StructuredQuery(final boolean descending, final int limit, final List<Facet> facets, final long windowFrom, final long windowTo,
      final int overRead, final StructuredSearch.Keyset after) {
    this.descending = descending;
    this.limit = limit;
    this.facets = facets;
    this.windowFrom = windowFrom;
    this.windowTo = windowTo;
    this.overRead = overRead;
    this.after = after;
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
    return new StructuredQuery(descending, limit, List.of(), Long.MIN_VALUE, Long.MAX_VALUE, DEFAULT_OVER_READ, null);
  }

  public StructuredQuery where(final Facet facet) {
    if (facet == null)
      throw new IllegalArgumentException("facet is null");
    final List<Facet> more = new ArrayList<>(facets.size() + 1);
    more.addAll(facets);
    more.add(facet);
    return new StructuredQuery(descending, limit, Collections.unmodifiableList(more), windowFrom, windowTo, overRead, after);
  }

  /** Only records whose sort value is between {@code from} and {@code to}, both included. */
  public StructuredQuery window(final long from, final long to) {
    if (from > to)
      throw new IllegalArgumentException("window " + from + ".." + to + " is empty");
    return new StructuredQuery(descending, limit, facets, from, to, overRead, after);
  }

  /** The walk reads at most {@code factor} x limit index entries, then answers what it found with {@code partial = true}. */
  public StructuredQuery overRead(final int factor) {
    if (factor < 1)
      throw new IllegalArgumentException("over-read factor must be at least 1, got " + factor);
    return new StructuredQuery(descending, limit, facets, windowFrom, windowTo, factor, after);
  }

  /** The next page: everything after {@code keyset} in this query's order, from {@link StructuredSearch.Result#next()}. */
  public StructuredQuery after(final StructuredSearch.Keyset keyset) {
    return new StructuredQuery(descending, limit, facets, windowFrom, windowTo, overRead, keyset);
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

  /** The most index entries one page may read: limit x over-read, saturating. */
  long overReadCap() {
    final long cap = (long) limit * overRead;
    return cap < 0 ? Long.MAX_VALUE : cap;
  }

  @Override
  public String toString() {
    return (descending ? "newest" : "oldest") + " " + limit + " where " + facets + (windowFrom != Long.MIN_VALUE || windowTo != Long.MAX_VALUE ?
        " window " + windowFrom + ".." + windowTo :
        "") + (after != null ? " after " + after : "") + " overRead " + overRead;
  }
}
