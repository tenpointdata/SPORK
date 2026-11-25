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

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.index.IndexException;
import com.arcadedb.function.sql.vector.SQLFunctionVectorAbstract;
import com.arcadedb.function.sql.vector.SQLFunctionVectorNeighbors;
import com.arcadedb.function.sql.vector.SQLFunctionVectorSparseNeighbors;
import com.arcadedb.query.sql.antlr.SubqueryExpression;
import com.arcadedb.query.sql.parser.BaseExpression;
import com.arcadedb.query.sql.parser.Expression;
import com.arcadedb.query.sql.parser.Json;
import com.arcadedb.query.sql.parser.JsonItem;
import com.arcadedb.query.sql.parser.LocalResultSet;
import com.arcadedb.query.sql.parser.Projection;
import com.arcadedb.query.sql.parser.ProjectionItem;
import com.arcadedb.query.sql.parser.SelectStatement;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.utility.RidBitmap;
import com.arcadedb.utility.RidBitmapSet;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@code filter} option of a k-NN function written as a sub-query, {@code {filter: (SELECT @rid FROM T WHERE ...)}},
 * materialized straight into the compact allow-list the vector indexes probe (a {@link RidBitmapSet}) instead of a list of
 * result rows that the function then copied into a {@code HashSet<RID>} (docs/spork/workstreams/vector-filter-allowlist.md).
 * <p>
 * Three costs go away:
 * <ul>
 *   <li>the rows: each matching record used to be a result row with its own property map, held in a list, then copied into a
 *   hash set as a boxed entry. The bitmap holds about two bytes per member;</li>
 *   <li>the record loads, when the sub-query is answered by one index alone ({@link #indexOnly}): the plan is an index fetch,
 *   the entry-to-record step, and at most a DISTINCT, a type filter and a projection of {@code @rid}. The RIDs then come off
 *   the index cursor and no record is read. Anything else (a residual WHERE, a LIMIT, an ORDER BY, several indexes) runs the
 *   plan as it is and keeps only the identity of each row;</li>
 *   <li>the repeats: the sub-query is run again by every call that evaluates the option, so by every row of a statement that
 *   searches once per row, and by each of two searches over the same filter. A sub-query whose answer cannot differ between
 *   those calls (see {@link #reusable}) is materialized once per statement, see {@link #CACHE_VARIABLE}.</li>
 * </ul>
 * The marking is decided on the syntax alone: a {@code filter} entry of a map literal passed to {@code vector.neighbors} or
 * {@code vector.sparseNeighbors}, whose value is exactly a parenthesised SELECT (no modifier such as {@code .l}). The value of
 * such a sub-query is never visible to anything but that option, so the set can replace the list.
 */
public final class RidSetSubquery {
  /**
   * The context variable holding the sub-queries the statement already materialized, keyed by their text, each with the
   * database's modification count it was read at. Set on the context the option is evaluated in.
   */
  static final         String    CACHE_VARIABLE = "$$$RID_SET_SUBQUERIES$$$";
  private static final String    FILTER_OPTION  = "filter";
  // JVM-WIDE: HOW MANY SUB-QUERIES WERE ANSWERED FROM THEIR INDEX ALONE, FROM THEIR ROWS, AND BY ONE MET EARLIER IN THE STATEMENT
  private static final LongAdder INDEX_ONLY     = new LongAdder();
  private static final LongAdder FROM_ROWS      = new LongAdder();
  private static final LongAdder REUSED         = new LongAdder();

  /** A materialized sub-query and the modification count of the database it was read at. */
  private record Materialized(RidBitmapSet set, long modificationCount) {
  }

  private RidSetSubquery() {
  }

  /**
   * Whether {@code functionName} takes a {@code filter} option of RIDs that it only probes for membership.
   */
  public static boolean consumesRidFilter(final String functionName) {
    if (functionName == null)
      return false;
    final String name = functionName.toLowerCase(Locale.ENGLISH);
    return name.equals(SQLFunctionVectorNeighbors.NAME.toLowerCase(Locale.ENGLISH)) || name.equals(
        SQLFunctionVectorSparseNeighbors.NAME.toLowerCase(Locale.ENGLISH));
  }

  /**
   * Marks the {@code filter} sub-query of a call to {@code functionName}, if it has one, so it is materialized as a RID set.
   * Idempotent, and decided by the syntax alone, so the same answer is written by whichever thread marks a shared tree first.
   */
  public static void markFilterSubqueries(final String functionName, final List<Expression> params) {
    if (params == null || !consumesRidFilter(functionName))
      return;
    for (final Expression param : params) {
      final Json json = mapLiteral(param);
      if (json == null)
        continue;
      for (final JsonItem item : json.items) {
        if (!FILTER_OPTION.equals(item.getLeftValue()) || item.right == null)
          continue;
        final SelectStatement statement = filterStatement(item.right);
        if (statement != null)
          statement.setRidSetConsumer(functionName);
      }
    }
  }

  /** The map literal an argument is, possibly wrapped in a bare expression node, else null. */
  private static Json mapLiteral(final Expression param) {
    if (param == null)
      return null;
    if (param.json != null)
      return param.json;
    if (param.mathExpression != null && param.mathExpression.getClass() == BaseExpression.class) {
      final BaseExpression base = (BaseExpression) param.mathExpression;
      if (base.expression != null && base.modifier == null && base.identifier == null)
        return mapLiteral(base.expression);
    }
    return null;
  }

  /**
   * The SELECT an option's value is made of, when the value is exactly a parenthesised SELECT, else null.
   */
  private static SelectStatement filterStatement(final Expression value) {
    if (value.json != null || value.rid != null || value.whereCondition != null || value.arrayConcatExpression != null)
      return null;
    if (!(value.mathExpression instanceof SubqueryExpression subquery) || subquery.modifier != null)
      return null;
    final SelectStatement statement = subquery.getStatement();
    return statement != null && statement.getClass() == SelectStatement.class ? statement : null;
  }

  /**
   * The allow-list a marked sub-query names, evaluated as a statement nested in {@code context} would be, or null when it cannot
   * be (an index it reads was dropped or rebuilt meanwhile): the caller then runs the sub-query as any other, which plans again.
   *
   * @param context the context the option is evaluated in: where a reusable answer is kept for the rest of the statement
   */
  public static RidBitmapSet materialize(final SelectStatement statement, final CommandContext context) {
    final DatabaseInternal database = (DatabaseInternal) context.getDatabase();
    final String cacheKey = reusable(statement, database) ? statement.toString() : null;
    @SuppressWarnings("unchecked")
    Map<String, Materialized> cache = cacheKey == null ? null : (Map<String, Materialized>) context.getVariable(CACHE_VARIABLE);
    final long modificationCount = cacheKey == null ? -1 : database.getModificationCount();
    if (cache != null) {
      final Materialized known = cache.get(cacheKey);
      if (known != null && known.modificationCount() == modificationCount) {
        REUSED.increment();
        return known.set();
      }
    }

    // WHAT SelectStatement.execute(db, params, parentContext) SETS UP FOR A NESTED STATEMENT
    final BasicCommandContext subContext = new BasicCommandContext();
    subContext.setParentWithoutOverridingChild(context);
    subContext.openGraphViewPins();
    subContext.setDatabase(database);
    subContext.setInputParameters(context.getInputParameters());

    final RidBitmapSet result;
    try {
      result = materialize(statement, statement.createExecutionPlan(subContext), subContext);
    } catch (final IndexException e) {
      return null;
    }

    if (cacheKey != null) {
      if (cache == null) {
        cache = new HashMap<>(4);
        context.setVariable(CACHE_VARIABLE, cache);
      }
      cache.put(cacheKey, new Materialized(result, modificationCount));
    }
    return result;
  }

  /**
   * Whether every evaluation of the sub-query within one statement answers the same set while the database is unchanged: it
   * reads no variable (no {@code $parent}, {@code $current}, LET or script variable: a {@code $} anywhere in its text, a
   * literal included, rules it out), binds no positional parameter (which binds by ordinal), and calls nothing that answers
   * differently from one call to the next ({@link CorrelatedSubQueryCache#isCacheable}). The database must track its
   * modifications, which is what the answer is re-validated against.
   */
  static boolean reusable(final SelectStatement statement, final DatabaseInternal database) {
    if (database.getModificationCount() < 0 || statement.refersToParent() || !CorrelatedSubQueryCache.isCacheable(statement))
      return false;
    final String text = statement.toString();
    return text.indexOf('$') < 0 && text.indexOf('?') < 0;
  }

  /**
   * Runs {@code plan}, the plan of {@code statement}, into the allow-list its rows name, and closes it.
   */
  public static RidBitmapSet materialize(final SelectStatement statement, final InternalExecutionPlan plan,
      final CommandContext context) {
    try {
      final IndexOnlyPlan indexOnly = indexOnly(plan);
      if (indexOnly == null) {
        FROM_ROWS.increment();
        return fromRows(plan, statement.getRidSetConsumer(), context);
      }
      INDEX_ONLY.increment();
      return indexOnly.run(context);
    } finally {
      plan.close();
    }
  }


  /**
   * The general path: every row of the plan, reduced to the RID it names exactly as the option reads a list element.
   */
  private static RidBitmapSet fromRows(final InternalExecutionPlan plan, final String functionName, final CommandContext context) {
    final RidBitmap bitmap = new RidBitmap();
    final DatabaseInternal database = (DatabaseInternal) context.getDatabase();
    // THE PLAN IS CLOSED BY THE CALLER
    final LocalResultSet rows = new LocalResultSet(plan);
    while (rows.hasNext()) {
      final Result row = rows.next();
      if (row == null)
        continue;
      final RID rid = SQLFunctionVectorAbstract.filterItemRid(row, functionName, database);
      if (rid.getBucketId() >= 0 && rid.getPosition() >= 0)
        bitmap.add(rid.getBucketId(), rid.getPosition());
    }
    return new RidBitmapSet(bitmap);
  }

  /** JVM-wide count of the sub-queries answered from their index alone, without reading a record. */
  public static long indexOnlyMaterializations() {
    return INDEX_ONLY.sum();
  }

  /** JVM-wide count of the sub-queries answered from their rows (records read). */
  public static long rowMaterializations() {
    return FROM_ROWS.sum();
  }

  /** JVM-wide count of the sub-queries answered by the set of the same one met earlier in the statement. */
  public static long reusedMaterializations() {
    return REUSED.sum();
  }

  /** Whether {@link #materialize} answers {@code plan} from its index alone, without reading a record. */
  public static boolean isIndexOnly(final InternalExecutionPlan plan) {
    return indexOnly(plan) != null;
  }

  /**
   * The plan of a sub-query answered by one index without reading a record, or null. Accepted: an index fetch, the step that
   * turns entries into records (only its bucket filter is kept), then any of a DISTINCT (the set is distinct already), a type
   * filter (answered from the bucket, which belongs to one type) and a projection of {@code @rid} alone. A WHERE the index does
   * not fully answer leaves a filter step behind, and that, like any other step, sends the sub-query down the general path.
   */
  static IndexOnlyPlan indexOnly(final InternalExecutionPlan plan) {
    if (!(plan instanceof SelectExecutionPlan select))
      return null;
    final List<ExecutionStep> steps = select.getSteps();
    if (steps.size() < 2 || steps.get(0).getClass() != FetchFromIndexStep.class
        || steps.get(1).getClass() != GetValueFromIndexEntryStep.class)
      return null;
    final FetchFromIndexStep fetch = (FetchFromIndexStep) steps.get(0);
    final GetValueFromIndexEntryStep values = (GetValueFromIndexEntryStep) steps.get(1);
    String typeName = null;
    for (int i = 2; i < steps.size(); i++) {
      final ExecutionStep step = steps.get(i);
      if (step.getClass() == DistinctExecutionStep.class)
        continue;
      if (step.getClass() == FilterByTypeStep.class && typeName == null) {
        typeName = ((FilterByTypeStep) step).getTypeName();
        continue;
      }
      if (step.getClass() == ProjectionCalculationStep.class && i == steps.size() - 1
          && projectsRidAlone(((ProjectionCalculationStep) step).projection))
        continue;
      return null;
    }
    return new IndexOnlyPlan(fetch, values.getFilterBucketIds(), typeName);
  }

  private static boolean projectsRidAlone(final Projection projection) {
    if (projection == null || projection.distinct || projection.getItems() == null || projection.getItems().size() != 1)
      return false;
    final ProjectionItem item = projection.getItems().getFirst();
    return !item.all && !item.exclude && item.nestedProjection == null && item.expression != null && "@rid".equalsIgnoreCase(
        item.expression.toString());
  }

  /**
   * An index-only sub-query: the RIDs off the cursor of {@code fetch}, kept when their bucket passes the plan's bucket filter
   * and belongs to {@code typeName} (or a sub-type). The read permission a record load would check is checked once per bucket.
   */
  static final class IndexOnlyPlan {
    private final FetchFromIndexStep fetch;
    private final int[]              filterBucketIds;
    private final String             typeName;

    IndexOnlyPlan(final FetchFromIndexStep fetch, final List<Integer> filterBucketIds, final String typeName) {
      this.fetch = fetch;
      this.typeName = typeName;
      if (filterBucketIds == null)
        this.filterBucketIds = null;
      else {
        this.filterBucketIds = new int[filterBucketIds.size()];
        for (int i = 0; i < this.filterBucketIds.length; i++)
          this.filterBucketIds[i] = filterBucketIds.get(i);
      }
    }

    RidBitmapSet run(final CommandContext context) {
      final DatabaseInternal database = (DatabaseInternal) context.getDatabase();
      final CommandContext stepContext = fetch.getContext() != null ? fetch.getContext() : context;
      final WorkGuard guard = WorkGuard.forCommandDeadline(stepContext);
      final RidBitmap bitmap = new RidBitmap();
      // The last bucket decided, and whether it passed: index entries come in key order, so runs of one bucket are common and a
      // bucket is decided by an array scan at most once per run
      int lastBucket = Integer.MIN_VALUE;
      boolean lastAccepted = false;
      int count = 0;
      Identifiable next;
      while ((next = fetch.nextIdentifiable(stepContext)) != null) {
        guard.checkPeriodically(++count);
        final RID rid = next.getIdentity();
        final int bucketId = rid.getBucketId();
        final long position = rid.getPosition();
        if (bucketId < 0 || position < 0)
          continue;
        if (bucketId != lastBucket) {
          lastBucket = bucketId;
          lastAccepted = acceptsBucket(database, bucketId);
          if (lastAccepted)
            // WHAT LocalBucket.getRecord() CHECKS ON THE LOAD THE GENERAL PATH MAKES: ONCE PER RUN OF ONE BUCKET
            database.checkPermissionsOnFile(bucketId, SecurityDatabaseUser.ACCESS.READ_RECORD);
        }
        if (lastAccepted)
          bitmap.add(bucketId, position);
      }
      return new RidBitmapSet(bitmap);
    }

    private boolean acceptsBucket(final DatabaseInternal database, final int bucketId) {
      if (filterBucketIds != null) {
        boolean found = false;
        for (final int filterBucketId : filterBucketIds)
          if (filterBucketId == bucketId) {
            found = true;
            break;
          }
        if (!found)
          return false;
      }
      if (typeName == null)
        return true;
      final DocumentType type = database.getSchema().getTypeByBucketId(bucketId);
      return type != null && type.isSubTypeOf(typeName);
    }
  }
}
