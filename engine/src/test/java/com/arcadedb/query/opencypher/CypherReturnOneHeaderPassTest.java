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
package com.arcadedb.query.opencypher;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A RETURN or WITH that reads several properties of one bound record ({@code RETURN n.a, n.b, n.c}) locates them in one
 * pass over the record header per row (SPORK retrieval speed, item S4). The rows must be exactly the rows the
 * item-by-item evaluation answers, which {@code arcadedb.queryProjectionPropertyCache=false} still runs: the same temporal
 * readings, the same nulls for a missing property, the same fall-backs for a variable that is not an immutable record.
 */
class CypherReturnOneHeaderPassTest {
  private static final String DATABASE_PATH = "./target/databases/cypher-return-one-header-pass";
  private static final int    ROWS          = 50;

  private Database database;

  @BeforeEach
  void setUp() {
    database = new DatabaseFactory(DATABASE_PATH).create();
    final VertexType person = database.getSchema().createVertexType("Person");
    person.createProperty("id", Type.LONG);
    person.createProperty("name", Type.STRING);
    person.createProperty("born", Type.DATE);
    person.createProperty("since", Type.ZONED_DATETIME);
    person.createProperty("friend", Type.LINK);
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "Person", "id");
    database.getSchema().createEdgeType("KNOWS");

    database.transaction(() -> {
      MutableVertex previous = null;
      for (int i = 0; i < ROWS; i++) {
        final MutableVertex v = database.newVertex("Person");
        v.set("id", (long) i);
        v.set("name", "name" + i);
        v.set("age", i % 60);
        v.set("born", LocalDate.of(1990, 1, 1).plusDays(i));
        v.set("since", ZonedDateTime.of(2020, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2)).plusHours(i));
        v.set("tags", List.of("t" + (i % 3)));
        v.set("meta", Map.of("k", i));
        if (i % 2 == 0)
          v.set("even", true);
        if (previous != null)
          v.set("friend", previous.getIdentity());
        v.save();
        if (previous != null)
          previous.newEdge("KNOWS", v).save();
        previous = v;
      }
    });
  }

  @AfterEach
  void tearDown() {
    if (database != null)
      database.drop();
  }

  @Test
  void returnOfSeveralPropertiesAnswersTheRecordValues() {
    final List<Result> rows = query(
        "MATCH (n:Person) WHERE n.id >= 0 RETURN n.id AS id, n.name AS name, n.age AS age, n.missing AS missing, n.even AS even, n.born AS born, n.since AS since, n.tags AS tags, n.meta AS meta, n.name AS again ORDER BY n.id");
    assertThat(rows).hasSize(ROWS);
    for (int i = 0; i < ROWS; i++) {
      final Result row = rows.get(i);
      assertThat(row.<Long>getProperty("id")).isEqualTo((long) i);
      assertThat(row.<String>getProperty("name")).isEqualTo("name" + i);
      assertThat(row.<String>getProperty("again")).isEqualTo("name" + i);
      assertThat(row.<Integer>getProperty("age")).isEqualTo(i % 60);
      assertThat(row.<Object>getProperty("missing")).isNull();
      assertThat(row.<Object>getProperty("even")).isEqualTo(i % 2 == 0 ? Boolean.TRUE : null);
      assertThat(row.<List<String>>getProperty("tags")).containsExactly("t" + (i % 3));
      assertThat(row.<Map<String, Object>>getProperty("meta")).containsEntry("k", i);
      // THE TEMPORALS GET THE CYPHER READING A SINGLE n.property GETS
      assertThat(row.<Object>getProperty("born").toString()).isEqualTo(LocalDate.of(1990, 1, 1).plusDays(i).toString());
      assertThat(row.<Object>getProperty("since").toString()).contains("+02:00");
    }
  }

  @Test
  void everyShapeMatchesTheItemByItemEvaluation() {
    final String[] queries = {
        "MATCH (n:Person) RETURN n.id, n.name, n.age, n.missing, n.even, n.born, n.since, n.tags, n.meta ORDER BY n.id",
        "MATCH (n:Person {id: 3}) RETURN n.id, n.name, n.born",
        "MATCH (n:Person) WHERE n.id IN [1, 2, 3] RETURN n.id AS id, n.name AS name, n.friend AS friend ORDER BY id",
        "MATCH (a:Person)-[:KNOWS]->(b:Person) RETURN a.id, a.name, b.id, b.name ORDER BY a.id",
        "MATCH (n:Person) WITH n.id AS id, n.name AS name, n.age AS age WHERE age > 10 RETURN id, name, age ORDER BY id",
        "MATCH (n:Person) WITH n, n.age AS age RETURN n.id, n.name, age, toUpper(n.name) AS upper, n.age + 1 AS next ORDER BY n.id",
        "MATCH (n:Person) RETURN n.id, n.name, n.friend.name AS friendName ORDER BY n.id",
        "MATCH (n:Person) RETURN n.id, labels(n) AS labels, n.name, id(n) IS NOT NULL AS hasId ORDER BY n.id",
        "MATCH (n:Person) RETURN DISTINCT n.age, n.even ORDER BY n.age",
        "UNWIND [{id: 1, name: 'x'}, {id: 2, name: 'y'}] AS m RETURN m.id, m.name ORDER BY m.id",
        "MATCH (n:Person) WHERE n.id < 3 SET n.age = 100 RETURN n.id, n.age, n.name ORDER BY n.id",
    };
    for (final String cypher : queries) {
      final List<Map<String, Object>> onePass = toMaps(query(cypher));
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, false);
      try {
        final List<Map<String, Object>> itemByItem = toMaps(query(cypher));
        assertThat(onePass).as(cypher).isNotEmpty();
        assertThat(onePass).as(cypher).isEqualTo(itemByItem);
      } finally {
        database.getConfiguration().setValue(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE, true);
      }
    }
  }

  @Test
  void aVariableBoundToSomethingElseIsEvaluatedAsBefore() {
    // A RID (a persisted LINK) is dereferenced by the expression, a map is read as a map
    final List<Result> rows = query("MATCH (n:Person) WHERE n.id = 5 WITH n.friend AS f RETURN f.id AS id, f.name AS name");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().<Long>getProperty("id")).isEqualTo(4L);
    assertThat(rows.getFirst().<String>getProperty("name")).isEqualTo("name4");
  }

  private List<Result> query(final String cypher) {
    final List<Result> rows = new ArrayList<>();
    database.transaction(() -> {
      try (final ResultSet resultSet = database.command("opencypher", cypher)) {
        while (resultSet.hasNext())
          rows.add(resultSet.next());
      }
    });
    return rows;
  }

  private static List<Map<String, Object>> toMaps(final List<Result> rows) {
    final List<Map<String, Object>> maps = new ArrayList<>(rows.size());
    for (final Result row : rows) {
      final Map<String, Object> map = row.toPropertyMap(false);
      // A VERTEX IN A ROW COMPARES BY IDENTITY ACROSS TWO RUNS: COMPARE ITS RID
      map.replaceAll((k, v) -> v instanceof com.arcadedb.database.Document d ? d.getIdentity() : v);
      maps.add(map);
    }
    return maps;
  }
}
