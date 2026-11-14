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

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.exception.CommandSemanticException;
import com.arcadedb.exception.InvalidPropertyTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code UPDATE ... INCREMENT}, {@code ADD} and {@code PUT} parsed but the planner refused every one of them with "Cannot execute
 * with UPDATE PUT/ADD/INCREMENT new executor", and so did {@code MOVE VERTEX}. They now run with the OrientDB semantics clients
 * written against it rely on: {@code INCREMENT f = n} is {@code f = ifnull(f, 0) + n}, {@code ADD f = v} appends to the collection
 * (creating a list when the property is missing) and {@code PUT f = k, v} puts the entry into the map (creating it when missing).
 */
class UpdateIncrementAddPutTest extends TestHelper {

  private RID rid;

  @BeforeEach
  void fill() {
    database.command("sql", "CREATE VERTEX TYPE Counter").close();
    database.command("sql", "CREATE PROPERTY Counter.n INTEGER").close();
    database.command("sql", "CREATE PROPERTY Counter.big LONG").close();
    database.command("sql", "CREATE PROPERTY Counter.d DOUBLE").close();
    database.command("sql", "CREATE PROPERTY Counter.tags LIST OF STRING").close();
    database.command("sql", "CREATE PROPERTY Counter.attrs MAP").close();
    database.transaction(() -> {
      try (final ResultSet rs = database.command("sql",
          "INSERT INTO Counter SET id = 1, n = 1, big = 10, d = 1.5, s = 'text', tags = ['a'], attrs = {'k': 1}")) {
        rid = rs.next().getIdentity().get();
      }
      database.command("sql", "INSERT INTO Counter SET id = 2").close();
    });
  }

  private void update(final String statement) {
    database.transaction(() -> database.command("sql", statement).close());
  }

  private Result row(final int id) {
    try (final ResultSet rs = database.query("sql", "SELECT FROM Counter WHERE id = ?", id)) {
      return rs.next();
    }
  }

  // INCREMENT ------------------------------------------------------------------------------------------------------------------

  @Test
  void incrementAddsToTheValue() {
    update("UPDATE " + rid + " INCREMENT n = 1");

    final Object n = row(1).getProperty("n");
    assertThat(n).isInstanceOf(Integer.class);
    assertThat(n).isEqualTo(2);
  }

  @Test
  void incrementByANegativeNumberSubtracts() {
    update("UPDATE " + rid + " INCREMENT n = -3, big = -11");

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(-2);
    assertThat(row(1).<Long>getProperty("big")).isEqualTo(-1L);
  }

  @Test
  void incrementADecimal() {
    update("UPDATE " + rid + " INCREMENT d = 0.25");

    assertThat(row(1).<Double>getProperty("d")).isEqualTo(1.75d);
  }

  @Test
  void incrementOnAMissingFieldStartsFromZero() {
    update("UPDATE Counter INCREMENT n = 5, extra = 2 WHERE id = 2");

    assertThat(row(2).<Integer>getProperty("n")).isEqualTo(5);
    assertThat(row(2).<Number>getProperty("extra").intValue()).isEqualTo(2);
  }

  @Test
  void incrementSeveralItemsAndSeveralRecords() {
    update("UPDATE Counter INCREMENT n = 1, big = 2");

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(2);
    assertThat(row(1).<Long>getProperty("big")).isEqualTo(12L);
    assertThat(row(2).<Integer>getProperty("n")).isEqualTo(1);
    assertThat(row(2).<Long>getProperty("big")).isEqualTo(2L);
  }

  @Test
  void incrementByAnExpressionAndAParameter() {
    database.transaction(() -> database.command("sql", "UPDATE " + rid + " INCREMENT n = big * 2, d = ?", 1).close());

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(21);
    assertThat(row(1).<Double>getProperty("d")).isEqualTo(2.5d);
  }

  @Test
  void incrementByNullLeavesTheValue() {
    update("UPDATE " + rid + " INCREMENT n = null");

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(1);
  }

  @Test
  void incrementReturnsTheNewValue() {
    database.transaction(() -> {
      try (final ResultSet rs = database.command("sql", "UPDATE " + rid + " INCREMENT n = 1 RETURN AFTER")) {
        assertThat(rs.next().<Integer>getProperty("n")).isEqualTo(2);
      }
    });
  }

  @Test
  void incrementANestedValue() {
    update("UPDATE " + rid + " INCREMENT attrs.k = 2");

    assertThat(row(1).<Map<String, Object>>getProperty("attrs").get("k")).isEqualTo(3);
  }

  @Test
  void incrementThroughTheTargetAlias() {
    update("UPDATE Counter c INCREMENT c.n = 1 WHERE id = 1");

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(2);
    assertThat(row(1).hasProperty("c")).isFalse();
  }

  @Test
  void incrementInsideAScriptTransaction() {
    database.command("sqlscript", "BEGIN; UPDATE " + rid + " INCREMENT n = 1; UPDATE " + rid + " INCREMENT n = 1; COMMIT;").close();

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(3);
  }

  @Test
  void incrementInAScriptWithoutBeginCommit() {
    database.transaction(
        () -> database.command("sqlscript", "UPDATE " + rid + " INCREMENT n = 1; UPDATE " + rid + " SET n = n + 1;").close());

    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(3);
  }

