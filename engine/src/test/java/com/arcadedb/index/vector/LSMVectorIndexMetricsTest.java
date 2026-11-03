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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPORK retrieval-speed workstream S10 (docs/spork/02-retrieval-speed.md, 4.2): the search counters of a vector index were the
 * only shared-write cache lines left on the search path, one CAS per search on each. They are {@code LongAdder}s now; what
 * they count must not change, from one thread or from many at once.
 */
class LSMVectorIndexMetricsTest {

  @Test
  void countsFromManyThreadsAddUpExactly() throws Exception {
    final LSMVectorIndexMetrics metrics = new LSMVectorIndexMetrics();
    final int threads = 16;
    final int perThread = 20_000;
    final CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++)
      new Thread(() -> {
        for (int i = 0; i < perThread; i++) {
          metrics.incrementSearchOperations();
          metrics.addSearchLatency(2);
          metrics.incrementInsertOperations(3);
          metrics.incrementVectorFetchFromGraph();
        }
        done.countDown();
      }).start();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();

    final long total = (long) threads * perThread;
    assertThat(metrics.getSearchOperations()).isEqualTo(total);
    assertThat(metrics.getSearchLatencyMs()).isEqualTo(total * 2);
    assertThat(metrics.getAvgSearchLatencyMs()).isEqualTo(2);
    assertThat(metrics.getInsertOperations()).isEqualTo(total * 3);
    assertThat(metrics.getVectorFetchFromGraph()).isEqualTo(total);
    assertThat(metrics.getBruteForceScans()).isZero();
  }

  @Test
  void statsReportTheCountersAndResetZeroesThem() {
    final LSMVectorIndexMetrics metrics = new LSMVectorIndexMetrics();
    metrics.incrementSearchOperations();
    metrics.incrementSearchOperations();
    metrics.addSearchLatency(10);
    metrics.incrementBruteForceScans();
    metrics.incrementPreFilterSearches();
    metrics.incrementFusedPQWalks();
    metrics.setGraphBuildQuantizedResident(7);

    final Map<String, Long> stats = new HashMap<>();
    metrics.populateStats(stats);
    assertThat(stats).containsEntry("searchOperations", 2L).containsEntry("avgSearchLatencyMs", 5L).containsEntry("bruteForceScans", 1L)
        .containsEntry("preFilterSearches", 1L).containsEntry("fusedPQWalks", 1L).containsEntry("graphBuildQuantizedResident", 7L)
        .containsEntry("insertOperations", 0L);

    metrics.reset();
    assertThat(metrics.getSearchOperations()).isZero();
    assertThat(metrics.getSearchLatencyMs()).isZero();
    assertThat(metrics.getAvgSearchLatencyMs()).isZero();
    assertThat(metrics.getBruteForceScans()).isZero();
    assertThat(metrics.getGraphBuildQuantizedResident()).isZero();
  }
}
