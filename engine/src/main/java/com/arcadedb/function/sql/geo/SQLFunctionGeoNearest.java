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

import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.engine.Bucket;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.function.sql.FunctionOptions;
import com.arcadedb.function.sql.SQLFunctionAbstract;
import com.arcadedb.function.sql.vector.RecordPredicateRidSet;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.geospatial.GeoNearestSearch;
import com.arcadedb.index.geospatial.LSMTreeGeoIndex;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.utility.IntHashSet;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL function geo.nearest: the {@code k} records nearest to a point, from the GEOSPATIAL index of a property, nearest
 * first, each with its great-circle distance in metres. Exact (not approximate): the index cells are walked outward from
 * the point and the walk stops as soon as no cell left can hold anything nearer than the k-th record found, so the type is
 * never scanned (see {@link GeoNearestSearch}).
 *
 * <p>Usage: {@code geo.nearest(<'Type[property]' | indexName>, <point>, <k>[, {maxDistanceMeters: <m>, where: <filter>}])}</p>
 * <ul>
 *   <li>{@code point}: a WKT point, a {@code geo.point()} or a Cypher point map</li>
 *   <li>{@code maxDistanceMeters}: no record farther than this is answered, so fewer than {@code k} rows may come back</li>
 *   <li>{@code where}: a map of property to value, or a SQL condition with named parameters, as {@code vector.neighbors}
 *   takes it: the walk skips a record that does not satisfy it and goes on until it has {@code k} that do</li>
 * </ul>
 * <p>Returns: a list of rows, each carrying the record's properties, {@code @rid}, {@code @type} and {@code distance}
 * (metres), to use with {@code expand()}: {@code SELECT expand(geo.nearest('Sighting[location]', 'POINT (-96.8 32.8)', 10))}.</p>
 */
public class SQLFunctionGeoNearest extends SQLFunctionAbstract {
  public static final String NAME = "geo.nearest";

  private static final Set<String> OPTIONS = Set.of("maxDistanceMeters", "where");

  public SQLFunctionGeoNearest() {
    super(NAME);
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
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    if (params[0] == null)
      throw new CommandExecutionException(NAME + "(): the index is null");

    final int k = parseK(params[2], NAME);
    double maxDistanceMeters = Double.POSITIVE_INFINITY;
    Object where = null;
    if (params.length > 3 && params[3] != null) {
      if (!(params[3] instanceof Map<?, ?> map))
        throw new CommandExecutionException(
            NAME + "(): options must be a map such as {maxDistanceMeters: 25000}, got " + params[3].getClass().getSimpleName());
      final FunctionOptions options = new FunctionOptions(NAME, map, OPTIONS);
      maxDistanceMeters = parseMaxDistance(options.get("maxDistanceMeters"), NAME);
      where = options.get("where");
    }

    final List<GeoNearestSearch.Hit> hits = search(params[0].toString(), params[1], k, maxDistanceMeters, where, context, NAME);
    final List<Map<String, Object>> rows = new ArrayList<>(hits.size());
    for (final GeoNearestSearch.Hit hit : hits)
      rows.add(toRow(hit));
    return rows;
  }

