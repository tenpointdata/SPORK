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
package performance;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.sql.executor.ExecutionStep;
import com.arcadedb.query.sql.executor.OrderedIndexLimitStep;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPU-time A/B of the ordered index walk with early LIMIT ({@link OrderedIndexLimitStep}) against the plan it replaces, on
 * Recon-shaped records ({@code docs/spork/workstreams/retrieval-bench.md}): the {@code structured} query
 * ({@code state = :s AND age BETWEEN :a AND :a + 4 AND services CONTAINS :svc ORDER BY tsPosted DESC LIMIT 100}) and the
 * {@code feed24h} query, which must keep its plan. Both arms run in this JVM on the same database, the previous plan with
 * {@code arcadedb.queryOrderedIndexLimit} off: per parameter set, one run of each arm per round, the order alternating
 * between rounds. Measured per query: CPU time and bytes allocated by the query thread, and CPU time of the whole process
 * (the parallel scan of the previous plan's selectivity fallback runs on other threads). Wall time is printed for
 * reference only: the machine this runs on is shared.
 * <p>
 * Run with {@code mvn -o -pl engine surefire:test -Dtest=OrderedIndexLimitBenchmark -Dgroups=benchmark -DexcludedGroups=}.
 * Knobs: {@code -Darcadedb.orderedLimitBench.records=1000000} (generated once under {@code target/databases}),
 * {@code -Darcadedb.orderedLimitBench.db=<path>} to run against an existing Recon dataset instead (the retrieval bench's
 * {@code databases/recon}, cloned first: the run writes statistics), {@code .params=50}, {@code .rounds=3}.
 */
@Tag("benchmark")
class OrderedIndexLimitBenchmark {
  private static final String   PREFIX   = "arcadedb.orderedLimitBench.";
  private static final String[] STATES   = { "CA", "TX", "FL", "NY", "PA", "IL", "OH", "GA", "NC", "MI", "NJ", "VA", "WA", "AZ", "TN",
      "MA", "IN", "MO", "MD", "WI", "CO", "MN", "SC", "AL", "LA", "KY", "OR", "OK", "CT", "UT", "IA", "NV", "AR", "MS", "KS", "NM",
      "NE", "ID", "WV", "HI", "NH", "ME", "RI", "MT", "DE", "SD", "ND", "AK", "DC", "VT", "WY" };
  private static final int      CITIES   = 300;
  private static final int      SERVICES = 40;
  private static final long     DAY_MS   = 86_400_000L;
  private static final long     NOW      = 1_793_491_200_000L;

  private static final String STRUCTURED =
      "SELECT FROM Record WHERE state = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT 100";
  private static final String FEED       = "SELECT FROM Record WHERE tsPosted >= :since AND location IS NOT NULL ORDER BY tsPosted DESC LIMIT 200";

  private final StringBuilder                            detailReport = new StringBuilder();
  private final ThreadMXBean                             threads = ManagementFactory.getThreadMXBean();
  private final com.sun.management.ThreadMXBean          sunThreads = (com.sun.management.ThreadMXBean) threads;
  private final com.sun.management.OperatingSystemMXBean os         =
      (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

  /** One arm's measures over every measured run. */
  private static final class Arm {
    final List<Long> cpu   = new ArrayList<>();
    final List<Long> alloc = new ArrayList<>();
    final List<Long> proc  = new ArrayList<>();
    final List<Long> wall  = new ArrayList<>();
    long rows;
  }

  /** Per parameter set: the CPU of each run of each arm, and how the last race ended. */
  private final Map<Map<String, Object>, long[]> perParamCpu = new LinkedHashMap<>();
  private final Map<Map<String, Object>, String> perParamOutcome = new HashMap<>();

  @Test
  void structuredAndFeedAB() throws Exception {
    final String existing = System.getProperty(PREFIX + "db");
    final int records = Integer.getInteger(PREFIX + "records", 1_000_000);
    final int paramSets = Integer.getInteger(PREFIX + "params", 50);
    final int rounds = Integer.getInteger(PREFIX + "rounds", 3);

    final String path = existing != null ? existing : "./target/databases/ordered-limit-bench-" + records;
    final DatabaseFactory factory = new DatabaseFactory(path);
    final Database database;
    if (existing == null && !(factory.exists() && Files.exists(Path.of(path, "generated.marker")))) {
      FileUtils.deleteRecursively(new File(path));
      database = factory.create();
      generate(database, records);
      Files.writeString(Path.of(path, "generated.marker"), String.valueOf(records));
    } else
      database = factory.open();

    try {
      final long tsMax;
      try (final ResultSet rs = database.query("sql", "SELECT max(tsPosted) AS m FROM Record")) {
        tsMax = rs.next().<Number>getProperty("m").longValue();
      }
      final long count = database.countType("Record", false);

      final List<Map<String, Object>> structuredParams = structuredParams(paramSets);
      final List<Map<String, Object>> feedParams = List.of(Map.of("since", tsMax - DAY_MS));

      final StringBuilder report = new StringBuilder();
      report.append(String.format(Locale.ROOT, "# Ordered index walk with early LIMIT, A/B on %,d records (%s)%n%n", count,
          existing != null ? existing : "generated"));
      report.append("| query | arm | rows | thread CPU median ms | mean ms | p90 ms | max ms | alloc median MB | process CPU mean ms | wall median ms |\n");
      report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");

      final int[] outcomes = new int[2];
      run(database, "structured", STRUCTURED, structuredParams, rounds, report, outcomes);
      run(database, "feed24h", FEED, feedParams, Math.max(rounds, 10), report, null);
      report.append(String.format(Locale.ROOT, "%nstructured races: %d answered by the ordered walk, %d by the previous plan (per execution)%n",
          outcomes[0], outcomes[1]));

      report.append(detailReport);
      System.out.println(report);
      Files.createDirectories(Path.of("target"));
      Files.writeString(Path.of("target", "ordered-index-limit-ab.md"), report.toString());
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_ORDERED_INDEX_LIMIT, true);
      database.close();
    }
  }

  private void run(final Database database, final String name, final String sql, final List<Map<String, Object>> paramSets,
      final int rounds, final StringBuilder report, final int[] outcomes) {
    final Arm before = new Arm();
    final Arm after = new Arm();
    // warm-up: every parameter set once per arm, and the answers compared
    for (final Map<String, Object> params : paramSets) {
      final List<Object> a = measure(database, sql, params, false, null, null);
      final List<Object> b = measure(database, sql, params, true, null, null);
      assertThat(b).as(name + " " + params + ": same sort keys from both plans").isEqualTo(a);
    }
    for (int round = 0; round < rounds; round++)
      for (final Map<String, Object> params : paramSets) {
        if (round % 2 == 0) {
          measure(database, sql, params, false, before, null);
          measure(database, sql, params, true, after, outcomes);
        } else {
          measure(database, sql, params, true, after, outcomes);
          measure(database, sql, params, false, before, null);
        }
      }
    row(report, name, "before (walk off)", before);
    row(report, name, "after (walk on)", after);
    if (Boolean.getBoolean(PREFIX + "detail")) {
      final StringBuilder detail = new StringBuilder(String.format(Locale.ROOT, "%n%s per parameter set (thread CPU, sum of runs, ms):%n", name));
      for (final Map<String, Object> params : paramSets) {
        final long[] cpu = perParamCpu.get(params);
        detail.append(String.format(Locale.ROOT, "  %s before %.1f after %.1f rows %d %s%n", params, cpu[0] / 1e6, cpu[1] / 1e6, cpu[2],
            perParamOutcome.getOrDefault(params, "")));
      }
      System.out.println(detail);
      detailReport.append(detail);
    }
  }

  /** Runs one query to the end with the walk on or off; returns the tsPosted of every row. */
  private List<Object> measure(final Database database, final String sql, final Map<String, Object> params, final boolean walk,
      final Arm arm, final int[] outcomes) {
    database.getConfiguration().setValue(GlobalConfiguration.QUERY_ORDERED_INDEX_LIMIT, walk);
    final List<Object> keys = new ArrayList<>(256);
    final long proc0 = os.getProcessCpuTime();
    final long cpu0 = threads.getCurrentThreadCpuTime();
    final long alloc0 = sunThreads.getCurrentThreadAllocatedBytes();
    final long wall0 = System.nanoTime();
    ExecutionStep first;
    try (final ResultSet rs = database.query("sql", sql, params)) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        keys.add(r.getProperty("tsPosted"));
      }
      first = rs.getExecutionPlan().orElseThrow().getSteps().getFirst();
    }
    final long wall = System.nanoTime() - wall0;
    final long alloc = sunThreads.getCurrentThreadAllocatedBytes() - alloc0;
    final long cpu = threads.getCurrentThreadCpuTime() - cpu0;
    final long proc = os.getProcessCpuTime() - proc0;
    if (arm != null) {
      arm.cpu.add(cpu);
      arm.alloc.add(alloc);
      arm.proc.add(proc);
      arm.wall.add(wall);
      arm.rows += keys.size();
      final long[] cpuOf = perParamCpu.computeIfAbsent(params, k -> new long[3]);
      cpuOf[walk ? 1 : 0] += cpu;
      cpuOf[2] = keys.size();
    }
    if (outcomes != null && first instanceof OrderedIndexLimitStep step) {
      outcomes[step.answeredByOrderedWalk() ? 0 : 1]++;
      perParamOutcome.put(params, step.answeringIndex() + " walk " + step.orderedEntriesRead() + " rivals " + step.rivalEntriesCounted());
    }
    return keys;
  }

  private static void row(final StringBuilder report, final String name, final String arm, final Arm m) {
    report.append(String.format(Locale.ROOT, "| %s | %s | %,d | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |%n", name, arm, m.rows,
        median(m.cpu) / 1e6, mean(m.cpu) / 1e6, pct(m.cpu, 0.9) / 1e6, pct(m.cpu, 1.0) / 1e6, median(m.alloc) / 1048576.0,
        mean(m.proc) / 1e6, median(m.wall) / 1e6));
  }

  private static double median(final List<Long> v) {
    return pct(v, 0.5);
  }

  private static double pct(final List<Long> v, final double p) {
    final long[] s = v.stream().mapToLong(Long::longValue).sorted().toArray();
    return s.length == 0 ? 0 : s[Math.min(s.length - 1, (int) Math.ceil(p * s.length) - (p >= 1.0 ? 1 : 0))];
  }

  private static double mean(final List<Long> v) {
    return v.stream().mapToLong(Long::longValue).average().orElse(0);
  }

  // Recon-shaped data and parameters, after benchmarks/.../ReconDomain and QueryMix -----------------------------------------

  private static final class Zipf {
    private final double[] cumulative;

    Zipf(final int n, final double s) {
      cumulative = new double[n];
      double sum = 0;
      for (int i = 0; i < n; i++) {
        sum += 1.0 / Math.pow(i + 1, s);
        cumulative[i] = sum;
      }
      for (int i = 0; i < n; i++)
        cumulative[i] /= sum;
    }

    int sample(final double u) {
      final int i = Arrays.binarySearch(cumulative, u);
      return Math.min(cumulative.length - 1, i >= 0 ? i : -i - 1);
    }
  }

  private static final Zipf     CITY_ZIPF    = new Zipf(CITIES, 1.0);
  private static final Zipf     SERVICE_ZIPF = new Zipf(SERVICES, 1.0);
  private static final String[] CITY_STATE   = new String[CITIES];

  static {
    final Random rnd = new Random(7);
    final Zipf stateZipf = new Zipf(STATES.length, 1.0);
    for (int i = 0; i < CITIES; i++)
      CITY_STATE[i] = STATES[stateZipf.sample(rnd.nextDouble())];
  }

  private static List<Map<String, Object>> structuredParams(final int n) {
    final Random rnd = new Random(4242);
    final List<Map<String, Object>> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      final Map<String, Object> p = new HashMap<>();
      final int ageMin = 18 + rnd.nextInt(25);
      p.put("state", CITY_STATE[CITY_ZIPF.sample(rnd.nextDouble())]);
      p.put("ageMin", ageMin);
      p.put("ageMax", ageMin + 4);
      p.put("service", "service" + SERVICE_ZIPF.sample(rnd.nextDouble()));
      list.add(p);
    }
    return list;
  }

  private static void generate(final Database database, final int records) {
    database.command("sql", "CREATE VERTEX TYPE Record");
    database.command("sql", "CREATE PROPERTY Record.recordId STRING");
    database.command("sql", "CREATE PROPERTY Record.tsPosted LONG");
    database.command("sql", "CREATE PROPERTY Record.city STRING");
    database.command("sql", "CREATE PROPERTY Record.state STRING");
    database.command("sql", "CREATE PROPERTY Record.location STRING");
    database.command("sql", "CREATE PROPERTY Record.age INTEGER");
    database.command("sql", "CREATE PROPERTY Record.views INTEGER");
    database.command("sql", "CREATE PROPERTY Record.services LIST OF STRING");
    database.command("sql", "CREATE PROPERTY Record.text STRING");

    final Random rnd = new Random(42);
    final int batch = 20_000;
    for (int start = 0; start < records; start += batch) {
      final int from = start;
      database.transaction(() -> {
        for (int i = from; i < Math.min(records, from + batch); i++) {
          final MutableVertex v = database.newVertex("Record");
          final int city = CITY_ZIPF.sample(rnd.nextDouble());
          final double u = rnd.nextDouble();
          v.set("recordId", "r" + i);
          v.set("tsPosted", NOW - (long) (730 * DAY_MS * u * u));
          v.set("city", "city" + city);
          v.set("state", CITY_STATE[city]);
          if (rnd.nextInt(100) < 85)
            v.set("location", String.format(Locale.ROOT, "POINT (%.5f %.5f)", -124.5 + rnd.nextDouble() * 57, 25.5 + rnd.nextDouble() * 23));
          v.set("age", 18 + (int) Math.abs(rnd.nextGaussian() * 6));
          v.set("views", (int) Math.exp(3 + rnd.nextGaussian()));
          final int nServices = 1 + rnd.nextInt(4);
          final List<String> services = new ArrayList<>(nServices);
          while (services.size() < nServices) {
            final String s = "service" + SERVICE_ZIPF.sample(rnd.nextDouble());
            if (!services.contains(s))
              services.add(s);
          }
          v.set("services", services);
          final StringBuilder text = new StringBuilder();
          final int words = 20 + rnd.nextInt(41);
          for (int w = 0; w < words; w++)
            text.append("w").append(rnd.nextInt(6000)).append(' ');
          v.set("text", text.toString());
          v.save();
        }
      });
    }
    database.command("sql", "CREATE INDEX ON Record (tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (state, tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (age, tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (services BY ITEM) NOTUNIQUE");
  }
}
