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
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 (docs/spork/03-f1-selections-design.md): {@code selection.venn} and {@code selection.compare}, the engine side of Recon's
 * compare feature. The fixture is a small ad graph:
 * <pre>
 *   a1 -HAS_PHONE-> p1, a1 -HAS_EMAIL-> e1
 *   a2 -HAS_PHONE-> p1, a2 -HAS_PHONE-> p2
 *   a3 -HAS_PHONE-> p2
 *   a4 -HAS_PHONE-> p1, a4 -HAS_PHONE-> p3
 *   a5 -HAS_PHONE-> p3, a5 -HAS_EMAIL-> e1
 *   a6 -HAS_PHONE-> p2, a6 -HAS_PHONE-> p4, a6 -OTHER-> a1
 * </pre>
 * With the selections S0 = [a1, a2], S1 = [a3, a4], S2 = [a5, a6] and {@code via} the identifier edges, the identifiers
 * fall in these regions: p2 in all three, p1 in S0+S1, p3 in S1+S2, e1 in S0+S2, p4 in S2 alone.
 */
class SelectionProceduresTest extends TestHelper {
  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("Ad");
    database.getSchema().createVertexType("Phone");
    database.getSchema().createVertexType("Email");
    database.getSchema().createEdgeType("HAS_PHONE");
    database.getSchema().createEdgeType("HAS_EMAIL");
    database.getSchema().createEdgeType("OTHER");

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
    });

    // A member that no longer exists: a saved selection can go stale
    database.transaction(() -> rids.get("gone").asVertex().delete());
  }

  // ---- selection.venn ----

  @Test
  void vennOverTheMembersThemselves() {
    // S0 = [a1, a2, a3], S1 = [a3, a4], S2 = [a1, a3, a5]
    final Map<Integer, Long> regions = venn(List.of(sel("a1", "a2", "a3"), sel("a3", "a4"), sel("a1", "a3", "a5")), null);

    assertThat(regions).containsOnly(
        Map.entry(0b001, 1L), // a2
        Map.entry(0b010, 1L), // a4
        Map.entry(0b100, 1L), // a5
        Map.entry(0b101, 1L), // a1
        Map.entry(0b111, 1L)); // a3
  }

  @Test
  void vennOverTheIdentifiersReachedVia() {
    final Map<Integer, Long> regions = venn(threeAdSelections(),
        Map.of("via", List.of("HAS_PHONE", "HAS_EMAIL"), "direction", "OUT"));

    assertThat(regions).containsOnly(
        Map.entry(0b011, 1L), // p1
        Map.entry(0b111, 1L), // p2
        Map.entry(0b110, 1L), // p3
        Map.entry(0b101, 1L), // e1
        Map.entry(0b100, 1L)); // p4
  }

  @Test
  void vennRowsNameTheSelectionsOfTheirRegion() {
    final List<Result> rows = rows("CALL selection.venn($sels, {via: 'HAS_EMAIL', direction: 'OUT'}) "
        + "YIELD mask, selections, count RETURN mask, selections, count", Map.of("sels", threeAdSelections()));

    assertThat(rows).hasSize(1);
    assertThat(((Number) rows.getFirst().getProperty("mask")).intValue()).isEqualTo(0b101);
    assertThat((List<Object>) rows.getFirst().getProperty("selections")).containsExactly(0, 2);
    assertThat(((Number) rows.getFirst().getProperty("count")).longValue()).isEqualTo(1L);
  }

  @Test
  void viaTheWildcardFollowsEveryEdgeType() {
    // a6 -OTHER-> a1 puts a1 in S2's neighbourhood as well
    final Map<Integer, Long> regions = venn(threeAdSelections(), Map.of("via", "*", "direction", "OUT"));
    assertThat(regions).containsEntry(0b100, 2L); // p4 and a1
  }

  // ---- selection.compare ----

  @Test
  void compareReturnsTheSharedIdentifiersMostSharedFirst() {
    final List<Result> rows = compare(threeAdSelections(), Map.of("via", List.of("HAS_PHONE", "HAS_EMAIL"), "direction", "OUT"));

    assertThat(names(rows)).hasSize(4);
    assertThat(names(rows).getFirst()).isEqualTo("p2");
    assertThat(names(rows)).containsExactlyInAnyOrder("p2", "p1", "p3", "e1");
    assertThat(((Number) rows.getFirst().getProperty("sharedBy")).intValue()).isEqualTo(3);
    assertThat(((Number) rows.getFirst().getProperty("mask")).intValue()).isEqualTo(0b111);
  }

  @Test
  void compareCountsAndNamesTheMembersBehindEachIdentifier() {
    final Result p1 = byName(compare(threeAdSelections(),
        Map.of("via", List.of("HAS_PHONE", "HAS_EMAIL"), "direction", "OUT")), "p1");

    assertThat(longs(p1.getProperty("counts"))).containsExactly(2L, 1L, 0L);
    final List<List<Object>> members = p1.getProperty("members");
    assertThat(members).hasSize(3);
    assertThat(members.get(0)).containsExactly(rids.get("a1"), rids.get("a2"));
    assertThat(members.get(1)).containsExactly(rids.get("a4"));
    assertThat(members.get(2)).isEmpty();
  }

  @Test
  void memberCapBoundsTheEvidenceButNotTheCounts() {
    final Result p1 = byName(compare(threeAdSelections(),
        Map.of("via", "HAS_PHONE", "direction", "OUT", "memberCap", 1)), "p1");

    assertThat(longs(p1.getProperty("counts"))).containsExactly(2L, 1L, 0L);
    final List<List<Object>> members = p1.getProperty("members");
    assertThat(members.get(0)).containsExactly(rids.get("a1"));
  }

  @Test
  void aZeroMemberCapSkipsTheEvidencePass() {
    final Result p2 = byName(compare(threeAdSelections(),
        Map.of("via", "HAS_PHONE", "direction", "OUT", "memberCap", 0)), "p2");

    assertThat((Object) p2.getProperty("counts")).isNull();
    assertThat((Object) p2.getProperty("members")).isNull();
  }

  @Test
  void minSharedSelectsHowManySelectionsAnElementMustBeIn() {
    final Map<String, Object> via = Map.of("via", List.of("HAS_PHONE", "HAS_EMAIL"), "direction", "OUT");

    assertThat(names(compare(threeAdSelections(), with(via, "minShared", 3)))).containsExactly("p2");

    final List<String> all = names(compare(threeAdSelections(), with(via, "minShared", 1)));
    assertThat(all).containsExactlyInAnyOrder("p1", "p2", "p3", "p4", "e1");
    assertThat(all.getLast()).isEqualTo("p4"); // the distinct element comes after every shared one
  }

  @Test
  void limitKeepsTheMostSharedRows() {
    final List<String> names = names(compare(threeAdSelections(),
        Map.of("via", List.of("HAS_PHONE", "HAS_EMAIL"), "direction", "OUT", "limit", 2)));

    assertThat(names).hasSize(2);
    assertThat(names.getFirst()).isEqualTo("p2");
  }

  @Test
  void compareOverTheMembersThemselves() {
    final List<Result> rows = compare(List.of(sel("a1", "a2", "a3"), sel("a3", "a4"), sel("a1", "a3", "a5")), null);

    assertThat(names(rows)).containsExactly("a3", "a1");
    assertThat(longs(rows.getFirst().getProperty("counts"))).containsExactly(1L, 1L, 1L);
  }

  @Test
  void compareAcceptsNodesAsSelectionMembers() {
    final List<Result> rows = rows("MATCH (a:Ad) WHERE a.name IN ['a1', 'a2'] WITH collect(a) AS s0 "
        + "MATCH (b:Ad) WHERE b.name IN ['a3', 'a4'] WITH s0, collect(b) AS s1 "
        + "CALL selection.compare([s0, s1], {via: 'HAS_PHONE', direction: 'OUT'}) YIELD node, sharedBy "
        + "RETURN node.name AS name, sharedBy", Map.of());

    assertThat(rows).hasSize(2);
    final List<String> names = new ArrayList<>();
    for (final Result r : rows)
      names.add(r.getProperty("name"));
    assertThat(names).containsExactlyInAnyOrder("p1", "p2");
  }

  @Test
  void staleMembersAreSkipped() {
    final List<Object> s0 = new ArrayList<>(sel("a1", "a2"));
    s0.add(rids.get("gone").toString());
    final Map<Integer, Long> regions = venn(List.of(s0, sel("a3", "a4")), Map.of("via", "HAS_PHONE", "direction", "OUT"));

    assertThat(regions).containsOnly(
        Map.entry(0b11, 2L), // p1, p2
        Map.entry(0b10, 1L)); // p3
  }

  // ---- refused calls ----

  @Test
  void refusesFewerThanTwoSelections() {
    assertThatThrownBy(() -> venn(List.of(sel("a1")), null))
        .hasStackTraceContaining("selection.venn(): selections must hold 2 to 8 lists, got 1");
  }

  @Test
  void refusesMoreThanEightSelections() {
    final List<Object> nine = new ArrayList<>();
    for (int i = 0; i < 9; i++)
      nine.add(sel("a1"));
    assertThatThrownBy(() -> compare(nine, null))
        .hasStackTraceContaining("selection.compare(): selections must hold 2 to 8 lists, got 9");
  }

  @Test
  void refusesAnUnknownOptionKey() {
    assertThatThrownBy(() -> compare(threeAdSelections(), Map.of("vai", "HAS_PHONE")))
        .hasStackTraceContaining("selection.compare(): unknown options key 'vai'");
  }

  @Test
  void refusesAnUnknownEdgeType() {
    assertThatThrownBy(() -> venn(threeAdSelections(), Map.of("via", "HAS_FONE")))
        .hasStackTraceContaining("HAS_FONE");
  }

  @Test
  void refusesAMemberThatIsNotARecordIdentity() {
    assertThatThrownBy(() -> venn(List.of(List.of("not-a-rid"), sel("a1")), null))
        .hasStackTraceContaining("selection.venn(): selections[0] holds 'not-a-rid'");
  }

  // ---- helpers ----

  private List<Object> threeAdSelections() {
    return List.of(sel("a1", "a2"), sel("a3", "a4"), sel("a5", "a6"));
  }

  /** A selection as RID strings, the shape a client sends; the node form is covered by {@link #compareAcceptsNodesAsSelectionMembers}. */
  private List<Object> sel(final String... names) {
    final List<Object> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return list;
  }

  private Map<Integer, Long> venn(final List<Object> selections, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("sels", selections);
    params.put("opts", options);
    final Map<Integer, Long> regions = new HashMap<>();
    for (final Result r : rows("CALL selection.venn($sels, $opts) YIELD mask, count RETURN mask, count", params))
      regions.put(((Number) r.getProperty("mask")).intValue(), ((Number) r.getProperty("count")).longValue());
    return regions;
  }

  private List<Result> compare(final List<Object> selections, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("sels", selections);
    params.put("opts", options);
    return rows("CALL selection.compare($sels, $opts) YIELD node, mask, selections, sharedBy, counts, members "
        + "RETURN node.name AS name, mask, selections, sharedBy, counts, members", params);
  }

  private List<Result> rows(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }

  private static List<String> names(final List<Result> rows) {
    final List<String> names = new ArrayList<>();
    for (final Result r : rows)
      names.add(r.getProperty("name"));
    return names;
  }

  private static Result byName(final List<Result> rows, final String name) {
    for (final Result r : rows)
      if (name.equals(r.getProperty("name")))
        return r;
    throw new AssertionError("no row for " + name + " in " + names(rows));
  }

  private static List<Long> longs(final Object list) {
    final List<Long> values = new ArrayList<>();
    for (final Object o : (List<?>) list)
      values.add(((Number) o).longValue());
    return values;
  }

  private static Map<String, Object> with(final Map<String, Object> base, final String key, final Object value) {
    final Map<String, Object> map = new HashMap<>(base);
    map.put(key, value);
    return map;
  }

  private static void link(final Map<String, MutableVertex> v, final String from, final String type, final String to) {
    v.get(from).newEdge(type, v.get(to)).save();
  }
}
