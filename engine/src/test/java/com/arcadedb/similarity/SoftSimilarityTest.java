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

import com.arcadedb.similarity.SoftSimilarity.ImageStats;
import com.arcadedb.similarity.SoftSimilarity.StyleStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static com.arcadedb.similarity.SoftSimilarity.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SoftSimilarity}: the per-member soft-evidence statistics the identity model consumes, with skip indexes for
 * leave-one-out views.
 */
class SoftSimilarityTest {
  private static final float[] X  = { 1, 0 };
  private static final float[] Y  = { 0, 1 };
  private static final float[] XY = { 1, 1 };

  @Test
  void styleStatsOverEveryPair() {
    final StyleStats s = SoftSimilarity.style(new float[][] { X, Y }, NONE, new float[][] { X, XY, null }, NONE, 0.9);
    // pairs: X-X 1, X-XY .707, Y-X 0, Y-XY .707; the null member is not compared
    assertThat(s.pairs()).isEqualTo(4);
    assertThat(s.pairsAbove()).isEqualTo(1);
    assertThat(s.best()).isCloseTo(1.0, within(1e-9));
    assertThat(s.mean()).isCloseTo((1 + Math.sqrt(0.5) + 0 + Math.sqrt(0.5)) / 4, within(1e-6));
    assertThat(SoftSimilarity.style(new float[][] { X, Y }, NONE, new float[][] { X, XY }, NONE, 0.7).pairsAbove()).isEqualTo(3);
  }

  @Test
  void skipIndexesLeaveAMemberOut() {
    final float[][] members = { X, Y, XY };
    final StyleStats skipped = SoftSimilarity.style(members, 0, members, NONE, 0.9);
    assertThat(skipped.pairs()).isEqualTo(6);
    final StyleStats rest = SoftSimilarity.style(new float[][] { Y, XY }, NONE, members, NONE, 0.9);
    assertThat(skipped).isEqualTo(rest);

    // leave-one-out: member j against every other member, no self pair
    for (int j = 0; j < members.length; j++) {
      final StyleStats loo = SoftSimilarity.styleAgainstRest(members, j, 0.9);
      assertThat(loo).isEqualTo(SoftSimilarity.style(new float[][] { members[j] }, NONE, members, j, 0.9));
      assertThat(loo.pairs()).isEqualTo(2);
    }
  }

  @Test
  void nothingComparedIsReportedAsSuch() {
    final StyleStats s = SoftSimilarity.style(new float[][] { null }, NONE, new float[][] { X }, NONE, 0.5);
    assertThat(s.pairs()).isZero();
    assertThat(s.best()).isNaN();
    assertThat(s.mean()).isNaN();
    final ImageStats i = SoftSimilarity.images(new long[][][] { {} }, NONE, new long[][][] { { { 1 } } }, NONE, 4);
    assertThat(i.pairs()).isZero();
    assertThat(i.minDistance()).isEqualTo(NONE);
    assertThat(i.meanDistance()).isNaN();
  }

  @Test
  void imageStatsCountHashAndMemberPairs() {
    // member a0 has two images; b0 holds a near copy of one of them, b1 of nothing
    final long[][][] a = { { { 0b1111L }, { -1L } }, { { 0L } } };
    final long[][][] b = { { { 0b0111L } }, { { 0x00FF_00FF_00FF_00FFL } } };
    final ImageStats s = SoftSimilarity.images(a, NONE, b, NONE, 4);
    assertThat(s.pairs()).isEqualTo(6);
    // within 4: a0[0]-b0 (1), a1-b0 (3)
    assertThat(s.pairsWithin()).isEqualTo(2);
    assertThat(s.memberPairsWithin()).isEqualTo(2);
    assertThat(s.minDistance()).isEqualTo(1);
    assertThat(SoftSimilarity.images(a, 0, b, NONE, 4).minDistance()).isEqualTo(3);
    assertThat(SoftSimilarity.images(a, NONE, b, 0, 4).pairsWithin()).isZero();
    assertThat(SoftSimilarity.images(a, NONE, b, NONE, 0).pairsWithin()).isZero();
  }

  @Test
  void imageLeaveOneOutAgreesWithTheExplicitForm() {
    final Random random = new Random(3);
    final long[][][] members = new long[40][][];
    for (int m = 0; m < members.length; m++) {
      members[m] = new long[random.nextInt(3)][];
      for (int h = 0; h < members[m].length; h++)
        members[m][h] = new long[] { random.nextLong() & 0xFFFFL, 0, 0, 0 };
    }
    for (int j = 0; j < members.length; j++)
      assertThat(SoftSimilarity.imagesAgainstRest(members, j, 9)).isEqualTo(
          SoftSimilarity.images(new long[][][] { members[j] }, NONE, members, j, 9));
  }

  @Test
  void mixedWidthsAndDimensionsAreRefused() {
    assertThatThrownBy(() -> SoftSimilarity.images(new long[][][] { { { 1 } } }, NONE, new long[][][] { { { 1, 2, 3, 4 } } }, NONE, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SoftSimilarity.style(new float[][] { X }, NONE, new float[][] { { 1, 2, 3 } }, NONE, 0.5)).isInstanceOf(
        IllegalArgumentException.class);
  }

  @Test
  void readersTurnPropertyValuesIntoMemberInputs() {
    assertThat(SoftSimilarity.styleOf(null, false)).isNull();
    assertThat(SoftSimilarity.styleOf("", false)).isNull();
    assertThat(SoftSimilarity.styleOf("Call me now!!", true)).isEqualTo(Stylometry.reconStyleVectorFloat("Call me now!!"));
    assertThat(SoftSimilarity.styleOf("Call me now!!", false)).hasSize(256);
    assertThat(SoftSimilarity.styleOf(List.of(0.5f, 0.5f), false)).containsExactly(0.5f, 0.5f);
    assertThat(SoftSimilarity.hashesOf(List.of("00ff", "ff00"))).hasNumberOfRows(2);
    assertThat(SoftSimilarity.hashesOf(null)).isEmpty();
  }
}
