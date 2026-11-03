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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.query.structured.Facet;
import com.arcadedb.query.structured.RecordFilter;
import com.arcadedb.query.structured.StructuredQuery;
import com.arcadedb.query.structured.StructuredSearch;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.keys.TextHash;
import com.arcadedb.spork.schema.SporkSchema;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * P4, P4b and P4v of 02-retrieval-speed.md 2.4 on the plugin's cursor path, and the retrieval harness's STRUCTURED query
 * (state + age range + service, newest first), at 5M records in one schema v1 partition, against the SQL plan on the same
 * data. The data follows {@code ReconDataGenerator}'s distributions (state through a Zipf 1.0 over 300 cities, age 18 +
 * |N(0,1)| x 6, 1 to 4 services Zipf 1.0 over 40, posted NOW - 730 d x u^2, views lognormal).
 * <p>
 * Run: {@code mvn -o -pl spork-recon -am test -Dtest=StructuredSearchBenchmark -Dsurefire.failIfNoSpecifiedTests=false
 * -DexcludedGroups= -Dspork.p4.records=5000000}. The partition is built once under {@code target/spork-p4-bench} and reused;
 * results go to {@code target/spork-p4-bench/results.md}.
 */
@Tag("benchmark")
class StructuredSearchBenchmark {
  private static final long     RECORDS     = Long.getLong("spork.p4.records", 5_000_000L);
  private static final int      QUERIES     = Integer.getInteger("spork.p4.queries", 400);
  private static final int      SQL_QUERIES = Integer.getInteger("spork.p4.sqlQueries", 40);
  private static final String   ROOT        = "target/spork-p4-bench";
  private static final long     NOW         = 1_793_707_200_000L;
  private static final long     DAY         = 86_400_000L;
  private static final long     SPAN        = 730 * DAY;
  private static final String[] STATES      = { "CA", "TX", "FL", "NY", "PA", "IL", "OH", "GA", "NC", "MI", "NJ", "VA", "WA", "AZ",
      "TN", "MA", "IN", "MO", "MD", "WI", "CO", "MN", "SC", "AL", "LA", "KY", "OR", "OK", "CT", "UT", "IA", "NV", "AR", "MS", "KS",
      "NM", "NE", "ID", "WV", "HI", "NH", "ME", "MT", "RI", "DE", "SD", "ND", "AK", "VT", "WY" };
  private static final int      CITIES      = 300;
  private static final int      SERVICES    = 40;
  private static final int      COUNT_CAP   = 10_000;
  /** The composites P4 reads; every other index of v1 is dropped so the 5M load maintains only these. */
  private static final Set<List<String>> KEPT = Set.of(List.of("record_id"), List.of("stateCode", "tsPosted", "views"),
      List.of("age", "tsPosted", "views"), List.of("services by item", "tsPosted", "views"), List.of("tsPosted", "views"),
      List.of("stateCode", "views", "tsPosted"), List.of("age", "views", "tsPosted"), List.of("services by item", "views", "tsPosted"),
      List.of("views", "tsPosted"), List.of("markings"), List.of("tenantId"));

  private static final class Zipf {
    private final double[] cumulative;

    Zipf(final int n, final double exponent) {
      cumulative = new double[n];
      double sum = 0;
      for (int r = 0; r < n; r++)
        cumulative[r] = sum += 1.0 / Math.pow(r + 1, exponent);
      for (int r = 0; r < n; r++)
        cumulative[r] /= sum;
      cumulative[n - 1] = 1.0;
    }

    int sample(final double u) {
      final int i = Arrays.binarySearch(cumulative, u);
      return i >= 0 ? i : Math.min(cumulative.length - 1, -i - 1);
    }
  }

  private static final String[] CITY_STATE = new String[CITIES];
  private static final Zipf     CITY_ZIPF  = new Zipf(CITIES, 1.0);
  private static final Zipf     SERVICE_ZIPF = new Zipf(SERVICES, 1.0);

  static {
    final SplittableRandom rnd = new SplittableRandom(20_250_101L);
    final Zipf stateZipf = new Zipf(STATES.length, 1.0);
    for (int i = 0; i < CITIES; i++)
      CITY_STATE[i] = STATES[stateZipf.sample(rnd.nextDouble())];
  }

