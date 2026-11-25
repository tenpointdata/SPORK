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

import com.arcadedb.exception.TimeoutException;
import com.arcadedb.index.RangeIndex;
import com.arcadedb.query.sql.parser.AndBlock;
import com.arcadedb.query.sql.parser.BinaryCondition;
import com.arcadedb.query.sql.parser.Limit;
import com.arcadedb.query.sql.parser.Skip;
import com.arcadedb.query.sql.parser.WhereClause;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * {@code WHERE a = :a AND <rest> ORDER BY k LIMIT n} answered by walking an index on {@code (a, k)} in the direction of the
 * sort and stopping after {@code SKIP + LIMIT} matches, the rest of the WHERE tested on each record, instead of reading
 * another index that answers part of {@code <rest>} and sorting everything it returns.
 * <p>
 * Which is cheaper depends on the values bound, which the planner cannot see in a cached plan and has no statistics for:
 * the walk reads about {@code n / selectivity} entries, a plan on another index every entry that index holds for the bound
 * values. So they race. The other indexes the WHERE can search are the {@link Rival rivals}, the planner's own choice
 * first. For every entry the walk reads, one entry of each rival is counted, index only, no record loaded. The first rival
 * to run out is the smallest, holding {@code R} entries: from then on the walk goes on until it has read {@code R} entries
 * itself and then gives way to the plan on that rival, filtered, sorted and capped as the planner builds an index search.
 * So the walk costs at most about what the smallest rival's plan costs, on top of it, and much less when the bound value of
 * {@code a} holds the matches early: the price of not knowing is bounded by about twice the cheapest of the plans on one
 * index, which is never more than the plan the planner picked, plus an index-only read of the rivals' entries.
 * <p>
 * The walk buffers its matches until it has {@code SKIP + LIMIT} of them or has read every entry of its range, because a
 * walk that gives way after having handed rows on could not take them back: a rival's ties come in another order. Every side
 * reads the same transaction: the index cursors merge the uncommitted changes of the current transaction, and the walk's
 * entries come from the planner's ordinary index-ordered read ({@link FetchFromIndexStep} then
 * {@link GetValueFromIndexEntryStep}), merged across the buckets by key.
 */
public class OrderedIndexLimitStep extends AbstractExecutionStep {
  /**
   * Another index the WHERE can be searched with: a fetch of its entries that only counts them, and the plan that answers
   * the statement from it (its index search, the residual filter, the sort capped at {@code SKIP + LIMIT}).
   */
  public record Rival(String indexName, FetchFromIndexStep counter, SelectExecutionPlan plan) {
  }

  private final SelectExecutionPlan walk;
  private final List<Rival>         rivals;
  private final Skip                skip;
  private final Limit               limit;
  private final String              walkedIndexName;
  private final boolean             ascending;

  // State of one execution
  private final long[]       rivalEntries;
  private       boolean      decided;
  private       boolean      fellBack;
  private       int          smallest = -1;
  private       long         walkEntries;
  private       List<Result> rows;
  private       int          served;

  /**
   * @param walkedIndex the index whose leading keys the key condition binds by equality and whose next key is the sort key
   * @param residual    what the walk's index does not answer, tested on each record, or null
   * @param bucketIds   the buckets the records may come from
   * @param distinct    whether the walk's index can list a record more than once
   * @param rivals      the plans on other indexes, the one the planner picked first (it answers when the race cannot run)
   */
  public OrderedIndexLimitStep(final RangeIndex walkedIndex, final AndBlock keyCondition, final BinaryCondition additionalRangeCondition,
      final boolean ascending, final WhereClause residual, final List<Integer> bucketIds, final boolean distinct,
      final List<Rival> rivals, final Skip skip, final Limit limit, final CommandContext context) {
    super(context);
    this.rivals = rivals;
    this.rivalEntries = new long[rivals.size()];
    this.skip = skip;
    this.limit = limit;
    this.ascending = ascending;
    this.walkedIndexName = walkedIndex.getName();

    this.walk = new SelectExecutionPlan(context, 0);
    walk.chain(new RacingFetchFromIndexStep(walkedIndex, keyCondition, additionalRangeCondition, ascending, context));
    walk.chain(new GetValueFromIndexEntryStep(context, bucketIds, null));
    if (distinct)
      walk.chain(new DistinctExecutionStep(context));
    if (residual != null)
      walk.chain(new FilterStep(residual, context));
  }

