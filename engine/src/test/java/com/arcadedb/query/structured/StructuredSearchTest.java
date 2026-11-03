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
package com.arcadedb.query.structured;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The P4 structured-search core ({@code docs/spork/02-retrieval-speed.md} 2.4): newest-first walk of the most selective
 * {@code (filter, tsPosted)} composite with the other filters tested per record, keyset paging and a bounded over-read. Every
 * answer is checked against a full scan of the type sorted in Java, so the index walk has an independent oracle.
 */
class StructuredSearchTest extends TestHelper {
  private static final String   TYPE     = "Advert";
  private static final String[] STATES   = { "CA", "CA", "CA", "CA", "CA", "CA", "CA", "CA", "TX", "TX", "TX", "TX", "TX", "FL", "FL", "FL",
      "NY", "NY", "WA", "VT" };
  private static final String[] SERVICES = { "s0", "s0", "s0", "s0", "s1", "s1", "s1", "s2", "s2", "s3", "s4", "s5", "s6", "s7" };
  private static final long     BASE_TS  = 1_790_000_000_000L;
  private static final int      ADVERTS  = 3_000;

  @Override
  protected void beginTest() {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(TYPE, 3);
      type.createProperty("state", Type.STRING);
      type.createProperty("age", Type.INTEGER);
      type.createProperty("services", Type.LIST, "STRING");
      type.createProperty("tsPosted", Type.LONG);
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "state", "tsPosted");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "age", "tsPosted");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "tsPosted");
    });
    database.command("sql", "CREATE INDEX ON " + TYPE + " (services BY ITEM) NOTUNIQUE").close();
    insert(ADVERTS, new SplittableRandom(7), null);
  }

  /** Adverts with skewed states and services, ages 18 to 45, and every 25th advert sharing its predecessor's timestamp. */
  private void insert(final int count, final SplittableRandom rnd, final String forcedState) {
    database.transaction(() -> {
      long previousTs = BASE_TS;
      for (int i = 0; i < count; i++) {
        final MutableDocument doc = database.newDocument(TYPE);
        doc.set("state", forcedState != null ? forcedState : STATES[rnd.nextInt(STATES.length)]);
        doc.set("age", 18 + rnd.nextInt(28));
        final List<String> services = new ArrayList<>();
        for (int s = 1 + rnd.nextInt(3); s > 0; s--) {
          final String service = SERVICES[rnd.nextInt(SERVICES.length)];
          if (!services.contains(service))
            services.add(service);
        }
        doc.set("services", services);
        final long ts = i % 25 == 24 ? previousTs : BASE_TS + rnd.nextLong(30L * 24 * 3600 * 1000);
        doc.set("tsPosted", ts);
        previousTs = ts;
        doc.save();
      }
    });
  }

  // ---------------------------------------------------------------------------------------------------------------------

  @Test
  void matchesTheFullScanOracleForEveryFilterCombination() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final SplittableRandom rnd = new SplittableRandom(11);
    for (int round = 0; round < 150; round++) {
      final StructuredQuery query = randomQuery(rnd).overRead(1_000_000);
      final StructuredSearch.Result result = search.search(query);

      assertThat(result.partial()).as("round %d: %s", round, query).isFalse();
      assertThat(rids(result.rows())).as("round %d: %s", round, query).containsExactlyElementsOf(oracle(query, query.limit()));
    }
  }

  @Test
  void walksTheMostSelectiveIndexedFilter() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    // VT is 5% of the adverts, a 28-year age range is all of them: walk the state composite
    assertThat(search.search(StructuredQuery.newestFirst(10).where(Facet.eq("state", "VT")).where(Facet.intRange("age", 18, 45))).walkedIndex())
        .isEqualTo(TYPE + "[state,tsPosted]");
    // CA is 40%, one age is under 4%: walk the age composite
    assertThat(search.search(StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA")).where(Facet.eq("age", 33))).walkedIndex())
        .isEqualTo(TYPE + "[age,tsPosted]");
  }

  @Test
  void keysetPagesConcatenateToTheFullOrder() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery first = StructuredQuery.newestFirst(7).where(Facet.in("state", "FL", "NY")).where(Facet.intRange("age", 20, 29));
    final List<RID> all = new ArrayList<>();
    StructuredQuery page = first;
    for (int pages = 0; pages < 1_000; pages++) {
      final StructuredSearch.Result result = search.search(page);
      assertThat(result.rows().size()).isLessThanOrEqualTo(7);
      all.addAll(rids(result.rows()));
      if (result.next() == null)
        break;
      page = first.after(result.next());
    }
    assertThat(all).containsExactlyElementsOf(oracle(first, Integer.MAX_VALUE));
  }

  @Test
  void overReadCapReturnsAPartialPageThatResumes() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    // walking CA with a rare service and a narrow age band: a 2 x limit cap is spent long before 20 matches
    final StructuredQuery first = StructuredQuery.newestFirst(20).where(Facet.eq("state", "CA")).where(Facet.in("services", "s7"))
        .where(Facet.intRange("age", 18, 45)).overRead(2);
    final StructuredSearch.Result result = search.search(first);
    assertThat(result.partial()).isTrue();
    assertThat(result.scanned()).isLessThanOrEqualTo(40);
    assertThat(result.next()).isNotNull();

    final List<RID> expected = oracle(first, Integer.MAX_VALUE);
    final List<RID> all = new ArrayList<>(rids(result.rows()));
    StructuredQuery page = first.after(result.next());
    for (int pages = 0; pages < 10_000; pages++) {
      final StructuredSearch.Result next = search.search(page);
      all.addAll(rids(next.rows()));
      if (next.next() == null)
        break;
      page = first.after(next.next());
    }
    assertThat(all).containsExactlyElementsOf(expected);
  }

  @Test
  void tiesOnTheSortKeyComeInRidOrderAcrossCursors() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(Integer.MAX_VALUE / 2).where(Facet.intRange("age", 18, 45)).overRead(1);
    final List<RID> rows = rids(search.search(query.overRead(1_000_000)).rows());
    assertThat(rows).hasSize(ADVERTS).containsExactlyElementsOf(oracle(query, Integer.MAX_VALUE));
  }

  @Test
  void windowBoundsTheSortKeyOnBothEnds() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final long from = BASE_TS + 5L * 24 * 3600 * 1000, to = BASE_TS + 6L * 24 * 3600 * 1000;
    final StructuredQuery query = StructuredQuery.newestFirst(500).where(Facet.in("state", "CA", "TX")).window(from, to).overRead(1_000_000);
    final List<Document> rows = search.search(query).rows();
    assertThat(rows).isNotEmpty().allSatisfy(d -> assertThat(d.getLong("tsPosted")).isBetween(from, to));
    assertThat(rids(rows)).containsExactlyElementsOf(oracle(query, 500));
  }

  @Test
  void oldestFirstWalksAscending() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.oldestFirst(30).where(Facet.eq("state", "TX")).where(Facet.in("services", "s0", "s3"))
        .overRead(1_000_000);
    assertThat(rids(search.search(query).rows())).containsExactlyElementsOf(oracle(query, 30));
  }

  @Test
  void withoutAnIndexedFilterItWalksTheSortIndex() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(25).where(Facet.in("services", "s5")).overRead(1_000_000);
    final StructuredSearch.Result result = search.search(query);
    assertThat(result.walkedIndex()).isEqualTo(TYPE + "[tsPosted]");
    assertThat(rids(result.rows())).containsExactlyElementsOf(oracle(query, 25));
  }

  @Test
  void aByItemCompositeIsWalkedOnceForEveryRecord() {
    database.command("sql", "CREATE INDEX ON " + TYPE + " (services BY ITEM, tsPosted) NOTUNIQUE").close();
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    // s6 and s7 are the rarest services; an advert listing both must come back once
    final StructuredQuery query = StructuredQuery.newestFirst(10_000).where(Facet.in("services", "s6", "s7")).overRead(1_000_000);
    final StructuredSearch.Result result = search.search(query);
    assertThat(result.walkedIndex()).isEqualTo(TYPE + "[servicesbyitem,tsPosted]");
    assertThat(rids(result.rows())).doesNotHaveDuplicates().containsExactlyElementsOf(oracle(query, 10_000));
  }

  @Test
  void cardinalityIsRefreshedSoTheChoiceFollowsTheData() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted", 0L); // refresh on every search
    // VT is about 150 adverts, the 30-35 band about 640: walk the state
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.eq("state", "VT")).where(Facet.intRange("age", 30, 35));
    assertThat(search.search(query).walkedIndex()).isEqualTo(TYPE + "[state,tsPosted]");

    insert(4_000, new SplittableRandom(3), "VT"); // VT becomes the most common state
    final StructuredSearch.Result after = search.search(query.overRead(1_000_000));
    assertThat(after.walkedIndex()).isEqualTo(TYPE + "[age,tsPosted]");
    assertThat(rids(after.rows())).containsExactlyElementsOf(oracle(query, 10));
  }

  @Test
  void aTypeWithoutASortIndexIsRefused() {
    database.transaction(() -> database.getSchema().createDocumentType("Bare").createProperty("tsPosted", Type.LONG));
    final StructuredSearch search = new StructuredSearch(database, "Bare", "tsPosted");
    assertThatThrownBy(() -> search.search(StructuredQuery.newestFirst(10))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bare").hasMessageContaining("tsPosted");
  }

  @Test
  void rejectsMeaninglessRequests() {
    assertThatThrownBy(() -> StructuredQuery.newestFirst(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StructuredQuery.newestFirst(10).overRead(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Facet.in("state")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Facet.intRange("age", 30, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThat(Facet.in("age", 30, 30L, 31).values()).containsExactly(30, 31); // one cursor per distinct value
    assertThatThrownBy(() -> new StructuredSearch(database, "Missing", "tsPosted").search(StructuredQuery.newestFirst(1)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing");
  }

  // ---------------------------------------------------------------------------------------------------------------------

  private StructuredQuery randomQuery(final SplittableRandom rnd) {
    StructuredQuery q = rnd.nextBoolean() ? StructuredQuery.newestFirst(1 + rnd.nextInt(60)) : StructuredQuery.oldestFirst(1 + rnd.nextInt(60));
    if (rnd.nextInt(3) > 0)
      q = q.where(rnd.nextBoolean() ? Facet.eq("state", STATES[rnd.nextInt(STATES.length)])
          : Facet.in("state", STATES[rnd.nextInt(STATES.length)], STATES[rnd.nextInt(STATES.length)]));
    if (rnd.nextInt(3) > 0) {
      final int from = 18 + rnd.nextInt(28);
      q = q.where(Facet.intRange("age", from, Math.min(45, from + rnd.nextInt(8))));
    }
    if (rnd.nextBoolean())
      q = q.where(Facet.in("services", SERVICES[rnd.nextInt(SERVICES.length)]));
    if (rnd.nextInt(4) == 0) {
      final long from = BASE_TS + rnd.nextLong(25L * 24 * 3600 * 1000);
      q = q.window(from, from + rnd.nextLong(1, 6L * 24 * 3600 * 1000));
    }
    return q;
  }

  /** Every advert, filtered by the query in Java, in (tsPosted, RID) order in the query's direction, cut at {@code limit}. */
  private List<RID> oracle(final StructuredQuery query, final int limit) {
    final List<Document> matches = new ArrayList<>();
    final Iterator<?> it = database.iterateType(TYPE, false);
    while (it.hasNext()) {
      final Document d = ((Identifiable) it.next()).asDocument();
      if (matchesAll(d, query))
        matches.add(d);
    }
    Comparator<Document> order = Comparator.comparingLong((Document d) -> d.getLong("tsPosted"))
        .thenComparingInt(d -> d.getIdentity().getBucketId()).thenComparingLong(d -> d.getIdentity().getPosition());
    if (query.descending())
      order = order.reversed();
    matches.sort(order);
    return rids(matches.subList(0, Math.min(limit, matches.size())));
  }

  private static boolean matchesAll(final Document d, final StructuredQuery query) {
    final long ts = d.getLong("tsPosted");
    if (ts < query.windowFrom() || ts > query.windowTo())
      return false;
    for (final Facet facet : query.facets()) {
      final Object value = d.get(facet.property());
      final Predicate<Object> hit = v -> {
        for (final Object wanted : facet.values())
          if (wanted.equals(v))
            return true;
        return false;
      };
      if (value instanceof List<?> list ? list.stream().noneMatch(hit) : !hit.test(value))
        return false;
    }
    return true;
  }

  private static List<RID> rids(final List<Document> docs) {
    final List<RID> rids = new ArrayList<>(docs.size());
    for (final Document d : docs)
      rids.add(d.getIdentity());
    return rids;
  }
}
