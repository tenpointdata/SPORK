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

import java.util.Arrays;

/**
 * The byte layouts the sidecar hands to the index for one vector (01-architecture.md 4c.1 and 4c.2). Every method reads
 * {@code dims} floats of {@code v} from {@code off} and writes into {@code out} from {@code outOff}, so a caller can
 * code a whole batch held in one flat array without copying rows.
 * <ul>
 *   <li><b>float32</b>: the canonical vector, little-endian. The index trains its codebook on it and can derive any
 *   centroid-relative code from it; the sidecar never sees the codebook.</li>
 *   <li><b>binary</b> (1-bit scan code, 32 B at 256-d): the engine's {@code BINARY} layout, bit {@code i} of byte
 *   {@code i / 8} set when component {@code i} is at or above the vector's own median, plus that median
 *   ({@code LSMVectorIndex.quantizeToBinary}, {@code BinaryCodeCache.pack}).</li>
 *   <li><b>int4</b> (4-bit rerank code, 128 B at 256-d): min-max scaled to 0..15, two components per byte, the even
 *   component in the low nibble, plus the min and the max.</li>
 *   <li><b>int8</b>: the engine's {@code INT8} layout, min-max scaled to -128..127, plus the min and the max.</li>
 * </ul>
 */
public final class VectorCodes {
  private VectorCodes() {
  }

  public static int binaryBytes(final int dims) {
    return (dims + 7) >>> 3;
  }

  public static int int4Bytes(final int dims) {
    return (dims + 1) >>> 1;
  }

  /** @return the median the bits are relative to */
  public static float binary(final float[] v, final int off, final int dims, final byte[] out, final int outOff) {
    final float[] row = off == 0 && dims == v.length ? v : Arrays.copyOfRange(v, off, off + dims);
    final float median = VectorUtils.median(row);
    final int bytes = binaryBytes(dims);
    Arrays.fill(out, outOff, outOff + bytes, (byte) 0);
    float min = row[0];
    float max = row[0];
    for (int i = 0; i < dims; i++) {
      final float x = row[i];
      if (x >= median)
        out[outOff + (i >>> 3)] |= (byte) (1 << (i & 7));
      if (x < min)
        min = x;
      if (x > max)
        max = x;
    }
    // Every bit set is how the engine stores a CONSTANT vector: a non-constant one whose median sits on its minimum
    // would collide with it, so its minimum components go below the median (same rule as LSMVectorIndex)
    if (min != max && median == min)
      for (int i = 0; i < dims; i++)
        if (row[i] == min)
          out[outOff + (i >>> 3)] &= (byte) ~(1 << (i & 7));
    return median;
  }

  /** Writes {@code range[0] = min, range[1] = max}. A constant vector codes as all zeros. */
  public static void int4(final float[] v, final int off, final int dims, final byte[] out, final int outOff, final float[] range) {
    minMax(v, off, dims, range);
    final float min = range[0];
    final float span = range[1] - min;
    final float scale = span == 0f ? 0f : 15f / span;
    Arrays.fill(out, outOff, outOff + int4Bytes(dims), (byte) 0);
    for (int i = 0; i < dims; i++) {
      final int q = Math.min(15, Math.round((v[off + i] - min) * scale));
      out[outOff + (i >>> 1)] |= (byte) ((i & 1) == 0 ? q : q << 4);
    }
  }

  public static void dequantizeInt4(final byte[] code, final int codeOff, final int dims, final float min, final float max, final float[] out,
      final int outOff) {
    final float step = (max - min) / 15f;
    for (int i = 0; i < dims; i++) {
      final int b = code[codeOff + (i >>> 1)];
      final int q = (i & 1) == 0 ? b & 0x0F : (b >>> 4) & 0x0F;
      out[outOff + i] = min + q * step;
    }
  }

  /** Writes {@code range[0] = min, range[1] = max}. A constant vector codes as all zeros, as in the engine. */
  public static void int8(final float[] v, final int off, final int dims, final byte[] out, final int outOff, final float[] range) {
    minMax(v, off, dims, range);
    final float min = range[0];
    final float span = range[1] - min;
    for (int i = 0; i < dims; i++)
      out[outOff + i] = span == 0f ? 0 : (byte) (Math.round((v[off + i] - min) / span * 255.0f) - 128);
  }

  public static void float32(final float[] v, final int off, final int dims, final byte[] out, final int outOff) {
    for (int i = 0; i < dims; i++) {
      final int bits = Float.floatToRawIntBits(v[off + i]);
      final int o = outOff + (i << 2);
      out[o] = (byte) bits;
      out[o + 1] = (byte) (bits >>> 8);
      out[o + 2] = (byte) (bits >>> 16);
      out[o + 3] = (byte) (bits >>> 24);
    }
  }

  private static void minMax(final float[] v, final int off, final int dims, final float[] range) {
    float min = v[off];
    float max = v[off];
    for (int i = 1; i < dims; i++) {
      final float x = v[off + i];
      if (x < min)
        min = x;
      if (x > max)
        max = x;
    }
    range[0] = min;
    range[1] = max;
  }
}
