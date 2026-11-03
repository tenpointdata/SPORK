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
import org.locationtech.spatial4j.io.ShapeIO;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand parser of {@code POINT (x y)} answers what the WKT reader answers, and leaves every other spelling to it.
 */
class GeoUtilsPlainPointTest {

  @Test
  void readsWhatTheReaderReads() throws Exception {
    final Random random = new Random(3);
    for (int i = 0; i < 2000; i++) {
      final double x = random.nextDouble() * 360 - 180;
      final double y = random.nextDouble() * 180 - 90;
      for (final String wkt : new String[] { "POINT (" + x + " " + y + ")", "point(" + x + "  " + y + ")",
          String.format(Locale.ROOT, "Point ( %.4f\t%.4f )", x, y), "POINT (" + (int) x + " " + (int) y + ")" }) {
        final Point fast = GeoUtils.parsePlainPoint(wkt);
        final Shape reader = GeoUtils.getSpatialContext().getFormats().getReader(ShapeIO.WKT).read(wkt);
        assertThat(fast).as(wkt).isNotNull();
        assertThat(fast.getX()).as(wkt).isEqualTo(((Point) reader).getX());
        assertThat(fast.getY()).as(wkt).isEqualTo(((Point) reader).getY());
      }
    }
    assertThat(GeoUtils.parsePlainPoint("POINT (180 -90)").getX()).isEqualTo(180.0);
    assertThat(GeoUtils.parsePlainPoint("POINT (+1.5 -.5)").getY()).isEqualTo(-0.5);
  }

  @Test
  void leavesEveryOtherSpellingToTheReader() {
    for (final String wkt : new String[] { "POINT EMPTY", "POINT Z (1 2 3)", "POINT (1 2 3)", "POINT (1e3 2)", "POINT (190 10)",
        "POINT (10 95)", "POINT (1 2) x", "POINT (1, 2)", "POINT (- 2)", "POINT (. 2)", "POINT (NaN 2)",
        "LINESTRING (0 0, 1 1)", "POINT ((1 2))", "POINTS (1 2)" })
      assertThat(GeoUtils.parsePlainPoint(wkt)).as(wkt).isNull();
    // and through parseGeometry the reader still decides them: past the antimeridian is moved back, not refused
    assertThat(((Point) GeoUtils.parseGeometry("POINT (190 10)")).getX()).isEqualTo(-170.0);
  }
}