  @Test
  void p4AtScale() throws Exception {
    final File directory = new File(ROOT, "rec_1");
    final File marker = new File(ROOT, "rec_1.loaded-" + RECORDS);
    if (!marker.exists()) {
      FileUtils.deleteRecursively(directory);
      load(directory.getPath());
      Files.writeString(marker.toPath(), "ok");
    }

    final StringBuilder report = new StringBuilder();
    try (final Database database = new DatabaseFactory(directory.getPath()).open()) {
      // the sparse visibility indexes a partition built before them lacks: without them every counted record is read
      database.command("sql", "CREATE INDEX IF NOT EXISTS ON Record (markings) NOTUNIQUE NULL_STRATEGY SKIP").close();
      database.command("sql", "CREATE INDEX IF NOT EXISTS ON Record (tenantId) NOTUNIQUE NULL_STRATEGY SKIP").close();
      final StructuredSearch search = new StructuredSearch(database, "Record", "tsPosted");
      // production's filter: the person's visibility, with this partition's hidden set (empty: nothing is marked or imported)
      final RecordFilter visibility = new RecordVisibility("deliverfund", 0L).forPartition(database);
      final Function<StructuredQuery, StructuredSearch.Result> run = q -> search.search(q.filter(visibility));

      report.append("| shape | n | p50 ms | p95 ms | p99 ms | mean ms | partial | capped | mean entries | mean records |\n");
      report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
      final SplittableRandom rnd = new SplittableRandom(12_345L);
      measure(report, "P4 state, newest 50 + count", QUERIES,
          () -> StructuredQuery.newestFirst(50).where(Facet.eq("stateCode", state(rnd))).count(COUNT_CAP), run);
      measure(report, "P4 state + 7 d window, newest 50 + count", QUERIES,
          () -> StructuredQuery.newestFirst(50).where(Facet.eq("stateCode", state(rnd))).window(NOW - 7 * DAY, NOW).count(COUNT_CAP), run);
      measure(report, "P4b state, newest 500 + count", QUERIES,
          () -> StructuredQuery.newestFirst(500).where(Facet.eq("stateCode", state(rnd))).count(COUNT_CAP), run);
      measure(report, "P4v state + 30 d, views_desc 50 + count", QUERIES,
          () -> StructuredQuery.newestFirst(50).rankBy("views").where(Facet.eq("stateCode", state(rnd))).window(NOW - 30 * DAY, NOW)
              .count(COUNT_CAP), run);
      measure(report, "P4 state, newest 50, page only", QUERIES,
          () -> StructuredQuery.newestFirst(50).where(Facet.eq("stateCode", state(rnd))), run);
      measure(report, "STRUCTURED (state + age + service), newest 100 + count", QUERIES, () -> structured(rnd, 100).count(COUNT_CAP), run);
      measure(report, "STRUCTURED, newest 50 + count", QUERIES, () -> structured(rnd, 50).count(COUNT_CAP), run);
      measure(report, "STRUCTURED, newest 50, page only", QUERIES, () -> structured(rnd, 50), run);
      measureSql(report, "STRUCTURED on the SQL plan, newest 100", SQL_QUERIES, () -> structured(rnd, 100), database);
    }
    final String result = "Records: " + RECORDS + ", load average at the end: " + loadAverage() + "\n\n" + report;
    Files.writeString(new File(ROOT, "results.md").toPath(), result, StandardCharsets.UTF_8);
    LogManager.instance().log(this, Level.INFO, "P4 benchmark%n%s", result);
  }

  private static String state(final SplittableRandom rnd) {
    return CITY_STATE[CITY_ZIPF.sample(rnd.nextDouble())];
  }

  private static StructuredQuery structured(final SplittableRandom rnd, final int limit) {
    final int ageMin = 18 + rnd.nextInt(25);
    return StructuredQuery.newestFirst(limit).where(Facet.eq("stateCode", state(rnd))).where(Facet.intRange("age", ageMin, ageMin + 4))
        .where(Facet.eq("services", "service" + SERVICE_ZIPF.sample(rnd.nextDouble())));
  }

  private interface QuerySource {
    StructuredQuery next();
  }

  private static void measure(final StringBuilder report, final String shape, final int n, final QuerySource source,
      final Function<StructuredQuery, StructuredSearch.Result> run) {
    // warm-up on other parameters of the same shape, then the timed pass
    for (int i = 0; i < n / 2; i++)
      run.apply(source.next());
    final long[] nanos = new long[n];
    int partial = 0;
    int capped = 0;
    long entries = 0;
    long records = 0;
    for (int i = 0; i < n; i++) {
      final StructuredQuery query = source.next();
      final long start = System.nanoTime();
      final StructuredSearch.Result result = run.apply(query);
      nanos[i] = System.nanoTime() - start;
      partial += result.partial() || result.totalPartial() ? 1 : 0;
      capped += result.totalCapped() ? 1 : 0;
      entries += result.scanned();
      records += result.recordsRead();
    }
    row(report, shape, nanos, partial, capped, entries / n, records / n);
  }