  @Override
  public ResultSet syncPull(final CommandContext context, final int nRecords) throws TimeoutException {
    pullPrevious(context, nRecords);

    if (!decided) {
      final long begin = context.isProfiling() ? System.nanoTime() : 0;
      try {
        decide(context);
      } finally {
        if (context.isProfiling())
          cost += System.nanoTime() - begin;
      }
    }

    if (rows == null)
      return rivals.get(smallest).plan().fetchNext(nRecords);

    return new ResultSet() {
      private int localCount = 0;

      @Override
      public boolean hasNext() {
        return localCount < nRecords && served < rows.size();
      }

      @Override
      public Result next() {
        if (!hasNext())
          throw new NoSuchElementException();
        localCount++;
        final Result row = rows.get(served);
        // handed on: the buffer does not keep it alive
        rows.set(served++, null);
        return row;
      }
    };
  }

  /** Runs the race: the walk's matches when it wins, null rows when the plan on the smallest rival has to answer. */
  private void decide(final CommandContext context) {
    decided = true;
    final int wanted = rowsWanted(context);
    if (wanted < 0) {
      fellBack = true;
      smallest = 0;
    } else {
      final List<Result> found = new ArrayList<>(Math.min(wanted, 1024));
      try {
        while (found.size() < wanted && !fellBack) {
          final ResultSet page = walk.fetchNext(wanted - found.size());
          if (!page.hasNext())
            break;
          while (found.size() < wanted && page.hasNext())
            found.add(page.next());
        }
      } finally {
        // the cursors of the walk and of the counters are released now, whichever side answers
        walk.close();
        for (final Rival rival : rivals)
          rival.counter().close();
      }
      if (!fellBack)
        rows = found;
    }
    // the plans that do not answer never ran: closing them releases nothing but keeps close() idempotent for all
    for (int i = 0; i < rivals.size(); i++)
      if (i != smallest || !fellBack)
        rivals.get(i).plan().close();
  }

  /** SKIP + LIMIT for this execution, or -1 when they cannot be read, in which case the planner's choice answers. */
  private int rowsWanted(final CommandContext context) {
    try {
      final int limitValue = limit.getValue(context);
      final int skipValue = skip == null ? 0 : skip.getValue(context);
      if (limitValue < 0 || skipValue < 0 || (long) limitValue + skipValue > Integer.MAX_VALUE)
        return -1;
      return limitValue + skipValue;
    } catch (final RuntimeException e) {
      return -1;
    }
  }

  /**
   * Called before the walk hands on each of its index entries: counts one entry of every rival for it until one runs out,
   * and refuses the entry, for good, once the walk has read more entries than that smallest rival holds.
   */
  private boolean admitWalkEntry() {
    if (fellBack)
      return false;
    ++walkEntries;
    if (smallest < 0)
      for (int i = 0; i < rivals.size(); i++) {
        if (rivals.get(i).counter().nextIdentifiable(context) == null) {
          // the first to run out: every rival was counted as many times before this round, so none holds fewer
          smallest = i;
          break;
        }
        ++rivalEntries[i];
      }
    if (smallest >= 0 && walkEntries > rivalEntries[smallest]) {
      fellBack = true;
      return false;
    }
    return true;
  }

  /** Whether the last execution was answered by the walk (false: by the plan on a rival). */
  public boolean answeredByOrderedWalk() {
    return decided && !fellBack;
  }

  /** The index that answered the last execution: the walked one, or the rival the walk gave way to. */
  public String answeringIndex() {
    return !decided ? null : fellBack ? rivals.get(smallest).indexName() : walkedIndexName;
  }

  /** The index entries the walk read in the last execution. */
  public long orderedEntriesRead() {
    return walkEntries;
  }

