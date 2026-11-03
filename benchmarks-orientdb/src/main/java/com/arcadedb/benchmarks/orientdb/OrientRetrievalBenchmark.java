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
package com.arcadedb.benchmarks.orientdb;

import com.arcadedb.benchmarks.retrieval.BenchProps;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.util.Statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Mode.SampleTime;

/**
 * The OrientDB leg: the nine queries of {@link OrientQueryMix} against the OrientDB copy of the dataset, embedded, under JMH
 * with the settings of the ArcadeDB embedded leg's warm mode ({@code SampleTime}, {@code forks=0}, the same
 * {@code -Darcadedb.retrievalBench.*} windows and thread levels), so the two tables compare row for row. One OrientDB session
 * per benchmark thread, the way OrientDB sessions are meant to be used.
 * <p>
 * {@code -Darcadedb.retrievalBench.mode=parity} instead runs every query {@code parityDraws} times (default 50) from a fixed
 * seed on one thread and prints the row totals, plus the plan of each SQL query: the check that both engines answer the
 * same questions with the same rows and that OrientDB uses its indexes.
 */
@State(Scope.Benchmark)
public class OrientRetrievalBenchmark {
  private static OrientDB              sharedOrientDB;
  private static OrientQueryMix.Tables sharedTables;

  private static synchronized void openShared() throws IOException {
    if (sharedOrientDB != null)
      return;
    final Path root = OrientDataset.root();
    final OrientDataset dataset = OrientDataset.read(root);
    sharedOrientDB = OrientDataset.open(root);
    sharedTables = new OrientQueryMix.Tables(dataset);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> sharedOrientDB.close()));
  }

  @State(Scope.Thread)
  public static class Session {
    ODatabaseSession             db;
    OrientQueryMix.ParamSampler sampler;

    @Setup(Level.Trial)
    public void open() throws IOException {
      openShared();
      db = OrientDataset.session(sharedOrientDB);
      sampler = new OrientQueryMix.ParamSampler(sharedTables, sharedTables.dataset.seed * 7919 + Thread.currentThread().getId());
    }

    @TearDown(Level.Trial)
    public void close() {
      db.activateOnCurrentThread();
      db.close();
    }
  }

  private static int run(final Session s, final OrientQueryMix.Query query) {
    return OrientQueryMix.execute(s.db, query, s.sampler.params(query));
  }

  @Benchmark
  public int identLookup(final Session s) {
    return run(s, OrientQueryMix.Query.IDENT_LOOKUP);
  }

  @Benchmark
  public int pivot1Hop(final Session s) {
    return run(s, OrientQueryMix.Query.PIVOT_1HOP);
  }

  @Benchmark
  public int pivot2Hop(final Session s) {
    return run(s, OrientQueryMix.Query.PIVOT_2HOP);
  }

  @Benchmark
  public int structured(final Session s) {
    return run(s, OrientQueryMix.Query.STRUCTURED);
  }

  @Benchmark
  public int feed24h(final Session s) {
    return run(s, OrientQueryMix.Query.FEED_24H);
  }

  @Benchmark
  public int geoRadius14d(final Session s) {
    return run(s, OrientQueryMix.Query.GEO_RADIUS_14D);
  }

  @Benchmark
  public int fulltextTop20(final Session s) {
    return run(s, OrientQueryMix.Query.FULLTEXT_TOP20);
  }

  @Benchmark
  public int vectorKnn20(final Session s) {
    return run(s, OrientQueryMix.Query.VECTOR_KNN20);
  }

  @Benchmark
  public int hydrate200(final Session s) {
    return run(s, OrientQueryMix.Query.HYDRATE_200);
  }

  // ---------------------------------------------------------------------------------------------------------------------

  public static void main(final String[] args) throws Exception {
    final String mode = BenchProps.string("mode", "warm");
    switch (mode) {
    case "warm" -> runWarm();
    case "parity" -> runParity();
    default -> throw new IllegalArgumentException("mode must be warm or parity, was '" + mode + "'");
    }
    System.exit(0);
  }

  private static String includePattern() {
    final String filter = BenchProps.string("queries", "");
    if (filter.isEmpty())
      return OrientRetrievalBenchmark.class.getName() + "\\..*";
    final StringBuilder sb = new StringBuilder(OrientRetrievalBenchmark.class.getName()).append("\\.(");
    final String[] ids = filter.split(",");
    for (int i = 0; i < ids.length; i++)
      sb.append(i > 0 ? "|" : "").append(OrientQueryMix.Query.byId(ids[i].trim()).id);
    return sb.append(")$").toString();
  }

  private static void runWarm() throws Exception {
    final int[] threadLevels = BenchProps.intList("threads", "1,8,32");
    final StringBuilder table = new StringBuilder(
        "| engine | query | threads | samples | p50 us | p95 us | p99 us | mean us | max us | q/s |\n|---|---|---|---|---|---|---|---|---|---|\n");
    final StringBuilder csv = new StringBuilder("engine,query,threads,samples,p50_us,p95_us,p99_us,mean_us,max_us,qps\n");
    for (final int threads : threadLevels) {
      final Options options = new OptionsBuilder().include(includePattern()).mode(SampleTime).timeUnit(TimeUnit.MICROSECONDS).forks(0)
          .threads(threads).warmupIterations(BenchProps.intValue("warmupIterations", 1))
          .warmupTime(TimeValue.seconds(BenchProps.intValue("warmupSeconds", 3))).measurementIterations(BenchProps.intValue("measureIterations", 3))
          .measurementTime(TimeValue.seconds(BenchProps.intValue("measureSeconds", 4))).shouldFailOnError(true).build();
      final Collection<RunResult> results = new Runner(options).run();
      for (final RunResult rr : results) {
        final String benchmark = rr.getParams().getBenchmark();
        final String id = benchmark.substring(benchmark.lastIndexOf('.') + 1);
        final Statistics s = rr.getPrimaryResult().getStatistics();
        final double qps = s.getMean() > 0 ? threads * 1_000_000.0 / s.getMean() : 0.0;
        table.append(String.format("| orientdb-3.2.24 | %s | %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.0f |%n", id, threads, s.getN(),
            s.getPercentile(50.0), s.getPercentile(95.0), s.getPercentile(99.0), s.getMean(), s.getMax(), qps));
        csv.append(String.format("orientdb-3.2.24,%s,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.0f%n", id, threads, s.getN(), s.getPercentile(50.0),
            s.getPercentile(95.0), s.getPercentile(99.0), s.getMean(), s.getMax(), qps));
      }
    }
    final Path out = BenchProps.results();
    Files.createDirectories(out);
    final String name = "orientdb-warm-" + BenchProps.records();
    Files.writeString(out.resolve(name + ".md"), table.toString());
    Files.writeString(out.resolve(name + ".csv"), csv.toString());
    System.out.println(table);
  }

  /** Row totals over a fixed sequence of parameters, and the plan OrientDB picked for each SQL query. */
  private static void runParity() throws IOException {
    openShared();
    final int draws = BenchProps.intValue("parityDraws", 50);
    final long seed = BenchProps.longValue("paritySeed", 12_345L);
    try (final ODatabaseSession db = OrientDataset.session(sharedOrientDB)) {
      for (final OrientQueryMix.Query query : OrientQueryMix.Query.values()) {
        final OrientQueryMix.ParamSampler sampler = new OrientQueryMix.ParamSampler(sharedTables, seed);
        long rows = 0;
        Map<String, Object> first = null;
        final long start = System.nanoTime();
        for (int i = 0; i < draws; i++) {
          final Map<String, Object> params = sampler.params(query);
          if (first == null)
            first = params;
          rows += OrientQueryMix.execute(db, query, params);
        }
        System.out.printf("PARITY %-14s rows=%d draws=%d meanMs=%.2f%n", query.id, rows, draws, (System.nanoTime() - start) / 1e6 / draws);
        if (query != OrientQueryMix.Query.VECTOR_KNN20 && BenchProps.bool("parityExplain", true))
          try (final OResultSet plan = db.query("EXPLAIN " + query.sql, first)) {
            if (plan.hasNext()) {
              final OResult r = plan.next();
              System.out.println(String.valueOf((Object) r.getProperty("executionPlanAsString")).indent(4));
            }
          }
      }
    }
  }
}
