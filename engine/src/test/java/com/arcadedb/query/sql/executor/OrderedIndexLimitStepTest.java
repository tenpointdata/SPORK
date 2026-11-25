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
package com.arcadedb.query.sql.executor;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WHERE a = :a AND <other predicates> ORDER BY k [ASC|DESC] LIMIT n} with an index on {@code (a, k)} and another index
 * the planner prefers for the other predicates: the ordered index walk races the preferred index and stops after n matches
 * when it wins ({@link OrderedIndexLimitStep}). The answers are checked against the plan the planner made before (the step
 * disabled) and against a filter and sort in Java over every record.
 */
class OrderedIndexLimitStepTest extends TestHelper {
  private static final String[] STATES   = { "CA", "TX", "FL", "NY", "PA", "IL", "OH", "GA" };
  private static final String[] SERVICES = { "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9" };

  private static final String STRUCTURED =
      "SELECT FROM Record WHERE state = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT :limit";

  private void createSchema(final int buckets) {
    database.command("sql", "CREATE VERTEX TYPE Record BUCKETS " + buckets);
    database.command("sql", "CREATE PROPERTY Record.state STRING");
    database.command("sql", "CREATE PROPERTY Record.city STRING");
    database.command("sql", "CREATE PROPERTY Record.age INTEGER");
    database.command("sql", "CREATE PROPERTY Record.tsPosted LONG");
    database.command("sql", "CREATE PROPERTY Record.services LIST OF STRING");
    database.command("sql", "CREATE PROPERTY Record.kind STRING");
    database.command("sql", "CREATE INDEX ON Record (state, tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (age, tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (services BY ITEM) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (kind) NOTUNIQUE");
  }

  /**
   * Records with skewed states and services, ages 18-60, timestamps mostly distinct with a share of ties and of nulls. Every
   * record lists a common service, so the services index holds a large share of the type for it.
   */
  private void load(final int records, final long seed) {
    final Random rnd = new Random(seed);
    database.transaction(() -> {
      for (int i = 0; i < records; i++) {
        final MutableVertex v = database.newVertex("Record");
        v.set("state", STATES[Math.min(STATES.length - 1, (int) Math.abs(rnd.nextGaussian() * 2.5))]);
        v.set("city", "city" + rnd.nextInt(5));
        v.set("age", 18 + rnd.nextInt(43));
        final int roll = rnd.nextInt(100);
        if (roll < 4)
          v.set("tsPosted", null);
        else if (roll < 10)
          v.set("tsPosted", 1_000_000L + rnd.nextInt(50) * 1000L); // ties
        else
          v.set("tsPosted", 1_000_000L + rnd.nextInt(10_000_000));
        final List<String> services = new ArrayList<>();
        services.add(SERVICES[0]);
        final int extra = rnd.nextInt(3);
        for (int s = 0; s < extra; s++) {
          final String service = SERVICES[1 + (int) Math.min(SERVICES.length - 2, Math.abs(rnd.nextGaussian() * 3))];
          if (!services.contains(service))
            services.add(service);
        }
        v.set("services", services);
        v.set("kind", rnd.nextInt(10) == 0 ? "rare" : "common");
        v.save();
      }
    });
  }

  @Test
  void explainShowsTheOrderedWalkRacingThePreferredIndex() {
    createSchema(4);
    load(2_000, 1);
    final String plan = explain(STRUCTURED, params("CA", 18, 60, "s0", 10));
    assertThat(plan).contains("ORDERED INDEX WALK").contains("Record[state,tsPosted]").contains("Record[servicesbyitem]")
        .contains("Record[age,tsPosted]");
    try (final ResultSet rs = database.query("sql", STRUCTURED, params("CA", 18, 60, "s0", 10))) {
      final ExecutionPlan executionPlan = rs.getExecutionPlan().orElseThrow();
      assertThat(executionPlan.getSteps().getFirst()).isInstanceOf(OrderedIndexLimitStep.class);
    }
  }

