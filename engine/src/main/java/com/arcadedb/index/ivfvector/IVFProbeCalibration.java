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

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Measures, at training time, how many posting lists a search has to probe on a freshly trained partition for its default
 * recall: what a fixed {@code nprobe} cannot know. How far the nearest neighbours of a query spread over the lists depends on
 * the intrinsic dimension of the data, not on the list count: on clustered 512-d embeddings they sit in the query's own few lists
 * (32 probes of 25,000 recall 0.91), on 24-d style vectors that are mostly noise they spread over hundreds (32 probes of 25,000
 * recall 0.47, 137 of 5,000 needed for 0.9 at 1M).
 * <p>
 * The measure uses the training sample as a scaled-down corpus. Up to {@value #QUERIES} vectors held out of the training act as
 * queries (a query the k-means saw sits closer to its centroids than a fresh one: with 30 sample vectors per list, measuring on
 * training vectors under-counted the probes by a third); for each, its {@code m} nearest sample vectors stand for the corpus'
 * {@value #REFERENCE_K} nearest neighbours, {@code m} being
 * {@value #REFERENCE_K} scaled by the sample's share of the corpus (the sample is the corpus thinned uniformly, so its m-th
 * neighbour lies about as far as the corpus' {@value #REFERENCE_K}-th). Each neighbour's list is the one {@link IVFCentroids#assign}
 * gives it, and its rank is the number of centroids nearer to the query than that list's centroid, which is the position
 * {@link IVFCentroids#select} ranks it at. The answer is the probe count that covers {@link #TARGET_RECALL} of those
 * (query, neighbour) pairs.
 * <p>
 * Cost: {@value #QUERIES} x sample x dimensions for the neighbours plus {@value #QUERIES} x lists x dimensions for the ranks, fanned
 * out over the query pool like the k-means assignment (engine-concurrency skill: no pool of its own, the reclaiming wait). At 5M
 * x 24-d (a 750K sample, 25,000 lists) that is about 4 GFLOP, about a second, against minutes of build.
 */
public final class IVFProbeCalibration {
  /** Sample vectors used as queries. */
  static final int    QUERIES       = 256;
  /** The answer size the calibration aims its recall at. */
  static final int    REFERENCE_K   = 20;
  /**
   * Share of the (query, neighbour) pairs whose list the calibrated probe must reach. Above the 0.9 recall it is meant to give,
   * because the scaled-down measure reads optimistic: on the 1M style corpus the probe it gives at 0.95 reaches 0.915 of the true
   * neighbours' lists, at 0.97 about 0.955 (INT8 scoring then costs about 0.02 more).
   */
  static final double TARGET_RECALL = 0.97;

  private IVFProbeCalibration() {
  }

  /** Vectors of a sample of {@code sampleSize} to hold out of the training as calibration queries: none below 100. */
  public static int heldOut(final int sampleSize) {
    return sampleSize < 100 ? 0 : Math.min(QUERIES, sampleSize / 20);
  }

  /**
   * Splits {@code sample} into the vectors to train on and {@link #heldOut} vectors spread evenly over it (a reservoir keeps the scan
   * order until it fills, so a block from one end could be one cluster): answers {@code [training, queries]}.
   */
  public static float[][][] split(final float[][] sample) {
    final int held = heldOut(sample.length);
    if (held == 0)
      return new float[][][] { sample, new float[0][] };
    final float[][] queries = new float[held][];
    final float[][] training = new float[sample.length - held][];
    final int stride = sample.length / held;
    int q = 0, t = 0;
    for (int i = 0; i < sample.length; i++)
      if (q < held && i == q * stride)
        queries[q++] = sample[i];
      else
        training[t++] = sample[i];
    return new float[][][] { training, queries };
  }

  /** {@link #heldOut} vectors spread evenly over {@code sample}, left in it: queries that skip themselves among its neighbours. */
  public static float[][] pick(final float[][] sample) {
    final int count = heldOut(sample.length);
    final float[][] queries = new float[count][];
    if (count > 0) {
      final int stride = sample.length / count;
      for (int q = 0; q < count; q++)
        queries[q] = sample[q * stride];
    }
    return queries;
  }

  /**
   * The probe count covering {@link #TARGET_RECALL} of the neighbour pairs of {@code queries} among {@code sample} on
   * {@code centroids}, at least 1 and at most the list count; 0 when there is nothing to measure (one list, no query, an empty
   * sample, an untrained set).
   *
   * @param sample     the training vectors, prepared as the index prepares them (normalized under COSINE)
   * @param queries    vectors drawn like the sample, held out of the training ({@link #split}) or picked from it ({@link #pick}: a
   *                   query skips itself)
   * @param corpusSize the vectors the sample was drawn from
   * @param dotProduct true for DOT_PRODUCT, whose neighbours are the largest dot products rather than the nearest points
   */
  public static int calibrate(final float[][] sample, final float[][] queries, final long corpusSize, final IVFCentroids centroids,
      final boolean dotProduct) {
    return calibrate(sample, queries, corpusSize, centroids, dotProduct, TARGET_RECALL);
  }

  /** {@link #calibrate(float[][], float[][], long, IVFCentroids, boolean)} at another target share of the pairs. */
  static int calibrate(final float[][] sample, final float[][] queries, final long corpusSize, final IVFCentroids centroids,
      final boolean dotProduct, final double targetRecall) {
    if (!centroids.trained || centroids.nlist <= 1 || sample.length < 2 || queries.length == 0)
      return 0;
    final long scaled = (long) Math.ceil(REFERENCE_K * (double) sample.length / Math.max(sample.length, corpusSize));
    final int neighbours = (int) Math.max(1, Math.min(Math.min(REFERENCE_K, scaled), sample.length - 1));
    final int[] ranks = new int[queries.length * neighbours];

    final int count = queries.length;
    final int parallelism = count < 8 ? 1 : Math.max(1, Math.min(count, Runtime.getRuntime().availableProcessors()));
    final int chunk = (count + parallelism - 1) / parallelism;
    final ExecutorService executor = parallelism > 1 ? QueryEngineManager.getInstance().getExecutorService() : null;
    final Future<?>[] futures = new Future<?>[parallelism - 1];
    int launched = 0;
    for (int t = 1; t < parallelism; t++) {
      final int from = t * chunk;
      final int to = Math.min(from + chunk, count);
      if (from >= to)
        break;
      futures[launched++] = executor.submit(() -> rankRange(sample, queries, neighbours, centroids, dotProduct, ranks, from, to));
    }
    rankRange(sample, queries, neighbours, centroids, dotProduct, ranks, 0, Math.min(chunk, count));
    GraphAlgorithms.awaitFutures(futures, launched);

    Arrays.sort(ranks);
    final int covered = (int) Math.ceil(targetRecall * ranks.length) - 1;
    return Math.min(centroids.nlist, ranks[Math.max(0, covered)] + 1);
  }

  /** For the queries {@code [from, to)}: the list rank of each of their {@code neighbours} nearest sample vectors into {@code ranks}. */
  private static void rankRange(final float[][] sample, final float[][] queries, final int neighbours, final IVFCentroids centroids,
      final boolean dotProduct, final int[] ranks, final int from, final int to) {
    final int dimensions = centroids.dimensions;
    final int nlist = centroids.nlist;
    final float[] nearest = new float[neighbours];
    final int[] nearestIds = new int[neighbours];
    final float[] centroidDistances = new float[nlist];
    for (int q = from; q < to; q++) {
      final float[] query = queries[q];

      // The m nearest sample vectors, kept sorted by insertion (m is at most REFERENCE_K)
      Arrays.fill(nearest, Float.MAX_VALUE);
      for (int i = 0; i < sample.length; i++) {
        final float[] other = sample[i];
        // A query picked from the sample is not its own neighbour
        if (other == query)
          continue;
        float distance = 0f;
        if (dotProduct) {
          for (int d = 0; d < dimensions; d++)
            distance -= query[d] * other[d];
        } else
          for (int d = 0; d < dimensions; d++) {
            final float diff = query[d] - other[d];
            distance += diff * diff;
          }
        if (distance >= nearest[neighbours - 1])
          continue;
        int at = neighbours - 1;
        while (at > 0 && nearest[at - 1] > distance) {
          nearest[at] = nearest[at - 1];
          nearestIds[at] = nearestIds[at - 1];
          at--;
        }
        nearest[at] = distance;
        nearestIds[at] = i;
      }

      for (int c = 0; c < nlist; c++)
        centroidDistances[c] = IVFCodec.squaredDistance(query, centroids.fine, c * dimensions);
      for (int j = 0; j < neighbours; j++) {
        final float listDistance = centroidDistances[centroids.assign(sample[nearestIds[j]])];
        int rank = 0;
        for (int c = 0; c < nlist; c++)
          if (centroidDistances[c] < listDistance)
            rank++;
        ranks[q * neighbours + j] = rank;
      }
    }
  }
}
