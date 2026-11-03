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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.spork.encoder;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PoolingTest {
  // batch 2, 3 tokens, hidden 4: row b, token t, component h = 100 b + 10 t + h
  private static final float[] HIDDEN = new float[2 * 3 * 4];

  static {
    for (int b = 0; b < 2; b++)
      for (int t = 0; t < 3; t++)
        for (int h = 0; h < 4; h++)
          HIDDEN[(b * 3 + t) * 4 + h] = 100 * b + 10 * t + h;
  }

  @Test
  void clsTakesTokenZeroOfEveryRowAndTruncates() {
    final float[] out = new float[2 * 2];
    Pooling.CLS.pool(FloatBuffer.wrap(HIDDEN), 2, 3, 4, null, 2, out);
    assertThat(out).containsExactly(0f, 1f, 100f, 101f);
  }

  @Test
  void meanAveragesOnlyTheTokensTheMaskKeeps() {
    final long[] mask = { 1, 1, 0, 1, 1, 1 };
    final float[] out = new float[2 * 4];
    Pooling.MEAN.pool(FloatBuffer.wrap(HIDDEN), 2, 3, 4, mask, 4, out);
    // Row 0: tokens 0 and 1 -> 5 + h. Row 1: tokens 0, 1, 2 -> 110 + h
    assertThat(out).containsExactly(5f, 6f, 7f, 8f, 110f, 111f, 112f, 113f);
  }

  @Test
  void noneTreatsTheOutputAsAlreadyPooled() {
    final float[] pooled = { 1f, 2f, 3f, 4f, 5f, 6f };
    final float[] out = new float[2 * 2];
    Pooling.NONE.pool(FloatBuffer.wrap(pooled), 2, 1, 3, null, 2, out);
    assertThat(out).containsExactly(1f, 2f, 4f, 5f);
  }

  @Test
  void normalizeMakesEveryRowUnitLengthAndLeavesZeroRowsAlone() {
    final float[] rows = { 3f, 4f, 0f, 0f, 1f, 1f };
    Pooling.l2NormalizeRows(rows, 3, 2);
    assertThat(rows[0]).isCloseTo(0.6f, within(1e-6f));
    assertThat(rows[1]).isCloseTo(0.8f, within(1e-6f));
    assertThat(rows[2]).isZero();
    assertThat(rows[3]).isZero();
    assertThat(rows[4]).isCloseTo((float) (1 / Math.sqrt(2)), within(1e-6f));
  }
}
