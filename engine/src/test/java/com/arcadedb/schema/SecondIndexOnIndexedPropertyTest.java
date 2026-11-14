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
package com.arcadedb.schema;

import com.arcadedb.TestHelper;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.exception.AlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A FULL_TEXT index asked for on a property that already has a UNIQUE index. A type keeps one index per property set, so the
 * request is refused, but it used to be refused with "Index 'Address[value]' already exists" (unnamed form, read as a plain
 * duplicate) or with a message that only said to drop the existing index (named form). Both now say why and what to do instead,
 * and the alternative they point at - the second index on a copy of the property - gives the unique constraint and the full-text
 * search together.
 */
class SecondIndexOnIndexedPropertyTest extends TestHelper {

  @BeforeEach
  void schema() {
    database.command("sql", "CREATE VERTEX TYPE Address").close();
    database.command("sql", "CREATE PROPERTY Address.value STRING").close();
    database.command("sql", "CREATE INDEX ON Address (value) UNIQUE").close();
  }

  @Test
  void unnamedFullTextBesideUniqueSaysWhy() {
    assertThatThrownBy(() -> database.command("sql", "CREATE INDEX ON Address (value) FULL_TEXT"))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageContaining("already exists")
        .hasMessageContaining("one index per property set")
        .hasMessageContaining("FULL_TEXT")
        .hasMessageContaining("another property");

    assertOnlyTheUniqueIndex();
  }

  @Test
  void namedFullTextBesideUniqueSaysWhy() {
    assertThatThrownBy(() -> database.command("sql", "CREATE INDEX `Address_value_fulltext` ON Address (value) FULL_TEXT"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Address[value]")
        .hasMessageContaining("one index per property set")
        .hasMessageContaining("FULL_TEXT");

    assertOnlyTheUniqueIndex();
    assertThat(database.getSchema().existsIndex("Address_value_fulltext")).isFalse();
  }

  @Test
  void aSameKindDuplicateKeepsThePlainMessage() {
    assertThatThrownBy(() -> database.command("sql", "CREATE INDEX ON Address (value) UNIQUE"))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessage("Index 'Address[value]' already exists");
  }

  @Test
  void uniqueAndFullTextOnACopyOfThePropertyBothWorkAndSurviveAReopen() {
    database.command("sql", "CREATE PROPERTY Address.value_search STRING").close();
    database.command("sql", "CREATE INDEX `Address_value_fulltext` ON Address (value_search) FULL_TEXT").close();

    database.transaction(() -> {
      database.command("sql", "CREATE VERTEX Address SET value = '12 Baker Street', value_search = '12 Baker Street'").close();
      database.command("sql", "CREATE VERTEX Address SET value = '3 Abbey Road', value_search = '3 Abbey Road'").close();
    });

    reopenDatabase();

    assertThatThrownBy(() -> database.transaction(
        () -> database.command("sql", "CREATE VERTEX Address SET value = '3 Abbey Road', value_search = '3 Abbey Road'").close()))
        .isInstanceOf(DuplicatedKeyException.class);

    try (final ResultSet rs = database.query("sql",
        "SELECT value FROM Address WHERE SEARCH_INDEX('Address_value_fulltext', 'baker') = true")) {
      assertThat(rs.next().<String>getProperty("value")).isEqualTo("12 Baker Street");
      assertThat(rs.hasNext()).isFalse();
    }
  }

  private void assertOnlyTheUniqueIndex() {
    assertThat(database.getSchema().getType("Address").getAllIndexes(false)).hasSize(1);
    assertThat(database.getSchema().getIndexByName("Address[value]").isUnique()).isTrue();
  }
}
