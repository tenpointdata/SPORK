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

import com.arcadedb.log.LogManager;
import com.arcadedb.utility.VectorApiSupport;

import java.util.logging.Level;

/**
 * The integer dot product of an INT8 scan: a query widened to shorts once per search against the INT8 code of every scanned
 * entry, read in place from the posting page's array. It is the whole per-entry cost of a low-dimensional INT8 scan (a 24-d scan of
 * 40K entries is a millisecond of it), so it runs on the Vector API when the JVM has it ({@code --add-modules jdk.incubator.vector},
 * which the server scripts and the test runs pass), measured at 6.4 ns per 24-d entry against 17 ns for the scalar loop, and on the
 * scalar loop otherwise. Chosen once, as the graph OLAP and time series SIMD ops are, after a smoke test against the scalar answer.
 */
final class IVFInt8Dot {
  /** One implementation of the dot product; both answer exactly the same integer. */
  interface Ops {
    /**
     * @param query      the query code widened to shorts, {@link #widen padded} to a multiple of 8 with zeros
     * @param code       the array holding the entry's code
     * @param at         the index of the code's first byte in {@code code}
     * @param dimensions the code bytes, never read past
     */
    int dot(short[] query, byte[] code, int at, int dimensions);
  }

  static final class Scalar implements Ops {
    @Override
    public int dot(final short[] query, final byte[] code, final int at, final int dimensions) {
      int dot = 0;
      for (int d = 0; d < dimensions; d++)
        dot += query[d] * code[at + d];
      return dot;
    }
  }

  private static final Ops     OPS;
  private static final boolean SIMD;

  static {
    OPS = select(VectorApiSupport.isVectorApiCompiled());
    SIMD = !(OPS instanceof Scalar);
  }

  private IVFInt8Dot() {
  }

  static Ops select(final boolean vectorApiCompiled) {
    if (!vectorApiCompiled)
      return new Scalar();
    try {
      final Ops ops = new IVFInt8DotSimd();
      // Smoke test: every length shape (16-byte pairs, an 8-byte chunk, a scalar tail) against the scalar answer, at an offset
      final Ops scalar = new Scalar();
      final byte[] code = new byte[64];
      final byte[] query = new byte[32];
      for (int i = 0; i < code.length; i++)
        code[i] = (byte) (i * 37 % 255 - 127);
      for (int i = 0; i < query.length; i++)
        query[i] = (byte) (127 - i * 11 % 255);
      final short[] wide = widen(query);
      for (int dimensions = 1; dimensions <= query.length; dimensions++)
        if (ops.dot(wide, code, 5, dimensions) != scalar.dot(wide, code, 5, dimensions))
          throw new IllegalStateException("SIMD INT8 dot smoke test failed at " + dimensions + " dimensions");
      return ops;
    } catch (final Exception | LinkageError e) {
      LogManager.instance().log(IVFInt8Dot.class, Level.INFO,
          "IVF INT8 scan without SIMD (%s): add '--add-modules jdk.incubator.vector' to the JVM flags for a faster scan", e.getMessage());
      return new Scalar();
    }
  }

  /** Whether the Vector API implementation is in use. */
  static boolean isSimd() {
    return SIMD;
  }

  /** {@code query} as shorts, zero-padded to a multiple of 8 lanes, the shape {@link #dot} reads. */
  static short[] widen(final byte[] query) {
    final short[] wide = new short[(query.length + 7) & ~7];
    for (int d = 0; d < query.length; d++)
      wide[d] = query[d];
    return wide;
  }

  static int dot(final short[] query, final byte[] code, final int at, final int dimensions) {
    return OPS.dot(query, code, at, dimensions);
  }
}
