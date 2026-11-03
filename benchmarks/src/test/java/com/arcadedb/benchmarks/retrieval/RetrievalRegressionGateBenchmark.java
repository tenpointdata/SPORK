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
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.StallAwareStopwatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval regression gate: every query of {@link QueryMix}, warm and single-threaded over a gate-scale dataset, must keep
 * its p95 within a headroom factor of the committed baseline ({@code retrieval-baselines.json}). The baseline is the number
 * this harness measured on the machine named in the file; the headroom absorbs the difference between machines and the run
 * to run noise, and the thing the gate catches is a query falling off its index onto a scan, which is a 10x to 1000x step, not
 * a 20% drift.
 * <p>
 * Timing follows the repository rule for wall-clock bounds: every sample is the elapsed nanoseconds minus the JVM-wide stall
 * observed inside it ({@link StallAwareStopwatch#jvmStallNanos()} differenced around each query), so a stop-the-world pause in
 * a long run cannot turn a healthy p95 red. The measured numbers are also written to
 * {@code target/retrieval-results/gate-measured.json}: copy that file over the baseline to re-baseline after an accepted change.
 * <p>
 * Knobs: {@code -Darcadedb.retrievalBench.gateRecords} (default 300,000), {@code gateSamples} (300), {@code gateHeadroom}
 * (overrides the file), {@code gateRoot} (default {@code target/retrieval-gate}, reused when a matching dataset is already there).
 */
@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalRegressionGateBenchmark {
  private static final String BASELINES = "/retrieval-baselines.json";

  private Database        database;
  private DatasetManifest manifest;
  private JSONObject      baselines;
  private double          headroom;
  private final JSONObject measured = new JSONObject();

  @BeforeAll
  void prepare() throws IOException {
    try (final InputStream in = RetrievalRegressionGateBenchmark.class.getResourceAsStream(BASELINES)) {
      assertThat(in).as("committed baselines " + BASELINES).isNotNull();
      baselines = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    final long records = BenchProps.longValue("gateRecords", baselines.getLong("records", 300_000L));
    final long seed = baselines.getLong("seed", 42L);
    headroom = Double.parseDouble(BenchProps.string("gateHeadroom", Double.toString(baselines.getDouble("headroom", 3.0))));

    final Path root = Paths.get(BenchProps.string("gateRoot", "target/retrieval-gate"));
    manifest = existingDataset(root, records, seed);
    if (manifest == null)
      manifest = new ReconDataGenerator(root, records, seed, 1, 20_000).generate();
    database = new DatabaseFactory(DatasetManifest.databasePath(root).toString()).open();
    ReconSchema.warmVectorIndex(database);
  }

  @AfterAll
  void close() throws IOException {
    if (database != null && database.isOpen())
      database.close();
    final Path out = BenchProps.results();
    Files.createDirectories(out);
    final JSONObject file = new JSONObject();
    file.put("records", manifest.records);
    file.put("seed", manifest.seed);
    file.put("headroom", headroom);
    file.put("hardware", BenchReport.hardwareLine());
    file.put("queries", measured);
    Files.writeString(out.resolve("gate-measured.json"), file.toString(2), StandardCharsets.UTF_8);
  }

  @Test
  void identLookup() {
    gate(QueryMix.Query.IDENT_LOOKUP);
  }

  @Test
  void pivot1Hop() {
    gate(QueryMix.Query.PIVOT_1HOP);
  }

  @Test
  void pivot2Hop() {
    gate(QueryMix.Query.PIVOT_2HOP);
  }

  @Test
  void structured() {
    gate(QueryMix.Query.STRUCTURED);
  }

  @Test
  void feed24h() {
    gate(QueryMix.Query.FEED_24H);
  }

  @Test
  void geoRadius14d() {
    gate(QueryMix.Query.GEO_RADIUS_14D);
  }

  @Test
  void fulltextTop20() {
    gate(QueryMix.Query.FULLTEXT_TOP20);
  }

  @Test
  void vectorKnn20() {
    gate(QueryMix.Query.VECTOR_KNN20);
  }

  @Test
  void hydrate200() {
    gate(QueryMix.Query.HYDRATE_200);
  }

  private void gate(final QueryMix.Query query) {
    final int samples = BenchProps.intValue("gateSamples", 300);
    final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(new QueryMix.Tables(manifest), manifest.seed + query.ordinal(),
        database);
    for (int i = 0; i < Math.max(10, samples / 6); i++)
      QueryMix.execute(database, query, sampler.params(query));

    final LatencyStats stats = new LatencyStats(samples);
    long rows = 0;
    for (int i = 0; i < samples; i++) {
      final Map<String, Object> params = sampler.params(query);
      final long stall0 = StallAwareStopwatch.jvmStallNanos();
      final long t0 = System.nanoTime();
      rows += QueryMix.execute(database, query, params);
      final long elapsed = System.nanoTime() - t0;
      final long stall = StallAwareStopwatch.jvmStallNanos() - stall0;
      stats.add(Math.max(0L, elapsed - stall));
    }
    final double p50Us = stats.percentileNanos(50) / 1_000.0, p95Us = stats.percentileNanos(95) / 1_000.0;
    final JSONObject m = new JSONObject();
    m.put("p50Us", Math.round(p50Us));
    m.put("p95Us", Math.round(p95Us));
    m.put("rowsPerQuery", rows / samples);
    measured.put(query.id, m);
    System.out.printf("gate %-14s p50 %9.1f us  p95 %9.1f us  %d rows/query%n", query.id, p50Us, p95Us, rows / samples);

    final JSONObject baseline = baselines.getJSONObject("queries").getJSONObject(query.id, null);
    assertThat(baseline).as("baseline for %s in %s", query.id, BASELINES).isNotNull();
    final double boundUs = baseline.getDouble("p95Us") * headroom;
    assertThat(p95Us).as("%s warm single-thread p95 stayed under %.0f us (baseline %.0f us x %.1f headroom, %d stall-discounted samples)%n"
            + "  This bound IS the assertion: loosening it deletes the test. A p95 past it on a healthy machine means the query "
            + "regressed, most likely off its index; profile it with the retrieval harness before touching the baseline.", query.id,
        boundUs, baseline.getDouble("p95Us"), headroom, samples).isLessThan(boundUs);
  }

  /** The dataset already at {@code root} when its manifest says the same records and seed, else null. */
  private static DatasetManifest existingDataset(final Path root, final long records, final long seed) {
    if (!Files.exists(DatasetManifest.manifestPath(root)))
      return null;
    try {
      final DatasetManifest m = DatasetManifest.read(root);
      return m.records == records && m.seed == seed ? m : null;
    } catch (final IOException e) {
      return null;
    }
  }
}
