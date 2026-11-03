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

import com.arcadedb.index.vector.VectorUtils;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VectorCodesTest {

  @Test
  void binaryCodeSetsBitIOfByteIOver8AtOrAboveTheMedian() {
    final float[] v = { 0.5f, -0.25f, 0.1f, 0.9f, -0.8f, 0.0f, 0.3f, -0.1f, 0.7f };
    final byte[] code = new byte[VectorCodes.binaryBytes(v.length)];
    final float median = VectorCodes.binary(v, 0, v.length, code, 0);

    assertThat(median).isEqualTo(VectorUtils.median(v));
    assertThat(code).hasSize(2);
    for (int i = 0; i < v.length; i++) {
      final boolean set = (code[i >>> 3] & (1 << (i & 7))) != 0;
      assertThat(set).as("bit %d", i).isEqualTo(v[i] >= median);
    }
  }

  @Test
  void binaryCodeOfAConstantVectorIsEveryBitSet() {
    final float[] v = { 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f };
    final byte[] code = new byte[1];
    VectorCodes.binary(v, 0, v.length, code, 0);
    assertThat(code[0]).isEqualTo((byte) 0xFF);
  }

  @Test
  void binaryCodeClearsTheMinimumWhenTheMedianSitsOnIt() {
    // The engine's rule (LSMVectorIndex.quantizeToBinary): a non-constant vector must not read back as the constant one
    final float[] v = { 0f, 0f, 0f, 5f };
    final byte[] code = new byte[1];
    VectorCodes.binary(v, 0, v.length, code, 0);
    assertThat(code[0]).isEqualTo((byte) 0b1000);
  }

  @Test
  void binaryCodeReadsAtAnOffsetAndWritesAtAnOffset() {
    final float[] rows = { 9f, 9f, 1f, -1f, 2f, -2f, 9f };
    final byte[] code = new byte[3];
    VectorCodes.binary(rows, 2, 4, code, 2);
    assertThat(code[0]).isZero();
    assertThat(code[1]).isZero();
    assertThat(code[2]).isEqualTo((byte) 0b0101);
  }

  @Test
  void int4PacksTwoComponentsPerByteLowNibbleFirstAndRoundTripsWithinHalfAStep() {
    final Random random = new Random(42);
    final float[] v = new float[256];
    for (int i = 0; i < v.length; i++)
      v[i] = (float) random.nextGaussian() * 0.06f;

    final byte[] code = new byte[VectorCodes.int4Bytes(v.length)];
    final float[] range = new float[2];
    VectorCodes.int4(v, 0, v.length, code, 0, range);

    assertThat(code).hasSize(128);
    final float step = (range[1] - range[0]) / 15f;
    final float[] back = new float[v.length];
    VectorCodes.dequantizeInt4(code, 0, v.length, range[0], range[1], back, 0);
    for (int i = 0; i < v.length; i++)
      assertThat(back[i]).isCloseTo(v[i], within(step / 2f + 1e-6f));

    // Component 0 is the low nibble of byte 0, component 1 its high nibble
    final int q0 = Math.round((v[0] - range[0]) / step);
    final int q1 = Math.round((v[1] - range[0]) / step);
    assertThat(code[0] & 0x0F).isEqualTo(q0);
    assertThat((code[0] >>> 4) & 0x0F).isEqualTo(q1);
  }

  @Test
  void int4OfAnOddDimensionLeavesTheLastHighNibbleClear() {
    final float[] v = { 0f, 1f, 0.5f };
    final byte[] code = new byte[2];
    final float[] range = new float[2];
    VectorCodes.int4(v, 0, v.length, code, 0, range);
    assertThat(range[0]).isEqualTo(0f);
    assertThat(range[1]).isEqualTo(1f);
    assertThat(code[0]).isEqualTo((byte) 0xF0);
    assertThat(code[1] & 0xF0).isZero();
  }

  @Test
  void int8MatchesTheEngineMinMaxFormula() {
    final float[] v = { -0.5f, 0f, 0.25f, 0.5f };
    final byte[] code = new byte[v.length];
    final float[] range = new float[2];
    VectorCodes.int8(v, 0, v.length, code, 0, range);
    assertThat(range).containsExactly(-0.5f, 0.5f);
    // (v - min) / (max - min) * 255 rounded, shifted by -128, as LSMVectorIndex.quantizeToInt8: 0.75 * 255 = 191.25 -> 63
    assertThat(code).containsExactly((byte) -128, (byte) 0, (byte) 63, (byte) 127);
  }

  @Test
  void float32IsLittleEndian() {
    final float[] v = { 1.5f, -2f, 0.125f };
    final byte[] bytes = new byte[12];
    VectorCodes.float32(v, 0, 3, bytes, 0);
    final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    assertThat(buffer.getFloat()).isEqualTo(1.5f);
    assertThat(buffer.getFloat()).isEqualTo(-2f);
    assertThat(buffer.getFloat()).isEqualTo(0.125f);
  }
}