  @Test
  void theOrderedWalkStopsEarlyWhenTheOtherIndexIsLarge() {
    createSchema(4);
    load(4_000, 2);
    final Map<String, Object> params = params("CA", 18, 60, "s0", 10);
    final OrderedIndexLimitStep step = runAndGetStep(STRUCTURED, params);
    assertThat(step.answeredByOrderedWalk()).isTrue();
    // CA holds about a third of the records and every record lists s0: ten matches are among the first few dozen entries. The
    // walk is an equality lookup, whose first 257 entries GetValueFromIndexEntryStep reads ahead (index only) to serve a small
    // key against one committed state. The two rivals, the services index (4,000 entries for s0) and the age index (every
    // record for 18-60), were counted one entry each per entry walked, and neither ran out
    assertThat(step.orderedEntriesRead()).isLessThanOrEqualTo(257);
    assertThat(step.rivalEntriesCounted()).isEqualTo(2 * step.orderedEntriesRead());
    assertThat(step.answeringIndex()).isEqualTo("Record[state,tsPosted]");
    assertSameAnswer(STRUCTURED, params);
  }

  @Test
  void aTinyOtherIndexWinsTheRace() {
    createSchema(4);
    load(4_000, 3);
    // one record lists the service: the services index answers it at once
    database.transaction(() -> database.newVertex("Record").set("state", "CA", "age", 30, "tsPosted", 5L, "services", List.of("unique"))
        .save());
    final Map<String, Object> params = params("CA", 18, 60, "unique", 10);
    final OrderedIndexLimitStep step = runAndGetStep(STRUCTURED, params);
    assertThat(step.answeredByOrderedWalk()).isFalse();
    assertThat(step.answeringIndex()).isEqualTo("Record[servicesbyitem]");
    assertThat(step.orderedEntriesRead()).isLessThanOrEqualTo(3);
    assertSameAnswer(STRUCTURED, params);
  }

  @Test
  void theSmallestRivalAnswersEvenWhenThePlannerPickedAnother() {
    createSchema(4);
    load(4_000, 12);
    // the planner picks the services index (every record lists s0); one age of 43 is the smallest search: about 93 entries,
    // while ten matches of age 60 in CA are about 430 entries into the walk
    final Map<String, Object> params = params("CA", 60, 60, "s0", 10);
    final OrderedIndexLimitStep step = runAndGetStep(STRUCTURED, params);
    assertThat(step.answeredByOrderedWalk()).isFalse();
    assertThat(step.answeringIndex()).isEqualTo("Record[age,tsPosted]");
    assertThat(step.orderedEntriesRead()).isLessThan(300);
    assertSameAnswer(STRUCTURED, params);
  }

  @Test
  void noMatchIsAnsweredByTheCheaperSide() {
    createSchema(2);
    load(3_000, 4);
    for (final String service : List.of("s0", "s5", "absent"))
      assertSameAnswer(STRUCTURED, params("GA", 59, 60, service, 100));
  }

  @Test
  void randomizedAnswersMatchThePreviousPlanAndAScan() {
    createSchema(5);
    load(6_000, 5);
    final Random rnd = new Random(55);
    final String[] shapes = {
        STRUCTURED,
        "SELECT FROM Record WHERE state = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service ORDER BY tsPosted ASC LIMIT :limit",
        "SELECT FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC SKIP 3 LIMIT :limit",
        "SELECT FROM Record WHERE state = :state AND kind = 'rare' AND age >= :ageMin ORDER BY tsPosted ASC LIMIT :limit",
        "SELECT FROM Record WHERE state = :state AND kind = 'common' ORDER BY tsPosted DESC LIMIT :limit",
        "SELECT state, tsPosted, @rid AS rid FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT :limit",
        "SELECT age, @rid AS rid FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT :limit",
        "SELECT tsPosted AS t, @rid AS rid FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY t DESC LIMIT :limit" };
    for (int i = 0; i < 120; i++) {
      final String sql = shapes[rnd.nextInt(shapes.length)];
      final int ageMin = 18 + rnd.nextInt(43);
      final Map<String, Object> params = params(STATES[rnd.nextInt(STATES.length)], ageMin, ageMin + rnd.nextInt(15),
          SERVICES[rnd.nextInt(SERVICES.length)], 1 + rnd.nextInt(rnd.nextBoolean() ? 20 : 400));
      assertSameAnswer(sql, params);
    }
    // both ends of the race were compared
    assertThat(walkAnswers).isGreaterThan(20);
    assertThat(fallbackAnswers).isGreaterThan(5);
  }

