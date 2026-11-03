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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How the partitions' totals add up into the answer's total, totalExact, totalCapped and totalEstimate. */
class SearchTotalsTest {

  private static StructuredSearch.Result counted(final long total, final boolean partial, final long estimate) {
    return new StructuredSearch.Result(List.of(), false, 0, "i", null, total, false, partial, estimate, 0);
  }

  private static StructuredSearch.Result table(final long estimate) {
    return new StructuredSearch.Result(List.of(), false, 0, "i", null, -1L, false, true, estimate, 0);
  }

  @Test
  void exactCountsAddUp() {
    final SearchTotals totals = new SearchTotals(10_000, true);
    totals.add(counted(120, false, 120));
    totals.add(counted(30, false, 30));

    assertThat(totals.total()).isEqualTo(150L);
    assertThat(totals.exact()).isTrue();
    assertThat(totals.estimate()).isEqualTo(150L);
    assertThat(totals.capped()).isFalse();
  }

  @Test
  void aBudgetedCountIsALowerBoundWithItsEstimate() {
    final SearchTotals totals = new SearchTotals(10_000, true);
    totals.add(counted(2_000, true, 7_400));
    totals.add(counted(15, false, 15));

    assertThat(totals.total()).as("N+").isEqualTo(2_015L);
    assertThat(totals.exact()).isFalse();
    assertThat(totals.estimate()).isEqualTo(7_415L);
  }

  @Test
  void aWindowedBudgetedCountHasNoEstimate() {
    final SearchTotals totals = new SearchTotals(10_000, false);
    totals.add(counted(2_000, true, 9_000));

    assertThat(totals.total()).isEqualTo(2_000L);
    assertThat(totals.estimate()).as("the core's scaling ignores the window").isEqualTo(-1L);
  }

  @Test
  void aTableAnswerMeansNothingWasCounted() {
    final SearchTotals totals = new SearchTotals(10_000, true);
    totals.add(table(4_200));
    totals.add(counted(300, false, 300));

    assertThat(totals.total()).as("not counted: about N").isEqualTo(-1L);
    assertThat(totals.exact()).isFalse();
    assertThat(totals.estimate()).isEqualTo(4_500L);
    assertThat(totals.capped()).isFalse();
  }

  @Test
  void anEstimatePastTheCapIsCapped() {
    final SearchTotals totals = new SearchTotals(10_000, true);
    totals.add(table(250_000));

    assertThat(totals.estimate()).isEqualTo(10_000L);
    assertThat(totals.capped()).as("10,000+").isTrue();
  }

  @Test
  void anExactCountPastTheCapIsCapped() {
    final SearchTotals totals = new SearchTotals(10_000, true);
    totals.add(counted(8_000, false, 8_000));
    totals.add(new StructuredSearch.Result(List.of(), false, 0, "i", null, 2_000, true, false, 2_000, 0));

    assertThat(totals.total()).isEqualTo(10_000L);
    assertThat(totals.exact()).isTrue();
    assertThat(totals.capped()).isTrue();
    assertThat(totals.capLeft()).isZero();
  }
}
