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

/**
 * {@code hamming.probeKeys(hash, bands, maxDistance)}: the band keys whose lookup finds every hash within
 * {@code maxDistance} of {@code hash} ({@link HammingBands#probeKeys}); refused above {@value #MAX_KEYS} keys. Pair it
 * with {@code hamming.distance} to verify the candidates. Null gives null.
 */
public class SQLFunctionHammingProbeKeys extends SQLFunctionSimilarityAbstract {
  public static final String NAME     = "hamming.probeKeys";
  public static final int    MAX_KEYS = 100_000;

  public SQLFunctionHammingProbeKeys() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 3, 3);
    final int bands = intArg(params[1], "bands", 1, 4096);
    final int maxDistance = intArg(params[2], "maxDistance", 0, 4096);
    if (params[0] == null)
      return null;
    try {
      return toList(HammingBands.probeKeys(hash(params[0], "hash"), bands, maxDistance, MAX_KEYS));
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(NAME + "(): " + e.getMessage());
    }
  }

  @Override
  public int getMinArgs() {
    return 3;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<hash>, <bands>, <maxDistance>)";
  }
}
