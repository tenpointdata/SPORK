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
package com.arcadedb.index.ivfvector;

import java.nio.ByteBuffer;

/**
 * The codes an {@code LSM_IVF_VECTOR} posting entry carries and the arithmetic a scan runs on them, all on primitive
 * arrays and page buffers: nothing here allocates per entry.
 * <ul>
 *   <li>BINARY: one bit per dimension, {@code v[d] > threshold[d]}, packed in 64-bit words so the Hamming distance is one
 *   XOR and one popcount per word</li>
 *   <li>INT8: {@code round(v[d] / scale)} with {@code scale = max|v| / 127} per vector, scored by an integer dot product
 *   and the two scales</li>
 *   <li>INT4: four bits per dimension of the residual against the list's centroid, scored against the float query</li>
 * </ul>
 */
public final class IVFCodec {
  private IVFCodec() {
  }

  /** 64-bit words of a BINARY code for {@code dimensions}. */
  public static int binaryWords(final int dimensions) {
    return (dimensions + 63) >>> 6;
  }

  /** Bytes of a BINARY code for {@code dimensions}: whole words, so a scan reads it with {@code getLong}. */
  public static int binaryBytes(final int dimensions) {
    return binaryWords(dimensions) << 3;
  }

  /** Packs the sign of {@code vector} against {@code thresholds} into {@code words} (zeroed first). */
  public static void binaryCode(final float[] vector, final float[] thresholds, final long[] words) {
    java.util.Arrays.fill(words, 0L);
    for (int d = 0; d < vector.length; d++)
      if (vector[d] > thresholds[d])
        words[d >>> 6] |= 1L << (d & 63);
  }

  /**
   * The one-bit code of {@code vector}: with a {@code projection} (SimHash, see {@link #gaussianProjection}) bit {@code i} is the
   * sign of the dot product with the i-th random direction, so the expected Hamming distance of two codes is {@code dims * angle / pi}
   * whatever the data looks like; without one, the sign against the per-dimension threshold.
   */
  public static void binaryCode(final float[] vector, final float[] projection, final float[] thresholds, final long[] words) {
    if (projection == null) {
      binaryCode(vector, thresholds, words);
      return;
    }
    java.util.Arrays.fill(words, 0L);
    final int dimensions = vector.length;
    for (int bit = 0, offset = 0; bit < dimensions; bit++, offset += dimensions) {
      float dot = 0f;
      for (int d = 0; d < dimensions; d++)
        dot += projection[offset + d] * vector[d];
      if (dot > 0f)
        words[bit >>> 6] |= 1L << (bit & 63);
    }
  }

  /**
   * {@code dimensions} random Gaussian directions of {@code dimensions} components each, flat, from {@code seed}: the SimHash
   * projection of a BINARY scan code. Deterministic, so only the seed is persisted (the header page) and every node rebuilds the
   * same matrix. Sign bits against a per-dimension threshold tell clusters apart but not the members of one tight cluster, where
   * only the few coordinates near the threshold flip; random directions spread the angle over every bit.
   */
  public static float[] gaussianProjection(final int seed, final int dimensions) {
    final java.util.Random random = new java.util.Random(seed);
    final float[] projection = new float[dimensions * dimensions];
    for (int i = 0; i < projection.length; i++)
      projection[i] = (float) random.nextGaussian();
    return projection;
  }

  /** Hamming distance between {@code query} and the code at {@code position} (absolute) of {@code page}. */
  public static int hamming(final long[] query, final ByteBuffer page, int position) {
    int distance = 0;
    for (int w = 0; w < query.length; w++, position += 8)
      distance += Long.bitCount(query[w] ^ page.getLong(position));
    return distance;
  }

  /** The per-vector INT8 scale: {@code max|v| / 127}, or 1 for the zero vector so the code is defined. */
  public static float int8Scale(final float[] vector) {
    float max = 0f;
    for (final float v : vector) {
      final float abs = Math.abs(v);
      if (abs > max)
        max = abs;
    }
    return max == 0f ? 1f : max / 127f;
  }

  /** Quantizes {@code vector} into {@code out} with {@code scale} (see {@link #int8Scale}). */
  public static void int8Code(final float[] vector, final float scale, final byte[] out) {
    final float inverse = 1f / scale;
    for (int d = 0; d < vector.length; d++)
      out[d] = (byte) Math.round(vector[d] * inverse);
  }

