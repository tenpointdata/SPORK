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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The Vector API {@link IVFInt8Dot.Ops}: 8 code bytes per load, widened to shorts and multiplied by the widened query; two such
 * products are added in shorts (each is at most 127 x 127, so two fit) before they are widened to ints and accumulated, which halves
 * the widenings. Fixed 128-bit shapes, native on NEON and SSE alike. Loaded only through {@link IVFInt8Dot#select}, which falls back
 * to the scalar loop when the {@code jdk.incubator.vector} module is absent.
 */
final class IVFInt8DotSimd implements IVFInt8Dot.Ops {
  private static final VectorSpecies<Byte>    BYTES  = ByteVector.SPECIES_64;
  private static final VectorSpecies<Short>   SHORTS = ShortVector.SPECIES_128;
  private static final VectorSpecies<Integer> INTS   = IntVector.SPECIES_128;

  @Override
  public int dot(final short[] query, final byte[] code, final int at, final int dimensions) {
    IntVector sum = IntVector.zero(INTS);
    int d = 0;
    for (; d + 16 <= dimensions; d += 16) {
      final ShortVector first = product(query, code, at, d);
      final ShortVector pair = first.add(product(query, code, at, d + 8));
      sum = sum.add(pair.convertShape(VectorOperators.S2I, INTS, 0)).add(pair.convertShape(VectorOperators.S2I, INTS, 1));
    }
    if (d + 8 <= dimensions) {
      final ShortVector single = product(query, code, at, d);
      sum = sum.add(single.convertShape(VectorOperators.S2I, INTS, 0)).add(single.convertShape(VectorOperators.S2I, INTS, 1));
      d += 8;
    }
    int dot = sum.reduceLanes(VectorOperators.ADD);
    for (; d < dimensions; d++)
      dot += query[d] * code[at + d];
    return dot;
  }

  private static ShortVector product(final short[] query, final byte[] code, final int at, final int d) {
    final ShortVector widened = (ShortVector) ByteVector.fromArray(BYTES, code, at + d).convertShape(VectorOperators.B2S, SHORTS, 0);
    return widened.mul(ShortVector.fromArray(SHORTS, query, d));
  }
}
