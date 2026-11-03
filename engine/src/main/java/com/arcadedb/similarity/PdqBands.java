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
 * The {@code pdqBands} multi-index hashing scheme for PDQ (docs/spork/workstreams/ws12-pdq.md): the {@link HammingBands}
 * keys of a 256-bit PDQ hash cut into {@value #BANDS} chunks of 32 bits, stored in a {@code LIST OF LONG} property under a
 * {@code BY ITEM} LSM index. Nothing here is a second mechanism: {@link #keys} is {@code hamming.bandKeys(pdq, 8)} and
 * {@link #probeKeys} is {@code hamming.probeKeys(pdq, 8, d)}, so SQL, {@code similarity.hash.neighbors} and Java callers
 * share one index.
 * <p>
 * Recall is exact at any radius: a hash within d of the query differs by at most {@code d / 8} bits in some band, and the
 * probe enumerates every chunk value within that many bits of each query chunk. The cost is the probe count, 8 x the
 * Hamming ball of radius {@code d / 8} in 32 bits: 264 keys at {@value #REENCODE_DISTANCE} (re-encodes, ingest dedup) and
 * 43,912 at {@value PdqHasher#NEAR_DUPLICATE_DISTANCE} (the reference's near-duplicate cut). Probes come back sorted and
 * without duplicates, so the index is read in key order and the 8 dihedral hashes of one image share their lookups.
 * </p>
 * <p>
 * Where the index lives is the architecture's call (01-architecture.md 4c.1, 06-stylometry-perceptual-hash.md 3.1): the
 * band index is kept on the hot-window media and case databases, and the corpus-wide radius-31 leg is the S-PDQ IVF space.
 * </p>
 */
public final class PdqBands {
  public static final int BANDS             = 8;
  /** Re-encodes and resizes, the ingest dedup radius: 264 probe keys. */
  public static final int REENCODE_DISTANCE = 15;
  /** The probe-count ceiling, as {@code hamming.probeKeys}: radius 31 needs 43,912 keys per query hash. */
  public static final int MAX_PROBE_KEYS    = 100_000;

  private PdqBands() {
  }

  /** The 8 band keys of a PDQ hash, {@code hamming.bandKeys(pdq, 8)}. */
  public static long[] keys(final long[] pdq) {
    checkWidth(pdq);
    return HammingBands.bandKeys(pdq, BANDS);
  }

  /** The keys whose lookup reaches every hash within {@code maxDistance} of {@code pdq}, sorted, without duplicates. */
  public static long[] probeKeys(final long[] pdq, final int maxDistance) {
    return probeKeys(new long[][] { pdq }, maxDistance);
  }

  /**
   * The union of the probe keys of several query hashes (the 8 dihedral hashes of an image, or the hashes of a record's
   * images), sorted and without duplicates; refused above {@value #MAX_PROBE_KEYS} keys per query hash.
   */
  public static long[] probeKeys(final long[][] queries, final int maxDistance) {
    if (queries.length == 0)
      return new long[0];
    final long perQuery = probeCount(maxDistance);
    final long[] all = new long[Math.toIntExact(perQuery * queries.length)];
    int size = 0;
    for (final long[] q : queries) {
      checkWidth(q);
      final long[] keys = HammingBands.probeKeys(q, BANDS, maxDistance, MAX_PROBE_KEYS);
      System.arraycopy(keys, 0, all, size, keys.length);
      size += keys.length;
    }
    Arrays.sort(all, 0, size);
    int unique = 0;
    for (int i = 0; i < size; i++)
      if (unique == 0 || all[i] != all[unique - 1])
        all[unique++] = all[i];
    return unique == all.length ? all : Arrays.copyOf(all, unique);
  }

  /** How many keys one query hash probes at {@code maxDistance}. */
  public static long probeCount(final int maxDistance) {
    return HammingBands.probeCount(PdqHasher.BITS, BANDS, maxDistance);
  }

  private static void checkWidth(final long[] hash) {
    if (hash.length != PdqHasher.BITS / 64)
      throw new IllegalArgumentException("A PDQ hash has " + PdqHasher.BITS + " bits, got " + hash.length * 64);
  }
}
