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
package com.arcadedb.postgres;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DataRow writer and {@code COPY ... TO STDOUT} read a plain element row's properties off its record in one pass (SPORK
 * retrieval speed, item S4) instead of one header walk per column. The column values must be the ones the per-column reading
 * answered, its element fallback included: a property removed from the row ({@code ResultInternal.removeProperty}, a tombstone)
 * was answered off the backing record by that fallback, and still is.
 */
class PostgresElementRowColumnValueTest {
  private static final String DATABASE_PATH = "./target/databases/PostgresElementRowColumnValueTest";

  private Database        database;
  private MutableDocument document;

  @BeforeEach
  void setUp() {
    final DatabaseFactory factory = new DatabaseFactory(DATABASE_PATH);
    if (factory.exists())
      factory.open().drop();
    database = factory.create();
    database.transaction(() -> {
      database.getSchema().createDocumentType("Item").createProperty("name", Type.STRING);
      document = database.newDocument("Item");
      document.set("name", "first");
      document.set("size", 3);
      document.set("tags", List.of("a", "b"));
      document.save();
    });
  }

  @AfterEach
  void tearDown() {
    if (database != null && database.isOpen())
      database.drop();
  }

  @Test
  void aPlainElementRowAnswersTheRecordValues() {
    final ResultInternal row = new ResultInternal(database.lookupByRID(document.getIdentity(), true));
    assertThat(PostgresNetworkExecutor.columnValue(row, "name")).isEqualTo("first");
    assertThat(PostgresNetworkExecutor.columnValue(row, "size")).isEqualTo(3);
    assertThat(PostgresNetworkExecutor.columnValue(row, "tags")).isEqualTo(List.of("a", "b"));
    assertThat(PostgresNetworkExecutor.columnValue(row, "missing")).isNull();
    assertThat(PostgresNetworkExecutor.columnValue(row, "@type")).isEqualTo("Item");
    assertThat(PostgresNetworkExecutor.columnValue(row, "@rid")).isEqualTo(document.getIdentity());
  }

  @Test
  void aRemovedPropertyFallsBackToTheRecordAsBefore() {
    final ResultInternal row = new ResultInternal(database.lookupByRID(document.getIdentity(), true));
    row.removeProperty("name");
    // THE PER-COLUMN READING: getProperty() ANSWERS null FOR THE TOMBSTONE, AND THE ELEMENT FALLBACK ANSWERS THE RECORD'S VALUE
    assertThat(row.<Object>getProperty("name")).isNull();
    assertThat(PostgresNetworkExecutor.columnValue(row, "name")).isEqualTo("first");
    assertThat(PostgresNetworkExecutor.columnValue(row, "size")).isEqualTo(3);
  }
}
