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
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.DatabaseOperationException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.exception.TimeoutException;
import com.arcadedb.query.sql.parser.BooleanExpression;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Created by luigidellaquila on 22/07/16.
 */
public class FetchFromRidsStep extends AbstractExecutionStep {
  /** RIDs hydrated per {@code lookupByRIDs} call: enough pages in flight to fill a device queue, bounded heap per pull. */
  private static final int         HYDRATE_BATCH_SIZE = 512;
  private       Iterable<RID>     rids;
  // Set only by the cache-friendly constructor used by SelectExecutionPlanner.handleTypeWithRidFilter
  // (WHERE @rid = ? / @rid IN ?, see issue #5855). When non-null, `rids` above is re-resolved lazily
  // against the live CommandContext on the first pull of every execution instead of being baked in
  // at plan-build time, mirroring how FetchFromTypeWithFilterStep re-evaluates its WhereClause. This
  // is what makes the step - and therefore the whole plan - safely reusable from the execution plan
  // cache across executions bound to different RIDs.
  private final BooleanExpression ridCondition;
  private       Iterator<RID>     iterator;
  private       Result            nextResult = null;
  // SPORK L1: THE RIDs ARE HYDRATED IN BATCHES THROUGH lookupByRIDs - ONE READ LOCK, ONE PAGE READ PER DISTINCT PAGE, THE
  // MISSES IN FLIGHT TOGETHER - INSTEAD OF AN EXISTENCE PROBE AND A LOOKUP PER RID. A RID THAT CANNOT BE RESOLVED (DELETED,
  // PAST ITS BUCKET, A BUCKET THAT IS GONE) IS A NULL ENTRY AND IS SKIPPED, WHICH KEEPS THE STEP ISOLATION INDEPENDENT (#4643).
  // STEP FIELDS, NOT RESULT-SET ONES: EVERY syncPull BUILDS A NEW RESULT SET, AND WHAT A BATCH LOADED PAST nRecords IS SERVED
  // BY THE NEXT ONE
  private final ArrayDeque<Record> loaded = new ArrayDeque<>();
  private final RID[]              batch  = new RID[HYDRATE_BATCH_SIZE];
  // Set to false by the planner when nothing downstream reads a record's content (SELECT @rid FROM [...]): the step then hands
  // out the lazy shells it did before the batched hydration, checked for existence but with no content copied (review m3)
  private       boolean            loadContent = true;
  // WHAT THE BUFFERED BATCH WAS LOADED UNDER (re-review of M6): THIS THREAD'S TRANSACTION CONTEXT AND ITS COMMIT COUNT. A COMMIT
  // SINCE THEN (A BATCH n BOUNDARY OF THE DML STATEMENT THIS STEP FEEDS, IN AN EXPLICIT OR AN autoTransaction STATEMENT ALIKE,
  // OR THE IMPLICIT TRANSACTION EACH ROW OPENS AND COMMITS WHEN A SCRIPT LINE RUNS THE STATEMENT IN autoTransaction MODE WITH NO
  // TRANSACTION OPEN) PUBLISHED WRITES THE TRANSACTION'S OWN DELETED AND WRITTEN SETS NO LONGER SHOW, SO EVERY RECORD STILL
  // BUFFERED IS RE-READ. NO COMMIT AND NO ACTIVE TRANSACTION MEANS THIS THREAD WROTE NOTHING SINCE THE LOAD (A WRITE OUTSIDE A
  // TRANSACTION OPENS ONE OR IS REFUSED), SO THE BUFFERED RECORD IS ANSWERED AS LOADED WITHOUT AN EXISTENCE PROBE
  private       TransactionContext loadedUnder;
  private       long               loadedAtCommitCount;

  public FetchFromRidsStep(final Iterable<RID> rids, final CommandContext context) {
    super(context);
    this.rids = rids;
    this.ridCondition = null;
    reset();
  }

  FetchFromRidsStep(final BooleanExpression ridCondition, final CommandContext context) {
    super(context);
    this.ridCondition = ridCondition;
  }

  /**
   * {@code false} when no step after this one reads a record's content, only its identity: the records are then lazy shells,
   * still checked for existence, and nothing is copied. Decided by the planner from the shape of the statement.
   */
  public void setLoadContent(final boolean loadContent) {
    this.loadContent = loadContent;
  }

  public boolean isLoadContent() {
    return loadContent;
  }

  public void reset() {
    // With a ridCondition, defer re-resolution to the first pull of the (re)started execution, so it
    // uses that execution's own live CommandContext/bound parameters rather than whichever context
    // reset() happens to be called without.
    iterator = ridCondition == null ? rids.iterator() : null;
    nextResult = null;
    loaded.clear();
    loadedUnder = null;
  }

