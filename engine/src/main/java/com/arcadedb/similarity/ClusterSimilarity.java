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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The soft-evidence features of two clusters of records (docs/spork/06-stylometry-perceptual-hash.md, the input of
 * slice 3 of docs/spork/04-identity-resolution-design.md): how alike their writing is and how many of their images are
 * near duplicates. Raw features only, no likelihood ratio: turning a cosine or a distance into evidence is the identity
 * model's job, from calibrated tables, never from the raw number.
 */
public final class ClusterSimilarity {
  private ClusterSimilarity() {
  }

  /**
   * Writing-style agreement. {@code max} and {@code meanBest} (each member's best match on the other side, averaged over
   * both sides) are NaN when the pairs were not compared ({@code pairsTruncated}); {@code centroid} is the cosine of the
   * two clusters' mean vectors and is always computed. {@code bestA}/{@code bestB} index the most similar pair, -1 when
   * none.
   */
  public record StyleFeatures(int sizeA, int sizeB, long pairs, boolean pairsTruncated, long pairsAbove, double max,
                              double meanBest, double centroid, int bestA, int bestB) {
  }

  /** The closest pair of hashes found between member {@code memberA} of A and member {@code memberB} of B. */
  public record NearPair(int memberA, int memberB, int distance) {
  }

  /**
   * Near-duplicate images. {@code pairs} lists every member pair with a hash pair within the distance, closest first;
   * {@code exactPairs} counts those at distance 0 (the same image re-encoded, or byte-identical); {@code minDistance} is
   * -1 when nothing is within the distance.
   */
  public record ImageFeatures(int hashesA, int hashesB, int minDistance, int exactPairs, List<NearPair> pairs) {
  }

  /** Compares every vector of A with every vector of B, unless there are more than {@code maxPairs} pairs. */
  public static StyleFeatures style(final float[][] a, final float[][] b, final double threshold, final long maxPairs) {
    final long pairs = (long) a.length * b.length;
    final double centroid = a.length == 0 || b.length == 0 ? Double.NaN : Stylometry.cosine(mean(a), mean(b));
    if (pairs == 0 || pairs > maxPairs)
      return new StyleFeatures(a.length, b.length, pairs, pairs > maxPairs, 0, Double.NaN, Double.NaN, centroid, -1, -1);

    final double[] bestOfA = new double[a.length];
    final double[] bestOfB = new double[b.length];
    Arrays.fill(bestOfA, -1);
    Arrays.fill(bestOfB, -1);
    long above = 0;
    double max = -1;
    int bestA = -1, bestB = -1;
    for (int i = 0; i < a.length; i++)
      for (int j = 0; j < b.length; j++) {
        final double c = Stylometry.cosine(a[i], b[j]);
        if (c >= threshold)
          above++;
        if (c > max) {
          max = c;
          bestA = i;
          bestB = j;
        }
        bestOfA[i] = Math.max(bestOfA[i], c);
        bestOfB[j] = Math.max(bestOfB[j], c);
      }
    double sum = 0;
    for (final double v : bestOfA)
      sum += v;
    for (final double v : bestOfB)
      sum += v;
    return new StyleFeatures(a.length, b.length, pairs, false, above, max, sum / (a.length + b.length), centroid, bestA, bestB);
  }

  /**
   * Finds the hash pairs within {@code maxDistance} between two clusters whose members hold several hashes each:
   * {@code ownerA[i]} is the member of A holding {@code hashesA[i]}.
   */
  public static ImageFeatures images(final long[][] hashesA, final int[] ownerA, final long[][] hashesB, final int[] ownerB,
      final int maxDistance) {
    if (hashesA.length == 0 || hashesB.length == 0)
      return new ImageFeatures(hashesA.length, hashesB.length, -1, 0, List.of());
    final HammingIndex index = new HammingIndex(hashesB, maxDistance);
    final Map<Long, Integer> best = new HashMap<>();
    for (int i = 0; i < hashesA.length; i++) {
      final int memberA = ownerA[i];
      index.forEachNeighbour(hashesA[i], (j, d) -> best.merge(((long) memberA << 32) | ownerB[j], d, Math::min));
    }
    final List<NearPair> pairs = new ArrayList<>(best.size());
    int exact = 0;
    int min = -1;
    for (final Map.Entry<Long, Integer> e : best.entrySet()) {
      final int d = e.getValue();
      pairs.add(new NearPair((int) (e.getKey() >>> 32), (int) (long) e.getKey(), d));
      if (d == 0)
        exact++;
      if (min < 0 || d < min)
        min = d;
    }
    pairs.sort((x, y) -> x.distance() != y.distance() ? Integer.compare(x.distance(), y.distance()) :
        x.memberA() != y.memberA() ? Integer.compare(x.memberA(), y.memberA()) : Integer.compare(x.memberB(), y.memberB()));
    return new ImageFeatures(hashesA.length, hashesB.length, min, exact, pairs);
  }

  static float[] mean(final float[][] vectors) {
    final float[] m = new float[vectors[0].length];
    for (final float[] v : vectors) {
      if (v.length != m.length)
        throw new IllegalArgumentException("Cannot average a " + v.length + "-d vector with " + m.length + "-d vectors");
      for (int i = 0; i < m.length; i++)
        m[i] += v[i];
    }
    return m;
  }
}
