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
package com.arcadedb.index.geospatial;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.log.LogManager;
import com.arcadedb.utility.StallAwareStopwatch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.shape.Shape;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval speed S12: the cursor set-up of a wide geo radius. A 200 km circle decomposes into a few hundred covering cells,
 * most of them runs of adjacent frontier siblings; before, every cell opened its own LSM range cursor (one seek per mutable
 * page and per compacted series each), now a run opens one. Measured on the same index, same circle, same rows: the scans
 * opened, the time to the first candidate (what a {@code LIMIT 1} pays) and the time to drain the candidate set, with
 * coalescing off ("before") and on ("after"). Nothing is asserted on time.
 * <p>
 * Run with: {@code mvn -o -pl engine test -Dsurefire.includes='**\/GeoRadiusCursorSetupBenchmark.java' -DexcludedGroups=none}
 */
@Tag("benchmark")
class GeoRadiusCursorSetupBenchmark extends TestHelper {
  private static final int    POINTS    = 200_000;
  private static final int    RUNS      = 30;
  // ROME, 200 KM: ABOUT 1.8 DEGREES OF LATITUDE, 2.4 OF LONGITUDE AT THAT LATITUDE
  private static final double CENTER_LAT = 41.9, CENTER_LON = 12.5;
  private static final double RADIUS_KM  = 200;

  @Test
  void cursorSetUpOfA200KmRadius() throws Exception {
    database.transaction(() -> {
      database.getSchema().createDocumentType("Place", 1).createProperty("coords", String.class);
      database.command("sql", "CREATE INDEX ON Place (coords) GEOSPATIAL");
    });
    // POINTS SPREAD OVER A 12 x 12 DEGREE BOX AROUND THE CENTRE, SO THE CIRCLE HOLDS ABOUT A TENTH OF THEM
    final Random random = new Random(20261102L);
    for (int from = 0; from < POINTS; from += 5_000) {
      database.transaction(() -> {
        for (int i = 0; i < 5_000; i++) {
          final double lat = CENTER_LAT - 6 + random.nextDouble() * 12;
          final double lon = CENTER_LON - 6 + random.nextDouble() * 12;
          final MutableDocument place = database.newDocument("Place");
          place.set("coords", String.format(Locale.ROOT, "POINT (%.5f %.5f)", lon, lat));
          place.save();
        }
      });
    }

    final TypeIndex typeIndex = (TypeIndex) database.getSchema().getIndexByName("Place[coords]");
    final Index[] buckets = typeIndex.getIndexesOnBuckets();
    assertThat(buckets).hasSize(1);
    final LSMTreeGeoIndex index = (LSMTreeGeoIndex) buckets[0];
    final String circle = circleWkt();
    final Shape shape = GeoUtils.parseGeometry(circle);
    final Object[] keys = { circle };

    // THE SAME CANDIDATES EITHER WAY
    final Set<RID> before = drain(index.newCursor(keys, shape, false));
    final Set<RID> after = drain(index.newCursor(keys, shape, true));
    assertThat(after).isEqualTo(before);
    assertThat(before).isNotEmpty();

    for (int warm = 0; warm < 5; warm++) {
      drain(index.newCursor(keys, shape, false));
      drain(index.newCursor(keys, shape, true));
    }

    final StringBuilder report = new StringBuilder();
    report.append("======== Geo radius cursor set-up benchmark (S12) ========\n");
    report.append("points %d, radius %.0f km, candidates %d, runs %d%n".formatted(POINTS, RADIUS_KM, before.size(), RUNS));
    report.append("%-28s %8s %10s %14s %12s %10s%n".formatted("cursor", "scans", "coalesced", "first row us", "drain ms", "stall ms"));
    report.append(measure("before: one scan per cell", index, keys, shape, false));
    report.append(measure("after: one scan per run", index, keys, shape, true));
    report.append("============================================================");

    LogManager.instance().log(this, Level.INFO, report.toString());
    Files.writeString(new File("./target/geo-radius-cursor-benchmark.txt").toPath(), report + System.lineSeparator(),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private String measure(final String label, final LSMTreeGeoIndex index, final Object[] keys, final Shape shape,
      final boolean coalesce) {
    long firstRowNanos = 0;
    long drainNanos = 0;
    int scans = 0;
    int coalesced = 0;
    final StallAwareStopwatch watch = StallAwareStopwatch.start();
    for (int run = 0; run < RUNS; run++) {
      final long begin = System.nanoTime();
      final GeoIndexCursor cursor = index.newCursor(keys, shape, coalesce);
      cursor.hasNext();
      firstRowNanos += System.nanoTime() - begin;
      while (cursor.hasNext())
        cursor.next();
      drainNanos += System.nanoTime() - begin;
      scans = cursor.scansOpened();
      coalesced = cursor.cellsCoalesced();
    }
    return "%-28s %8d %10d %14.1f %12.2f %10d%n".formatted(label, scans, coalesced, firstRowNanos / 1_000.0 / RUNS,
        drainNanos / 1_000_000.0 / RUNS, watch.stallMs());
  }

  private static Set<RID> drain(final GeoIndexCursor cursor) {
    final Set<RID> rids = new HashSet<>();
    while (cursor.hasNext()) {
      final Identifiable candidate = cursor.next();
      rids.add(candidate.getIdentity());
    }
    return rids;
  }

  /** A 64-point polygon approximating the circle, in WKT (lon lat). */
  private static String circleWkt() {
    final double dLat = RADIUS_KM / 111.0;
    final double dLon = RADIUS_KM / (111.0 * Math.cos(Math.toRadians(CENTER_LAT)));
    final StringBuilder wkt = new StringBuilder("POLYGON ((");
    for (int i = 0; i <= 64; i++) {
      final double angle = 2 * Math.PI * (i % 64) / 64;
      if (i > 0)
        wkt.append(", ");
      wkt.append(String.format(Locale.ROOT, "%.5f %.5f", CENTER_LON + dLon * Math.cos(angle), CENTER_LAT + dLat * Math.sin(angle)));
    }
    return wkt.append("))").toString();
  }
}
