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
package com.arcadedb.query.opencypher.optimizer;

import com.arcadedb.function.geo.CypherPointFunction;
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.query.opencypher.ast.ArithmeticExpression;
import com.arcadedb.query.opencypher.ast.BooleanCoercionExpression;
import com.arcadedb.query.opencypher.ast.BooleanExpression;
import com.arcadedb.query.opencypher.ast.ComparisonExpression;
import com.arcadedb.query.opencypher.ast.Expression;
import com.arcadedb.query.opencypher.ast.FunctionCallExpression;
import com.arcadedb.query.opencypher.ast.ListExpression;
import com.arcadedb.query.opencypher.ast.LiteralExpression;
import com.arcadedb.query.opencypher.ast.LogicalExpression;
import com.arcadedb.query.opencypher.ast.MapExpression;
import com.arcadedb.query.opencypher.ast.ParameterExpression;
import com.arcadedb.query.opencypher.ast.PropertyAccessExpression;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.ResultInternal;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

import java.util.List;
import java.util.Map;

/**
 * A WHERE conjunct a GEOSPATIAL index on a node property can answer, on a property holding WKT (the type such an index is
 * defined on):
 * <ul>
 *   <li>{@code point.distance(n.prop, <centre>) < <metres>} - also {@code <=}, the operands of either the call or the
 *   comparison swapped ({@code $r > point.distance($c, n.prop)});</li>
 *   <li>{@code point.withinBBox(n.prop, <lowerLeft>, <upperRight>)}.</li>
 * </ul>
 * The centre, corners and radius must be known before any row is read: literals, parameters, maps and lists of them,
 * {@code point()} calls and arithmetic over them. The index answers a superset (the cells covering the circle or the box),
 * so the conjunct stays in the WHERE and checks each candidate exactly; only conjuncts of a top-level AND are taken, since a
 * disjunct does not restrict the rows on its own.
 */
public final class GeoSeekPredicate {
  public enum Kind {DISTANCE, BBOX}

  private static final String DISTANCE_FUNCTION = "point.distance";
  private static final String BBOX_FUNCTION     = "point.withinbbox";

  private final String     propertyName;
  private final Kind       kind;
  /** The centre of a DISTANCE predicate, the lower-left corner of a BBOX one. */
  private final Expression first;
  /** The radius of a DISTANCE predicate, the upper-right corner of a BBOX one. */
  private final Expression second;
  private final String     text;

  private GeoSeekPredicate(final String propertyName, final Kind kind, final Expression first, final Expression second,
      final String text) {
    this.propertyName = propertyName;
    this.kind = kind;
    this.first = first;
    this.second = second;
    this.text = text;
  }

  /**
   * The geo predicates on {@code variable} among the top-level conjuncts of {@code expression}, in the order they are written.
   */
  public static void extract(final String variable, final BooleanExpression expression, final List<GeoSeekPredicate> into) {
    if (expression instanceof LogicalExpression logical) {
      if (logical.getOperator() == LogicalExpression.Operator.AND) {
        extract(variable, logical.getLeft(), into);
        if (logical.getRight() != null)
          extract(variable, logical.getRight(), into);
      }
      return;
    }

    if (expression instanceof ComparisonExpression comparison) {
      final GeoSeekPredicate distance = fromComparison(variable, comparison);
      if (distance != null)
        into.add(distance);
      return;
    }

    if (expression instanceof BooleanCoercionExpression coercion
        && coercion.getExpression() instanceof FunctionCallExpression call
        && BBOX_FUNCTION.equalsIgnoreCase(call.getFunctionName()) && call.getArguments().size() == 3) {
      final List<Expression> args = call.getArguments();
      final String property = propertyOf(variable, args.get(0));
      if (property != null && isStatic(args.get(1)) && isStatic(args.get(2)))
        into.add(new GeoSeekPredicate(property, Kind.BBOX, args.get(1), args.get(2), expression.getText()));
    }
  }

  private static GeoSeekPredicate fromComparison(final String variable, final ComparisonExpression comparison) {
    final ComparisonExpression.Operator operator = comparison.getOperator();
    final Expression call;
    final Expression radius;
    if (operator == ComparisonExpression.Operator.LESS_THAN || operator == ComparisonExpression.Operator.LESS_THAN_OR_EQUAL) {
      call = comparison.getLeft();
      radius = comparison.getRight();
    } else if (operator == ComparisonExpression.Operator.GREATER_THAN
        || operator == ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL) {
      call = comparison.getRight();
      radius = comparison.getLeft();
    } else
      return null;

    if (!(call instanceof FunctionCallExpression function) || !DISTANCE_FUNCTION.equalsIgnoreCase(function.getFunctionName())
        || function.getArguments().size() != 2 || !isStatic(radius))
      return null;

    final List<Expression> args = function.getArguments();
    for (int i = 0; i < 2; i++) {
      final String property = propertyOf(variable, args.get(i));
      if (property != null && isStatic(args.get(1 - i)))
        return new GeoSeekPredicate(property, Kind.DISTANCE, args.get(1 - i), radius, comparison.getText());
    }
    return null;
  }

