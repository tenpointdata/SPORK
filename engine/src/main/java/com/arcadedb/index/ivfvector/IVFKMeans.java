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
package com.arcadedb.index.ivfvector;

import com.arcadedb.graph.olap.GraphAlgorithms;
import com.arcadedb.query.QueryEngineManager;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Trains the centroids of an {@code LSM_IVF_VECTOR} index on a sample of its vectors: Lloyd's k-means on primitive
 * arrays, in two levels when the index has more than {@link IVFCentroids#SINGLE_LEVEL_MAX} lists. The coarse level
 * clusters the whole sample into {@code ceil(sqrt(k))} groups and every group is clustered again into its share of the
 * {@code k} fine centroids, in proportion to its size, so the fine lists come out balanced and the work is
 * {@code O(n * sqrt(k) * d)} per iteration instead of {@code O(n * k * d)}.
 * <p>
 * The assignment step of every iteration, which is all of the cost, fans out over the query engine pool
 * ({@code QueryEngineManager}, see the {@code engine-concurrency} skill) and waits with the reclaiming
 * {@link GraphAlgorithms#awaitFutures}, so a build never parks on a pool it may itself be running on.
 */
public final class IVFKMeans {
  /** Points below which an assignment pass runs on the calling thread alone. */
  private static final int PARALLEL_THRESHOLD = 4_096;

  private IVFKMeans() {
  }

  /**
   * @param sample     the training vectors, already normalized when the similarity asks for it
   * @param k          the fine centroids wanted; the result may hold fewer when the sample is smaller
   * @param iterations Lloyd iterations per level
   * @param version    the version the returned centroids carry
   */
  public static IVFCentroids train(final float[][] sample, final int dimensions, final int k, final int iterations,
      final int version, final int projectionSeed, final Random random) {
    if (sample.length == 0)
      return IVFCentroids.untrained(dimensions, version, projectionSeed);
    final int fineCount = Math.min(k, sample.length);
    final float[] thresholds = columnMeans(sample, dimensions);

    if (fineCount <= IVFCentroids.SINGLE_LEVEL_MAX) {
      final float[] fine = lloyd(sample, dimensions, fineCount, iterations, random);
      return new IVFCentroids(dimensions, fine, columnMeans(sample, dimensions), new int[] { 0, fineCount }, thresholds, version,
          true, projectionSeed);
    }

    final int coarseCount = Math.min((int) Math.ceil(Math.sqrt(fineCount)), sample.length);
    final float[] coarse = lloyd(sample, dimensions, coarseCount, iterations, random);
    final int[] assignment = new int[sample.length];
    assign(sample, dimensions, coarse, coarseCount, assignment);

    // Group the sample by coarse centroid without allocating per group
    final int[] groupSize = new int[coarseCount];
    for (final int a : assignment)
      groupSize[a]++;
    final int[] groupOffset = new int[coarseCount + 1];
    for (int c = 0; c < coarseCount; c++)
      groupOffset[c + 1] = groupOffset[c] + groupSize[c];
    final int[] fill = groupOffset.clone();
    final float[][] grouped = new float[sample.length][];
    for (int i = 0; i < sample.length; i++)
      grouped[fill[assignment[i]]++] = sample[i];

    // Each group gets its share of the fine centroids, at least one when it has points; rounding leaves the total
    // within a few of k, which the posting-size target tolerates. A group the final assignment left with no point (Lloyd's
    // last update can empty a cluster, duplicates make it certain) is dropped from the layout: kept, it would own an empty
    // fine range that the coarse centroid still attracts vectors to
    int kept = 0;
    for (int c = 0; c < coarseCount; c++)
      if (groupSize[c] > 0)
        kept++;
    final float[] keptCoarse = kept == coarseCount ? coarse : new float[kept * dimensions];
    final int[] coarseStart = new int[kept + 1];
    final float[][] fineByGroup = new float[kept][];
    int total = 0;
    int g = 0;
    for (int c = 0; c < coarseCount; c++) {
      final int points = groupSize[c];
      if (points == 0)
        continue;
      final int share = Math.min(points, Math.max(1, Math.round((float) fineCount * points / sample.length)));
      final float[][] groupSample = new float[points][];
      System.arraycopy(grouped, groupOffset[c], groupSample, 0, points);
      fineByGroup[g] = lloyd(groupSample, dimensions, share, iterations, random);
      if (keptCoarse != coarse)
        System.arraycopy(coarse, c * dimensions, keptCoarse, g * dimensions, dimensions);
      coarseStart[g++] = total;
      total += share;
    }
    coarseStart[kept] = total;
    final float[] fine = new float[total * dimensions];
    for (int c = 0; c < kept; c++)
      System.arraycopy(fineByGroup[c], 0, fine, coarseStart[c] * dimensions, fineByGroup[c].length);
    return new IVFCentroids(dimensions, fine, keptCoarse, coarseStart, thresholds, version, true, projectionSeed);
  }

  /** Lloyd's k-means: {@code k} centroids seeded from distinct sample points, {@code iterations} assign/update rounds. */
  static float[] lloyd(final float[][] points, final int dimensions, final int k, final int iterations, final Random random) {
    final float[] centroids = new float[k * dimensions];
    // Seed with k distinct points (a partial Fisher-Yates over the indexes)
    final int[] order = new int[points.length];
    for (int i = 0; i < order.length; i++)
      order[i] = i;
    for (int i = 0; i < k; i++) {
      final int j = i + random.nextInt(points.length - i);
      final int tmp = order[i];
      order[i] = order[j];
      order[j] = tmp;
      System.arraycopy(points[order[i]], 0, centroids, i * dimensions, dimensions);
    }
    if (k == points.length || k == 1 && iterations > 0) {
      if (k == 1)
        meanInto(points, dimensions, centroids, 0);
      return centroids;
    }

    final int[] assignment = new int[points.length];
    final float[] sums = new float[k * dimensions];
    final int[] counts = new int[k];
    for (int iteration = 0; iteration < iterations; iteration++) {
      assign(points, dimensions, centroids, k, assignment);
      java.util.Arrays.fill(sums, 0f);
      java.util.Arrays.fill(counts, 0);
      for (int i = 0; i < points.length; i++) {
        final int c = assignment[i];
        counts[c]++;
        final float[] p = points[i];
        final int offset = c * dimensions;
        for (int d = 0; d < dimensions; d++)
          sums[offset + d] += p[d];
      }
      for (int c = 0; c < k; c++) {
        final int offset = c * dimensions;
        if (counts[c] == 0) {
          // An empty cluster is re-seeded on a random point, so no list is left dead
          System.arraycopy(points[random.nextInt(points.length)], 0, centroids, offset, dimensions);
          continue;
        }
        final float inverse = 1f / counts[c];
        for (int d = 0; d < dimensions; d++)
          centroids[offset + d] = sums[offset + d] * inverse;
      }
    }
    return centroids;
  }

  /** Nearest centroid of every point, fanned out over the query pool when the sample is large enough to pay for it. */
  static void assign(final float[][] points, final int dimensions, final float[] centroids, final int k, final int[] assignment) {
    final int n = points.length;
    if (n < PARALLEL_THRESHOLD) {
      assignRange(points, dimensions, centroids, k, assignment, 0, n);
      return;
    }
    final int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
    final int chunk = (n + parallelism - 1) / parallelism;
    final ExecutorService executor = QueryEngineManager.getInstance().getExecutorService();
    final Future<?>[] futures = new Future<?>[parallelism - 1];
    int launched = 0;
    for (int t = 1; t < parallelism; t++) {
      final int start = t * chunk;
      final int end = Math.min(start + chunk, n);
      if (start >= n)
        break;
      futures[launched++] = executor.submit(() -> assignRange(points, dimensions, centroids, k, assignment, start, end));
    }
    // The caller runs the first chunk itself and then reclaims whatever the pool has not started (issue #6568)
    assignRange(points, dimensions, centroids, k, assignment, 0, Math.min(chunk, n));
    GraphAlgorithms.awaitFutures(futures, launched);
  }

  private static void assignRange(final float[][] points, final int dimensions, final float[] centroids, final int k,
      final int[] assignment, final int from, final int to) {
    for (int i = from; i < to; i++) {
      final float[] p = points[i];
      int best = 0;
      float bestDistance = Float.MAX_VALUE;
      for (int c = 0; c < k; c++) {
        final float distance = IVFCodec.squaredDistance(p, centroids, c * dimensions);
        if (distance < bestDistance) {
          best = c;
          bestDistance = distance;
        }
      }
      assignment[i] = best;
    }
  }

  static float[] columnMeans(final float[][] points, final int dimensions) {
    final float[] means = new float[dimensions];
    meanInto(points, dimensions, means, 0);
    return means;
  }

  private static void meanInto(final float[][] points, final int dimensions, final float[] target, final int offset) {
    final double[] sums = new double[dimensions];
    for (final float[] p : points)
      for (int d = 0; d < dimensions; d++)
        sums[d] += p[d];
    for (int d = 0; d < dimensions; d++)
      target[offset + d] = (float) (sums[d] / points.length);
  }
}
