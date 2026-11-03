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
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.CommandContext;

import java.util.Locale;

/**
 * {@code history.edges(<vertex>, <instant> [, <direction> [, <edgeType>]])}: the edges of a vertex as they were at an instant,
 * including the ones superseded or deleted since. Direction is {@code out}, {@code in} or {@code both} (the default).
 */
public class SQLFunctionHistoryEdges extends SQLFunctionHistoryAbstract {
  public static final String NAME = "history.edges";

  public SQLFunctionHistoryEdges() {
    super(NAME);
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 4;
  }

  @Override
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    if (params[0] == null || params[1] == null)
      return null;
    final Vertex.DIRECTION direction = params.length > 2 && params[2] != null ? toDirection(params[2].toString()) : Vertex.DIRECTION.BOTH;
    final String edgeType = params.length > 3 && params[3] != null ? params[3].toString() : null;
    return toMaps(versioning(context).edgesAsOf(toRid(params[0]), toEpochMillis(params[1]), direction, edgeType));
  }

  private Vertex.DIRECTION toDirection(final String value) {
    try {
      return Vertex.DIRECTION.valueOf(value.toUpperCase(Locale.ENGLISH));
    } catch (final IllegalArgumentException e) {
      throw new CommandExecutionException("Function '" + NAME + "' expects direction 'out', 'in' or 'both', found: " + value);
    }
  }

  @Override
  public String getSyntax() {
    return "history.edges(<vertex>, <instant> [, <direction> [, <edgeType>]])";
  }
}
