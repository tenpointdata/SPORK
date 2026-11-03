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
package com.arcadedb.similarity;

import java.util.Arrays;

/**
 * An in-memory Hamming radius index over a fixed set of hashes, for set-scoped work (clustering a selection, comparing two
 * clusters' images). Small sets are compared pairwise; larger ones use multi-index hashing ({@link HammingBands}) over
 * sorted primitive tables, one {@code long[]} per band packing (chunk, position), so building it allocates
 * bands + 1 arrays and searching it allocates nothing per candidate. Not thread safe: a search stamps the positions it
 * has verified.
 */
public final class HammingIndex {
  /** Pairs below this many hashes are compared directly: n^2 / 2 popcounts beat building band tables. */
  static final int BRUTE_FORCE_LIMIT = 2048;

  @FunctionalInterface
  public interface PairConsumer {
    void accept(int i, int j, int distance);
  }

  @FunctionalInterface
  public interface NeighbourConsumer {
    void accept(int i, int distance);
  }

  private final long[][] hashes;
  private final int      maxDistance;
  private final int      bands;
  private final int      chunkBits;
  private final int      radius;
  private final long[][] tables;
  private final int[]    stamps;
  private int            stamp;

  public HammingIndex(final long[][] hashes, final int maxDistance) {
    if (maxDistance < 0)
      throw new IllegalArgumentException("maxDistance cannot be negative, got " + maxDistance);
    this.hashes = hashes;
    this.maxDistance = maxDistance;
    final int words = hashes.length == 0 ? 1 : hashes[0].length;
    for (final long[] h : hashes)
      if (h.length != words)
        throw new IllegalArgumentException(
            "Cannot index a " + h.length * 64 + "-bit hash with " + words * 64 + "-bit hashes");

    if (hashes.length <= BRUTE_FORCE_LIMIT) {
      this.bands = 0;
      this.chunkBits = 0;
      this.radius = 0;
      this.tables = null;
      this.stamps = null;
      return;
    }

    this.bands = HammingBands.suggestBands(words * 64, hashes.length);
    this.chunkBits = words * 64 / bands;
    this.radius = maxDistance / bands;
    this.tables = new long[bands][hashes.length];
    for (int b = 0; b < bands; b++) {
      final long[] table = tables[b];
      for (int i = 0; i < hashes.length; i++)
        table[i] = (BitHash.chunk(hashes[i], b * chunkBits, chunkBits) << 32) | i;
      Arrays.sort(table);
    }
    this.stamps = new int[hashes.length];
  }

  public boolean usesBands() {
    return tables != null;
  }

  public int size() {
    return hashes.length;
  }

  /** Every pair (i &lt; j) within the distance, each once. */
  public void forEachPair(final PairConsumer action) {
    if (tables == null) {
      for (int i = 0; i < hashes.length; i++)
        for (int j = i + 1; j < hashes.length; j++) {
          final int d = BitHash.hamming(hashes[i], hashes[j], maxDistance);
          if (d <= maxDistance)
            action.accept(i, j, d);
        }
      return;
    }
    for (int i = 0; i < hashes.length; i++) {
      final int from = i;
      search(hashes[i], (j, d) -> {
        if (j > from)
          action.accept(from, j, d);
      });
    }
  }

  /** Every indexed hash within the distance of {@code query}, each once. */
  public void forEachNeighbour(final long[] query, final NeighbourConsumer action) {
    if (query.length != (hashes.length == 0 ? query.length : hashes[0].length))
      throw new IllegalArgumentException(
          "Cannot compare a " + query.length * 64 + "-bit hash with " + hashes[0].length * 64 + "-bit hashes");
    if (tables == null) {
      for (int i = 0; i < hashes.length; i++) {
        final int d = BitHash.hamming(query, hashes[i], maxDistance);
        if (d <= maxDistance)
          action.accept(i, d);
      }
      return;
    }
    search(query, action);
  }

  private void search(final long[] query, final NeighbourConsumer action) {
    if (++stamp == 0) {
      Arrays.fill(stamps, 0);
      stamp = 1;
    }
    final int current = stamp;
    for (int b = 0; b < bands; b++) {
      final long[] table = tables[b];
      HammingBands.forEachWithin(BitHash.chunk(query, b * chunkBits, chunkBits), chunkBits, radius, value -> {
        final long low = value << 32;
        for (int p = lowerBound(table, low); p < table.length && (table[p] >>> 32) == value; p++) {
          final int j = (int) table[p];
          if (stamps[j] == current)
            continue;
          stamps[j] = current;
          final int d = BitHash.hamming(query, hashes[j], maxDistance);
          if (d <= maxDistance)
            action.accept(j, d);
        }
      });
    }
  }

  /**
   * The first position whose packed entry is at least {@code key}, in the signed order {@link Arrays#sort(long[])} left
   * the table in: the entries of one chunk are contiguous in it whatever the chunk's top bit, and ordered by position.
   */
  private static int lowerBound(final long[] table, final long key) {
    int lo = 0;
    int hi = table.length;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (table[mid] < key)
        lo = mid + 1;
      else
        hi = mid;
    }
    return lo;
  }
}