  /**
   * The search behind {@code geo.nearest} and the Cypher procedure of the same name.
   *
   * @param indexSpec the index, as {@code 'Type[property]'} or as its schema name
   * @param point     the point to measure from
   * @param where     the {@code where} option, or null
   * @param caller    the entry point, for the error messages
   */
  public static List<GeoNearestSearch.Hit> search(final String indexSpec, final Object point, final int k,
      final double maxDistanceMeters, final Object where, final CommandContext context, final String caller) {
    final Schema schema = context.getDatabase().getSchema();

    final DocumentType type;
    final String propertyName;
    final TypeIndex typeIndex;
    final int bracket = indexSpec.indexOf('[');
    if (bracket > 0 && indexSpec.endsWith("]")) {
      final String typeName = indexSpec.substring(0, bracket);
      if (!schema.existsType(typeName))
        throw new CommandExecutionException(caller + "(): type '" + typeName + "' does not exist");
      type = schema.getType(typeName);
      propertyName = indexSpec.substring(bracket + 1, indexSpec.length() - 1);
      typeIndex = SQLFunctionGeoPredicate.resolveGeoIndex(type, propertyName);
      if (typeIndex == null)
        throw new CommandExecutionException(caller + "(): no usable GEOSPATIAL index on " + indexSpec
            + " (an index built before 26.11.1 must be rebuilt first)");
    } else {
      final Index index = schema.existsIndex(indexSpec) ? schema.getIndexByName(indexSpec) : null;
      if (!(index instanceof TypeIndex named) || named.getType() != Schema.INDEX_TYPE.GEOSPATIAL)
        throw new CommandExecutionException(caller + "(): '" + indexSpec + "' is not a GEOSPATIAL index");
      type = schema.getType(named.getTypeName());
      propertyName = named.getPropertyNames().getFirst();
      typeIndex = SQLFunctionGeoPredicate.resolveGeoIndex(type, propertyName);
      if (typeIndex == null)
        throw new CommandExecutionException(caller + "(): the GEOSPATIAL index '" + indexSpec
            + "' was built before 26.11.1 and must be rebuilt first");
    }

    final Shape shape;
    try {
      shape = GeoUtils.parseGeometry(point);
    } catch (final IllegalArgumentException e) {
      throw new CommandExecutionException(caller + "(): " + e.getMessage(), e);
    }
    if (!(shape instanceof Point origin))
      throw new CommandExecutionException(caller + "(): the origin must be a point, got " + point);

    // An index declared on a supertype spans every bucket of the hierarchy: only the buckets of the type asked for (and of
    // its subtypes) are searched
    final IntHashSet bucketIds = new IntHashSet();
    for (final Bucket bucket : type.getBuckets(true))
      bucketIds.add(bucket.getFileId());
    final List<LSMTreeGeoIndex> indexes = new ArrayList<>();
    for (final IndexInternal bucketIndex : typeIndex.getIndexesOnBuckets())
      if (bucketIndex instanceof LSMTreeGeoIndex geoIndex && bucketIds.contains(geoIndex.getAssociatedBucketId()))
        indexes.add(geoIndex);

    final Set<RID> allowed = RecordPredicateRidSet.of(where, null, caller, context);
    final double ox = origin.getX();
    final double oy = origin.getY();
    final double maxRadians = maxDistanceMeters / (GeoUtils.EARTH_RADIUS_KM * 1000.0);

    return GeoNearestSearch.search(indexes, ox, oy, k, maxRadians, rid -> {
      if (allowed != null && !allowed.contains(rid))
        return null;
      final Document record;
      try {
        record = rid.asDocument();
      } catch (final RecordNotFoundException e) {
        // deleted by a commit that overlapped the walk
        return null;
      }
      final Shape value;
      try {
        value = GeoUtils.parseGeometry(record.get(propertyName));
      } catch (final IllegalArgumentException e) {
        return null;
      }
      if (value == null)
        return null;
      final double radians = value instanceof Point p ?
          GeoUtils.haversineRadians(ox, oy, p.getX(), p.getY()) :
          Math.toRadians(GeoUtils.distanceDegrees(origin, value));
      return new GeoNearestSearch.Hit(record, radians);
    }, WorkGuard.forCommandDeadline(context));
  }

  /** The record's properties, then {@code @rid}, {@code @type} and {@code distance}, which win over a property of the same name. */
  private static Map<String, Object> toRow(final GeoNearestSearch.Hit hit) {
    final Document record = hit.record();
    final Map<String, Object> row = new LinkedHashMap<>(record.toMap(false));
    row.put("@rid", record.getIdentity());
    row.put("@type", record.getTypeName());
    row.put("distance", GeoUtils.radiansToMetres(hit.distanceRadians()));
    return row;
  }

  public static int parseK(final Object value, final String caller) {
    if (!(value instanceof Number number) || number.doubleValue() != Math.floor(number.doubleValue()) || number.longValue() < 0
        || number.longValue() > Integer.MAX_VALUE)
      throw new CommandExecutionException(caller + "(): k must be a non-negative integer, got " + value);
    return number.intValue();
  }

  public static double parseMaxDistance(final Object value, final String caller) {
    if (value == null)
      return Double.POSITIVE_INFINITY;
    if (!(value instanceof Number number) || !(number.doubleValue() >= 0))
      throw new CommandExecutionException(caller + "(): maxDistanceMeters must be a non-negative number, got " + value);
    return number.doubleValue();
  }

  @Override
  public String getSyntax() {
    return "geo.nearest(<'Type[property]' | indexName>, <point>, <k>[, {maxDistanceMeters: <m>, where: <filter>}])";
  }
}
