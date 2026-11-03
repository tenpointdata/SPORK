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
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexException;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.ivfvector.LSMIVFVectorIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.StallAwareStopwatch;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code LSM_IVF_VECTOR} under Raft: the index is built on the leader (training, centroid pages, postings, locator) and every
 * follower searches it from the pages the Raft log shipped, with no training or assignment of its own. A delete committed on the
 * leader disappears from the followers' answers, and an index created empty trains itself on the leader once it holds
 * {@code autoTrainThreshold} vectors, the training reaching the followers as pages of one transaction. A follower's write during a
 * build on the leader is refused until the build ends, and any build outside the leader's schema session is refused.
 */
class LSMIVFVectorIndexHATest extends BaseRaftHATest {
  static final String TYPE_NAME  = "IvfDoc";
  static final String AUTO_TYPE  = "IvfAuto";
  static final String BUILD_TYPE = "IvfBuild";
  static final int    DIMENSIONS = 16;
  static final int    VECTORS    = 2_000;
  // A refused commit is answered in seconds; one held until the build ends never is, the build waiting on the writer
  static final long   WRITER_REFUSED_MS = 60_000L;
  static final long   WRITER_HANG_MS    = 120_000L;

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Override
  protected void populateDatabase() {
  }

  @Test
  void anIndexBuiltOnTheLeaderIsSearchedOnTheFollowers() {
    final int leaderIndex = findLeaderIndex();
    assertThat(leaderIndex).as("a Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());
    leader.command("sql", "CREATE DOCUMENT TYPE " + TYPE_NAME + " BUCKETS 1");
    leader.command("sql", "CREATE PROPERTY " + TYPE_NAME + ".id INTEGER");
    leader.command("sql", "CREATE PROPERTY " + TYPE_NAME + ".embedding ARRAY_OF_FLOATS");
    insert(leader, TYPE_NAME, 0, VECTORS);
    leader.command("sql", "CREATE INDEX ON " + TYPE_NAME + " (embedding) LSM_IVF_VECTOR METADATA {dimensions: " + DIMENSIONS
        + ", similarity: 'EUCLIDEAN', nlist: 10, nprobe: 4}");
    waitForAllServers();

    for (int server = 0; server < getServerCount(); server++) {
      final int serverIndex = server;
      assertThat(trainedOn(serverIndex, TYPE_NAME)).as("trained on server %d", serverIndex).isTrue();
      assertThat(centroidsOn(serverIndex, TYPE_NAME)).isEqualTo(10);
      assertThat(entriesOn(serverIndex, TYPE_NAME)).isEqualTo((long) VECTORS);
      for (int id = 0; id < VECTORS; id += 97) {
        final int record = id;
        final RID expected = withResyncRetry(serverIndex, db -> recordOf(db, TYPE_NAME, record));
        final RID found = withResyncRetry(serverIndex, db -> nearest(db, TYPE_NAME, embedding(record)));
        assertThat(found).as("server %d must find record %d", serverIndex, record).isEqualTo(expected);
      }
    }

    // A delete on the leader: gone from every follower's answer
    final RID deleted = recordOf(leader, TYPE_NAME, 5);
    leader.transaction(() -> leader.lookupByRID(deleted, true).asDocument().delete());
    waitForAllServers();
    for (int server = 0; server < getServerCount(); server++) {
      final int serverIndex = server;
      assertThat(entriesOn(serverIndex, TYPE_NAME)).isEqualTo(VECTORS - 1L);
      final RID found = withResyncRetry(serverIndex, db -> nearest(db, TYPE_NAME, embedding(5)));
      assertThat(found).as("server %d", serverIndex).isNotEqualTo(deleted);
    }
  }

  @Test
  void theAutomaticTrainingOnTheLeaderReachesTheFollowers() throws InterruptedException {
    final int leaderIndex = findLeaderIndex();
    assertThat(leaderIndex).as("a Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());
    leader.command("sql", "CREATE DOCUMENT TYPE " + AUTO_TYPE + " BUCKETS 1");
    leader.command("sql", "CREATE PROPERTY " + AUTO_TYPE + ".id INTEGER");
    leader.command("sql", "CREATE PROPERTY " + AUTO_TYPE + ".embedding ARRAY_OF_FLOATS");
    leader.command("sql", "CREATE INDEX ON " + AUTO_TYPE + " (embedding) LSM_IVF_VECTOR METADATA {dimensions: " + DIMENSIONS
        + ", similarity: 'EUCLIDEAN', targetPostingSize: 100, autoTrainThreshold: 500, nprobe: 4}");
    insert(leader, AUTO_TYPE, 0, 400);
    waitForAllServers();
    for (int server = 0; server < getServerCount(); server++)
      assertThat(trainedOn(server, AUTO_TYPE)).isFalse();

    insert(leader, AUTO_TYPE, 400, 200);
    // The training runs on the leader's query pool after the commit that crossed the threshold: wait for it before the replicas
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!trainedOn(leaderIndex, AUTO_TYPE) && System.nanoTime() < deadline)
      Thread.sleep(20);
    waitForAllServers();
    for (int server = 0; server < getServerCount(); server++) {
      final int serverIndex = server;
      assertThat(trainedOn(serverIndex, AUTO_TYPE)).as("trained on server %d", serverIndex).isTrue();
      assertThat(centroidsOn(serverIndex, AUTO_TYPE)).isEqualTo(6);
      assertThat(entriesOn(serverIndex, AUTO_TYPE)).isEqualTo(600L);
      for (int id = 0; id < 600; id += 59) {
        final int record = id;
        final RID expected = withResyncRetry(serverIndex, db -> recordOf(db, AUTO_TYPE, record));
        final RID found = withResyncRetry(serverIndex, db -> nearest(db, AUTO_TYPE, embedding(record)));
        assertThat(found).as("server %d must find record %d", serverIndex, record).isEqualTo(expected);
      }
    }
  }

  /**
   * The removal record that keeps a build chunk from replaying a stale entry over a newer write lives in the leader's JVM, and a
   * follower's write reaches the leader as page bytes the state machine applies, with no replay: a follower's delete or update
   * committed into an open chunk would be overwritten by the build. It cannot commit there: the build runs in the leader's schema
   * session, which makes the leader exclusive on the database, so the follower's transaction is refused, retryably, while the
   * build runs, and commits after it. Paused in the second chunk (records 500-999), after the scan has passed the two records the
   * follower writes, so a write that did land would be replayed over by the chunk's stale entries: a ghost of the deleted record
   * and the updated record found only by its old vector.
   */
  @Test
  void aFollowerWriteDuringABuildOnTheLeaderIsRefusedUntilTheBuildEnds() {
    final int leaderIndex = findLeaderIndex();
    assertThat(leaderIndex).as("a Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final int followerIndex = (leaderIndex + 1) % getServerCount();
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());
    final Database follower = getServerDatabase(followerIndex, getDatabaseName());
    leader.command("sql", "CREATE DOCUMENT TYPE " + BUILD_TYPE + " BUCKETS 1");
    leader.command("sql", "CREATE PROPERTY " + BUILD_TYPE + ".id INTEGER");
    leader.command("sql", "CREATE PROPERTY " + BUILD_TYPE + ".embedding ARRAY_OF_FLOATS");
    insert(leader, BUILD_TYPE, 0, VECTORS);
    waitForAllServers();
    final RID deleted = recordOf(leader, BUILD_TYPE, 600);
    final RID updated = recordOf(leader, BUILD_TYPE, 610);
    final float[] moved = embedding(100_000);

    final AtomicBoolean fired = new AtomicBoolean();
    final AtomicBoolean writerEnded = new AtomicBoolean();
    final AtomicReference<Throwable> duringBuild = new AtomicReference<>();
    final AtomicReference<AssertionError> tooSlow = new AtomicReference<>();
    final AtomicBoolean deletedDuringBuild = new AtomicBoolean();
    final Index.BuildIndexCallback callback = (document, indexed) -> {
      if (indexed != 750 || !fired.compareAndSet(false, true))
        return;
      final Thread writer = new Thread(() -> {
        try {
          writeOnFollower(follower, deleted, updated, moved, 1);
        } catch (final Throwable t) {
          duringBuild.set(t);
        }
      });
      final StallAwareStopwatch stopwatch = StallAwareStopwatch.start();
      writer.start();
      try {
        // Hang detector only: a commit held until the build ends never ends, since the build waits here for it
        writer.join(WRITER_HANG_MS);
      } catch (final InterruptedException e) {
        throw new IllegalStateException(e);
      }
      writerEnded.set(!writer.isAlive());
      if (writerEnded.get())
        try {
          stopwatch.assertGaveUpWithin(WRITER_REFUSED_MS, "refused during the build rather than held until it ends");
        } catch (final AssertionError e) {
          tooSlow.set(e);
        }
      deletedDuringBuild.set(!leader.existsRecord(deleted));
    };
    leader.getSchema().buildTypeIndex(BUILD_TYPE, new String[] { "embedding" }).withIVFVectorType().withDimensions(DIMENSIONS)
        .withSimilarity("EUCLIDEAN").withNlist(10).withNprobe(4).withCallback(callback).withBatchSize(500).create();

    assertThat(fired.get()).isTrue();
    assertThat(writerEnded.get()).as("the follower's commit is answered while the build runs, not held until it ends (hung)")
        .isTrue();
    if (tooSlow.get() != null)
      throw tooSlow.get();
    assertThat(deletedDuringBuild.get()).as("the follower's delete must not reach the leader inside the build").isFalse();
    assertThat(hasCause(duringBuild.get(), NeedRetryException.class))
        .as("the follower's commit is refused retryably, got %s", duringBuild.get()).isTrue();

    // After the build the same write commits, and every node's index answers it
    writeOnFollower(follower, deleted, updated, moved, 10);
    waitForAllServers();
    for (int server = 0; server < getServerCount(); server++) {
      final int serverIndex = server;
      assertThat(entriesOn(serverIndex, BUILD_TYPE)).as("server %d", serverIndex).isEqualTo(VECTORS - 1L);
      final RID byNewVector = withResyncRetry(serverIndex, db -> nearest(db, BUILD_TYPE, moved));
      assertThat(byNewVector).as("server %d", serverIndex).isEqualTo(updated);
      final RID byDeletedVector = withResyncRetry(serverIndex, db -> nearest(db, BUILD_TYPE, embedding(600)));
      assertThat(byDeletedVector).as("server %d", serverIndex).isNotEqualTo(deleted);
    }
  }

  /**
   * A build asked directly of the index outside CREATE INDEX or REBUILD INDEX has no leader-exclusive window around it, so a write
   * of another node could commit after the scan passed its record, unseen by the leader's removal record. That holds for a build in
   * chunks, in one transaction, or sharing the caller's transaction alike: each is refused on every node before it touches
   * anything, and the index keeps answering.
   */
  @Test
  void aBuildOutsideTheLeadersSchemaSessionIsRefused() {
    final int leaderIndex = findLeaderIndex();
    assertThat(leaderIndex).as("a Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());
    leader.command("sql", "CREATE DOCUMENT TYPE " + BUILD_TYPE + " BUCKETS 1");
    leader.command("sql", "CREATE PROPERTY " + BUILD_TYPE + ".id INTEGER");
    leader.command("sql", "CREATE PROPERTY " + BUILD_TYPE + ".embedding ARRAY_OF_FLOATS");
    insert(leader, BUILD_TYPE, 0, 500);
    leader.command("sql", "CREATE INDEX ON " + BUILD_TYPE + " (embedding) LSM_IVF_VECTOR METADATA {dimensions: " + DIMENSIONS
        + ", similarity: 'EUCLIDEAN', nlist: 4, nprobe: 4}");
    waitForAllServers();

    for (int server = 0; server < getServerCount(); server++) {
      final Database db = getServerDatabase(server, getDatabaseName());
      final LSMIVFVectorIndex index = ivf(db, BUILD_TYPE);
      assertThatThrownBy(() -> index.build(100, false, null)).as("chunked, server %d", server).isInstanceOf(IndexException.class)
          .hasMessageContaining("REBUILD INDEX");
      assertThatThrownBy(() -> index.build(0, false, null)).as("one transaction, server %d", server)
          .isInstanceOf(IndexException.class).hasMessageContaining("REBUILD INDEX");
      assertThatThrownBy(() -> db.transaction(() -> index.build(100, true, null))).as("caller's transaction, server %d", server)
          .isInstanceOf(IndexException.class).hasMessageContaining("REBUILD INDEX");
      assertThat(entriesOn(server, BUILD_TYPE)).isEqualTo(500L);
      final RID expected = recordOf(db, BUILD_TYPE, 7);
      final RID found = withResyncRetry(server, d -> nearest(d, BUILD_TYPE, embedding(7)));
      assertThat(found).as("server %d", server).isEqualTo(expected);
    }
  }

  static void writeOnFollower(final Database follower, final RID deleted, final RID updated, final float[] moved, final int attempts) {
    follower.transaction(() -> {
      follower.lookupByRID(deleted, true).asDocument().delete();
      final MutableDocument doc = follower.lookupByRID(updated, true).asDocument().modify();
      doc.set("embedding", moved);
      doc.save();
    }, false, attempts);
  }

  static boolean hasCause(Throwable t, final Class<? extends Throwable> type) {
    for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause())
      if (type.isInstance(t))
        return true;
    return false;
  }

  boolean trainedOn(final int server, final String type) {
    final Boolean trained = withResyncRetry(server, db -> ivf(db, type).isTrained());
    return trained;
  }

  int centroidsOn(final int server, final String type) {
    final Integer centroids = withResyncRetry(server, db -> ivf(db, type).getCentroidCount());
    return centroids;
  }

  long entriesOn(final int server, final String type) {
    final Long entries = withResyncRetry(server, db -> ivf(db, type).countEntries());
    return entries;
  }

  static void insert(final Database database, final String type, final int from, final int count) {
    database.transaction(() -> {
      for (int i = from; i < from + count; i++)
        database.newDocument(type).set("id", i, "embedding", embedding(i)).save();
    });
  }

  static LSMIVFVectorIndex ivf(final Database db, final String type) {
    final TypeIndex typeIndex = db.getSchema().getType(type).getPolymorphicIndexByProperties("embedding");
    return (LSMIVFVectorIndex) typeIndex.getIndexesOnBuckets()[0];
  }

  static RID nearest(final Database db, final String type, final float[] query) {
    try (final ResultSet rs = db.query("sql", "SELECT expand(vector.neighbors('" + type + "[embedding]', ?, 1))", query)) {
      return rs.hasNext() ? rs.next().getIdentity().orElse(null) : null;
    }
  }

  static RID recordOf(final Database db, final String type, final int id) {
    try (final ResultSet rs = db.query("sql", "SELECT @rid AS rid FROM " + type + " WHERE id = ?", id)) {
      return rs.next().getProperty("rid");
    }
  }

  static float[] embedding(final int id) {
    final Random random = new Random(0x1F5L * 31 + id);
    final float[] vector = new float[DIMENSIONS];
    for (int d = 0; d < DIMENSIONS; d++)
      vector[d] = (float) random.nextGaussian();
    return vector;
  }
}
