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
package com.arcadedb.benchmarks.retrieval;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates a small dataset once and checks the three things the harness rests on: the generator writes the shape it
 * promises (types, indexes, Zipf hubs, manifest), every query of the mix runs embedded and answers the expected shape, and
 * the HTTP leg answers the same rows in both encodings.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconDatasetTest {
  private static final Path ROOT    = Paths.get("target", "retrieval-bench-test");
  private static final long RECORDS = 3_000;

  private DatasetManifest                     manifest;
  private Database                            database;
  private final Map<QueryMix.Query, Integer>  embeddedRows = new EnumMap<>(QueryMix.Query.class);
  private final Map<QueryMix.Query, Map<String, Object>> fixedParams = new EnumMap<>(QueryMix.Query.class);

  @BeforeAll
  void generate() throws Exception {
    manifest = new ReconDataGenerator(ROOT, RECORDS, 42L, 1, 500).generate();
    database = new DatabaseFactory(DatasetManifest.databasePath(ROOT).toString()).open();
    ReconSchema.warmVectorIndex(database);
  }

  @AfterAll
  void cleanup() {
    if (database != null && database.isOpen())
      database.close();
    FileUtils.deleteRecursively(ROOT.toFile());
  }

  @Test
  void existingDatasetIsRecognisedOnlyForTheSameRecordsAndSeed() {
    assertThat(ReconDataGenerator.existingDataset(ROOT, RECORDS, 42L)).isNotNull();
    assertThat(ReconDataGenerator.existingDataset(ROOT, RECORDS, 43L)).isNull();
    assertThat(ReconDataGenerator.existingDataset(ROOT, RECORDS + 1, 42L)).isNull();
    assertThat(ReconDataGenerator.existingDataset(ROOT.resolve("absent"), RECORDS, 42L)).isNull();
  }

  @Test
  void manifestDescribesTheDataset() throws Exception {
    assertThat(manifest.records).isEqualTo(RECORDS);
    assertThat(manifest.recordVertices).isEqualTo(RECORDS);
    assertThat(manifest.identifierVertices).isEqualTo(
        (long) manifest.phonePool() + manifest.emailPool() + manifest.handlePool() + manifest.imagePool());
    assertThat(manifest.edges).isGreaterThan(RECORDS); // at least one phone per record, plus the optional ones
    assertThat(manifest.tsMin).isLessThan(manifest.tsMax);
    assertThat(manifest.tsMax).isLessThanOrEqualTo(ReconDomain.NOW_MS);
    assertThat(manifest.sampleRecordRids).hasSize((int) RECORDS); // every record is sampled below 20,000
    assertThat(manifest.timingsMs).containsKeys("identifiers", "records+outEdges", "indexes(total)", "total");

    final DatasetManifest reread = DatasetManifest.read(ROOT);
    assertThat(reread.toJson().toString()).isEqualTo(manifest.toJson().toString());
  }

  @Test
  void schemaHasEveryTypeAndIndex() {
    final Schema schema = database.getSchema();
    assertThat(schema.existsType(ReconSchema.RECORD)).isTrue();
    for (final String t : ReconSchema.IDENTIFIER_TYPES)
      assertThat(schema.existsType(t)).isTrue();
    for (final String e : ReconSchema.EDGE_TYPES)
      assertThat(schema.existsType(e)).isTrue();
    for (final String index : ReconSchema.ALL_INDEXES)
      assertThat(schema.existsIndex(index)).as("index %s", index).isTrue();
    assertThat(schema.getIndexByName(ReconSchema.IDX_PHONE).isUnique()).isTrue();
    assertThat(schema.getIndexByName(ReconSchema.IDX_TEXT).getType()).isEqualTo(Schema.INDEX_TYPE.FULL_TEXT);
    assertThat(schema.getIndexByName(ReconSchema.IDX_LOCATION).getType()).isEqualTo(Schema.INDEX_TYPE.GEOSPATIAL);
    assertThat(schema.getIndexByName(ReconSchema.IDX_STYLE_VECTOR).getType()).isEqualTo(Schema.INDEX_TYPE.LSM_VECTOR);
    assertThat(manifest.indexEntries.get(ReconSchema.IDX_TS)).isEqualTo(RECORDS);
    assertThat(manifest.indexEntries.get(ReconSchema.IDX_SERVICES)).isGreaterThan(RECORDS); // one entry per list item
    assertThat(manifest.indexEntries.get(ReconSchema.IDX_TEXT)).isGreaterThan(RECORDS * ReconDomain.MIN_WORDS / 2); // one per token
  }

  @Test
  void countsMatchTheManifest() {
    assertThat(database.countType(ReconSchema.RECORD, false)).isEqualTo(RECORDS);
    long identifiers = 0;
    for (final String t : ReconSchema.IDENTIFIER_TYPES)
      identifiers += database.countType(t, false);
    assertThat(identifiers).isEqualTo(manifest.identifierVertices);
    // lightweight edges have no record of their own, so they are counted from the vertices' edge lists
    long edges = 0;
    for (final java.util.Iterator<com.arcadedb.database.Record> it = database.iterateType(ReconSchema.RECORD, false); it.hasNext(); )
      edges += it.next().asVertex().countEdges(Vertex.DIRECTION.OUT);
    assertThat(edges).isEqualTo(manifest.edges);
    for (final String e : ReconSchema.EDGE_TYPES)
      assertThat(database.countType(e, false)).as("%s is lightweight: no edge records", e).isZero();
  }

  @Test
  void identifiersAreZipfSkewedSoRankZeroIsAHub() {
    final int hubDegree = inDegree(ReconDomain.phoneKey(0));
    final int tailDegree = inDegree(ReconDomain.phoneKey(manifest.phonePool() / 2));
    assertThat(hubDegree).as("the top-ranked phone is a hub").isGreaterThan(30);
    assertThat(hubDegree).as("rank 0 (%d) dwarfs the middle of the pool (%d)", hubDegree, tailDegree).isGreaterThan(5 * tailDegree);
    final QueryMix.Tables tables = new QueryMix.Tables(manifest);
    assertThat(tables.referencedPhoneMass).isBetween(0.5, 1.0);
    assertThat(tables.hubPhoneMass).as("no phone reaches HUB_DEGREE at 3,000 records").isZero();
    final QueryMix.Tables large = new QueryMix.Tables(manifestOf(5_000_000L));
    assertThat(large.hubPhoneMass).as("at 5M records the head of the pool is hubs").isBetween(0.01, 0.5);
    assertThat(large.hubPhoneMass).isLessThan(large.referencedPhoneMass);
  }

  @Test
  void everyQueryRunsEmbeddedAndAnswersItsShape() {
    final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(new QueryMix.Tables(manifest), 1L, database);
    for (final QueryMix.Query q : QueryMix.Query.values()) {
      final Map<String, Object> params = sampler.params(q);
      fixedParams.put(q, params);
      final int rows = QueryMix.execute(database, q, params);
      embeddedRows.put(q, rows);
      switch (q) {
      case IDENT_LOOKUP -> assertThat(rows).isEqualTo(1);
      case PIVOT_1HOP, PIVOT_2HOP -> assertThat(rows).as("%s with %s", q.id, params).isBetween(1, QueryMix.PIVOT_LIMIT);
      case STRUCTURED -> assertThat(rows).isBetween(0, QueryMix.SEARCH_LIMIT);
      case FEED_24H -> assertThat(rows).isBetween(1, QueryMix.FEED_LIMIT);
      case GEO_RADIUS_14D -> assertThat(rows).isBetween(0, QueryMix.PIVOT_LIMIT);
      case FULLTEXT_TOP20 -> assertThat(rows).isBetween(1, QueryMix.TEXT_LIMIT);
      case VECTOR_KNN20 -> assertThat(rows).isEqualTo(QueryMix.KNN_K);
      case HYDRATE_200 -> assertThat(rows).isEqualTo(QueryMix.HYDRATE_COUNT);
      }
    }
  }

  @Test
  void newestFirstQueriesAreOrderedEvenWhenThePlanHasNoSortStep() {
    // feed24h has no ORDER BY step in its plan: the index fetch is expected to deliver the order. Pin that it does.
    final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(new QueryMix.Tables(manifest), 5L, database);
    for (final QueryMix.Query q : new QueryMix.Query[] { QueryMix.Query.FEED_24H, QueryMix.Query.PIVOT_1HOP, QueryMix.Query.STRUCTURED }) {
      final Map<String, Object> params = sampler.params(q);
      long previous = Long.MAX_VALUE;
      int rows = 0;
      try (final ResultSet rs = database.query("sql", q.sql, params)) {
        while (rs.hasNext()) {
          final long ts = rs.next().<Long>getProperty("tsPosted");
          assertThat(ts).as("%s row %d is not newer than the row before it", q.id, rows).isLessThanOrEqualTo(previous);
          previous = ts;
          rows++;
        }
      }
      if (q == QueryMix.Query.FEED_24H) {
        assertThat(rows).isGreaterThan(1);
        assertThat(previous).isGreaterThanOrEqualTo((Long) params.get("since"));
      }
    }
  }

  @Test
  void geoRadiusAnswersOnlyRecordsInsideTheRadiusAndWindow() {
    final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(new QueryMix.Tables(manifest), 3L, database);
    final Map<String, Object> params = sampler.params(QueryMix.Query.GEO_RADIUS_14D);
    final long since = (Long) params.get("since");
    try (final ResultSet rs = database.query("sql", QueryMix.Query.GEO_RADIUS_14D.sql, params)) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        assertThat(r.<Long>getProperty("tsPosted")).isGreaterThanOrEqualTo(since);
        assertThat(r.<String>getProperty("location")).startsWith("POINT (");
      }
    }
  }

  @Test
  void httpAnswersTheSameRowsInBothEncodings() throws Exception {
    // the server needs the files to itself
    database.close();
    try (final BenchServer server = BenchServer.start(ROOT)) {
      assertThat(server.httpPort()).isGreaterThan(0);
      final HttpQueryClient client = new HttpQueryClient(server.baseUrl(), DatasetManifest.DATABASE_NAME, BenchServer.ROOT_USER,
          BenchServer.ROOT_PASSWORD);
      final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(new QueryMix.Tables(manifest), 1L, null);
      for (final QueryMix.Query q : QueryMix.Query.values()) {
        final Map<String, Object> params = sampler.params(q);
        final int json = client.queryJson(q.sql, params);
        final int ndjson = client.queryNdjson(q.sql, params);
        assertThat(ndjson).as("%s: ndjson vs json", q.id).isEqualTo(json);
        // the sampler is seeded like the embedded one above, so the parameters and therefore the rows are the same
        if (embeddedRows.containsKey(q))
          assertThat(json).as("%s: http vs embedded", q.id).isEqualTo(embeddedRows.get(q));
      }
    } finally {
      database = new DatabaseFactory(DatasetManifest.databasePath(ROOT).toString()).open();
    }
  }

  private static DatasetManifest manifestOf(final long records) {
    final DatasetManifest m = new DatasetManifest();
    m.records = records;
    m.seed = 42;
    return m;
  }

  private int inDegree(final String phoneKey) {
    try (final ResultSet rs = database.query("sql", "SELECT FROM Phone WHERE key = :key", Map.of("key", phoneKey))) {
      assertThat(rs.hasNext()).isTrue();
      final Vertex phone = rs.next().getVertex().orElseThrow();
      return (int) phone.countEdges(Vertex.DIRECTION.IN, ReconSchema.HAS_PHONE);
    }
  }

  @Test
  void fixedWorkloadAnswersEveryQueryAndTheSameRowsTwice() {
    final FixedWorkload.Settings settings = new FixedWorkload.Settings(12_345L, 3, Map.of("vectorKnn20", 2), 2, 0);
    final List<FixedWorkload.Result> first = new FixedWorkload(EmbeddedRetrievalBenchmark.fixedEngine(database, new QueryMix.Tables(manifest)),
        settings).run(EmbeddedRetrievalBenchmark.queryIds());
    final List<FixedWorkload.Result> second = new FixedWorkload(EmbeddedRetrievalBenchmark.fixedEngine(database, new QueryMix.Tables(manifest)),
        settings).run(EmbeddedRetrievalBenchmark.queryIds());

    assertThat(first).extracting(FixedWorkload.Result::query)
        .containsExactly(Arrays.stream(QueryMix.Query.values()).map(q -> q.id).toArray(String[]::new));
    for (final FixedWorkload.Result r : first) {
      assertThat(r.draws()).isEqualTo(r.query().equals("vectorKnn20") ? 2 : 3);
      assertThat(r.medianMs()).isPositive();
      assertThat(r.medianMs()).isLessThanOrEqualTo(r.maxMs());
    }
    assertThat(first.get(0).rows()).isEqualTo(3); // identLookup: one phone per draw
    // the same seed asks the same questions: the row totals are what the OrientDB leg's totals are compared against
    assertThat(second).extracting(FixedWorkload.Result::rows).isEqualTo(first.stream().map(FixedWorkload.Result::rows).toList());
  }
}
