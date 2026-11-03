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
package com.arcadedb.index.ivfvector;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.engine.BasePage;
import com.arcadedb.engine.ImmutablePage;
import com.arcadedb.engine.PageId;
import com.arcadedb.engine.PageManager;
import com.arcadedb.engine.PublicationTestSupport;
import com.arcadedb.exception.CommandSQLParsingException;
import com.arcadedb.index.Index;
import com.arcadedb.index.vector.VectorUtils;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.MultiTypeIndexes;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.TypeLSMIVFVectorIndexBuilder;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests of the adversarial review of {@code LSM_IVF_VECTOR} (docs/spork/workstreams/ivf-vector.md, "Review findings and
 * fixes"): reads overlapping a publication, an update inside the writing transaction, the automatic training under concurrent
 * writers, the single-flight centroid reload, the uncommitted training kept off the shared field, the distance scale of pending
 * entries, the logical index that mixes index kinds and the training sample that capped the lists; and of its second round: reads that
 * wait out a publication holding no lock (a replicated apply), a search scoped to its own file rather than to every commit of the JVM,
 * the retraining as the index grows and the footprint of an allow-list; and of its fifth round: writers under REPEATABLE_READ, and
 * builds whose chunks overlap writes to records they have scanned (the database lock off).
 * <p>
 * A reader overlapping a publication is staged with the postings file's page-read observer: it answers, once, the page a reader
 * would have met in the window (a page past the file's end, which reads as zeros, or a header read just before a training) and
 * moves the publication sequence as the commit would have.
 */
class LSMIVFVectorIndexReviewTest extends TestHelper {

  @AfterEach
  void removeObserver() {
    IVFPostingsComponent.setPageReadObserver(null);
  }

  // ---------------------------------------------------------------- blocker: reads overlapping a publication

  /**
   * A scan that follows a link into a page of a commit still being published reads zeros: count 0 and next page 0, the header. It
   * used to walk on into the header as if it were a posting page (an endless loop, an exception or foreign entries, by the
   * dimensions); now a link to page 0 is never followed and the search is read again once the publication is over.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aScanThatMeetsAPageStillBeingPublishedReadsTheSearchAgain() {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(101), 5_000, dims);
    final List<RID> rids = createTypeAndInsert("Torn", vectors, 1_000, b -> b.withNlist(1));
    final LSMIVFVectorIndex index = ivfIndex("Torn");
    final IVFPostingsComponent component = index.getPostingsComponent();

    // The second page of the one list, and an entry it holds
    final int[] second = new int[1];
    database.transaction(() -> {
      final IVFPostingsComponent.Header header = component.readHeader();
      second[0] = component.readPage(component.headPage(header, 0)).readInt(IVFPostingsComponent.P_NEXT);
    });
    assertThat(second[0]).isPositive();
    final int perPage = (component.contentSize() - IVFPostingsComponent.P_ENTRIES) / index.getEntrySize();
    final int probe = perPage + 17;

    final AtomicBoolean fired = new AtomicBoolean();
    final PageManager pageManager = ((DatabaseInternal) database).getPageManager();
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != second[0] || !fired.compareAndSet(false, true))
        return page;
      PublicationTestSupport.publicationOverlapped(pageManager);
      return zeroPage(component, pageNumber);
    });

    final long retried = index.getStats().get("retriedSearches");
    final List<Pair<RID, Float>> found = search(index, vectors.get(probe), 5, 1);
    assertThat(fired.get()).isTrue();
    assertThat(found).hasSize(5);
    assertThat(found.getFirst().getFirst()).isEqualTo(rids.get(probe));
    assertThat(new HashSet<>(found.stream().map(Pair::getFirst).toList())).hasSize(5);
    assertThat(index.getStats().get("retriedSearches")).isGreaterThan(retried);
  }

  /**
   * A training that commits between the centroids a search resolved and the header it reads made the list ids of the old set index
   * the directory of the new one: the search scanned another list than the one it chose. Run on two identical types (k-means is
   * seeded, so both train the same lists), one query per cluster: whichever cluster the new layout puts in list 1 was missed.
   */
  @Test
  void aTrainingBetweenTheCentroidsAndTheHeaderIsReadAgain() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = twoClusters(new Random(102), 2_000, dims);
    final List<RID> first = createUntrainedTypeAndInsert("TwinA", vectors);
    final List<RID> second = createUntrainedTypeAndInsert("TwinB", vectors);

    // vectors.get(0) is in cluster 0, vectors.get(1) in cluster 1
    assertThat(clusterOf(first, searchWithATrainingInTheWindow("TwinA", vectors.get(0)), 2)).isZero();
    assertThat(clusterOf(second, searchWithATrainingInTheWindow("TwinB", vectors.get(1)), 2)).isOne();
  }

  /**
   * A centroid load that met the header of a training whose sections were not published yet (they reach past the committed page count,
   * so they read as zeros) cached zero centroids, or, once the round-1 fix made it read again, threw after eight attempts that a
   * publication holding no page-manager lock (a replicated apply, #10941) burned in microseconds. Staged as that apply: the publication
   * held open without the lock for 300 ms while the committed header names fine centroids past the file. The load now waits for the
   * sections, bounded by time, and answers the trained set.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aCentroidLoadWaitsForTheSectionsOfAHeaderStillBeingPublished() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = fourClusters(new Random(103), 2_000, dims);
    final List<RID> rids = createTypeAndInsert("Load", vectors, 500, b -> b.withNlist(4));
    reopenDatabase();
    final LSMIVFVectorIndex index = ivfIndex("Load");
    final IVFPostingsComponent component = index.getPostingsComponent();
    final int pastTheEnd = inTransaction(component::getTotalPages) + 100;

    final AtomicBoolean inFlight = new AtomicBoolean(true);
    final AtomicInteger served = new AtomicInteger();
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != 0 || !inFlight.get())
        return page;
      served.incrementAndGet();
      return withInt(page, IVFPostingsComponent.H_FINE_PAGE, pastTheEnd);
    });

    final Thread apply = replicatedApplyFor(300, inFlight);
    try {
      // One probe per query: the right list only if the resident centroids are the trained ones
      for (int i = 0; i < 4; i++)
        assertThat(clusterOf(rids, search(index, vectors.get(i), 1, 1).getFirst().getFirst(), 4)).as("query of cluster %d", i).isEqualTo(i);
    } finally {
      apply.join();
    }
    assertThat(served.get()).isPositive();
  }

  // ---------------------------------------------------------------- major: an update inside the writing transaction

  /** An update of a vector inside a transaction answered the record twice: once for its committed vector, once for its new one. */
  @Test
  void anUpdateInsideTheTransactionAnswersTheRecordOnceForItsNewVector() {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(104), 1_000, dims);
    final List<RID> rids = createTypeAndInsert("Upd", vectors, 500, b -> b.withNlist(4));
    final LSMIVFVectorIndex index = ivfIndex("Upd");
    final RID updated = rids.get(42);
    final float[] moved = randomVectors(new Random(105), 1, dims).getFirst();

    database.transaction(() -> {
      final MutableDocument doc = updated.asDocument().modify();
      doc.set("embedding", moved);
      doc.save();

      // The probe and the rerank path
      assertNoStaleCopy(index.findNeighbors(vectors.get(42), 5, 4, -1, null), updated, vectors.get(42), moved);
      // The exact path of a small allow-list, which reads the record of the transaction
      final Set<RID> allowed = new HashSet<>(rids.subList(40, 50));
      assertNoStaleCopy(index.findNeighbors(vectors.get(42), 5, 4, -1, allowed), updated, vectors.get(42), moved);
      // The new vector finds it
      assertThat(index.findNeighbors(moved, 1, 4, -1, null).getFirst().getFirst()).isEqualTo(updated);
    });
  }

  private static void assertNoStaleCopy(final List<Pair<RID, Float>> found, final RID updated, final float[] old, final float[] moved) {
    int copies = 0;
    for (final Pair<RID, Float> pair : found)
      if (pair.getFirst().equals(updated)) {
        copies++;
        assertThat(pair.getSecond()).as("the distance of the new vector, not the old one's").isGreaterThan(0.2f);
      }
    assertThat(copies).isLessThanOrEqualTo(1);
    assertThat(found.stream().map(Pair::getFirst).distinct().count()).isEqualTo(found.size());
  }

  // ---------------------------------------------------------------- major: automatic training under concurrent writers

  /**
   * The automatic training re-populated in one transaction that every concurrent commit beat on the header page: under a steady
   * stream of writers it never won, and each failed round cost the writer that ran it a full re-population. Now it runs on the query
   * pool, and its last attempt holds an explicit lock: it trains while the writers keep committing.
   */
  @Test
  @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void theAutomaticTrainingWinsAgainstASteadyStreamOfWriters() throws Exception {
    final int dims = 16;
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType("Busy");
      type.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    });
    createIndex("Busy", dims, b -> b.withNlist(0).withAutoTrainThreshold(3_000).withTargetPostingSize(500));
    final LSMIVFVectorIndex index = ivfIndex("Busy");
    insertInto("Busy", randomVectors(new Random(106), 2_900, dims), 500);
    assertThat(index.isTrained()).isFalse();

    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicInteger written = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final List<Thread> writers = new ArrayList<>();
    for (int w = 0; w < 4; w++) {
      final Random random = new Random(200 + w);
      final Thread writer = new Thread(() -> {
        try {
          while (!stop.get()) {
            final float[] v = randomVectors(random, 1, dims).getFirst();
            database.transaction(() -> {
              final MutableDocument doc = database.newDocument("Busy");
              doc.set("embedding", v);
              doc.save();
            }, false, 50);
            written.incrementAndGet();
          }
        } catch (final Throwable t) {
          failure.compareAndSet(null, t);
        }
      });
      writers.add(writer);
      writer.start();
    }

    boolean trained = false;
    try {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < deadline && failure.get() == null && !(trained = index.isTrained()))
        Thread.sleep(20);
    } finally {
      stop.set(true);
      for (final Thread writer : writers)
        writer.join();
    }
    assertThat(failure.get()).isNull();
    assertThat(trained).as("trained while %d records were written by concurrent writers", written.get()).isTrue();

    waitForIdleTraining(index);
    final long records = database.countType("Busy", false);
    assertThat(index.countEntries()).isEqualTo(records);
  }

  // ---------------------------------------------------------------- major: the centroid reload

  /** Every search that met stale centroids loaded its own copy (and started its own graph build): one loader now, the others wait. */
  @Test
  void concurrentSearchesLoadTheCentroidsOnce() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = fourClusters(new Random(107), 2_000, dims);
    createTypeAndInsert("Herd", vectors, 500, b -> b.withNlist(4));
    reopenDatabase();
    final LSMIVFVectorIndex index = ivfIndex("Herd");
    final IVFPostingsComponent component = index.getPostingsComponent();
    final int finePage = inTransaction(() -> component.readHeader().finePage());

    final AtomicInteger loads = new AtomicInteger();
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c == component && pageNumber == finePage) {
        loads.incrementAndGet();
        // A load that takes a while, as one of a large set does: every other search arrives while it runs
        try {
          Thread.sleep(200);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return page;
    });

    final List<Thread> searchers = new ArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    for (int t = 0; t < 6; t++) {
      final float[] query = vectors.get(t);
      final Thread searcher = new Thread(() -> {
        try {
          search(index, query, 1, 1);
        } catch (final Throwable e) {
          failure.compareAndSet(null, e);
        }
      });
      searchers.add(searcher);
      searcher.start();
    }
    for (final Thread searcher : searchers)
      searcher.join();
    assertThat(failure.get()).isNull();
    assertThat(loads.get()).isEqualTo(1);
  }

  /**
   * A REPEATABLE_READ transaction that pinned the header before a training wrote the older set back into the shared field, and the
   * next READ_COMMITTED search loaded the newer one again: the two flipped it on every search. The older set is now kept as the
   * previous one and the field never goes back.
   */
  @Test
  void aRepeatableReadSearchOnAnOlderLayoutDoesNotDowngradeTheResidentCentroids() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = fourClusters(new Random(108), 2_000, dims);
    final List<RID> rids = createUntrainedTypeAndInsert("Flip", vectors);
    final LSMIVFVectorIndex index = ivfIndex("Flip");
    assertThat(index.getCentroidCount()).isEqualTo(1);

    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    database.begin();
    try {
      // Pins the untrained header
      assertThat(clusterOf(rids, index.findNeighbors(vectors.get(0), 1, 1, -1, null).getFirst().getFirst(), 4)).isZero();

      runOnAnotherThread(() -> database.transaction(() -> index.build(0, true, null)));
      assertThat(index.getStats().get("centroids")).isEqualTo(4L);

      assertThat(clusterOf(rids, index.findNeighbors(vectors.get(1), 1, 1, -1, null).getFirst().getFirst(), 4)).isOne();
      assertThat(index.getStats().get("centroids")).as("the resident set stays the newest").isEqualTo(4L);
    } finally {
      database.rollback();
      database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    }
  }

  // ---------------------------------------------------------------- minor: the uncommitted training

  /** A training put its set in the shared field before its transaction committed: a rolled back training stayed resident. */
  @Test
  void aRolledBackTrainingLeavesTheResidentCentroidsAlone() {
    final int dims = 16;
    final List<float[]> vectors = fourClusters(new Random(109), 2_000, dims);
    createUntrainedTypeAndInsert("Rollback", vectors);
    final LSMIVFVectorIndex index = ivfIndex("Rollback");
    assertThat(index.getCentroidCount()).isEqualTo(1);

    database.begin();
    index.build(0, true, null);
    // Inside its own transaction the training is what its writes see
    assertThat(index.getCentroidCount()).isEqualTo(4);
    database.rollback();

    assertThat(index.getStats().get("centroids")).isEqualTo(1L);
    assertThat(index.getStats().get("trained")).isEqualTo(0L);
    assertThat(index.getCentroidCount()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- minor: the distance scale of pending entries

  /**
   * With {@code rerank: 0} on a BINARY+INT8 index the committed entries are answered on the Hamming proxy, and the pending ones used
   * to be answered on the exact distance: two scales sorted together. A pending entry now gets the distance it gets once committed.
   */
  @Test
  void aPendingEntryIsScoredOnTheScaleOfTheCommittedOnes() {
    final int dims = 32;
    final List<float[]> vectors = randomVectors(new Random(110), 1_000, dims);
    createTypeAndInsert("Scale", vectors, 500, b -> b.withNlist(4));
    final LSMIVFVectorIndex index = ivfIndex("Scale");
    final float[] fresh = randomVectors(new Random(111), 1, dims).getFirst();
    final float[] query = fresh.clone();
    query[0] += 0.3f;

    final float[] pending = new float[1];
    final RID[] rid = new RID[1];
    database.transaction(() -> {
      final MutableDocument doc = database.newDocument("Scale");
      doc.set("embedding", fresh);
      doc.save();
      rid[0] = doc.getIdentity();
      pending[0] = distanceOf(index.findNeighbors(query, 10, 4, 0, 0, null), rid[0]);
    });
    final float committed = inTransaction(() -> distanceOf(index.findNeighbors(query, 10, 4, 0, 0, null), rid[0]));
    assertThat(pending[0]).isEqualTo(committed);
  }

  private static float distanceOf(final List<Pair<RID, Float>> found, final RID rid) {
    for (final Pair<RID, Float> pair : found)
      if (pair.getFirst().equals(rid))
        return pair.getSecond();
    throw new AssertionError(rid + " not found in " + found);
  }

  // ---------------------------------------------------------------- minor: a logical index over both kinds

  /** A logical index whose first member is IVF answered from its IVF members only and skipped the check of the others. */
  @Test
  void aLogicalIndexMixingIndexKindsIsRefused() {
    database.transaction(() -> {
      for (final String name : List.of("Graph", "Ivf", "Plain")) {
        final DocumentType type = database.getSchema().createDocumentType(name);
        type.createProperty("e", Type.ARRAY_OF_FLOATS);
        type.createProperty("name", Type.STRING);
      }
    });
    database.command("sql", "CREATE INDEX ON Graph (e) LSM_VECTOR METADATA {dimensions: 2, similarity: 'COSINE'}");
    database.command("sql", "CREATE INDEX ON Ivf (e) LSM_IVF_VECTOR METADATA {dimensions: 2, similarity: 'COSINE', nlist: 1}");
    database.command("sql", "CREATE INDEX ON Plain (name) NOTUNIQUE");
    database.transaction(() -> {
      database.command("sql", "INSERT INTO Graph SET e = [1.0, 0.0]").close();
      database.command("sql", "INSERT INTO Ivf SET e = [0.9, 0.1]").close();
    });

    register("mixed", List.of("Ivf", "Graph"), List.of("e"), List.of("Ivf[e]", "Graph[e]"));
    assertThatThrownBy(() -> neighbors("mixed")).isInstanceOf(CommandSQLParsingException.class).hasMessageContaining("mixes");

    register("notVector", List.of("Ivf", "Plain"), List.of("e"), List.of("Ivf[e]", "Plain[name]"));
    assertThatThrownBy(() -> neighbors("notVector")).isInstanceOf(CommandSQLParsingException.class)
        .hasMessageContaining("not a vector index");
  }

  private void register(final String name, final List<String> labels, final List<String> properties, final List<String> indexes) {
    MultiTypeIndexes.register(database.getSchema(),
        new MultiTypeIndexes.Entry(name, MultiTypeIndexes.Kind.VECTOR, false, labels, properties, indexes, List.of()));
  }

  private void neighbors(final String index) {
    database.transaction(() -> {
      try (final ResultSet rs = database.query("sql", "SELECT expand(vector.neighbors('" + index + "', [1.0, 0.0], 2))")) {
        while (rs.hasNext())
          rs.next();
      }
    });
  }

  // ---------------------------------------------------------------- minor: the training sample

  /** k-means cannot make more lists than it has points: a sample smaller than the lists wanted made fewer, longer lists. */
  @Test
  void theTrainingSampleGrowsWithTheListsWanted() {
    final int dims = 8;
    final List<float[]> vectors = randomVectors(new Random(112), 3_000, dims);
    createTypeAndInsert("Sample", vectors, 1_000, b -> b.withNlist(100).withTrainingSample(50));
    assertThat(ivfIndex("Sample").getCentroidCount()).isEqualTo(100);
  }

  // ---------------------------------------------------------------- round 2: reads that overlap a publication, scoped to the file

  /**
   * A scan that meets a page of a commit still being applied on a follower (#10941: the apply holds no page-manager lock) spent its
   * eight attempts in microseconds, since the wait for the lock returned at once, then its two locked attempts inside the same apply,
   * and threw a {@code ConcurrentModificationException} at a read-only query. Now the search waits, bounded by time, until the page it
   * links to is there.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aSearchWaitsOutAPublicationThatHoldsNoLock() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(113), 5_000, dims);
    final List<RID> rids = createTypeAndInsert("Apply", vectors, 1_000, b -> b.withNlist(1));
    final LSMIVFVectorIndex index = ivfIndex("Apply");
    final IVFPostingsComponent component = index.getPostingsComponent();
    final int second = inTransaction(() -> component.readPage(component.headPage(component.readHeader(), 0)).readInt(IVFPostingsComponent.P_NEXT));
    assertThat(second).isPositive();
    final int probe = (component.contentSize() - IVFPostingsComponent.P_ENTRIES) / index.getEntrySize() + 17;

    final AtomicBoolean inFlight = new AtomicBoolean(true);
    final AtomicInteger zeroed = new AtomicInteger();
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != second || !inFlight.get())
        return page;
      zeroed.incrementAndGet();
      return zeroPage(component, pageNumber);
    });

    final long retried = index.getStats().get("retriedSearches");
    final Thread apply = replicatedApplyFor(300, inFlight);
    final List<Pair<RID, Float>> found;
    try {
      found = search(index, vectors.get(probe), 5, 1);
    } finally {
      apply.join();
    }
    assertThat(zeroed.get()).isPositive();
    assertThat(found).hasSize(5);
    assertThat(found.getFirst().getFirst()).isEqualTo(rids.get(probe));
    assertThat(found.stream().map(Pair::getFirst).distinct().count()).isEqualTo(5);
    assertThat(index.getStats().get("retriedSearches")).isGreaterThan(retried);
  }

  /**
   * The publication sequence the search validated against is one counter for the whole JVM: every commit anywhere (another type,
   * another database) made the search run again, and after eight runs it ran under the page-manager lock, which froze the commits of
   * every database in the process for the length of the scan. A commit elsewhere during the scan now costs the search nothing, and a
   * commit never waits for a search.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aSearchIsNotReadAgainForCommitsElsewhereAndHoldsNoGlobalLock() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(114), 2_000, dims);
    final List<RID> rids = createTypeAndInsert("Quiet", vectors, 500, b -> b.withNlist(1));
    database.transaction(() -> database.getSchema().createDocumentType("Elsewhere"));
    final LSMIVFVectorIndex index = ivfIndex("Quiet");
    final IVFPostingsComponent component = index.getPostingsComponent();
    // Read once per run of the search (the directory entry of the one list probed): a commit elsewhere during every run
    final int directory = inTransaction(() -> component.readHeader().directoryPage());

    final Thread searcher = Thread.currentThread();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger stalled = new AtomicInteger();
    final List<Thread> committers = new ArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != directory || Thread.currentThread() != searcher || commits.incrementAndGet() > 10)
        return page;
      // A commit somewhere else in the JVM while the scan runs
      final Thread committer = new Thread(() -> {
        try {
          database.transaction(() -> database.newDocument("Elsewhere").set("n", 1).save());
        } catch (final Throwable t) {
          failure.compareAndSet(null, t);
        }
      });
      committers.add(committer);
      committer.start();
      try {
        committer.join(5_000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (committer.isAlive())
        stalled.incrementAndGet();
      return page;
    });

    final long retried = index.getStats().get("retriedSearches");
    final List<Pair<RID, Float>> found = search(index, vectors.get(7), 3, 1);
    IVFPostingsComponent.setPageReadObserver(null);
    for (final Thread committer : committers)
      committer.join();

    assertThat(failure.get()).isNull();
    assertThat(commits.get()).isPositive();
    assertThat(stalled.get()).as("commits that waited for the search").isZero();
    assertThat(index.getStats().get("retriedSearches") - retried).as("searches read again for commits to other files").isZero();
    assertThat(found.getFirst().getFirst()).isEqualTo(rids.get(7));
  }

  /**
   * Without the publication sequence a torn read can meet one record twice: its old entry live on a page read before an update's
   * publication and its new one on a page read after. The answer holds a record once, at its best distance.
   */
  @Test
  void aPositionMetTwiceByATornReadIsAnsweredOnce() {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(115), 1_000, dims);
    final List<RID> rids = createTypeAndInsert("Twice", vectors, 500, b -> b.withNlist(1));
    final LSMIVFVectorIndex index = ivfIndex("Twice");
    final IVFPostingsComponent component = index.getPostingsComponent();
    final RID updated = rids.get(42);
    final float[] moved = vectors.get(42).clone();
    moved[0] += 0.01f;
    database.transaction(() -> {
      final MutableDocument doc = updated.asDocument().modify();
      doc.set("embedding", moved);
      doc.save();
    });

    // The old entry of the record, dead now on the one posting page, read live as before the update was published
    final int head = inTransaction(() -> component.headPage(component.readHeader(), 0));
    final int entrySize = index.getEntrySize();
    final int deadOffset = inTransaction(() -> {
      final BasePage page = component.readPage(head);
      for (int i = 0, count = page.readInt(IVFPostingsComponent.P_COUNT); i < count; i++) {
        final int offset = IVFPostingsComponent.P_ENTRIES + i * entrySize;
        if (page.readLong(offset + IVFPostingsComponent.E_POSITION) == updated.getPosition() && page.readByte(offset + IVFPostingsComponent.E_LIVE) == 0)
          return offset;
      }
      throw new AssertionError("no dead entry for " + updated);
    });
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != head)
        return page;
      final BasePage copy = copyOf(page);
      copy.getContent().put(BasePage.PAGE_HEADER_SIZE + deadOffset + IVFPostingsComponent.E_LIVE, (byte) 1);
      return copy;
    });

    final List<Pair<RID, Float>> found = search(index, vectors.get(42), 10, 1);
    assertThat(found.stream().filter(p -> p.getFirst().equals(updated)).count()).isOne();
    assertThat(found.stream().map(Pair::getFirst).distinct().count()).isEqualTo(found.size());
  }

  // ---------------------------------------------------------------- round 2: the partition keeps up with the corpus

  /**
   * The automatic training sized the partition once, at the threshold, and nothing trained it again: an index created empty and then
   * loaded kept the lists of its first 10,000 vectors (50 lists) forever, and its lists grew with the corpus. It now trains again in
   * the background once its live entries reach four times what its last training saw, and the count it trained at survives a reopen.
   */
  @Test
  @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void theIndexTrainsAgainAsItGrows() throws Exception {
    final int dims = 16;
    database.transaction(() -> database.getSchema().createDocumentType("Grow").createProperty("embedding", Type.ARRAY_OF_FLOATS));
    createIndex("Grow", dims, b -> b.withNlist(0).withAutoTrainThreshold(500).withTargetPostingSize(50));
    final LSMIVFVectorIndex index = ivfIndex("Grow");
    final List<float[]> vectors = randomVectors(new Random(116), 3_400, dims);
    final long retrainingsMeter = IVFVectorIndexMetrics.INSTANCE.autoRetrainings.sum();
    final List<RID> rids = new ArrayList<>(insertInto("Grow", vectors.subList(0, 600), 100));
    waitForIdleTraining(index);
    final int first = index.getCentroidCount();
    assertThat(first).isBetween(10, 12);

    rids.addAll(insertInto("Grow", vectors.subList(600, vectors.size()), 100));
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    while (System.nanoTime() < deadline && index.getStats().get("autoRetrainings") == 0)
      Thread.sleep(20);
    assertThat(index.getStats().get("autoRetrainings")).as("trainings after the first").isPositive();
    assertThat(index.getStats().get("autoTrainings")).as("a retraining is not a first training").isOne();
    assertThat(IVFVectorIndexMetrics.INSTANCE.autoRetrainings.sum()).isGreaterThan(retrainingsMeter);
    assertThat(index.getCentroidCount()).isGreaterThanOrEqualTo(4 * first);
    assertThat(index.countEntries()).isEqualTo(database.countType("Grow", false));
    final long trainedAt = index.getStats().get("trainedAtEntries");
    assertThat(trainedAt).isGreaterThanOrEqualTo(4L * 600);
    assertThat(index.getStats().get("liveEntries")).isEqualTo(index.countEntries());
    final int probe = 3_000;
    assertThat(search(index, vectors.get(probe), 1, 4).getFirst().getFirst()).isEqualTo(rids.get(probe));

    reopenDatabase();
    assertThat(ivfIndex("Grow").getStats().get("trainedAtEntries")).isEqualTo(trainedAt);
  }

  // ---------------------------------------------------------------- round 2: the allow-list's footprint

  /**
   * The blocked bitmap's block index was a dense array sized by the largest allowed position: one record at position 20G (large records
   * leave most of a page's 2,048 positions unused) cost 160 MB per query, before the exact path that a single record takes. A filter now
   * costs what it holds: a small one is a sorted array, a sparse one indexes only its own blocks.
   */
  @Test
  void anAllowListCostsWhatItHoldsNotHowFarItReaches() {
    final int bucket = 7;
    final long far = 20_000_000_000L;

    final LSMIVFVectorIndex.PositionFilter one = LSMIVFVectorIndex.PositionFilter.of(Set.of(new RID(bucket, far)), bucket, 5_000);
    assertThat(one.bytes()).isLessThan(1_024L);
    assertThat(one.contains(far)).isTrue();
    assertThat(one.contains(far - 1)).isFalse();
    assertThat(one.contains(0)).isFalse();

    final Set<RID> spread = new HashSet<>();
    for (long i = 0; i < 10_000; i++)
      spread.add(new RID(bucket, i * 2_000_000L + (i % 511)));
    spread.add(new RID(bucket + 1, 3L));
    final LSMIVFVectorIndex.PositionFilter sparse = LSMIVFVectorIndex.PositionFilter.of(spread, bucket, 0);
    assertThat(sparse.size).isEqualTo(10_000);
    assertThat(sparse.bytes()).as("10,000 positions reaching 20G").isLessThan(4L << 20);
    for (long i = 0; i < 10_000; i++) {
      final long position = i * 2_000_000L + (i % 511);
      assertThat(sparse.contains(position)).isTrue();
      assertThat(sparse.contains(position + 1)).isFalse();
    }
    assertThat(sparse.contains(3L)).isFalse();

    // Dense positions keep the two-array-read block index
    final Set<RID> dense = new HashSet<>();
    for (long p = 0; p < 100_000; p += 3)
      dense.add(new RID(bucket, p));
    final LSMIVFVectorIndex.PositionFilter packed = LSMIVFVectorIndex.PositionFilter.of(dense, bucket, 0);
    assertThat(packed.bytes()).isLessThan(64L << 10);
    for (long p = 0; p < 100_002; p++)
      assertThat(packed.contains(p)).isEqualTo(p < 100_000 && p % 3 == 0);
  }

  // ---------------------------------------------------------------- round 3: coarse groups with no lists

  /**
   * A coarse group that owns no fine centroid (an empty range of {@code coarseStart}) still has a coarse centroid. When it was the
   * nearest group, {@code assign} searched its empty range and got back its start: past the last list when the group was the last one
   * (an {@code ArrayIndexOutOfBoundsException} reading the fine centroid {@code nlist}, or {@code nlist} itself handed to the append,
   * which wrote the directory slot after the last list), the first list of the next group otherwise. Empty groups are now skipped, and
   * a layout read back from an older file that still has them assigns and probes correctly.
   */
  @Test
  void aCoarseGroupWithNoListsIsNeverAssigned() {
    // Lists 0-1 in group 0, 2-3 in group 1, group 2 empty and last, nearest to the vector
    final IVFCentroids lastEmpty = new IVFCentroids(2, new float[] { 0, 0, 1, 0, 10, 0, 11, 0 }, new float[] { 0.5f, 0, 10.5f, 0, 100, 100 },
        new int[] { 0, 2, 4, 4 }, new float[2], 1, true);
    assertThat(lastEmpty.assign(new float[] { 100, 100 })).isEqualTo(3);

    // The two nearest groups are both empty and last: the start of their range is nlist
    final IVFCentroids twoLastEmpty = new IVFCentroids(2, new float[] { 0, 0, 1, 0, 10, 0, 11, 0 },
        new float[] { 0.5f, 0, 10.5f, 0, 100, 100, 101, 101 }, new int[] { 0, 2, 4, 4, 4 }, new float[2], 1, true);
    assertThat(twoLastEmpty.assign(new float[] { 100, 100 })).isEqualTo(3);

    // Empty groups in the middle: their start is the first list of the next group, a valid but wrong list
    final IVFCentroids middleEmpty = new IVFCentroids(2, new float[] { 0, 0, 0, 40, 200, 0, 300, 0 },
        new float[] { 0, 20, 0, 60, 0, 61, 250, 0 }, new int[] { 0, 2, 2, 2, 4 }, new float[2], 1, true);
    assertThat(middleEmpty.assign(new float[] { 0, 50 })).isEqualTo(1);

    // A probe opens at least four groups that hold lists, however many empty ones rank before them: four empty groups nearest the
    // query used to fill the minimum, so only group 0 (16 lists, enough candidates) was opened and list 16 of group 1 was missed
    final float[] fine = new float[32 * 2];
    for (int i = 0; i < 16; i++)
      fine[i * 2] = 10 * i;
    fine[16 * 2] = -4;
    for (int i = 17; i < 32; i++) {
      fine[i * 2] = 100 + i;
      fine[i * 2 + 1] = 50;
    }
    final IVFCentroids probed = new IVFCentroids(2, fine, new float[] { 0, 0, 5, 0, -1, 0, -2, 0, -3, 0, -4, 0 },
        new int[] { 0, 16, 32, 32, 32, 32, 32 }, new float[2], 1, true);
    assertThat(probed.select(new float[] { -4, 0 }, 1)).containsExactly(16);
  }

  /**
   * Lloyd's last update can leave a coarse centroid with no point of the sample (duplicates make it certain), and the training laid
   * that group out with an empty fine range. A training now drops such groups, so every coarse group it lays out owns a list.
   */
  @Test
  void aTrainingLaysOutNoEmptyCoarseGroup() {
    final int dims = 4;
    final float[][] distinct = { { 1, 0, 0, 0 }, { 0, 1, 0, 0 }, { 0, 0, 1, 0 } };
    final float[][] sample = new float[600][];
    for (int i = 0; i < sample.length; i++)
      sample[i] = distinct[i % distinct.length].clone();

    final IVFCentroids trained = IVFKMeans.train(sample, dims, 400, 5, 1, 0, new Random(42));
    assertThat(trained.coarseCount).isGreaterThan(1);
    for (int c = 0; c < trained.coarseCount; c++)
      assertThat(trained.coarseStart[c + 1]).as("fine range of coarse group %d", c).isGreaterThan(trained.coarseStart[c]);
    for (final float[] v : distinct)
      assertThat(trained.assign(v)).isBetween(0, trained.nlist - 1);
  }

  // ---------------------------------------------------------------- round 4: the build under live writers

  /**
   * Pass 2 of a build appended its entries straight onto the posting pages, the header page included, inside each chunk's
   * transaction and outside the commit lock: a writer that committed to the index while a chunk was open moved the header page on,
   * and the chunk's commit failed the page-version check, which threw the whole build away. The build now stages its entries on the
   * commit lane like a live write, so they are appended under the commit lock and a writer's commit costs it nothing.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aBuildChunkCommitsOverAWriterThatCommittedInsideIt() {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(120), 3_000, dims);
    final List<RID> rids = createTypeAndInsert("Live", vectors, 1_000, b -> b.withNlist(8).withAutoTrainThreshold(0));
    final LSMIVFVectorIndex index = ivfIndex("Live");
    final RID deleted = rids.get(10);
    final RID updated = rids.get(20);
    final float[] moved = randomVectors(new Random(121), 1, dims).getFirst();

    // In the middle of the second chunk: the first (records 0-499) is committed, the second holds 250 entries not committed yet
    final AtomicBoolean fired = new AtomicBoolean();
    final Index.BuildIndexCallback callback = (document, indexed) -> {
      if (indexed != 750 || !fired.compareAndSet(false, true))
        return;
      try {
        runOnAnotherThread(() -> database.transaction(() -> {
          database.lookupByRID(deleted, true).asDocument().delete();
          final MutableDocument doc = database.lookupByRID(updated, true).asDocument().modify();
          doc.set("embedding", moved);
          doc.save();
        }));
      } catch (final InterruptedException e) {
        throw new IllegalStateException(e);
      }
    };
    database.transaction(() -> index.build(500, false, callback), false, 1);

    assertThat(fired.get()).isTrue();
    assertThat(index.countEntries()).isEqualTo(vectors.size() - 1);
    final int nlist = index.getCentroidCount();
    assertThat(search(index, moved, 1, nlist).getFirst().getFirst()).isEqualTo(updated);
    assertThat(search(index, vectors.get(10), 10, nlist).stream().map(Pair::getFirst)).doesNotContain(deleted);
    for (int i = 0; i < vectors.size(); i += 7)
      if (i != 10 && i != 20)
        assertThat(search(index, vectors.get(i), 10, nlist).stream().map(Pair::getFirst)).as("record %d", i).contains(rids.get(i));
  }

  /**
   * The first chunk of a build also wrote the layout (the header and centroid pages), and a writer that committed while it was open
   * failed it the same way. The layout is now written in a transaction of its own whose first act is an explicit lock: the writer that
   * tries to commit meanwhile waits for it, and both commit. Run under REPEATABLE_READ, which keeps the header page the build read
   * before it writes it, so the window between the two is as wide as the test makes it (under READ_COMMITTED it runs from that write
   * to the commit: the same race, too short to stage).
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aWriterCommittingWhileTheLayoutIsWrittenWaitsForIt() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(125), 2_000, dims);
    createTypeAndInsert("Layout", vectors, 1_000, b -> b.withNlist(4).withAutoTrainThreshold(0));
    final LSMIVFVectorIndex index = ivfIndex("Layout");
    final IVFPostingsComponent component = index.getPostingsComponent();
    final float[] extra = randomVectors(new Random(126), 1, dims).getFirst();

    final Thread builder = Thread.currentThread();
    final AtomicBoolean fired = new AtomicBoolean();
    final AtomicReference<Thread> writer = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    // The build's read of the header before it writes the new layout: a writer commits an insert (an append to the header page) in
    // one attempt, so a retry is impossible by construction and a conflict surfaces as the failure: it waits for the layout's lock
    // and then commits on top of it
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != 0 || Thread.currentThread() != builder || !fired.compareAndSet(false, true))
        return page;
      final Thread w = new Thread(() -> {
        try {
          database.transaction(() -> {
            final MutableDocument doc = database.newDocument("Layout");
            doc.set("embedding", extra);
            doc.save();
          }, false, 1);
        } catch (final Throwable t) {
          failure.set(t);
        }
      });
      writer.set(w);
      w.start();
      try {
        // Long enough for an unhindered commit to land before the layout's
        w.join(1_000);
      } catch (final InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return page;
    });
    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    try {
      database.transaction(() -> index.build(500, false, null), false, 1);
    } finally {
      database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
      IVFPostingsComponent.setPageReadObserver(null);
    }
    writer.get().join();

    assertThat(fired.get()).isTrue();
    assertThat(failure.get()).isNull();
    assertThat(index.countEntries()).isEqualTo(vectors.size() + 1);
    assertThat(search(index, extra, 1, index.getCentroidCount()).getFirst().getFirst())
        .isEqualTo(database.query("sql", "SELECT FROM Layout ORDER BY @rid DESC LIMIT 1").next().getIdentity().get());
  }

  /**
   * CREATE INDEX and REBUILD INDEX on a type another thread keeps inserting into, updating and deleting from: both complete, the
   * writer is never failed by them, and afterwards every live record is found by an exhaustive search (every list probed) and the
   * index holds exactly one entry per live record.
   */
  @Test
  @Tag("slow")
  @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void createAndRebuildIndexCompleteUnderLiveIngest() throws Exception {
    createAndRebuildUnderLiveIngest("Ingest");
  }

  /**
   * {@link #createAndRebuildIndexCompleteUnderLiveIngest} with {@code arcadedb.backup.enabled=false}, which turns the database lock
   * off: CREATE INDEX and REBUILD INDEX then no longer hold writers back, so a writer updates and deletes records the open chunk of
   * the build has already scanned and commits before it. The chunk's stale entries used to replace the writer's new ones (the
   * one-live-entry-per-position locator) and bring deleted records back as live ghosts.
   */
  @Test
  @Tag("slow")
  @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void createAndRebuildIndexCompleteUnderLiveIngestWithoutTheDatabaseLock() throws Exception {
    GlobalConfiguration.BACKUP_ENABLED.setValue(false);
    reopenDatabase();
    createAndRebuildUnderLiveIngest("Unlocked");
  }

  /**
   * A writer that updates, deletes or clears the vector of records a build chunk has scanned, and commits while that chunk is still
   * open: the chunk's entries for them are stale, and replayed at its commit they replaced the writer's new entry (the locator holds
   * one live entry per position) or brought a deleted record back as a live ghost. The build now skips an entry whose record a write
   * reached after the layout.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aBuildChunkDoesNotOverwriteWritesToRecordsItHasScanned() {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(127), 3_000, dims);
    final List<RID> rids = createTypeAndInsert("Scanned", vectors, 1_000, b -> b.withNlist(8).withAutoTrainThreshold(0));
    final LSMIVFVectorIndex index = ivfIndex("Scanned");
    final RID updated = rids.get(600);
    final RID deleted = rids.get(610);
    final RID cleared = rids.get(620);
    final float[] moved = randomVectors(new Random(128), 1, dims).getFirst();

    // The second chunk (records 500-999) has scanned and staged records 500-749 and is not committed yet
    final AtomicBoolean fired = new AtomicBoolean();
    final Index.BuildIndexCallback callback = (document, indexed) -> {
      if (indexed != 750 || !fired.compareAndSet(false, true))
        return;
      try {
        runOnAnotherThread(() -> database.transaction(() -> {
          final MutableDocument doc = database.lookupByRID(updated, true).asDocument().modify();
          doc.set("embedding", moved);
          doc.save();
          database.lookupByRID(deleted, true).asDocument().delete();
          final MutableDocument emptied = database.lookupByRID(cleared, true).asDocument().modify();
          emptied.remove("embedding");
          emptied.save();
        }));
      } catch (final InterruptedException e) {
        throw new IllegalStateException(e);
      }
    };
    database.transaction(() -> index.build(500, false, callback), false, 1);

    assertThat(fired.get()).isTrue();
    assertThat(index.countEntries()).isEqualTo(vectors.size() - 2);
    final int nlist = index.getCentroidCount();
    assertThat(search(index, moved, 1, nlist).getFirst().getFirst()).isEqualTo(updated);
    assertThat(search(index, vectors.get(610), 10, nlist).stream().map(Pair::getFirst)).doesNotContain(deleted);
    assertThat(search(index, vectors.get(620), 10, nlist).stream().map(Pair::getFirst)).doesNotContain(cleared);
    for (int i = 500; i < 1_000; i += 7)
      if (i != 600 && i != 610 && i != 620)
        assertThat(search(index, vectors.get(i), 10, nlist).stream().map(Pair::getFirst)).as("record %d", i).contains(rids.get(i));
  }

  // ---------------------------------------------------------------- round 5: writers under REPEATABLE_READ

  /**
   * Under REPEATABLE_READ a write read the header page through its transaction to learn the centroid version, which pinned the page;
   * the commit replay then appended on the pinned copy, so any commit to the index in between - whatever list or record it touched -
   * failed the page-version check. A write now takes the version from the committed state, and the replay of a transaction that wrote
   * no postings page in its body releases what its body pinned (a search before the write pins the header and the lists it scanned).
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aRepeatableReadWriterCommitsOverAnotherWritersCommit() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(130), 2_000, dims);
    final List<RID> rids = createTypeAndInsert("Repeatable", vectors, 1_000, b -> b.withNlist(4).withAutoTrainThreshold(0));
    final LSMIVFVectorIndex index = ivfIndex("Repeatable");
    final List<float[]> extra = randomVectors(new Random(131), 6, dims);

    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    try {
      for (int round = 0; round < 2; round++) {
        final int r = round;
        database.begin();
        if (r == 1)
          assertThat(index.findNeighbors(vectors.get(5), 5, 4, -1, null)).isNotEmpty();
        final MutableDocument inserted = database.newDocument("Repeatable");
        inserted.set("embedding", extra.get(3 * r));
        inserted.save();
        final MutableDocument doc = database.lookupByRID(rids.get(r), true).asDocument().modify();
        doc.set("embedding", extra.get(3 * r + 1));
        doc.save();

        // Another writer commits to the same index while this transaction is open
        runOnAnotherThread(() -> database.transaction(() -> {
          final MutableDocument other = database.newDocument("Repeatable");
          other.set("embedding", extra.get(3 * r + 2));
          other.save();
        }, false, 1));

        // No retry: the commit must not conflict
        database.commit();
      }
    } finally {
      if (database.isTransactionActive())
        database.rollback();
      database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    }

    assertThat(index.countEntries()).isEqualTo(vectors.size() + 4).isEqualTo(database.countType("Repeatable", false));
    final int nlist = index.getCentroidCount();
    assertThat(search(index, extra.get(1), 1, nlist).getFirst().getFirst()).isEqualTo(rids.get(0));
    assertThat(search(index, extra.get(4), 1, nlist).getFirst().getFirst()).isEqualTo(rids.get(1));
  }

  /**
   * A REPEATABLE_READ transaction that read the index (pinning its header) before a training committed codes its writes against the
   * committed centroids: it used to code them against the layout its pinned header named, so the commit had to assign and code every
   * one of them again on the new layout, under the commit lock.
   */
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aRepeatableReadWriteIsCodedAgainstTheCommittedCentroids() throws Exception {
    final int dims = 16;
    final List<float[]> vectors = randomVectors(new Random(133), 2_000, dims);
    createTypeAndInsert("Recoded", vectors, 1_000, b -> b.withNlist(4).withAutoTrainThreshold(0));
    final LSMIVFVectorIndex index = ivfIndex("Recoded");
    final List<float[]> extra = randomVectors(new Random(134), 20, dims);
    final long trainings = index.getStats().get("trainings");
    final long recodes = index.getStats().get("replayRecodes");

    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    try {
      database.begin();
      // Pins the header page at the current layout
      assertThat(index.getCentroidCount()).isEqualTo(4);
      runOnAnotherThread(() -> database.transaction(() -> index.build(0, true, null)));
      assertThat(index.getStats().get("trainings")).isEqualTo(trainings + 1);
      for (final float[] v : extra) {
        final MutableDocument doc = database.newDocument("Recoded");
        doc.set("embedding", v);
        doc.save();
      }
      database.commit();
    } finally {
      if (database.isTransactionActive())
        database.rollback();
      database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    }

    assertThat(index.getStats().get("replayRecodes")).isEqualTo(recodes);
    assertThat(index.countEntries()).isEqualTo(vectors.size() + extra.size());
    final int nlist = index.getCentroidCount();
    for (final float[] v : extra)
      assertThat(search(index, v, 1, nlist)).isNotEmpty();
  }

  /**
   * Several threads insert and update in overlapping REPEATABLE_READ transactions, some of them searching first: every transaction
   * commits within the default retries, and the index holds one entry per live record.
   */
  @Test
  @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void overlappingRepeatableReadWritersAllCommit() throws Exception {
    final int dims = 16;
    final int threads = 4;
    final int transactions = 40;
    final List<float[]> vectors = randomVectors(new Random(132), 2_000, dims);
    createTypeAndInsert("Overlap", vectors, 1_000, b -> b.withNlist(8).withAutoTrainThreshold(0));
    final LSMIVFVectorIndex index = ivfIndex("Overlap");

    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicInteger committed = new AtomicInteger();
    final List<Thread> workers = new ArrayList<>(threads);
    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    try {
      for (int t = 0; t < threads; t++) {
        final Random random = new Random(140 + t);
        final Thread worker = new Thread(() -> {
          final List<RID> own = new ArrayList<>();
          try {
            for (int n = 0; n < transactions && failure.get() == null; n++) {
              final boolean searchFirst = n % 2 == 0;
              final List<float[]> batch = randomVectors(random, 5, dims);
              final RID target = own.isEmpty() ? null : own.get(random.nextInt(own.size()));
              final float[] moved = randomVectors(random, 1, dims).getFirst();
              final List<RID> created = new ArrayList<>();
              database.transaction(() -> {
                created.clear();
                if (searchFirst)
                  index.findNeighbors(moved, 5, 2, -1, null);
                for (final float[] v : batch) {
                  final MutableDocument doc = database.newDocument("Overlap");
                  doc.set("embedding", v);
                  doc.save();
                  created.add(doc.getIdentity());
                }
                if (target != null) {
                  final MutableDocument doc = database.lookupByRID(target, true).asDocument().modify();
                  doc.set("embedding", moved);
                  doc.save();
                }
              });
              own.addAll(created);
              committed.incrementAndGet();
            }
          } catch (final Throwable e) {
            failure.compareAndSet(null, e);
          }
        });
        workers.add(worker);
        worker.start();
      }
      for (final Thread worker : workers)
        worker.join();
    } finally {
      database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    }

    assertNoFailure(failure);
    assertThat(committed.get()).isEqualTo(threads * transactions);
    assertThat(index.countEntries()).isEqualTo(database.countType("Overlap", false))
        .isEqualTo(vectors.size() + (long) threads * transactions * 5);
  }

  private void createAndRebuildUnderLiveIngest(final String type) throws Exception {
    final int dims = 16;
    final int nlist = 8;
    final List<float[]> initial = randomVectors(new Random(122), 4_000, dims);
    database.transaction(() -> database.getSchema().createDocumentType(type).createProperty("embedding", Type.ARRAY_OF_FLOATS));
    final List<RID> live = new ArrayList<>(insertInto(type, initial, 1_000));

    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicInteger committed = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread writer = new Thread(() -> {
      final Random random = new Random(123);
      try {
        while (!stop.get()) {
          final int op = random.nextInt(4);
          final float[] v = randomVectors(random, 1, dims).getFirst();
          final RID target = live.get(random.nextInt(live.size()));
          final RID[] created = new RID[1];
          database.transaction(() -> {
            created[0] = null;
            if (op <= 1) {
              final MutableDocument doc = database.newDocument(type);
              doc.set("embedding", v);
              doc.save();
              created[0] = doc.getIdentity();
            } else if (op == 2) {
              final MutableDocument doc = database.lookupByRID(target, true).asDocument().modify();
              doc.set("embedding", v);
              doc.save();
            } else
              database.lookupByRID(target, true).asDocument().delete();
          }, false, 50);
          if (created[0] != null)
            live.add(created[0]);
          else if (op == 3)
            live.remove(target);
          committed.incrementAndGet();
        }
      } catch (final Throwable t) {
        failure.compareAndSet(null, t);
      }
    });
    writer.start();
    try {
      waitForCommits(committed, 50, failure);
      database.command("sql", "CREATE INDEX ON " + type + " (embedding) LSM_IVF_VECTOR METADATA "
          + "{dimensions: 16, similarity: 'COSINE', nlist: " + nlist + ", autoTrainThreshold: 0}");
      waitForCommits(committed, committed.get() + 50, failure);
      final String name = database.getSchema().getType(type).getPolymorphicIndexByProperties("embedding").getName();
      database.command("sql", "REBUILD INDEX `" + name + "` WITH batchSize = 500");
      waitForCommits(committed, committed.get() + 50, failure);
    } finally {
      stop.set(true);
      writer.join();
    }
    assertNoFailure(failure);

    final LSMIVFVectorIndex index = ivfIndex(type);
    assertThat(index.isTrained()).isTrue();
    assertThat(index.getCentroidCount()).isEqualTo(nlist);
    assertThat(index.countEntries()).isEqualTo(database.countType(type, false)).isEqualTo(live.size());
    database.transaction(() -> {
      final Iterator<Record> records = database.iterateType(type, false);
      while (records.hasNext()) {
        final Document doc = records.next().asDocument();
        final float[] v = VectorUtils.toFloatArray(doc.get("embedding"));
        assertThat(index.findNeighbors(v, 10, nlist, -1, null).stream().map(Pair::getFirst)).as("record %s", doc.getIdentity())
            .contains(doc.getIdentity());
      }
    });
  }

  /** A training is counted once it commits: a build that rolls back trains nothing, and the counters say so. */
  @Test
  void aTrainingThatRollsBackIsNotCounted() {
    final int dims = 16;
    createTypeAndInsert("Uncounted", randomVectors(new Random(124), 1_000, dims), 500, b -> b.withNlist(4));
    final LSMIVFVectorIndex index = ivfIndex("Uncounted");
    final long trainings = index.getStats().get("trainings");
    final long published = IVFVectorIndexMetrics.INSTANCE.trainings.sum();

    database.begin();
    index.build(0, true, null);
    database.rollback();
    assertThat(index.getStats().get("trainings")).isEqualTo(trainings);

    database.transaction(() -> index.build(0, true, null));
    assertThat(index.getStats().get("trainings")).isEqualTo(trainings + 1);
    assertThat(IVFVectorIndexMetrics.INSTANCE.trainings.sum()).isGreaterThan(published);
  }

  private static void waitForCommits(final AtomicInteger committed, final int atLeast, final AtomicReference<Throwable> failure)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (committed.get() < atLeast && failure.get() == null && System.nanoTime() < deadline)
      Thread.sleep(5);
    assertNoFailure(failure);
    assertThat(committed.get()).as("commits of the writer").isGreaterThanOrEqualTo(atLeast);
  }

  // ---------------------------------------------------------------- helpers

  /** Fails with the whole stack of what a background thread threw, causes included: the wrapper alone rarely says what went wrong. */
  private static void assertNoFailure(final AtomicReference<Throwable> failure) {
    final Throwable thrown = failure.get();
    if (thrown == null)
      return;
    final StringWriter trace = new StringWriter();
    thrown.printStackTrace(new PrintWriter(trace));
    throw new AssertionError("a background thread failed:\n" + trace, thrown);
  }

  private RID searchWithATrainingInTheWindow(final String type, final float[] query) throws Exception {
    final LSMIVFVectorIndex index = ivfIndex(type);
    final IVFPostingsComponent component = index.getPostingsComponent();
    assertThat(index.isTrained()).isFalse();
    final Thread searcher = Thread.currentThread();
    final AtomicBoolean fired = new AtomicBoolean();
    IVFPostingsComponent.setPageReadObserver((c, pageNumber, page) -> {
      if (c != component || pageNumber != 0 || Thread.currentThread() != searcher || !fired.compareAndSet(false, true))
        return page;
      // The header as the search read it, then a training that commits before its next read
      final BasePage before = copyOf(page);
      try {
        runOnAnotherThread(() -> database.transaction(() -> index.build(0, true, null)));
      } catch (final InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return before;
    });
    try {
      final List<Pair<RID, Float>> found = search(index, query, 1, 1);
      assertThat(fired.get()).isTrue();
      assertThat(index.isTrained()).isTrue();
      return found.getFirst().getFirst();
    } finally {
      IVFPostingsComponent.setPageReadObserver(null);
    }
  }

  private List<Pair<RID, Float>> search(final LSMIVFVectorIndex index, final float[] query, final int k, final int nprobe) {
    return inTransaction(() -> index.findNeighbors(query, k, nprobe, -1, null));
  }

  private <T> T inTransaction(final Supplier<T> reader) {
    final AtomicReference<T> result = new AtomicReference<>();
    database.transaction(() -> result.set(reader.get()));
    return result.get();
  }

  /** The cluster of {@code rid} among records inserted in turn over {@code clusters} clusters. */
  private static int clusterOf(final List<RID> rids, final RID rid, final int clusters) {
    final int index = rids.indexOf(rid);
    assertThat(index).isNotNegative();
    return index % clusters;
  }

  private static void runOnAnotherThread(final Runnable task) throws InterruptedException {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      try {
        task.run();
      } catch (final Throwable t) {
        failure.set(t);
      }
    });
    thread.start();
    thread.join();
    if (failure.get() != null)
      throw new IllegalStateException(failure.get());
  }

  private void waitForIdleTraining(final LSMIVFVectorIndex index) throws InterruptedException {
    // The automatic training clears its flag after its commit: wait for it so the counts below are final
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline && index.getStats().get("autoTrainings") == 0)
      Thread.sleep(10);
  }

  /**
   * A replicated apply in flight for {@code millis}: the publication sequence odd and no page-manager lock held (#10941), and
   * {@code inFlight} cleared when it ends.
   */
  private Thread replicatedApplyFor(final long millis, final AtomicBoolean inFlight) {
    final PageManager pageManager = ((DatabaseInternal) database).getPageManager();
    PublicationTestSupport.beginPublication(pageManager);
    final Thread apply = new Thread(() -> {
      try {
        Thread.sleep(millis);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        inFlight.set(false);
        PublicationTestSupport.endPublication(pageManager);
      }
    });
    apply.start();
    return apply;
  }

  /** {@code page} with the int at {@code offset} of its content replaced. */
  private static BasePage withInt(final BasePage page, final int offset, final int value) {
    final BasePage copy = copyOf(page);
    copy.getContent().putInt(BasePage.PAGE_HEADER_SIZE + offset, value);
    return copy;
  }

  private BasePage zeroPage(final IVFPostingsComponent component, final int pageNumber) {
    final int size = component.getPageSize();
    return new ImmutablePage(new PageId((DatabaseInternal) database, component.getFileId(), pageNumber), size, new byte[size], 0, 0);
  }

  private static BasePage copyOf(final BasePage page) {
    final byte[] bytes = Arrays.copyOf(page.getContent().array(), page.getPhysicalSize());
    return new ImmutablePage(page.getPageId(), page.getPhysicalSize(), bytes, (int) page.getVersion(), page.getContentSize());
  }

  private List<RID> createTypeAndInsert(final String type, final List<float[]> vectors, final int batch,
      final Consumer<TypeLSMIVFVectorIndexBuilder> settings) {
    database.transaction(() -> {
      final DocumentType t = database.getSchema().createDocumentType(type);
      t.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    });
    final List<RID> rids = insertInto(type, vectors, batch);
    createIndex(type, vectors.getFirst().length, settings);
    return rids;
  }

  /** An index created on an empty type (untrained, one list), then {@code vectors} inserted; trained by a build of 2 or 4 lists. */
  private List<RID> createUntrainedTypeAndInsert(final String type, final List<float[]> vectors) {
    database.transaction(() -> {
      final DocumentType t = database.getSchema().createDocumentType(type);
      t.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    });
    createIndex(type, vectors.getFirst().length, b -> b.withNlist(0).withAutoTrainThreshold(0).withTargetPostingSize(vectors.size() / 4));
    return insertInto(type, vectors, 500);
  }

  private List<RID> insertInto(final String type, final List<float[]> vectors, final int batch) {
    final List<RID> rids = new ArrayList<>(vectors.size());
    for (int from = 0; from < vectors.size(); from += batch) {
      final int start = from;
      database.transaction(() -> {
        for (int i = start; i < Math.min(start + batch, vectors.size()); i++) {
          final MutableDocument doc = database.newDocument(type);
          doc.set("embedding", vectors.get(i));
          doc.save();
          rids.add(doc.getIdentity());
        }
      });
    }
    return rids;
  }

  private void createIndex(final String type, final int dims, final Consumer<TypeLSMIVFVectorIndexBuilder> settings) {
    final TypeLSMIVFVectorIndexBuilder builder = database.getSchema().buildTypeIndex(type, new String[] { "embedding" })
        .withIVFVectorType().withDimensions(dims).withSimilarity("COSINE").withScanCode("BINARY").withRerankCode("INT8")
        .withTrainingSample(20_000).withKmeansIterations(5);
    settings.accept(builder);
    builder.create();
  }

  private LSMIVFVectorIndex ivfIndex(final String type) {
    final Index[] buckets = database.getSchema().getType(type).getPolymorphicIndexByProperties("embedding").getIndexesOnBuckets();
    assertThat(buckets).hasSize(1);
    return (LSMIVFVectorIndex) buckets[0];
  }

  private static List<float[]> randomVectors(final Random random, final int count, final int dims) {
    final List<float[]> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final float[] v = new float[dims];
      for (int d = 0; d < dims; d++)
        v[d] = (float) random.nextGaussian();
      vectors.add(v);
    }
    return vectors;
  }

  /** Two orthogonal clusters, alternating: even indexes in cluster 0, odd ones in cluster 1. */
  private static List<float[]> twoClusters(final Random random, final int count, final int dims) {
    return clusters(random, count, dims, 2);
  }

  /** Four orthogonal clusters, in turn: index {@code i} is in cluster {@code i % 4}. */
  private static List<float[]> fourClusters(final Random random, final int count, final int dims) {
    return clusters(random, count, dims, 4);
  }

  private static List<float[]> clusters(final Random random, final int count, final int dims, final int clusters) {
    final int width = dims / clusters;
    final List<float[]> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final int cluster = i % clusters;
      final float[] v = new float[dims];
      for (int d = 0; d < dims; d++)
        v[d] = (float) random.nextGaussian() * 0.05f + (d / width == cluster ? 1f : 0f);
      vectors.add(v);
    }
    return vectors;
  }
}
