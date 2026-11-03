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
import com.arcadedb.query.opencypher.procedures.CypherProcedureRegistry;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code identity.compare} and {@code identity.cohesion} (docs/spork/04-identity-resolution-design.md) over the ad graph of
 * {@link IdentityFixture}: A = [a1, a2] and B = [b1, b2] are one person, C = [c1, c2] posts from Los Angeles an hour after
 * A posts from New York, x is a Chicago ad 400 days later.
 */
class IdentityProceduresTest extends TestHelper {
  private static final long DAY = IdentityFixture.DAY;
  private static final long T0  = IdentityFixture.T0;

  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    rids.putAll(IdentityFixture.build(database));
  }

  // ---- identity.compare ----

  @Test
  void compareRecognisesOnePersonAcrossTwoClusters() {
    final Result r = compare(sel("a1", "a2"), sel("b1", "b2"), options());

    assertThat(r.<String>getProperty("verdict")).isIn("SAME", "LIKELY_SAME");
    assertThat(num(r, "probability")).isGreaterThan(0.9);
    assertThat(num(r, "lower")).isLessThan(num(r, "probability"));
    assertThat(num(r, "upper")).isGreaterThan(num(r, "probability"));
    assertThat(num(r, "matchWeight")).isGreaterThan(10.0);
    assertThat(num(r, "matchWeightStdDev")).isGreaterThan(0.0);
    assertThat(((Number) r.getProperty("sizeA")).intValue()).isEqualTo(2);
    assertThat(((Number) r.getProperty("sizeB")).intValue()).isEqualTo(2);

    final List<Map<String, Object>> evidence = r.getProperty("evidence");
    assertThat(evidence).extracting(e -> e.get("identifier")).contains(rids.get("p1"), rids.get("e1"));
    final Map<String, Object> phone = evidence.stream().filter(e -> rids.get("p1").equals(e.get("identifier"))).findFirst().orElseThrow();
    assertThat(phone.get("type")).isEqualTo("SHARED");
    assertThat(phone.get("kind")).isEqualTo("Phone");
    assertThat(phone.get("countA")).isEqualTo(2);
    assertThat(phone.get("countB")).isEqualTo(1);
    assertThat(((Number) phone.get("gapDays")).doubleValue()).isEqualTo(1.0);

    final Map<String, Object> temporal = r.getProperty("temporal");
    assertThat(temporal.get("firstA")).isEqualTo(T0);
    assertThat(temporal.get("lastB")).isEqualTo(T0 + 5 * DAY);
    assertThat(((Number) temporal.get("gapDays")).doubleValue()).isEqualTo(1.0);
    assertThat(temporal.get("conflicts")).isEqualTo(0);
  }

  @Test
  void compareSeparatesTwoPeopleWhoPostFarApartAtTheSameTime() {
    final Result r = compare(sel("a1", "a2"), sel("c1", "c2"), options());

    assertThat(r.<String>getProperty("verdict")).isIn("DIFFERENT", "LIKELY_DIFFERENT");
    assertThat(num(r, "matchWeight")).isLessThan(0.0);
    assertThat(num(r, "upper")).isLessThan(0.5);

    final List<Map<String, Object>> evidence = r.getProperty("evidence");
    assertThat(evidence).extracting(e -> e.get("type")).containsOnly("CONFLICT", "DISJOINT");
    final Map<String, Object> temporal = r.getProperty("temporal");
    assertThat(temporal.get("conflicts")).isEqualTo(2);
    assertThat(temporal.get("comparablePairs")).isEqualTo(2);
  }

  @Test
  void compareIsSymmetric() {
    final Result ab = compare(sel("a1", "a2", "x"), sel("b1", "c2"), options());
    final Result ba = compare(sel("b1", "c2"), sel("a1", "a2", "x"), options());

    assertThat(num(ab, "logOdds")).isCloseTo(num(ba, "logOdds"), within(1e-12));
    assertThat(num(ab, "lower")).isCloseTo(num(ba, "lower"), within(1e-12));
  }

  @Test
  void aHubFrequencyTakesTheWeightOutOfASharedIdentifier() {
    database.transaction(() -> rids.get("p1").asVertex().modify().set("recordCount", 9_000_000).save());

    final Map<String, Object> opts = options();
    opts.put("frequencyProperty", "recordCount");
    final Result r = compare(sel("a1", "a2"), sel("b1"), opts);

    final List<Map<String, Object>> evidence = r.getProperty("evidence");
    assertThat(evidence).singleElement().satisfies(e -> {
      assertThat(e.get("identifier")).isEqualTo(rids.get("p1"));
      assertThat(((Number) e.get("weight")).doubleValue()).isZero();
    });
    assertThat(r.<String>getProperty("verdict")).isEqualTo("INSUFFICIENT_EVIDENCE");
  }

  @Test
  void theDegreeStandsInForTheFrequencyWhenNoPropertyIsNamed() {
    // p1 has three ads, so its degree is the same 3 its recordCount says
    final Map<String, Object> byProperty = options();
    byProperty.put("frequencyProperty", "recordCount");

    assertThat(num(compare(sel("a1"), sel("b1"), options()), "logOdds"))
        .isCloseTo(num(compare(sel("a1"), sel("b1"), byProperty), "logOdds"), within(1e-12));
  }

  @Test
  void kindSettingsChangeTheWeightOfTheirKind() {
    final double base = num(compare(sel("a2"), sel("b1"), options()), "matchWeight");

    final Map<String, Object> opts = options();
    opts.put("kinds", Map.of("Phone", Map.of("m", 0.95)));
    assertThat(num(compare(sel("a2"), sel("b1"), opts), "matchWeight")).isGreaterThan(base);
  }

  @Test
  void ridStringsAndStaleMembersAreAccepted() {
    final List<Object> a = sel("a1", "gone");
    a.add(rids.get("a2").toString());
    final Result r = compare(a, sel("b1", "b2"), options());

    assertThat(((Number) r.getProperty("sizeA")).intValue()).isEqualTo(2);
  }

  @Test
  void membersInBothClustersAreSetAsideAndCounted() {
    final Result r = compare(sel("a1", "a2"), sel("a2", "b1"), options());

    assertThat(((Number) r.getProperty("sharedMembers")).intValue()).isEqualTo(1);
    assertThat(((Number) r.getProperty("sizeA")).intValue()).isEqualTo(1);
    assertThat(((Number) r.getProperty("sizeB")).intValue()).isEqualTo(1);
  }

  @Test
  void compareRefusesBadInput() {
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), Map.of("viaa", "HAS_PHONE")))
        .hasStackTraceContaining("unknown options key 'viaa'");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), Map.of("via", "HAS_FAX")))
        .hasStackTraceContaining("HAS_FAX");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), Map.of("kinds", Map.of("Fax", Map.of("m", 0.5)))))
        .hasStackTraceContaining("Fax");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), Map.of("kinds", Map.of("Phone", Map.of("mm", 0.5)))))
        .hasStackTraceContaining("unknown kinds.Phone key 'mm'");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), Map.of("prior", "high")))
        .hasStackTraceContaining("prior must be a number");
    assertThatThrownBy(() -> compare(sel("gone"), sel("b1"), null))
        .hasStackTraceContaining("clusterA holds no existing node");
  }

  // ---- identity.cohesion ----

  @Test
  void cohesionPutsTheMemberThatDoesNotBelongFirst() {
    final Map<String, Object> params = new HashMap<>();
    params.put("cluster", sel("a1", "a2", "b1", "b2", "x"));
    params.put("opts", options());
    final List<Result> rows = rows(
        "CALL identity.cohesion($cluster, $opts) YIELD member, probability, lower, upper, matchWeight, verdict, evidence "
            + "RETURN member, probability, lower, upper, matchWeight, verdict, evidence", params);

    assertThat(rows).hasSize(5);
    assertThat(rows.getFirst().<RID>getProperty("member")).isEqualTo(rids.get("x"));
    assertThat(num(rows.getFirst(), "probability")).isLessThan(0.5);
    for (final Result row : rows.subList(1, rows.size()))
      assertThat(num(row, "probability")).isGreaterThan(0.9);
    for (int i = 1; i < rows.size(); i++)
      assertThat(num(rows.get(i), "probability")).isGreaterThanOrEqualTo(num(rows.get(i - 1), "probability"));
  }

  @Test
  void cohesionHonoursLimitAndRefusesASingleMember() {
    final Map<String, Object> params = new HashMap<>();
    params.put("cluster", sel("a1", "a2", "b1", "b2", "x"));
    final Map<String, Object> opts = options();
    opts.put("limit", 2);
    params.put("opts", opts);
    assertThat(rows("CALL identity.cohesion($cluster, $opts) YIELD member RETURN member", params)).hasSize(2);

    params.put("cluster", sel("a1", "gone"));
    assertThatThrownBy(() -> rows("CALL identity.cohesion($cluster, $opts) YIELD member RETURN member", params))
        .hasStackTraceContaining("at least 2 existing nodes");
  }

  @Test
  void proceduresAreRegistered() {
    assertThat(CypherProcedureRegistry.hasProcedure("identity.compare")).isTrue();
    assertThat(CypherProcedureRegistry.hasProcedure("identity.cohesion")).isTrue();
  }

  // ---- helpers ----

  private static Map<String, Object> options() {
    return IdentityFixture.options();
  }

  private Result compare(final List<Object> a, final List<Object> b, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("a", a);
    params.put("b", b);
    params.put("opts", options);
    final List<Result> rows = rows("CALL identity.compare($a, $b, $opts) "
        + "YIELD probability, lower, upper, logOdds, matchWeight, matchWeightStdDev, verdict, sizeA, sizeB, sharedMembers, evidence, temporal "
        + "RETURN probability, lower, upper, logOdds, matchWeight, matchWeightStdDev, verdict, sizeA, sizeB, sharedMembers, evidence, temporal",
        params);
    assertThat(rows).hasSize(1);
    return rows.getFirst();
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

  private List<Result> rows(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }
}
