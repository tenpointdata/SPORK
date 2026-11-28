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
package com.arcadedb.function.sql.vector;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.ivfvector.LSMIVFVectorIndex;
import com.arcadedb.index.vector.LSMVectorIndex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.query.sql.executor.RidSetSubquery;
import com.arcadedb.utility.Pair;
import com.arcadedb.utility.RidBitmap;
import com.arcadedb.utility.RidBitmapSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code filter} option of {@code vector.neighbors} given as a sub-query (docs/spork/workstreams/vector-filter-allowlist.md):
 * the sub-query is materialized as a compressed RID bitmap, from the index alone when one index answers it, and both vector
 * index kinds probe it with primitive pairs. Every answer must be the one the old representation (a {@code HashSet<RID>} of the
 * sub-query's rows) gave: same allow-list members, same neighbours in the same order, and the calling transaction's own
 * inserts and deletes seen by the sub-query.
 */
class VectorFilterAllowListTest extends TestHelper {
  private static final int    RECORDS = 4_000;
  private static final int    DIMS    = 8;
  private static final String GRAPH   = "Record[styleVec]";
  private static final String IVF     = "Record[ivfVec]";

  // STATE A: 0.1%, B: 1%, C: 20%, D: THE REST
  private static String stateOf(final int i) {
    if (i % 1000 == 7)
      return "A";
    if (i % 100 == 3)
      return "B";
    if (i % 5 == 1)
      return "C";
    return "D";
  }

  @Override
  public void beginTest() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Record");
      database.command("sql", "CREATE PROPERTY Record.state STRING");
      database.command("sql", "CREATE PROPERTY Record.tsPosted LONG");
      database.command("sql", "CREATE PROPERTY Record.note STRING");
      database.command("sql", "CREATE PROPERTY Record.styleVec ARRAY_OF_FLOATS");
      database.command("sql", "CREATE PROPERTY Record.ivfVec ARRAY_OF_FLOATS");
      database.command("sql", "CREATE INDEX ON Record (state, tsPosted) NOTUNIQUE");
      database.command("sql", "CREATE INDEX ON Record (styleVec) LSM_VECTOR METADATA {dimensions: " + DIMS + ", similarity: 'COSINE'}");
    });
    final Random random = new Random(42);
    database.transaction(() -> {
      for (int i = 0; i < RECORDS; i++) {
        final float[] vector = randomVector(random);
        database.newDocument("Record").set("state", stateOf(i)).set("tsPosted", (long) random.nextInt(100_000))
            .set("note", "n" + (i % 13)).set("styleVec", vector).set("ivfVec", vector).save();
      }
    });
    // A LOW EXACT-FILTER THRESHOLD, SO THE 1% AND WIDER FILTERS TAKE THE POSITION-FILTER SCAN AND ONLY THE 0.1% ONE THE EXACT PATH
    database.command("sql", "CREATE INDEX ON Record (ivfVec) LSM_IVF_VECTOR METADATA {dimensions: " + DIMS
        + ", similarity: 'COSINE', nlist: 16, nprobe: 4, exactFilterThreshold: 20}");
  }

  private static float[] randomVector(final Random random) {
    final float[] v = new float[DIMS];
    for (int d = 0; d < DIMS; d++)
      v[d] = (float) random.nextGaussian();
    return v;
  }

  /** The filters under test, the predicate each stands for, and the selectivity it names. */
  private record Filter(String name, String where, Map<String, Object> params, Predicate<Document> predicate) {
  }

  private List<Filter> filters() {
    final List<Filter> filters = new ArrayList<>();
    filters.add(new Filter("0.1%", "state = :state", Map.of("state", "A"), d -> "A".equals(d.getString("state"))));
    filters.add(new Filter("1%", "state = :state", Map.of("state", "B"), d -> "B".equals(d.getString("state"))));
    filters.add(new Filter("20%", "state = :state", Map.of("state", "C"), d -> "C".equals(d.getString("state"))));
    filters.add(new Filter("20% range", "state = :state AND tsPosted >= :since", Map.of("state", "C", "since", 50_000L),
        d -> "C".equals(d.getString("state")) && d.getLong("tsPosted") >= 50_000L));
    filters.add(new Filter("100%", "state IN ['A', 'B', 'C', 'D']", Map.of(), d -> true));
    filters.add(new Filter("empty", "state = :state", Map.of("state", "Z"), d -> false));
    return filters;
  }

  private Set<RID> groundTruth(final Predicate<Document> predicate) {
    final Set<RID> expected = new HashSet<>();
    final Iterator<Record> it = database.iterateType("Record", true);
    while (it.hasNext()) {
      final Document document = (Document) it.next();
      if (predicate.test(document))
        expected.add(document.getIdentity());
    }
    return expected;
  }

  private static Map<String, Object> withQuery(final Map<String, Object> params, final float[] query) {
    final Map<String, Object> all = new HashMap<>(params);
    all.put("vector", query);
    return all;
  }

  private List<Pair<RID, Float>> search(final String sql, final Map<String, Object> params) {
    final List<Pair<RID, Float>> answer = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", sql, params)) {
      while (rs.hasNext()) {
        final Result row = rs.next();
        answer.add(new Pair<>(row.getIdentity().get(), ((Number) row.getProperty("distance")).floatValue()));
      }
    }
    return answer;
  }

  private static String neighbors(final String index, final String filter, final String extraOptions) {
    return "SELECT expand(`vector.neighbors`('" + index + "', :vector, 10, { filter: " + filter + extraOptions + " }))";
  }

  @Test
  void indexOnlySubqueryNamesTheRecordsTheWhereSelects() {
    for (final Filter filter : filters()) {
      final Set<RID> expected = groundTruth(filter.predicate());
      final RidBitmapSet indexOnly = materialize("SELECT @rid FROM Record WHERE " + filter.where(), filter.params(), true);
      assertThat(new HashSet<>(indexOnly)).as(filter.name()).isEqualTo(expected);
      // A RESIDUAL CONDITION THE INDEX CANNOT ANSWER TAKES THE GENERAL PATH, WHICH READS THE RECORDS
      final RidBitmapSet general = materialize("SELECT FROM Record WHERE " + filter.where() + " AND note IS NOT NULL",
          filter.params(), false);
      assertThat(new HashSet<>(general)).as(filter.name()).isEqualTo(expected);
    }
  }

  @Test
  void searchesAnswerWhatTheHashSetAllowListAnswered() {
    final LSMVectorIndex graph = (LSMVectorIndex) bucketIndex(GRAPH);
    final LSMIVFVectorIndex ivf = (LSMIVFVectorIndex) bucketIndex(IVF);
    final Random random = new Random(7);
    for (final Filter filter : filters()) {
      final Set<RID> hashSet = groundTruth(filter.predicate());
      final RidBitmap bitmap = new RidBitmap();
      for (final RID rid : hashSet)
        bitmap.add(rid);
      final RidBitmapSet compressed = new RidBitmapSet(bitmap);
      for (int q = 0; q < 5; q++) {
        final float[] query = randomVector(random);
        database.transaction(() -> {
          assertThat(graph.findNeighborsFromVector(query, 10, compressed)).as(filter.name())
              .isEqualTo(graph.findNeighborsFromVector(query, 10, hashSet));
          assertThat(ivf.findNeighbors(query, 10, 4, -1, compressed)).as(filter.name())
              .isEqualTo(ivf.findNeighbors(query, 10, 4, -1, hashSet));
        });
      }
    }
  }

  @Test
  void subqueryFilterAnswersWhatTheRidListFilterAnswers() {
    final Random random = new Random(11);
    for (final String index : List.of(GRAPH, IVF))
      for (final Filter filter : filters()) {
        final List<RID> rids = new ArrayList<>(groundTruth(filter.predicate()));
        for (int q = 0; q < 5; q++) {
          final float[] query = randomVector(random);
          final Map<String, Object> params = withQuery(filter.params(), query);
          final long indexOnlyBefore = RidSetSubquery.indexOnlyMaterializations();
          final List<Pair<RID, Float>> bySubquery = search(
              neighbors(index, "(SELECT @rid FROM Record WHERE " + filter.where() + ")", ""), params);
          // THE STATEMENT TOOK THE INDEX-ONLY PATH: NO RECORD WAS READ TO BUILD THE ALLOW-LIST
          assertThat(RidSetSubquery.indexOnlyMaterializations()).as(index + " " + filter.name()).isGreaterThan(indexOnlyBefore);
          final Map<String, Object> listParams = withQuery(Map.of("rids", rids), query);
          final List<Pair<RID, Float>> byList = search(neighbors(index, ":rids", ""), listParams);
          assertThat(bySubquery).as(index + " " + filter.name()).isEqualTo(byList);
          final Set<RID> allowed = new HashSet<>(rids);
          for (final Pair<RID, Float> row : bySubquery)
            assertThat(allowed).as(index + " " + filter.name()).contains(row.getFirst());
          if (rids.isEmpty())
            assertThat(bySubquery).isEmpty();
          else
            assertThat(bySubquery).hasSize(Math.min(10, rids.size()));
        }
      }
  }

  @Test
  void subqueryLiftedIntoALetAnswersEveryRowTheSame() {
    // AN OUTER STATEMENT WITH A TARGET: THE SUB-QUERY IS LIFTED INTO A LET RUN ONCE, AND EVERY ROW'S CALL PROBES THE SAME SET
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Probe");
      database.newDocument("Probe").set("v", new float[] { 1, 0, 0, 0, 0, 0, 0, 0 }).save();
      database.newDocument("Probe").set("v", new float[] { 0, 1, 0, 0, 0, 0, 0, 0 }).save();
    });
    for (final String index : List.of(GRAPH, IVF)) {
      final Set<RID> allowed = groundTruth(d -> "B".equals(d.getString("state")));
      final long indexOnlyBefore = RidSetSubquery.indexOnlyMaterializations();
      final long rowsBefore = RidSetSubquery.rowMaterializations();
      try (final ResultSet rs = database.query("sql", "SELECT v, `vector.neighbors`('" + index
          + "', v, 5, { filter: (SELECT @rid FROM Record WHERE state = 'B') }) AS hits FROM Probe")) {
        int rows = 0;
        while (rs.hasNext()) {
          final Result row = rs.next();
          final List<?> hits = row.getProperty("hits");
          assertThat(hits).hasSize(5);
          final float[] v = row.getProperty("v");
          final List<Pair<RID, Float>> byList = search(neighbors(index, ":rids", "").replace(", 10,", ", 5,"),
              Map.of("vector", v, "rids", new ArrayList<>(allowed)));
          final List<RID> got = new ArrayList<>();
          for (final Object hit : hits)
            got.add((RID) ((Map<?, ?>) hit).get("@rid"));
          final List<RID> want = new ArrayList<>();
          for (final Pair<RID, Float> p : byList)
            want.add(p.getFirst());
          assertThat(got).isEqualTo(want);
          ++rows;
        }
        assertThat(rows).isEqualTo(2);
      }
      // ONE MATERIALIZATION FOR THE TWO ROWS (THE byList SEARCHES IN BETWEEN TAKE NO SUB-QUERY)
      assertThat(RidSetSubquery.indexOnlyMaterializations() - indexOnlyBefore).as(index).isEqualTo(1);
      assertThat(RidSetSubquery.rowMaterializations()).as(index).isEqualTo(rowsBefore);
    }
  }

  @Test
  void subqueryFilterSeesTheTransactionsOwnInsertsAndDeletes() {
    final float[] query = { 1, 1, 1, 1, 1, 1, 1, 1 };
    for (final String index : List.of(GRAPH, IVF)) {
      database.begin();
      try {
        // TWO NEW RECORDS OF STATE B RIGHT ON THE QUERY, AND EVERY COMMITTED B RECORD WITH tsPosted < 50000 DELETED
        final MutableDocument near1 = database.newDocument("Record").set("state", "B").set("tsPosted", 1L)
            .set("styleVec", query).set("ivfVec", query);
        near1.save();
        final MutableDocument near2 = database.newDocument("Record").set("state", "B").set("tsPosted", 2L)
            .set("styleVec", new float[] { 1, 1, 1, 1, 1, 1, 1, 0.9f }).set("ivfVec", new float[] { 1, 1, 1, 1, 1, 1, 1, 0.9f });
        near2.save();
        final Set<RID> deleted = new HashSet<>();
        try (final ResultSet rs = database.query("sql", "SELECT FROM Record WHERE state = 'B' AND tsPosted < 50000 AND tsPosted > 2")) {
          while (rs.hasNext()) {
            final Document d = rs.next().getElement().get();
            deleted.add(d.getIdentity());
            d.delete();
          }
        }
        assertThat(deleted).isNotEmpty();

        final Set<RID> expected = groundTruth(d -> "B".equals(d.getString("state")));
        assertThat(expected).contains(near1.getIdentity(), near2.getIdentity()).doesNotContainAnyElementsOf(deleted);
        assertThat(new HashSet<>(materialize("SELECT @rid FROM Record WHERE state = 'B'", Map.of(), true))).isEqualTo(expected);

        final List<Pair<RID, Float>> bySubquery = search(neighbors(index, "(SELECT @rid FROM Record WHERE state = 'B')", ""),
            Map.of("vector", query));
        final List<Pair<RID, Float>> byList = search(neighbors(index, ":rids", ""),
            Map.of("vector", query, "rids", new ArrayList<>(expected)));
        assertThat(bySubquery).as(index).isEqualTo(byList);
        assertThat(bySubquery.get(0).getFirst()).as(index).isEqualTo(near1.getIdentity());
        for (final Pair<RID, Float> row : bySubquery)
          assertThat(expected).as(index).contains(row.getFirst());
      } finally {
        database.rollback();
      }
    }
  }

  @Test
  void theSameIndexOnlySubqueryInOneStatementIsMaterializedOnce() {
    // TWO SEARCHES OVER THE SAME FILTER IN ONE STATEMENT: BOTH ANSWER, AND THE SECOND SUB-QUERY IS THE FIRST ONE'S SET
    final long indexOnlyBefore = RidSetSubquery.indexOnlyMaterializations();
    final long reusedBefore = RidSetSubquery.reusedMaterializations();
    try (final ResultSet rs = database.query("sql", """
        SELECT `vector.neighbors`('Record[styleVec]', :vector, 3, { filter: (SELECT @rid FROM Record WHERE state = 'B') }) AS a,
               `vector.neighbors`('Record[ivfVec]', :vector, 3, { filter: (SELECT @rid FROM Record WHERE state = 'B') }) AS b
        FROM Record WHERE state = 'A' LIMIT 1""", Map.of("vector", new float[] { 1, 0, 0, 0, 0, 0, 0, 0 }))) {
      final Result row = rs.next();
      assertThat((List<?>) row.getProperty("a")).hasSize(3);
      assertThat((List<?>) row.getProperty("b")).hasSize(3);
    }
    assertThat(RidSetSubquery.indexOnlyMaterializations() - indexOnlyBefore).isEqualTo(1);
    assertThat(RidSetSubquery.reusedMaterializations() - reusedBefore).isEqualTo(1);
    final RidBitmapSet[] sets = RidSetSubqueryTestSupport.materializeInOneStatement(database,
        "SELECT @rid FROM Record WHERE state = 'B'", "SELECT @rid FROM Record WHERE state = 'B'",
        "SELECT @rid FROM Record WHERE state = 'C'",
        // THE GENERAL PATH IS REUSED AS WELL
        "SELECT FROM Record WHERE state = 'B' AND note IS NOT NULL", "SELECT FROM Record WHERE state = 'B' AND note IS NOT NULL");
    assertThat(sets[1]).isSameAs(sets[0]);
    // ANOTHER SUB-QUERY IS NOT ANSWERED BY THE FIRST ONE'S SET
    assertThat(sets[2]).isNotSameAs(sets[0]);
    assertThat(new HashSet<>(sets[2])).isEqualTo(groundTruth(d -> "C".equals(d.getString("state"))));
    assertThat(sets[4]).isSameAs(sets[3]);
    assertThat(new HashSet<>(sets[3])).isEqualTo(new HashSet<>(sets[0]));
  }

  @Test
  void aSubqueryThatCanAnswerDifferentlyIsNotReused() {
    // A VARIABLE, A POSITIONAL PARAMETER, A NON-REPEATABLE FUNCTION: EACH EVALUATION RUNS THE SUB-QUERY AGAIN
    for (final String subquery : List.of("SELECT @rid FROM Record WHERE state = $wanted",
        "SELECT @rid FROM Record WHERE state = 'B' AND tsPosted < randomInt(1000000) + 1000000")) {
      final RidBitmapSet[] sets = RidSetSubqueryTestSupport.materializeInOneStatement(database, subquery, subquery);
      assertThat(sets[1]).as(subquery).isNotSameAs(sets[0]);
    }
    // A WRITE BETWEEN TWO EVALUATIONS (A CALLER WRITING WHILE IT ITERATES) IS SEEN BY THE SECOND ONE
    database.transaction(() -> {
      final RID[] added = new RID[1];
      final RidBitmapSet[] sets = RidSetSubqueryTestSupport.materializeAround(database, "SELECT @rid FROM Record WHERE state = 'B'",
          () -> added[0] = database.newDocument("Record").set("state", "B").set("tsPosted", 5L).save().getIdentity());
      assertThat(sets[0]).doesNotContain(added[0]);
      assertThat(sets[1]).contains(added[0]);
    });
  }

  @Test
  void aFilterRowThatNamesNoRidStillFailsLoudly() {
    assertThatThrownBy(() -> {
      try (final ResultSet rs = database.query("sql",
          neighbors(GRAPH, "(SELECT state FROM Record WHERE state = 'A')", ""), Map.of("vector", new float[DIMS]))) {
        rs.next();
      }
    }).hasMessageContaining("must contain RIDs");
  }

  @Test
  void aSubqueryWithAModifierKeepsItsValue() {
    // (SELECT list(@rid) AS l ...).l IS NOT THE SUB-QUERY ALONE: IT STAYS A LIST OF ROWS AND IS FLATTENED BY THE OPTION
    final Set<RID> expected = groundTruth(d -> "B".equals(d.getString("state")));
    final List<Pair<RID, Float>> answer = search(
        neighbors(GRAPH, "(SELECT list(@rid) AS l FROM Record WHERE state = 'B').l", ""), Map.of("vector", new float[] { 1, 1, 1, 1, 1, 1, 1, 1 }));
    assertThat(answer).hasSize(10);
    for (final Pair<RID, Float> row : answer)
      assertThat(expected).contains(row.getFirst());
  }

  private Index bucketIndex(final String name) {
    final Index[] buckets = ((TypeIndex) database.getSchema().getIndexByName(name)).getIndexesOnBuckets();
    assertThat(buckets).hasSize(1);
    return buckets[0];
  }

  /** The allow-list a sub-query is materialized into, through the same entry point the statement uses. */
  private RidBitmapSet materialize(final String subquery, final Map<String, Object> params, final boolean expectIndexOnly) {
    return RidSetSubqueryTestSupport.materialize(database, subquery, params, expectIndexOnly);
  }
}
