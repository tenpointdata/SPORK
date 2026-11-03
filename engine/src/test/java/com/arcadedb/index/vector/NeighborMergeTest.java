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
package com.arcadedb.index.vector;

import com.arcadedb.database.RID;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NeighborMerge} must read exactly what the multi-index searches used to produce: the concatenation of the per-index
 * answers sorted (stably) by distance, ties in concatenation order.
 */
class NeighborMergeTest {

  @Test
  void mergeEqualsTheStableSortOfTheConcatenationIncludingTies() {
    final Random rng = new Random(42);
    for (int round = 0; round < 500; round++) {
      final int sources = 1 + rng.nextInt(12);
      final List<List<Pair<RID, Float>>> lists = new ArrayList<>();
      int rid = 0;
      for (int s = 0; s < sources; s++) {
        final int n = rng.nextInt(20);
        final List<Pair<RID, Float>> list = new ArrayList<>();
        for (int i = 0; i < n; i++)
          // Few distinct values, so ties across and within sources are the common case
          list.add(new Pair<>(new RID(s, rid++), (float) rng.nextInt(6) - 1.5f));
        list.sort(Comparator.comparing(Pair::getSecond));
        lists.add(list);
      }

      assertThat(read(NeighborMerge.of(lists))).containsExactlyElementsOf(concatenatedAndSorted(lists));
      assertThat(NeighborMerge.of(lists).size()).isEqualTo(concatenatedAndSorted(lists).size());
    }
  }

  @Test
  void anUnsortedSourceIsMergedAsTheSortOfTheConcatenationWouldHaveIt() {
    final List<Pair<RID, Float>> unsorted = List.of(new Pair<>(new RID(1, 0), 3f), new Pair<>(new RID(1, 1), 1f),
        new Pair<>(new RID(1, 2), 1f), new Pair<>(new RID(1, 3), 0.5f));
    final List<Pair<RID, Float>> sorted = List.of(new Pair<>(new RID(2, 0), 1f), new Pair<>(new RID(2, 1), 2f));
    final List<List<Pair<RID, Float>>> lists = List.of(unsorted, sorted);

    assertThat(read(NeighborMerge.of(lists))).containsExactlyElementsOf(concatenatedAndSorted(lists));
  }

  @Test
  void specialDistancesOrderAsFloatCompareOrdersThem() {
    final List<Pair<RID, Float>> a = List.of(new Pair<>(new RID(1, 0), -0.0f), new Pair<>(new RID(1, 1), Float.MAX_VALUE),
        new Pair<>(new RID(1, 2), Float.NaN));
    final List<Pair<RID, Float>> b = List.of(new Pair<>(new RID(2, 0), -1f), new Pair<>(new RID(2, 1), 0.0f),
        new Pair<>(new RID(2, 2), Float.POSITIVE_INFINITY));
    final List<List<Pair<RID, Float>>> lists = List.of(a, b);

    assertThat(read(NeighborMerge.of(lists))).containsExactlyElementsOf(concatenatedAndSorted(lists));
  }

  @Test
  void emptyAndMissingSourcesContributeNothing() {
    final List<List<Pair<RID, Float>>> lists = new ArrayList<>();
    lists.add(List.of());
    lists.add(null);
    lists.add(List.of(new Pair<>(new RID(3, 0), 1f)));
    final NeighborMerge merge = NeighborMerge.of(lists);

    assertThat(merge.size()).isEqualTo(1);
    assertThat(read(merge)).extracting(Pair::getFirst).containsExactly(new RID(3, 0));
    assertThat(read(NeighborMerge.of(List.of()))).isEmpty();
  }

  private static List<Pair<RID, Float>> read(final NeighborMerge merge) {
    final List<Pair<RID, Float>> out = new ArrayList<>();
    for (final Pair<RID, Float> p : merge)
      out.add(p);
    return out;
  }

  private static List<Pair<RID, Float>> concatenatedAndSorted(final List<List<Pair<RID, Float>>> lists) {
    final List<Pair<RID, Float>> all = new ArrayList<>();
    for (final List<Pair<RID, Float>> l : lists)
      if (l != null)
        all.addAll(l);
    all.sort(Comparator.comparing(Pair::getSecond));
    return all;
  }
}
