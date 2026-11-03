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
import com.arcadedb.database.Document;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import com.arcadedb.serializer.JsonSerializer;
import com.arcadedb.utility.FileUtils;
import com.arcadedb.utility.StallAwareStopwatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Measures the one-header-pass projection (SPORK retrieval speed, item S4) against the per-property reading it replaces,
 * on the shape the design document sizes: 3 of 30 properties projected from 100K index-hit rows, plus the whole-record
 * {@code SELECT *}, the element-row JSON serialization, the openCypher {@code RETURN n.a, n.b, n.c} and the {@code count(*)}
 * over index hits that item S5 would spare its record loads (deferred, see the workstream notes). Every shape runs with
 * {@code arcadedb.queryProjectionPropertyCache} on (after) and off (before, the per-property reading) in the same JVM, as
 * alternating pairs (one run of each arm per iteration), and reports the median of the measured runs of each arm: effective wall time (the JVM stall discounted), CPU time of the thread and bytes
 * it allocated, which is where the copies of the decoded values and the header walks show.
 * <p>
 * Run with {@code ./mvnw -o -pl engine test -Dsurefire.includes=**&#47;ProjectionHeaderPassBenchmark.java -Dgroups=benchmark
 * -DexcludedGroups=}. Override the dataset with {@code -Darcadedb.projectionBenchmark.rows=200000}.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
@Tag("benchmark")
class ProjectionHeaderPassBenchmark {
  private static final String DATABASE_PATH       = "./target/databases/projection-header-pass-benchmark";
  private static final String ROWS_PROPERTY       = "arcadedb.projectionBenchmark.rows";
  private static final int    DEFAULT_ROWS        = 100_000;
  private static final int    PROPERTIES          = 30;
  private static final int    BATCH_SIZE          = 20_000;
  private static final int    WARMUP_ITERATIONS   = 3;
  private static final int    MEASURED_ITERATIONS = 7;

  private static Database database;
  private static int      rows;

  @BeforeAll
  static void populate() {
    rows = Integer.getInteger(ROWS_PROPERTY, DEFAULT_ROWS);
    FileUtils.deleteRecursively(new File(DATABASE_PATH));
    database = new DatabaseFactory(DATABASE_PATH).create();

    final VertexType wide = database.getSchema().createVertexType("Wide");
    wide.createProperty("key", Type.LONG);
    for (int p = 0; p < PROPERTIES; p++)
      wide.createProperty(name(p), p % 3 == 0 ? Type.STRING : p % 3 == 1 ? Type.INTEGER : Type.DOUBLE);
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "Wide", "key");