  @Override
  public ResultSet syncPull(final CommandContext context, final int nRecords) throws TimeoutException {
    pullPrevious(context, nRecords);
    if (ridCondition != null && iterator == null) {
      this.rids = SelectExecutionPlanner.resolveRidEqualityOrInListAtRuntime(ridCondition, context);
      iterator = this.rids.iterator();
    }
    // A RID list dominated by missing/deleted entries scans the whole list inside one fetchNext() call, the
    // same class of unbounded-inner-loop gap ScanWithFilterStep guards for a WHERE that rejects everything
    // (issue #6465).
    final WorkGuard guard = WorkGuard.forCommandDeadline(context);
    final DatabaseInternal database = (DatabaseInternal) context.getDatabase();
    return new ResultSet() {
      int internalNext = 0;

      private void fetchNext() {
        if (nextResult != null) {
          return;
        }
        while (true) {
          while (!loaded.isEmpty()) {
            final Record current = currentInTransaction(loaded.poll());
            if (current != null) {
              nextResult = new ResultInternal(current);
              return;
            }
          }
          if (!iterator.hasNext())
            return;
          // THE BATCH IS SIZED TO WHAT THIS PULL STILL OWES (review m3): A LIMIT 1 OVER 10,000 RIDS HYDRATES ONE, NOT 512. A LATER
          // PULL THAT OWES MORE GETS A FULL BATCH, AND WHAT A BATCH LOADED PAST nRecords WAITS IN loaded FOR IT
          final int wanted = Math.min(batch.length, Math.max(1, nRecords - internalNext));
          int n = 0;
          while (n < wanted && iterator.hasNext()) {
            guard.check();
            final RID nextRid = iterator.next();
            if (nextRid != null)
              batch[n++] = nextRid;
          }
          if (n == 0)
            return;

          final Record[] records = hydrate(database, n == batch.length ? batch : Arrays.copyOf(batch, n));
          for (int i = 0; i < n; i++)
            if (records[i] != null)
              loaded.add(records[i]);
          Arrays.fill(batch, 0, n, null);
          // SAMPLED AFTER THE LOAD, WHICH MAY ITSELF CREATE THIS THREAD'S CONTEXT BUT NEVER COMMITS
          loadedUnder = database.getTransactionIfExists();
          loadedAtCommitCount = loadedUnder != null ? loadedUnder.getCommitCount() : 0L;
        }
      }

      /**
       * The buffered record as the single lookup at this pull would answer it (review of L1): the batch was loaded ahead of
       * the rows a DML statement processes one at a time, so an earlier row may have deleted the record since (skipped, as
       * the per-RID existence probe did) or written a newer copy into the transaction (answered instead of the stale one).
       * A transaction that has neither deleted nor written anything costs two field reads; otherwise at most three hash
       * probes, no page access.
       * <p>
       * The transaction is resolved for each record, not once per pull, and a commit on this thread since the batch was
       * loaded (re-review of M6: a {@code BATCH n} boundary commits and re-begins between rows, popping a nested context or
       * moving the commit count of the reused one) makes the record re-read exactly as the per-RID probe did: an existence
       * check and a fresh lookup. Without such a commit the cost is one context lookup and two field reads.
       */
      private Record currentInTransaction(final Record buffered) {
        final TransactionContext tx = database.getTransactionIfExists();
        if (tx != loadedUnder || (tx != null && tx.getCommitCount() != loadedAtCommitCount))
          return reread(buffered.getIdentity());
        if (tx == null || !tx.isActive())
          return buffered;
        final RID rid = buffered.getIdentity();
        if (tx.hasDeletedRecords() && tx.isDeletedInTransaction(rid))
          return null;
        final Record written = tx.getWrittenRecord(rid);
        return written != null ? written : buffered;
      }

      /**
       * The batch with its content when it loads, which is the common case and the point of the batching. Before the batching
       * this step answered every RID with {@code lookupByRID(rid, false)}: under {@code READ_COMMITTED} a lazy shell, its content
       * read only by whoever needed it. A record whose content does not load (a broken chunk chain) was therefore still handed
       * out, and a DELETE took the loader's verdict on its own tolerant path (#6282). The batch keeps that answer: when loading
       * the content raises the loader's verdict, the batch is hydrated again without it, which hands out the same shells and
       * raises, under {@code REPEATABLE_READ} that forces the load, the same exception the per-RID path raised.
       */
      private Record[] hydrate(final DatabaseInternal database, final RID[] rids) {
        if (!loadContent)
          return database.lookupByRIDs(rids, false);
        try {
          return database.lookupByRIDs(rids, true);
        } catch (final DatabaseOperationException | ConcurrentModificationException e) {
          return database.lookupByRIDs(rids, false);
        }
      }

      private Record reread(final RID rid) {
        try {
          if (!database.existsRecord(rid))
            return null;
          return database.lookupByRID(rid, loadContent);
        } catch (final RecordNotFoundException e) {
          // DELETED BETWEEN THE PROBE AND THE LOOKUP: SKIPPED, AS THE PER-RID PROBE DID (#4643)
          return null;
        }
      }

      @Override
      public boolean hasNext() {
        if (internalNext >= nRecords)
          return false;

        if (nextResult == null)
          fetchNext();

        return nextResult != null;
      }

      @Override
      public Result next() {
        if (!hasNext())
          throw new NoSuchElementException();

        internalNext++;
        final Result result = nextResult;
        nextResult = null;
        return result;
      }

    };
  }

  @Override
  public String prettyPrint(final int depth, final int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ FETCH FROM RIDs\n" + ExecutionStepInternal.getIndent(depth, indent) + "  "
        + (ridCondition != null ? ridCondition : rids);
  }

  @Override
  public boolean canBeCached() {
    return ridCondition != null;
  }

  @Override
  public ExecutionStep copy(final CommandContext context) {
    if (ridCondition == null)
      throw new UnsupportedOperationException();
    final FetchFromRidsStep copy = new FetchFromRidsStep(ridCondition, context);
    copy.loadContent = loadContent;
    return copy;
  }
}
