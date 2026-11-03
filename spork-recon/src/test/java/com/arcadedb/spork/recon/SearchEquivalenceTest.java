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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.Document;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.query.structured.Facet;
import com.arcadedb.query.structured.StructuredQuery;
import com.arcadedb.query.structured.StructuredSearch;
import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.schema.SporkSchema;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search route's read path, the engine's structured-search core under the plugin's visibility filter, against the SQL it
 * replaces (02-retrieval-speed.md 2.4, R36): on a schema v1 partition, for many seeded filter, sort and window combinations, the
 * same rows in the same order and the same count up to the cap. Every 40th record carries a marking the person does not hold,
 * so hidden records are in every comparison; SQL leaves them out with a predicate. Every {@code tsPosted} is distinct, because
 * SQL leaves the order of equal sort keys open.
 */
class SearchEquivalenceTest {
  private static final String   PATH     = "target/databases-search/rec_1";
  private static final String[] STATES   = { "TX", "CA", "FL", "NY", "WA" };
  private static final String[] SERVICES = { "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7" };
  private static final int      RECORDS  = 4_000;
  private static final long     T0       = 1_793_707_200_000L;
  private static final String   TENANT   = "deliverfund";

  private static Database         database;
  private static StructuredSearch core;

  @BeforeAll
  static void createPartition() {
    FileUtils.deleteRecursively(new File(PATH));
    database = new DatabaseFactory(PATH).create();
    SporkSchema.install(database);
    final Random rnd = new Random(7);
    database.transaction(() -> {
      for (int n = 1; n <= RECORDS; n++) {
        final Document record = database.newVertex("Record")//
            .set("record_id", RecordId.of(1, n))//
            // distinct timestamps, shuffled against insertion order so RID order says nothing about time order
            .set("tsPosted", T0 - (long) ((n * 7919L) % RECORDS) * 60_000L)//
            .set("views", rnd.nextInt(50))//
            .set("stateCode", STATES[skewed(rnd, STATES.length)])//
            .set("age", 18 + skewed(rnd, 20))//
            .set("hasImages", rnd.nextInt(3) > 0)//
            .set("services", List.of(SERVICES[skewed(rnd, SERVICES.length)], SERVICES[rnd.nextInt(SERVICES.length)]))//
            .set("markings", n % 40 == 0 ? 0b10L : null)//
            .save();
      }
    });
    core = new StructuredSearch(database, "Record", "tsPosted");
  }

  @AfterAll
  static void dropPartition() {
    if (database != null)
      database.drop();
  }

  private static int skewed(final Random rnd, final int n) {
    return (int) (n * Math.pow(rnd.nextDouble(), 2));
  }

  private static StructuredSearch.Result search(final StructuredQuery query) {
    return core.search(query.filter(new RecordVisibility(TENANT, 0L).forPartition(database)));
  }

  /** The SQL the core replaces, with visibility as a predicate. */
  private static List<Long> sql(final StructuredQuery query, final int limit) {
    final StringBuilder where = new StringBuilder("(markings IS NULL OR markings = 0)");
    final Map<String, Object> params = new HashMap<>();
    int i = 0;
    for (final Facet facet : query.facets()) {
      final String p = "p" + i++;
      if (facet.values().size() > 1) { // the age band
        where.append(" AND ").append(facet.property()).append(" >= :").append(p).append("min AND ").append(facet.property())
            .append(" <= :").append(p).append("max");
        params.put(p + "min", facet.values().getFirst());
        params.put(p + "max", facet.values().getLast());
        continue;
      }
      where.append(" AND ").append(facet.property()).append(facet.property().equals("services") ? " CONTAINS :" : " = :").append(p);
      params.put(p, facet.values().getFirst());
    }
    if (query.windowFrom() != Long.MIN_VALUE || query.windowTo() != Long.MAX_VALUE) {
      where.append(" AND tsPosted >= :from AND tsPosted <= :to");
      params.put("from", query.windowFrom());
      params.put("to", query.windowTo());
    }
    final String order = query.rankProperty() != null ? "views DESC, tsPosted DESC" : query.descending() ? "tsPosted DESC" : "tsPosted ASC";
    final List<Long> ids = new ArrayList<>();
    try (final ResultSet rs = database.query("sql",
        "SELECT record_id FROM Record WHERE " + where + " ORDER BY " + order + " LIMIT " + limit, params)) {
      while (rs.hasNext())
        ids.add(rs.next().<Long>getProperty("record_id"));
    }
    return ids;
  }

  private static List<Long> ids(final StructuredSearch.Result result) {
    final List<Long> ids = new ArrayList<>();
    for (final Document row : result.rows())
      ids.add(row.getLong("record_id"));
    return ids;
  }

  private static void assertSameAsSql(final StructuredQuery query) {
    final StructuredSearch.Result result = search(query);
    assertThat(ids(result)).as("rows of %s", query).isEqualTo(sql(query, query.limit()));
    final long expected = sql(query, RECORDS + 1).size();
    assertThat(result.total()).as("count of %s", query).isEqualTo(Math.min(expected, query.countCap()));
    assertThat(result.totalCapped()).as("capped of %s", query).isEqualTo(expected > query.countCap());
    assertThat(result.partial()).isFalse();
    assertThat(result.totalPartial()).isFalse();
  }

  private static StructuredQuery unbounded(final StructuredQuery query, final int countCap) {
    return query.overRead(1_000_000).countBudget(Integer.MAX_VALUE).count(countCap);
  }

