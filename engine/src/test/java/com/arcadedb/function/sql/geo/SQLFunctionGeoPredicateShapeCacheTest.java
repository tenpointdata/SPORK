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

import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.shape.Shape;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval speed S12: the search shape of a geo.* predicate is a constant of the statement, and was parsed from its WKT for
 * every row the predicate re-checked. The function keeps the last shape parsed from each argument and answers it for an equal
 * value, so a row costs the parse of its own geometry only.
 */
class SQLFunctionGeoPredicateShapeCacheTest {

  @Test
  void anEqualValueIsAnsweredWithTheShapeParsedLastTime() {
    final SQLFunctionGeoIntersects function = new SQLFunctionGeoIntersects();
    final String wkt = "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))";
    final Shape first = function.parseShape(wkt);
    // A DIFFERENT STRING INSTANCE WITH THE SAME TEXT, AS A PARAMETER BOUND AGAIN WOULD BE
    final Shape second = function.parseShape(new String(wkt));
    assertThat(second).isSameAs(first);

    // ANOTHER VALUE IS PARSED, AND REPLACES IT
    final Shape other = function.parseShape("POINT(1 1)");
    assertThat(other).isNotSameAs(first);
    assertThat(function.parseShape("POINT(1 1)")).isSameAs(other);
    assertThat(function.parseShape(wkt)).isNotSameAs(first);

    // A SHAPE IS ANSWERED AS IS, A CYPHER POINT MAP IS CACHED BY ITS EQUALITY
    final Shape search = function.parseShape(wkt);
    assertThat(function.parseShape(search)).isSameAs(search);
    final Shape point = function.parseShape(Map.of("x", 1.0, "y", 2.0));
    assertThat(function.parseShape(Map.of("x", 1.0, "y", 2.0))).isSameAs(point);
    assertThat(function.parseShape(null)).isNull();
  }

  /**
   * Review finding F7: the cache used to remember the caller's map itself, so a map changed in place after the first parse was
   * found equal to the remembered one (it IS the remembered one) and answered with the shape of what it held before.
   */
  @Test
  void aPointMapChangedInPlaceIsParsedAgain() {
    final SQLFunctionGeoIntersects function = new SQLFunctionGeoIntersects();
    final Map<String, Object> point = new HashMap<>();
    point.put("x", 1.0);
    point.put("y", 2.0);
    final Shape before = function.parseShape(point);
    point.put("x", 50.0);
    final Shape after = function.parseShape(point);
    assertThat(after).isNotSameAs(before);
    assertThat(after.getCenter().getX()).isEqualTo(50.0);
  }

  /**
   * Review finding F7: the record's own geometry, the first argument, changes on every row, so remembering it only cost a volatile
   * store and an allocation per row on a function instance shared by every execution. It is parsed without the cache, and the
   * search shape stays cached across rows.
   */
  @Test
  void theRecordGeometryDoesNotGoThroughTheCache() {
    final SQLFunctionGeoIntersects function = new SQLFunctionGeoIntersects();
    final String polygon = "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))";
    final Shape search = function.parseShape(polygon);
    for (int row = 0; row < 3; row++)
      assertThat((Boolean) function.execute(null, null, null, new Object[] { "POINT(" + row + " " + row + ")", polygon }, null)).isTrue();
    assertThat(function.parseShape(polygon)).isSameAs(search);
  }

  @Test
  void thePredicateStillAnswersWhatItAnsweredBefore() {
    final SQLFunctionGeoIntersects function = new SQLFunctionGeoIntersects();
    final Object[] params = { "POINT(5 5)", "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))" };
    assertThat((Boolean) function.execute(null, null, null, params, null)).isTrue();
    assertThat((Boolean) function.execute(null, null, null, params, null)).isTrue();
    params[0] = "POINT(50 50)";
    assertThat((Boolean) function.execute(null, null, null, params, null)).isFalse();
    assertThat(function.execute(null, null, null, new Object[] { null, params[1] }, null)).isNull();
  }
}
