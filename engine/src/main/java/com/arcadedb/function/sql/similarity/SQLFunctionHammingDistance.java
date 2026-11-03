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

/**
 * {@code hamming.distance(a, b)}: the number of differing bits of two binary hashes (hex strings, integers, bytes). Either
 * side may hold several hashes (a list of strings or bytes, as a record holds the hashes of its images): the answer is
 * then the smallest distance over every pair. Null or an empty list on either side gives null.
 */
public class SQLFunctionHammingDistance extends SQLFunctionSimilarityAbstract {
  public static final String NAME = "hamming.distance";

  public SQLFunctionHammingDistance() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 2, 2);
    final long[][] a = hashes(params[0], "first hash");
    final long[][] b = hashes(params[1], "second hash");
    if (a.length == 0 || b.length == 0)
      return null;
    int best = Integer.MAX_VALUE;
    try {
      for (final long[] x : a)
        for (final long[] y : b)
          best = Math.min(best, BitHash.hamming(x, y));
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(NAME + "(): " + e.getMessage());
    }
    return best;
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 2;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<hash or hashes>, <hash or hashes>)";
  }
}
