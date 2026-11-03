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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nearest-neighbour and radius latency over synthetic US points, before (a scan, the only plan there was; the SQL scan runs
 * in parallel over the type) and after ({@code geo.nearest}, and {@code geo.dWithinMeters} answered from the GEOSPATIAL
 * index, single-threaded), on a compacted index.
 * <p>
 * The points are 60% around 30 metro areas (Gaussian, sigma 0.3 degrees, about 33 km) and 40% uniform over the contiguous
 * US, so a query meets both dense and sparse cells. Half the query points are metro centres, half are uniform.
 * <p>
 * {@code -Darcadedb.geoBenchmark.points=5000000} sets the size (default 5M), {@code -Darcadedb.geoBenchmark.scanQueries=3}
 * how many queries run the scan plan (each reads every record).
 */
@Tag("benchmark")
class GeoIndexBenchmark {
  private static final String POINTS_PROPERTY       = "arcadedb.geoBenchmark.points";
  private static final String SCAN_QUERIES_PROPERTY = "arcadedb.geoBenchmark.scanQueries";
  private static final int    QUERIES               = 40;
  private static final int    BATCH                 = 20_000;
  private static final Path   ROOT                  = Path.of("target", "databases", "GeoIndexBenchmark");

  private static final double[][] METROS = { { -74.0, 40.7 }, { -118.2, 34.0 }, { -87.6, 41.9 }, { -95.4, 29.8 }, { -112.1, 33.4 },
      { -75.2, 39.9 }, { -98.5, 29.4 }, { -117.2, 32.7 }, { -96.8, 32.8 }, { -121.9, 37.3 }, { -97.7, 30.3 }, { -81.7, 30.3 },
      { -83.0, 40.0 }, { -80.8, 35.2 }, { -86.2, 39.8 }, { -122.4, 37.8 }, { -122.3, 47.6 }, { -105.0, 39.7 }, { -77.0, 38.9 },
      { -71.1, 42.4 }, { -84.4, 33.7 }, { -80.2, 25.8 }, { -90.1, 29.95 }, { -93.3, 44.98 }, { -115.1, 36.2 }, { -86.8, 36.2 },
      { -90.2, 38.6 }, { -82.5, 27.95 }, { -94.6, 39.1 }, { -111.9, 40.8 } };

  @Test
  void nearestAndRadiusBeforeAndAfter() throws Exception {
    final int points = Integer.getInteger(POINTS_PROPERTY, 5_000_000);
    final int scanQueries = Integer.getInteger(SCAN_QUERIES_PROPERTY, 3);

    FileUtils.deleteRecursively(ROOT.toFile());
    try (final DatabaseFactory factory = new DatabaseFactory(ROOT.toString()); final Database database = factory.create()) {
      final long loadMs = load(database, points);
      System.out.printf(Locale.ROOT, "%nLoaded %,d points with a GEOSPATIAL index in %,d ms (%,.0f points/s)%n", points, loadMs,
          points * 1000.0 / loadMs);

      // measured on a compacted index, as a production one is: right after a bulk load the LSM-Tree is a stack of mutable
      // pages, and every range scan opens a cursor on each of them
      final long compactStart = System.nanoTime();
      int compacted = 0;
      for (final IndexInternal bucketIndex : ((TypeIndex) database.getSchema().getIndexByName("Spot[coords]")).getIndexesOnBuckets()) {
        // a compaction the load scheduled may still be running: let it end, then run one over everything
        while (bucketIndex.isCompacting())
          Thread.sleep(100);
        if (bucketIndex.scheduleCompaction() && bucketIndex.compact())
          ++compacted;
      }
      System.out.printf(Locale.ROOT, "Compacted %d bucket index(es) in %,d ms: %s%n", compacted,
          (System.nanoTime() - compactStart) / 1_000_000, ((TypeIndex) database.getSchema().getIndexByName("Spot[coords]")).getStats());

      final List<double[]> queries = queryPoints();

      // warm-up, so the first measured query does not pay for the class loading and the page cache
      for (int i = 0; i < 5; i++)
        count(database, "SELECT count(*) AS c FROM (SELECT expand(geo.nearest('Spot[coords]', ?, 10)))", wkt(queries.get(i)));

      System.out.printf(Locale.ROOT, "%n| query | plan | median ms | p95 ms | max ms | rows (median) |%n|---|---|---|---|---|---|%n");
      for (final int k : new int[] { 10, 100 }) {
        report("kNN k=" + k, "after: geo.nearest", database, queries,
            "SELECT count(*) AS c FROM (SELECT expand(geo.nearest('Spot[coords]', ?, " + k + ")))");
        report("kNN k=" + k, "before: scan + ORDER BY geo.distance", database, queries.subList(0, scanQueries),
            "SELECT count(*) AS c FROM (SELECT id, geo.distance(coords, ?) AS d FROM Spot ORDER BY d LIMIT " + k + ")");
      }
      for (final int metres : new int[] { 25_000, 200_000 }) {
        report("radius " + metres / 1000 + " km", "after: geo.dWithinMeters (index)", database, queries,
            "SELECT count(id) AS c FROM Spot WHERE geo.dWithinMeters(coords, ?, " + metres + ") = true");
        report("radius " + metres / 1000 + " km", "before: scan + geo.distance", database, queries.subList(0, scanQueries),
            "SELECT count(id) AS c FROM Spot WHERE geo.distance(coords, ?) <= " + metres);
      }

      // the index answers what the scan answers
      for (final double[] q : queries.subList(0, scanQueries))
        assertThat(count(database, "SELECT count(id) AS c FROM Spot WHERE geo.dWithinMeters(coords, ?, 25000) = true", wkt(q)))
            .isEqualTo(count(database, "SELECT count(id) AS c FROM Spot WHERE geo.distance(coords, ?) <= 25000", wkt(q)));

      database.drop();
    } finally {
      FileUtils.deleteRecursively(ROOT.toFile());
    }
  }

