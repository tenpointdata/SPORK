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
import com.arcadedb.TestHelper;
import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.select.SelectVectorResult;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The per-bucket sub-indexes of one {@code LSM_VECTOR} index are searched in parallel on {@link VectorSearchPool}, and the
 * answer must be exactly the serial one - same rows, same distances, same order - through every entry point
 * ({@code vector.neighbors} with each of its options, {@code db.index.vector.queryNodes}, {@code select().nearestTo()}).
 * Where a worker could not read what the caller reads (uncommitted changes, {@code REPEATABLE_READ}), the search must stay
 * on the caller's thread.
 */
@Tag("vector")
class VectorParallelBucketSearchTest extends TestHelper {
  private static final int DIMENSIONS  = 16;
  private static final int BUCKETS     = 8;
  private static final int NUM_VECTORS = 1_200;
  private static final int K           = 10;

  private final List<float[]> vectors = new ArrayList<>();

  @AfterEach
  void restoreParallelSearch() {
    if (database != null && database.isOpen())
      database.getConfiguration().setValue(GlobalConfiguration.VECTOR_INDEX_PARALLEL_SEARCH, true);
  }

  @Test
  void sqlNeighborsAnswerTheSameInParallelAsSerially() {
    populate("EUCLIDEAN", null);
    final Random rng = new Random(11);
    for (int q = 0; q < 10; q++) {
      final float[] query = randomVector(rng);
      assertSameBothWays("plain", () -> sqlNeighbors(query, K, null));
      assertSameBothWays("efSearch", () -> sqlNeighbors(query, K, Map.of("efSearch", 64)));
      assertSameBothWays("where", () -> sqlNeighbors(query, K, Map.of("where", Map.of("tenant", 1))));
      assertSameBothWays("groupBy", () -> sqlNeighbors(query, 3, Map.of("groupBy", "tenant", "groupSize", 2)));
      assertSameBothWays("maxDistance", () -> sqlNeighbors(query, 50, Map.of("maxDistance", 25.0)));
    }
  }

  @Test
  void quantizedIndexesWithRerankAnswerTheSameInParallelAsSerially() {
    populate("EUCLIDEAN", "BINARY");
    final Random rng = new Random(12);
    for (int q = 0; q < 5; q++) {
      final float[] query = randomVector(rng);
      assertSameBothWays("binary default rerank", () -> sqlNeighbors(query, K, null));
      assertSameBothWays("binary rerank 4", () -> sqlNeighbors(query, K, Map.of("rerank", 4)));
    }
  }

  @Test
  void cypherQueryNodesAndFluentNearestToAnswerTheSameInParallelAsSerially() {
    populate("COSINE", null);
    final Random rng = new Random(13);
    for (int q = 0; q < 5; q++) {
      final float[] query = randomVector(rng);
      assertSameBothWays("queryNodes", () -> {
        final List<String> rows = new ArrayList<>();
        try (final ResultSet rs = database.query("opencypher",
            "CALL db.index.vector.queryNodes('Doc[embedding]', $k, $q) YIELD node, score RETURN node, score",
            Map.of("k", K, "q", query))) {
          rs.forEachRemaining(r -> rows.add(r.<Document>getProperty("node").getIdentity() + "@" + r.getProperty("score")));
        }
        return rows;
      });
      assertSameBothWays("nearestTo", () -> {
        final List<String> rows = new ArrayList<>();
        for (final SelectVectorResult<Document> r : database.select().fromType("Doc").nearestTo("embedding", query, K).documents())
          rows.add(r.getDocument().getIdentity() + "@" + r.getDistance());
        return rows;
      });
    }
  }

  @Test
  void aCallerWithUncommittedChangesSearchesSeriallyAndSeesItsOwnWrites() {
    populate("EUCLIDEAN", null);
    final float[] query = randomVector(new Random(14));

    database.begin();
    try {
      final RID mine = database.newDocument("Doc").set("id", -1).set("tenant", 0).set("embedding", query).save().getIdentity();
      final long fanOutsBefore = VectorSearchPool.getInstance().getFanOutCount();

      final List<String> rows = sqlNeighbors(query, K, null);

      assertThat(rows.getFirst()).startsWith(mine + "@");
      assertThat(VectorSearchPool.getInstance().getFanOutCount())
          .as("a worker cannot see the caller's pending vector, so the search must not fan out").isEqualTo(fanOutsBefore);
    } finally {
      database.rollback();
    }
  }

