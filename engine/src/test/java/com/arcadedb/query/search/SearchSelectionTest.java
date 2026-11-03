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
package com.arcadedb.query.search;

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.query.selection.SelectionService;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 4b (docs/spork/03-f1-selections-design.md, 2.5): the shared search services behind {@code /api/v1/vector/*} and the
 * MCP search tools take a {@code selection} argument. Ten docs, all mentioning "escort", with dense embeddings along an axis
 * and sparse vectors on one token; the saved selection {@code g} holds d2, d5 and d8.
 */
class SearchSelectionTest extends TestHelper {
  private static final int         DOCS    = 10;
  private static final Set<String> MEMBERS = Set.of("d2", "d5", "d8");

  @Override
  protected void beginTest() {
    database.command("sql", "CREATE VERTEX TYPE Doc BUCKETS 2");
    database.command("sql", "CREATE PROPERTY Doc.name STRING");
    database.command("sql", "CREATE PROPERTY Doc.text STRING");
    database.command("sql", "CREATE PROPERTY Doc.embedding ARRAY_OF_FLOATS");
    database.command("sql", "CREATE PROPERTY Doc.tokens ARRAY_OF_INTEGERS");
    database.command("sql", "CREATE PROPERTY Doc.weights ARRAY_OF_FLOATS");
    database.command("sql", "CREATE INDEX ON Doc (text) FULL_TEXT");
    database.command("sql", "CREATE INDEX ON Doc (embedding) LSM_VECTOR METADATA { dimensions: 2, similarity: 'EUCLIDEAN' }");
    database.command("sql", "CREATE INDEX ON Doc (tokens, weights) LSM_SPARSE_VECTOR METADATA { dimensions: 8, "
        + "weightQuantization: 'FP32' }");

    final List<String> members = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < DOCS; i++) {
        final RID rid = database.newVertex("Doc").set("name", "d" + i).set("text", "escort ad number " + i)
            .set("embedding", new float[] { i, 0f }).set("tokens", new int[] { 1 }).set("weights", new float[] { 1f + i })
            .save().getIdentity();
        if (MEMBERS.contains("d" + i))
          members.add(rid.toString());
      }
    });
    SelectionService.save(database, new JSONObject().put("name", "g").put("members", new JSONArray(members)));
  }

  @Test
  void denseVectorSearchStaysInsideTheSelection() {
    final JSONObject response = VectorSearch.search(database, new JSONObject().put("indexName", "Doc[embedding]")
        .put("queryVector", new JSONArray(List.of(0.1, 0.0))).put("k", 2).put("selection", "g"));
    // the two members nearest the origin, not d0 and d1
    assertThat(names(response)).containsExactly("d2", "d5");
  }

  @Test
  void sparseVectorSearchStaysInsideTheSelection() {
    final JSONObject response = VectorSearch.search(database, new JSONObject().put("indexName", "Doc[tokens,weights]")
        .put("sparse", true).put("queryIndices", new JSONArray(List.of(1))).put("queryVector", new JSONArray(List.of(1.0)))
        .put("k", 10).put("selection", "g"));
    assertThat(names(response)).containsExactlyInAnyOrderElementsOf(MEMBERS);
  }

  @Test
  void fullTextSearchStaysInsideTheSelection() {
    final JSONObject response = FullTextQuery.search(database, new JSONObject().put("indexName", "Doc[text]")
        .put("queryText", "escort").put("limit", 10).put("selection", "g"));
    assertThat(names(response)).containsExactlyInAnyOrderElementsOf(MEMBERS);
  }

  @Test
  void bothLegsOfAHybridSearchStayInsideTheSelection() {
    final JSONObject response = HybridSearch.search(database, new JSONObject().put("vectorIndexName", "Doc[embedding]")
        .put("queryVector", new JSONArray(List.of(0.1, 0.0))).put("fulltextIndexName", "Doc[text]").put("fulltextQuery", "escort")
        .put("k", 10).put("selection", "g"));
    assertThat(names(response)).isNotEmpty();
    assertThat(MEMBERS).containsAll(names(response));
  }

  @Test
  void aSelectionMustNameASavedSelection() {
    final JSONObject unknown = new JSONObject().put("indexName", "Doc[text]").put("queryText", "escort").put("selection", "nope");
    assertThatThrownBy(() -> FullTextQuery.search(database, unknown)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no selection named 'nope'");

    final JSONObject notAString = new JSONObject().put("indexName", "Doc[embedding]")
        .put("queryVector", new JSONArray(List.of(0.1, 0.0))).put("selection", 7);
    assertThatThrownBy(() -> VectorSearch.search(database, notAString)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'selection' must be the name of a saved selection");

    final JSONObject blank = new JSONObject().put("indexName", "Doc[text]").put("queryText", "escort").put("selection", " ");
    assertThatThrownBy(() -> FullTextQuery.validateArguments(blank)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'selection' must be the name of a saved selection");
  }

  private static List<String> names(final JSONObject response) {
    final JSONArray results = response.getJSONArray("results");
    final List<String> names = new ArrayList<>(results.length());
    for (int i = 0; i < results.length(); i++)
      names.add(results.getJSONObject(i).getJSONObject("properties").getString("name"));
    return names;
  }
}
