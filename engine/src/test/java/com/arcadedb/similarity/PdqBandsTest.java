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

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code pdqBands} scheme: the same keys as {@code hamming.bandKeys(pdq, 8)}, the probe counts, and exact recall at the
 * near-duplicate radius 31 on an LSM {@code BY ITEM} index against brute force, including neighbours whose flipped bits are
 * spread evenly over the bands (the pigeonhole worst case: at distance 31 only one band is within 3 bits).
 */
class PdqBandsTest extends TestHelper {
  private static final int[] DISTANCES = { 0, 1, 7, 8, 15, 16, 23, 24, 30, 31, 32, 33, 40 };

  @Test
  void keysAreTheHammingBandKeysAndProbesAreSortedAndUnique() {
    final Random random = new Random(7);
    final long[] pdq = randomHash(random);
    assertThat(PdqBands.keys(pdq)).isEqualTo(HammingBands.bandKeys(pdq, 8));
    assertThat(PdqBands.probeCount(PdqBands.REENCODE_DISTANCE)).isEqualTo(264);
    assertThat(PdqBands.probeCount(PdqHasher.NEAR_DUPLICATE_DISTANCE)).isEqualTo(43_912);

    final long[] probes = PdqBands.probeKeys(pdq, 31);
    assertThat(probes).hasSize(43_912).isSorted();
    final Set<Long> expected = new HashSet<>();
    for (final long k : HammingBands.probeKeys(pdq, 8, 31, 100_000))
      expected.add(k);
    final Set<Long> actual = new HashSet<>();
    for (final long k : probes)
      actual.add(k);
    assertThat(probes).hasSize(expected.size());
    assertThat(actual.equals(expected)).isTrue();

    // two queries sharing a band value share its probes
    final long[] twin = pdq.clone();
    twin[3] ^= 1L;
    final long[] union = PdqBands.probeKeys(new long[][] { pdq, twin }, 15);
    assertThat(union).isSorted().doesNotHaveDuplicates();
    assertThat(union.length).isLessThan(2 * 264);

    assertThatThrownBy(() -> PdqBands.keys(new long[] { 1L })).hasMessageContaining("256 bits");
    assertThatThrownBy(() -> PdqBands.probeKeys(pdq, 40)).hasMessageContaining("maxKeys");
  }

  @Test
  void radius31RecallOnAnLsmIndexIsExactAgainstBruteForce() {
    final Random random = new Random(31);
    final List<long[]> queries = new ArrayList<>();
    final Map<RID, long[]> stored = new HashMap<>();
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Img");
      database.command("sql", "CREATE PROPERTY Img.pdq BINARY");
      database.command("sql", "CREATE PROPERTY Img.pdqBands LIST OF LONG");
      database.command("sql", "CREATE INDEX ON Img (pdqBands BY ITEM) NOTUNIQUE");
    });
    database.transaction(() -> {
      for (int i = 0; i < 4_000; i++)
        store(randomHash(random), stored);
      for (int q = 0; q < 24; q++) {
        final long[] centre = randomHash(random);
        queries.add(centre);
        for (final int d : DISTANCES) {
          store(flipRandom(centre, d, random), stored);
          store(flipSpread(centre, d, random), stored);
        }
      }
    });

    final TypeIndex index = database.getSchema().getType("Img").getPolymorphicIndexByProperties("pdqBands by item");
    assertThat(index).isNotNull();
    int found = 0;
    for (final long[] q : queries) {
      final Set<RID> expected = new HashSet<>();
      for (final Map.Entry<RID, long[]> e : stored.entrySet())
        if (BitHash.hamming(e.getValue(), q) <= 31)
          expected.add(e.getKey());

      final Set<RID> viaIndex = new HashSet<>();
      for (final long key : PdqBands.probeKeys(q, 31)) {
        final IndexCursor cursor = index.get(new Object[] { key });
        while (cursor.hasNext()) {
          final RID rid = cursor.next().getIdentity();
          if (BitHash.hamming(stored.get(rid), q) <= 31)
            viaIndex.add(rid);
        }
      }
      assertThat(viaIndex).isEqualTo(expected);
      // every planted neighbour up to 31 bits, both the random and the evenly spread one
      assertThat(expected.size()).isGreaterThanOrEqualTo(2 * 10);
      found += expected.size();
    }
    assertThat(found).isGreaterThanOrEqualTo(24 * 20);

    // the same through SQL (hamming.probeKeys over the BINARY property) and the Cypher procedure
    for (final long[] q : queries.subList(0, 3)) {
      final Set<RID> expected = new HashSet<>();
      for (final Map.Entry<RID, long[]> e : stored.entrySet())
        if (BitHash.hamming(e.getValue(), q) <= 31)
          expected.add(e.getKey());
      final Set<RID> viaSql = new HashSet<>();
      try (final ResultSet rs = database.query("sql",
          "SELECT @rid AS rid FROM Img WHERE pdqBands CONTAINSANY hamming.probeKeys(:q, 8, 31) AND hamming.distance(pdq, :q) <= 31",
          Map.of("q", BitHash.toHex(q)))) {
        rs.stream().forEach(r -> viaSql.add(r.getProperty("rid")));
      }
      assertThat(viaSql).isEqualTo(expected);

      final Set<RID> viaProcedure = new HashSet<>();
      try (final ResultSet rs = database.query("opencypher",
          "CALL similarity.hash.neighbors($q, {type: 'Img', property: 'pdq', bandsProperty: 'pdqBands', bands: 8, maxDistance: 31,"
              + " limit: 1000}) YIELD node RETURN node", Map.of("q", BitHash.toBytes(q)))) {
        rs.stream().forEach(r -> viaProcedure.add(((Document) r.<Object>getProperty("node")).getIdentity()));
      }
      assertThat(viaProcedure).isEqualTo(expected);
    }
  }

  private void store(final long[] pdq, final Map<RID, long[]> stored) {
    final List<Long> bands = new ArrayList<>(8);
    for (final long k : PdqBands.keys(pdq))
      bands.add(k);
    final Document doc = database.newDocument("Img").set("pdq", BitHash.toBytes(pdq)).set("pdqBands", bands).save();
    stored.put(doc.getIdentity(), pdq);
  }

  static long[] randomHash(final Random random) {
    return new long[] { random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong() };
  }

  /** {@code d} distinct random bits flipped. */
  static long[] flipRandom(final long[] hash, final int d, final Random random) {
    final long[] out = hash.clone();
    final Set<Integer> bits = new HashSet<>();
    while (bits.size() < d)
      bits.add(random.nextInt(256));
    for (final int bit : bits)
      out[bit >>> 6] ^= 1L << (63 - (bit & 63));
    return out;
  }

  /** {@code d} bits flipped as evenly as possible over the 8 bands of 32 bits, so no band is closer than {@code d / 8}. */
  static long[] flipSpread(final long[] hash, final int d, final Random random) {
    final long[] out = hash.clone();
    final int start = random.nextInt(8);
    for (int i = 0; i < d; i++) {
      final int band = (start + i) % 8;
      final int round = i / 8;
      final int bit = band * 32 + (round * 7 + band) % 32;
      out[bit >>> 6] ^= 1L << (63 - (bit & 63));
    }
    return out;
  }
}