    for (int start = 0; start < rows; start += BATCH_SIZE) {
      final int from = start;
      final int to = Math.min(start + BATCH_SIZE, rows);
      database.transaction(() -> {
        for (int i = from; i < to; i++) {
          final MutableVertex v = database.newVertex("Wide").set("key", (long) i);
          for (int p = 0; p < PROPERTIES; p++)
            v.set(name(p), p % 3 == 0 ? "value-" + i + "-" + p : p % 3 == 1 ? i + p : i * 0.5d + p);
          v.save();
        }
      });
    }
  }

  @AfterAll
  static void tearDown() {
    if (database != null)
      database.drop();
  }

  private static String name(final int p) {
    return String.format(Locale.ENGLISH, "f%02d", p);
  }

  @Test
  void projectionShapes() {
    final String range = "key BETWEEN 0 AND " + (rows - 1);
    System.out.printf(Locale.ENGLISH, "%nProjection header pass: %,d rows x %d properties, index-hit rows, median of %d runs%n", rows,
        PROPERTIES, MEASURED_ITERATIONS);
    System.out.printf(Locale.ENGLISH, "%-52s %-7s %10s %10s %14s %12s%n", "shape", "cache", "wall ms", "cpu ms", "alloc MB", "ns/row");

    compare("SQL 3 of 30: SELECT f03, f11, f27 ... WHERE key range",
        () -> consumeSql("SELECT f03, f11, f27 FROM Wide WHERE " + range));
    compare("SQL 10 of 30: SELECT f00..f27 step 3 ... WHERE key range",
        () -> consumeSql("SELECT f00, f03, f06, f09, f12, f15, f18, f21, f24, f27 FROM Wide WHERE " + range));
    compare("SQL expression: SELECT f04 + f07, f01.toUpperCase() ...",
        () -> consumeSql("SELECT f04 + f07 AS sum, f01.toUpperCase() AS upper, f29 FROM Wide WHERE " + range));
    compare("SQL 30 of 30: SELECT * ... WHERE key range",
        () -> consumeSql("SELECT * FROM Wide WHERE " + range));
    compare("Cypher 3 of 30: RETURN n.f03, n.f11, n.f27",
        () -> consumeCypher("MATCH (n:Wide) WHERE n.key >= 0 AND n.key <= " + (rows - 1) + " RETURN n.f03, n.f11, n.f27"));
    compare("Cypher WITH 3 of 30 then RETURN",
        () -> consumeCypher("MATCH (n:Wide) WHERE n.key >= 0 AND n.key <= " + (rows - 1)
            + " WITH n.f03 AS a, n.f11 AS b, n.f27 AS c RETURN a, b, c"));

    // THE SERIALIZER CHANGE IS NOT BEHIND THE FLAG. THE TWO READINGS OF AN ELEMENT ROW THE SERIALIZERS (JSON, Bolt, gRPC,
    // Postgres) TAKE ARE SPELLED OUT HERE AS ALTERNATING ARMS: NAMES THEN get() PER NAME (before), ONE toPropertyMap PASS
    // (after, what JsonSerializer.serializeResult now reads). The full serializeResult is reported on its own for scale
    final String scan = "SELECT FROM Wide WHERE " + range;
    final long[][] serializerArms = measurePairs(() -> {
      long count = 0;
      try (final ResultSet rs = database.query("sql", scan)) {
        while (rs.hasNext()) {
          final Result row = rs.next();
          final Document document = row.toElement();
          final Map<String, Object> values = new java.util.LinkedHashMap<>();
          for (final String propertyName : row.getPropertyNames())
            values.put(propertyName, document.get(propertyName));
          count += values.size() > 0 ? 1 : 0;
        }
      }
      return count;
    }, () -> {
      long count = 0;
      try (final ResultSet rs = database.query("sql", scan)) {
        while (rs.hasNext())
          count += rs.next().toPropertyMap(false).size() > 0 ? 1 : 0;
      }
      return count;
    });
    report("Element row read: names + get (serializers before)", "before", serializerArms[0]);
    report("Element row read: toPropertyMap (serializers after)", "after", serializerArms[1]);
    final JsonSerializer serializer = JsonSerializer.createJsonSerializer();
    report("JSON element rows: full serializeResult (after)", "after", measure(() -> {
      long count = 0;
      try (final ResultSet rs = database.query("sql", scan)) {
        while (rs.hasNext()) {
          serializer.serializeResult(database, rs.next());
          count++;
        }
      }
      return count;
    }));

    // THE SAME SHAPE AGAIN AT THE END OF THE RUN: A DIFFERENCE FROM THE FIRST PAIR IS THE HEAP AND THE JIT, NOT THE CODE
    compare("SQL 30 of 30: SELECT * ... (repeated, end of run)",
        () -> consumeSql("SELECT * FROM Wide WHERE " + range));
    compare("SQL 3 of 30: SELECT f03, f11, f27 ... (repeated, end of run)",
        () -> consumeSql("SELECT f03, f11, f27 FROM Wide WHERE " + range));

    // ITEM S5 IN SQL, DEFERRED: WHAT A count(*) OVER THE INDEX HITS COSTS TODAY
    // ONE ROW COMES BACK: ns/row IS REPORTED PER INDEX HIT
    final long[] count = measure(() -> consumeSql("SELECT count(*) FROM Wide WHERE " + range));
    count[3] = rows;
    report("SQL count(*) over index hits (ns per hit)", "today", count);
  }

  /**
   * Before (the per-property reading) and after (the default) as ALTERNATING pairs: every measured iteration runs one of
   * each, so heap, JIT and the load of other processes on the machine fall on both arms alike.
   */
  private void compare(final String shape, final LongSupplier run) {
    final LongSupplier before = () -> {
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, false);
      return run.getAsLong();
    };
    final LongSupplier after = () -> {
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, true);
      return run.getAsLong();
    };
    final long[][] measured = measurePairs(before, after);
    report(shape, "before", measured[0]);
    report(shape, "after", measured[1]);
    database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, true);
  }

  /** {@link #measure} of two arms, alternated per iteration: the medians of each arm. */
  private static long[][] measurePairs(final LongSupplier first, final LongSupplier second) {
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      first.getAsLong();
      second.getAsLong();
    }
    final long[][] firstRuns = new long[4][MEASURED_ITERATIONS];
    final long[][] secondRuns = new long[4][MEASURED_ITERATIONS];
    for (int i = 0; i < MEASURED_ITERATIONS; i++) {
      // THE ARM THAT RUNS FIRST ALTERNATES TOO, SO NEITHER ALWAYS INHERITS THE OTHER'S GARBAGE
      if ((i & 1) == 0) {
        runOnce(first, firstRuns, i);
        runOnce(second, secondRuns, i);
      } else {
        runOnce(second, secondRuns, i);
        runOnce(first, firstRuns, i);
      }
    }
    return new long[][] { medians(firstRuns), medians(secondRuns) };
  }

  private static void runOnce(final LongSupplier run, final long[][] into, final int iteration) {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final com.sun.management.ThreadMXBean sun = threads instanceof com.sun.management.ThreadMXBean s ? s : null;
    final long cpuBefore = threads.getCurrentThreadCpuTime();
    final long allocBefore = sun != null ? sun.getCurrentThreadAllocatedBytes() : 0;
    final StallAwareStopwatch watch = StallAwareStopwatch.start();
    into[3][iteration] = run.getAsLong();
    into[0][iteration] = watch.effectiveMs();
    into[1][iteration] = (threads.getCurrentThreadCpuTime() - cpuBefore) / 1_000_000;
    into[2][iteration] = sun != null ? sun.getCurrentThreadAllocatedBytes() - allocBefore : 0;
  }

  private static long[] medians(final long[][] runs) {
    return new long[] { median(runs[0]), median(runs[1]), median(runs[2]), runs[3][MEASURED_ITERATIONS - 1] };
  }

  private long consumeSql(final String sql) {
    long count = 0;
    try (final ResultSet rs = database.query("sql", sql)) {
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
    }
    return count;
  }

  private long consumeCypher(final String cypher) {
    long count = 0;
    try (final ResultSet rs = database.query("opencypher", cypher)) {
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
    }
    return count;
  }

  /** wall ms, cpu ms, allocated bytes, rows: the medians of the measured runs. */
  private static long[] measure(final LongSupplier run) {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final com.sun.management.ThreadMXBean sun = threads instanceof com.sun.management.ThreadMXBean s ? s : null;
    for (int i = 0; i < WARMUP_ITERATIONS; i++)
      run.getAsLong();
    final long[] wall = new long[MEASURED_ITERATIONS];
    final long[] cpu = new long[MEASURED_ITERATIONS];
    final long[] alloc = new long[MEASURED_ITERATIONS];
    long rowsRead = 0;
    for (int i = 0; i < MEASURED_ITERATIONS; i++) {
      final long cpuBefore = threads.getCurrentThreadCpuTime();
      final long allocBefore = sun != null ? sun.getCurrentThreadAllocatedBytes() : 0;
      final StallAwareStopwatch watch = StallAwareStopwatch.start();
      rowsRead = run.getAsLong();
      wall[i] = watch.effectiveMs();
      cpu[i] = (threads.getCurrentThreadCpuTime() - cpuBefore) / 1_000_000;
      alloc[i] = sun != null ? sun.getCurrentThreadAllocatedBytes() - allocBefore : 0;
    }
    return new long[] { median(wall), median(cpu), median(alloc), rowsRead };
  }

  private static long median(final long[] values) {
    final long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static void report(final String shape, final String cache, final long[] measured) {
    final long rowsRead = Math.max(1, measured[3]);
    System.out.printf(Locale.ENGLISH, "%-52s %-7s %10d %10d %14.1f %12d%n", shape, cache, measured[0], measured[1],
        measured[2] / (1024d * 1024d), measured[0] * 1_000_000 / rowsRead);
  }
}
