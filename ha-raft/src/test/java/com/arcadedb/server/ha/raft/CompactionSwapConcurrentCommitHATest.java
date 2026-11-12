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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.engine.PageId;
import com.arcadedb.engine.PaginatedComponent;
import com.arcadedb.engine.TransactionManager;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.lsm.LSMTreeIndex;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.utility.LockManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-12, item 1: a page write to the new mutable file an LSM compaction swapped in reached the Raft log BEFORE
 * the entry that creates that file on the followers. Every follower skipped it ({@code TransactionManager.applyChanges}
 * drops the pages of a file it does not have), installed the file from the compaction entry at the version the leader
 * read when it serialized it, and failed the next write to the page with a {@code WALVersionGapException}: quarantine
 * and a full resync on every follower.
 * <p>
 * How a commit gets there: {@code RaftReplicatedDatabase.commit()} waits for an open recording session only BEFORE its
 * phase 1 ({@code waitForActiveRecordingSession}). A commit that passed that check before the compaction session opened
 * and then queued on a file lock (under concurrent writers, a bucket lock is held for a whole replication round trip)
 * takes the index lock only after {@code LSMTreeIndex.splitIndex} swapped the mutable file and released it, re-resolves
 * the migrated file id, writes its key into the NEW file and ships it in a {@code TX_ENTRY} - while the compaction thread
 * is still serializing the new files for the {@code SCHEMA_ENTRY} it publishes them with.
 * <p>
 * The first test drives that interleaving deterministically: a blocker holds the lock of a bucket the writer needs, the
 * writer queues on it after passing the session check, the compaction runs, and the publication hook releases the blocker
 * and lets the writer's entry commit before the compaction entry. The second runs concurrent writers through forced
 * compaction rounds, with the same hook only widening each round until a post-swap commit is either acknowledged or
 * queued on the new file.
 */
class CompactionSwapConcurrentCommitHATest extends BaseRaftHATest {

