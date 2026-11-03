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
package com.arcadedb.query.opencypher.procedures.geo;

import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.function.sql.FunctionOptions;
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.function.sql.geo.SQLFunctionGeoNearest;
import com.arcadedb.index.geospatial.GeoNearestSearch;
import com.arcadedb.query.opencypher.procedures.CypherProcedure;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code geo.nearest(index, point, k[, {maxDistanceMeters, where}]) YIELD node, distance}: the {@code k} nodes nearest to a
 * point, from the GEOSPATIAL index of a WKT property, nearest first, with their great-circle distance in metres. The
 * Cypher face of the SQL function {@code geo.nearest}: exact, and answered by walking the index cells outward from the
 * point, never by scanning the label.
 * <pre>
 * CALL geo.nearest('Sighting[location]', point({longitude: -96.8, latitude: 32.8}), 10, {maxDistanceMeters: 25000})
 * YIELD node, distance
 * RETURN node.phone AS phone, distance
 * </pre>
 * The index is {@code 'Type[property]'} or the index name; the point a Cypher point, a WKT point or a {@code geo.point()};
 * {@code where} a map of property to value or a SQL condition with named parameters, tested as the walk meets each
 * candidate, so {@code k} matching nodes come back whenever the index holds them.
 */
public class GeoNearest implements CypherProcedure {
  public static final String NAME = "geo.nearest";

  private static final Set<String> OPTIONS = Set.of("maxDistanceMeters", "where");

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 3;
  }

  @Override
  public int getMaxArgs() {
    return 4;
  }

  @Override
  public String getDescription() {
    return "Returns the k nodes nearest to a point from a GEOSPATIAL index, nearest first, with their distance in metres; "
        + "options {maxDistanceMeters: <m>, where: <map or condition>}";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("node", "distance");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    if (args[0] == null)
      throw new CommandExecutionException(NAME + "(): the index is null");

    final int k = SQLFunctionGeoNearest.parseK(args[2], NAME);
    double maxDistanceMeters = Double.POSITIVE_INFINITY;
    Object where = null;
    if (args.length > 3 && args[3] != null) {
      if (!(args[3] instanceof Map<?, ?> map))
        throw new CommandExecutionException(
            NAME + "(): options must be a map such as {maxDistanceMeters: 25000}, got " + args[3].getClass().getSimpleName());
      final FunctionOptions options = new FunctionOptions(NAME, map, OPTIONS);
      maxDistanceMeters = SQLFunctionGeoNearest.parseMaxDistance(options.get("maxDistanceMeters"), NAME);
      where = options.get("where");
    }

    final List<GeoNearestSearch.Hit> hits = SQLFunctionGeoNearest.search(args[0].toString(), args[1], k, maxDistanceMeters,
        where, context, NAME);
    final List<Result> rows = new ArrayList<>(hits.size());
    for (final GeoNearestSearch.Hit hit : hits) {
      final ResultInternal row = new ResultInternal();
      row.setProperty("node", hit.record());
      row.setProperty("distance", GeoUtils.radiansToMetres(hit.distanceRadians()));
      rows.add(row);
    }
    return rows.stream();
  }
}
