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
package com.arcadedb.function.sql.vector;

import com.arcadedb.database.Database;
import com.arcadedb.query.sql.antlr.SQLAntlrParser;
import com.arcadedb.query.sql.executor.BasicCommandContext;
import com.arcadedb.query.sql.executor.InternalExecutionPlan;
import com.arcadedb.query.sql.executor.RidSetSubquery;
import com.arcadedb.query.sql.parser.SelectStatement;
import com.arcadedb.utility.RidBitmapSet;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Materializes a sub-query the way a {@code filter} option does, and checks which path it takes.
 */
final class RidSetSubqueryTestSupport {
  private RidSetSubqueryTestSupport() {
  }

  static RidBitmapSet materialize(final Database database, final String subquery, final Map<String, Object> params,
      final boolean expectIndexOnly) {
    final SelectStatement statement = parse(database, subquery);
    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    context.setInputParameters(params);
    final InternalExecutionPlan plan = statement.createExecutionPlanNoCache(context);
    assertThat(RidSetSubquery.isIndexOnly(plan)).as(subquery).isEqualTo(expectIndexOnly);
    return RidSetSubquery.materialize(statement, plan, context);
  }

  /** Each of {@code subqueries} in turn, as the {@code filter} options of one statement evaluate them. */
  static RidBitmapSet[] materializeInOneStatement(final Database database, final String... subqueries) {
    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    final RidBitmapSet[] sets = new RidBitmapSet[subqueries.length];
    for (int i = 0; i < subqueries.length; i++)
      sets[i] = RidSetSubquery.materialize(parse(database, subqueries[i]), context);
    return sets;
  }

  /** {@code subquery} twice in one statement, with {@code between} run between the two evaluations. */
  static RidBitmapSet[] materializeAround(final Database database, final String subquery, final Runnable between) {
    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    final SelectStatement statement = parse(database, subquery);
    final RidBitmapSet first = RidSetSubquery.materialize(statement, context);
    between.run();
    return new RidBitmapSet[] { first, RidSetSubquery.materialize(statement, context) };
  }

  private static SelectStatement parse(final Database database, final String subquery) {
    final SelectStatement statement = (SelectStatement) new SQLAntlrParser(database).parse(subquery);
    statement.setRidSetConsumer(SQLFunctionVectorNeighbors.NAME);
    return statement;
  }
}
