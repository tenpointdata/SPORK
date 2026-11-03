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
package com.arcadedb.function.sql.history;

import com.arcadedb.database.Identifiable;
import com.arcadedb.query.sql.executor.CommandContext;

/**
 * {@code history.of(<record>)}: every version of a record of a versioned type, oldest first, the current one last, as maps of its
 * properties plus {@code @rid}, {@code @type}, {@code @version}, {@code @txFrom}, {@code @txTo} and {@code @current}.
 */
public class SQLFunctionHistoryOf extends SQLFunctionHistoryAbstract {
  public static final String NAME = "history.of";

  public SQLFunctionHistoryOf() {
    super(NAME);
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
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    if (params[0] == null)
      return null;
    return toMaps(versioning(context).history(toRid(params[0])));
  }

  @Override
  public String getSyntax() {
    return "history.of(<record>)";
  }
}
