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
 * Latency samples in nanoseconds, kept in a growable primitive array, with nearest-rank percentiles. Not thread-safe: one
 * per client thread, {@link #merge merged} at the end.
 */
public final class LatencyStats {
  private long[]  samples;
  private int     size;
  private boolean sorted;

  public LatencyStats() {
    this(1024);
  }

  public LatencyStats(final int capacity) {
    samples = new long[Math.max(16, capacity)];
  }

  public void add(final long nanos) {
    if (size == samples.length)
      samples = Arrays.copyOf(samples, size * 2);
    samples[size++] = nanos;
    sorted = false;
  }

  public void merge(final LatencyStats other) {
    for (int i = 0; i < other.size; i++)
      add(other.samples[i]);
  }

  public int count() {
    return size;
  }

  /** Nearest-rank percentile, {@code p} in {@code (0, 100]}; 0 when there is no sample. */
  public long percentileNanos(final double p) {
    if (size == 0)
      return 0L;
    if (!sorted) {
      Arrays.sort(samples, 0, size);
      sorted = true;
    }
    final int rank = (int) Math.ceil(p / 100.0 * size);
    return samples[Math.min(size - 1, Math.max(0, rank - 1))];
  }

  public long maxNanos() {
    return percentileNanos(100.0);
  }

  public double meanNanos() {
    if (size == 0)
      return 0.0;
    double sum = 0.0;
    for (int i = 0; i < size; i++)
      sum += samples[i];
    return sum / size;
  }

  public long sumNanos() {
    long sum = 0L;
    for (int i = 0; i < size; i++)
      sum += samples[i];
    return sum;
  }
}
