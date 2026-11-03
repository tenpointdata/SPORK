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
package com.arcadedb.function.sql.geo;

import com.arcadedb.TestHelper;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.io.GeohashUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The query shapes of a convergence analysis, two identifier groups within 200 km and 14 days of each other: the GeoHash
 * helpers that put a radius and a time window on one composite index, and the shapes themselves, checked against brute
 * force.
 */
class GeoConvergencePatternTest extends TestHelper {
  private static final long   DAY_MS = 86_400_000L;
  private static final double RADIUS = 200_000;

  @Test
  void geohashOfAPoint() {
    assertThat(value("SELECT geo.geohash('POINT (-96.8 32.8)', 5) AS v")).isEqualTo(GeohashUtils.encodeLatLon(32.8, -96.8, 5));
    assertThat(value("SELECT geo.geohash('POINT (-96.8 32.8)') AS v")).isEqualTo(GeohashUtils.encodeLatLon(32.8, -96.8, 12));
    assertThat(value("SELECT geo.geohash('POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))', 5) AS v")).isNull();
    assertThatThrownBy(() -> value("SELECT geo.geohash('POINT (0 0)', 13) AS v")).isInstanceOf(CommandExecutionException.class);
  }

  /** Every point within the distance has its cell in the cover, wherever it is: near a pole and across the antimeridian too. */
  @Test
  void theCoverHoldsTheCellOfEveryPointInTheRadius() {
    final Random random = new Random(19);
    for (int q = 0; q < 30; q++) {
      final double lon = q == 0 ? 179.5 : q == 1 ? -179.9 : random.nextDouble() * 360 - 180;
      final double lat = q == 2 ? 88.5 : random.nextDouble() * 160 - 80;
      final String centre = "POINT (" + lon + " " + lat + ")";
      final Set<String> cover = new HashSet<>(SQLFunctionGeoHashCover.cover(
          GeoUtils.searchShapeWithin(GeoUtils.parseGeometry(centre), GeoUtils.metresToDegrees(RADIUS)), 3));
      for (int i = 0; i < 400; i++) {
        // a random point of the disc, by bearing and distance
        final double bearing = random.nextDouble() * 2 * Math.PI;
        final double d = Math.sqrt(random.nextDouble()) * RADIUS / (GeoUtils.EARTH_RADIUS_KM * 1000);
        final double phi1 = Math.toRadians(lat);
        final double phi2 = Math.asin(Math.sin(phi1) * Math.cos(d) + Math.cos(phi1) * Math.sin(d) * Math.cos(bearing));
        final double lambda2 = Math.toRadians(lon) + Math.atan2(Math.sin(bearing) * Math.sin(d) * Math.cos(phi1),
            Math.cos(d) - Math.sin(phi1) * Math.sin(phi2));
        final double plon = ((Math.toDegrees(lambda2) + 540) % 360) - 180;
        assertThat(cover).as("from %s", centre).contains(GeohashUtils.encodeLatLon(Math.toDegrees(phi2), plon, 3));
      }
    }
    // and it is the area, not the world: 200 km at mid latitude is a handful of 1.4 degree cells
    final List<?> cells = (List<?>) value("SELECT geo.geohashCover('POINT (-96.8 32.8)', 3, 200000) AS v");
    assertThat(cells.size()).isBetween(9, 25);
    assertThatThrownBy(() -> value("SELECT geo.geohashCover('POINT (-96.8 32.8)', 9, 2000000) AS v"))
        .isInstanceOf(CommandExecutionException.class).hasMessageContaining("coarser");
  }

  /**
   * Open-ended convergence, "who was within 200 km of this sighting within 14 days of it": a composite index on a GeoHash
   * cell and the day turns the radius and the window into one index range per cell, instead of every sighting of the radius
   * at any time. The exact check is {@code geo.distance}, which no index answers: written with {@code geo.dWithinMeters},
   * the planner would take the GEOSPATIAL index on the same property instead, and read the whole disc.
   */
  @Test
  void aRadiusAndATimeWindowReadTheCompositeIndex() {
    final List<double[]> sightings = createSightings(4000);

    final Random random = new Random(29);
    for (int q = 0; q < 10; q++) {
      final double[] origin = sightings.get(random.nextInt(sightings.size()));
      final String centre = "POINT (" + origin[0] + " " + origin[1] + ")";
      final long day = (long) origin[3] / DAY_MS;
      final String sql = "SELECT id FROM Sighting WHERE cell3 IN geo.geohashCover(:p, 3, 200000) AND day BETWEEN :from AND :to"
          + " AND geo.distance(coords, :p) <= 200000";
      final Map<String, Object> params = Map.of("p", centre, "from", day - 14, "to", day + 14);

      assertThat(explain(sql, params)).contains("FETCH FROM INDEX Sighting[cell3,day]");
      final Set<Integer> expected = new HashSet<>();
      for (final double[] s : sightings)
        if (Math.abs((long) s[3] / DAY_MS - day) <= 14
            && GeoUtils.haversineRadians(origin[0], origin[1], s[0], s[1]) * GeoUtils.EARTH_RADIUS_KM * 1000 <= RADIUS)
          expected.add((int) s[2]);
      assertThat(ids(sql, params)).isEqualTo(expected).isNotEmpty();
    }
  }

