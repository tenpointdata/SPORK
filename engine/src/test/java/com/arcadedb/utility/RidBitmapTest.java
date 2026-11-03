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
package com.arcadedb.utility;

import com.arcadedb.database.RID;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 (docs/spork/03-f1-selections-design.md): the compressed RID set behind selections. Every operation is checked against
 * a plain {@code HashSet<RID>}, with positions on both sides of 2^32, because a large bucket's positions
 * ({@code pageId * maxRecordsInPage + slot}) do not fit the 32-bit values a single RoaringBitmap holds.
 */
class RidBitmapTest {
  private static final long HIGH = 1L << 32;

  @Test
  void addContainsRemoveAndCardinality() {
    final RidBitmap bitmap = new RidBitmap();
    assertThat(bitmap.isEmpty()).isTrue();

    assertThat(bitmap.add(new RID(3, 10))).isTrue();
    assertThat(bitmap.add(new RID(3, 10))).isFalse();
    assertThat(bitmap.add(3, HIGH + 10)).isTrue();
    assertThat(bitmap.add(new RID(7, 0))).isTrue();

    assertThat(bitmap.cardinality()).isEqualTo(3);
    assertThat(bitmap.contains(new RID(3, 10))).isTrue();
    assertThat(bitmap.contains(3, HIGH + 10)).isTrue();
    assertThat(bitmap.contains(new RID(3, 11))).isFalse();
    assertThat(bitmap.contains(new RID(4, 10))).isFalse();

    assertThat(bitmap.remove(new RID(3, HIGH + 10))).isTrue();
    assertThat(bitmap.remove(new RID(3, HIGH + 10))).isFalse();
    assertThat(bitmap.contains(3, HIGH + 10)).isFalse();
    assertThat(bitmap.cardinality()).isEqualTo(2);
  }

  @Test
  void setOperationsMatchAHashSetReference() {
    final Random random = new Random(42);
    final Set<RID> refA = new HashSet<>();
    final Set<RID> refB = new HashSet<>();
    final RidBitmap a = new RidBitmap();
    final RidBitmap b = new RidBitmap();

    for (int i = 0; i < 20_000; i++) {
      final RID rid = randomRid(random);
      if (random.nextBoolean()) {
        refA.add(rid);
        a.add(rid);
      }
      if (random.nextInt(3) == 0) {
        refB.add(rid);
        b.add(rid);
      }
    }

    final Set<RID> and = new HashSet<>(refA);
    and.retainAll(refB);
    final Set<RID> or = new HashSet<>(refA);
    or.addAll(refB);
    final Set<RID> andNot = new HashSet<>(refA);
    andNot.removeAll(refB);
    final Set<RID> xor = new HashSet<>(or);
    xor.removeAll(and);

    assertThat(toSet(RidBitmap.and(a, b))).isEqualTo(and);
    assertThat(toSet(RidBitmap.or(a, b))).isEqualTo(or);
    assertThat(toSet(RidBitmap.andNot(a, b))).isEqualTo(andNot);
    assertThat(toSet(RidBitmap.xor(a, b))).isEqualTo(xor);
    assertThat(RidBitmap.andCardinality(a, b)).isEqualTo(and.size());

    assertThat(RidBitmap.and(a, b).cardinality()).isEqualTo(and.size());
    assertThat(RidBitmap.or(a, b).cardinality()).isEqualTo(or.size());

    // The static forms leave their inputs alone
    assertThat(toSet(a)).isEqualTo(refA);
    assertThat(toSet(b)).isEqualTo(refB);

    // The in-place forms agree with the static ones
    assertThat(a.copy().andWith(b)).isEqualTo(RidBitmap.and(a, b));
    assertThat(a.copy().orWith(b)).isEqualTo(RidBitmap.or(a, b));
    assertThat(a.copy().andNotWith(b)).isEqualTo(RidBitmap.andNot(a, b));
    assertThat(a.copy().xorWith(b)).isEqualTo(RidBitmap.xor(a, b));
  }

  @Test
  void andWithDropsBucketsTheOtherSideLacks() {
    final RidBitmap a = new RidBitmap();
    a.add(1, 5);
    a.add(2, 5);
    a.add(2, HIGH + 5);
    final RidBitmap b = new RidBitmap();
    b.add(2, 5);

    final RidBitmap result = a.andWith(b);
    assertThat(result.cardinality()).isEqualTo(1);
    assertThat(result.contains(2, 5)).isTrue();
    assertThat(toSet(result)).containsExactly(new RID(2, 5));
  }