  /**
   * The distance between the INT8-coded query and the INT8 code at {@code position} (absolute) of {@code page}, with the
   * entry's scale right after the code, under {@code similarity}: {@code 1 - dot} (vectors normalized at index time) for
   * COSINE, {@code -dot} for DOT_PRODUCT, the squared L2 for EUCLIDEAN.
   *
   * @param querySquaredNorm the exact squared norm of the float query, EUCLIDEAN only
   */
  public static float int8Distance(final IVFSimilarity similarity, final byte[] query, final float queryScale,
      final float querySquaredNorm, final ByteBuffer page, final int position) {
    final int dimensions = query.length;
    int dot = 0;
    int squares = 0;
    for (int d = 0; d < dimensions; d++) {
      final int c = page.get(position + d);
      dot += query[d] * c;
      squares += c * c;
    }
    final float scale = page.getFloat(position + dimensions);
    final float dotProduct = dot * queryScale * scale;
    return switch (similarity) {
      case COSINE -> 1f - dotProduct;
      case DOT_PRODUCT -> -dotProduct;
      case EUCLIDEAN -> querySquaredNorm + squares * scale * scale - 2f * dotProduct;
      case HAMMING -> throw new IllegalStateException("HAMMING has no INT8 code");
    };
  }

  /** Same as {@link #int8Distance(IVFSimilarity, byte[], float, float, ByteBuffer, int)} on an in-memory code. */
  public static float int8Distance(final IVFSimilarity similarity, final byte[] query, final float queryScale,
      final float querySquaredNorm, final byte[] code, final float scale) {
    final int dimensions = query.length;
    int dot = 0;
    int squares = 0;
    for (int d = 0; d < dimensions; d++) {
      final int c = code[d];
      dot += query[d] * c;
      squares += c * c;
    }
    final float dotProduct = dot * queryScale * scale;
    return switch (similarity) {
      case COSINE -> 1f - dotProduct;
      case DOT_PRODUCT -> -dotProduct;
      case EUCLIDEAN -> querySquaredNorm + squares * scale * scale - 2f * dotProduct;
      case HAMMING -> throw new IllegalStateException("HAMMING has no INT8 code");
    };
  }

  // ---------------------------------------------------------------- INT4 residual codes

  /** Bytes of an INT4 residual code for {@code dimensions}: two dimensions per byte, then the scale and the correction float. */
  public static int int4Bytes(final int dimensions) {
    return ((dimensions + 1) >>> 1) + 8;
  }

  /**
   * Codes the residual {@code vector - centroid} (the centroid at {@code centroidOffset} of the flat {@code centroids}) in four
   * bits per dimension into {@code out} at {@code position}: level {@code round(r / scale)} in [-8, 7] stored as an unsigned
   * nibble (low nibble first), {@code scale = max|r| / 7}, then the scale and the EUCLIDEAN correction {@code 2 c.r' + |r'|^2}
   * of the quantized residual {@code r'} (0 for the other similarities, which do not read it).
   */
  public static void int4ResidualCode(final IVFSimilarity similarity, final float[] vector, final float[] centroids,
      final int centroidOffset, final byte[] out, final int position) {
    final int dimensions = vector.length;
    float max = 0f;
    for (int d = 0; d < dimensions; d++) {
      final float abs = Math.abs(vector[d] - centroids[centroidOffset + d]);
      if (abs > max)
        max = abs;
    }
    final float scale = max == 0f ? 1f : max / 7f;
    final float inverse = 1f / scale;
    double correction = 0;
    for (int d = 0; d < dimensions; d += 2) {
      final float c0 = centroids[centroidOffset + d];
      final int l0 = clampNibble(Math.round((vector[d] - c0) * inverse));
      int l1 = 0;
      float c1 = 0f;
      if (d + 1 < dimensions) {
        c1 = centroids[centroidOffset + d + 1];
        l1 = clampNibble(Math.round((vector[d + 1] - c1) * inverse));
      }
      out[position + (d >>> 1)] = (byte) ((l0 + 8) | ((l1 + 8) << 4));
      final float r0 = l0 * scale;
      final float r1 = l1 * scale;
      correction += 2.0 * (c0 * r0 + c1 * r1) + r0 * r0 + r1 * r1;
    }
    final int tail = position + ((dimensions + 1) >>> 1);
    putFloat(out, tail, scale);
    putFloat(out, tail + 4, similarity == IVFSimilarity.EUCLIDEAN ? (float) correction : 0f);
  }

  private static int clampNibble(final int level) {
    return level < -8 ? -8 : Math.min(level, 7);
  }