  /** The entries of the rivals counted in the last execution, all of them together. */
  public long rivalEntriesCounted() {
    long total = 0;
    for (final long entries : rivalEntries)
      total += entries;
    return total;
  }

  @Override
  public void reset() {
    walk.reset(context);
    for (final Rival rival : rivals) {
      rival.plan().reset(context);
      rival.counter().reset();
    }
    decided = false;
    fellBack = false;
    smallest = -1;
    walkEntries = 0;
    Arrays.fill(rivalEntries, 0);
    rows = null;
    served = 0;
  }

  @Override
  public void close() {
    walk.close();
    for (final Rival rival : rivals) {
      rival.counter().close();
      rival.plan().close();
    }
    super.close();
  }

  @Override
  public void sendTimeout() {
    super.sendTimeout();
    // the sub-plans are not chained to this step: the timeout reaches them here
    for (final ExecutionStep step : walk.getSteps())
      ((ExecutionStepInternal) step).sendTimeout();
    for (final Rival rival : rivals)
      for (final ExecutionStep step : rival.plan().getSteps())
        ((ExecutionStepInternal) step).sendTimeout();
  }

  @Override
  public List<ExecutionPlan> getSubExecutionPlans() {
    final List<ExecutionPlan> plans = new ArrayList<>(1 + rivals.size());
    plans.add(walk);
    for (final Rival rival : rivals)
      plans.add(rival.plan());
    return plans;
  }

  @Override
  public String prettyPrint(final int depth, final int indent) {
    final String ind = ExecutionStepInternal.getIndent(depth, indent);
    final StringBuilder result = new StringBuilder(ind).append("+ ORDERED INDEX WALK ").append(walkedIndexName)
        .append(ascending ? " ASC" : " DESC").append(" WITH EARLY LIMIT, racing");
    for (int i = 0; i < rivals.size(); i++)
      result.append(i == 0 ? " " : ", ").append(rivals.get(i).indexName());
    if (context.isProfiling())
      result.append(" (").append(getCostFormatted()).append(")");
    if (decided) {
      result.append("\n").append(ind).append("  ");
      if (fellBack)
        result.append("gave way after ").append(walkEntries).append(" entries: ").append(rivals.get(smallest).indexName())
            .append(" holds ").append(rivalEntries[smallest]);
      else
        result.append("answered by the ordered walk after ").append(walkEntries).append(" entries");
    }
    result.append("\n").append(ind).append("  +-- ORDERED WALK");
    result.append("\n").append(walk.prettyPrint(depth + 2, indent));
    for (final Rival rival : rivals) {
      result.append("\n").append(ind).append("  +-- OR, WHEN ").append(rival.indexName()).append(" RUNS OUT FIRST");
      result.append("\n").append(rival.plan().prettyPrint(depth + 2, indent));
    }
    return result.toString();
  }

  /**
   * The walk's index read, asking {@link #admitWalkEntry()} before it hands on each entry. A subclass rather than a step of
   * its own because {@link GetValueFromIndexEntryStep} serves the entries of an index read it can see, which it reconciles
   * with commits that overlap the read.
   */
  private final class RacingFetchFromIndexStep extends FetchFromIndexStep {
    private RacingFetchFromIndexStep(final RangeIndex index, final AndBlock condition, final BinaryCondition additionalRangeCondition,
        final boolean orderAsc, final CommandContext context) {
      super(index, condition, additionalRangeCondition, orderAsc, context);
    }

    @Override
    public ResultSet syncPull(final CommandContext context, final int nRecords) throws TimeoutException {
      final ResultSet entries = super.syncPull(context, nRecords);
      return new ResultSet() {
        private boolean admitted;

        @Override
        public boolean hasNext() {
          if (admitted)
            return true;
          if (fellBack || !entries.hasNext() || !admitWalkEntry())
            return false;
          admitted = true;
          return true;
        }

        @Override
        public Result next() {
          if (!hasNext())
            throw new NoSuchElementException();
          admitted = false;
          return entries.next();
        }

        @Override
        public void close() {
          entries.close();
        }
      };
    }
  }
}
