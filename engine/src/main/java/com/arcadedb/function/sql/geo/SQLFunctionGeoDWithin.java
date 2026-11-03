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
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.parser.Expression;
import org.locationtech.spatial4j.shape.Shape;

/**
 * SQL function geo.dWithin: returns true if geometry g is within the given distance of shape.
 *
 * <p><b>Distance unit: degrees of great-circle arc</b> (Spatial4j native unit).
 * This is different from {@code geo.distance()}, which returns metres by default.
 * Approximate conversion: 1 degree ≈ 111.32 km at the equator.
 * Example: to test "within 10 km", pass {@code 10.0 / 111.32 ≈ 0.0898}, or use {@code geo.dWithinMeters}.</p>
 *
 * <p>Answered from a GEOSPATIAL index on the first argument when there is one and the distance is known before any record
 * is read: the index is asked for the cells within the distance of the shape.</p>
 *
 * <p>Usage: {@code geo.dWithin(g, shape, distanceDegrees)}</p>
 * <p>Returns: Boolean</p>
 */
public class SQLFunctionGeoDWithin extends SQLFunctionGeoPredicate {
  public static final String NAME = "geo.dWithin";

  public SQLFunctionGeoDWithin() {
    super(NAME);
  }

  protected SQLFunctionGeoDWithin(final String name) {
    super(name);
  }

  @Override
  public int getMinArgs() {
    return 3;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  /**
   * The distance is needed before any record is read, to widen the search shape the index is asked for: a distance read
   * off each record leaves the predicate to the scan.
   */
  @Override
  protected boolean canResolveIndexSearchShape(final Expression[] oExpressions, final CommandContext context) {
    return Double.isFinite(resolveDistance(oExpressions, context));
  }

  /**
   * geo.dWithin compares the great-circle distance between the two geometries (0 when they intersect) with the radius, so
   * the index is asked for the cells within that radius of the search shape rather than the cells of the shape itself:
   * every record within the distance has a cell there, and the predicate checks the candidates exactly.
   */
  @Override
  protected Shape indexSearchShape(final Shape shape, final Expression[] oExpressions, final CommandContext context) {
    return GeoUtils.searchShapeWithin(shape, toDegrees(resolveDistance(oExpressions, context)));
  }

  @Override
  protected Boolean evaluate(final Shape geom1, final Shape geom2, final Object[] params) {
    if (params.length < 3 || params[2] == null)
      return null;
    final double distance = toDegrees(GeoUtils.getDoubleValue(params[2]));
    // the distance between the shapes, not between their centres (issue #10210)
    return GeoUtils.distanceDegrees(geom1, geom2) <= distance;
  }

  /** The distance argument, in the unit of this function, as degrees of arc. */
  protected double toDegrees(final double distance) {
    return distance;
  }

  /** The distance argument evaluated without a record, or NaN when it is not a number on its own. */
  private static double resolveDistance(final Expression[] oExpressions, final CommandContext context) {
    if (oExpressions == null || oExpressions.length < 3 || oExpressions[2] == null)
      return Double.NaN;
    final Object value;
    try {
      value = oExpressions[2].execute((Identifiable) null, context);
    } catch (final RuntimeException e) {
      // it reads the record, which there is none of yet
      return Double.NaN;
    }
    return value instanceof Number number ? number.doubleValue() : Double.NaN;
  }

  @Override
  public String getSyntax() {
    return "geo.dWithin(<geometry>, <shape>, <distanceDegrees>)";
  }
}
