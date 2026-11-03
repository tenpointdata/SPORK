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
import com.arcadedb.similarity.PerceptualHash;

/**
 * {@code image.quality(image)}: PDQ's 0 to 100 quality score of an image ({@link PerceptualHash#quality}). Featureless
 * images score low and their hashes collide with each other: store the score next to the hash and never link on a hash
 * whose image scored below 50. Null image gives null.
 */
public class SQLFunctionImageQuality extends SQLFunctionSimilarityAbstract {
  public static final String NAME = "image.quality";

  public SQLFunctionImageQuality() {
    super(NAME);
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    checkArgs(params, 1, 1);
    if (params[0] == null)
      return null;
    try {
      return PerceptualHash.quality(imageBytes(params[0]));
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
    return 1;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<image bytes or base64>)";
  }
}
