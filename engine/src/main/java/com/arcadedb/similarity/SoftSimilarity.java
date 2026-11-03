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

import com.arcadedb.index.vector.VectorUtils;

/**
 * The soft-evidence statistics of two groups of members, as the identity model consumes them (slice 3 of
 * docs/spork/04-identity-resolution-design.md; docs/spork/06-stylometry-perceptual-hash.md 5.2). Pure Java, no database.
 * <p>
 * Inputs are per member, in primitive arrays a cluster profile can carry: a style vector ({@code float[]}, or null when
 * the member has none) and the member's image hashes ({@code long[][]}, one {@code long[]} per image: 1 word for a
 * 64-bit hash, 4 for PDQ; empty when it has none). Each function compares every member of A with every member of B except
 * the member at the skip index on either side ({@link #NONE} skips nothing), so a leave-one-out view needs no copy; the
 * {@code *AgainstRest} forms are member j against every other member of one array.
 * </p>
 * <p>
 * The answers are raw statistics: the likelihood ratio comes from a calibrated curve per signal, never from these
 * numbers directly. Thresholds are parameters, so the calibration decides them.
 * </p>
 */
public final class SoftSimilarity {
  /** No member skipped; no distance found. */
  public static final int NONE = -1;

  /**
   * Style agreement over the member pairs that both have a vector: {@code best} and {@code mean} cosine (NaN when
   * {@code pairs} is 0) and how many pairs reach the threshold.
   */
  public record StyleStats(long pairs, long pairsAbove, double best, double mean) {
  }

  /**
   * Image agreement over every pair of hashes, one from each side: the smallest distance ({@link #NONE} when no pair was
   * compared), the mean distance (NaN then), the hash pairs within the distance and the member pairs holding at least one.
   */
  public record ImageStats(long pairs, long pairsWithin, long memberPairsWithin, int minDistance, double meanDistance) {
  }

  private SoftSimilarity() {
  }

  public static StyleStats style(final float[][] a, final int skipA, final float[][] b, final int skipB, final double threshold) {
    final StyleAccumulator acc = new StyleAccumulator();
    for (int i = 0; i < a.length; i++)
      if (i != skipA)
        for (int j = 0; j < b.length; j++)
          if (j != skipB)
            acc.pair(a[i], b[j], threshold);
    return acc.stats();
  }

  /** Member {@code j} against every other member of {@code members}. */
  public static StyleStats styleAgainstRest(final float[][] members, final int j, final double threshold) {
    final StyleAccumulator acc = new StyleAccumulator();
    for (int k = 0; k < members.length; k++)
      if (k != j)
        acc.pair(members[j], members[k], threshold);
    return acc.stats();
  }

  public static ImageStats images(final long[][][] a, final int skipA, final long[][][] b, final int skipB, final int maxDistance) {
    final Accumulator acc = new Accumulator();
    for (int i = 0; i < a.length; i++)
      if (i != skipA)
        for (int j = 0; j < b.length; j++)
          if (j != skipB)
            acc.member(a[i], b[j], maxDistance);
    return acc.stats();
  }

  /** Member {@code j}'s images against every other member's images. */
  public static ImageStats imagesAgainstRest(final long[][][] members, final int j, final int maxDistance) {
    final Accumulator acc = new Accumulator();
    for (int k = 0; k < members.length; k++)
      if (k != j)
        acc.member(members[j], members[k], maxDistance);
    return acc.stats();
  }

  /**
   * A member's style from a property value: a vector as is, or the style of a text ({@link Stylometry#reconStyleVectorFloat}
   * when {@code recon}, else the 256-d {@link Stylometry#profile}); null for no value or an all-zero vector.
   */
  public static float[] styleOf(final Object value, final boolean recon) {
    if (value == null)
      return null;
    final float[] v = value instanceof String text ?
        (recon ? Stylometry.reconStyleVectorFloat(text) : Stylometry.profile(text, 256)) :
        VectorUtils.toFloatArray(value);
    for (final float x : v)
      if (x != 0)
        return v;
    return null;
  }

  /** A member's image hashes from a property value ({@link BitHash#parseAll}): one hash, several, or none. */
  public static long[][] hashesOf(final Object value) {
    return BitHash.parseAll(value);
  }

  private static final class StyleAccumulator {
    long   pairs, above;
    double best = Double.NaN, sum;

    void pair(final float[] x, final float[] y, final double threshold) {
      if (x == null || y == null)
        return;
      final double c = Stylometry.cosine(x, y);
      pairs++;
      sum += c;
      if (c >= threshold)
        above++;
      if (Double.isNaN(best) || c > best)
        best = c;
    }

    StyleStats stats() {
      return new StyleStats(pairs, above, best, pairs == 0 ? Double.NaN : sum / pairs);
    }
  }

  private static final class Accumulator {
    long   pairs, within, memberPairs;
    int    min = NONE;
    double sum;

    void member(final long[][] x, final long[][] y, final int maxDistance) {
      boolean memberWithin = false;
      for (final long[] h : x)
        for (final long[] g : y) {
          final int d = BitHash.hamming(h, g);
          pairs++;
          sum += d;
          if (min == NONE || d < min)
            min = d;
          if (d <= maxDistance) {
            within++;
            memberWithin = true;
          }
        }
      if (memberWithin)
        memberPairs++;
    }

    ImageStats stats() {
      return new ImageStats(pairs, within, memberPairs, min, pairs == 0 ? Double.NaN : sum / pairs);
    }
  }
}
