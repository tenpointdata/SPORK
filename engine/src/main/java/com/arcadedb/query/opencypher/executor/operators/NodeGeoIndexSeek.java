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
package com.arcadedb.query.opencypher.executor.operators;

import com.arcadedb.database.Identifiable;
import com.arcadedb.engine.Bucket;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.function.sql.geo.SQLFunctionGeoPredicate;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.geospatial.LSMTreeGeoIndex;
import com.arcadedb.query.opencypher.optimizer.GeoSeekPredicate;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.VertexType;
import com.arcadedb.utility.IntHashSet;
import org.locationtech.spatial4j.shape.Shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Physical operator that finds the vertices of a label whose WKT property lies in the circle of a
 * {@code point.distance(n.p, c) < r} predicate, or in the box of a {@code point.withinBBox(n.p, ll, ur)} one, from the
 * GEOSPATIAL index on the property: the records of the GeoHash cells covering the circle or the box, one cell scan at a
 * time. A superset: the predicate stays in the WHERE above this operator and checks each vertex exactly.
 * <p>
 * The circle and the box are worked out when the operator runs, so a parameter of the predicate is read per execution.
 */
public class NodeGeoIndexSeek extends AbstractPhysicalOperator {
  private final String           variable;
  private final String           label;
  private final GeoSeekPredicate predicate;
  private final String           indexName;

  public NodeGeoIndexSeek(final String variable, final String label, final GeoSeekPredicate predicate, final String indexName,
      final double estimatedCost, final long estimatedCardinality) {
    super(estimatedCost, estimatedCardinality);
    this.variable = variable;
    this.label = label;
    this.predicate = predicate;
    this.indexName = indexName;
  }

  @Override
  public ResultSet execute(final CommandContext context, final int nRecords) {
    // Bounds this operator's row loop by the command deadline (issue #6266)
    final WorkGuard guard = WorkGuard.forCommandDeadline(context);
    return new ResultSet() {
      private List<LSMTreeGeoIndex> bucketIndexes;
      private Shape                 searchShape;
      private int                   nextBucket;
      private IndexCursor           cursor;
      private final List<Result>    buffer      = new ArrayList<>();
      private int                   bufferIndex = 0;
      private boolean               initialized = false;
      private boolean               finished    = false;

      @Override
      public boolean hasNext() {
        if (bufferIndex < buffer.size())
          return true;
        if (finished)
          return false;
        fetchMore(nRecords > 0 ? nRecords : 100);
        return bufferIndex < buffer.size();
      }

      @Override
      public Result next() {
        if (!hasNext())
          throw new NoSuchElementException();
        return buffer.get(bufferIndex++);
      }

      private void initialize() {
        initialized = true;

        final DocumentType type = context.getDatabase().getSchema().getType(label);
        // A non-vertex type with the same name (edge/document type) matches no node pattern (issue #5194)
        if (!(type instanceof VertexType)) {
          finished = true;
          return;
        }

        final TypeIndex index = SQLFunctionGeoPredicate.resolveGeoIndex(type, predicate.getPropertyName());
        if (index == null)
          // The planner picked an index that the schema no longer offers: an empty result would silently drop rows
          throw new CommandExecutionException(
              "Index '" + indexName + "' on type '" + label + "' is no longer available: re-plan the query");

        searchShape = predicate.searchShape(context);
        if (searchShape == null) {
          // the predicate is true for no row (a null radius, a cartesian centre...)
          finished = true;
          return;
        }

        // An index declared on a supertype spans every bucket of the hierarchy: only the buckets of the label (and of its
        // subtypes, which a label matches too) are read, so every vertex found carries the label (#7021)
        final IntHashSet bucketIds = new IntHashSet();
        for (final Bucket bucket : type.getBuckets(true))
          bucketIds.add(bucket.getFileId());
        bucketIndexes = new ArrayList<>();
        for (final IndexInternal bucketIndex : index.getIndexesOnBuckets())
          if (bucketIndex instanceof LSMTreeGeoIndex geoIndex && bucketIds.contains(geoIndex.getAssociatedBucketId()))
            bucketIndexes.add(geoIndex);
      }

      private void fetchMore(final int n) {
        buffer.clear();
        bufferIndex = 0;

        if (!initialized) {
          initialize();
          if (finished)
            return;
        }

        while (buffer.size() < n) {
          guard.check();
          if (cursor == null || !cursor.hasNext()) {
            if (cursor != null) {
              cursor.close();
              cursor = null;
            }
            if (nextBucket >= bucketIndexes.size()) {
              finished = true;
              return;
            }
            // lazy: one covering cell scan at a time (#5601), deduplicated across the cells of the shape by the cursor
            cursor = bucketIndexes.get(nextBucket++).get(new Object[] { searchShape });
            continue;
          }

          final Identifiable identifiable = cursor.next();
          final Vertex vertex;
          try {
            vertex = identifiable.asVertex();
          } catch (final RecordNotFoundException e) {
            // deleted by a commit that overlapped the scan
            continue;
          }

          final ResultInternal result = new ResultInternal();
          result.setProperty(variable, vertex);
          buffer.add(result);
        }
      }

      @Override
      public void close() {
        // a compacted-series cursor registers with its file and must be released, even on a LIMIT (#5635)
        if (cursor != null) {
          cursor.close();
          cursor = null;
        }
      }
    };
  }

  @Override
  public String getOperatorType() {
    return "NodeGeoIndexSeek";
  }

  @Override
  public String explain(final int depth) {
    return getIndent(depth) + "+ NodeGeoIndexSeek(" + variable + ":" + label + ") [index=" + indexName + ", "
        + predicate.getText() + ", cost=" + String.format(Locale.US, "%.2f", estimatedCost) + ", rows=" + estimatedCardinality
        + "]\n";
  }

  public String getVariable() {
    return variable;
  }

  public String getLabel() {
    return label;
  }

  public GeoSeekPredicate getPredicate() {
    return predicate;
  }

  public String getIndexName() {
    return indexName;
  }
}