  @Test
  void nullSortKeysComeFirstAscendingAndLastDescending() {
    createSchema(3);
    load(3_000, 11);
    database.transaction(() -> {
      for (int i = 0; i < 5; i++)
        database.newVertex("Record").set("state", "NY", "age", 25, "services", List.of("s0"), "kind", "common").save();
    });
    final String asc = "SELECT FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted ASC LIMIT :limit";
    final List<Row> first = run(asc, params("NY", 0, 0, "s0", 8));
    assertThat(walkAnswers).isEqualTo(1);
    assertThat(first.subList(0, 5)).allMatch(r -> r.key() == null);
    assertSameAnswer(asc, params("NY", 0, 0, "s0", 8));

    final String desc = "SELECT FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT :limit";
    final List<Row> all = run(desc, params("NY", 0, 0, "s0", 100_000));
    assertThat(all.getLast().key()).isNull();
    assertSameAnswer(desc, params("NY", 0, 0, "s0", 100_000));
  }

  @Test
  void twoEqualityKeysBeforeTheOrderKey() {
    createSchema(3);
    database.command("sql", "CREATE INDEX ON Record (state, city, tsPosted) NOTUNIQUE");
    database.command("sql", "CREATE INDEX ON Record (kind, age) NOTUNIQUE");
    load(4_000, 6);
    final String sql = "SELECT FROM Record WHERE state = :state AND city = :city AND kind = :kind AND age = :age ORDER BY tsPosted DESC LIMIT :limit";
    final Random rnd = new Random(66);
    for (int i = 0; i < 40; i++) {
      final Map<String, Object> params = new HashMap<>();
      params.put("state", STATES[rnd.nextInt(3)]);
      params.put("city", "city" + rnd.nextInt(5));
      params.put("kind", rnd.nextBoolean() ? "common" : "rare");
      params.put("age", 18 + rnd.nextInt(43));
      params.put("limit", 1 + rnd.nextInt(5));
      assertSameAnswer(sql, params);
    }
    final String plan = explain(sql, Map.of("state", "CA", "city", "city1", "kind", "common", "age", 30, "limit", 3));
    assertThat(plan).contains("ORDERED INDEX WALK").contains("Record[state,city,tsPosted]").contains("Record[kind,age]");
  }

  @Test
  void uncommittedChangesAreSeenByBothSidesOfTheRace() {
    createSchema(4);
    load(3_000, 7);
    final Random rnd = new Random(77);
    database.begin();
    try {
      // newest records, deletions of the newest committed ones, and records moved to another state, none committed
      for (int i = 0; i < 50; i++)
        database.newVertex("Record").set("state", "CA", "age", 20 + rnd.nextInt(30), "tsPosted", 20_000_000L + rnd.nextInt(1000), "services",
            List.of("s0", "s1"), "kind", "common").save();
      try (final ResultSet rs = database.query("sql", "SELECT FROM Record WHERE state = 'CA' ORDER BY tsPosted DESC LIMIT 200")) {
        int i = 0;
        while (rs.hasNext()) {
          final Document doc = rs.next().getRecord().orElseThrow().asDocument();
          if (i % 3 == 0)
            doc.delete();
          else if (i % 3 == 1)
            ((MutableDocument) doc.modify()).set("state", "TX").save();
          i++;
        }
      }
      for (final String service : List.of("s0", "s1", "s4"))
        for (final String state : List.of("CA", "TX"))
          for (final int limit : List.of(5, 60, 500))
            assertSameAnswer(STRUCTURED, params(state, 18, 60, service, limit));
    } finally {
      database.rollback();
    }
  }

