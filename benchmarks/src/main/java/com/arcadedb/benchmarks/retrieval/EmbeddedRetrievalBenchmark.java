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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.util.Statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Mode.SampleTime;
import static org.openjdk.jmh.annotations.Mode.SingleShotTime;

/**
 * The embedded leg: the nine queries of {@link QueryMix} against the generated dataset, through the Java API, under JMH.
 * Three modes, chosen with {@code -Darcadedb.retrievalBench.mode=}:
 * <ul>
 *   <li>{@code warm} (default): {@code SampleTime} at 1, 8 and 32 threads, in this JVM ({@code forks=0}) so the 27 trials share
 *   one open database and one JIT, the way a server does; p50/p95/p99 come from JMH's sample distribution, throughput from
 *   Little's law ({@code threads / mean}) since the clients are closed-loop;</li>
 *   <li>{@code cold}: {@code SingleShotTime}, one fork per query, no warm-up, one invocation: a fresh JVM whose page cache is
 *   empty and whose vector graph is not loaded. Drop the OS file cache first ({@code sudo purge}) for a truly cold disk;</li>
 *   <li>{@code profile}: one fork per profiled query at 8 threads with Java Flight Recorder on, then {@link JfrTopFrames}
 *   turns each recording into the top-frame tables.</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class EmbeddedRetrievalBenchmark {
  /** The open database, shared by every trial of a {@code forks=0} run and opened once per fork otherwise. */
  private static Database        sharedDatabase;
  private static QueryMix.Tables sharedTables;

  @State(Scope.Benchmark)
  public static class Dataset {
    Database        database;
    QueryMix.Tables tables;

    @Setup(Level.Trial)
    public void open() throws IOException {
      synchronized (EmbeddedRetrievalBenchmark.class) {
        if (sharedDatabase == null) {
          final Path root = BenchProps.root();
          final DatasetManifest manifest = DatasetManifest.read(root);
          sharedDatabase = new DatabaseFactory(DatasetManifest.databasePath(root).toString()).open();
          sharedTables = new QueryMix.Tables(manifest);
          if (!BenchProps.bool("cold", false))
            ReconSchema.warmVectorIndex(sharedDatabase);
          Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (sharedDatabase.isOpen())
              sharedDatabase.close();
          }));
        }
        database = sharedDatabase;
        tables = sharedTables;
      }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      // closed by the shutdown hook: the next trial of a forks=0 run reuses it
    }
  }

  @State(Scope.Thread)
  public static class Params {
    QueryMix.ParamSampler sampler;

    @Setup(Level.Trial)
    public void init(final Dataset dataset) {
      sampler = new QueryMix.ParamSampler(dataset.tables, dataset.tables.manifest().seed * 7919 + Thread.currentThread().getId(),
          dataset.database);
    }
  }

  private static int run(final Dataset d, final Params p, final QueryMix.Query query) {
    final Map<String, Object> params = p.sampler.params(query);
    return QueryMix.execute(d.database, query, params);
  }

  @Benchmark
  public int identLookup(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.IDENT_LOOKUP);
  }

  @Benchmark
  public int pivot1Hop(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.PIVOT_1HOP);
  }

  @Benchmark
  public int pivot2Hop(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.PIVOT_2HOP);
  }

  @Benchmark
  public int structured(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.STRUCTURED);
  }

  @Benchmark
  public int feed24h(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.FEED_24H);
  }

  @Benchmark
  public int geoRadius14d(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.GEO_RADIUS_14D);
  }

  @Benchmark
  public int fulltextTop20(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.FULLTEXT_TOP20);
  }

  @Benchmark
  public int vectorKnn20(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.VECTOR_KNN20);
  }

  @Benchmark
  public int hydrate200(final Dataset d, final Params p) {
    return run(d, p, QueryMix.Query.HYDRATE_200);
  }

  // ---------------------------------------------------------------------------------------------------------------------

  public static void main(final String[] args) throws Exception {
    final String mode = BenchProps.string("mode", "warm");
    final DatasetManifest manifest = DatasetManifest.read(BenchProps.root());
    final Path results = BenchProps.results();
    Files.createDirectories(results);
    System.out.println("Hardware: " + BenchReport.hardwareLine());
    switch (mode) {
    case "warm" -> runWarm(manifest, results);
    case "cold" -> runCold(manifest, results);
    case "profile" -> runProfile(manifest, results);
    case "fixed" -> runFixed(manifest, results);
    default -> throw new IllegalArgumentException("mode must be warm, cold, profile or fixed, was '" + mode + "'");
    }
  }

  /** The query ids of {@code queries}, or all nine in mix order. */
  static List<String> queryIds() {
    final String filter = BenchProps.string("queries", "");
    final List<String> ids = new ArrayList<>();
    if (filter.isEmpty())
      for (final QueryMix.Query q : QueryMix.Query.values())
        ids.add(q.id);
    else
      for (final String id : filter.split(","))
        ids.add(QueryMix.Query.byId(id.trim()).id);
    return ids;
  }

  /**
   * The {@link FixedWorkload} against this dataset, in this JVM, one thread: the same parameter sets the OrientDB leg's fixed
   * mode answers, so the two tables compare query for query. {@code engineLabel} names the engine in the table (default
   * {@code arcadedb}), which is how runs against different engine builds stay apart.
   */
  private static void runFixed(final DatasetManifest manifest, final Path results) throws IOException {
    final String label = BenchProps.string("engineLabel", "arcadedb");
    final QueryMix.Tables tables = new QueryMix.Tables(manifest);
    try (final Database database = new DatabaseFactory(DatasetManifest.databasePath(BenchProps.root()).toString()).open()) {
      ReconSchema.warmVectorIndex(database);
      final List<FixedWorkload.Result> rows = new FixedWorkload(fixedEngine(database, tables), FixedWorkload.Settings.fromProperties())
          .run(queryIds());
      System.out.println(FixedWorkload.write(results, "fixed-" + label + "-" + manifest.records, label, rows));
    }
  }

  /** The ArcadeDB side of the {@link FixedWorkload}: {@link QueryMix}'s sampler and executor over one open database. */
  static FixedWorkload.Engine fixedEngine(final Database database, final QueryMix.Tables tables) {
    return new FixedWorkload.Engine() {
      @Override
      public List<Map<String, Object>> draw(final String queryId, final long seed, final int count) {
        final QueryMix.Query query = QueryMix.Query.byId(queryId);
        final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(tables, seed, database);
        final List<Map<String, Object>> params = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
          params.add(sampler.params(query));
        return params;
      }

      @Override
      public int execute(final String queryId, final Map<String, Object> params) {
        return QueryMix.execute(database, QueryMix.Query.byId(queryId), params);
      }
    };
  }

  private static String includePattern() {
    final String filter = BenchProps.string("queries", "");
    if (filter.isEmpty())
      return EmbeddedRetrievalBenchmark.class.getName() + "\\..*";
    final StringBuilder sb = new StringBuilder(EmbeddedRetrievalBenchmark.class.getName()).append("\\.(");
    final String[] ids = filter.split(",");
    for (int i = 0; i < ids.length; i++)
      sb.append(i > 0 ? "|" : "").append(QueryMix.Query.byId(ids[i].trim()).id);
    return sb.append(")$").toString();
  }

  private static void runWarm(final DatasetManifest manifest, final Path results) throws RunnerException, IOException {
    final int[] threadLevels = BenchProps.intList("threads", "1,8,32");
    final int warmupIterations = BenchProps.intValue("warmupIterations", 1);
    final int warmupSeconds = BenchProps.intValue("warmupSeconds", 3);
    final int measureIterations = BenchProps.intValue("measureIterations", 3);
    final int measureSeconds = BenchProps.intValue("measureSeconds", 4);
    final BenchReport report = new BenchReport();
    for (final int threads : threadLevels) {
      final Options options = new OptionsBuilder().include(includePattern()).mode(SampleTime).timeUnit(TimeUnit.MICROSECONDS).forks(0)
          .threads(threads).warmupIterations(warmupIterations).warmupTime(TimeValue.seconds(warmupSeconds))
          .measurementIterations(measureIterations).measurementTime(TimeValue.seconds(measureSeconds)).shouldFailOnError(true).build();
      collect(report, new Runner(options).run(), "warm", threads);
    }
    report.write(results, "embedded-warm-" + manifest.records);
    System.out.println(report.toMarkdown());
  }

  private static void runCold(final DatasetManifest manifest, final Path results) throws RunnerException, IOException {
    final Options options = forked(new OptionsBuilder().include(includePattern()).mode(SingleShotTime).timeUnit(TimeUnit.MICROSECONDS)
        .threads(1).warmupIterations(0).measurementIterations(1).measurementBatchSize(1).shouldFailOnError(true),
        List.of("-D" + BenchProps.PREFIX + "cold=true")).build();
    final BenchReport report = new BenchReport();
    collect(report, new Runner(options).run(), "cold", 1);
    report.write(results, "embedded-cold-" + manifest.records);
    System.out.println(report.toMarkdown());
  }

  private static void runProfile(final DatasetManifest manifest, final Path results) throws RunnerException, IOException {
    final String[] ids = BenchProps.string("profileQueries", "pivot1Hop,structured,fulltextTop20,vectorKnn20").split(",");
    final int threads = BenchProps.intValue("profileThreads", 8);
    final int seconds = BenchProps.intValue("profileSeconds", 20);
    final StringBuilder all = new StringBuilder();
    for (final String rawId : ids) {
      final QueryMix.Query query = QueryMix.Query.byId(rawId.trim());
      final Path jfr = results.resolve("profile-" + query.id + "-" + manifest.records + ".jfr").toAbsolutePath();
      Files.deleteIfExists(jfr);
      final String recording = "-XX:StartFlightRecording=filename=" + jfr + ",settings=profile,dumponexit=true,"
          + "jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.FileRead#threshold=0ms,jdk.FileWrite#threshold=0ms,"
          + "jdk.SocketRead#threshold=0ms,jdk.ObjectAllocationSample#throttle=2000/s";
      final Options options = forked(new OptionsBuilder().include(EmbeddedRetrievalBenchmark.class.getName() + "\\." + query.id + "$")
          .mode(SampleTime).timeUnit(TimeUnit.MICROSECONDS).threads(threads).warmupIterations(1).warmupTime(TimeValue.seconds(5))
          .measurementIterations(1).measurementTime(TimeValue.seconds(seconds)).shouldFailOnError(true), List.of(recording)).build();
      new Runner(options).run();
      final String tables = JfrTopFrames.analyze(jfr, BenchProps.intValue("profileTop", 25));
      final String section = "## " + query.id + " (" + query.description + "), " + threads + " threads, " + seconds + " s\n\n" + tables;
      Files.writeString(results.resolve("profile-" + query.id + "-" + manifest.records + ".md"), section);
      all.append(section).append('\n');
      System.out.println(section);
    }
    Files.writeString(results.resolve("profile-summary-" + manifest.records + ".md"), all.toString());
  }

  /** A forked run gets the heap, the modules and every {@code arcadedb.*} property of this JVM. */
  private static ChainedOptionsBuilder forked(final ChainedOptionsBuilder builder, final List<String> extra) {
    final List<String> jvmArgs = new ArrayList<>();
    jvmArgs.add("-Xmx" + BenchProps.string("forkHeap", "12g"));
    jvmArgs.add("--add-modules");
    jvmArgs.add("jdk.incubator.vector");
    jvmArgs.add("--enable-native-access=ALL-UNNAMED");
    for (final String key : System.getProperties().stringPropertyNames())
      if (key.startsWith("arcadedb."))
        jvmArgs.add("-D" + key + "=" + System.getProperty(key));
    jvmArgs.addAll(extra);
    return builder.forks(1).jvmArgs(jvmArgs.toArray(new String[0]));
  }

  private static void collect(final BenchReport report, final Collection<RunResult> runResults, final String phase, final int threads) {
    for (final RunResult rr : runResults) {
      final String benchmark = rr.getParams().getBenchmark();
      final String id = benchmark.substring(benchmark.lastIndexOf('.') + 1);
      final Statistics s = rr.getPrimaryResult().getStatistics();
      final double meanUs = s.getMean();
      final double qps = meanUs > 0 ? threads * 1_000_000.0 / meanUs : 0.0;
      report.add(new BenchReport.Row("embedded", id, "java", threads, phase, s.getN(), s.getPercentile(50.0), s.getPercentile(95.0),
          s.getPercentile(99.0), meanUs, s.getMax(), qps, -1));
    }
  }
}
