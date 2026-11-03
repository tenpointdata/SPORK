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
package com.arcadedb.query.sql.executor;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.exception.TimeoutException;
import com.arcadedb.query.sql.parser.Projection;
import com.arcadedb.query.sql.parser.ProjectionItem;

/**
 * Created by luigidellaquila on 12/07/16.
 */
public class ProjectionCalculationStep extends AbstractExecutionStep {
  protected final Projection projection;
  /**
   * The view the rows are projected through when no expression of the projection can hand the row out, so that the N
   * properties the projection reads of a record cost one walk of its header instead of N (SPORK retrieval speed, item
   * S4, on the primitive of issue #9496). Null when an expression could keep the row - a function call, a nested
   * projection, a sub-query, {@code *} - or when {@code arcadedb.queryProjectionPropertyCache} is off. One per step,
   * which is one per execution and one thread: the view serves the rows one after the other.
   */
  private final PropertyCachingResult view;

  public ProjectionCalculationStep(final Projection projection, final CommandContext context) {
    super(context);
    this.projection = projection;
    final Database database = context != null ? context.getDatabase() : null;
    // A PROJECTION OF @this ALONE ANSWERS THE ROW IT IS GIVEN, WHICH WOULD BE THE VIEW, AND A * ITEM (ProjectionItem.all, NO
    // EXPRESSION) READS THE ROW AS A WHOLE: NEITHER IS A NODE THE AST CHECK CAN SEE
    this.view = database != null && usesPropertyCache(database) && !projection.isExpand() && !projection.returnsRecordAsIs()
        && !hasStarItem(projection) && PropertyCachingResult.isSafeOnView(projection) ? new PropertyCachingResult(database) : null;
  }

  /**
   * Whether an item of {@code projection} is {@code *}: its branch of {@code Projection.calculateSingle} reads the whole row
   * ({@code toElement}, {@code toPropertyMap}) and reads a plain row in one pass already, so it gets the scan's row, never the
   * view, whose equivalence on that branch is not established.
   */
  private static boolean hasStarItem(final Projection projection) {
    if (projection.getItems() != null)
      for (final ProjectionItem item : projection.getItems())
        if (item.isAll())
          return true;
    return false;
  }

  /** Whether the rows are projected through the property-caching view, for the tests of the step. */
  boolean readsThroughPropertyCache() {
    return view != null;
  }

  private static boolean usesPropertyCache(final Database database) {
    return database.getConfiguration().getValueAsBoolean(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE);
  }

  @Override
  public ResultSet syncPull(final CommandContext context, final int nRecords) throws TimeoutException {
    checkForPrevious();

    final ResultSet parentRs = prev.syncPull(context, nRecords);
    return new ResultSet() {
      @Override
      public boolean hasNext() {
        return parentRs.hasNext();
      }

      @Override
      public Result next() {
        final Result item = parentRs.next();
        final Object oldCurrent = context.getVariable("current");
        // $current IS THE ROW THE SCAN PRODUCED, NEVER THE VIEW: AN EXPRESSION MAY KEEP WHAT $current ANSWERS
        context.setVariable("current", item);
        final Result result = calculateProjections(context, view != null ? view.of(item) : item);
        context.setVariable("current", oldCurrent);
        return result;
      }

      @Override
      public void close() {
        parentRs.close();
      }
    };
  }

  private Result calculateProjections(final CommandContext context, final Result next) {
    final long begin = context.isProfiling() ? System.nanoTime() : 0;
    try {
      return this.projection.calculateSingle(context, next);
    } finally {
      if( context.isProfiling() ) {
        cost += System.nanoTime() - begin;
      }
    }
  }

  @Override
  public String prettyPrint(final int depth, final int indent) {
    final String spaces = ExecutionStepInternal.getIndent(depth, indent);
    String result = spaces + "+ CALCULATE PROJECTIONS";
    if ( context.isProfiling() )
      result += " (" + getCostFormatted() + ")";

    result += "\n" + spaces + "  " + projection.toString();
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(final CommandContext context) {
    return new ProjectionCalculationStep(projection.copy(), context);
  }
}