  private static void putFloat(final byte[] out, final int position, final float value) {
    final int bits = Float.floatToRawIntBits(value);
    out[position] = (byte) (bits >>> 24);
    out[position + 1] = (byte) (bits >>> 16);
    out[position + 2] = (byte) (bits >>> 8);
    out[position + 3] = (byte) bits;
  }

  /**
   * The per-list constant of an INT4 residual distance: {@code q.c} for COSINE and DOT_PRODUCT, {@code |q - c|^2} for EUCLIDEAN,
   * computed once per probed list rather than once per entry.
   */
  public static float int4ListConstant(final IVFSimilarity similarity, final float[] query, final float[] centroids,
      final int centroidOffset) {
    if (similarity == IVFSimilarity.EUCLIDEAN)
      return squaredDistance(query, centroids, centroidOffset);
    float dot = 0f;
    for (int d = 0; d < query.length; d++)
      dot += query[d] * centroids[centroidOffset + d];
    return dot;
  }

  /**
   * The distance between the float {@code query} and the INT4 residual code at {@code position} (absolute) of {@code page}, given
   * the {@link #int4ListConstant} of the entry's list: the query is not quantized (asymmetric distance), only the residual is.
   */
  public static float int4Distance(final IVFSimilarity similarity, final float[] query, final float listConstant,
      final ByteBuffer page, final int position) {
    final int dimensions = query.length;
    final int pairs = dimensions >>> 1;
    float dot = 0f;
    for (int i = 0, d = 0; i < pairs; i++, d += 2) {
      final int b = page.get(position + i) & 0xFF;
      dot += query[d] * ((b & 0x0F) - 8) + query[d + 1] * ((b >>> 4) - 8);
    }
    if ((dimensions & 1) != 0)
      dot += query[dimensions - 1] * ((page.get(position + pairs) & 0x0F) - 8);
    final int tail = position + ((dimensions + 1) >>> 1);
    return int4Combine(similarity, listConstant, dot * page.getFloat(tail), page.getFloat(tail + 4));
  }

  /** Same as {@link #int4Distance(IVFSimilarity, float[], float, ByteBuffer, int)} on an in-memory code at {@code position}. */
  public static float int4Distance(final IVFSimilarity similarity, final float[] query, final float listConstant,
      final byte[] code, final int position) {
    final ByteBuffer wrapped = ByteBuffer.wrap(code);
    return int4Distance(similarity, query, listConstant, wrapped, position);
  }

  private static float int4Combine(final IVFSimilarity similarity, final float listConstant, final float queryDotResidual,
      final float correction) {
    return switch (similarity) {
      case COSINE -> 1f - (listConstant + queryDotResidual);
      case DOT_PRODUCT -> -(listConstant + queryDotResidual);
      // |q - c - r|^2 = |q - c|^2 - 2 q.r + (2 c.r + |r|^2)
      case EUCLIDEAN -> listConstant - 2f * queryDotResidual + correction;
      case HAMMING -> throw new IllegalStateException("HAMMING has no INT4 code");
    };
  }

  /** Hamming distance between {@code query} and an in-memory code. */
  public static int hamming(final long[] query, final long[] code) {
    int distance = 0;
    for (int w = 0; w < query.length; w++)
      distance += Long.bitCount(query[w] ^ code[w]);
    return distance;
  }

  /**
   * The distance a BINARY-only search answers under a similarity that is not HAMMING: the Hamming count over the
   * dimensions, a monotone proxy of the angle in [0, 1]. Not comparable to a reranked distance of the same index.
   */
  public static float hammingProxy(final int hamming, final int dimensions) {
    return hamming / (float) dimensions;
  }

  /** Normalizes {@code vector} in place to unit length; the zero vector is left as it is. */
  public static void normalize(final float[] vector) {
    double sum = 0;
    for (final float v : vector)
      sum += v * v;
    if (sum == 0)
      return;
    final float inverse = (float) (1.0 / Math.sqrt(sum));
    for (int d = 0; d < vector.length; d++)
      vector[d] *= inverse;
  }

  public static float squaredNorm(final float[] vector) {
    double sum = 0;
    for (final float v : vector)
      sum += v * v;
    return (float) sum;
  }

  /** Squared L2 distance between {@code a} and the vector at {@code offset} of the flat array {@code b}. */
  public static float squaredDistance(final float[] a, final float[] b, final int offset) {
    float sum = 0f;
    for (int d = 0; d < a.length; d++) {
      final float diff = a[d] - b[offset + d];
      sum += diff * diff;
    }
    return sum;
  }
}