  @Test
  void feedShapeKeepsItsPlan() {
    createSchema(2);
    load(2_000, 8);
    final String sql = "SELECT FROM Record WHERE tsPosted >= :since AND age IS NOT NULL ORDER BY tsPosted DESC LIMIT 20";
    final String plan = explain(sql, Map.of("since", 5_000_000L));
    assertThat(plan).doesNotContain("ORDERED INDEX WALK").contains("Record[tsPosted]").doesNotContain("ORDER BY");
    final List<Row> rows = run(sql, Map.of("since", 5_000_000L));
    assertThat(rows).hasSize(20);
    for (int i = 1; i < rows.size(); i++)
      assertThat((Long) rows.get(i).key()).isLessThanOrEqualTo((Long) rows.get(i - 1).key());
  }

  @Test
  void shapesTheWalkCannotServeKeepThePreviousPlan() {
    createSchema(2);
    load(1_000, 9);
    final Map<String, Object> p = params("CA", 18, 60, "s1", 10);
    // no LIMIT
    assertThat(explain("SELECT FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC", p))
        .doesNotContain("ORDERED INDEX WALK");
    // an aggregation
    assertThat(explain("SELECT count(*) FROM Record WHERE state = :state AND services CONTAINS :service GROUP BY age ORDER BY age LIMIT 3",
        p)).doesNotContain("ORDERED INDEX WALK");
    // directions mixed
    assertThat(explain("SELECT FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC, age ASC LIMIT 3", p))
        .doesNotContain("ORDERED INDEX WALK");
    // a sort on an expression
    assertThat(explain("SELECT FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted.asString() DESC LIMIT 3",
        p)).doesNotContain("ORDERED INDEX WALK");
    // an alias that shadows the sort key with another value
    assertThat(explain("SELECT age AS tsPosted FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT 3",
        p)).doesNotContain("ORDERED INDEX WALK");
    // DISTINCT
    assertThat(explain("SELECT DISTINCT age FROM Record WHERE state = :state AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT 3",
        p)).doesNotContain("ORDERED INDEX WALK");
    // disabled
    database.getConfiguration().setValue(GlobalConfiguration.QUERY_ORDERED_INDEX_LIMIT, false);
    try {
      assertThat(explain(STRUCTURED, p)).doesNotContain("ORDERED INDEX WALK");
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_ORDERED_INDEX_LIMIT, true);
    }
  }

  @Test
  void profileTellsWhichSideAnswered() {
    createSchema(2);
    load(2_000, 10);
    try (final ResultSet rs = database.command("sql", "PROFILE " + STRUCTURED, params("CA", 18, 60, "s0", 5))) {
      final String plan = rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
      assertThat(plan).contains("ORDERED INDEX WALK").contains("answered by the ordered walk");
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------

  private static Map<String, Object> params(final String state, final int ageMin, final int ageMax, final String service, final int limit) {
    final Map<String, Object> params = new HashMap<>();
    params.put("state", state);
    params.put("ageMin", ageMin);
    params.put("ageMax", ageMax);
    params.put("service", service);
    params.put("limit", limit);
    return params;
  }

  private String explain(final String sql, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("sql", "EXPLAIN " + sql, params)) {
      return rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  private OrderedIndexLimitStep runAndGetStep(final String sql, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("sql", sql, params)) {
      while (rs.hasNext())
        rs.next();
      return (OrderedIndexLimitStep) rs.getExecutionPlan().orElseThrow().getSteps().getFirst();
    }
  }

  private record Row(RID rid, Object key) {
  }

  /** How the races of the queries run so far ended: answered by the ordered walk, or by the previous plan. */
  private int walkAnswers;
  private int fallbackAnswers;

  private List<Row> run(final String sql, final Map<String, Object> params) {
    final List<Row> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", sql, params)) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        // a projection carries the record as @rid AS rid; the key is read from the record either way
        final RID rid = r.isElement() ? r.getIdentity().orElseThrow() : r.<Identifiable>getProperty("rid").getIdentity();
        rows.add(new Row(rid, rid.asDocument().getLong("tsPosted")));
      }
      if (rs.getExecutionPlan().orElseThrow().getSteps().getFirst() instanceof OrderedIndexLimitStep step) {
        if (step.answeredByOrderedWalk())
          walkAnswers++;
        else
          fallbackAnswers++;
      }
    }
    return rows;
  }

  /**
   * Same rows as the plan with the step disabled and as a filter and sort over every record: the same sort keys in the same
   * order, and the same records except among the ties on the key at either edge of the page, which any plan may pick from.
   */
  private void assertSameAnswer(final String sql, final Map<String, Object> params) {
    final List<Row> actual = run(sql, params);

    ((DatabaseInternal) database).getExecutionPlanCache().invalidate();
    database.getConfiguration().setValue(GlobalConfiguration.QUERY_ORDERED_INDEX_LIMIT, false);
    final List<Row> previous;
    try {
      previous = run(sql, params);
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.QUERY_ORDERED_INDEX_LIMIT, true);
      ((DatabaseInternal) database).getExecutionPlanCache().invalidate();
    }
    compare(sql + " " + params, previous, actual);
    compare(sql + " " + params + " (scan)", scan(sql, params), actual);
  }

  private static void compare(final String what, final List<Row> expected, final List<Row> actual) {
    assertThat(actual).as(what).hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++)
      assertThat(actual.get(i).key()).as(what + " key #" + i).isEqualTo(expected.get(i).key());
    if (expected.isEmpty())
      return;
    final Object first = expected.getFirst().key();
    final Object last = expected.getLast().key();
    final Set<RID> e = new HashSet<>();
    final Set<RID> a = new HashSet<>();
    for (int i = 0; i < expected.size(); i++) {
      if (!Objects.equals(expected.get(i).key(), first) && !Objects.equals(expected.get(i).key(), last))
        e.add(expected.get(i).rid());
      if (!Objects.equals(actual.get(i).key(), first) && !Objects.equals(actual.get(i).key(), last))
        a.add(actual.get(i).rid());
    }
    assertThat(a).as(what + " records").isEqualTo(e);
  }

  /** The query evaluated in Java over every record: the WHERE as a predicate, then the sort (nulls lowest), SKIP and LIMIT. */
  private List<Row> scan(final String sql, final Map<String, Object> params) {
    final Predicate<Document> where = predicateOf(sql, params);
    final boolean asc = sql.contains("ORDER BY tsPosted ASC");
    final int skip = sql.contains("SKIP 3") ? 3 : 0;
    final int limit = (Integer) params.get("limit");

    final List<Document> matches = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", "SELECT FROM Record")) {
      while (rs.hasNext()) {
        final Document doc = rs.next().getRecord().orElseThrow().asDocument();
        if (where.test(doc))
          matches.add(doc);
      }
    }
    final Comparator<Document> byKey = Comparator.comparing(d -> d.getLong("tsPosted"), Comparator.nullsFirst(Comparator.naturalOrder()));
    matches.sort(asc ? byKey : byKey.reversed());
    final List<Row> rows = new ArrayList<>();
    for (int i = skip; i < matches.size() && rows.size() < limit; i++)
      rows.add(new Row(matches.get(i).getIdentity(), matches.get(i).getLong("tsPosted")));
    return rows;
  }

  private static Predicate<Document> predicateOf(final String sql, final Map<String, Object> params) {
    Predicate<Document> p = d -> Objects.equals(d.getString("state"), params.get("state"));
    if (sql.contains("age >= :ageMin"))
      p = p.and(d -> d.getInteger("age") != null && d.getInteger("age") >= (Integer) params.get("ageMin"));
    if (sql.contains("age <= :ageMax"))
      p = p.and(d -> d.getInteger("age") != null && d.getInteger("age") <= (Integer) params.get("ageMax"));
    if (sql.contains("services CONTAINS :service"))
      p = p.and(d -> d.getList("services") != null && d.getList("services").contains(params.get("service")));
    if (sql.contains("kind = 'rare'"))
      p = p.and(d -> "rare".equals(d.getString("kind")));
    if (sql.contains("kind = 'common'"))
      p = p.and(d -> "common".equals(d.getString("kind")));
    if (sql.contains("city = :city"))
      p = p.and(d -> Objects.equals(d.getString("city"), params.get("city")));
    if (sql.contains("kind = :kind"))
      p = p.and(d -> Objects.equals(d.getString("kind"), params.get("kind")));
    if (sql.contains("age = :age"))
      p = p.and(d -> Objects.equals(d.getInteger("age"), params.get("age")));
    return p;
  }
}