  private static long load(final Database database, final int points) {
    database.command("sql", "CREATE DOCUMENT TYPE Spot");
    database.command("sql", "CREATE PROPERTY Spot.coords STRING");
    database.command("sql", "CREATE INDEX ON Spot (coords) GEOSPATIAL");

    final Random random = new Random(42);
    final long start = System.nanoTime();
    database.begin();
    for (int i = 0; i < points; i++) {
      final double lon;
      final double lat;
      if (random.nextDouble() < 0.6) {
        final double[] metro = METROS[random.nextInt(METROS.length)];
        lon = metro[0] + random.nextGaussian() * 0.3;
        lat = metro[1] + random.nextGaussian() * 0.3;
      } else {
        lon = -124.7 + random.nextDouble() * 57.7;
        lat = 25.0 + random.nextDouble() * 24.0;
      }
      database.newDocument("Spot").set("id", i).set("coords", "POINT (" + lon + " " + lat + ")").save();
      if ((i + 1) % BATCH == 0) {
        database.commit();
        database.begin();
      }
    }
    database.commit();
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static List<double[]> queryPoints() {
    final Random random = new Random(7);
    final List<double[]> queries = new ArrayList<>(QUERIES);
    for (int i = 0; i < QUERIES; i++)
      if (i % 2 == 0) {
        final double[] metro = METROS[random.nextInt(METROS.length)];
        queries.add(new double[] { metro[0] + random.nextGaussian() * 0.1, metro[1] + random.nextGaussian() * 0.1 });
      } else
        queries.add(new double[] { -124.7 + random.nextDouble() * 57.7, 25.0 + random.nextDouble() * 24.0 });
    return queries;
  }

  private static void report(final String query, final String plan, final Database database, final List<double[]> queries,
      final String sql) {
    final double[] millis = new double[queries.size()];
    final long[] rows = new long[queries.size()];
    for (int i = 0; i < queries.size(); i++) {
      final long start = System.nanoTime();
      rows[i] = count(database, sql, wkt(queries.get(i)));
      millis[i] = (System.nanoTime() - start) / 1e6;
    }
    Arrays.sort(millis);
    Arrays.sort(rows);
    System.out.printf(Locale.ROOT, "| %s | %s | %.2f | %.2f | %.2f | %,d |%n", query, plan, millis[millis.length / 2],
        millis[Math.min(millis.length - 1, (int) Math.ceil(millis.length * 0.95) - 1)], millis[millis.length - 1],
        rows[rows.length / 2]);
  }

  private static long count(final Database database, final String sql, final String point) {
    try (final ResultSet rs = database.query("sql", sql, point)) {
      return ((Number) rs.next().getProperty("c")).longValue();
    }
  }

  private static String wkt(final double[] point) {
    return "POINT (" + point[0] + " " + point[1] + ")";
  }
}
