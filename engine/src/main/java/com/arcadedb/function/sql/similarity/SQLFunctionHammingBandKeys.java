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
import com.arcadedb.similarity.HammingBands;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code hamming.bandKeys(hash, bands)}: the multi-index hashing keys of a hash ({@link HammingBands#bandKeys}), one LONG
 * per band. Store them in a {@code LIST OF LONG} property indexed {@code BY ITEM} and {@code similarity.hash.neighbors}
 * finds every hash within a Hamming distance through the index. Several hashes (a list of strings or bytes) give the
 * union of their keys, so one property indexes all the images of a record. Null gives null.
 */
public class SQLFunctionHammingBandKeys extends SQLFunctionSimilarityAbstract {
  public static final String NAME = "hamming.bandKeys";

  public SQLFunctionHammingBandKeys() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 2, 2);
    final int bands = intArg(params[1], "bands", 1, 4096);
    if (params[0] == null)
      return null;
    final Set<Long> keys = new LinkedHashSet<>();
    try {
      for (final long[] h : hashes(params[0], "hash"))
        for (final long k : HammingBands.bandKeys(h, bands))
          keys.add(k);
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(NAME + "(): " + e.getMessage());
    }
    return new ArrayList<>(keys);
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
    return NAME + "(<hash or hashes>, <bands>)";
  }
}