  @Test
  void aSingleFacetMatchesSqlInEverySort() {
    for (final String state : STATES) {
      assertSameAsSql(unbounded(StructuredQuery.newestFirst(50).where(Facet.eq("stateCode", state)), 10_000));
      assertSameAsSql(unbounded(StructuredQuery.oldestFirst(50).where(Facet.eq("stateCode", state)), 10_000));
      assertSameAsSql(unbounded(StructuredQuery.newestFirst(50).rankBy("views").where(Facet.eq("stateCode", state)), 10_000));
    }
  }

  @Test
  void theBenchmarkShapeMatchesSql() {
    // state + age range + service, newest first (QueryMix.STRUCTURED)
    final Random rnd = new Random(11);
    for (int q = 0; q < 60; q++) {
      final int ageMin = 18 + rnd.nextInt(18);
      assertSameAsSql(unbounded(StructuredQuery.newestFirst(1 + rnd.nextInt(100)).where(Facet.eq("stateCode", STATES[rnd.nextInt(
          STATES.length)])).where(Facet.intRange("age", ageMin, ageMin + 4)).where(Facet.eq("services", SERVICES[rnd.nextInt(
          SERVICES.length)])), 10_000));
    }
  }

  @Test
  void everyCombinationOfFiltersSortsAndWindowsMatchesSql() {
    final Random rnd = new Random(13);
    for (int q = 0; q < 200; q++) {
      final int limit = 1 + rnd.nextInt(120);
      StructuredQuery query = switch (rnd.nextInt(3)) {
        case 0 -> StructuredQuery.newestFirst(limit);
        case 1 -> StructuredQuery.oldestFirst(limit);
        default -> StructuredQuery.newestFirst(limit).rankBy("views");
      };
      if (rnd.nextBoolean())
        query = query.where(Facet.eq("stateCode", STATES[rnd.nextInt(STATES.length)]));
      if (rnd.nextBoolean()) {
        final int ageMin = 18 + rnd.nextInt(20);
        query = query.where(Facet.intRange("age", ageMin, ageMin + rnd.nextInt(6)));
      }
      if (rnd.nextBoolean())
        query = query.where(Facet.eq("services", SERVICES[rnd.nextInt(SERVICES.length)]));
      if (rnd.nextInt(4) == 0)
        query = query.where(Facet.eq("hasImages", rnd.nextBoolean()));
      if (rnd.nextBoolean()) {
        final long to = T0 - rnd.nextInt(RECORDS / 2) * 60_000L;
        query = query.window(to - rnd.nextInt(RECORDS) * 60_000L, to);
      }
      assertSameAsSql(unbounded(query, 1 + rnd.nextInt(3_000)));
    }
  }

  @Test
  void theSmallestFacetDrivesTheWalk() {
    final StructuredQuery query = unbounded(StructuredQuery.newestFirst(10).where(Facet.eq("services", "s0")).where(Facet.eq(
        "stateCode", "WA")).where(Facet.intRange("age", 36, 37)), 10_000);

    // s0 is the commonest service; ages 36 and 37 are the rarest facet values of the fixture
    assertThat(search(query).walkedIndex()).isEqualTo("Record[age,tsPosted,views]");
    assertSameAsSql(query);
  }

  @Test
  void aCoveredCountReadsOnlyThePageAndTheHiddenRecords() {
    final StructuredQuery query = StructuredQuery.newestFirst(5).where(Facet.eq("stateCode", "TX")).count(10_000);
    final StructuredSearch.Result result = search(query);
    long hiddenTx = 0;
    try (final ResultSet rs = database.query("sql", "SELECT count(*) AS n FROM Record WHERE stateCode = 'TX' AND markings = 2")) {
      hiddenTx = rs.next().<Long>getProperty("n");
    }

    assertThat(hiddenTx).isPositive();
    assertThat(result.recordsRead()).isLessThanOrEqualTo(5 + 1 + hiddenTx);
    assertThat(result.total()).isEqualTo(sql(query, RECORDS).size());
  }

  /** The core's table total counts every entry, hidden or not: the route may use it only when the person can see them all. */
  @Test
  void theTableEstimateIsUsedOnlyWhenThePersonSeesEverything() {
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.eq("stateCode", "TX")).count(10_000).countBudget(2_000)
        .estimateTotal();
    final long allTx = sql(StructuredQuery.newestFirst(1).where(Facet.eq("stateCode", "TX")), RECORDS).size()
        + hiddenCount("TX");

    // the fixture's marked records are hidden from this person: the budgeted count runs
    final StructuredSearch.Result hidden = core.search(query.filter(new RecordVisibility(TENANT, 0L).forPartition(database)));
    assertThat(hidden.total()).isNotNegative();

    // holding the marking, the person sees the whole partition: the total is the table's, nothing is counted
    final StructuredSearch.Result everything = core.search(query.filter(new RecordVisibility(TENANT, 0b10L).forPartition(database)));
    assertThat(everything.total()).isEqualTo(-1L);
    assertThat(everything.totalPartial()).isTrue();
    assertThat(everything.totalEstimate()).isEqualTo(allTx);
    assertThat(everything.rows()).hasSize(10);
  }

  private static long hiddenCount(final String state) {
    try (final ResultSet rs = database.query("sql", "SELECT count(*) AS n FROM Record WHERE stateCode = :s AND markings = 2",
        Map.of("s", state))) {
      return rs.next().<Long>getProperty("n");
    }
  }
}