  @Test
  void incrementByANonNumberIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " INCREMENT n = 'x'"))
        .isInstanceOf(InvalidPropertyTypeException.class)
        .hasMessageContaining("INCREMENT")
        .hasMessageContaining("String");
    assertThat(row(1).<Integer>getProperty("n")).isEqualTo(1);
  }

  @Test
  void incrementANonNumberIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " INCREMENT s = 1"))
        .isInstanceOf(InvalidPropertyTypeException.class)
        .hasMessageContaining("INCREMENT")
        .hasMessageContaining("'s'");
    assertThat(row(1).<String>getProperty("s")).isEqualTo("text");
  }

  // ADD ------------------------------------------------------------------------------------------------------------------------

  @Test
  void addAppendsToAList() {
    update("UPDATE " + rid + " ADD tags = 'b'");

    assertThat(row(1).<List<String>>getProperty("tags")).containsExactly("a", "b");
  }

  @Test
  void addACollectionAppendsItsItems() {
    update("UPDATE " + rid + " ADD tags = ['b', 'c']");

    assertThat(row(1).<List<String>>getProperty("tags")).containsExactly("a", "b", "c");
  }

  @Test
  void addOnAMissingFieldCreatesAList() {
    update("UPDATE Counter ADD tags = 'x', other = 3 WHERE id = 2");

    assertThat(row(2).<List<String>>getProperty("tags")).containsExactly("x");
    assertThat(row(2).<List<Object>>getProperty("other")).hasSize(1);
  }

  @Test
  void addTwiceToTheSameListAppendsBoth() {
    update("UPDATE " + rid + " ADD tags = 'b', tags = 'c'");

    assertThat(row(1).<List<String>>getProperty("tags")).containsExactly("a", "b", "c");
  }

  @Test
  void addNullAddsNothing() {
    update("UPDATE " + rid + " ADD tags = null");

    assertThat(row(1).<List<String>>getProperty("tags")).containsExactly("a");
  }

  @Test
  void addInsideAScriptTransaction() {
    database.command("sqlscript", "BEGIN; UPDATE " + rid + " ADD tags = 'b'; COMMIT;").close();

    assertThat(row(1).<List<String>>getProperty("tags")).containsExactly("a", "b");
  }

  @Test
  void addToAScalarIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " ADD s = 'x'"))
        .isInstanceOf(InvalidPropertyTypeException.class)
        .hasMessageContaining("ADD")
        .hasMessageContaining("'s'");
  }

  @Test
  void addToAMapIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " ADD attrs = 'x'"))
        .isInstanceOf(InvalidPropertyTypeException.class)
        .hasMessageContaining("PUT");
  }

  @Test
  void addWithACompoundOperatorIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " ADD tags += 'x'"))
        .isInstanceOf(CommandSemanticException.class)
        .hasMessageContaining("ADD");
  }

  // PUT ------------------------------------------------------------------------------------------------------------------------

  @Test
  void putAddsAnEntry() {
    update("UPDATE " + rid + " PUT attrs = 'j', 'v'");

    assertThat(row(1).<Map<String, Object>>getProperty("attrs")).containsEntry("k", 1).containsEntry("j", "v");
  }

  @Test
  void putReplacesAnExistingKey() {
    update("UPDATE " + rid + " PUT attrs = 'k', 5");

    assertThat(row(1).<Map<String, Object>>getProperty("attrs")).containsExactly(Map.entry("k", 5));
  }

  @Test
  void putOnAMissingFieldCreatesAMap() {
    update("UPDATE Counter PUT attrs = 'a', 1, attrs = 'b', 2 WHERE id = 2");

    assertThat(row(2).<Map<String, Object>>getProperty("attrs")).containsEntry("a", 1).containsEntry("b", 2);
  }

  @Test
  void putInsideAScriptTransaction() {
    database.command("sqlscript", "BEGIN; UPDATE " + rid + " PUT attrs = 'j', 'v'; COMMIT;").close();

    assertThat(row(1).<Map<String, Object>>getProperty("attrs")).containsEntry("j", "v");
  }

  @Test
  void putIntoANonMapIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " PUT tags = 'k', 'v'"))
        .isInstanceOf(InvalidPropertyTypeException.class)
        .hasMessageContaining("PUT")
        .hasMessageContaining("'tags'");
  }

  @Test
  void putANullKeyIsRefused() {
    assertThatThrownBy(() -> update("UPDATE " + rid + " PUT attrs = null, 'v'"))
        .isInstanceOf(InvalidPropertyTypeException.class)
        .hasMessageContaining("key");
  }

  // MOVE VERTEX ----------------------------------------------------------------------------------------------------------------

  @Test
  void moveVertexRunsIncrementAddAndPut() {
    database.command("sql", "CREATE VERTEX TYPE Moved").close();

    database.transaction(() -> database.command("sql", "MOVE VERTEX " + rid + " TO TYPE:Moved INCREMENT n = 1").close());
    try (final ResultSet rs = database.query("sql", "SELECT FROM Moved")) {
      final Result moved = rs.next();
      assertThat(moved.<Number>getProperty("n").intValue()).isEqualTo(2);
      assertThat(rs.hasNext()).isFalse();
    }

    database.transaction(() -> database.command("sql", "MOVE VERTEX (SELECT FROM Moved) TO TYPE:Counter ADD tags = 'b'").close());
    database.transaction(() -> database.command("sql", "MOVE VERTEX (SELECT FROM Counter WHERE id = 1) TO TYPE:Moved PUT attrs = 'j', 'v'").close());
    try (final ResultSet rs = database.query("sql", "SELECT FROM Moved")) {
      final Result moved = rs.next();
      assertThat(moved.<List<String>>getProperty("tags")).containsExactly("a", "b");
      assertThat(moved.<Map<String, Object>>getProperty("attrs")).containsEntry("j", "v");
    }
  }
}
