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
import com.arcadedb.similarity.Stylometry;

/**
 * {@code style.profile(text [, dimensions])}: the character n-gram authorship profile of a text
 * ({@link Stylometry#profile}), unit length, {@value #DEFAULT_DIMENSIONS} dimensions by default. Store it in an
 * {@code LSM_VECTOR} index with cosine similarity for corpus-wide same-author search. Null text gives null.
 */
public class SQLFunctionStyleProfile extends SQLFunctionSimilarityAbstract {
  public static final String NAME               = "style.profile";
  public static final int    DEFAULT_DIMENSIONS = 256;

  public SQLFunctionStyleProfile() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 1, 2);
    final int dimensions = params.length > 1 && params[1] != null ?
        intArg(params[1], "dimensions", Stylometry.MIN_PROFILE_DIMENSIONS, Stylometry.MAX_PROFILE_DIMENSIONS) :
        DEFAULT_DIMENSIONS;
    if (Integer.bitCount(dimensions) != 1)
      throw new CommandSQLParsingException(NAME + "(): dimensions must be a power of 2, got " + dimensions);
    return params[0] == null ? null : Stylometry.profile(text(params[0], "text"), dimensions);
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
    return NAME + "(<text> [, <dimensions>])";
  }
}
