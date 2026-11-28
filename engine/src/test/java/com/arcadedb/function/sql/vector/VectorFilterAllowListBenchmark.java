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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPU and allocation of a k-NN search filtered by a sub-query, the Recon shape
 * {@code vector.neighbors('Record[styleVec]', :vector, 20, {filter: (SELECT @rid FROM Record WHERE state = :state AND tsPosted >= :since)})},
 * on both vector index kinds (docs/spork/workstreams/vector-filter-allowlist.md).
 * <p>
 * Uses only the public query API, so the same compiled class measures any engine build: run {@link #main} once against the
 * classes of the base and once against the modified ones, interleaved, on the same database, and compare. Every line it prints
 * for a query carries a hash of the neighbours answered, so two runs prove they answered the same.
 * <p>
 * Measures what the calling thread spent (thread CPU, thread allocated bytes), the process CPU over the same window (which
 * includes the GC work the allocations cause and the IVF scan's pool workers) and the wall time, as medians per query. Knobs:
 * {@code -Dbench.path} (database directory, generated once and reused), {@code -Dbench.records} (default 1,000,000),
 * {@code -Dbench.warmup} / {@code -Dbench.iterations} per filter, {@code -Dbench.indexes} ({@code graph,ivf}), {@code -Dbench.only}
 * (a filter name prefix).
 */
@Tag("benchmark")
class VectorFilterAllowListBenchmark {
  private static final int      DIMS      = 24;
  private static final int      CENTROIDS = 200;
  private static final long     DAY       = 86_400_000L;
  private static final long     NOW       = 1_790_000_000_000L;
  private static final String[] WORDS     = new String[600];

  static {
    final Random random = new Random(1);
    for (int i = 0; i < WORDS.length; i++) {
      final StringBuilder word = new StringBuilder();
      final int len = 3 + random.nextInt(7);
      for (int c = 0; c < len; c++)
        word.append((char) ('a' + random.nextInt(26)));
      WORDS[i] = word.toString();
    }
  }

  /** s0 about 20% of the records, s1 1%, s2 0.1%, the rest over 47 states. */
  private static String stateOf(final Random random) {
    final double u = random.nextDouble();
    if (u < 0.20)
      return "s0";
    if (u < 0.21)
      return "s1";
    if (u < 0.211)
      return "s2";
    return "s" + (3 + random.nextInt(47));
  }

  @Test
  void filteredKnn() {
    main(new String[0]);
  }

  public static void main(final String[] args) {
    final int records = Integer.getInteger("bench.records", 1_000_000);
    final String path = System.getProperty("bench.path", "target/vector-filter-bench-" + records);
    final int warmup = Integer.getInteger("bench.warmup", 5);
    final int iterations = Integer.getInteger("bench.iterations", 15);
    final List<String> indexes = Arrays.asList(System.getProperty("bench.indexes", "graph,ivf").split(","));
    // THE FILTERS WHOSE NAME STARTS WITH THIS ONLY, OR ALL
    final String only = System.getProperty("bench.only");

    final DatabaseFactory factory = new DatabaseFactory(path);
    if (!factory.exists())
      generate(factory, records);

    try (final Database database = factory.open()) {
      for (final String name : List.of("Record[styleVec]", "Record[ivfVec]"))
        for (final IndexInternal sub : ((TypeIndex) database.getSchema().getIndexByName(name)).getIndexesOnBuckets())
          sub.warmUp();

      final long since30d = NOW - 30 * DAY;
      final Object[][] filters = {
          // name, state, since
          { "0.1% (s2)", "s2", 0L }, { "1% (s1)", "s1", 0L }, { "20% (s0)", "s0", 0L }, { "0.6% (s0, 30d)", "s0", since30d },
          { "empty", "zz", 0L } };
      final Random random = new Random(99);
      final float[][] queries = new float[iterations + warmup][];
      for (int i = 0; i < queries.length; i++)
        queries[i] = centroid(random.nextInt(CENTROIDS));

      System.out.printf(Locale.ENGLISH, "%-6s %-16s %9s %10s %10s %10s %9s %s%n", "index", "filter", "allowed", "threadCPU", "procCPU",
          "alloc", "wall", "hash");
      for (final String kind : indexes) {
        final String index = kind.equals("ivf") ? "Record[ivfVec]" : "Record[styleVec]";
        for (final Object[] filter : filters)
          if (only == null || ((String) filter[0]).startsWith(only))
            measure(database, index, kind, (String) filter[0], (String) filter[1], (Long) filter[2], queries, warmup);
        // 100%: every state
        if (only == null || "100%".startsWith(only))
          measureAll(database, index, kind, queries, warmup);
      }
    }
  }

  private static void measure(final Database database, final String index, final String kind, final String name, final String state,
      final long since, final float[][] queries, final int warmup) {
    final String sql = "SELECT expand(`vector.neighbors`('" + index
        + "', :vector, 20, { filter: (SELECT @rid FROM Record WHERE state = :state AND tsPosted >= :since) }))";
    final Map<String, Object> params = new HashMap<>();
    params.put("state", state);
    params.put("since", since);
    final long allowed = count(database, "SELECT count(*) AS c FROM Record WHERE state = :state AND tsPosted >= :since", params);
    run(database, sql, params, kind, name, allowed, queries, warmup);
  }

  private static void measureAll(final Database database, final String index, final String kind, final float[][] queries,
      final int warmup) {
    final StringBuilder states = new StringBuilder();
    for (int s = 0; s < 50; s++)
      states.append(s == 0 ? "" : ", ").append("'s").append(s).append('\'');
    final String sql = "SELECT expand(`vector.neighbors`('" + index + "', :vector, 20, { filter: (SELECT @rid FROM Record WHERE state IN ["
        + states + "]) }))";
    final long allowed = count(database, "SELECT count(*) AS c FROM Record", Map.of());
    run(database, sql, new HashMap<>(), kind, "100%", allowed, queries, warmup);
  }

  private static void run(final Database database, final String sql, final Map<String, Object> params, final String kind,
      final String name, final long allowed, final float[][] queries, final int warmup) {
    final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    final int measured = queries.length - warmup;
    final long[] cpu = new long[measured];
    final long[] processCpu = new long[measured];
    final long[] alloc = new long[measured];
    final long[] wall = new long[measured];
    long hash = 17;
    for (int i = 0; i < queries.length; i++) {
      params.put("vector", queries[i]);
      final long c0 = threads.getCurrentThreadCpuTime();
      final long a0 = threads.getCurrentThreadAllocatedBytes();
      final long p0 = os.getProcessCpuTime();
      final long w0 = System.nanoTime();
      final List<Object> answer = new ArrayList<>();
      try (final ResultSet rs = database.query("sql", sql, params)) {
        while (rs.hasNext()) {
          final Result row = rs.next();
          answer.add(row.getIdentity().get());
          answer.add(row.getProperty("distance"));
        }
      }
      final long w1 = System.nanoTime();
      final long p1 = os.getProcessCpuTime();
      final long a1 = threads.getCurrentThreadAllocatedBytes();
      final long c1 = threads.getCurrentThreadCpuTime();
      if (allowed == 0)
        assertThat(answer).isEmpty();
      hash = hash * 31 + answer.toString().hashCode();
      if (i >= warmup) {
        cpu[i - warmup] = c1 - c0;
        alloc[i - warmup] = a1 - a0;
        processCpu[i - warmup] = p1 - p0;
        wall[i - warmup] = w1 - w0;
      }
    }
    System.out.printf(Locale.ENGLISH, "%-6s %-16s %9d %8.2fms %8.2fms %8.2fMB %7.2fms %08x%n", kind, name, allowed, median(cpu) / 1e6,
        median(processCpu) / 1e6, median(alloc) / 1048576.0, median(wall) / 1e6, (int) (hash ^ (hash >>> 32)));
  }

  private static long median(final long[] values) {
    final long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static long count(final Database database, final String sql, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("sql", sql, params)) {
      return ((Number) rs.next().getProperty("c")).longValue();
    }
  }

  private static float[] centroid(final int c) {
    final Random random = new Random(1000 + c);
    final float[] v = new float[DIMS];
    for (int d = 0; d < DIMS; d++)
      v[d] = (float) random.nextGaussian();
    return normalize(v);
  }

  private static float[] normalize(final float[] v) {
    double norm = 0;
    for (final float x : v)
      norm += x * x;
    final float inv = (float) (1 / Math.sqrt(norm));
    for (int d = 0; d < v.length; d++)
      v[d] *= inv;
    return v;
  }

  private static void generate(final DatabaseFactory factory, final int records) {
    final long start = System.currentTimeMillis();
    try (final Database database = factory.create()) {
      database.command("sql", "CREATE DOCUMENT TYPE Record BUCKETS 1");
      for (final String property : List.of("state STRING", "city STRING", "tsPosted LONG", "age INTEGER", "views INTEGER",
          "text STRING", "styleVec ARRAY_OF_FLOATS", "ivfVec ARRAY_OF_FLOATS"))
        database.command("sql", "CREATE PROPERTY Record." + property);
      final float[][] centroids = new float[CENTROIDS][];
      for (int c = 0; c < CENTROIDS; c++)
        centroids[c] = centroid(c);
      final Random random = new Random(42);
      final int batch = 20_000;
      for (int done = 0; done < records; done += batch) {
        final int end = Math.min(records, done + batch);
        final int from = done;
        database.transaction(() -> {
          for (int i = from; i < end; i++) {
            final float[] base = centroids[(int) Math.min(CENTROIDS - 1, Math.abs(random.nextGaussian()) * CENTROIDS / 3)];
            final float[] v = new float[DIMS];
            for (int d = 0; d < DIMS; d++)
              v[d] = base[d] + (float) random.nextGaussian() * 0.35f / (float) Math.sqrt(DIMS);
            normalize(v);
            final StringBuilder text = new StringBuilder();
            final int words = 20 + random.nextInt(40);
            for (int w = 0; w < words; w++)
              text.append(w == 0 ? "" : " ").append(WORDS[random.nextInt(WORDS.length)]);
            final double u = random.nextDouble();
            database.newDocument("Record").set("state", stateOf(random)).set("city", "c" + random.nextInt(300))
                .set("tsPosted", NOW - (long) (730 * DAY * u * u)).set("age", 18 + random.nextInt(40))
                .set("views", random.nextInt(10_000)).set("text", text.toString()).set("styleVec", v).set("ivfVec", v).save();
          }
        });
      }
      System.out.printf("loaded %d records in %d s%n", records, (System.currentTimeMillis() - start) / 1000);
      database.command("sql", "CREATE INDEX ON Record (state, tsPosted) NOTUNIQUE METADATA {\"buildMode\": \"SORTED\"}");
      System.out.printf("state,tsPosted index at %d s%n", (System.currentTimeMillis() - start) / 1000);
      database.command("sql", "CREATE INDEX ON Record (ivfVec) LSM_IVF_VECTOR METADATA {dimensions: " + DIMS + ", similarity: 'COSINE'}");
      System.out.printf("IVF index at %d s%n", (System.currentTimeMillis() - start) / 1000);
      database.command("sql", "CREATE INDEX ON Record (styleVec) LSM_VECTOR METADATA {dimensions: " + DIMS + ", similarity: 'COSINE'}");
      System.out.printf("graph index at %d s%n", (System.currentTimeMillis() - start) / 1000);
    }
  }
}
