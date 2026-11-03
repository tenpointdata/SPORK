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
package com.arcadedb.query.sql.executor;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projection of N plain properties walks the record header once per row instead of once per property (SPORK retrieval
 * speed, item S4): the rows of {@code ProjectionCalculationStep} are read through a {@code PropertyCachingResult} when
 * no expression of the projection can hand the row out, and {@code SELECT *} reads the record in one pass. The rows must be
 * exactly the rows the per-property path answers, which {@code arcadedb.queryProjectionPropertyCache=false} still runs.
 */
public class ProjectionOneHeaderPassTest extends TestHelper {
  private static final int ROWS = 200;

  @Override
  protected void beginTest() {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType("Wide");
      type.createProperty("key", Type.LONG);
      type.createProperty("name", Type.STRING);
      type.createProperty("born", Type.DATE);
      type.createProperty("tags", Type.LIST);
      type.createProperty("meta", Type.MAP);
      type.createProperty("link", Type.LINK);
      database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "Wide", "key");

      RID previous = null;
      for (int i = 0; i < ROWS; i++) {
        final MutableDocument doc = database.newDocument("Wide");
        doc.set("key", (long) i);
        doc.set("name", "name" + i);
        doc.set("born", java.time.LocalDate.of(2000, 1, 1).plusDays(i));
        doc.set("age", i % 90);
        doc.set("score", i * 1.5d);
        doc.set("tags", List.of("t" + (i % 3), "u" + (i % 5)));
        doc.set("meta", Map.of("k", i, "nested", Map.of("deep", "v" + i)));
        doc.newEmbeddedDocument("Wide", "embedded").set("inner", i); // AN EMBEDDED DOCUMENT OF THE SAME TYPE
        if (i % 2 == 0)
          doc.set("even", true);
        if (i % 7 == 0)
          doc.set("nullable", (Object) null);
        if (previous != null)
          doc.set("link", previous);
        doc.save();
        previous = doc.getIdentity();
      }
    });
  }

  @Test
  public void projectedPropertiesAreTheRecordValues() {
    final List<Result> rows = query(
        "SELECT key, name, age, score, even, nullable, missing, born, link, @rid AS rid, @type AS type FROM Wide WHERE key BETWEEN 0 AND 199 ORDER BY key");
    assertThat(rows).hasSize(ROWS);
    for (int i = 0; i < ROWS; i++) {
      final Result row = rows.get(i);
      assertThat(row.<Long>getProperty("key")).isEqualTo((long) i);
      assertThat(row.<String>getProperty("name")).isEqualTo("name" + i);
      assertThat(row.<Integer>getProperty("age")).isEqualTo(i % 90);
      assertThat(row.<Double>getProperty("score")).isEqualTo(i * 1.5d);
      assertThat(row.<Object>getProperty("even")).isEqualTo(i % 2 == 0 ? Boolean.TRUE : null);
      assertThat(row.<Object>getProperty("nullable")).isNull();
      assertThat(row.<Object>getProperty("missing")).isNull();
      assertThat(row.<Object>getProperty("born")).isEqualTo(java.time.LocalDate.of(2000, 1, 1).plusDays(i));
      assertThat(row.<Object>getProperty("rid")).isInstanceOf(RID.class);
      assertThat(row.<String>getProperty("type")).isEqualTo("Wide");
      if (i > 0)
        assertThat(row.<Object>getProperty("link")).isInstanceOf(RID.class);
      // THE PROJECTED ROW HAS EXACTLY THE PROJECTED COLUMNS: THE VIEW NEVER LEAKS THE RECORD INTO IT
      assertThat(row.getPropertyNames()).containsExactly("key", "name", "age", "score", "even", "nullable", "missing", "born", "link", "rid",
          "type");
      assertThat(row.isElement()).isFalse();
    }
  }

  @Test
  public void mutableValuesAreNotSharedBetweenRows() {
    // A list or a map read off a row through the view is decoded per read: the first row's list must not show up again
    final List<Result> rows = query("SELECT key, tags, meta, embedded FROM Wide WHERE key BETWEEN 0 AND 9 ORDER BY key");
    assertThat(rows).hasSize(10);
    for (int i = 0; i < 10; i++) {
      final Result row = rows.get(i);
      assertThat(row.<List<String>>getProperty("tags")).containsExactly("t" + (i % 3), "u" + (i % 5));
      assertThat(row.<Map<String, Object>>getProperty("meta")).containsEntry("k", i);
      assertThat(((Map<String, Object>) row.<Map<String, Object>>getProperty("meta").get("nested")).get("deep")).isEqualTo("v" + i);
      final Document embedded = row.getProperty("embedded");
      assertThat(embedded.getInteger("inner")).isEqualTo(i);
    }
    // THE ROWS ARE INDEPENDENT OBJECTS ONCE THE QUERY IS OVER
    assertThat(rows.get(0).<List<String>>getProperty("tags")).isNotSameAs(rows.get(1).<List<String>>getProperty("tags"));
  }

  @Test
  public void expressionsReadTheSameValuesAsThePerPropertyPath() {
    final String[] queries = {
        "SELECT key, name, age, score, even, nullable, missing, born, link, tags, meta FROM Wide WHERE key BETWEEN 0 AND 199 ORDER BY key",
        "SELECT key, age + 1 AS next, score * 2 AS doubled, name.toUpperCase() AS upper, tags[0] AS first, meta.k AS k, meta.nested.deep AS deep FROM Wide WHERE key < 50 ORDER BY key",
        "SELECT key, $current.name AS current, @this.age AS thisAge FROM Wide WHERE key < 20 ORDER BY key",
        "SELECT key, ifnull(even, false) AS even, format('%s-%d', name, age) AS label FROM Wide WHERE key < 20 ORDER BY key",
        "SELECT key, name, embedded:{inner} AS nested FROM Wide WHERE key < 20 ORDER BY key",
        "SELECT *, key + 1 AS next FROM Wide WHERE key < 20 ORDER BY key",
        "SELECT *, !meta, !tags FROM Wide WHERE key < 20 ORDER BY key",
        "SELECT * FROM Wide WHERE key < 20 ORDER BY key",
        "SELECT key, name FROM (SELECT key, name, age FROM Wide WHERE key < 20) ORDER BY key",
        "SELECT key, name, age FROM Wide WHERE key < 20 ORDER BY key LIMIT 5",
        "SELECT key, name, link.name AS linked, link.link.name AS linked2 FROM Wide WHERE key BETWEEN 2 AND 20 ORDER BY key",
    };

    for (final String sql : queries) {
      final List<Map<String, Object>> cached = toMaps(query(sql));
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, false);
      try {
        final List<Map<String, Object>> plain = toMaps(query(sql));
        assertThat(cached).as(sql).isEqualTo(plain);
        assertThat(cached).as(sql).isNotEmpty();
      } finally {
        database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, true);
      }
    }
  }

  @Test
  public void selectStarKeepsTheRecordAttributesAndExclusions() {
    // NO ORDER BY: THE PLANNER TURNS !meta INTO A PLAIN meta ITEM OF THE PROJECTION AFTER THE SORT (A NULL COLUMN), WHICH
    // IS WHAT THE PER-PROPERTY PATH ANSWERS TOO, SEE expressionsReadTheSameValuesAsThePerPropertyPath
    final List<Result> rows = query("SELECT *, !meta FROM Wide WHERE key < 3");
    assertThat(rows).hasSize(3);
    for (int i = 0; i < 3; i++) {
      final Result row = rows.get(i);
      assertThat(row.getPropertyNames()).doesNotContain("meta");
      assertThat(row.getPropertyNames()).contains("key", "name", "tags", "embedded", "@rid", "@type");
      assertThat(row.<String>getProperty("@type")).isEqualTo("Wide");
      assertThat(row.<Object>getProperty("@rid")).isInstanceOf(RID.class);
      assertThat(row.<Long>getProperty("key")).isEqualTo((long) i);
      assertThat(row.isElement()).isTrue();
      // A RECORD ATTRIBUTE OF THE ROW IS NOT A PROPERTY OF THE RECORD: THE JSON OF THE ROW HAS NO meta EITHER
      assertThat(row.toJSON().has("meta")).isFalse();
      assertThat(row.toJSON().getString("@type")).isEqualTo("Wide");
    }
  }

  @Test
  public void selectStarIsNotReadThroughTheView() {
    // A * ITEM (ProjectionItem.all, NO EXPRESSION) HAS NO NODE THE AST CHECK CAN SEE, AND ITS BRANCH OF calculateSingle READS THE
    // ROW AS A WHOLE (toElement, toPropertyMap): IT MUST GET THE SCAN'S ROW, NOT THE VIEW, WHOSE ONE-PASS READING IT WOULD MISS
    for (final String sql : List.of("SELECT * FROM Wide WHERE key < 3", "SELECT *, !meta FROM Wide WHERE key < 3",
        "SELECT *, key AS k FROM Wide WHERE key < 3"))
      assertThat(projectionSteps(sql)).as(sql).isNotEmpty().noneMatch(ProjectionCalculationStep::readsThroughPropertyCache);
    // THE PLAIN COLUMN LIST STILL IS
    assertThat(projectionSteps("SELECT key, name FROM Wide WHERE key < 3")).isNotEmpty()
        .allMatch(ProjectionCalculationStep::readsThroughPropertyCache);
  }

  @Test
  public void aProjectionAfterAnUpdateReadsTheWrittenValues() {
    // The rows of a RETURN AFTER are mutable documents with content set on them: the view declines them, and they read as always
    final List<Result> rows = new ArrayList<>();
    database.transaction(() -> rows.addAll(query("UPDATE Wide SET age = 1000 RETURN AFTER key, age, name WHERE key < 3")));
    assertThat(rows).hasSize(3);
    for (final Result row : rows) {
      assertThat(row.<Integer>getProperty("age")).isEqualTo(1000);
      assertThat(row.<String>getProperty("name")).startsWith("name");
    }
  }

  private List<Result> query(final String sql) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.command("sql", sql)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private List<ProjectionCalculationStep> projectionSteps(final String sql) {
    final List<ProjectionCalculationStep> steps = new ArrayList<>();
    try (final ResultSet resultSet = database.query("sql", sql)) {
      while (resultSet.hasNext())
        resultSet.next();
      for (final ExecutionStep step : resultSet.getExecutionPlan().orElseThrow().getSteps())
        if (step instanceof ProjectionCalculationStep projection)
          steps.add(projection);
    }
    return steps;
  }

  private static List<Map<String, Object>> toMaps(final List<Result> rows) {
    final List<Map<String, Object>> maps = new ArrayList<>(rows.size());
    for (final Result row : rows)
      maps.add(row.toPropertyMap(true));
    return maps;
  }
}
