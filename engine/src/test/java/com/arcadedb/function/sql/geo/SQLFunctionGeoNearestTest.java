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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code geo.nearest}: the k records of a geospatially indexed property nearest to a point, from the index.
 */
class SQLFunctionGeoNearestTest extends TestHelper {

  @BeforeEach
  void createPlaces() {
    database.command("sql", "CREATE DOCUMENT TYPE Place");
    database.command("sql", "CREATE PROPERTY Place.name STRING");
    database.command("sql", "CREATE PROPERTY Place.coords STRING");
    database.command("sql", "CREATE INDEX ON Place (coords) GEOSPATIAL");
    database.command("sql", "CREATE DOCUMENT TYPE Hotel EXTENDS Place");

    database.transaction(() -> {
      database.command("sql", "INSERT INTO Place SET name = 'Rome', kind = 'city', coords = 'POINT (12.4964 41.9028)'");
      database.command("sql", "INSERT INTO Place SET name = 'Naples', kind = 'city', coords = 'POINT (14.2681 40.8518)'");
      database.command("sql", "INSERT INTO Place SET name = 'Florence', kind = 'city', coords = 'POINT (11.2558 43.7696)'");
      database.command("sql", "INSERT INTO Place SET name = 'Milan', kind = 'city', coords = 'POINT (9.19 45.4642)'");
      database.command("sql", "INSERT INTO Place SET name = 'Tivoli', kind = 'town', coords = 'POINT (12.7967 41.9636)'");
      database.command("sql", "INSERT INTO Hotel SET name = 'Hotel Roma', kind = 'hotel', coords = 'POINT (12.49 41.90)'");
    });
  }

  @Test
  void answersTheNearestFirstWithTheirDistanceInMetres() {
    final List<Result> rows = rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12.4964 41.9028)', 3))");
    assertThat(names(rows)).containsExactly("Rome", "Hotel Roma", "Tivoli");
    assertThat(rows.getFirst().<Double>getProperty("distance")).isCloseTo(0.0, within(1e-6));
    // the same metres geo.distance answers
    final double expected = (double) rows("SELECT geo.distance('POINT (12.4964 41.9028)', 'POINT (12.7967 41.9636)') AS d")
        .getFirst().getProperty("d");
    assertThat(rows.get(2).<Double>getProperty("distance")).isCloseTo(expected, within(1e-6));
    assertThat(rows.get(2).<String>getProperty("kind")).isEqualTo("town");
    assertThat(rows.get(2).<Object>getProperty("@rid")).isNotNull();
  }

  @Test
  void acceptsTheIndexNameAndACypherPointMap() {
    final List<Result> rows = rows(
        "SELECT expand(geo.nearest('Place[coords]', {'longitude': 14.0, 'latitude': 40.9}, 1))");
    assertThat(names(rows)).containsExactly("Naples");

    final String indexName = database.getSchema().getType("Place").getAllIndexes(false).iterator().next().getName();
    assertThat(names(rows("SELECT expand(geo.nearest(?, 'POINT (14.0 40.9)', 1))", indexName))).containsExactly("Naples");
  }

  @Test
  void whereRestrictsTheCandidates() {
    assertThat(names(rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12.4964 41.9028)', 2, {where: \"kind = 'city'\"}))")))
        .containsExactly("Rome", "Naples");
    assertThat(names(rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12.4964 41.9028)', 2, {where: {kind: 'town'}}))")))
        .containsExactly("Tivoli");
    assertThat(names(rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12.4964 41.9028)', 2, {where: 'kind = :k'}))",
        Map.of("k", "city")))).containsExactly("Rome", "Naples");
  }

  @Test
  void maxDistanceMetersBoundsTheAnswer() {
    assertThat(names(rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12.4964 41.9028)', 10, {maxDistanceMeters: 30000}))")))
        .containsExactly("Rome", "Hotel Roma", "Tivoli");
  }

  @Test
  void aSubtypeAnswersOnlyItsOwnRecords() {
    assertThat(names(rows("SELECT expand(geo.nearest('Hotel[coords]', 'POINT (14.2 40.8)', 3))"))).containsExactly("Hotel Roma");
  }

  @Test
  void projectsAsARowSource() {
    final List<Result> rows = rows(
        "SELECT name, distance FROM (SELECT expand(geo.nearest('Place[coords]', 'POINT (9.2 45.4)', 2))) WHERE distance < 100000");
    assertThat(names(rows)).containsExactly("Milan");
  }

  @Test
  void zeroNeighboursAnswersNothing() {
    assertThat(rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12 41)', 0))")).isEmpty();
  }

  @Test
  void refusesAPropertyWithoutAGeospatialIndex() {
    assertThatThrownBy(() -> rows("SELECT expand(geo.nearest('Place[name]', 'POINT (12 41)', 3))"))
        .isInstanceOf(CommandExecutionException.class).hasMessageContaining("GEOSPATIAL");
  }

  @Test
  void refusesAShapeThatIsNotAPoint() {
    assertThatThrownBy(() -> rows("SELECT expand(geo.nearest('Place[coords]', 'POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))', 3))"))
        .isInstanceOf(CommandExecutionException.class).hasMessageContaining("point");
  }

  @Test
  void seesTheRecordsOfTheCurrentTransaction() {
    database.transaction(() -> {
      database.command("sql", "INSERT INTO Place SET name = 'Ostia', kind = 'town', coords = 'POINT (12.29 41.73)'");
      assertThat(names(rows("SELECT expand(geo.nearest('Place[coords]', 'POINT (12.29 41.73)', 1))"))).containsExactly("Ostia");
    });
  }

  private List<Result> rows(final String sql, final Object... args) {
    final List<Result> out = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", sql, args)) {
      while (rs.hasNext())
        out.add(rs.next());
    }
    return out;
  }

  private List<Result> rows(final String sql, final Map<String, Object> args) {
    final List<Result> out = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", sql, args)) {
      while (rs.hasNext())
        out.add(rs.next());
    }
    return out;
  }

  private static List<String> names(final List<Result> rows) {
    return rows.stream().map(r -> r.<String>getProperty("name")).toList();
  }
}
