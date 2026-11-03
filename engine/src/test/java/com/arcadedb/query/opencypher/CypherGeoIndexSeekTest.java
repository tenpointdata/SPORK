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
package com.arcadedb.query.opencypher;

import com.arcadedb.TestHelper;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A {@code point.distance(n.prop, <point>) < r} or {@code point.withinBBox(n.prop, <ll>, <ur>)} predicate of a MATCH is
 * answered from the GEOSPATIAL index on {@code prop} (a WKT string property): the planner anchors the node on a
 * {@code NodeGeoIndexSeek}, visible in EXPLAIN, and the predicate still checks each candidate exactly.
 */
class CypherGeoIndexSeekTest extends TestHelper {
  private static final String CENTRE = "point({longitude: -95.0, latitude: 40.0})";

  @BeforeEach
  void createPlaces() {
    for (final String type : new String[] { "Place", "Plain" }) {
      database.command("sql", "CREATE VERTEX TYPE " + type);
      database.command("sql", "CREATE PROPERTY " + type + ".loc STRING");
    }
    database.command("sql", "CREATE INDEX ON Place (loc) GEOSPATIAL");

    final Random random = new Random(3);
    database.transaction(() -> {
      for (int i = 0; i < 2000; i++) {
        final String wkt = "POINT (" + (-100 + random.nextDouble() * 10) + " " + (35 + random.nextDouble() * 10) + ")";
        database.newVertex("Place").set("id", i).set("loc", wkt).save();
        database.newVertex("Plain").set("id", i).set("loc", wkt).save();
      }
    });
  }

  @Test
  void pointFunctionsReadAWktPoint() {
    final Result row = single("RETURN point.distance('POINT (12.5 41.9)', point({longitude: 12.5, latitude: 41.9})) AS d, "
        + "point.withinBBox('POINT (12.5 41.9)', point({longitude: 12, latitude: 41}), point({longitude: 13, latitude: 42})) AS b");
    assertThat(row.<Double>getProperty("d")).isCloseTo(0.0, within(1e-9));
    assertThat(row.<Boolean>getProperty("b")).isTrue();
  }

  @Test
  void aRadiusSeeksTheIndex() {
    for (final int metres : new int[] { 1000, 25_000, 200_000 }) {
      final String where = "WHERE point.distance(n.loc, " + CENTRE + ") < " + metres;
      assertThat(explain("MATCH (n:Place) " + where + " RETURN n.id AS id")).contains("NodeGeoIndexSeek");
      assertThat(ids("MATCH (n:Place) " + where + " RETURN n.id AS id")).isEqualTo(ids("MATCH (n:Plain) " + where + " RETURN n.id AS id"));
    }
    assertThat(ids("MATCH (n:Place) WHERE point.distance(n.loc, " + CENTRE + ") < 200000 RETURN n.id AS id")).isNotEmpty();
  }

  @Test
  void parametersFlippedOperandsAndOtherConjuncts() {
    final Map<String, Object> params = Map.of("c", Map.of("longitude", -95.0, "latitude", 40.0), "r", 150_000);
    final String[] wheres = {
        "WHERE $r >= point.distance(point($c), n.loc)",
        "WHERE point.distance(n.loc, point($c)) <= $r AND n.id % 2 = 0",
        "WHERE n.id > 100 AND point.distance(point($c), n.loc) < $r * 2" };
    for (final String where : wheres) {
      assertThat(explain("MATCH (n:Place) " + where + " RETURN n.id AS id", params)).as(where).contains("NodeGeoIndexSeek");
      assertThat(ids("MATCH (n:Place) " + where + " RETURN n.id AS id", params)).as(where)
          .isEqualTo(ids("MATCH (n:Plain) " + where + " RETURN n.id AS id", params));
    }
  }

  @Test
  void aBoundingBoxSeeksTheIndex() {
    final String where = "WHERE point.withinBBox(n.loc, point({longitude: -96.5, latitude: 39}), point({longitude: -94, latitude: 41.5}))";
    assertThat(explain("MATCH (n:Place) " + where + " RETURN n.id AS id")).contains("NodeGeoIndexSeek");
    final Set<Integer> found = ids("MATCH (n:Place) " + where + " RETURN n.id AS id");
    assertThat(found).isNotEmpty().isEqualTo(ids("MATCH (n:Plain) " + where + " RETURN n.id AS id"));
  }

  @Test
  void aCentreReadOffAnotherNodeIsLeftToTheFilter() {
    final String query = "MATCH (a:Place {id: 7}) MATCH (n:Place) WHERE point.distance(n.loc, a.loc) < 50000 RETURN n.id AS id";
    assertThat(ids(query)).isEqualTo(ids(query.replace("(n:Place)", "(n:Plain)")));
  }

  @Test
  void anOrIsLeftToTheFilter() {
    final String where = "WHERE point.distance(n.loc, " + CENTRE + ") < 20000 OR n.id = 3";
    assertThat(explain("MATCH (n:Place) " + where + " RETURN n.id AS id")).doesNotContain("NodeGeoIndexSeek");
    assertThat(ids("MATCH (n:Place) " + where + " RETURN n.id AS id")).contains(3);
  }

  @Test
  void theNearestProcedure() {
    final List<Result> rows = rows("CALL geo.nearest('Place[loc]', " + CENTRE + ", 5) YIELD node, distance "
        + "RETURN node.id AS id, distance", Map.of());
    assertThat(rows).hasSize(5);
    final List<Integer> expected = new ArrayList<>();
    try (final ResultSet rs = database.query("sql",
        "SELECT id FROM (SELECT expand(geo.nearest('Place[loc]', 'POINT (-95 40)', 5)))")) {
      while (rs.hasNext())
        expected.add(rs.next().getProperty("id"));
    }
    assertThat(rows.stream().map(r -> r.<Integer>getProperty("id")).toList()).isEqualTo(expected);
    for (int i = 1; i < rows.size(); i++)
      assertThat(rows.get(i).<Double>getProperty("distance")).isGreaterThanOrEqualTo(rows.get(i - 1).getProperty("distance"));

    // with options
    final List<Result> near = rows("CALL geo.nearest('Place[loc]', $c, 50, {maxDistanceMeters: 30000, where: 'id < 1000'}) "
        + "YIELD node, distance RETURN node.id AS id, distance", Map.of("c", "POINT (-95 40)"));
    for (final Result r : near) {
      assertThat(r.<Double>getProperty("distance")).isLessThanOrEqualTo(30000.0);
      assertThat(r.<Integer>getProperty("id")).isLessThan(1000);
    }
  }

  private Result single(final String cypher) {
    try (final ResultSet rs = database.query("opencypher", cypher)) {
      return rs.next();
    }
  }

  private String explain(final String cypher) {
    return explain(cypher, Map.of());
  }

  private String explain(final String cypher, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("opencypher", "EXPLAIN " + cypher, params)) {
      return rs.next().toJSON().toString();
    }
  }

  private Set<Integer> ids(final String cypher) {
    return ids(cypher, Map.of());
  }

  private Set<Integer> ids(final String cypher, final Map<String, Object> params) {
    final Set<Integer> out = new HashSet<>();
    for (final Result r : rows(cypher, params))
      out.add(r.getProperty("id"));
    return out;
  }

  private List<Result> rows(final String cypher, final Map<String, Object> params) {
    final List<Result> out = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", cypher, params)) {
      while (rs.hasNext())
        out.add(rs.next());
    }
    return out;
  }
}
