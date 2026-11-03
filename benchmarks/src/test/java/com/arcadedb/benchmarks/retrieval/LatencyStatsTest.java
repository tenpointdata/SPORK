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

import static org.assertj.core.api.Assertions.assertThat;

class LatencyStatsTest {
  @Test
  void nearestRankPercentiles() {
    final LatencyStats stats = new LatencyStats(4);
    for (long v = 100; v >= 1; v--)
      stats.add(v);
    assertThat(stats.count()).isEqualTo(100);
    assertThat(stats.percentileNanos(50)).isEqualTo(50);
    assertThat(stats.percentileNanos(95)).isEqualTo(95);
    assertThat(stats.percentileNanos(99)).isEqualTo(99);
    assertThat(stats.maxNanos()).isEqualTo(100);
    assertThat(stats.meanNanos()).isEqualTo(50.5);
  }

  @Test
  void emptyIsZero() {
    final LatencyStats stats = new LatencyStats();
    assertThat(stats.percentileNanos(99)).isZero();
    assertThat(stats.meanNanos()).isZero();
  }

  @Test
  void mergeKeepsEverySampleAndStaysSortedAfterMoreAdds() {
    final LatencyStats a = new LatencyStats(), b = new LatencyStats();
    a.add(5);
    a.add(1);
    b.add(3);
    a.percentileNanos(50); // sorts
    a.merge(b);
    a.add(4);
    assertThat(a.count()).isEqualTo(4);
    assertThat(a.percentileNanos(50)).isEqualTo(3);
    assertThat(a.percentileNanos(100)).isEqualTo(5);
  }

  @Test
  void reportRowDerivesMicrosecondsAndThroughput() {
    final LatencyStats stats = new LatencyStats();
    for (int i = 0; i < 10; i++)
      stats.add(2_000_000L); // 2 ms each
    final BenchReport.Row row = BenchReport.fromStats("http", "q", "json", 1, "warm", stats, 5.0, 7);
    assertThat(row.p50Us()).isEqualTo(2_000.0);
    assertThat(row.qps()).isEqualTo(2.0);
    assertThat(row.rows()).isEqualTo(7);
    final BenchReport report = new BenchReport();
    report.add(row);
    assertThat(report.toMarkdown()).contains("| http | q | json | 1 | warm | 10 | 2.00 |");
    assertThat(report.toJson().toString()).contains("\"p95Us\":2000");
  }
}
