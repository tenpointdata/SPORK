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

import java.util.Arrays;

/**
 * Zipf distribution over the ranks {@code 0..n-1} (rank 0 is the most frequent), as a precomputed cumulative table and a
 * binary search. Stateless: the caller supplies the uniform draw, so one table can serve every thread of a benchmark and
 * the same seed reproduces the same ranks whoever holds the random generator.
 * <p>
 * Memory is one {@code double} per rank, which is what makes it usable over an identifier pool of tens of millions.
 */
public final class ZipfSampler {
  private final double[] cumulative;

  /**
   * @param n        number of ranks, at least 1
   * @param exponent the Zipf exponent {@code s}: frequency of rank {@code r} is proportional to {@code 1 / (r + 1)^s}
   */
  public ZipfSampler(final int n, final double exponent) {
    if (n < 1)
      throw new IllegalArgumentException("n must be at least 1, was " + n);
    if (exponent < 0.0)
      throw new IllegalArgumentException("exponent must not be negative, was " + exponent);
    cumulative = new double[n];
    double sum = 0.0;
    for (int r = 0; r < n; r++) {
      sum += 1.0 / Math.pow(r + 1, exponent);
      cumulative[r] = sum;
    }
    for (int r = 0; r < n; r++)
      cumulative[r] /= sum;
    cumulative[n - 1] = 1.0;
  }

  public int size() {
    return cumulative.length;
  }

  /** Probability mass of rank {@code r}. */
  public double probability(final int r) {
    return r == 0 ? cumulative[0] : cumulative[r] - cumulative[r - 1];
  }

  /** Cumulative mass of ranks {@code 0..r} inclusive: a draw below it lands on one of them. */
  public double cumulativeMass(final int r) {
    return cumulative[r];
  }

  /**
   * The largest rank whose expected count after {@code draws} draws is at least {@code minCount}, or 0 when even rank 0
   * falls short. Masses decrease with rank, so this is the size of the head that a consumer can expect to have seen.
   */
  public int maxRankWithExpectedCount(final double draws, final double minCount) {
    int lo = 0, hi = cumulative.length - 1;
    while (lo < hi) {
      final int mid = (lo + hi + 1) >>> 1;
      if (draws * probability(mid) >= minCount)
        lo = mid;
      else
        hi = mid - 1;
    }
    return lo;
  }

  /**
   * The rank a uniform draw in {@code [0, 1)} lands on: the smallest rank whose cumulative mass exceeds {@code u}.
   */
  public int sample(final double u) {
    if (u < 0.0 || u >= 1.0)
      throw new IllegalArgumentException("u must be in [0, 1), was " + u);
    final int pos = Arrays.binarySearch(cumulative, u);
    if (pos >= 0) {
      // u sits exactly on a boundary: it belongs to the next rank, since ranks own [cumulative[r-1], cumulative[r])
      return Math.min(pos + 1, cumulative.length - 1);
    }
    return -pos - 1;
  }
}