  @Test
  void iterationIsOrderedByBucketThenPosition() {
    final RidBitmap bitmap = new RidBitmap();
    bitmap.add(9, 1);
    bitmap.add(2, HIGH + 1);
    bitmap.add(2, 7);
    bitmap.add(2, 0xFFFF_FFFFL); // the largest position below 2^32 must sort after the small ones, not as a negative int
    bitmap.add(0, 3);

    final List<RID> order = new ArrayList<>();
    for (final RID rid : bitmap)
      order.add(rid);

    assertThat(order).containsExactly(new RID(0, 3), new RID(2, 7), new RID(2, 0xFFFF_FFFFL), new RID(2, HIGH + 1),
        new RID(9, 1));

    final List<String> visited = new ArrayList<>();
    bitmap.forEach((bucketId, position) -> visited.add("#" + bucketId + ":" + position));
    assertThat(visited).containsExactly("#0:3", "#2:7", "#2:4294967295", "#2:4294967297", "#9:1");
  }

  @Test
  void serializationRoundTrips() {
    final Random random = new Random(7);
    final RidBitmap bitmap = new RidBitmap();
    for (int i = 0; i < 5_000; i++)
      bitmap.add(randomRid(random));
    bitmap.runOptimize();

    final RidBitmap restored = RidBitmap.fromBytes(bitmap.toBytes());
    assertThat(restored).isEqualTo(bitmap);
    assertThat(restored.cardinality()).isEqualTo(bitmap.cardinality());

    final RidBitmap empty = RidBitmap.fromBytes(new RidBitmap().toBytes());
    assertThat(empty.isEmpty()).isTrue();
  }

  @Test
  void deserializationRefusesAnUnknownFormat() {
    final byte[] bytes = new RidBitmap().toBytes();
    bytes[0] = 99;
    assertThatThrownBy(() -> RidBitmap.fromBytes(bytes)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("format version 99");
  }

  @Test
  void deserializationRefusesTruncatedOrGarbledInputAsBadInput() {
    final RidBitmap bitmap = new RidBitmap();
    bitmap.add(1, 5);
    bitmap.add(2, HIGH + 7);
    final byte[] bytes = bitmap.toBytes();

    assertThatThrownBy(() -> RidBitmap.fromBytes(Arrays.copyOf(bytes, bytes.length - 3)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Corrupted RID bitmap");
    final byte[] garbled = bytes.clone();
    for (int i = 13; i < garbled.length; i++)
      garbled[i] = (byte) 0xFF;
    assertThatThrownBy(() -> RidBitmap.fromBytes(garbled)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesIdentitiesThatAreNotRecordAddresses() {
    final RidBitmap bitmap = new RidBitmap();
    assertThatThrownBy(() -> bitmap.add(new RID(-1, 0))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("#-1:0");
    assertThatThrownBy(() -> bitmap.add(3, -2)).isInstanceOf(IllegalArgumentException.class);
    // A lookup of such an identity is simply absent rather than an error
    assertThat(bitmap.contains(new RID(-1, -1))).isFalse();
  }

  @Test
  void bucketIdsAreDistinctAndSorted() {
    final RidBitmap bitmap = new RidBitmap();
    bitmap.add(9, 1);
    bitmap.add(2, 3);
    bitmap.add(2, HIGH + 3); // a second key in bucket 2, still one bucket id
    assertThat(bitmap.bucketIds()).containsExactly(2, 9);
    assertThat(new RidBitmap().bucketIds()).isEmpty();
  }

  @Test
  void theSetViewAnswersFromTheBitmapAndIsReadOnly() {
    final RidBitmap bitmap = new RidBitmap();
    bitmap.add(1, 5);
    bitmap.add(3, HIGH + 7);
    final RidBitmapSet set = new RidBitmapSet(bitmap);

    assertThat(set).hasSize(2).containsExactly(new RID(1, 5), new RID(3, HIGH + 7));
    assertThat(set.contains(new RID(1, 5))).isTrue();
    assertThat(set.contains(new RID(1, 6))).isFalse();
    assertThat(set.contains("#1:5")).isFalse(); // a string is not a RID: equality, not parsing
    assertThat(set.bucketIds()).containsExactly(1, 3);
    assertThat(set).isEqualTo(Set.of(new RID(1, 5), new RID(3, HIGH + 7)));

    assertThatThrownBy(() -> set.add(new RID(2, 2))).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.retainAll(Set.of())).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.iterator().remove()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equalityIgnoresEmptiedContainers() {
    final RidBitmap a = new RidBitmap();
    a.add(1, 1);
    a.add(5, 1);
    a.remove(new RID(5, 1));
    final RidBitmap b = new RidBitmap();
    b.add(1, 1);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  private static RID randomRid(final Random random) {
    final int bucket = random.nextInt(4);
    final long position = switch (random.nextInt(3)) {
      case 0 -> random.nextInt(5_000);
      case 1 -> 0xFFFF_0000L + random.nextInt(0x1_0000); // just below 2^32
      default -> HIGH * (1 + random.nextInt(2)) + random.nextInt(5_000);
    };
    return new RID(bucket, position);
  }

  private static Set<RID> toSet(final RidBitmap bitmap) {
    final Set<RID> set = new HashSet<>();
    for (final RID rid : bitmap)
      set.add(rid);
    return set;
  }
}
