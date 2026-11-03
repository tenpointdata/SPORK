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

import java.util.function.LongConsumer;

/**
 * Multi-index hashing (Norouzi, Punjani and Fleet, 2012) keys, so a Hamming radius search runs on an ordinary
 * {@code BY ITEM} LSM index instead of a scan.
 * <p>
 * A b-bit hash is cut into {@code bands} equal chunks of at most 32 bits. Store {@link #bandKeys} (one key per chunk,
 * tagged with its band) in a {@code LIST OF LONG} property indexed {@code BY ITEM}. To find every hash within distance r of
 * a query, look up {@link #probeKeys}: every chunk value within r / bands (rounded down) of the query's chunk, in each
 * band. By the pigeonhole principle a hash within r differs from the query by at most r / bands bits in at least one band,
 * so the lookup misses nothing; candidates are then verified with the full distance.
 * </p>
 * <p>
 * The trade-off is the chunk width: a chunk of about log2(N) bits makes each key match about one stored hash, so the
 * lookup reads few false candidates; narrower chunks (more bands) need fewer probe keys per band but match more hashes
 * per key. {@link #suggestBands} picks the width.
 * </p>
 */
public final class HammingBands {
  private HammingBands() {
  }

  public static long[] bandKeys(final long[] hash, final int bands) {
    final int chunkBits = chunkBits(hash.length * 64, bands);
    final long[] keys = new long[bands];
    for (int b = 0; b < bands; b++)
      keys[b] = ((long) b << 32) | BitHash.chunk(hash, b * chunkBits, chunkBits);
    return keys;
  }

  /**
   * The keys whose lookup finds every hash within {@code maxDistance} of {@code hash}: refused when there would be more
   * than {@code maxKeys} of them.
   */
  public static long[] probeKeys(final long[] hash, final int bands, final int maxDistance, final int maxKeys) {
    if (maxDistance < 0)
      throw new IllegalArgumentException("maxDistance cannot be negative, got " + maxDistance);
    final int bits = hash.length * 64;
    final int chunkBits = chunkBits(bits, bands);
    final long count = probeCount(bits, bands, maxDistance);
    if (count > maxKeys)
      throw new IllegalArgumentException(
          "A " + bits + "-bit hash in " + bands + " bands needs " + count + " probe keys for distance " + maxDistance
              + ", more than maxKeys " + maxKeys + ": use more bands or a smaller distance");
    final long[] keys = new long[(int) count];
    final int radius = maxDistance / bands;
    final int[] size = { 0 };
    for (int b = 0; b < bands; b++) {
      final long tag = (long) b << 32;
      forEachWithin(BitHash.chunk(hash, b * chunkBits, chunkBits), chunkBits, radius, v -> keys[size[0]++] = tag | v);
    }
    return keys;
  }

  /** How many keys {@link #probeKeys} returns: bands x the size of a Hamming ball of radius maxDistance / bands. */
  public static long probeCount(final int bits, final int bands, final int maxDistance) {
    final int chunkBits = chunkBits(bits, bands);
    final int radius = Math.min(maxDistance / bands, chunkBits);
    long ball = 0;
    long binomial = 1;
    for (int k = 0; k <= radius; k++) {
      ball += binomial;
      binomial = binomial * (chunkBits - k) / (k + 1);
    }
    return ball * bands;
  }

  /**
   * The number of bands that makes each chunk about log2(population) bits wide: a power of two dividing {@code bits},
   * nearest on a log scale, with chunks of at most 32 bits.
   */
  public static int suggestBands(final int bits, final long population) {
    final double target = bits / Math.max(1.0, Math.log(Math.max(2, population)) / Math.log(2));
    int best = Math.max(1, bits / 32);
    double bestError = Double.MAX_VALUE;
    for (int bands = best; bands <= bits; bands <<= 1) {
      final double error = Math.abs(Math.log(bands / target));
      if (error < bestError) {
        bestError = error;
        best = bands;
      }
    }
    return best;
  }

  static int chunkBits(final int bits, final int bands) {
    if (bands < 1 || bits % bands != 0)
      throw new IllegalArgumentException("bands must divide the " + bits + " bits of the hash, got " + bands);
    final int chunkBits = bits / bands;
    if (chunkBits > 32)
      throw new IllegalArgumentException(
          "A band holds at most 32 bits: a " + bits + "-bit hash needs at least " + bits / 32 + " bands, got " + bands);
    return chunkBits;
  }

  /** Calls {@code action} with every value of {@code chunkBits} bits within {@code radius} bits of {@code value}. */
  static void forEachWithin(final long value, final int chunkBits, final int radius, final LongConsumer action) {
    action.accept(value);
    if (radius > 0)
      flip(value, chunkBits, 0, Math.min(radius, chunkBits), action);
  }

  private static void flip(final long value, final int chunkBits, final int from, final int left, final LongConsumer action) {
    for (int bit = from; bit < chunkBits; bit++) {
      final long flipped = value ^ (1L << bit);
      action.accept(flipped);
      if (left > 1)
        flip(flipped, chunkBits, bit + 1, left - 1, action);
    }
  }
}
