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
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.utility.RidBitmap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 2 (docs/spork/03-f1-selections-design.md, 2.3): saved selections. The fixture is a small ad graph:
 * <pre>
 *   a1 -HAS_PHONE-> p1      a2 -HAS_PHONE-> p1, p2      a3 -HAS_PHONE-> p2      a4 -HAS_PHONE-> p1, p3
 * </pre>
 */
class SelectionHandlesTest extends TestHelper {
  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("Ad");
    database.getSchema().createVertexType("Phone");
    database.getSchema().createEdgeType("HAS_PHONE");

    database.transaction(() -> {
      final Map<String, MutableVertex> v = new HashMap<>();
      for (final String name : List.of("a1", "a2", "a3", "a4"))
        v.put(name, database.newVertex("Ad").set("name", name).save());
      for (final String name : List.of("p1", "p2", "p3"))
        v.put(name, database.newVertex("Phone").set("name", name).save());
      v.get("a1").newEdge("HAS_PHONE", v.get("p1")).save();
      v.get("a2").newEdge("HAS_PHONE", v.get("p1")).save();
      v.get("a2").newEdge("HAS_PHONE", v.get("p2")).save();
      v.get("a3").newEdge("HAS_PHONE", v.get("p2")).save();
      v.get("a4").newEdge("HAS_PHONE", v.get("p1")).save();
      v.get("a4").newEdge("HAS_PHONE", v.get("p3")).save();
      for (final Map.Entry<String, MutableVertex> e : v.entrySet())
        rids.put(e.getKey(), e.getValue().getIdentity());
    });
  }

  // ---- save and resolve ----

  @Test
  void savedSelectionsAreComparedByName() {
    save("g0", sel("a1", "a2"), null);
    save("g1", sel("a3", "a4"), null);

    final List<String> shared = new ArrayList<>();
    for (final Result r : read("CALL selection.compare(['g0', 'g1'], {via: 'HAS_PHONE', direction: 'OUT'}) YIELD node "
        + "RETURN node.name AS name", Map.of()))
      shared.add(r.getProperty("name"));
    assertThat(shared).containsExactlyInAnyOrder("p1", "p2");
  }

  @Test
  void handlesAndListsMixInOneCall() {
    save("g0", sel("a1", "a2"), null);

    final List<Result> rows = read("CALL selection.venn(['g0', $s1]) YIELD mask, count RETURN mask, count",
        Map.of("s1", sel("a2", "a3")));
    final Map<Integer, Long> regions = new HashMap<>();
    for (final Result r : rows)
      regions.put(((Number) r.getProperty("mask")).intValue(), ((Number) r.getProperty("count")).longValue());
    assertThat(regions).containsOnly(Map.entry(0b01, 1L), Map.entry(0b11, 1L), Map.entry(0b10, 1L));
  }

  @Test
  void saveYieldsTheHandle() {
    final Result row = save("g0", sel("a1", "a2", "a2"), null);

    assertThat((String) row.getProperty("name")).isEqualTo("g0");
    assertThat(((Number) row.getProperty("cardinality")).longValue()).isEqualTo(2L);
    final long createdAt = ((Number) row.getProperty("createdAt")).longValue();
    final long expiresAt = ((Number) row.getProperty("expiresAt")).longValue();
    assertThat(expiresAt - createdAt).isEqualTo(SelectionStore.DEFAULT_TTL_SECONDS * 1000L);
  }

  @Test
  void aCypherQueryCanBeTheSource() {
    final Result row = save("on-p1", "MATCH (a:Ad)-[:HAS_PHONE]->(:Phone {name: $phone}) RETURN a",
        Map.of("params", Map.of("phone", "p1")));
    assertThat(((Number) row.getProperty("cardinality")).longValue()).isEqualTo(3L);

    final Result handle = get("on-p1");
    assertThat((String) handle.getProperty("source")).isEqualTo("MATCH (a:Ad)-[:HAS_PHONE]->(:Phone {name: $phone}) RETURN a");
    assertThat((String) handle.getProperty("language")).isEqualTo("opencypher");
    assertThat((Map<String, Object>) handle.getProperty("params")).containsEntry("phone", "p1");
    assertThat(memberNames("on-p1", Map.of())).containsExactly("a1", "a2", "a4");
  }

  @Test
  void aSqlQueryCanBeTheSourceAndListsInRowsAreFlattened() {
    final Result row = save("by-sql", "SELECT FROM Ad WHERE name IN :names",
        Map.of("language", "sql", "params", Map.of("names", List.of("a3", "a4"))));
    assertThat(((Number) row.getProperty("cardinality")).longValue()).isEqualTo(2L);

    final Result collected = save("collected", "MATCH (a:Ad) WHERE a.name IN ['a1', 'a3'] RETURN collect(a) AS ads, 42 AS n",
        null);
    assertThat(((Number) collected.getProperty("cardinality")).longValue()).isEqualTo(2L);
  }

  @Test
  void aSerializedBitmapCanBeTheSource() {
    final RidBitmap bitmap = new RidBitmap();
    bitmap.add(rids.get("a2"));
    bitmap.add(rids.get("a4"));

    final Result row = save("from-bitmap", Map.of("bitmap", Base64.getEncoder().encodeToString(bitmap.toBytes())), null);
    assertThat(((Number) row.getProperty("cardinality")).longValue()).isEqualTo(2L);
    assertThat(memberNames("from-bitmap", Map.of())).containsExactly("a2", "a4");
    assertThat((String) get("from-bitmap").getProperty("source")).isEqualTo("bitmap");
  }

  @Test
  void aSourceMapMustCarryABitmap() {
    assertThatThrownBy(() -> save("bad", Map.of("bits", "AA=="), null))
        .hasStackTraceContaining("selection.save(): a map source must be {bitmap: '<base64>'}");
    assertThatThrownBy(() -> save("bad", Map.of("bitmap", "not base64!"), null))
        .hasStackTraceContaining("selection.save(): source.bitmap is not a base64 RID bitmap");
  }

  @Test
  void aQuerySourceRunsReadOnly() {
    assertThatThrownBy(() -> save("bad", "CREATE (x:Ad {name: 'x'}) RETURN x", null)).isNotNull();
    assertThat(count("SELECT count() AS c FROM Ad WHERE name = 'x'")).isZero();
  }

  @Test
  void membersStreamInRidOrderWithSkipAndLimitAndSkipStaleOnes() {
    save("all", sel("a4", "a3", "a2", "a1"), null);
    assertThat(memberNames("all", Map.of())).containsExactly("a1", "a2", "a3", "a4");
    assertThat(memberNames("all", Map.of("skip", 1, "limit", 2))).containsExactly("a2", "a3");

    database.transaction(() -> rids.get("a2").asVertex().delete());
    assertThat(memberNames("all", Map.of())).containsExactly("a1", "a3", "a4");
  }

  @Test
  void savingOverALiveHandleNeedsReplace() {
    save("g0", sel("a1"), null);
    assertThatThrownBy(() -> save("g0", sel("a1", "a2"), null))
        .hasStackTraceContaining("selection.save(): a selection named 'g0' already exists");

    save("g0", sel("a1", "a2"), Map.of("replace", true));
    assertThat(((Number) get("g0").getProperty("cardinality")).longValue()).isEqualTo(2L);
    assertThat(count("SELECT count() AS c FROM " + SelectionStore.TYPE_NAME)).isEqualTo(1L);
  }

  @Test
  void dropRemovesAHandle() {
    save("g0", sel("a1"), null);

    assertThat((Boolean) write("CALL selection.drop('g0') YIELD dropped RETURN dropped", Map.of()).getFirst()
        .getProperty("dropped")).isTrue();
    assertThat((Boolean) write("CALL selection.drop('g0') YIELD dropped RETURN dropped", Map.of()).getFirst()
        .getProperty("dropped")).isFalse();
    assertThatThrownBy(() -> get("g0")).hasStackTraceContaining("no selection named 'g0'");
  }

  // ---- expiry ----

  @Test
  void anExpiredHandleIsRefusedEverywhere() {
    save("old", sel("a1"), Map.of("ttl", 60));
    expire("old");

    assertThatThrownBy(() -> get("old")).hasStackTraceContaining("selection 'old' expired at");
    assertThatThrownBy(() -> memberNames("old", Map.of())).hasStackTraceContaining("selection 'old' expired at");
    assertThatThrownBy(() -> read("CALL selection.venn(['old', $s]) YIELD count RETURN count", Map.of("s", sel("a1"))))
        .hasStackTraceContaining("selection 'old' expired at");
  }

  @Test
  void anExpiredHandleIsOverwrittenWithoutReplace() {
    save("old", sel("a1"), null);
    expire("old");

    save("old", sel("a1", "a2", "a3"), null);
    assertThat(((Number) get("old").getProperty("cardinality")).longValue()).isEqualTo(3L);
  }

  @Test
  void purgeDeletesOnlyExpiredHandles() {
    save("old1", sel("a1"), null);
    save("old2", sel("a2"), null);
    save("live", sel("a3"), null);
    save("forever", sel("a4"), Map.of("ttl", 0));
    expire("old1");
    expire("old2");

    assertThat(((Number) write("CALL selection.purge() YIELD purged RETURN purged", Map.of()).getFirst()
        .getProperty("purged")).longValue()).isEqualTo(2L);
    assertThat(count("SELECT count() AS c FROM " + SelectionStore.TYPE_NAME)).isEqualTo(2L);
    assertThat((Object) get("forever").getProperty("expiresAt")).isNull();
  }

  @Test
  void writesPurgeExpiredHandlesAsTheyGo() {
    save("old", sel("a1"), null);
    expire("old");

    save("new", sel("a2"), null);
    assertThat(count("SELECT count() AS c FROM " + SelectionStore.TYPE_NAME)).isEqualTo(1L);
  }

  // ---- combine ----

  @Test
  void combineAppliesSetAlgebraAcrossHandlesAndLists() {
    save("x", sel("a1", "a2", "a3"), null);
    save("y", sel("a2", "a3", "a4"), null);

    assertThat(combine("and", List.of("x", "y"), "and")).containsExactly("a2", "a3");
    assertThat(combine("or", List.of("x", "y"), "or")).containsExactly("a1", "a2", "a3", "a4");
    assertThat(combine("andNot", List.of("x", "y"), "andNot")).containsExactly("a1");
    assertThat(combine("andNot", List.of("x", "y", sel("a1")), "andNot3")).isEmpty();
    // a2 and a3 are in all three inputs and a4 in one, odd counts; a1 is in two
    assertThat(combine("xor", List.of("x", "y", sel("a1", "a2", "a3")), "xor")).containsExactly("a2", "a3", "a4");

    assertThat((String) get("and").getProperty("source")).isEqualTo("combine:and(x, y)");
  }

  @Test
  void combineRefusesAnUnknownOperator() {
    save("x", sel("a1"), null);
    assertThatThrownBy(() -> combine("nand", List.of("x", "x"), "z"))
        .hasStackTraceContaining("selection.combine(): unknown op 'nand', expected one of and, or, andNot, xor");
  }

  // ---- storage ----

  @Test
  void handlesSurviveAReopen() {
    save("g0", sel("a1", "a3"), Map.of("ttl", 0));
    reopenDatabase();
    assertThat(memberNames("g0", Map.of())).containsExactly("a1", "a3");
  }

  @Test
  void theTypeIsCreatedOnceWithItsIndexesInOneSchemaFrame() {
    final long versionBefore = database.getSchema().getEmbedded().getVersion();
    save("g0", sel("a1"), null);
    final long versionAfter = database.getSchema().getEmbedded().getVersion();
    save("g1", sel("a2"), null);

    assertThat(versionAfter - versionBefore).isEqualTo(1L);
    assertThat(database.getSchema().getEmbedded().getVersion()).isEqualTo(versionAfter);
    final DocumentType type = database.getSchema().getType(SelectionStore.TYPE_NAME);
    assertThat(type.getIndexesByProperties("name")).hasSize(1);
    assertThat(type.getIndexesByProperties("name").getFirst().isUnique()).isTrue();
    assertThat(type.getIndexesByProperties("expiresAt")).hasSize(1);
  }

  @Test
  void aForeignTypeOfTheSameNameIsRefused() {
    database.getSchema().createDocumentType(SelectionStore.TYPE_NAME);
    assertThatThrownBy(() -> save("g0", sel("a1"), null)).hasStackTraceContaining(SelectionStore.TYPE_NAME);
  }

  @Test
  void refusesABlankName() {
    assertThatThrownBy(() -> save(" ", sel("a1"), null)).hasStackTraceContaining("selection.save(): name");
  }

  // ---- helpers ----

  private List<Object> sel(final String... names) {
    final List<Object> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return list;
  }

  private Result save(final String name, final Object source, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("name", name);
    params.put("source", source);
    params.put("opts", options);
    return write("CALL selection.save($name, $source, $opts) YIELD name, cardinality, createdAt, expiresAt "
        + "RETURN name, cardinality, createdAt, expiresAt", params).getFirst();
  }

  private List<String> combine(final String op, final List<Object> inputs, final String name) {
    final Map<String, Object> params = new HashMap<>();
    params.put("op", op);
    params.put("inputs", inputs);
    params.put("name", name);
    write("CALL selection.combine($op, $inputs, $name) YIELD cardinality RETURN cardinality", params);
    return memberNames(name, Map.of());
  }

  private Result get(final String name) {
    return read("CALL selection.get($name) YIELD name, cardinality, createdAt, expiresAt, source, language, params, createdBy "
        + "RETURN name, cardinality, createdAt, expiresAt, source, language, params, createdBy", Map.of("name", name)).getFirst();
  }

  private List<String> memberNames(final String name, final Map<String, Object> options) {
    final List<String> names = new ArrayList<>();
    for (final Result r : read("CALL selection.members($name, $opts) YIELD node RETURN node.name AS name",
        Map.of("name", name, "opts", options)))
      names.add(r.getProperty("name"));
    return names;
  }

  private void expire(final String name) {
    database.transaction(() -> database.command("sql", "UPDATE " + SelectionStore.TYPE_NAME + " SET expiresAt = 1 WHERE name = ?",
        name));
  }

  private long count(final String sql) {
    try (final ResultSet rs = database.query("sql", sql)) {
      return ((Number) rs.next().getProperty("c")).longValue();
    }
  }

  private List<Result> read(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }

  private List<Result> write(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    database.transaction(() -> {
      try (final ResultSet rs = database.command("opencypher", query, params)) {
        while (rs.hasNext())
          rows.add(rs.next());
      }
    });
    return rows;
  }
}
