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
package com.arcadedb.query.opencypher.procedures.selection;

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.olap.GraphAnalyticalView;
import com.arcadedb.graph.olap.GraphAnalyticalViewRegistry;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 5 (docs/spork/03-f1-selections-design.md, 2.8): with a graph analytical view over the {@code via} edge types,
 * {@code selection.venn} and {@code selection.compare} walk its CSR instead of the records, answer exactly what the record walk
 * answers, and load no record to do it; without a usable view they fall back to the record walk. Fixture as
 * {@link SelectionProceduresTest}: S0 = [a1, a2], S1 = [a3, a4], S2 = [a5, a6] share p2 (all), p1 (S0+S1), p3 (S1+S2),
 * e1 (S0+S2), and p4 is S2's alone.
 */
class SelectionCsrPathTest extends TestHelper {
  private static final String IDENTIFIERS = "{via: ['HAS_PHONE', 'HAS_EMAIL'], direction: 'OUT'}";

  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("Ad");
    database.getSchema().createVertexType("Phone");
    database.getSchema().createVertexType("Email");
    database.getSchema().createEdgeType("HAS_PHONE");
    database.getSchema().createEdgeType("HAS_EMAIL");
    database.getSchema().createEdgeType("OTHER");
    database.getSchema().createDocumentType("Note");