  /**
   * Closed convergence, the analysis as stated: both groups are known, so their sightings are read through the index on the
   * group and paired, the distance and the time gap checked on each pair. No spatial index takes part, and none can do
   * better: the pairs to check are those of two small sets.
   */
  @Test
  void twoKnownGroupsArePairedInCypher() {
    final List<double[]> sightings = createSightings(1500);

    final String cypher = "MATCH (a:Sighting {groupId: 'A'}), (b:Sighting {groupId: 'B'}) "
        + "WHERE point.distance(a.coords, b.coords) < 200000 AND abs(a.ts - b.ts) <= 14 * 86400000 "
        + "RETURN a.id AS a, b.id AS b";
    final Set<List<Integer>> pairs = new HashSet<>();
    try (final ResultSet rs = database.query("opencypher", cypher)) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        pairs.add(List.of(r.<Integer>getProperty("a"), r.<Integer>getProperty("b")));
      }
    }

    final Set<List<Integer>> expected = new HashSet<>();
    for (final double[] a : sightings)
      for (final double[] b : sightings)
        if (a[4] == 0 && b[4] == 1 && Math.abs(a[3] - b[3]) <= 14 * DAY_MS
            && GeoUtils.haversineRadians(a[0], a[1], b[0], b[1]) * GeoUtils.EARTH_RADIUS_KM * 1000 < RADIUS)
          expected.add(List.of((int) a[2], (int) b[2]));
    assertThat(pairs).isEqualTo(expected).isNotEmpty();
  }

  /** Sightings over Texas and Oklahoma in 2025, {lon, lat, id, ts, group}: group 0 is 'A', 1 is 'B', the rest are 'other'. */
  private List<double[]> createSightings(final int count) {
    database.command("sql", "CREATE VERTEX TYPE Sighting");
    database.command("sql", "CREATE PROPERTY Sighting.coords STRING");
    database.command("sql", "CREATE PROPERTY Sighting.cell3 STRING");
    database.command("sql", "CREATE PROPERTY Sighting.day LONG");
    database.command("sql", "CREATE PROPERTY Sighting.groupId STRING");
    database.command("sql", "CREATE INDEX ON Sighting (cell3, day) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Sighting (groupId) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Sighting (coords) GEOSPATIAL");

    final Random random = new Random(37);
    final long start = 1_735_689_600_000L; // 2025-01-01
    final String[] groups = { "A", "B", "other" };
    final List<double[]> sightings = new ArrayList<>(count);
    database.transaction(() -> {
      for (int i = 0; i < count; i++) {
        final double lon = -104 + random.nextDouble() * 10;
        final double lat = 29 + random.nextDouble() * 8;
        final long ts = start + (long) (random.nextDouble() * 365 * DAY_MS);
        final int group = i % 10 == 0 ? 0 : i % 10 == 1 ? 1 : 2;
        sightings.add(new double[] { lon, lat, i, ts, group });
        database.command("sql", "INSERT INTO Sighting SET id = ?, groupId = ?, ts = ?, day = ?, coords = ?,"
                + " cell3 = geo.geohash(?, 3)", i, groups[group], ts, ts / DAY_MS, "POINT (" + lon + " " + lat + ")",
            "POINT (" + lon + " " + lat + ")");
      }
    });
    return sightings;
  }

  private Object value(final String sql) {
    try (final ResultSet rs = database.query("sql", sql)) {
      return rs.next().getProperty("v");
    }
  }

  private String explain(final String sql, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("sql", "EXPLAIN " + sql, params)) {
      return rs.next().getProperty("executionPlanAsString");
    }
  }

  private Set<Integer> ids(final String sql, final Map<String, Object> params) {
    final Set<Integer> out = new HashSet<>();
    try (final ResultSet rs = database.query("sql", sql, params)) {
      while (rs.hasNext())
        out.add(rs.next().getProperty("id"));
    }
    return out;
  }
}
