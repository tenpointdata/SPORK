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

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.LSMIVFVectorIndexMetadata;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.TypeLSMIVFVectorIndexBuilder;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Correctness of the {@code LSM_IVF_VECTOR} index: round trips through the transaction, visibility, recall against a brute-force
 * scan on random and clustered corpora, allow-list filtering, reopen from disk and WAL replay after a simulated crash.
 */
class LSMIVFVectorIndexTest extends TestHelper {

  private static final String TYPE = "IvfDoc";

  @Test
  void insertSearchDeleteRoundTrip() {
    final int dims = 32;
    final Random random = new Random(1);
    final List<float[]> vectors = randomVectors(random, 2_000, dims);
    final List<RID> rids = createTypeAndInsert(vectors, 500);
    createIndex(dims, "COSINE", 16, "BINARY", "INT8");

    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.countEntries()).isEqualTo(vectors.size());
    assertThat(index.getCentroidCount()).isEqualTo(16);

    // A stored vector finds itself first
    final int probe = 777;
    database.transaction(() -> {
      final List<Pair<RID, Float>> neighbors = index.findNeighbors(vectors.get(probe), 5, 4, 10, null);
      assertThat(neighbors).hasSize(5);
      assertThat(neighbors.getFirst().getFirst()).isEqualTo(rids.get(probe));
      assertThat(neighbors.getFirst().getSecond()).isLessThan(0.05f);
    });

    // The same answer through SQL, with the distance of every row
    database.transaction(() -> {
      try (final ResultSet rs = database.query("sql",
          "SELECT expand(vector.neighbors('" + TYPE + "[embedding]', ?, 5, {nprobe: 4}))", vectors.get(probe))) {
        assertThat(rs.hasNext()).isTrue();
        final Result first = rs.next();
        assertThat(first.getIdentity().get()).isEqualTo(rids.get(probe));
        assertThat(first.<Float>getProperty("distance")).isLessThan(0.05f);
      }
    });

    // Deleting the record takes it out of the answer
    database.transaction(() -> rids.get(probe).asDocument().delete());
    assertThat(index.countEntries()).isEqualTo(vectors.size() - 1);
    database.transaction(() -> {
      final List<Pair<RID, Float>> neighbors = index.findNeighbors(vectors.get(probe), 5, 4, 10, null);
      for (final Pair<RID, Float> neighbor : neighbors)
        assertThat(neighbor.getFirst()).isNotEqualTo(rids.get(probe));
    });

