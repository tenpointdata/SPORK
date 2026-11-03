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
package com.arcadedb.index.vector;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.graph.olap.GraphAlgorithms;
import com.arcadedb.index.sparsevector.SparseVectorScoringPool;
import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.utility.DedicatedThreadPool;
import com.arcadedb.utility.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-wide dedicated executor that searches the per-bucket sub-indexes of one dense {@code LSM_VECTOR} index in parallel.
 * <p>
 * A type with N buckets has N independent graphs. {@code vector.neighbors}, {@code db.index.vector.queryNodes} and
 * {@code select().nearestTo()} used to search them one after the other, so a query's latency was the SUM of N graph walks.
 * {@link #searchAll} runs them side by side and hands the answers back in index order, so every caller merges them exactly
 * as before (see {@link NeighborMerge}) and the answer does not depend on whether the search fanned out.
 * <p>
 * <b>Why a pool of its own.</b> Not the {@code QueryEngineManager} pool, where graph algorithms run seconds-long chunks a
 * k-NN fan-out would queue behind, and not the {@code SparseVectorScoringPool}, whose reservation gate counts sparse
 * queries only. Never the JDK common pool (see the {@code QueryEngineManager} class javadoc).
 * <p>
 * <b>When it fans out.</b> Only where a worker reads what the caller would read, otherwise the search stays on the
 * caller's thread, which is exactly the pre-pool behaviour:
 * <ul>
 *   <li>two sub-indexes or more, and {@link GlobalConfiguration#VECTOR_INDEX_PARALLEL_SEARCH} on for the database;</li>
 *   <li>the caller holds no uncommitted change and no {@code REPEATABLE_READ} transaction. A worker binds a context of its
 *   own, so it reads committed pages only: it would neither see the caller's pending vectors nor the page images the
 *   caller's transaction pinned;</li>
 *   <li>every sub-index already has its graph resident or resolved. The first search after an open loads or builds it, and
 *   that takes rebuild permits and heap; it stays on the caller, as it always did;</li>
 *   <li>the caller is not itself one of this pool's workers (a nested fan-out would only add queueing);</li>
 *   <li>the multi-index searches already in flight cannot keep the pool busy by themselves. Splitting buys latency with
 *   dispatch overhead, which is only worth paying with workers going spare.</li>
 * </ul>
 * <p>
 * <b>Liveness.</b> Bounded queue with caller-runs on saturation, and the wait RECLAIMS queued tasks
 * ({@link GraphAlgorithms#awaitFutures(Future[], int, DedicatedThreadPool)}), so a fan-out always completes even when every
 * worker is busy with other queries' searches.
 * <p>
 * <b>Context.</b> A task run by a worker binds the database to the worker thread as the CALLER's user, so record reads
 * (the stored vectors of an unquantized index, a {@code where} predicate) are made with the caller's permissions, and
 * removes the binding when done. A task reclaimed by, or rejected to, the caller runs in the caller's own context.
 */
public final class VectorSearchPool extends DedicatedThreadPool {

  private static final class Holder {
    static final VectorSearchPool INSTANCE = new VectorSearchPool();
  }

  /**
   * How much caller-side concurrency counts as enough to keep the pool busy without splitting: fan-out stops once
   * {@code inFlightSearches * this > poolSize}. Same shape, and same reasoning, as {@code SparseVectorScoringPool}'s gate:
   * N concurrent queries already occupy N caller threads, and splitting each of them past that point only trades
   * throughput for dispatch.
   */
  private static final int CALLER_LOAD_GATE_FACTOR = 2;

  /** Marks the pool's own workers, so a search running on one never fans out again. */
  private static final ThreadLocal<Boolean> POOL_THREAD = new ThreadLocal<>();

  /** Multi-index searches in flight on caller threads, fanned out or not. */
  private final AtomicInteger inFlightSearches = new AtomicInteger();
  /** Cumulative multi-index searches that fanned out. */
  private final AtomicLong    fanOuts          = new AtomicLong();
  /** Cumulative multi-index searches that stayed on the caller's thread. */
  private final AtomicLong    serialSearches   = new AtomicLong();

  /** One sub-index search, as the caller would have run it in its own loop. */
  @FunctionalInterface
  public interface BucketSearch {
    List<Pair<RID, Float>> search(LSMVectorIndex index);
  }

  private VectorSearchPool() {
    super("ArcadeDB-VectorSearch-", autoSizeThreads(GlobalConfiguration.VECTOR_SEARCH_POOL_THREADS.getValueAsInteger()),
        queueSizeOrDefault(GlobalConfiguration.VECTOR_SEARCH_QUEUE_SIZE.getValueAsInteger()), SaturationPolicy.CALLER_RUNS,
        (body, name) -> new Thread(() -> {
          POOL_THREAD.set(Boolean.TRUE);
          body.run();
        }, name), "Dense vector search pool",
        "the bucket is searched on the caller's thread, which costs parallelism but never the query",
        GlobalConfiguration.VECTOR_SEARCH_POOL_THREADS, GlobalConfiguration.VECTOR_SEARCH_QUEUE_SIZE);
  }

  public static VectorSearchPool getInstance() {
    return Holder.INSTANCE;
  }

  /** True on one of this pool's workers. */
  public static boolean isPoolThread() {
    return Boolean.TRUE.equals(POOL_THREAD.get());
  }

  /**
   * Runs {@code search} once per index and returns the answers in the order of {@code indexes}, fanning out on this pool
   * when that is safe (see the class javadoc) and on the caller's thread otherwise. The first failure, in index order, is
   * rethrown as it was raised once every search has ended: a fan-out never leaves a search running behind its caller.
   */
  public static List<List<Pair<RID, Float>>> searchAll(final List<LSMVectorIndex> indexes, final BucketSearch search) {
    if (indexes.size() < 2)
      return searchSerially(indexes, search);
    return getInstance().searchMany(indexes, search);
  }

  private List<List<Pair<RID, Float>>> searchMany(final List<LSMVectorIndex> indexes, final BucketSearch search) {
    inFlightSearches.incrementAndGet();
    try {
      if (!canFanOut(indexes)) {
        serialSearches.incrementAndGet();
        return searchSerially(indexes, search);
      }
      fanOuts.incrementAndGet();
      return fanOut(indexes, search);
    } finally {
      inFlightSearches.decrementAndGet();
    }
  }

  private static List<List<Pair<RID, Float>>> searchSerially(final List<LSMVectorIndex> indexes, final BucketSearch search) {
    final List<List<Pair<RID, Float>>> out = new ArrayList<>(indexes.size());
    for (final LSMVectorIndex index : indexes)
      out.add(search.search(index));
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<List<Pair<RID, Float>>> fanOut(final List<LSMVectorIndex> indexes, final BucketSearch search) {
    final DatabaseInternal database = indexes.getFirst().getDatabase();
    final String databasePath = database.getDatabasePath();
    final DatabaseContext.DatabaseContextTL callerContext = DatabaseContext.INSTANCE.getContextIfExists(databasePath);
    final SecurityDatabaseUser user = callerContext != null ? callerContext.getCurrentUser() : null;

    final int count = indexes.size();
    final List<Pair<RID, Float>>[] answers = new List[count];
    final Future<?>[] futures = new Future<?>[count];
    // The caller searches the first index itself rather than parking while a worker does: one dispatch fewer, and the
    // caller is the one thread certain to be free
    for (int i = 1; i < count; i++) {
      final int slot = i;
      futures[i] = executor.submit(() -> {
        answers[slot] = searchBound(database, databasePath, user, indexes.get(slot), search);
      });
    }

    // Whatever the caller's own search throws, the workers' searches are waited for before it is rethrown: a fan-out never
    // leaves a search running behind its caller
    Throwable callerFailure = null;
    try {
      answers[0] = search.search(indexes.getFirst());
    } catch (final RuntimeException | Error e) {
      callerFailure = e;
    }

    try {
      GraphAlgorithms.awaitFutures(futures, count, this);
    } catch (final RuntimeException | Error e) {
      if (callerFailure == null)
        throw e;
      callerFailure.addSuppressed(e);
    }
    if (callerFailure instanceof RuntimeException e)
      throw e;
    if (callerFailure instanceof Error e)
      throw e;

    final List<List<Pair<RID, Float>>> out = new ArrayList<>(count);
    for (final List<Pair<RID, Float>> answer : answers)
      out.add(answer);
    return out;
  }

  /**
   * Runs one search with the database bound to the running thread. A worker binds it as the caller's user and unbinds it
   * afterwards; a thread that already has a context for this database - the caller, reclaiming or running a rejected task -
   * keeps its own.
   */
  private static List<Pair<RID, Float>> searchBound(final DatabaseInternal database, final String databasePath,
      final SecurityDatabaseUser user, final LSMVectorIndex index, final BucketSearch search) {
    if (DatabaseContext.INSTANCE.getContextIfExists(databasePath) != null)
      return search.search(index);
    DatabaseContext.INSTANCE.init(database).setCurrentUser(user);
    try {
      return search.search(index);
    } finally {
      DatabaseContext.INSTANCE.removeContext(databasePath);
    }
  }

  /** Whether a search over {@code indexes} would fan out right now. See the class javadoc for each condition. */
  boolean canFanOut(final List<LSMVectorIndex> indexes) {
    if (indexes.size() < 2 || isPoolThread())
      return false;
    final DatabaseInternal database = indexes.getFirst().getDatabase();
    if (!database.getConfiguration().getValueAsBoolean(GlobalConfiguration.VECTOR_INDEX_PARALLEL_SEARCH))
      return false;
    if (inFlightSearches.get() * CALLER_LOAD_GATE_FACTOR > executor.getMaximumPoolSize())
      return false;
    if (callerTransactionHidesFromWorkers(database.getDatabasePath()))
      return false;
    for (final LSMVectorIndex index : indexes)
      if (!index.isGraphResolved())
        return false;
    return true;
  }

  /**
   * True when a worker, reading through a context of its own, could answer differently than the caller: the caller holds
   * uncommitted changes (its pending vectors, a flush inside its transaction), or sits in a {@code REPEATABLE_READ}
   * transaction whose pinned page images a worker would not read. Every transaction on the stack is asked, since a nested
   * one can hide an outer one's state.
   */
  static boolean callerTransactionHidesFromWorkers(final String databasePath) {
    if (SparseVectorScoringPool.callerHoldsUncommittedChanges(databasePath))
      return true;
    final DatabaseContext.DatabaseContextTL ctx = DatabaseContext.INSTANCE.getContextIfExists(databasePath);
    if (ctx == null)
      return false;
    for (int i = 0; i < ctx.transactions.size(); i++) {
      final TransactionContext tx = ctx.transactions.get(i);
      if (tx != null && tx.isActive() && tx.getIsolationLevel() == Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ)
        return true;
    }
    return false;
  }

  /** Cumulative multi-index searches that fanned out since JVM start. */
  public long getFanOutCount() {
    return fanOuts.get();
  }

  /** Cumulative multi-index searches that stayed on the caller's thread since JVM start. */
  public long getSerialSearchCount() {
    return serialSearches.get();
  }

  /** Multi-index searches currently in flight on caller threads. */
  public int getInFlightSearches() {
    return inFlightSearches.get();
  }
}