  private static String propertyOf(final String variable, final Expression expression) {
    return expression instanceof PropertyAccessExpression access && variable.equals(access.getVariableName()) ?
        access.getPropertyName() :
        null;
  }

  /** Whether the expression has the same value on every row: it reads no variable and no non-deterministic function. */
  static boolean isStatic(final Expression expression) {
    if (expression instanceof LiteralExpression || expression instanceof ParameterExpression)
      return true;
    if (expression instanceof MapExpression map) {
      for (final Expression value : map.getEntries().values())
        if (!isStatic(value))
          return false;
      return true;
    }
    if (expression instanceof ListExpression list) {
      for (final Expression element : list.getElements())
        if (!isStatic(element))
          return false;
      return true;
    }
    if (expression instanceof ArithmeticExpression arithmetic)
      return isStatic(arithmetic.getLeft()) && (arithmetic.getRight() == null || isStatic(arithmetic.getRight()));
    if (expression instanceof FunctionCallExpression call && "point".equalsIgnoreCase(call.getFunctionName())) {
      for (final Expression argument : call.getArguments())
        if (!isStatic(argument))
          return false;
      return true;
    }
    return false;
  }

  /**
   * The shape to ask the index for, evaluated once per execution: the circle of the radius around the centre, or the box.
   * Null when the predicate is true for no row: a null, non-numeric or cartesian operand makes {@code point.distance} and
   * {@code point.withinBBox} answer null against a WKT (geographic) property, and so does a box whose bottom is above its
   * top.
   */
  public Shape searchShape(final CommandContext context) {
    try {
      return resolveSearchShape(context);
    } catch (final RuntimeException e) {
      // an operand Spatial4j refuses (a coordinate off the globe): the predicate decides every row, the index cannot narrow
      // them
      return GeoUtils.getSpatialContext().getWorldBounds();
    }
  }

  private Shape resolveSearchShape(final CommandContext context) {
    final ResultInternal noRow = new ResultInternal();
    final Point a = geographicPoint(first.evaluate(noRow, context));
    if (a == null)
      return null;

    if (kind == Kind.DISTANCE) {
      if (!(second.evaluate(noRow, context) instanceof Number metres))
        return null;
      return GeoUtils.searchShapeWithin(a, GeoUtils.metresToDegrees(metres.doubleValue()));
    }

    final Point b = geographicPoint(second.evaluate(noRow, context));
    if (b == null || a.getY() > b.getY())
      return null;
    // a lower-left longitude past the upper-right one is a box across the antimeridian, as Spatial4j reads it too
    return GeoUtils.getSpatialContext().getShapeFactory().rect(a.getX(), b.getX(), a.getY(), b.getY());
  }

  /** A WGS-84 point from a point map or WKT, or null for any other value, a cartesian point included. */
  private static Point geographicPoint(final Object value) {
    if (!(CypherPointFunction.wgs84PointOf(value) instanceof Map<?, ?> map))
      return null;
    final Object crs = map.get("crs");
    final boolean geographic;
    if (crs instanceof String name && (CypherPointFunction.isGeographicCrs(name) || CypherPointFunction.isCartesianCrs(name)))
      geographic = CypherPointFunction.isGeographicCrs(name);
    else
      geographic = map.containsKey("longitude") && map.containsKey("latitude");
    if (!geographic)
      return null;
    final Object lon = map.get("longitude") != null ? map.get("longitude") : map.get("x");
    final Object lat = map.get("latitude") != null ? map.get("latitude") : map.get("y");
    if (!(lon instanceof Number x) || !(lat instanceof Number y))
      return null;
    return GeoUtils.getSpatialContext().getShapeFactory().pointXY(x.doubleValue(), y.doubleValue());
  }

  public String getPropertyName() {
    return propertyName;
  }

  public Kind getKind() {
    return kind;
  }

  /** The predicate as written, for EXPLAIN. */
  public String getText() {
    return text;
  }

  @Override
  public String toString() {
    return text;
  }
}
