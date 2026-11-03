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

import com.arcadedb.database.Identifiable;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.function.sql.SQLFunctionAbstract;
import com.arcadedb.query.sql.executor.CommandContext;
import org.locationtech.spatial4j.io.GeohashUtils;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL function geo.geohashCover: the GeoHash cells of one precision that a shape touches, optionally widened by a distance
 * in metres. With a cell column ({@code geo.geohash(coords, 3)}) leading a composite index, it turns a radius into the
 * equality keys of that index, so a radius-and-time-window query reads one index range per cell instead of every record
 * in the radius at any time:
 * <pre>
 * CREATE INDEX ON Sighting (cell3, day) NOTUNIQUE
 * SELECT FROM Sighting WHERE cell3 IN geo.geohashCover(:p, 3, 200000) AND day BETWEEN :from AND :to
 *   AND geo.distance(coords, :p) &lt;= 200000
 * </pre>
 * The cells are a superset of the area: the exact predicate stays in the WHERE. It is written with {@code geo.distance},
 * which no index answers, so the planner takes the composite index rather than a GEOSPATIAL one on the same property.
 *
 * <p>Usage: {@code geo.geohashCover(<shape>, <precision>[, <distanceMeters>])}</p>
 * <p>Returns: the list of GeoHash strings, sorted. Refuses an area of more than 100,000 cells: use a coarser precision</p>
 */
public class SQLFunctionGeoHashCover extends SQLFunctionAbstract {
  public static final String NAME = "geo.geohashCover";

  static final int MAX_CELLS = 100_000;

  public SQLFunctionGeoHashCover() {
    super(NAME);
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public Object execute(final Object iThis, final Identifiable iCurrentRecord, final Object iCurrentResult,
      final Object[] iParams, final CommandContext iContext) {
    if (iParams[0] == null)
      return null;
    final int precision = SQLFunctionGeoHash.precision(iParams[1], 0, NAME);
    if (precision == 0)
      throw new CommandExecutionException(NAME + "(): the precision is required");
    Shape shape;
    try {
      shape = GeoUtils.parseGeometry(iParams[0]);
    } catch (final IllegalArgumentException e) {
      throw new CommandExecutionException(NAME + "(): " + e.getMessage(), e);
    }
    if (iParams.length > 2 && iParams[2] != null)
      shape = GeoUtils.searchShapeWithin(shape, GeoUtils.metresToDegrees(GeoUtils.getDoubleValue(iParams[2])));
    return cover(shape, precision);
  }

  /** Every cell of the precision whose rectangle the shape touches, walking the cells of the shape's bounding box. */
  static List<String> cover(final Shape shape, final int precision) {
    final int bits = precision * 5;
    final double cellWidth = 360.0 / (1L << ((bits + 1) / 2));
    final double cellHeight = 180.0 / (1L << (bits / 2));

    final Rectangle box = shape.getBoundingBox();
    final int firstRow = row(box.getMinY(), cellHeight);
    final int lastRow = row(box.getMaxY(), cellHeight);
    final int columns = (int) Math.round(360.0 / cellWidth);
    final int firstColumn = column(box.getMinX(), cellWidth, columns);
    int lastColumn = column(box.getMaxX(), cellWidth, columns);
    // a box across the antimeridian wraps round: its columns run past the last one and start again from the first
    if (lastColumn < firstColumn)
      lastColumn += columns;
    if ((long) (lastRow - firstRow + 1) * (lastColumn - firstColumn + 1) > MAX_CELLS)
      throw new CommandExecutionException(
          NAME + "(): the area covers more than " + MAX_CELLS + " cells of precision " + precision + ", use a coarser precision");

    final List<String> cells = new ArrayList<>();
    for (int r = firstRow; r <= lastRow; r++) {
      final double lat = -90.0 + (r + 0.5) * cellHeight;
      for (int c = firstColumn; c <= lastColumn; c++) {
        final double lon = -180.0 + ((c % columns) + 0.5) * cellWidth;
        final String hash = GeohashUtils.encodeLatLon(lat, lon, precision);
        if (shape.relate(GeohashUtils.decodeBoundary(hash, GeoUtils.getSpatialContext())).intersects())
          cells.add(hash);
      }
    }
    cells.sort(null);
    return cells;
  }

  private static int row(final double lat, final double cellHeight) {
    return (int) Math.min(Math.floor((lat + 90.0) / cellHeight), Math.round(180.0 / cellHeight) - 1);
  }

  private static int column(final double lon, final double cellWidth, final int columns) {
    return (int) Math.min(Math.floor((lon + 180.0) / cellWidth), columns - 1);
  }

  @Override
  public String getSyntax() {
    return "geo.geohashCover(<shape>, <precision>[, <distanceMeters>])";
  }
}
