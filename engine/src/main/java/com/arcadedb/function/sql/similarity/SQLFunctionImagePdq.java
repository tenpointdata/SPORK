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
package com.arcadedb.function.sql.similarity;

import com.arcadedb.database.Identifiable;
import com.arcadedb.exception.CommandSQLParsingException;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.similarity.PdqHasher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code image.pdq(image [, dihedral])}: Meta's PDQ hash of an image, bit for bit the reference's ({@link PdqHasher},
 * docs/spork/workstreams/ws12-pdq.md), as a map: {@code hash} (64 hex digits, as the reference and hash lists print it) and
 * {@code quality} (0 to 100; below {@value PdqHasher#MIN_QUALITY} the hash must not decide a match). With
 * {@code dihedral = true} the map also holds {@code hashes}, the 8 hashes of the image's rotations and mirrors in the
 * reference's order (the first is {@code hash}). Compare with {@code hamming.distance} (near-duplicate at 31 or less) and
 * index with {@code hamming.bandKeys(hash, 8)} ({@code pdqBands}). The image is bytes or a base64 string. Null gives null;
 * bytes that are not an image are refused.
 */
public class SQLFunctionImagePdq extends SQLFunctionSimilarityAbstract {
  public static final String NAME = "image.pdq";

  public SQLFunctionImagePdq() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 1, 2);
    if (params.length > 1 && params[1] != null && !(params[1] instanceof Boolean))
      throw new CommandSQLParsingException(NAME + "(): dihedral must be a boolean, got " + typeOf(params[1]));
    final boolean dihedral = params.length > 1 && Boolean.TRUE.equals(params[1]);
    if (params[0] == null)
      return null;
    final byte[] bytes = imageBytes(params[0]);
    try {
      final Map<String, Object> result = new LinkedHashMap<>(4);
      if (!dihedral) {
        final PdqHasher.Hash hash = PdqHasher.hashImage(bytes);
        result.put("hash", hash.hex());
        result.put("quality", hash.quality());
        return result;
      }
      final PdqHasher.Dihedral all = PdqHasher.dihedralImage(bytes);
      final List<String> hashes = new ArrayList<>(8);
      for (final long[] h : all.hashes())
        hashes.add(BitHash.toHex(h));
      result.put("hash", hashes.getFirst());
      result.put("quality", all.quality());
      result.put("hashes", hashes);
      return result;
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(NAME + "(): " + e.getMessage());
    }
  }

  @Override
  public int getMinArgs() {
    return 1;
  }

  @Override
  public int getMaxArgs() {
    return 2;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<image bytes or base64> [, <dihedral>])";
  }
}