  private static final String INDEXED_TYPE   = "Indexed";
  private static final String OTHER_TYPE     = "Other";
  private static final String KEY            = "key";
  // SMALL INDEX PAGES SO A FEW HUNDRED KEYS FILL SEVERAL OF THEM AND A COMPACTION HAS IMMUTABLE PAGES TO MERGE
  private static final int    INDEX_PAGE     = 4096;
  private static final int    SEED_RECORDS   = 600;
  private static final long   AWAIT_MS       = 60_000;

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Override
  protected void populateDatabase() {
  }

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    // THE TEST TRIGGERS EVERY COMPACTION ITSELF
    config.setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
  }

  @BeforeEach
  void countWalVersionGaps() {
    ArcadeStateMachine.TEST_WAL_GAP_COUNTER = new AtomicInteger();
  }

  @AfterEach
  void clearHooks() {
    RaftReplicatedDatabase.TEST_PRE_DISPATCH_HOOK = null;
    RaftReplicatedDatabase.TEST_POST_REPLICATION_HOOK = null;
    RaftReplicatedDatabase.TEST_BEFORE_COMPACTION_PUBLISH_HOOK = null;
    ArcadeStateMachine.TEST_WAL_GAP_COUNTER = null;
  }

  @Test
  void aCommitQueuedAcrossTheSwapIsNotReplicatedBeforeTheCompactionThatCreatedItsFile() throws Exception {
    final int leaderIndex = requireLeaderIndex();
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());
    final LSMTreeIndex lsm = createSchemaAndSeed(leader);
    final TransactionManager txManager = ((DatabaseInternal) leader).getEmbedded().getTransactionManager();
    final String otherBucketFile = String.valueOf(leader.getSchema().getType(OTHER_TYPE).getBuckets(false).getFirst().getFileId());
    assertThat(Integer.parseInt(otherBucketFile)).as("the writer must queue on the blocker's bucket BEFORE it locks the index")
        .isLessThan(lsm.getMutableIndex().getFileId());

    final AtomicReference<Thread> blockerThread = new AtomicReference<>();
    final AtomicReference<Thread> writerThread = new AtomicReference<>();
    final CountDownLatch blockerAtDispatch = new CountDownLatch(1);
    final CountDownLatch releaseBlocker = new CountDownLatch(1);
    final CountDownLatch writerAtDispatch = new CountDownLatch(1);
    final CountDownLatch releaseWriter = new CountDownLatch(1);
    final CountDownLatch writerAcknowledged = new CountDownLatch(1);
    final AtomicBoolean writerShippedFirst = new AtomicBoolean();
    final AtomicInteger publications = new AtomicInteger();

    RaftReplicatedDatabase.TEST_PRE_DISPATCH_HOOK = db -> {
      if (Thread.currentThread() == blockerThread.get()) {
        blockerAtDispatch.countDown();
        awaitQuietly(releaseBlocker);
      } else if (Thread.currentThread() == writerThread.get()) {
        writerAtDispatch.countDown();
        awaitQuietly(releaseWriter);
      }
    };
    RaftReplicatedDatabase.TEST_POST_REPLICATION_HOOK = db -> {
      if (Thread.currentThread() == writerThread.get())
        writerAcknowledged.countDown();
    };
    // AFTER THE COMPACTION HAS RUN AND ITS PAGES ARE READ FOR SHIPPING, BEFORE THE ENTRY THAT PUBLISHES THEM IS SUBMITTED
    RaftReplicatedDatabase.TEST_BEFORE_COMPACTION_PUBLISH_HOOK = db -> {
      if (publications.getAndIncrement() > 0)
        return;
      releaseBlocker.countDown();
      final String newMutableFile = String.valueOf(lsm.getMutableIndex().getFileId());
      final long deadline = System.currentTimeMillis() + AWAIT_MS;
      // THE WRITER EITHER GETS THROUGH ITS PHASE 1 ON THE NEW FILE (THE BUG) OR QUEUES ON THAT FILE'S LOCK (THE FIX)
      while (writerAtDispatch.getCount() > 0 && !hasWaiter(txManager, newMutableFile)) {
        if (System.currentTimeMillis() > deadline)
          throw new IllegalStateException("the writer neither committed into nor queued on file " + newMutableFile + ": "
              + txManager.getLockStats());
        sleepQuietly(5);
      }
      writerShippedFirst.set(writerAtDispatch.getCount() == 0);
      releaseWriter.countDown();
      if (writerShippedFirst.get())
        awaitQuietly(writerAcknowledged);
    };

    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<?> blocker = executor.submit(() -> {
        blockerThread.set(Thread.currentThread());
        leader.transaction(() -> leader.newDocument(OTHER_TYPE).set(KEY, "blocker").save(), false, 1);
      });
      assertThat(blockerAtDispatch.await(AWAIT_MS, TimeUnit.MILLISECONDS)).as("the blocker holds the bucket lock").isTrue();

      final Future<?> writer = executor.submit(() -> {
        writerThread.set(Thread.currentThread());
        leader.transaction(() -> {
          leader.newDocument(OTHER_TYPE).set(KEY, "writer").save();
          leader.newDocument(INDEXED_TYPE).set(KEY, "writer-key").save();
        }, false, 1);
      });
      // PAST THE RECORDING-SESSION CHECK, QUEUED ON THE BLOCKER'S BUCKET: NO SESSION IS OPEN YET
      awaitWaiter(txManager, otherBucketFile);

      assertThat(lsm.scheduleCompaction()).isTrue();
      assertThat(lsm.compact()).as("the compaction ran").isTrue();

      blocker.get(AWAIT_MS, TimeUnit.MILLISECONDS);
      writer.get(AWAIT_MS, TimeUnit.MILLISECONDS);
    } finally {
      releaseBlocker.countDown();
      releaseWriter.countDown();
      executor.shutdownNow();
      executor.awaitTermination(AWAIT_MS, TimeUnit.MILLISECONDS);
    }

    // ONE MORE WRITE TO THE SAME INDEX PAGE: ON A FOLLOWER THAT LOST THE WRITER'S PAGE THIS IS THE VERSION GAP
    leader.transaction(() -> leader.newDocument(INDEXED_TYPE).set(KEY, "after-key").save());
    waitForAllServers();

    assertNoDivergence();
    for (int i = 0; i < getServerCount(); i++) {
      assertThat(lookup(i, "writer-key")).as("server %d finds the key the writer committed across the swap", i).isEqualTo(1);
      assertThat(lookup(i, "after-key")).as("server %d finds the key committed after the compaction", i).isEqualTo(1);
    }
    assertSamePageVersions(leaderIndex, lsm.getName());
  }

  /**
   * N writers on one LSM-indexed type through forced compaction rounds. Each round's publication waits (the hook) until a
   * commit that began after the swap was acknowledged or is queued on the new file: the shape of the incident, where
   * about 20 commits per second ran into each compaction.
   */
  @Test
  void concurrentWritersThroughForcedCompactionsLeaveEveryNodeIdentical() throws Exception {
    final int writers = 8;
    final int rounds = 4;

    final int leaderIndex = requireLeaderIndex();
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());
    final LSMTreeIndex lsm = createSchemaAndSeed(leader);
    final TransactionManager txManager = ((DatabaseInternal) leader).getEmbedded().getTransactionManager();

    final AtomicLong acknowledged = new AtomicLong();
    RaftReplicatedDatabase.TEST_POST_REPLICATION_HOOK = db -> acknowledged.incrementAndGet();
    RaftReplicatedDatabase.TEST_BEFORE_COMPACTION_PUBLISH_HOOK = db -> {
      final String newMutableFile = String.valueOf(lsm.getMutableIndex().getFileId());
      final long before = acknowledged.get();
      final long deadline = System.currentTimeMillis() + AWAIT_MS;
      while (acknowledged.get() == before && !hasWaiter(txManager, newMutableFile) && System.currentTimeMillis() < deadline)
        sleepQuietly(2);
    };

    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicLong keys = new AtomicLong();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final ExecutorService executor = Executors.newFixedThreadPool(writers);
    final List<Future<?>> futures = new ArrayList<>();
    try {
      for (int w = 0; w < writers; w++)
        futures.add(executor.submit(() -> {
          while (!stop.get() && failure.get() == null)
            try {
              leader.transaction(() -> leader.newDocument(INDEXED_TYPE).set(KEY, "w-" + keys.incrementAndGet()).save(), false, 10);
            } catch (final NeedRetryException e) {
              // RETRIED ON THE NEXT LOOP
            } catch (final Throwable e) {
              failure.compareAndSet(null, e);
            }
        }));

      for (int round = 0; round < rounds; round++) {
        // LET THE MUTABLE FILE GROW PAST ONE PAGE AGAIN BEFORE THE NEXT ROUND
        final int pagesBefore = lsm.getMutableIndex().getTotalPages();
        final long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (lsm.getMutableIndex().getTotalPages() < Math.max(3, pagesBefore) && System.currentTimeMillis() < deadline)
          Thread.sleep(10);
        if (lsm.scheduleCompaction())
          lsm.compact();
      }
    } finally {
      stop.set(true);
      executor.shutdown();
      assertThat(executor.awaitTermination(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }
    for (final Future<?> f : futures)
      f.get();
    assertThat(failure.get()).as("no writer failed with a non-retryable error").isNull();

    leader.transaction(() -> leader.newDocument(INDEXED_TYPE).set(KEY, "after-key").save());
    waitForAllServers();

    assertNoDivergence();
    final long expected = leader.countType(INDEXED_TYPE, true);
    for (int i = 0; i < getServerCount(); i++) {
      final Database db = getServerDatabase(i, getDatabaseName());
      assertThat(db.countType(INDEXED_TYPE, true)).as("server %d record count", i).isEqualTo(expected);
      assertThat(db.getSchema().getIndexByName(INDEXED_TYPE + "[" + KEY + "]").countEntries())
          .as("server %d index entries", i).isEqualTo(expected);
    }
    assertSamePageVersions(leaderIndex, lsm.getName());
  }

  private LSMTreeIndex createSchemaAndSeed(final Database leader) {
    // THE OTHER TYPE FIRST: ITS BUCKET FILE ID IS LOWER THAN EVERY FILE OF THE INDEX, SO A COMMIT TOUCHING BOTH LOCKS IT FIRST
    final DocumentType other = leader.getSchema().buildDocumentType().withName(OTHER_TYPE).withTotalBuckets(1).create();
    other.createProperty(KEY, Type.STRING);
    final DocumentType indexed = leader.getSchema().buildDocumentType().withName(INDEXED_TYPE).withTotalBuckets(1).create();
    indexed.createProperty(KEY, Type.STRING);
    final TypeIndex typeIndex = (TypeIndex) leader.getSchema().buildTypeIndex(INDEXED_TYPE, new String[] { KEY })
        .withType(Schema.INDEX_TYPE.LSM_TREE).withUnique(false).withPageSize(INDEX_PAGE).create();

    for (int start = 0; start < SEED_RECORDS; start += 100) {
      final int from = start;
      leader.transaction(() -> {
        for (int i = from; i < from + 100; i++)
          leader.newDocument(INDEXED_TYPE).set(KEY, "seed-" + i).save();
      });
    }
    leader.transaction(() -> leader.newDocument(OTHER_TYPE).set(KEY, "seed").save());
    waitForAllServers();

    final LSMTreeIndex lsm = (LSMTreeIndex) typeIndex.getIndexesOnBuckets()[0];
    assertThat(lsm.getMutableIndex().getTotalPages()).as("the seed fills several index pages").isGreaterThan(2);
    return lsm;
  }

  private void assertNoDivergence() {
    assertThat(ArcadeStateMachine.TEST_WAL_GAP_COUNTER.get()).as("WAL version gaps on the followers").isZero();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(isDatabaseQuarantined(i, getDatabaseName())).as("server %d quarantined the database", i).isFalse();
  }

  private long lookup(final int serverIndex, final String key) {
    final Database db = getServerDatabase(serverIndex, getDatabaseName());
    long found = 0;
    final IndexCursor cursor = db.getSchema().getIndexByName(INDEXED_TYPE + "[" + KEY + "]").get(new Object[] { key });
    while (cursor.hasNext()) {
      cursor.next();
      ++found;
    }
    return found;
  }

  /** Every page of the bucket index's files carries the same version on every node. */
  private void assertSamePageVersions(final int leaderIndex, final String bucketIndexName) throws Exception {
    final DatabaseInternal leaderDb = (DatabaseInternal) getServerDatabase(leaderIndex, getDatabaseName());
    final LSMTreeIndex leaderIndexComponent = (LSMTreeIndex) leaderDb.getSchema().getIndexByName(bucketIndexName);
    final List<Integer> fileIds = leaderIndexComponent.getFileIds();
    for (int i = 0; i < getServerCount(); i++) {
      if (i == leaderIndex)
        continue;
      final DatabaseInternal follower = (DatabaseInternal) getServerDatabase(i, getDatabaseName());
      for (final int fileId : fileIds) {
        final PaginatedComponent onLeader = (PaginatedComponent) leaderDb.getSchema().getEmbedded().getFileByIdIfExists(fileId);
        final PaginatedComponent onFollower = (PaginatedComponent) follower.getSchema().getEmbedded().getFileByIdIfExists(fileId);
        assertThat(onFollower).as("server %d holds file %d", i, fileId).isNotNull();
        assertThat(onFollower.getTotalPages()).as("server %d pages of file %d", i, fileId).isEqualTo(onLeader.getTotalPages());
        for (int page = 0; page < onLeader.getTotalPages(); page++) {
          final long leaderVersion = leaderDb.getPageManager()
              .getImmutablePage(new PageId(leaderDb, fileId, page), onLeader.getPageSize(), false, false).getVersion();
          final long followerVersion = follower.getPageManager()
              .getImmutablePage(new PageId(follower, fileId, page), onFollower.getPageSize(), false, false).getVersion();
          assertThat(followerVersion).as("server %d version of page %d of file %d", i, page, fileId).isEqualTo(leaderVersion);
        }
      }
    }
  }

  private static boolean hasWaiter(final TransactionManager txManager, final String fileId) {
    for (final LockManager.LockStats s : txManager.getLockStats())
      if (s.resource().equals(fileId) && s.waiters() > 0)
        return true;
    return false;
  }

  private static void awaitWaiter(final TransactionManager txManager, final String fileId) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + AWAIT_MS;
    while (!hasWaiter(txManager, fileId)) {
      assertThat(System.currentTimeMillis()).as("a commit queued on file %s: %s", fileId, txManager.getLockStats())
          .isLessThan(deadline);
      Thread.sleep(5);
    }
  }

  private static void awaitQuietly(final CountDownLatch latch) {
    try {
      latch.await(AWAIT_MS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepQuietly(final long ms) {
    try {
      Thread.sleep(ms);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
