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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BitHash}, {@link HammingBands} and {@link HammingIndex}: parsing and distance, the pigeonhole guarantee of the
 * band and probe keys, and the in-memory multi-index search against brute force.
 */
class HammingTest {

  // ---- BitHash ----

  @Test
  void parsesEveryRepresentationOfTheSameHash() {
    final long[] h = { 0x8F00_0000_0000_00A1L };
    assertThat(BitHash.parse("8f000000000000a1")).isEqualTo(h);
    assertThat(BitHash.parse("0x8F000000000000A1")).isEqualTo(h);
    assertThat(BitHash.parse(0x8F00_0000_0000_00A1L)).isEqualTo(h);
    assertThat(BitHash.parse(new byte[] { (byte) 0x8F, 0, 0, 0, 0, 0, 0, (byte) 0xA1 })).isEqualTo(h);
    assertThat(BitHash.parse(List.of(0x8F00_0000_0000_00A1L))).isEqualTo(h);
    assertThat(BitHash.toHex(h)).isEqualTo("8f000000000000a1");
    // a short hex string is a hash of its own width, left-padded to whole longs
    assertThat(BitHash.parse("ff")).isEqualTo(new long[] { 0xFFL });
  }

  @Test
  void hammingCountsDifferingBits() {
    assertThat(BitHash.hamming(new long[] { 0 }, new long[] { -1L })).isEqualTo(64);
    assertThat(BitHash.hamming(new long[] { 0b1011, 7 }, new long[] { 0b0001, 7 })).isEqualTo(2);
    assertThatThrownBy(() -> BitHash.hamming(new long[1], new long[4])).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64").hasMessageContaining("256");
  }

  @Test
  void refusesWhatIsNotAHash() {
    assertThatThrownBy(() -> BitHash.parse("xyz")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BitHash.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BitHash.parse(3.5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BitHash.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- HammingBands ----

  @Test
  void bandKeysAreTaggedChunks() {
    final long[] keys = HammingBands.bandKeys(new long[] { 0x0001_0002_0003_0004L }, 4);
    assertThat(keys).containsExactly(1L, (1L << 32) | 2, (2L << 32) | 3, (3L << 32) | 4);
    assertThatThrownBy(() -> HammingBands.bandKeys(new long[1], 1)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
    assertThatThrownBy(() -> HammingBands.bandKeys(new long[1], 5)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("divide");
  }

  @Test
  void probeKeysCountIsTheBallSize() {
    // 4 bands of 16 bits, distance 7: radius 1 per band, 1 + 16 keys per band
    assertThat(HammingBands.probeKeys(new long[] { 42 }, 4, 7, 100_000)).hasSize(4 * 17);
    // radius 2: 1 + 16 + 120
    assertThat(HammingBands.probeKeys(new long[] { 42 }, 4, 8, 100_000)).hasSize(4 * 137);
    assertThat(HammingBands.probeCount(64, 4, 8)).isEqualTo(4 * 137);
    assertThatThrownBy(() -> HammingBands.probeKeys(new long[] { 42 }, 2, 20, 1000)).isInstanceOf(
        IllegalArgumentException.class).hasMessageContaining("maxKeys");
  }

  @Test
  void everyHashWithinTheDistanceSharesAProbeKey() {
    final Random random = new Random(11);
    for (final int words : new int[] { 1, 4 }) {
      final int bits = words * 64;
      final int bands = words == 1 ? 4 : 16;
      final int maxDistance = words == 1 ? 9 : 31;
      for (int trial = 0; trial < 300; trial++) {
        final long[] a = randomHash(random, words);
        final long[] b = flip(a, random, random.nextInt(maxDistance + 1), bits);
        final Set<Long> stored = new HashSet<>();
        for (final long k : HammingBands.bandKeys(b, bands))
          stored.add(k);
        boolean found = false;
        for (final long k : HammingBands.probeKeys(a, bands, maxDistance, 1_000_000))
          found |= stored.contains(k);
        assertThat(found).as("trial %d, %d bits, distance %d", trial, bits, BitHash.hamming(a, b)).isTrue();
      }
    }
  }

  @Test
  void suggestedBandsKeepChunksNearLogOfThePopulation() {
    assertThat(HammingBands.suggestBands(64, 1_000_000_000L)).isEqualTo(2);
    assertThat(HammingBands.suggestBands(64, 50_000)).isEqualTo(4);
    assertThat(HammingBands.suggestBands(256, 1_350_000_000L)).isEqualTo(8);
    assertThat(HammingBands.suggestBands(256, 10)).isEqualTo(64);
  }

  // ---- HammingIndex ----

  @Test
  void multiIndexSearchMatchesBruteForce() {
    final Random random = new Random(5);
    for (final int words : new int[] { 1, 4 }) {
      final int n = 6000;
      final int maxDistance = words == 1 ? 6 : 24;
      final long[][] hashes = new long[n][];
      for (int i = 0; i < n; i++)
        hashes[i] = i > 0 && random.nextInt(4) == 0 ?
            flip(hashes[random.nextInt(i)], random, random.nextInt(maxDistance + 3), words * 64) :
            randomHash(random, words);

      final HammingIndex index = new HammingIndex(hashes, maxDistance);
      assertThat(index.usesBands()).isTrue();
      final List<String> viaIndex = new ArrayList<>();
      index.forEachPair((i, j, d) -> viaIndex.add(i + "-" + j + ":" + d));

      final List<String> bruteForce = new ArrayList<>();
      for (int i = 0; i < n; i++)
        for (int j = i + 1; j < n; j++) {
          final int d = BitHash.hamming(hashes[i], hashes[j]);
          if (d <= maxDistance)
            bruteForce.add(i + "-" + j + ":" + d);
        }
      assertThat(bruteForce).hasSizeGreaterThan(100);
      assertThat(viaIndex).containsExactlyInAnyOrderElementsOf(bruteForce);

      final List<String> neighbours = new ArrayList<>();
      index.forEachNeighbour(hashes[7], (i, d) -> neighbours.add(i + ":" + d));
      for (int i = 0; i < n; i++)
        if (BitHash.hamming(hashes[7], hashes[i]) <= maxDistance)
          assertThat(neighbours).contains(i + ":" + BitHash.hamming(hashes[7], hashes[i]));
    }
  }

  @Test
  void smallSetsUseBruteForce() {
    final long[][] hashes = { { 0 }, { 1 }, { 3 }, { -1L } };
    final HammingIndex index = new HammingIndex(hashes, 2);
    assertThat(index.usesBands()).isFalse();
    final List<String> pairs = new ArrayList<>();
    index.forEachPair((i, j, d) -> pairs.add(i + "-" + j + ":" + d));
    assertThat(pairs).containsExactlyInAnyOrder("0-1:1", "0-2:2", "1-2:1");
  }

  @Test
  void indexRefusesMixedWidths() {
    assertThatThrownBy(() -> new HammingIndex(new long[][] { { 0 }, { 0, 0, 0, 0 } }, 2)).isInstanceOf(
        IllegalArgumentException.class);
  }

  private static long[] randomHash(final Random random, final int words) {
    final long[] h = new long[words];
    for (int i = 0; i < words; i++)
      h[i] = random.nextLong();
    return h;
  }

  private static long[] flip(final long[] source, final Random random, final int count, final int bits) {
    final long[] h = source.clone();
    final Set<Integer> flipped = new HashSet<>();
    while (flipped.size() < count)
      flipped.add(random.nextInt(bits));
    for (final int bit : flipped)
      h[bit >>> 6] ^= 1L << (63 - (bit & 63));
    return h;
  }
}
