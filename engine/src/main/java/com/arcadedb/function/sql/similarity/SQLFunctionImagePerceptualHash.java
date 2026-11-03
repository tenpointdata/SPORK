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
import com.arcadedb.similarity.PerceptualHash;
import com.arcadedb.similarity.PerceptualHash.Method;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code image.perceptualHash(image [, method [, dihedral]])}: the perceptual hash of an image ({@link PerceptualHash}) as a hex
 * string: {@code 'phash'} (the default), {@code 'dhash'}, {@code 'ahash'} (16 hex digits each) or {@code 'pdq'} (64). The
 * image is bytes or a base64 string. With {@code dihedral = true} the answer is the list of the 8 hashes of the image's
 * rotations and mirrors, the first being the plain hash: index those, or probe with all of them, to match flipped
 * re-posts. Null image gives null; bytes that are not an image are refused.
 */
public class SQLFunctionImagePerceptualHash extends SQLFunctionSimilarityAbstract {
  public static final String NAME = "image.perceptualHash";

  public SQLFunctionImagePerceptualHash() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 1, 3);
    final Method method;
    try {
      method = params.length > 1 && params[1] != null ? Method.parse(text(params[1], "method")) : Method.PHASH;
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(NAME + "(): " + e.getMessage());
    }
    final boolean dihedral = params.length > 2 && Boolean.TRUE.equals(params[2]);
    if (params[0] == null)
      return null;
    final byte[] bytes = imageBytes(params[0]);
    try {
      if (!dihedral)
        return BitHash.toHex(PerceptualHash.hash(bytes, method));
      final List<String> hashes = new ArrayList<>(8);
      for (final long[] h : PerceptualHash.dihedral(bytes, method))
        hashes.add(BitHash.toHex(h));
      return hashes;
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
    return 3;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<image bytes or base64> [, 'phash' | 'dhash' | 'ahash' | 'pdq' [, <dihedral>]])";
  }
}