    database.transaction(() -> {
      final Map<String, MutableVertex> v = new HashMap<>();
      for (final String name : List.of("a1", "a2", "a3", "a4", "a5", "a6", "gone"))
        v.put(name, database.newVertex("Ad").set("name", name).save());
      for (final String name : List.of("p1", "p2", "p3", "p4"))
        v.put(name, database.newVertex("Phone").set("name", name).save());
      v.put("e1", database.newVertex("Email").set("name", "e1").save());
      link(v, "a1", "HAS_PHONE", "p1");
      link(v, "a1", "HAS_EMAIL", "e1");
      link(v, "a2", "HAS_PHONE", "p1");
      link(v, "a2", "HAS_PHONE", "p2");
      link(v, "a3", "HAS_PHONE", "p2");
      link(v, "a4", "HAS_PHONE", "p1");
      link(v, "a4", "HAS_PHONE", "p3");
      link(v, "a5", "HAS_PHONE", "p3");
      link(v, "a5", "HAS_EMAIL", "e1");
      link(v, "a6", "HAS_PHONE", "p2");
      link(v, "a6", "HAS_PHONE", "p4");
      link(v, "a6", "OTHER", "a1");
      for (final Map.Entry<String, MutableVertex> e : v.entrySet())
        rids.put(e.getKey(), e.getValue().getIdentity());
      rids.put("note", database.newDocument("Note").set("x", 1).save().getIdentity());
    });
    database.transaction(() -> rids.get("gone").asVertex().delete());
  }

  @Test
  void theViewAnswersWhatTheRecordWalkAnswersAndLoadsNothing() {
    final String venn = vennQuery(IDENTIFIERS);
    final String compare = compareQuery(IDENTIFIERS);
    final Map<Integer, Long> vennByRecords = regions(venn);
    final List<String> compareByRecords = compareRows(compare);
    assertThat(recordsLoadedBy(venn)).as("the record walk loads the members").isPositive();

    createView("HAS_PHONE, HAS_EMAIL");

    assertThat(regions(venn)).isEqualTo(vennByRecords);
    assertThat(compareRows(compare)).isEqualTo(compareByRecords);
    assertThat(recordsLoadedBy(venn)).as("through the view: no member, no neighbour").isZero();
    // compare loads only the records of the rows it returns, to answer `node`
    assertThat(recordsLoadedBy(compare)).isEqualTo(compareByRecords.size());
  }

  @Test
  void bothDirectionsAndTheMembersThemselvesAgreeToo() {
    final String both = vennQuery("{via: 'HAS_PHONE'}");
    final String members = vennQuery("{}");
    final Map<Integer, Long> bothByRecords = regions(both);
    final Map<Integer, Long> membersByRecords = regions(members);

    createView("HAS_PHONE, HAS_EMAIL");

    assertThat(regions(both)).isEqualTo(bothByRecords);
    assertThat(regions(members)).isEqualTo(membersByRecords);
  }

  @Test
  void aViewLackingAnEdgeTypeIsNotUsed() {
    final String query = vennQuery(IDENTIFIERS);
    final Map<Integer, Long> byRecords = regions(query);

    createView("HAS_EMAIL");

    assertThat(regions(query)).isEqualTo(byRecords);
    assertThat(recordsLoadedBy(query)).as("HAS_PHONE is not in the view: the record walk").isPositive();
  }

  @Test
  void aTransactionSeesItsOwnUncommittedEdges() {
    createView("HAS_PHONE, HAS_EMAIL");

    database.transaction(() -> {
      // a1 now also reaches p4, S2's alone until now: p4 becomes shared by S0 and S2
      rids.get("a1").asVertex().modify().newEdge("HAS_PHONE", rids.get("p4")).save();
      final Map<Integer, Long> regions = regions(vennQuery(IDENTIFIERS));
      assertThat(regions).doesNotContainKey(0b100);
      assertThat(regions).containsEntry(0b101, 2L); // e1 and p4
    });
  }

  @Test
  void aMemberTheViewDoesNotMapIsStillDecided() {
    createView("HAS_PHONE, HAS_EMAIL");

    // a stale member is skipped, as on the record walk
    final Map<String, Object> params = new HashMap<>();
    params.put("sels", List.of(List.of(rids.get("a1").toString(), rids.get("gone").toString()), sel("a3")));
    final Map<Integer, Long> regions = new TreeMap<>();
    for (final Result r : rows("CALL selection.venn($sels, " + IDENTIFIERS + ") YIELD mask, count RETURN mask, count", params))
      regions.put(((Number) r.getProperty("mask")).intValue(), ((Number) r.getProperty("count")).longValue());
    assertThat(regions).containsOnly(Map.entry(0b01, 2L), Map.entry(0b10, 1L));

    // a member that is not a vertex is refused, as on the record walk
    params.put("sels", List.of(List.of(rids.get("note").toString()), sel("a3")));
    assertThatThrownBy(() -> rows("CALL selection.venn($sels, " + IDENTIFIERS + ") YIELD count RETURN count", params))
        .hasStackTraceContaining("is not a node");
  }

  // ---- helpers ----

  private void createView(final String edgeTypes) {
    database.command("sql", "CREATE GRAPH ANALYTICAL VIEW selView VERTEX TYPES (Ad, Phone, Email) EDGE TYPES (" + edgeTypes + ")");
    final GraphAnalyticalView view = GraphAnalyticalViewRegistry.get(database, "selView");
    final long deadline = System.currentTimeMillis() + 60_000;
    while (!view.isReady() && System.currentTimeMillis() < deadline)
      Thread.onSpinWait();
    assertThat(view.isReady()).isTrue();
  }

  private String vennQuery(final String options) {
    return "CALL selection.venn([" + selText("a1", "a2") + ", " + selText("a3", "a4") + ", " + selText("a5", "a6") + "], " + options
        + ") YIELD mask, count RETURN mask, count";
  }

  private String compareQuery(final String options) {
    return "CALL selection.compare([" + selText("a1", "a2") + ", " + selText("a3", "a4") + ", " + selText("a5", "a6") + "], " + options
        + ") YIELD node, mask, counts, members RETURN node.name AS name, mask, counts, members";
  }

  private Map<Integer, Long> regions(final String query) {
    final Map<Integer, Long> regions = new TreeMap<>();
    for (final Result r : rows(query, Map.of()))
      regions.put(((Number) r.getProperty("mask")).intValue(), ((Number) r.getProperty("count")).longValue());
    return regions;
  }

  /** Each row as one comparable line: the element, its region, and the evidence. */
  private List<String> compareRows(final String query) {
    final List<String> lines = new ArrayList<>();
    for (final Result r : rows(query, Map.of()))
      lines.add(r.getProperty("name") + " " + r.getProperty("mask") + " " + r.getProperty("counts") + " " + r.getProperty("members"));
    return lines;
  }

  private long recordsLoadedBy(final String query) {
    final long before = readRecords();
    rows(query, Map.of());
    return readRecords() - before;
  }

  private long readRecords() {
    return ((Number) ((DatabaseInternal) database).getStats().get("readRecord")).longValue();
  }

  private List<Result> rows(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }

  private List<Object> sel(final String... names) {
    final List<Object> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return list;
  }

  private String selText(final String... names) {
    final List<String> quoted = new ArrayList<>();
    for (final String name : names)
      quoted.add("'" + rids.get(name) + "'");
    return "[" + String.join(", ", quoted) + "]";
  }

  private static void link(final Map<String, MutableVertex> v, final String from, final String type, final String to) {
    v.get(from).newEdge(type, v.get(to)).save();
  }
}