  @Test
  void aRepeatableReadCallerSearchesSerially() {
    populate("EUCLIDEAN", null);
    final float[] query = randomVector(new Random(15));
    final List<String> expected = sqlNeighbors(query, K, null);

    database.begin(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    try {
      final long fanOutsBefore = VectorSearchPool.getInstance().getFanOutCount();
      assertThat(rids(sqlNeighbors(query, K, null))).containsExactlyElementsOf(rids(expected));
      assertThat(VectorSearchPool.getInstance().getFanOutCount()).isEqualTo(fanOutsBefore);
    } finally {
      database.commit();
    }
  }

  @Test
  void aSearchOnAPoolWorkerDoesNotFanOutAgain() throws Exception {
    populate("EUCLIDEAN", null);
    final List<LSMVectorIndex> indexes = subIndexes();
    assertThat(VectorSearchPool.getInstance().canFanOut(indexes)).isTrue();
    assertThat(VectorSearchPool.getInstance().getExecutorService().submit(() -> VectorSearchPool.getInstance().canFanOut(indexes))
        .get()).isFalse();
  }

  @Test
  void aFailingBucketSearchFailsTheWholeSearchAfterEveryOtherBucketEnded() {
    populate("EUCLIDEAN", null);
    final List<LSMVectorIndex> indexes = subIndexes();
    final AtomicInteger searched = new AtomicInteger();
    final LSMVectorIndex failing = indexes.get(3);

    assertThatThrownBy(() -> VectorSearchPool.searchAll(indexes, index -> {
      searched.incrementAndGet();
      if (index == failing)
        throw new IllegalStateException("bucket 3 failed");
      return List.of();
    })).isInstanceOf(IllegalStateException.class).hasMessage("bucket 3 failed");
    assertThat(searched.get()).isEqualTo(indexes.size());
  }

  @Test
  void answersComeBackInIndexOrder() {
    populate("EUCLIDEAN", null);
    final List<LSMVectorIndex> indexes = subIndexes();
    final List<List<Pair<RID, Float>>> answers = VectorSearchPool.searchAll(indexes,
        index -> List.of(new Pair<>(new RID(index.getAssociatedBucketId(), 0), 0f)));

    assertThat(answers).hasSize(indexes.size());
    for (int i = 0; i < indexes.size(); i++)
      assertThat(answers.get(i).getFirst().getFirst().getBucketId()).isEqualTo(indexes.get(i).getAssociatedBucketId());
  }

  /**
   * Rows are "rid@distance". The RIDs and their order must match exactly; a distance may differ in its last float bits,
   * because jvector's Vector API kernels reduce in an order that depends on how far the JIT has compiled them: the SERIAL
   * search alone returns different last bits before and after warm-up, which is what a parallel run, whose workers reach
   * those kernels through other call paths, also shows.
   */
  private void assertSameBothWays(final String what, final Supplier<List<String>> search) {
    final VectorSearchPool pool = VectorSearchPool.getInstance();

    database.getConfiguration().setValue(GlobalConfiguration.VECTOR_INDEX_PARALLEL_SEARCH, false);
    final long fanOutsBefore = pool.getFanOutCount();
    final List<String> serial = search.get();
    assertThat(pool.getFanOutCount()).as("%s: serial run must not fan out", what).isEqualTo(fanOutsBefore);

    database.getConfiguration().setValue(GlobalConfiguration.VECTOR_INDEX_PARALLEL_SEARCH, true);
    final List<String> parallel = search.get();
    assertThat(pool.getFanOutCount()).as("%s: parallel run must fan out", what).isGreaterThan(fanOutsBefore);

    assertThat(serial).as("%s: precondition, the search answers something", what).isNotEmpty();
    assertThat(rids(parallel)).as("%s: the parallel answer must be the serial one, row for row", what)
        .containsExactlyElementsOf(rids(serial));
    for (int i = 0; i < serial.size(); i++)
      assertThat(distance(parallel.get(i))).as("%s: distance of row %d", what, i)
          .isCloseTo(distance(serial.get(i)), within(1e-5f * Math.max(1f, Math.abs(distance(serial.get(i))))));
  }

  private static List<String> rids(final List<String> rows) {
    final List<String> out = new ArrayList<>(rows.size());
    for (final String row : rows)
      out.add(row.substring(0, row.indexOf('@')));
    return out;
  }

  private static float distance(final String row) {
    return Float.parseFloat(row.substring(row.indexOf('@') + 1));
  }

  private List<String> sqlNeighbors(final float[] query, final int k, final Map<String, Object> options) {
    final List<String> rows = new ArrayList<>();
    final String sql = options == null ?
        "SELECT expand(vector.neighbors('Doc[embedding]', ?, ?))" :
        "SELECT expand(vector.neighbors('Doc[embedding]', ?, ?, ?))";
    try (final ResultSet rs = options == null ? database.query("sql", sql, query, k) : database.query("sql", sql, query, k, options)) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        rows.add(r.getIdentity().orElseThrow() + "@" + r.getProperty("distance"));
      }
    }
    return rows;
  }

  private void populate(final String similarity, final String quantization) {
    final Random rng = new Random(BUCKETS * 1000 + similarity.length());
    database.transaction(() -> {
      final DocumentType t = database.getSchema().createDocumentType("Doc", BUCKETS);
      t.createProperty("id", Type.INTEGER);
      t.createProperty("tenant", Type.INTEGER);
      t.createProperty("embedding", Type.ARRAY_OF_FLOATS);
      for (int i = 0; i < NUM_VECTORS; i++) {
        final float[] v = randomVector(rng);
        vectors.add(v);
        database.newDocument("Doc").set("id", i).set("tenant", i % 4).set("embedding", v).save();
      }
    });
    database.command("sql", "CREATE INDEX ON Doc (embedding) LSM_VECTOR METADATA { \"dimensions\": " + DIMENSIONS
        + ", \"similarity\": \"" + similarity + "\"" + (quantization != null ? ", \"quantization\": \"" + quantization + "\"" : "")
        + " }");

    // The first search after a build loads or builds each graph on the caller; fan-out starts once they are resolved
    final List<LSMVectorIndex> indexes = subIndexes();
    assertThat(indexes).hasSize(BUCKETS);
    for (final LSMVectorIndex index : indexes)
      index.warmUp();
    for (final LSMVectorIndex index : indexes)
      assertThat(index.isGraphResolved()).isTrue();
  }

  private List<LSMVectorIndex> subIndexes() {
    final TypeIndex typeIndex = (TypeIndex) database.getSchema().getIndexByName("Doc[embedding]");
    final List<LSMVectorIndex> out = new ArrayList<>();
    for (final IndexInternal sub : typeIndex.getIndexesOnBuckets())
      out.add((LSMVectorIndex) sub);
    return out;
  }

  private static float[] randomVector(final Random rng) {
    final float[] v = new float[DIMENSIONS];
    for (int i = 0; i < DIMENSIONS; i++)
      v[i] = (float) rng.nextGaussian();
    return v;
  }
}