  private static void measureSql(final StringBuilder report, final String shape, final int n, final QuerySource source,
      final Database database) {
    for (int i = 0; i < Math.min(5, n); i++)
      sql(database, source.next());
    final long[] nanos = new long[n];
    for (int i = 0; i < n; i++) {
      final StructuredQuery query = source.next();
      final long start = System.nanoTime();
      sql(database, query);
      nanos[i] = System.nanoTime() - start;
    }
    row(report, shape, nanos, 0, 0, -1, -1);
  }

  private static int sql(final Database database, final StructuredQuery query) {
    final Map<String, Object> params = new HashMap<>();
    final Facet state = query.facets().get(0), age = query.facets().get(1), service = query.facets().get(2);
    params.put("state", state.values().getFirst());
    params.put("ageMin", age.values().getFirst());
    params.put("ageMax", age.values().getLast());
    params.put("service", service.values().getFirst());
    int rows = 0;
    try (final ResultSet rs = database.query("sql",
        "SELECT FROM Record WHERE stateCode = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service "
            + "ORDER BY tsPosted DESC LIMIT " + query.limit(), params)) {
      while (rs.hasNext()) {
        rs.next();
        rows++;
      }
    }
    return rows;
  }

  private static void row(final StringBuilder report, final String shape, final long[] nanos, final int partial, final int capped,
      final long entries, final long records) {
    final long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    double sum = 0;
    for (final long v : sorted)
      sum += v;
    report.append(String.format("| %s | %d | %.2f | %.2f | %.2f | %.2f | %d | %d | %s | %s |%n", shape, sorted.length,
        ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99), sum / sorted.length / 1e6, partial, capped,
        entries < 0 ? "" : Long.toString(entries), records < 0 ? "" : Long.toString(records)));
  }

  private static double ms(final long[] sorted, final double quantile) {
    return sorted[Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1)] / 1e6;
  }

  private static String loadAverage() {
    return String.format("%.1f", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
  }

  /** Schema v1, only the P4 composites kept, 5M records in batches, then a full compaction as at seal. */
  private static void load(final String path) throws Exception {
    try (final Database database = new DatabaseFactory(path).create()) {
      SporkSchema.install(database);
      final DocumentType record = database.getSchema().getType(RecordVisibility.RECORD_TYPE);
      final List<String> drop = new ArrayList<>();
      for (final Index index : record.getAllIndexes(false))
        if (index instanceof TypeIndex typeIndex && !KEPT.contains(typeIndex.getPropertyNames()))
          drop.add(index.getName());
      for (final String name : drop)
        database.getSchema().dropIndex(name);

      final SplittableRandom rnd = new SplittableRandom(42L);
      final int batch = 20_000;
      for (long start = 0; start < RECORDS; start += batch) {
        final long from = start;
        database.transaction(() -> {
          for (long n = from; n < Math.min(RECORDS, from + batch); n++) {
            final double u = rnd.nextDouble();
            final int serviceCount = 1 + rnd.nextInt(4);
            final List<String> services = new ArrayList<>(serviceCount);
            for (int s = 0; s < serviceCount; s++) {
              final String service = "service" + SERVICE_ZIPF.sample(rnd.nextDouble());
              if (!services.contains(service))
                services.add(service);
            }
            final int city = CITY_ZIPF.sample(rnd.nextDouble());
            final float[] style = new float[24];
            for (int i = 0; i < style.length; i++)
              style[i] = (float) rnd.nextDouble();
            database.newVertex(RecordVisibility.RECORD_TYPE)//
                .set("record_id", RecordId.of(1, n))//
                .set("upstreamRid", (168L << 40) | n)//
                .set("tsPosted", NOW - (long) (SPAN * u * u))//
                .set("views", (int) Math.min(1_000_000.0, Math.exp(nextGaussian(rnd) * 1.2 + 5.0)))//
                .set("age", Math.min(60, 18 + (int) (Math.abs(nextGaussian(rnd)) * 6.0)))//
                .set("stateCode", CITY_STATE[city]).set("state", CITY_STATE[city]).set("city", "city" + city)//
                .set("services", services)//
                .set("phones", List.of(String.format("555%07d", rnd.nextInt(10_000_000))))//
                .set("textHash", TextHash.of("title " + n, "body " + n))//
                .set("styleVec", style)//
                .save();
          }
        });
      }

      for (final Index index : record.getAllIndexes(false))
        if (index instanceof TypeIndex typeIndex)
          for (final IndexInternal bucketIndex : typeIndex.getIndexesOnBuckets())
            if (bucketIndex.scheduleCompaction())
              bucketIndex.compact();
    }
  }

  private static double nextGaussian(final SplittableRandom rnd) {
    // Box-Muller: SplittableRandom has no nextGaussian on every JDK this module builds with
    return Math.sqrt(-2.0 * Math.log(1.0 - rnd.nextDouble())) * Math.cos(2.0 * Math.PI * rnd.nextDouble());
  }
}
