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
package com.arcadedb.benchmarks.retrieval;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipfSamplerTest {
  @Test
  void massesSumToOneAndDecreaseWithRank() {
    final ZipfSampler zipf = new ZipfSampler(1000, 1.0);
    double sum = 0.0;
    for (int r = 0; r < 1000; r++) {
      sum += zipf.probability(r);
      if (r > 0)
        assertThat(zipf.probability(r)).isLessThan(zipf.probability(r - 1));
    }
    assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void extremeDrawsLandOnFirstAndLastRank() {
    final ZipfSampler zipf = new ZipfSampler(50, 0.8);
    assertThat(zipf.sample(0.0)).isZero();
    assertThat(zipf.sample(Math.nextDown(1.0))).isEqualTo(49);
    assertThatThrownBy(() -> zipf.sample(1.0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void headFrequencyMatchesTheMass() {
    final ZipfSampler zipf = new ZipfSampler(10_000, 1.0);
    final SplittableRandom rnd = new SplittableRandom(1);
    int hits = 0;
    final int draws = 200_000;
    for (int i = 0; i < draws; i++)
      if (zipf.sample(rnd.nextDouble()) == 0)
        hits++;
    assertThat(hits / (double) draws).isCloseTo(zipf.probability(0), org.assertj.core.data.Offset.offset(0.005));
  }

  @Test
  void headSizeForAnExpectedCount() {
    final ZipfSampler zipf = new ZipfSampler(1000, 1.0);
    final int head = zipf.maxRankWithExpectedCount(2000, 1.0);
    assertThat(2000 * zipf.probability(head)).isGreaterThanOrEqualTo(1.0);
    if (head + 1 < 1000)
      assertThat(2000 * zipf.probability(head + 1)).isLessThan(1.0);
    assertThat(zipf.cumulativeMass(999)).isEqualTo(1.0);
    assertThat(zipf.cumulativeMass(0)).isEqualTo(zipf.probability(0));
    assertThat(zipf.maxRankWithExpectedCount(0.0, 1.0)).isZero();
  }

  @Test
  void exponentZeroIsUniform() {
    final ZipfSampler zipf = new ZipfSampler(4, 0.0);
    for (int r = 0; r < 4; r++)
      assertThat(zipf.probability(r)).isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-12));
  }
}
