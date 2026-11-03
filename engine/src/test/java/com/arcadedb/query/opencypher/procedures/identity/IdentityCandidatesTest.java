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
package com.arcadedb.query.opencypher.procedures.identity;

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.opencypher.procedures.CypherProcedureRegistry;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code identity.candidates} (docs/spork/04-identity-resolution-design.md, G3): the groups outside a cluster most likely to
 * be the same individual. Times are days from T0, every ad is in New York; {@code persona} is indexed, {@code nick} is not:
 * <pre>
 *   a1 day 0    P0 -> p1, e1        the footprint A = [a1, a2]
 *   a2 day 2    P0 -> p1, hub
 *   b1 day 3    P1 -> p1            shares p1 a day after A
 *   b2 day 5    P1 -> e1            shares e1 five days after A
 *   b3 day 6    P1 -> e5            in P1, but reaches nothing of A
 *   d1 day 1000 P2 -> p1            shares p1 almost three years later
 *   c1 day 0    P3 -> p9            unrelated
 *   r0..r29     (no time) -> hub    a phone on 31 ads
 * </pre>
 */
class IdentityCandidatesTest extends TestHelper {
  private static final long DAY = 86_400_000L;
  private static final long T0  = 1_700_000_000_000L;

  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("Ad").createProperty("persona", String.class);
    database.getSchema().getType("Ad").createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "persona");
    database.getSchema().createVertexType("Phone");
    database.getSchema().createVertexType("Email");
    database.getSchema().createEdgeType("HAS_PHONE");
    database.getSchema().createEdgeType("HAS_EMAIL");

    database.transaction(() -> {
      final Map<String, MutableVertex> v = new HashMap<>();
      ad(v, "a1", T0, "P0");
      ad(v, "a2", T0 + 2 * DAY, "P0");
      ad(v, "b1", T0 + 3 * DAY, "P1");
      ad(v, "b2", T0 + 5 * DAY, "P1");
      ad(v, "b3", T0 + 6 * DAY, "P1");
      ad(v, "d1", T0 + 1000 * DAY, "P2");
      ad(v, "c1", T0, "P3");
      for (final String name : List.of("p1", "p9", "hub"))
        v.put(name, database.newVertex("Phone").set("name", name).save());
      for (final String name : List.of("e1", "e5"))
        v.put(name, database.newVertex("Email").set("name", name).save());

      link(v, "a1", "HAS_PHONE", "p1");
      link(v, "a1", "HAS_EMAIL", "e1");
      link(v, "a2", "HAS_PHONE", "p1");
      link(v, "a2", "HAS_PHONE", "hub");
      link(v, "b1", "HAS_PHONE", "p1");
      link(v, "b2", "HAS_EMAIL", "e1");
      link(v, "b3", "HAS_EMAIL", "e5");
      link(v, "d1", "HAS_PHONE", "p1");
      link(v, "c1", "HAS_PHONE", "p9");
      for (int i = 0; i < 30; i++) {
        final MutableVertex r = database.newVertex("Ad").set("name", "r" + i).set("nick", "R").save();
        r.newEdge("HAS_PHONE", v.get("hub"));
        v.put("r" + i, r);
      }

      for (final Map.Entry<String, MutableVertex> e : v.entrySet())
        rids.put(e.getKey(), e.getValue().getIdentity());
    });
  }

  @Test
  void recordModeRanksTheRecordsOutsideTheFootprint() {
    final List<Result> rows = candidates(sel("a1", "a2"), options(10));

    assertThat(rows).extracting(r -> r.<RID>getProperty("candidate"))
        .containsExactly(rids.get("b1"), rids.get("b2"), rids.get("d1"));
    for (int i = 1; i < rows.size(); i++)
      assertThat(num(rows.get(i), "logOdds")).isLessThan(num(rows.get(i - 1), "logOdds"));

    final Result b1 = rows.getFirst();
    assertThat(b1.<List<RID>>getProperty("members")).containsExactly(rids.get("b1"));
    assertThat(((Number) b1.getProperty("size")).intValue()).isEqualTo(1);
    assertThat(((Number) b1.getProperty("blockedOn")).intValue()).isEqualTo(1);
    assertThat(b1.<Boolean>getProperty("complete")).isTrue();
    assertThat(b1.<String>getProperty("verdict")).isIn("SAME", "LIKELY_SAME");
    assertThat(num(b1, "lower")).isLessThan(num(b1, "probability"));
    final List<Map<String, Object>> evidence = b1.getProperty("evidence");
    assertThat(evidence.getFirst().get("identifier")).isEqualTo(rids.get("p1"));
  }

  @Test
  void identifiersAboveMaxFrequencyAreNotBlockedOn() {
    final Map<String, Object> opts = options(1000);
    opts.put("limit", 100);
    final List<Result> rows = candidates(sel("a1", "a2"), opts);

    // The hub phone (31 ads) is now under the cap, so its 30 other ads become candidates too
    assertThat(rows).hasSize(33);
    assertThat(rows).extracting(r -> r.<RID>getProperty("candidate")).doesNotContain(rids.get("a1"), rids.get("a2"), rids.get("c1"));

    opts.put("limit", 2);
    assertThat(candidates(sel("a1", "a2"), opts)).hasSize(2);
  }

  @Test
  void maxScoredKeepsTheCandidatesBlockedOnTheMostIdentifiers() {
    final Map<String, Object> opts = options(1000);
    opts.put("maxScored", 3);
    opts.put("limit", 100);
    // b1 and d1 share p1, b2 shares e1, the hub ads share only the hub: all are blocked on one identifier, so the cap keeps
    // the first three in RID order
    final List<Result> rows = candidates(sel("a1", "a2"), opts);
    assertThat(rows).hasSize(3);
  }

  @Test
  void maxCandidatesBoundsTheRecordsCollected() {
    final Map<String, Object> opts = options(1000);
    opts.put("maxCandidates", 2);
    opts.put("limit", 100);

    assertThat(candidates(sel("a1", "a2"), opts)).hasSize(2);
  }

  @Test
  void groupModeLoadsWholeGroupsThroughTheIndex() {
    final Map<String, Object> opts = options(10);
    opts.put("groupBy", "persona");
    final List<Result> rows = candidates(sel("a1", "a2"), opts);

    // P0 is the footprint's own group: nothing left once its members are set aside
    assertThat(rows).extracting(r -> r.<Object>getProperty("candidate")).containsExactly("P1", "P2");

    final Result p1 = rows.getFirst();
    assertThat(p1.<List<RID>>getProperty("members")).containsExactlyInAnyOrder(rids.get("b1"), rids.get("b2"), rids.get("b3"));
    assertThat(((Number) p1.getProperty("size")).intValue()).isEqualTo(3);
    assertThat(((Number) p1.getProperty("blockedOn")).intValue()).isEqualTo(2);
    assertThat(p1.<Boolean>getProperty("complete")).isTrue();
    assertThat(((Number) p1.getProperty("overlap")).intValue()).isZero();
    assertThat(p1.<String>getProperty("verdict")).isIn("SAME", "LIKELY_SAME");
  }

  @Test
  void groupModeReportsTheMembersOfTheFootprintItContains() {
    database.transaction(() -> rids.get("a2").asVertex().modify().set("persona", "P1").save());

    final Map<String, Object> opts = options(10);
    opts.put("groupBy", "persona");
    final Result p1 = candidates(sel("a1", "a2"), opts).getFirst();

    assertThat(p1.<Object>getProperty("candidate")).isEqualTo("P1");
    assertThat(((Number) p1.getProperty("overlap")).intValue()).isEqualTo(1);
    assertThat(p1.<List<RID>>getProperty("members")).doesNotContain(rids.get("a2"));
  }

  @Test
  void groupModeWithoutAnIndexScoresOnlyTheRecordsItReached() {
    final Map<String, Object> opts = options(1000);
    opts.put("groupBy", "nick");
    final List<Result> rows = candidates(sel("a1", "a2"), opts);

    final Result r = rows.stream().filter(row -> "R".equals(row.getProperty("candidate"))).findFirst().orElseThrow();
    assertThat(r.<Boolean>getProperty("complete")).isFalse();
    assertThat(((Number) r.getProperty("size")).intValue()).isEqualTo(30);
    // Records without the property are groups of their own, keyed by their RID
    assertThat(rows).extracting(row -> row.<Object>getProperty("candidate")).contains(rids.get("b1"));
  }

  @Test
  void candidatesRefusesBadInput() {
    assertThatThrownBy(() -> candidates(sel("a1"), Map.of("groupBy", 3))).hasStackTraceContaining("groupBy must be a property name");
    assertThatThrownBy(() -> candidates(sel("a1"), Map.of("maxFrequency", 0))).hasStackTraceContaining("maxFrequency");
    assertThatThrownBy(() -> candidates(sel("a1"), Map.of("limitt", 3))).hasStackTraceContaining("unknown options key 'limitt'");
    assertThatThrownBy(() -> candidates(new ArrayList<>(), null)).hasStackTraceContaining("cluster holds no existing node");
  }

  @Test
  void candidatesIsRegistered() {
    assertThat(CypherProcedureRegistry.hasProcedure("identity.candidates")).isTrue();
  }

  // ---- helpers ----

  private static Map<String, Object> options(final int maxFrequency) {
    final Map<String, Object> opts = new HashMap<>();
    opts.put("via", List.of("HAS_PHONE", "HAS_EMAIL"));
    opts.put("timeProperty", "ts");
    opts.put("population", 10_000_000);
    opts.put("maxFrequency", maxFrequency);
    return opts;
  }

  private List<Result> candidates(final List<Object> cluster, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("cluster", cluster);
    params.put("opts", options);
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher",
        "CALL identity.candidates($cluster, $opts) YIELD candidate, members, size, complete, overlap, blockedOn, "
            + "probability, lower, upper, logOdds, matchWeight, verdict, evidence "
            + "RETURN candidate, members, size, complete, overlap, blockedOn, probability, lower, upper, logOdds, matchWeight, "
            + "verdict, evidence", params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }

  private static double num(final Result r, final String name) {
    return ((Number) r.getProperty(name)).doubleValue();
  }

  private List<Object> sel(final String... names) {
    final List<Object> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return list;
  }

  private void ad(final Map<String, MutableVertex> v, final String name, final long ts, final String persona) {
    v.put(name, database.newVertex("Ad").set("name", name).set("ts", ts).set("persona", persona).save());
  }

  private static void link(final Map<String, MutableVertex> v, final String from, final String type, final String to) {
    v.get(from).newEdge(type, v.get(to));
  }
}
