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

import com.arcadedb.query.structured.StructuredSearch;

/**
 * The search answer's total over every partition it walked. Each partition answers one of three ways: an exact count, a
 * budgeted count that stopped early (a lower bound and an estimate scaled from the driving facet), or a total read from the
 * core's cardinality table, where nothing was counted. The answer's {@code total} is the counted matches, or -1 when any
 * partition was answered from the table; {@code totalEstimate} is what a client shows as "about N".
 */
final class SearchTotals {
  private final int     cap;
  private final boolean estimable;
  private long          counted;
  private long          estimate;
  private boolean       exact = true;
  private boolean       capped;
  private boolean       fromTable;

  /** @param estimable false when a posted window applies: the core's scaled estimate ignores it */
  SearchTotals(final int cap, final boolean estimable) {
    this.cap = cap;
    this.estimable = estimable;
  }

  void add(final StructuredSearch.Result partition) {
    if (counted >= cap) {
      // the cap was reached before this partition, whose count (of 1) or table only says whether more match
      capped |= partition.total() > 0 || partition.total() < 0 && partition.totalEstimate() > 0;
      return;
    }
    if (partition.total() < 0) {
      fromTable = true;
      exact = false;
      estimate += partition.totalEstimate();
      return;
    }
    counted += partition.total();
    capped |= partition.totalCapped();
    if (partition.totalPartial()) {
      exact = false;
      estimate += partition.totalEstimate();
    } else
      estimate += partition.total();
  }

  /** What the cap leaves for the next partition's count. */
  long capLeft() {
    return Math.max(0, cap - counted);
  }

  /** The counted matches up to the cap, or -1 when a partition's total came from the table and was not counted. */
  long total() {
    return fromTable ? -1L : Math.min(counted, cap);
  }

  boolean exact() {
    return exact;
  }

  /** More than the cap match: counted, or estimated when the total is not exact. */
  boolean capped() {
    return capped || !exact && (fromTable || estimable) && estimate > cap;
  }

  /** The total when exact; otherwise the estimate up to the cap, or -1 when there is none to trust. */
  long estimate() {
    if (exact)
      return total();
    if (!fromTable && !estimable)
      return -1L;
    return Math.min(cap, Math.max(Math.min(counted, cap), estimate));
  }
}
