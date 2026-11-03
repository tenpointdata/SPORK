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
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

/**
 * SQL function geo.geohash: the GeoHash cell of a point at a precision (1 to 12 characters), the key of the cell column a
 * composite index pairs with another one, such as a day, for a radius-and-time-window query (see {@code geo.geohashCover}).
 *
 * <p>Usage: {@code geo.geohash(<point>[, <precision>])}, precision 12 by default</p>
 * <p>Returns: the GeoHash string, or null when the value is null or not a point</p>
 */
public class SQLFunctionGeoHash extends SQLFunctionAbstract {
  public static final String NAME = "geo.geohash";

  public SQLFunctionGeoHash() {
    super(NAME);
  }

  @Override
  public int getMinArgs() {
    return 1;
  }

  @Override
  public int getMaxArgs() {
    return 2;
  }

  @Override
  public Object execute(final Object iThis, final Identifiable iCurrentRecord, final Object iCurrentResult,
      final Object[] iParams, final CommandContext iContext) {
    if (iParams[0] == null)
      return null;
    final int precision = precision(iParams.length > 1 ? iParams[1] : null, 12, NAME);
    final Shape shape;
    try {
      shape = GeoUtils.parseGeometry(iParams[0]);
    } catch (final IllegalArgumentException e) {
      return null;
    }
    if (!(shape instanceof Point point))
      return null;
    return GeohashUtils.encodeLatLon(point.getY(), point.getX(), precision);
  }

  /** A GeoHash precision argument, 1 to 12 characters. */
  static int precision(final Object value, final int defaultValue, final String caller) {
    if (value == null)
      return defaultValue;
    if (!(value instanceof Number number) || number.intValue() < 1 || number.intValue() > 12
        || number.doubleValue() != number.intValue())
      throw new CommandExecutionException(caller + "(): the precision must be an integer from 1 to 12, got " + value);
    return number.intValue();
  }

  @Override
  public String getSyntax() {
    return "geo.geohash(<point>[, <precision>])";
  }
}
