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
package com.arcadedb.query.selection;

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 4 (docs/spork/03-f1-selections-design.md, 2.5): every search modality takes a saved selection as the set to search
 * within, inside the index scan. Twelve ads, all mentioning "escort", on points along the equator and with 2-d embeddings;
 * the saved selection {@code g} holds four of them (ad1, ad4, ad7, ad10).
 */
class SelectionModalitiesTest extends TestHelper {
  private static final int      ADS     = 12;
  private static final Set<String> MEMBERS = Set.of("ad1", "ad4", "ad7", "ad10");

  @Override
  protected void beginTest() {
    database.command("sql", "CREATE VERTEX TYPE Ad BUCKETS 2");
    database.command("sql", "CREATE PROPERTY Ad.name STRING");
    database.command("sql", "CREATE PROPERTY Ad.text STRING");
    database.command("sql", "CREATE PROPERTY Ad.coords STRING");
    database.command("sql", "CREATE PROPERTY Ad.embedding ARRAY_OF_FLOATS");
    database.command("sql", "CREATE INDEX ON Ad (text) FULL_TEXT");
    database.command("sql", "CREATE INDEX ON Ad (coords) GEOSPATIAL");
    database.command("sql", "CREATE INDEX ON Ad (embedding) LSM_VECTOR METADATA { dimensions: 2, similarity: 'EUCLIDEAN' }");

    final List<String> members = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < ADS; i++) {
        final RID rid = database.newVertex("Ad").set("name", "ad" + i).set("text", "new escort in town number " + i)
            .set("coords", "POINT (" + i + " 0)").set("embedding", new float[] { i, 0f }).save().getIdentity();
        if (MEMBERS.contains("ad" + i))
          members.add(rid.toString());
      }
    });
    SelectionService.save(database, new JSONObject().put("name", "g").put("members", new JSONArray(members)));
    SelectionService.save(database, new JSONObject().put("name", "none").put("members", new JSONArray()));
  }

  @Test
  void fullTextInSqlSearchesOnlyTheSelection() {
    final String query = "SELECT name FROM Ad WHERE SEARCH_INDEX('Ad[text]', 'escort') = true AND @rid IN selection.rids('g')";
    // The first search computes the index's corpus statistics with a scan of the type: not the subject
    names("SELECT name FROM Ad WHERE SEARCH_INDEX('Ad[text]', 'escort') = true LIMIT 1");

    assertThat(names(query)).containsExactlyInAnyOrderElementsOf(MEMBERS);
    assertThat(plan(query)).contains("RID FILTER").doesNotContain("FETCH FROM RIDs");
    // Decided on the RIDs before any record is loaded: only the members are read
    assertThat(typeRecordsLoadedBy(query)).isEqualTo(MEMBERS.size());
  }

  @Test
  void notInExcludesTheSelection() {
    final List<String> names = names(
        "SELECT name FROM Ad WHERE SEARCH_INDEX('Ad[text]', 'escort') = true AND @rid NOT IN selection.rids('g')");
    assertThat(names).hasSize(ADS - MEMBERS.size()).doesNotContainAnyElementsOf(MEMBERS);
  }

  @Test
  void geoInSqlSearchesOnlyTheSelection() {
    // POINT (0..5 0): ad0 to ad5, of which ad1 and ad4 are members
    final List<String> names = names("SELECT name FROM Ad WHERE geo.within(coords, geo.geomFromText("
        + "'POLYGON ((-0.5 -1, 5.5 -1, 5.5 1, -0.5 1, -0.5 -1))')) = true AND @rid IN selection.rids('g')");
    assertThat(names).containsExactlyInAnyOrder("ad1", "ad4");
  }

  @Test
  void vectorInSqlSearchesOnlyTheSelection() {
    final List<String> names = names("SELECT name FROM (SELECT expand(vector.neighbors('Ad[embedding]', [0.0, 0.0], 3, "
        + "{selection: 'g'})))");
    // the three members nearest the origin, not ad0 to ad2
    assertThat(names).containsExactly("ad1", "ad4", "ad7");
  }

  @Test
  void aSelectionAndAFilterAreBothApplied() {
    final String ad0 = rid("ad0");
    final String ad4 = rid("ad4");
    final List<String> names = names("SELECT name FROM (SELECT expand(vector.neighbors('Ad[embedding]', [0.0, 0.0], 3, "
        + "{selection: 'g', filter: ['" + ad0 + "', '" + ad4 + "']})))");
    assertThat(names).containsExactly("ad4");
  }

  @Test
  void vectorInCypherSearchesOnlyTheSelection() {
    final List<String> names = cypherNames("CALL db.index.vector.queryNodes('Ad[embedding]', 2, [0.0, 0.0], {selection: 'g'}) "
        + "YIELD node RETURN node.name AS name");
    assertThat(names).containsExactly("ad1", "ad4");
  }

  @Test
  void fullTextInCypherSearchesOnlyTheSelection() {
    final List<String> names = cypherNames("CALL db.index.fulltext.queryNodes('Ad[text]', 'escort', {selection: 'g'}) "
        + "YIELD node RETURN node.name AS name");
    assertThat(names).containsExactlyInAnyOrderElementsOf(MEMBERS);
  }

  @Test
  void anUnknownSelectionIsRefused() {
    assertThatThrownBy(() -> names("SELECT name FROM Ad WHERE @rid IN selection.rids('nope')"))
        .hasStackTraceContaining("no selection named 'nope'");
    assertThatThrownBy(() -> cypherNames("CALL db.index.vector.queryNodes('Ad[embedding]', 2, [0.0, 0.0], {selection: 'nope'}) "
        + "YIELD node RETURN node.name AS name")).hasStackTraceContaining("no selection named 'nope'");
  }

  @Test
  void aSelectionAloneIsFetchedByRid() {
    final String query = "SELECT name FROM Ad WHERE @rid IN selection.rids('g')";
    assertThat(names(query)).containsExactlyInAnyOrderElementsOf(MEMBERS);
    // No indexed function to push it into: the direct fetch of the members stays the plan (issue #5824's rewrite)
    assertThat(plan(query)).contains("FETCH FROM RIDs");
    assertThat(typeRecordsLoadedBy(query)).isEqualTo(MEMBERS.size());
  }

  @Test
  void geoPushesTheSelectionIntoTheIndexToo() {
    final String query = "SELECT name FROM Ad WHERE geo.within(coords, geo.geomFromText("
        + "'POLYGON ((-0.5 -1, 5.5 -1, 5.5 1, -0.5 1, -0.5 -1))')) = true AND @rid IN selection.rids('g')";
    assertThat(plan(query)).contains("RID FILTER").doesNotContain("FETCH FROM RIDs");
    // ad1 and ad4, the matches inside the selection, and nothing else of the type
    assertThat(typeRecordsLoadedBy(query)).isEqualTo(2);
  }

  // ---- helpers ----

  private String rid(final String name) {
    try (final ResultSet rs = database.query("sql", "SELECT @rid AS rid FROM Ad WHERE name = ?", name)) {
      return rs.next().getProperty("rid").toString();
    }
  }

  private List<String> names(final String sql) {
    final List<String> names = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", sql)) {
      while (rs.hasNext())
        names.add(rs.next().getProperty("name"));
    }
    return names;
  }

  private List<String> cypherNames(final String cypher) {
    final List<String> names = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", cypher, Map.of())) {
      while (rs.hasNext())
        names.add(rs.next().getProperty("name"));
    }
    return names;
  }

  private String plan(final String sql) {
    try (final ResultSet rs = database.query("sql", sql)) {
      return rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  private long recordsLoadedBy(final String sql) {
    final long before = readRecords();
    names(sql);
    return readRecords() - before;
  }

  /**
   * The records of the searched type a query over selection {@code g} loads, net of the reads of the SelectionHandle document
   * itself: the same statement over the EMPTY selection {@code none} has the same plan and resolves its handle as often, but
   * loads no record of the type.
   */
  private long typeRecordsLoadedBy(final String query) {
    final String overNothing = query.replace("selection.rids('g')", "selection.rids('none')");
    // run both once first, so both are measured on a cached plan
    names(query);
    names(overNothing);
    return recordsLoadedBy(query) - recordsLoadedBy(overNothing);
  }

  private long readRecords() {
    return ((Number) ((DatabaseInternal) database).getStats().get("readRecord")).longValue();
  }
}
