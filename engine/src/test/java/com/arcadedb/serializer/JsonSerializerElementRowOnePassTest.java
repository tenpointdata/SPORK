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
package com.arcadedb.serializer;

import com.arcadedb.TestHelper;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsonSerializer#serializeResult} reads an element row that reshapes nothing off its record in one pass (SPORK
 * retrieval speed, item S4). The JSON must be the one the name-by-name reading produced: the same properties in the same
 * order with the same formatting, the record attributes, the declared-type precision of a temporal, a removed property
 * left out, and a row a projection reshaped still read through the row.
 */
public class JsonSerializerElementRowOnePassTest extends TestHelper {

  @Override
  protected void beginTest() {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType("Row");
      type.createProperty("id", Type.INTEGER);
      type.createProperty("born", Type.DATE);
      type.createProperty("at", Type.DATETIME_MICROS);
      for (int i = 0; i < 3; i++) {
        final MutableDocument doc = database.newDocument("Row");
        doc.set("id", i);
        doc.set("name", "row" + i);
        doc.set("born", LocalDate.of(2001, 2, 3).plusDays(i));
        doc.set("at", LocalDateTime.of(2024, 5, 6, 7, 8, 9, 123456000).plusSeconds(i));
        doc.set("tags", List.of("a", "b" + i));
        doc.set("meta", Map.of("k", i));
        doc.set("maybe", (Object) null);
        doc.newEmbeddedDocument("Row", "inner").set("id", i * 10);
        doc.save();
      }
    });
  }

  @Test
  public void anElementRowSerializesAsItsRecordDid() {
    final JsonSerializer serializer = JsonSerializer.createJsonSerializer().setIncludeTypeHints(true);
    try (final ResultSet rs = database.query("sql", "SELECT FROM Row ORDER BY id")) {
      int i = 0;
      while (rs.hasNext()) {
        final Result row = rs.next();
        assertThat(row.isElement()).isTrue();
        final JSONObject json = serializer.serializeResult(database, row);
        // THE SAME NAMES THE NAME-BY-NAME READING LISTED, IN THE RECORD'S ORDER, AFTER THE RECORD ATTRIBUTES
        assertThat(json.keySet()).containsExactly("@rid", "@type", "@cat", "id", "name", "born", "at", "tags", "meta", "maybe", "inner");
        assertThat(json.getInt("id")).isEqualTo(i);
        assertThat(json.getString("name")).isEqualTo("row" + i);
        assertThat(json.getString("born")).isEqualTo(LocalDate.of(2001, 2, 3).plusDays(i).toString());
        // DATETIME_MICROS KEEPS ITS MICROSECONDS (issue #4149), WHICH NEEDS THE DECLARED TYPE OF THE COLUMN
        assertThat(json.getString("at")).contains(".123456");
        assertThat(json.getJSONArray("tags").length()).isEqualTo(2);
        assertThat(json.getJSONObject("meta").getInt("k")).isEqualTo(i);
        assertThat(json.isNull("maybe")).isTrue();
        assertThat(json.getJSONObject("inner").getInt("id")).isEqualTo(i * 10);
        assertThat(json.getString("@type")).isEqualTo("Row");
        assertThat(json.getString("@cat")).isEqualTo("d");
        // AN ELEMENT ROW CARRIES NO TYPE HINTS: THE SCHEMA ANSWERS THEM ON THE CLIENT (issue #10905)
        assertThat(json.has("@props")).isFalse();
        i++;
      }
      assertThat(i).isEqualTo(3);
    }
  }

  @Test
  public void aRemovedPropertyStaysOut() {
    try (final ResultSet rs = database.query("sql", "SELECT FROM Row WHERE id = 1")) {
      final ResultInternal row = (ResultInternal) rs.next();
      row.removeProperty("name");
      final JSONObject json = JsonSerializer.createJsonSerializer().serializeResult(database, row);
      assertThat(json.has("name")).isFalse();
      assertThat(json.getInt("id")).isEqualTo(1);
    }
  }

  @Test
  public void aReshapedRowIsReadThroughTheRow() {
    // SELECT *, <expression> keeps the element AND content: the computed alias wins, the excluded column stays out
    try (final ResultSet rs = database.query("sql", "SELECT *, !tags, id + 100 AS id FROM Row WHERE id = 2")) {
      final Result row = rs.next();
      final JSONObject json = JsonSerializer.createJsonSerializer().serializeResult(database, row);
      assertThat(json.getInt("id")).isEqualTo(102);
      assertThat(json.has("tags")).isFalse();
      assertThat(json.getString("name")).isEqualTo("row2");
    }
  }
}
