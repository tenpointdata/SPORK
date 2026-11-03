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
package com.arcadedb.benchmarks.retrieval;

import com.arcadedb.database.Database;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The schema of the synthetic Recon dataset: four identifier vertex types, the Record vertex type, four {@code HAS_*} edge
 * types, and the eleven indexes the query mix exercises. Types are created before the load, indexes after it (an index built
 * over existing records uses the sorted build, which is far cheaper than maintaining it row by row through a bulk load).
 */
public final class ReconSchema {
  public static final String RECORD     = "Record";
  public static final String PHONE      = "Phone";
  public static final String EMAIL      = "Email";
  public static final String HANDLE     = "Handle";
  public static final String IMAGE_HASH = "ImageHash";

  public static final String HAS_PHONE  = "HAS_PHONE";
  public static final String HAS_EMAIL  = "HAS_EMAIL";
  public static final String HAS_HANDLE = "HAS_HANDLE";
  public static final String HAS_IMAGE  = "HAS_IMAGE";

  public static final String[] IDENTIFIER_TYPES = { PHONE, EMAIL, HANDLE, IMAGE_HASH };
  public static final String[] EDGE_TYPES       = { HAS_PHONE, HAS_EMAIL, HAS_HANDLE, HAS_IMAGE };

  public static final String IDX_PHONE        = "Phone[key]";
  public static final String IDX_EMAIL        = "Email[key]";
  public static final String IDX_HANDLE       = "Handle[key]";
  public static final String IDX_IMAGE        = "ImageHash[key]";
  public static final String IDX_TS           = "Record[tsPosted]";
  public static final String IDX_STATE_TS     = "Record[state,tsPosted]";
  public static final String IDX_AGE_TS       = "Record[age,tsPosted]";
  public static final String IDX_SERVICES     = "Record[servicesbyitem]";
  public static final String IDX_TEXT         = "Record[text]";
  public static final String IDX_LOCATION     = "Record[location]";
  public static final String IDX_STYLE_VECTOR = "Record[styleVec]";

  public static final String[] ALL_INDEXES = { IDX_PHONE, IDX_EMAIL, IDX_HANDLE, IDX_IMAGE, IDX_TS, IDX_STATE_TS, IDX_AGE_TS,
      IDX_SERVICES, IDX_TEXT, IDX_LOCATION, IDX_STYLE_VECTOR };

  private ReconSchema() {
  }

  /** Vertex and edge types with declared properties; no index yet. */
  public static void createTypes(final Database database, final int recordBuckets) {
    database.transaction(() -> {
      final Schema schema = database.getSchema();
      for (final String identifier : IDENTIFIER_TYPES) {
        final VertexType type = schema.createVertexType(identifier, 1);
        type.createProperty("key", Type.STRING);
      }

      final VertexType record = schema.createVertexType(RECORD, recordBuckets);
      record.createProperty("recordId", Type.STRING);
      record.createProperty("source", Type.STRING);
      record.createProperty("tsPosted", Type.LONG);
      record.createProperty("tsCollected", Type.LONG);
      record.createProperty("city", Type.STRING);
      record.createProperty("state", Type.STRING);
      record.createProperty("location", Type.STRING); // WKT POINT (lon lat), null when the advert carries no place
      record.createProperty("age", Type.INTEGER);
      record.createProperty("views", Type.INTEGER);
      record.createProperty("services", Type.LIST, "STRING");
      record.createProperty("text", Type.STRING);
      record.createProperty("styleVec", Type.ARRAY_OF_FLOATS);

      for (final String edge : EDGE_TYPES)
        schema.buildEdgeType().withName(edge).withLightweight(true).create();
    });
  }

  /**
   * The eleven indexes, created over the loaded records. Returns the wall time each took, in creation order. The LSM ones
   * use the sorted build; the vector graph is built synchronously ({@code buildGraphNow} defaults to true).
   */
  public static Map<String, Long> createIndexes(final Database database) {
    final Map<String, Long> timings = new LinkedHashMap<>();
    final String sorted = " METADATA {\"buildMode\": \"SORTED\"}";
    for (final String identifier : IDENTIFIER_TYPES)
      timed(timings, identifier + "[key]", database, "CREATE INDEX ON " + identifier + " (key) UNIQUE" + sorted);
    timed(timings, IDX_TS, database, "CREATE INDEX ON Record (tsPosted) NOTUNIQUE" + sorted);
    timed(timings, IDX_STATE_TS, database, "CREATE INDEX ON Record (state, tsPosted) NOTUNIQUE" + sorted);
    timed(timings, IDX_AGE_TS, database, "CREATE INDEX ON Record (age, tsPosted) NOTUNIQUE" + sorted);
    timed(timings, IDX_SERVICES, database, "CREATE INDEX ON Record (services BY ITEM) NOTUNIQUE");
    timed(timings, IDX_TEXT, database, "CREATE INDEX ON Record (text) FULL_TEXT");
    timed(timings, IDX_LOCATION, database, "CREATE INDEX ON Record (location) GEOSPATIAL");
    timed(timings, IDX_STYLE_VECTOR, database, "CREATE INDEX ON Record (styleVec) LSM_VECTOR METADATA {\"dimensions\": "
        + ReconDomain.VECTOR_DIMENSIONS + ", \"similarity\": \"COSINE\"}");
    return timings;
  }

  /**
   * Loads (or builds) the vector graph of every bucket of the style index now, so a warm benchmark does not charge the first
   * kNN query with it. A cold benchmark deliberately skips this.
   */
  public static void warmVectorIndex(final Database database) {
    final Index index = database.getSchema().getIndexByName(IDX_STYLE_VECTOR);
    if (index instanceof TypeIndex typeIndex) {
      for (final IndexInternal sub : typeIndex.getIndexesOnBuckets())
        sub.warmUp();
    } else if (index instanceof IndexInternal internal)
      internal.warmUp();
  }

  private static void timed(final Map<String, Long> timings, final String name, final Database database, final String sql) {
    final long start = System.nanoTime();
    database.command("sql", sql).close();
    timings.put(name, (System.nanoTime() - start) / 1_000_000L);
  }
}
