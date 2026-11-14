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
package com.arcadedb.server.ha.raft;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.engine.TransactionManager;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.LockManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures why replicated commits that write the same bucket serialize (SPORK stress report, issue 4): on the leader a
 * commit takes the commit lock of every file it writes in phase 1 ({@code TransactionContext.commit1stPhase}) and gives it
 * back only in {@code completeCommit()}, after the Raft entry was majority-acknowledged and applied. The whole replication
 * round trip is therefore spent holding the bucket's lock, and every other commit into that bucket queues behind it.
 * <p>
 * Two measurements, both on lock state rather than on wall-clock (the machine is shared):
 * <ol>
 * <li>{@link #aReplicatingCommitHoldsItsBucketLockFromPhaseOneUntilItCompletes()} parks one commit at the dispatch to Raft
 * and right after the acknowledgement, and reads the leader's lock table: the bucket is held at both points, a second
 * commit into the same bucket is queued on it, and a commit into another bucket goes through meanwhile.</li>
 * <li>{@link #sameBucketCommitsQueueBehindReplicationWhileSpreadBucketsDoNot()} runs the same single-vertex load into a
 * 1-bucket type and a 16-bucket type and compares the leader's commit-lock counters (#10509): the share of releases handed
 * straight to a waiting commit (a queue) and the mean hold, which is the replication round trip in both cases.</li>
 * </ol>
 * Tagged {@code benchmark}: it prints figures and is skipped by the regular lanes. Run it with
 * {@code mvn -o -pl ha-raft -am test -Dtest=HaSameBucketCommitSerializationBenchmark -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false}.
 * The design that would relieve it is {@code docs/spork/workstreams/ha-commit-pipelining.md}.
 */
@Tag("benchmark")
class HaSameBucketCommitSerializationBenchmark extends SharedClusterRaftHATest {

  private static final int THREADS            = 16;
  private static final int COMMITS_PER_THREAD = 40;
  private static final int SPREAD_BUCKETS     = 16;

  /** Set on the committing thread that the hooks must park. */
  private static final ThreadLocal<Boolean> PARK = ThreadLocal.withInitial(() -> Boolean.FALSE);

  @Override
  protected int getServerCount() {
    return 3;
  }

  @AfterEach
  void clearHooks() {
    RaftReplicatedDatabase.TEST_PRE_DISPATCH_HOOK = null;
    RaftReplicatedDatabase.TEST_POST_REPLICATION_HOOK = null;
  }

  @Test
  void aReplicatingCommitHoldsItsBucketLockFromPhaseOneUntilItCompletes() throws Exception {
    final String hotType = uniqueName("HotBucket");
    final String otherType = uniqueName("OtherBucket");
    final int leaderIndex = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leaderIndex, getDatabaseName());
    leaderDb.command("sql", "CREATE VERTEX TYPE " + hotType + " BUCKETS 1");
    leaderDb.command("sql", "CREATE VERTEX TYPE " + otherType + " BUCKETS 1");
    // A property name the dictionary does not hold yet is added by a commit of its own (on file 0), which would be the one
    // the hooks park: register it first
    leaderDb.transaction(() -> leaderDb.newVertex(otherType).set("who", "warm-up").save());
    waitForAllServers();

    final String hotFile = String.valueOf(leaderDb.getSchema().getType(hotType).getBuckets(false).getFirst().getFileId());
    final TransactionManager txManager = ((DatabaseInternal) leaderDb).getEmbedded().getTransactionManager();

    final CountDownLatch atDispatch = new CountDownLatch(1);
    final CountDownLatch releaseDispatch = new CountDownLatch(1);
    final CountDownLatch afterAck = new CountDownLatch(1);
    final CountDownLatch releaseAck = new CountDownLatch(1);
    final AtomicReference<LockManager.LockStats> heldAtDispatch = new AtomicReference<>();
    final AtomicReference<LockManager.LockStats> heldAfterAck = new AtomicReference<>();
    final AtomicReference<String> tableAtDispatch = new AtomicReference<>();

    RaftReplicatedDatabase.TEST_PRE_DISPATCH_HOOK = db -> {
      if (!PARK.get())
        return;
      heldAtDispatch.set(lockOf(txManager, hotFile));
      tableAtDispatch.set(String.valueOf(txManager.getLockStats()));
      atDispatch.countDown();
      awaitQuietly(releaseDispatch);
    };
    RaftReplicatedDatabase.TEST_POST_REPLICATION_HOOK = db -> {
      if (!PARK.get())
        return;
      heldAfterAck.set(lockOf(txManager, hotFile));
      afterAck.countDown();
      awaitQuietly(releaseAck);
    };

    final ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      final Future<?> parked = executor.submit(() -> {
        PARK.set(true);
        try {
          leaderDb.transaction(() -> leaderDb.newVertex(hotType).set("who", "parked").save());
        } finally {
          PARK.remove();
        }
      });
      assertThat(atDispatch.await(60, TimeUnit.SECONDS)).as("the commit reached the dispatch to Raft").isTrue();
      assertThat(heldAtDispatch.get()).as("phase 1 is over and the entry is about to be submitted: bucket file %s is locked (%s)",
              hotFile, tableAtDispatch.get())
          .isNotNull();

      // A second commit into the same bucket queues on the lock the parked commit holds
      final Future<?> sameBucket = executor.submit(
          () -> leaderDb.transaction(() -> leaderDb.newVertex(hotType).set("who", "queued").save(), false, 10));
      final LockManager.LockStats queued = awaitWaiter(txManager, hotFile);
      assertThat(queued.waiters()).as("the same-bucket commit waits for the replicating one").isGreaterThanOrEqualTo(1);

      // A commit into another bucket is not held back by it at all
      executor.submit(() -> leaderDb.transaction(() -> leaderDb.newVertex(otherType).set("who", "free").save()))
          .get(60, TimeUnit.SECONDS);
      assertThat(sameBucket.isDone()).as("the same-bucket commit is still queued").isFalse();

      // Through quorum and the leader's own apply: the pages are published, and the lock is STILL held
      releaseDispatch.countDown();
      assertThat(afterAck.await(60, TimeUnit.SECONDS)).as("the entry was acknowledged").isTrue();
      assertThat(heldAfterAck.get()).as("acknowledged and applied, but not completed: the bucket is still locked").isNotNull();
      assertThat(sameBucket.isDone()).as("the same-bucket commit is still queued after the acknowledgement").isFalse();

      releaseAck.countDown();
      parked.get(60, TimeUnit.SECONDS);
      sameBucket.get(60, TimeUnit.SECONDS);
    } finally {
      releaseDispatch.countDown();
      releaseAck.countDown();
      executor.shutdownNow();
      executor.awaitTermination(60, TimeUnit.SECONDS);
    }

    assertThat(countOf(leaderDb, hotType)).isEqualTo(2L);
    assertThat(countOf(leaderDb, otherType)).isEqualTo(2L);
  }

  @Test
  void sameBucketCommitsQueueBehindReplicationWhileSpreadBucketsDoNot() throws Exception {
    final String singleType = uniqueName("SingleBucket");
    final String spreadType = uniqueName("SpreadBuckets");
    final int leaderIndex = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leaderIndex, getDatabaseName());
    leaderDb.command("sql", "CREATE VERTEX TYPE " + singleType + " BUCKETS 1");
    leaderDb.command("sql", "CREATE VERTEX TYPE " + spreadType + " BUCKETS " + SPREAD_BUCKETS);
    waitForAllServers();

    final TransactionManager txManager = ((DatabaseInternal) leaderDb).getEmbedded().getTransactionManager();

    // Warm both paths once, so neither leg pays for the first page of its buckets or for class loading
    runLoad(leaderDb, singleType, 4, 5);
    runLoad(leaderDb, spreadType, 4, 5);

    final LockFigures single = measure(leaderDb, txManager, singleType);
    final LockFigures spread = measure(leaderDb, txManager, spreadType);

    LogManager.instance().log(this, Level.WARNING, """
            TEST: issue 4, %d threads x %d single-vertex commits on the leader of a %d-node cluster
              1 bucket : %s
              %d buckets: %s""", THREADS, COMMITS_PER_THREAD, getServerCount(), single, SPREAD_BUCKETS, spread);

    // The queue: almost every release of the single bucket's lock hands it to a commit already waiting for it, while the
    // spread buckets are mostly free when a commit gets to them
    assertThat(single.handOffShare()).as("1 bucket: commits queue on the bucket lock (%s)", single).isGreaterThan(0.5);
    assertThat(spread.handOffShare()).as("%d buckets queue less than 1 (%s vs %s)", SPREAD_BUCKETS, spread, single)
        .isLessThan(single.handOffShare());

    final long expected = (long) THREADS * COMMITS_PER_THREAD + 4 * 5;
    assertThat(countOf(leaderDb, singleType)).isEqualTo(expected);
    assertThat(countOf(leaderDb, spreadType)).isEqualTo(expected);
    assertClusterConsistency();
  }

  private LockFigures measure(final Database db, final TransactionManager txManager, final String type) throws Exception {
    final Map<String, Object> before = txManager.getStats();
    final long start = System.nanoTime();
    runLoad(db, type, THREADS, COMMITS_PER_THREAD);
    final long elapsed = System.nanoTime() - start;
    final Map<String, Object> after = txManager.getStats();
    return new LockFigures(delta(before, after, "commitLockHolds"), delta(before, after, "commitLockHoldNanos"),
        delta(before, after, "commitLockHandOffs"), delta(before, after, "commitLockHandOffNanos"),
        (long) THREADS * COMMITS_PER_THREAD, elapsed);
  }

  private static void runLoad(final Database db, final String type, final int threads, final int commitsPerThread)
      throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    final AtomicBoolean failed = new AtomicBoolean();
    try {
      final Future<?>[] futures = new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        final int thread = t;
        futures[t] = executor.submit(() -> {
          for (int i = 0; i < commitsPerThread && !failed.get(); i++) {
            final int n = i;
            try {
              db.transaction(() -> db.newVertex(type).set("t", thread).set("i", n).save(), false, 50);
            } catch (final RuntimeException e) {
              failed.set(true);
              throw e;
            }
          }
        });
      }
      for (final Future<?> f : futures)
        f.get(10, TimeUnit.MINUTES);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(60, TimeUnit.SECONDS);
    }
  }

  private static long delta(final Map<String, Object> before, final Map<String, Object> after, final String key) {
    return ((Number) after.get(key)).longValue() - ((Number) before.get(key)).longValue();
  }

  private static LockManager.LockStats lockOf(final TransactionManager txManager, final String fileId) {
    for (final LockManager.LockStats s : txManager.getLockStats())
      if (s.resource().equals(fileId))
        return s;
    return null;
  }

  private static LockManager.LockStats awaitWaiter(final TransactionManager txManager, final String fileId)
      throws InterruptedException {
    final long deadline = System.currentTimeMillis() + 60_000;
    LockManager.LockStats s = lockOf(txManager, fileId);
    while ((s == null || s.waiters() == 0) && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
      s = lockOf(txManager, fileId);
    }
    assertThat(s).as("bucket file %s is locked", fileId).isNotNull();
    return s;
  }

  private static void awaitQuietly(final CountDownLatch latch) {
    try {
      latch.await(60, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static long countOf(final Database db, final String typeName) {
    try (final ResultSet rs = db.query("sql", "SELECT count(@rid) AS cnt FROM " + typeName)) {
      return rs.next().<Number>getProperty("cnt").longValue();
    }
  }

  /** Commit-lock counters of one leg; the throughput is printed for orientation only, never asserted. */
  private record LockFigures(long holds, long holdNanos, long handOffs, long handOffNanos, long commits, long elapsedNanos) {
    double handOffShare() {
      return holds == 0 ? 0 : (double) handOffs / holds;
    }

    @Override
    public String toString() {
      return String.format("holds=%d handOffs=%d (%.0f%%) meanHold=%dus meanHandOff=%dus commits/s=%.0f", holds, handOffs,
          100 * handOffShare(), holds == 0 ? 0 : holdNanos / holds / 1000, handOffs == 0 ? 0 : handOffNanos / handOffs / 1000,
          commits * 1e9 / elapsedNanos);
    }
  }
}
