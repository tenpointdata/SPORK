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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic of the recommended layout for 300M-900M 512-d vectors on a 126 GB node, written up in
 * {@code docs/spork/workstreams/vector-scale.md}, checked against the estimators the engine itself admits builds with
 * ({@link VectorHeapBudget}, {@link VectorRebuildPermits}) and the per-vector costs its resident structures declare
 * ({@link VectorLocationIndex#APPROX_RETAINED_BYTES_PER_LOCATION}, {@link BinaryCodeCache#bytesPerCode}). If one of those
 * moves, this test says which line of the document is no longer true.
 */
class VectorScaleLayoutTest {
  private static final long GB         = 1024L * 1024 * 1024;
  private static final int  DIMENSIONS = 512;

  // The recommended node layout (see the document)
  private static final long HEAP                  = 80 * GB;  // -Xmx80g of the 126 GB node
  private static final long PAGE_CACHE            = 24 * GB;  // arcadedb.maxPageRAM, on heap
  private static final long OTHER_LIVE_HEAP       = 6 * GB;   // full-text, geo, query working sets, Raft, metaspace aside
  private static final int  BUILD_CACHE_VECTORS   = 1_000_000; // arcadedb.vectorIndex.graphBuildCacheSize
  private static final int  REBUILD_HEAP_PERCENT  = 90;       // arcadedb.vectorIndex.rebuildMaxHeapPercent (default)

  /** Location index + one-bit code + ordinal map, per live vector of a BINARY 512-d index whose codes are all resident. */
  private static long binaryResidentBytesPerVector() {
    return VectorLocationIndex.APPROX_RETAINED_BYTES_PER_LOCATION + BinaryCodeCache.bytesPerCode(DIMENSIONS) + Integer.BYTES;
  }

  @Test
  void residentBytesPerVectorAreWhatTheDocumentSays() {
    assertThat(VectorLocationIndex.APPROX_RETAINED_BYTES_PER_LOCATION).isEqualTo(32);
    assertThat(BinaryCodeCache.bytesPerCode(DIMENSIONS)).isEqualTo(72);
    assertThat(binaryResidentBytesPerVector()).isEqualTo(108);
    // PRODUCT with 32 subspaces: one byte per subspace instead of the one-bit code
    assertThat(VectorLocationIndex.APPROX_RETAINED_BYTES_PER_LOCATION + 32 + Integer.BYTES).isEqualTo(68);
  }

  @Test
  void threeHundredMillionBinaryVectorsFitTheHeapWithTheirCodesResident() {
    final long resident = 300_000_000L * binaryResidentBytesPerVector();
    assertThat(resident / GB).isEqualTo(30); // 32.4e9 bytes
    assertThat(resident + PAGE_CACHE + OTHER_LIVE_HEAP).isLessThan(HEAP);
  }

  @Test
  void nineHundredMillionVectorsDoNotFitOnHeapEvenAsPqCodes() {
    // Location index alone, the part no quantization shrinks
    assertThat(900_000_000L * VectorLocationIndex.APPROX_RETAINED_BYTES_PER_LOCATION / GB).isEqualTo(26);
    final long binary = 900_000_000L * binaryResidentBytesPerVector();
    final long pq32 = 900_000_000L * (VectorLocationIndex.APPROX_RETAINED_BYTES_PER_LOCATION + 32 + Integer.BYTES);
    assertThat(binary + PAGE_CACHE + OTHER_LIVE_HEAP).isGreaterThan(HEAP);
    assertThat(pq32 + PAGE_CACHE + OTHER_LIVE_HEAP).isGreaterThan(HEAP);
  }

  @Test
  void aBucketOfTheRecommendedSizeBuildsInAboutSevenGigabytes() {
    // 300M over 64 buckets
    final long perBucket = 300_000_000L / 64;
    assertThat(perBucket).isEqualTo(4_687_500L);
    final long build = VectorHeapBudget.estimateRebuildHeapBytes(perBucket, DIMENSIONS, BUILD_CACHE_VECTORS, false);
    assertThat(build / GB).isEqualTo(6); // 5.2 GB graph + 2.1 GB build cache = 7.3e9 bytes = 6.8 GiB
    // Without an explicit build cache it is sized to 25% of the heap ceiling, capped at the corpus: the whole bucket here
    final long autoCache = VectorHeapBudget.buildCacheBudgetBytes(25, HEAP, HEAP) / VectorHeapBudget.bytesPerCachedVector(DIMENSIONS);
    assertThat(autoCache).isGreaterThan(perBucket);
    assertThat(VectorHeapBudget.estimateRebuildHeapBytes(perBucket, DIMENSIONS, perBucket, false) / GB).isEqualTo(14);
  }

  @Test
  void twoBucketBuildsRunSideBySideAtThreeHundredMillionAndAThirdWaits() {
    final long perBucket = 300_000_000L / 64;
    final long build = VectorHeapBudget.estimateRebuildHeapBytes(perBucket, DIMENSIONS, BUILD_CACHE_VECTORS, false);
    final long live = 300_000_000L * binaryResidentBytesPerVector() + PAGE_CACHE + OTHER_LIVE_HEAP;
    final long budget = VectorHeapBudget.budgetBytes(REBUILD_HEAP_PERCENT, HEAP - live);

    final VectorRebuildPermits permits = new VectorRebuildPermits(4);
    assertThat(permits.tryAcquire(build, budget)).isTrue();
    assertThat(permits.tryAcquire(build, budget)).isTrue();
    assertThat(permits.tryAcquire(build, budget)).as("a third build does not fit the %d GB budget", budget / GB).isFalse();
  }
}
