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
import com.arcadedb.versioning.RecordVersion;

/**
 * {@code history.asOf(<record>, <instant>)}: the version of a record valid at an instant (a date or epoch milliseconds, taken as
 * the end of that millisecond), or null when the record did not exist then.
 */
public class SQLFunctionHistoryAsOf extends SQLFunctionHistoryAbstract {
  public static final String NAME = "history.asOf";

  public SQLFunctionHistoryAsOf() {
    super(NAME);
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
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    if (params[0] == null || params[1] == null)
      return null;
    final RecordVersion version = versioning(context).asOf(toRid(params[0]), toEpochMillis(params[1]));
    return version == null ? null : version.toMap();
  }

  @Override
  public String getSyntax() {
    return "history.asOf(<record>, <instant>)";
  }
}
