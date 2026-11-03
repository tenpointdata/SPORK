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
import com.arcadedb.index.vector.VectorUtils;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.similarity.Stylometry;

/**
 * {@code style.similarity(a, b [, method])}: the stylometric cosine similarity of two texts, or of two style vectors
 * already computed. For texts, {@code method} is {@code 'profile'} (the n-gram profile, the default) or {@code 'recon'}
 * (Recon's 24-d vector, the one its 0.95 threshold applies to). Null on either side gives null.
 */
public class SQLFunctionStyleSimilarity extends SQLFunctionSimilarityAbstract {
  public static final String NAME = "style.similarity";

  public SQLFunctionStyleSimilarity() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 2, 3);
    final String method = params.length > 2 && params[2] != null ? text(params[2], "method") : "profile";
    if (!method.equals("profile") && !method.equals("recon"))
      throw new CommandSQLParsingException(NAME + "(): method must be 'profile' or 'recon', got '" + method + "'");
    if (params[0] == null || params[1] == null)
      return null;
    final float[] a = vector(params[0], method);
    final float[] b = vector(params[1], method);
    try {
      return Stylometry.cosine(a, b);
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(NAME + "(): " + e.getMessage());
    }
  }

  private float[] vector(final Object value, final String method) {
    if (value instanceof String s)
      return method.equals("recon") ?
          Stylometry.reconStyleVectorFloat(s) :
          Stylometry.profile(s, SQLFunctionStyleProfile.DEFAULT_DIMENSIONS);
    try {
      return VectorUtils.toFloatArray(value);
    } catch (final RuntimeException e) {
      throw new CommandSQLParsingException(NAME + "(): arguments must be texts or vectors, got " + typeOf(value));
    }
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<text or vector>, <text or vector> [, 'profile' | 'recon'])";
  }
}