    // Updating the vector of a record moves it: the old vector no longer finds it, the new one does
    final float[] moved = randomVectors(random, 1, dims).getFirst();
    final RID updated = rids.get(12);
    database.transaction(() -> {
      final MutableDocument doc = updated.asDocument().modify();
      doc.set("embedding", moved);
      doc.save();
    });
    database.transaction(() -> {
      final List<Pair<RID, Float>> neighbors = index.findNeighbors(moved, 1, 4, 10, null);
      assertThat(neighbors.getFirst().getFirst()).isEqualTo(updated);
      final List<Pair<RID, Float>> old = index.findNeighbors(vectors.get(12), 3, 4, 10, null);
      for (final Pair<RID, Float> neighbor : old)
        assertThat(neighbor.getFirst()).isNotEqualTo(updated);
    });
  }

  @Test
  void transactionVisibility() throws Exception {
    final int dims = 16;
    final Random random = new Random(2);
    final List<float[]> vectors = randomVectors(random, 300, dims);
    createTypeAndInsert(vectors, 100);
    createIndex(dims, "EUCLIDEAN", 4, "INT8", "NONE");
    final LSMIVFVectorIndex index = ivfIndex();

    final float[] fresh = randomVectors(random, 1, dims).getFirst();
    for (int i = 0; i < dims; i++)
      fresh[i] += 10f; // far from everything stored, so it is unmistakable in a top-1

    // Uncommitted: visible to the writing transaction, invisible to another one
    database.begin();
    final MutableDocument doc = database.newDocument(TYPE);
    doc.set("embedding", fresh);
    doc.save();
    final RID pendingRid = doc.getIdentity();
    assertThat(index.findNeighbors(fresh, 1, 4, 0, null).getFirst().getFirst()).isEqualTo(pendingRid);

    final AtomicReference<RID> seenByOther = new AtomicReference<>();
    final Thread other = new Thread(() -> database.transaction(
        () -> seenByOther.set(index.findNeighbors(fresh, 1, 4, 0, null).getFirst().getFirst())));
    other.start();
    other.join();
    assertThat(seenByOther.get()).isNotEqualTo(pendingRid);

    // Rolled back: gone for everybody
    database.rollback();
    database.transaction(() -> assertThat(index.findNeighbors(fresh, 1, 4, 0, null).getFirst().getFirst()).isNotEqualTo(pendingRid));
    assertThat(index.countEntries()).isEqualTo(vectors.size());

    // Committed: visible to everybody
    final RID[] committed = new RID[1];
    database.transaction(() -> {
      final MutableDocument d = database.newDocument(TYPE);
      d.set("embedding", fresh);
      d.save();
      committed[0] = d.getIdentity();
    });
    final AtomicReference<RID> seenAfterCommit = new AtomicReference<>();
    final Thread reader = new Thread(() -> database.transaction(
        () -> seenAfterCommit.set(index.findNeighbors(fresh, 1, 4, 0, null).getFirst().getFirst())));
    reader.start();
    reader.join();
    assertThat(seenAfterCommit.get()).isEqualTo(committed[0]);
    assertThat(index.countEntries()).isEqualTo(vectors.size() + 1);
  }

  /**
   * I.i.d. Gaussian vectors are the adversarial case of an inverted file: there is no cluster structure for the centroids to
   * find, so the nearest neighbours of a query sit in lists all over the partition and the recall at a fixed {@code nprobe}
   * is bounded by the share of the lists probed (measured: 0.27 at 32 of 500 lists, 0.44 at 64, 0.66 at 128). One-bit codes
   * suffer the same way on such data (0.76 at an exhaustive probe with a 200x rerank), which is why this corpus is indexed
   * with INT8 scan codes. What the test pins is the exact path: an exhaustive probe answers the brute-force neighbours.
   */
  @Test
  @Tag("slow")
  void recallOnRandom128d() {
    final int dims = 128;
    final Random random = new Random(3);
    final List<float[]> vectors = randomVectors(random, 100_000, dims);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    createIndex(dims, "COSINE", 0, "INT8", "NONE");
    final LSMIVFVectorIndex index = ivfIndex();
    // 100K / targetPostingSize 200: the two-level training shares the lists among the coarse groups, so the total may be off by a few
    assertThat(index.getCentroidCount()).isBetween(495, 505);

    final List<float[]> queries = randomVectors(random, 50, dims);
    for (final int nprobe : new int[] { 32, 64, 128 })
      System.out.println("random 128-d INT8 recall@10 nprobe=" + nprobe + ": " + recallAt10(index, vectors, rids, queries, nprobe, 0, null));
    final double quarter = recallAt10(index, vectors, rids, queries, 128, 0, null);
    assertThat(quarter).as("a quarter of the lists").isGreaterThanOrEqualTo(0.55);
    final double exhaustive = recallAt10(index, vectors, rids, queries, index.getCentroidCount(), 0, null);
    System.out.println("random 128-d INT8 recall@10 exhaustive: " + exhaustive);
    assertThat(exhaustive).as("every list").isGreaterThanOrEqualTo(0.95);
  }

  @Test
  @Tag("slow")
  void recallOnClustered512d() {
    final int dims = 512;
    final Random random = new Random(4);
    final List<float[]> vectors = clusteredVectors(random, 100_000, dims, 1_000, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    createIndex(dims, "COSINE", 0, "BINARY", "INT8");
    final LSMIVFVectorIndex index = ivfIndex();

    final List<float[]> queries = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      final float[] q = vectors.get(random.nextInt(vectors.size())).clone();
      for (int d = 0; d < dims; d++)
        q[d] += (float) random.nextGaussian() * 0.1f;
      queries.add(q);
    }
    final double recall = recallAt10(index, vectors, rids, queries, 32, null);
    System.out.println("clustered 512-d recall@10 nprobe=32: " + recall);
    assertThat(recall).isGreaterThanOrEqualTo(0.9);
  }

  @Test
  void allowListFiltering() {
    final int dims = 64;
    final Random random = new Random(5);
    final List<float[]> vectors = clusteredVectors(random, 20_000, dims, 100, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    createIndex(dims, "COSINE", 100, "BINARY", "INT8");
    final LSMIVFVectorIndex index = ivfIndex();

    // 1% of the records, chosen at random, so the nearest ones are mostly outside the probed lists
    final Set<RID> allowed = new HashSet<>();
    while (allowed.size() < 200)
      allowed.add(rids.get(random.nextInt(rids.size())));

    final List<float[]> queries = randomVectors(random, 30, dims);
    database.transaction(() -> {
      for (final float[] query : queries) {
        final List<Pair<RID, Float>> neighbors = index.findNeighbors(query, 10, 8, 10, allowed);
        assertThat(neighbors).isNotEmpty();
        for (final Pair<RID, Float> neighbor : neighbors)
          assertThat(allowed).contains(neighbor.getFirst());
      }
    });
    // The allow-list widens the probe, so the recall within the list stays high
    final double recall = recallAt10(index, vectors, rids, queries, 8, allowed);
    System.out.println("allow-list 1% recall@10 nprobe=8: " + recall);
    assertThat(recall).isGreaterThanOrEqualTo(0.8);

    // A wide allow-list goes through the posting scan with the filter probed per entry
    final Set<RID> quarter = new HashSet<>();
    for (int i = 0; i < rids.size(); i += 4)
      quarter.add(rids.get(i));
    database.transaction(() -> {
      for (final float[] query : queries)
        for (final Pair<RID, Float> neighbor : index.findNeighbors(query, 10, 8, 10, quarter))
          assertThat(quarter).contains(neighbor.getFirst());
    });
    final double quarterRecall = recallAt10(index, vectors, rids, queries, 8, quarter);
    System.out.println("allow-list 25% recall@10 nprobe=8: " + quarterRecall);
    assertThat(quarterRecall).isGreaterThanOrEqualTo(0.8);

    // The SQL filter option reaches the same path
    database.transaction(() -> {
      final List<RID> filter = new ArrayList<>(allowed);
      try (final ResultSet rs = database.query("sql",
          "SELECT expand(vector.neighbors('" + TYPE + "[embedding]', ?, 10, {filter: ?}))", queries.getFirst(), filter)) {
        int rows = 0;
        while (rs.hasNext()) {
          assertThat(allowed).contains(rs.next().getIdentity().get());
          rows++;
        }
        assertThat(rows).isEqualTo(10);
      }
    });
  }

  @Test
  void reopenFromDisk() {
    final int dims = 24;
    final Random random = new Random(6);
    final List<float[]> vectors = randomVectors(random, 3_000, dims);
    final List<RID> rids = createTypeAndInsert(vectors, 1_000);
    createIndex(dims, "DOT_PRODUCT", 12, "INT8", "NONE");

    final int probe = 1_234;
    reopenDatabase();

    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.getType()).isEqualTo(Schema.INDEX_TYPE.LSM_IVF_VECTOR);
    final LSMIVFVectorIndexMetadata metadata = index.getIVFMetadata();
    assertThat(metadata.dimensions).isEqualTo(dims);
    assertThat(metadata.similarity).isEqualTo(IVFSimilarity.DOT_PRODUCT);
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.INT8);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.NONE);
    assertThat(index.getCentroidCount()).isEqualTo(12);
    assertThat(index.countEntries()).isEqualTo(vectors.size());

    database.transaction(() -> {
      final List<Pair<RID, Float>> neighbors = index.findNeighbors(vectors.get(probe), 3, 3, 0, null);
      assertThat(neighbors.getFirst().getFirst()).isEqualTo(rids.get(probe));
    });

    // The reopened index keeps taking writes into the same posting lists
    final float[] fresh = randomVectors(random, 1, dims).getFirst();
    final RID[] added = new RID[1];
    database.transaction(() -> {
      final MutableDocument d = database.newDocument(TYPE);
      d.set("embedding", fresh);
      d.save();
      added[0] = d.getIdentity();
    });
    database.transaction(
        () -> assertThat(index.findNeighbors(fresh, 1, 3, 0, null).getFirst().getFirst()).isEqualTo(added[0]));
  }

  @Test
  void walReplayAfterCrash() {
    final int dims = 20;
    final Random random = new Random(7);
    final List<float[]> vectors = randomVectors(random, 1_000, dims);
    createTypeAndInsert(vectors, 500);
    createIndex(dims, "COSINE", 8, "BINARY", "INT8");

    // Committed after the build, so their postings exist only in the WAL when the "crash" hits
    final List<float[]> late = randomVectors(random, 200, dims);
    final List<RID> lateRids = new ArrayList<>();
    database.transaction(() -> {
      for (final float[] v : late) {
        final MutableDocument d = database.newDocument(TYPE);
        d.set("embedding", v);
        d.save();
        lateRids.add(d.getIdentity());
      }
    });

    ((DatabaseInternal) database).kill();
    database.close();
    database = factory.open();

    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.countEntries()).isEqualTo(vectors.size() + late.size());
    database.transaction(() -> {
      for (int i = 0; i < late.size(); i += 20)
        assertThat(index.findNeighbors(late.get(i), 1, 8, 10, null).getFirst().getFirst()).isEqualTo(lateRids.get(i));
    });
  }

  @Test
  void untrainedIndexAcceptsWritesAndRebuildTrains() {
    final int dims = 16;
    final Random random = new Random(8);
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(TYPE);
      type.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    });
    createIndex(dims, "COSINE", 0, "BINARY", "INT8");
    final LSMIVFVectorIndex index = ivfIndex();
    // Nothing to train on: one pseudo-centroid takes every vector until a rebuild
    assertThat(index.getCentroidCount()).isEqualTo(1);
    assertThat(index.isTrained()).isFalse();

    final List<float[]> vectors = randomVectors(random, 2_000, dims);
    final List<RID> rids = insert(vectors, 500);
    assertThat(index.countEntries()).isEqualTo(vectors.size());
    database.transaction(
        () -> assertThat(index.findNeighbors(vectors.get(5), 1, 1, 10, null).getFirst().getFirst()).isEqualTo(rids.get(5)));

    database.command("sql", "REBUILD INDEX `" + ivfTypeIndex().getName() + "`");
    final LSMIVFVectorIndex rebuilt = ivfIndex();
    assertThat(rebuilt.isTrained()).isTrue();
    assertThat(rebuilt.getCentroidCount()).isEqualTo(10);
    assertThat(rebuilt.countEntries()).isEqualTo(vectors.size());
    database.transaction(
        () -> assertThat(rebuilt.findNeighbors(vectors.get(5), 1, 4, 10, null).getFirst().getFirst()).isEqualTo(rids.get(5)));
  }

  @Test
  void sqlCreateIndexWithMetadata() {
    final int dims = 8;
    final Random random = new Random(9);
    final List<float[]> vectors = randomVectors(random, 500, dims);
    final List<RID> rids = createTypeAndInsert(vectors, 500);
    database.command("sql", "CREATE INDEX ON " + TYPE + " (embedding) LSM_IVF_VECTOR METADATA "
        + "{dimensions: 8, similarity: 'EUCLIDEAN', nlist: 4, scanCode: 'INT8', rerankCode: 'NONE', nprobe: 2}");

    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.getIVFMetadata().similarity).isEqualTo(IVFSimilarity.EUCLIDEAN);
    assertThat(index.getIVFMetadata().nprobe).isEqualTo(2);
    assertThat(index.getCentroidCount()).isEqualTo(4);

    database.transaction(() -> {
      try (final ResultSet rs = database.query("sql",
          "SELECT expand(vector.neighbors('" + TYPE + "[embedding]', ?, 1))", vectors.get(42))) {
        assertThat(rs.next().getIdentity().get()).isEqualTo(rids.get(42));
      }
    });
  }

  @Test
  void hammingOverBinaryVectors() {
    final int dims = 64;
    final Random random = new Random(10);
    final List<float[]> vectors = new ArrayList<>();
    for (int i = 0; i < 2_000; i++) {
      final float[] v = new float[dims];
      for (int d = 0; d < dims; d++)
        v[d] = random.nextBoolean() ? 1f : 0f;
      vectors.add(v);
    }
    final List<RID> rids = createTypeAndInsert(vectors, 500);
    createIndex(dims, "HAMMING", 8, "BINARY", "NONE");
    final LSMIVFVectorIndex index = ivfIndex();

    database.transaction(() -> {
      final float[] query = vectors.get(99).clone();
      query[3] = 1f - query[3]; // one bit away
      final List<Pair<RID, Float>> neighbors = index.findNeighbors(query, 1, 8, 0, null);
      assertThat(neighbors.getFirst().getFirst()).isEqualTo(rids.get(99));
      assertThat(neighbors.getFirst().getSecond()).isEqualTo(1f);
    });
  }

  // ---------------------------------------------------------------- hardening

  /**
   * The default rerank budget is an absolute candidate count, not {@code k * rerankOversample}: on clusters denser than the
   * budget (here 1,000 points each, as the 5M benchmark corpus) 10 x k one-bit survivors miss most of the true neighbours,
   * 2,000 catch them. A query that names its oversample still gets exactly {@code k * n}.
   */
  @Test
  void defaultRerankBudgetIsAnAbsoluteCandidateCount() {
    final int dims = 128;
    final Random random = new Random(21);
    final List<float[]> vectors = clusteredVectors(random, 20_000, dims, 20, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    createIndex(dims, "COSINE", 100, "BINARY", "INT8");
    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.getIVFMetadata().rerankCandidates).isEqualTo(LSMIVFVectorIndexMetadata.DEFAULT_RERANK_CANDIDATES);

    final List<float[]> queries = noisyQueries(random, vectors, 30, 0.1f);
    // Default: 2,000 candidates whatever k (32 lists of ~200 hold more than that)
    database.transaction(() -> {
      final long before = index.getStats().get("rerankedCandidates");
      index.findNeighbors(queries.getFirst(), 10, 32, -1, null);
      assertThat(index.getStats().get("rerankedCandidates") - before).isEqualTo(2_000L);
      // A per-query oversample is honoured as k * n
      final long mid = index.getStats().get("rerankedCandidates");
      index.findNeighbors(queries.getFirst(), 10, 32, 5, null);
      assertThat(index.getStats().get("rerankedCandidates") - mid).isEqualTo(50L);
    });

    final double byDefault = recallAt10(index, vectors, rids, queries, 32, -1, null);
    final double tenTimesK = recallAt10(index, vectors, rids, queries, 32, 10, null);
    System.out.println("dense clusters recall@10: default budget " + byDefault + ", 10 x k " + tenTimesK);
    assertThat(byDefault).isGreaterThanOrEqualTo(0.9);
    assertThat(byDefault).isGreaterThan(tenTimesK);
  }

  /** The exact-filter crossover is an absolute record count: 10% of 30K is answered from the records, 25% scans the lists. */
  @Test
  void exactFilterCrossoverIsAnAbsoluteCount() {
    final int dims = 32;
    final Random random = new Random(22);
    final List<float[]> vectors = clusteredVectors(random, 30_000, dims, 100, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 10_000);
    createIndex(dims, "COSINE", 100, "BINARY", "INT8");
    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.getIVFMetadata().exactFilterThreshold).isEqualTo(LSMIVFVectorIndexMetadata.DEFAULT_EXACT_FILTER_THRESHOLD);

    final Set<RID> tenPercent = new HashSet<>();
    while (tenPercent.size() < 3_000)
      tenPercent.add(rids.get(random.nextInt(rids.size())));
    final Set<RID> quarter = new HashSet<>();
    for (int i = 0; i < rids.size(); i += 4)
      quarter.add(rids.get(i));
    final float[] query = vectors.get(17);

    database.transaction(() -> {
      final long before = index.getStats().get("exactFilterSearches");
      // 3,000 allowed records: under the 5,000 default although 10% of the index (the old 1% ratio scanned the lists here)
      for (final Pair<RID, Float> neighbor : index.findNeighbors(query, 10, 8, -1, tenPercent))
        assertThat(tenPercent).contains(neighbor.getFirst());
      assertThat(index.getStats().get("exactFilterSearches") - before).isEqualTo(1L);
      // 7,500 allowed records: the posting scan with the bitmap
      for (final Pair<RID, Float> neighbor : index.findNeighbors(query, 10, 8, -1, quarter))
        assertThat(quarter).contains(neighbor.getFirst());
      assertThat(index.getStats().get("exactFilterSearches") - before).isEqualTo(1L);
    });
    assertThat(recallAt10(index, vectors, rids, noisyQueries(random, vectors, 20, 0.1f), 8, -1, tenPercent)).isEqualTo(1.0);
    assertThat(recallAt10(index, vectors, rids, noisyQueries(random, vectors, 20, 0.1f), 8, -1, quarter)).isGreaterThanOrEqualTo(0.9);
  }

  /**
   * The lists of one query are read on the query pool and the per-task heaps merged: the answer is the serial answer, entry for
   * entry. One list, and a transaction holding pages it wrote, stay on the calling thread.
   */
  @Test
  void parallelPostingReadsAnswerTheSerialAnswer() {
    final int dims = 64;
    final Random random = new Random(23);
    final List<float[]> vectors = clusteredVectors(random, 20_000, dims, 200, 0.3f);
    createTypeAndInsert(vectors, 5_000);
    for (final String rerankCode : new String[] { "INT8", "INT4" }) {
      if (database.getSchema().getType(TYPE).getPolymorphicIndexByProperties("embedding") != null)
        database.getSchema().dropIndex(ivfTypeIndex().getName());
      createIndex(dims, "COSINE", 100, "BINARY", rerankCode);
      final LSMIVFVectorIndex index = ivfIndex();
      final Set<RID> filter = new HashSet<>();
      for (int i = 0; i < 20_000; i += 2)
        filter.add(RID.create(database, index.getAssociatedBucketId(), i));
      final List<float[]> queries = noisyQueries(random, vectors, 20, 0.1f);
      database.transaction(() -> {
        for (final float[] query : queries)
          for (final Set<RID> allowed : Arrays.<Set<RID>>asList(null, filter)) {
            // A budget above the entries of the 32 lists: no candidate is evicted, so a tie on the one-bit distance cannot make
            // the two candidate sets differ
            index.setParallelScan(false);
            final List<Pair<RID, Float>> serial = index.findNeighbors(query, 10, 32, 1_000, allowed);
            index.setParallelScan(true);
            final long parallelBefore = index.getStats().get("parallelSearches");
            final List<Pair<RID, Float>> parallel = index.findNeighbors(query, 10, 32, 1_000, allowed);
            assertThat(index.getStats().get("parallelSearches") - parallelBefore).isEqualTo(1L);
            assertSameAnswer(rerankCode, serial, parallel);
          }
        // A single list is scanned on the caller
        final long parallelBefore = index.getStats().get("parallelSearches");
        index.findNeighbors(queries.getFirst(), 10, 1, -1, null);
        assertThat(index.getStats().get("parallelSearches")).isEqualTo(parallelBefore);
      });
      // A transaction that wrote pages reads its own view on its own thread
      database.transaction(() -> {
        final MutableDocument doc = database.newDocument(TYPE);
        doc.set("embedding", vectors.get(3));
        doc.save();
        final long parallelBefore = index.getStats().get("parallelSearches");
        final List<Pair<RID, Float>> found = index.findNeighbors(vectors.get(3), 2, 32, -1, null);
        assertThat(index.getStats().get("parallelSearches")).isEqualTo(parallelBefore);
        assertThat(found.stream().map(Pair::getFirst).toList()).contains(doc.getIdentity());
      });
    }
  }

  /**
   * INT4 residual rerank codes: about half the INT8 rerank bytes (128 + 8 against 256 + 4 at 256-d), the distance asymmetric (float
   * query against the 4-bit residual of the entry's list centroid). Sixteen levels of the residual are coarser than 255 levels of
   * the vector on this corpus (measured: recall 0.66 against INT8's 0.94), so INT4 alone trades recall for bytes; what lifts the
   * ceiling of either code is the exact tier, which rescores the best 50 reranked candidates on their stored vectors (0.98).
   */
  @Test
  void int4RerankCodeAndExactRerankTier() {
    final int dims = 256;
    final Random random = new Random(24);
    final List<float[]> vectors = clusteredVectors(random, 20_000, dims, 40, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    final List<float[]> queries = noisyQueries(random, vectors, 30, 0.1f);

    createIndex(dims, "COSINE", 100, "BINARY", "INT8");
    final int int8Entry = ivfIndex().getEntrySize();
    final double int8Recall = recallAt10(ivfIndex(), vectors, rids, queries, 32, -1, null);
    database.getSchema().dropIndex(ivfTypeIndex().getName());

    createIndex(dims, "COSINE", 100, "BINARY", "INT4");
    final LSMIVFVectorIndex int4 = ivfIndex();
    final int int4Entry = int4.getEntrySize();
    final double int4Recall = recallAt10(int4, vectors, rids, queries, 32, -1, null);
    final double[] exactTier = new double[1];
    database.transaction(() -> {
      int hits = 0;
      for (final float[] query : queries) {
        final Set<RID> truth = bruteForceTop10(IVFSimilarity.COSINE, vectors, rids, query, null);
        for (final Pair<RID, Float> f : int4.findNeighbors(query, 10, 32, -1, 50, null))
          if (truth.contains(f.getFirst()))
            hits++;
      }
      exactTier[0] = hits / (10.0 * queries.size());
    });
    System.out.println("256-d entry bytes INT8 " + int8Entry + " INT4 " + int4Entry + "; recall@10 INT8 " + int8Recall + " INT4 " + int4Recall
        + " INT4 + exact 50 " + exactTier[0]);
    // live flag + position + 256 one-bit codes, then the rerank code: INT8 = 256 + scale, INT4 = 128 + scale + correction
    assertThat(int8Entry).isEqualTo(9 + 32 + 260);
    assertThat(int4Entry).isEqualTo(9 + 32 + 136);
    assertThat(int4Recall).isGreaterThanOrEqualTo(0.55);
    assertThat(exactTier[0]).isGreaterThanOrEqualTo(Math.max(int4Recall, int8Recall));
    assertThat(exactTier[0]).isGreaterThanOrEqualTo(0.97);
  }

  /** INT4 distances are distances, under every similarity that has a rerank: a stored vector finds itself at about 0. */
  @Test
  void int4RerankUnderEverySimilarity() {
    final int dims = 48;
    final Random random = new Random(25);
    final List<float[]> vectors = clusteredVectors(random, 3_000, dims, 30, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 1_000);
    for (final String similarity : new String[] { "COSINE", "DOT_PRODUCT", "EUCLIDEAN" }) {
      if (database.getSchema().getType(TYPE).getPolymorphicIndexByProperties("embedding") != null)
        database.getSchema().dropIndex(ivfTypeIndex().getName());
      createIndex(dims, similarity, 12, "BINARY", "INT4");
      final LSMIVFVectorIndex index = ivfIndex();
      final IVFSimilarity sim = IVFSimilarity.parse(similarity);
      final Map<RID, float[]> byRid = new HashMap<>();
      for (int i = 0; i < rids.size(); i++)
        byRid.put(rids.get(i), vectors.get(i));
      database.transaction(() -> {
        for (int probe = 0; probe < 3_000; probe += 300) {
          final List<Pair<RID, Float>> found = index.findNeighbors(vectors.get(probe), 5, 12, -1, null);
          assertThat(found).hasSize(5);
          // DOT_PRODUCT ranks a longer neighbour above the probe itself; the two distances do have a nearest-first self
          if (sim != IVFSimilarity.DOT_PRODUCT)
            assertThat(found.getFirst().getFirst()).as(similarity + " probe " + probe).isEqualTo(rids.get(probe));
          for (final Pair<RID, Float> f : found) {
            final float exact = exactDistance(sim, vectors.get(probe), byRid.get(f.getFirst()));
            assertThat(f.getSecond()).as(similarity).isCloseTo(exact, offset(0.4f + 0.15f * Math.abs(exact)));
          }
        }
      });
    }
  }

  /** Pass 2 of the build codes on the query pool: the same entries, in the same lists, as the single-threaded build. */
  @Test
  void parallelBuildMatchesTheSerialBuild() {
    final int dims = 32;
    final Random random = new Random(26);
    final List<float[]> vectors = clusteredVectors(random, 30_000, dims, 100, 0.3f);
    createTypeAndInsert(vectors, 10_000);
    final List<float[]> queries = noisyQueries(random, vectors, 20, 0.1f);
    try {
      LSMIVFVectorIndex.setParallelBuild(false);
      createIndex(dims, "COSINE", 64, "BINARY", "INT8");
      final List<List<Pair<RID, Float>>> serial = answers(ivfIndex(), queries);
      final long serialEntries = ivfIndex().countEntries();

      LSMIVFVectorIndex.setParallelBuild(true);
      database.command("sql", "REBUILD INDEX `" + ivfTypeIndex().getName() + "`");
      assertThat(ivfIndex().countEntries()).isEqualTo(serialEntries).isEqualTo(vectors.size());
      final List<List<Pair<RID, Float>>> parallel = answers(ivfIndex(), queries);
      for (int q = 0; q < queries.size(); q++)
        assertSameAnswer("query " + q, serial.get(q), parallel.get(q));
    } finally {
      LSMIVFVectorIndex.setParallelBuild(true);
    }
  }

  /**
   * An index created on an empty type trains by itself once it holds {@code autoTrainThreshold} vectors, on the query pool and in one
   * transaction of its own: the lists are re-populated, every vector is found, and a write coded against the untrained centroid that commits after
   * the training is coded again at replay.
   */
  @Test
  void untrainedIndexTrainsItselfAtTheThreshold() throws Exception {
    final int dims = 24;
    final Random random = new Random(27);
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(TYPE);
      type.createProperty("embedding", Type.ARRAY_OF_FLOATS);
      type.createProperty("n", Type.INTEGER);
    });
    createIndex(dims, "COSINE", 0, "BINARY", "INT8", b -> b.withAutoTrainThreshold(1_000).withTargetPostingSize(100));
    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.isTrained()).isFalse();

    final List<float[]> vectors = clusteredVectors(random, 1_200, dims, 20, 0.3f);
    final List<RID> rids = insert(vectors.subList(0, 900), 300);
    assertThat(index.isTrained()).isFalse();
    assertThat(index.getCentroidCount()).isEqualTo(1);

    // A transaction that writes now, against the untrained centroid, and commits only after the training
    final float[] late = vectors.get(1_100);
    database.begin();
    final MutableDocument pending = database.newDocument(TYPE);
    pending.set("embedding", late);
    pending.save();

    final Thread crossing = new Thread(() -> rids.addAll(insert(vectors.subList(900, 1_100), 200)));
    crossing.start();
    crossing.join();
    // The training runs on the query pool, not on the writer whose commit crossed the threshold: wait for it
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (index.getStats().get("autoTrainings") == 0 && System.nanoTime() < deadline)
      Thread.sleep(10);
    assertThat(index.isTrained()).isTrue();
    assertThat(index.getStats().get("autoTrainings")).isEqualTo(1L);
    assertThat(index.getCentroidCount()).isEqualTo(11);
    assertThat(index.countEntries()).isEqualTo(1_100L);

    final long recodes = index.getStats().get("replayRecodes");
    database.commit();
    assertThat(index.getStats().get("replayRecodes")).isGreaterThan(recodes);
    assertThat(index.countEntries()).isEqualTo(1_101L);

    database.transaction(() -> {
      assertThat(index.findNeighbors(late, 1, 4, -1, null).getFirst().getFirst()).isEqualTo(pending.getIdentity());
      for (int i = 0; i < 1_100; i += 37)
        assertThat(index.findNeighbors(vectors.get(i), 1, 4, -1, null).getFirst().getFirst()).isEqualTo(rids.get(i));
    });

    // The training is on the pages: a reopen reads it back
    reopenDatabase();
    assertThat(ivfIndex().isTrained()).isTrue();
    assertThat(ivfIndex().getCentroidCount()).isEqualTo(11);
    assertThat(ivfIndex().countEntries()).isEqualTo(1_101L);
  }

  /** Deletes and updates find their entry through the locator, never by walking the list. */
  @Test
  void deletesUseTheLocator() {
    final int dims = 16;
    final Random random = new Random(28);
    final List<float[]> vectors = randomVectors(random, 3_000, dims);
    final List<RID> rids = createTypeAndInsert(vectors, 1_000);
    createIndex(dims, "EUCLIDEAN", 4, "INT8", "NONE");
    final LSMIVFVectorIndex index = ivfIndex();
    final long metricBefore = IVFVectorIndexMetrics.INSTANCE.locatorDeletes.sum();

    database.transaction(() -> {
      for (int i = 0; i < 3_000; i += 3)
        rids.get(i).asDocument().delete();
    });
    database.transaction(() -> {
      for (int i = 1; i < 3_000; i += 30) {
        final MutableDocument doc = rids.get(i).asDocument().modify();
        doc.set("embedding", vectors.get((i + 1) % vectors.size()));
        doc.save();
      }
    });
    assertThat(index.getStats().get("walkedDeletes")).isZero();
    assertThat(index.getStats().get("locatorDeletes")).isEqualTo(1_000L + 100L);
    assertThat(IVFVectorIndexMetrics.INSTANCE.locatorDeletes.sum() - metricBefore).isGreaterThanOrEqualTo(1_100L);
    assertThat(index.countEntries()).isEqualTo(2_000L);
    database.transaction(() -> {
      assertThat(index.getPostingsComponent().locatorEntries()).isEqualTo(2_000L);
      for (int i = 0; i < 3_000; i += 3)
        for (final Pair<RID, Float> neighbor : index.findNeighbors(vectors.get(i), 3, 4, -1, null))
          assertThat(neighbor.getFirst()).isNotEqualTo(rids.get(i));
      // A moved record is found at its new vector, once
      final List<Pair<RID, Float>> moved = index.findNeighbors(vectors.get(2), 2, 4, -1, null);
      assertThat(moved.stream().map(Pair::getFirst).toList()).contains(rids.get(1));
    });
  }

  /** The locator's linear hash table against a map, through enough entries to split buckets many times and chain overflow pages. */
  @Test
  void locatorAgreesWithAMap() {
    final int dims = 8;
    createTypeAndInsert(randomVectors(new Random(29), 10, dims), 10);
    createIndex(dims, "COSINE", 2, "INT8", "NONE");
    final IVFPostingsComponent component = ivfIndex().getPostingsComponent();
    final Random random = new Random(30);
    final Map<Long, Long> model = new HashMap<>();
    database.transaction(() -> {
      final IVFLocator locator = new IVFLocator(component);
      // Positions of one bucket page are consecutive: the hash has to spread them
      for (long p = 0; p < 40_000; p++) {
        final long position = (p / 30 + 1_000) * 2048 + p % 30;
        final int page = random.nextInt(1_000_000);
        final int slot = random.nextInt(100);
        locator.put(position, page, slot);
        model.put(position, ((long) page << 32) | slot);
      }
      final List<Long> keys = new ArrayList<>(model.keySet());
      for (int i = 0; i < 10_000; i++) {
        final long position = keys.get(random.nextInt(keys.size()));
        if (model.remove(position) != null)
          assertThat(locator.remove(position)).isTrue();
        else
          assertThat(locator.remove(position)).isFalse();
      }
      // Re-mapping a known position replaces its slot and reports the old one
      final long kept = model.keySet().iterator().next();
      assertThat(locator.put(kept, 7, 3)).isEqualTo(model.get(kept));
      model.put(kept, (7L << 32) | 3);
    });
    database.transaction(() -> {
      final IVFLocator locator = new IVFLocator(component);
      assertThat(locator.size()).isEqualTo(model.size() + 10L); // the 10 records the index was built on
      for (final Map.Entry<Long, Long> e : model.entrySet())
        assertThat(locator.get(e.getKey())).isEqualTo(e.getValue());
      assertThat(locator.get(99_999_999L)).isEqualTo(IVFLocator.NOT_FOUND);
    });
  }

  @Test
  void hardeningSettingsRoundTripThroughSqlAndReopen() {
    final int dims = 8;
    createTypeAndInsert(randomVectors(new Random(31), 300, dims), 300);
    database.command("sql", "CREATE INDEX ON " + TYPE + " (embedding) LSM_IVF_VECTOR METADATA "
        + "{dimensions: 8, nlist: 4, rerankCode: 'INT4', rerankCandidates: 500, exactFilterThreshold: 77, autoTrainThreshold: 0, "
        + "exactRerank: 20}");
    reopenDatabase();
    final LSMIVFVectorIndexMetadata metadata = ivfIndex().getIVFMetadata();
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.INT4);
    assertThat(metadata.rerankCandidates).isEqualTo(500);
    assertThat(metadata.exactFilterThreshold).isEqualTo(77);
    assertThat(metadata.autoTrainThreshold).isZero();
    assertThat(metadata.exactRerank).isEqualTo(20);
  }

  /** Same distances in the same order; the same records, except among the ones tied with the last answered distance. */
  private static void assertSameAnswer(final String label, final List<Pair<RID, Float>> expected, final List<Pair<RID, Float>> actual) {
    assertThat(actual.stream().map(Pair::getSecond).toList()).as(label).isEqualTo(expected.stream().map(Pair::getSecond).toList());
    if (expected.isEmpty())
      return;
    final float last = expected.getLast().getSecond();
    assertThat(actual.stream().filter(p -> p.getSecond() < last).map(Pair::getFirst).toList()).as(label)
        .isEqualTo(expected.stream().filter(p -> p.getSecond() < last).map(Pair::getFirst).toList());
  }

  /** The blocked-bitmap allow-list answers what the sorted-array one answers, sparse far positions included. */
  @Test
  void blockedAndSortedAllowListsAgree() {
    final int dims = 32;
    final Random random = new Random(33);
    final List<float[]> vectors = clusteredVectors(random, 20_000, dims, 100, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    createIndex(dims, "COSINE", 100, "BINARY", "INT8");
    final LSMIVFVectorIndex index = ivfIndex();
    final Set<RID> allowed = new HashSet<>();
    for (int i = 0; i < rids.size(); i += 3)
      allowed.add(rids.get(i));
    // Positions far past the last record, as a large-record bucket numbers them: they must neither match nor break the probe
    allowed.add(RID.create(database, index.getAssociatedBucketId(), 1L << 30));
    allowed.add(RID.create(database, index.getAssociatedBucketId(), (1L << 30) + 511));
    final List<float[]> queries = noisyQueries(random, vectors, 20, 0.1f);
    index.setParallelScan(false);
    try {
      database.transaction(() -> {
        for (final float[] query : queries) {
          LSMIVFVectorIndex.setSortedFilter(true);
          final List<Pair<RID, Float>> sorted = index.findNeighbors(query, 10, 16, 1_000, allowed);
          LSMIVFVectorIndex.setSortedFilter(false);
          final List<Pair<RID, Float>> blocked = index.findNeighbors(query, 10, 16, 1_000, allowed);
          assertSameAnswer("blocked", sorted, blocked);
          for (final Pair<RID, Float> neighbor : blocked)
            assertThat(allowed).contains(neighbor.getFirst());
        }
      });
    } finally {
      LSMIVFVectorIndex.setSortedFilter(false);
    }
  }

  /**
   * From {@code IVFCentroids.graphMinLists} lists the probe set and the assignment come from a jvector graph over the centroids:
   * built synchronously by the build (pass 2 assigns through it), in the background after a reopen, the two levels answering until
   * it is published.
   */
  @Test
  void centroidGraphSelectsTheProbeSet() throws Exception {
    final int dims = 32;
    final Random random = new Random(32);
    final List<float[]> vectors = clusteredVectors(random, 20_000, dims, 400, 0.3f);
    final List<RID> rids = createTypeAndInsert(vectors, 5_000);
    final List<float[]> queries = noisyQueries(random, vectors, 30, 0.1f);
    final int before = IVFCentroids.graphMinLists;
    try {
      IVFCentroids.graphMinLists = 300;
      createIndex(dims, "COSINE", 400, "BINARY", "INT8");
      final LSMIVFVectorIndex index = ivfIndex();
      assertThat(index.getStats().get("centroidGraph")).isEqualTo(1L);
      final double withGraph = recallAt10(index, vectors, rids, queries, 16, -1, null);
      System.out.println("centroid graph recall@10 nprobe=16: " + withGraph);
      assertThat(withGraph).isGreaterThanOrEqualTo(0.9);

      reopenDatabase();
      final LSMIVFVectorIndex reopened = ivfIndex();
      assertThat(reopened.isTrained()).isTrue();
      final long deadline = System.currentTimeMillis() + 60_000;
      while (reopened.getStats().get("centroidGraph") == 0 && System.currentTimeMillis() < deadline)
        Thread.sleep(20);
      assertThat(reopened.getStats().get("centroidGraph")).isEqualTo(1L);
      assertThat(recallAt10(reopened, vectors, rids, queries, 16, -1, null)).isGreaterThanOrEqualTo(0.9);
    } finally {
      IVFCentroids.graphMinLists = before;
    }
  }

  private List<List<Pair<RID, Float>>> answers(final LSMIVFVectorIndex index, final List<float[]> queries) {
    final List<List<Pair<RID, Float>>> answers = new ArrayList<>();
    index.setParallelScan(false);
    database.transaction(() -> {
      for (final float[] query : queries)
        answers.add(index.findNeighbors(query, 10, 16, -1, null));
    });
    return answers;
  }

  private static List<float[]> noisyQueries(final Random random, final List<float[]> vectors, final int count, final float noise) {
    final List<float[]> queries = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final float[] q = vectors.get(random.nextInt(vectors.size())).clone();
      for (int d = 0; d < q.length; d++)
        q[d] += (float) random.nextGaussian() * noise;
      queries.add(q);
    }
    return queries;
  }

  // ---------------------------------------------------------------- helpers

  private List<RID> createTypeAndInsert(final List<float[]> vectors, final int batch) {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(TYPE);
      type.createProperty("embedding", Type.ARRAY_OF_FLOATS);
      type.createProperty("n", Type.INTEGER);
    });
    return insert(vectors, batch);
  }

  private List<RID> insert(final List<float[]> vectors, final int batch) {
    final List<RID> rids = new ArrayList<>(vectors.size());
    for (int from = 0; from < vectors.size(); from += batch) {
      final int start = from;
      database.transaction(() -> {
        for (int i = start; i < Math.min(start + batch, vectors.size()); i++) {
          final MutableDocument doc = database.newDocument(TYPE);
          doc.set("embedding", vectors.get(i));
          doc.set("n", i);
          doc.save();
          rids.add(doc.getIdentity());
        }
      });
    }
    return rids;
  }

  private void createIndex(final int dims, final String similarity, final int nlist, final String scanCode,
      final String rerankCode) {
    createIndex(dims, similarity, nlist, scanCode, rerankCode, b -> {
    });
  }

  private void createIndex(final int dims, final String similarity, final int nlist, final String scanCode, final String rerankCode,
      final Consumer<TypeLSMIVFVectorIndexBuilder> settings) {
    final TypeLSMIVFVectorIndexBuilder builder = database.getSchema().buildTypeIndex(TYPE, new String[] { "embedding" })
        .withIVFVectorType().withDimensions(dims).withSimilarity(similarity).withNlist(nlist).withScanCode(scanCode)
        .withRerankCode(rerankCode).withTrainingSample(20_000).withKmeansIterations(5);
    settings.accept(builder);
    builder.create();
  }

  private TypeIndex ivfTypeIndex() {
    return database.getSchema().getType(TYPE).getPolymorphicIndexByProperties("embedding");
  }

  private LSMIVFVectorIndex ivfIndex() {
    final Index[] buckets = ivfTypeIndex().getIndexesOnBuckets();
    assertThat(buckets).hasSize(1);
    assertThat(buckets[0]).isInstanceOf(LSMIVFVectorIndex.class);
    return (LSMIVFVectorIndex) buckets[0];
  }

  private double recallAt10(final LSMIVFVectorIndex index, final List<float[]> vectors, final List<RID> rids,
      final List<float[]> queries, final int nprobe, final Set<RID> allowed) {
    return recallAt10(index, vectors, rids, queries, nprobe, 10, allowed);
  }

  private double recallAt10(final LSMIVFVectorIndex index, final List<float[]> vectors, final List<RID> rids,
      final List<float[]> queries, final int nprobe, final int oversample, final Set<RID> allowed) {
    final IVFSimilarity similarity = index.getIVFMetadata().similarity;
    final int[] hits = new int[1];
    database.transaction(() -> {
      for (final float[] query : queries) {
        final Set<RID> truth = bruteForceTop10(similarity, vectors, rids, query, allowed);
        final List<Pair<RID, Float>> found = index.findNeighbors(query, 10, nprobe, oversample, allowed);
        for (final Pair<RID, Float> f : found)
          if (truth.contains(f.getFirst()))
            hits[0]++;
      }
    });
    return hits[0] / (10.0 * queries.size());
  }

  private static Set<RID> bruteForceTop10(final IVFSimilarity similarity, final List<float[]> vectors, final List<RID> rids,
      final float[] query, final Set<RID> allowed) {
    final float[] best = new float[10];
    final int[] bestIdx = new int[10];
    Arrays.fill(best, Float.MAX_VALUE);
    for (int i = 0; i < vectors.size(); i++) {
      if (allowed != null && !allowed.contains(rids.get(i)))
        continue;
      final float distance = exactDistance(similarity, query, vectors.get(i));
      if (distance < best[9]) {
        int pos = 9;
        while (pos > 0 && best[pos - 1] > distance) {
          best[pos] = best[pos - 1];
          bestIdx[pos] = bestIdx[pos - 1];
          pos--;
        }
        best[pos] = distance;
        bestIdx[pos] = i;
      }
    }
    final Set<RID> truth = new HashSet<>();
    for (int i = 0; i < 10; i++)
      if (best[i] != Float.MAX_VALUE)
        truth.add(rids.get(bestIdx[i]));
    return truth;
  }

  private static float exactDistance(final IVFSimilarity similarity, final float[] a, final float[] b) {
    double dot = 0, na = 0, nb = 0, l2 = 0;
    for (int d = 0; d < a.length; d++) {
      dot += a[d] * b[d];
      na += a[d] * a[d];
      nb += b[d] * b[d];
      final double diff = a[d] - b[d];
      l2 += diff * diff;
    }
    return switch (similarity) {
      case COSINE -> (float) (1.0 - dot / Math.sqrt(na * nb));
      case DOT_PRODUCT -> (float) -dot;
      case EUCLIDEAN -> (float) l2;
      case HAMMING -> (float) l2;
    };
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

  private static List<float[]> clusteredVectors(final Random random, final int count, final int dims, final int clusters,
      final float spread) {
    final List<float[]> centers = randomVectors(random, clusters, dims);
    final List<float[]> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final float[] center = centers.get(random.nextInt(clusters));
      final float[] v = new float[dims];
      for (int d = 0; d < dims; d++)
        v[d] = center[d] + (float) random.nextGaussian() * spread;
      vectors.add(v);
    }
    return vectors;
  }
}
