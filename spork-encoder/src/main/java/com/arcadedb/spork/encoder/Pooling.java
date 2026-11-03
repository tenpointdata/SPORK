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

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Turns a model output into one row per input and keeps its first {@code dims} components (Matryoshka truncation, a
 * no-op when {@code dims} is the native width). {@link #NONE} is for graphs that pool themselves: the S-TEXT export
 * slices the CLS token inside the graph, so only {@code batch x hidden} floats ever leave ONNX Runtime instead of
 * {@code batch x tokens x hidden}.
 */
public enum Pooling {
  /** Output {@code [batch, hidden]}, already one row per input. */
  NONE {
    @Override
    void pool(final FloatBuffer out, final int batch, final int tokens, final int hidden, final long[] mask, final int dims, final float[] rows) {
      for (int b = 0; b < batch; b++)
        out.get(b * hidden, rows, b * dims, dims);
    }
  },
  /** Output {@code [batch, tokens, hidden]}: token 0. */
  CLS {
    @Override
    void pool(final FloatBuffer out, final int batch, final int tokens, final int hidden, final long[] mask, final int dims, final float[] rows) {
      for (int b = 0; b < batch; b++)
        out.get(b * tokens * hidden, rows, b * dims, dims);
    }
  },
  /** Output {@code [batch, tokens, hidden]}: the mean of the tokens the attention mask keeps. */
  MEAN {
    @Override
    void pool(final FloatBuffer out, final int batch, final int tokens, final int hidden, final long[] mask, final int dims, final float[] rows) {
      for (int b = 0; b < batch; b++) {
        final int row = b * dims;
        int kept = 0;
        for (int t = 0; t < tokens; t++) {
          if (mask[b * tokens + t] == 0)
            continue;
          ++kept;
          final int base = (b * tokens + t) * hidden;
          for (int h = 0; h < dims; h++)
            rows[row + h] += out.get(base + h);
        }
        if (kept > 0) {
          final float inv = 1f / kept;
          for (int h = 0; h < dims; h++)
            rows[row + h] *= inv;
        }
      }
    }
  };

  /**
   * @param out    the model output, row-major
   * @param mask   the attention mask, {@code batch x tokens}; only {@link #MEAN} reads it
   * @param dims   the components to keep, at most {@code hidden}
   * @param rows   receives {@code batch x dims} floats; must be zeroed for {@link #MEAN}
   */
  abstract void pool(FloatBuffer out, int batch, int tokens, int hidden, long[] mask, int dims, float[] rows);

  /** Scales every row of {@code rows} to unit L2 norm in place; an all-zero row stays zero. */
  static void l2NormalizeRows(final float[] rows, final int count, final int dims) {
    for (int r = 0; r < count; r++) {
      final int base = r * dims;
      double sum = 0;
      for (int i = 0; i < dims; i++)
        sum += rows[base + i] * rows[base + i];
      if (sum == 0)
        continue;
      final float inv = (float) (1.0 / Math.sqrt(sum));
      for (int i = 0; i < dims; i++)
        rows[base + i] *= inv;
    }
  }

  static Pooling parse(final String name) {
    return valueOf(name.toUpperCase(Locale.ROOT));
  }
}
