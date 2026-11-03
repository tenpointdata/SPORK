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
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code geo.dWithinMeters}, the radius predicate in metres, and {@code geo.dWithin}, its degree twin: both answer from the
 * geospatial index with the exact same rows as a scan.
 */
class SQLFunctionGeoDWithinMetersTest extends TestHelper {

  @Test
  void evaluatesInMetres() {
    // one degree of latitude is 111.19 km on the mean-radius sphere geo.distance uses
    assertThat(value("SELECT geo.dWithinMeters('POINT (0 0)', 'POINT (0 1)', 111200) AS v")).isEqualTo(true);
    assertThat(value("SELECT geo.dWithinMeters('POINT (0 0)', 'POINT (0 1)', 111100) AS v")).isEqualTo(false);
    assertThat(value("SELECT geo.dWithinMeters(null, 'POINT (0 1)', 111100) AS v")).isNull();
    // a polygon is measured from its nearest edge
    assertThat(value("SELECT geo.dWithinMeters('POINT (0 0)', 'POLYGON ((1 -1, 2 -1, 2 1, 1 1, 1 -1))', 111200) AS v"))
        .isEqualTo(true);
  }

  @Test
  void answersFromTheIndexExactlyAsTheScan() {
    createType("Indexed", true);
    createType("Scanned", false);
    final Random random = new Random(17);
    database.transaction(() -> {
      for (int i = 0; i < 3000; i++) {
        final String wkt = "POINT (" + (-100 + random.nextDouble() * 10) + " " + (35 + random.nextDouble() * 8) + ")";
        database.newDocument("Indexed").set("id", i).set("coords", wkt).save();
        database.newDocument("Scanned").set("id", i).set("coords", wkt).save();
      }
      // a polygon whose nearest edge is inside the radius while its centre is far outside it
      database.newDocument("Indexed").set("id", -1).set("coords", "POLYGON ((-94.7 39, -90 39, -90 41, -94.7 41, -94.7 39))").save();
      database.newDocument("Scanned").set("id", -1).set("coords", "POLYGON ((-94.7 39, -90 39, -90 41, -94.7 41, -94.7 39))").save();
    });

    for (final double metres : new double[] { 1000, 25_000, 200_000 }) {
      final String where = "geo.dWithinMeters(coords, 'POINT (-95 40)', " + metres + ") = true";
      assertThat(explain("SELECT id FROM Indexed WHERE " + where)).contains("FETCH FROM INDEXED FUNCTION");
      assertThat(ids("SELECT id FROM Indexed WHERE " + where)).isEqualTo(ids("SELECT id FROM Scanned WHERE " + where));
    }
    assertThat(ids("SELECT id FROM Indexed WHERE geo.dWithinMeters(coords, 'POINT (-95 40)', 200000) = true")).contains(-1);

    // geo.dWithin takes degrees, and is now index-backed too
    final String degrees = "geo.dWithin(coords, 'POINT (-95 40)', 0.5) = true";
    assertThat(explain("SELECT id FROM Indexed WHERE " + degrees)).contains("FETCH FROM INDEXED FUNCTION");
    assertThat(ids("SELECT id FROM Indexed WHERE " + degrees)).isEqualTo(ids("SELECT id FROM Scanned WHERE " + degrees));

    // a radius the query can only read off each record cannot drive the index, and is evaluated on the scan
    final String perRecord = "geo.dWithinMeters(coords, 'POINT (-95 40)', id * 100) = true";
    assertThat(explain("SELECT id FROM Indexed WHERE " + perRecord)).doesNotContain("FETCH FROM INDEXED FUNCTION");
    assertThat(ids("SELECT id FROM Indexed WHERE " + perRecord)).isEqualTo(ids("SELECT id FROM Scanned WHERE " + perRecord));
  }

  @Test
  void aRadiusAcrossTheAntimeridianAndNearThePole() {
    createType("Edge", true);
    database.transaction(() -> {
      database.newDocument("Edge").set("id", 1).set("coords", "POINT (179.95 10)").save();
      database.newDocument("Edge").set("id", 2).set("coords", "POINT (-179.95 10)").save();
      database.newDocument("Edge").set("id", 3).set("coords", "POINT (0 89.9)").save();
      database.newDocument("Edge").set("id", 4).set("coords", "POINT (180 89.9)").save();
    });
    assertThat(ids("SELECT id FROM Edge WHERE geo.dWithinMeters(coords, 'POINT (179.99 10)', 20000) = true")).containsExactlyInAnyOrder(1, 2);
    // the two points near the pole are 0.2 degrees (22 km) apart across it
    assertThat(ids("SELECT id FROM Edge WHERE geo.dWithinMeters(coords, 'POINT (90 89.9)', 20000) = true")).containsExactlyInAnyOrder(3, 4);
  }

  /**
   * A record written earlier in the same transaction is a candidate of the index: the prefix range scan of a covering cell
   * reads the committed pages only, so the entries the transaction queued are merged in.
   */
  @Test
  void seesTheRecordsOfTheCurrentTransaction() {
    createType("Pending", true);
    database.transaction(() -> database.newDocument("Pending").set("id", 1).set("coords", "POINT (10 10)").save());
    database.transaction(() -> {
      database.newDocument("Pending").set("id", 2).set("coords", "POINT (10.01 10.01)").save();
      assertThat(ids("SELECT id FROM Pending WHERE geo.dWithinMeters(coords, 'POINT (10 10)', 5000) = true"))
          .containsExactlyInAnyOrder(1, 2);
      assertThat(ids("SELECT id FROM Pending WHERE geo.within(coords, 'POLYGON ((9 9, 11 9, 11 11, 9 11, 9 9))') = true"))
          .containsExactlyInAnyOrder(1, 2);
    });
  }

  private void createType(final String name, final boolean indexed) {
    database.command("sql", "CREATE DOCUMENT TYPE " + name);
    database.command("sql", "CREATE PROPERTY " + name + ".coords STRING");
    if (indexed)
      database.command("sql", "CREATE INDEX ON " + name + " (coords) GEOSPATIAL");
  }

  private Object value(final String sql) {
    try (final ResultSet rs = database.query("sql", sql)) {
      return rs.next().getProperty("v");
    }
  }

  private String explain(final String sql) {
    try (final ResultSet rs = database.query("sql", "EXPLAIN " + sql)) {
      return rs.next().getProperty("executionPlanAsString");
    }
  }

  private Set<Integer> ids(final String sql) {
    final Set<Integer> out = new HashSet<>();
    try (final ResultSet rs = database.query("sql", sql)) {
      while (rs.hasNext())
        out.add(rs.next().getProperty("id"));
    }
    return out;
  }
}
