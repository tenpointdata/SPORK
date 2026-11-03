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
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The capped count, the record filter and the rank order ({@code views_desc}, P4 and P4v of
 * {@code docs/spork/02-retrieval-speed.md} 2.4) of the structured-search core, each against a full scan of the type in Java.
 */
class StructuredSearchCountAndRankTest extends TestHelper {
  private static final String   TYPE     = "Advert";
  private static final String[] STATES   = { "CA", "CA", "CA", "CA", "CA", "CA", "TX", "TX", "TX", "FL", "FL", "NY", "WA", "VT" };
  private static final String[] SERVICES = { "s0", "s0", "s0", "s1", "s1", "s2", "s3", "s4" };
  private static final long     BASE_TS  = 1_790_000_000_000L;
  private static final int      ADVERTS  = 3_000;

  @Override
  protected void beginTest() {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(TYPE, 1);
      type.createProperty("state", Type.STRING);
      type.createProperty("age", Type.INTEGER);
      type.createProperty("views", Type.INTEGER);
      type.createProperty("services", Type.LIST, "STRING");
      type.createProperty("tsPosted", Type.LONG);
      // schema v1's two arms: (facet, sort, rank) for time order and (facet, rank, sort) for rank order
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "state", "tsPosted", "views");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "age", "tsPosted", "views");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "tsPosted", "views");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "state", "views", "tsPosted");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "age", "views", "tsPosted");
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "views", "tsPosted");
    });
    database.command("sql", "CREATE INDEX ON " + TYPE + " (services BY ITEM, tsPosted, views) NOTUNIQUE").close();
    database.command("sql", "CREATE INDEX ON " + TYPE + " (services BY ITEM, views, tsPosted) NOTUNIQUE").close();

    final SplittableRandom rnd = new SplittableRandom(7);
    database.transaction(() -> {
      for (int i = 0; i < ADVERTS; i++) {
        final MutableDocument doc = database.newDocument(TYPE);
        doc.set("state", STATES[rnd.nextInt(STATES.length)]);
        doc.set("age", 18 + rnd.nextInt(28));
        doc.set("views", rnd.nextInt(40)); // many equal views: the rank order's ties fall to tsPosted, then RID
        final List<String> services = new ArrayList<>();
        for (int s = 1 + rnd.nextInt(3); s > 0; s--) {
          final String service = SERVICES[rnd.nextInt(SERVICES.length)];
          if (!services.contains(service))
            services.add(service);
        }
        doc.set("services", services);
        doc.set("tsPosted", BASE_TS + rnd.nextLong(30L * 24 * 3600 * 1000) / 1000 * 1000); // second precision: some equal
        doc.save();
      }
    });
  }

  // ---------------------------------------------------------------------------------------------------------------------

  private StructuredQuery randomQuery(final SplittableRandom rnd, final boolean rank) {
    StructuredQuery q = rank ? StructuredQuery.newestFirst(1 + rnd.nextInt(120)).rankBy("views") :
        rnd.nextBoolean() ? StructuredQuery.newestFirst(1 + rnd.nextInt(120)) : StructuredQuery.oldestFirst(1 + rnd.nextInt(120));
    if (rnd.nextBoolean())
      q = q.where(Facet.eq("state", STATES[rnd.nextInt(STATES.length)]));
    if (rnd.nextBoolean()) {
      final int from = 18 + rnd.nextInt(28);
      q = q.where(Facet.intRange("age", from, Math.min(45, from + rnd.nextInt(6))));
    }
    if (rnd.nextBoolean())
      q = q.where(Facet.eq("services", SERVICES[rnd.nextInt(SERVICES.length)]));
    if (rnd.nextInt(3) == 0) {
      final long from = BASE_TS + rnd.nextLong(20L * 24 * 3600 * 1000);
      q = q.window(from, from + rnd.nextLong(10L * 24 * 3600 * 1000));
    }
    return q.overRead(1_000_000).countBudget(Integer.MAX_VALUE).count(1 + rnd.nextInt(2_000));
  }

  /** Every matching record in the query's order: rank descending then time descending, or time in the query's direction; RID breaks ties. */
  private List<RID> oracle(final StructuredQuery q, final Predicate<Document> filter) {
    final List<Document> all = new ArrayList<>();
    for (final Iterator<? extends Identifiable> it = database.iterateType(TYPE, false); it.hasNext(); ) {
      final Document d = it.next().asDocument();
      final long ts = d.getLong("tsPosted");
      if (ts < q.windowFrom() || ts > q.windowTo())
        continue;
      boolean ok = filter.test(d);
      for (final Facet f : q.facets())
        ok &= f.matches(d.get(f.property()));
      if (ok)
        all.add(d);
    }
    Comparator<Document> order = q.rankProperty() != null ?
        Comparator.<Document>comparingLong(d -> d.getInteger(q.rankProperty())).thenComparingLong(d -> d.getLong("tsPosted")) :
        Comparator.comparingLong(d -> d.getLong("tsPosted"));
    order = order.thenComparing(d -> d.getIdentity().getBucketId()).thenComparing(d -> d.getIdentity().getPosition());
    all.sort(q.descending() ? order.reversed() : order);
    return all.stream().map(Document::getIdentity).toList();
  }

  private static List<RID> rids(final List<Document> rows) {
    return rows.stream().map(Document::getIdentity).toList();
  }

  private static void assertCount(final StructuredSearch.Result result, final List<RID> all, final StructuredQuery q) {
    assertThat(result.total()).as("total of %s", q).isEqualTo(Math.min(all.size(), q.countCap()));
    assertThat(result.totalCapped()).as("capped of %s", q).isEqualTo(all.size() > q.countCap());
    assertThat(result.totalPartial()).as("partial count of %s", q).isFalse();
  }

  // ---------------------------------------------------------------------------------------------------------------------

  @Test
  void theCountMatchesTheOracleUpToTheCap() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final SplittableRandom rnd = new SplittableRandom(11);
    for (int round = 0; round < 150; round++) {
      final StructuredQuery q = randomQuery(rnd, false);
      final StructuredSearch.Result result = search.search(q);
      final List<RID> all = oracle(q, d -> true);

      assertThat(rids(result.rows())).as("rows of %s", q).containsExactlyElementsOf(all.subList(0, Math.min(q.limit(), all.size())));
      assertCount(result, all, q);
    }
  }

  @Test
  void withoutACountTheTotalIsNotComputed() {
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(
        StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA")));

    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.recordsRead()).isEqualTo(10);
  }

  @Test
  void aCountTheWalkedFacetCoversReadsNoRecordBeyondThePage() {
    final StructuredQuery q = StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA")).count(10_000);
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(q);

    assertThat(result.recordsRead()).isEqualTo(10);
    assertCount(result, oracle(q, d -> true), q);
  }

  @Test
  void theRecordFilterAppliesToRowsAndCount() {
    final Predicate<Document> evenViews = d -> d.getInteger("views") % 2 == 0;
    final StructuredQuery q = StructuredQuery.newestFirst(25).where(Facet.eq("state", "TX")).count(10_000).filter(evenViews::test);
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(q);
    final List<RID> all = oracle(q, evenViews);

    assertThat(rids(result.rows())).containsExactlyElementsOf(all.subList(0, 25));
    assertCount(result, all, q);
    assertThat(result.recordsRead()).as("a filter that may hide anything is asked on every counted record").isEqualTo(result.scanned());
  }

  @Test
  void aFilterThatNamesWhatItMayHideKeepsTheCountOnTheIndex() {
    final List<RID> tx = oracle(StructuredQuery.newestFirst(1).where(Facet.eq("state", "TX")), d -> true);
    final Set<RID> hidden = Set.of(tx.get(40), tx.get(41), tx.get(tx.size() - 1));
    final RecordFilter filter = new RecordFilter() {
      @Override
      public boolean accepts(final Document record) {
        return !hidden.contains(record.getIdentity());
      }

      @Override
      public boolean mustRead(final RID rid) {
        return hidden.contains(rid);
      }
    };
    final StructuredQuery q = StructuredQuery.newestFirst(10).where(Facet.eq("state", "TX")).count(10_000).filter(filter);
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(q);

    assertThat(result.total()).isEqualTo(tx.size() - 3L);
    assertThat(result.recordsRead()).as("the page, plus the three it may hide").isEqualTo(13);
  }

  @Test
  void aCountPastItsBudgetIsALowerBoundWithAnEstimate() {
    final StructuredQuery q = StructuredQuery.newestFirst(5).where(Facet.eq("state", "CA")).where(Facet.intRange("age", 18, 30))
        .count(10_000).countBudget(50);
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(q);
    final long exact = oracle(q, d -> true).size();

    assertThat(result.totalPartial()).isTrue();
    assertThat(result.total()).isLessThan(exact);
    assertThat(result.totalEstimate()).isGreaterThanOrEqualTo(result.total());
    assertThat(result.rows()).hasSize(5);
  }

  @Test
  void theRankOrderMatchesTheOracle() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final SplittableRandom rnd = new SplittableRandom(13);
    for (int round = 0; round < 120; round++) {
      final StructuredQuery q = randomQuery(rnd, true);
      final StructuredSearch.Result result = search.search(q);
      final List<RID> all = oracle(q, d -> true);

      assertThat(rids(result.rows())).as("rows of %s", q).containsExactlyElementsOf(all.subList(0, Math.min(q.limit(), all.size())));
      assertThat(result.partial()).isFalse();
      assertCount(result, all, q);
    }
  }

  @Test
  void aNarrowWindowInRankOrderFallsBackToTheTimeArm() {
    final long from = BASE_TS + 3L * 24 * 3600 * 1000;
    final StructuredQuery q = StructuredQuery.newestFirst(20).rankBy("views").where(Facet.eq("state", "CA")).window(from,
        from + 12L * 3600 * 1000).rankScanCap(10).count(10_000);
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(q);
    final List<RID> all = oracle(q, d -> true);

    assertThat(all.size()).as("the fixture window holds more than the page").isGreaterThan(20);
    assertThat(rids(result.rows())).containsExactlyElementsOf(all.subList(0, 20));
    assertThat(result.partial()).isFalse();
    assertCount(result, all, q);
  }

  @Test
  void theRankFallbackIsPartialWhenTheWindowExceedsItsCap() {
    // five days of CA: most views-ordered entries fall outside, so 10 entries do not fill the page, and the window holds more than 30
    final long from = BASE_TS + 3L * 24 * 3600 * 1000;
    final StructuredQuery q = StructuredQuery.newestFirst(5).rankBy("views").where(Facet.eq("state", "CA")).window(from,
        from + 5L * 24 * 3600 * 1000).rankScanCap(10).rankFallbackCap(30);
    final StructuredSearch.Result result = new StructuredSearch(database, TYPE, "tsPosted").search(q);

    assertThat(result.partial()).isTrue();
    assertThat(result.rows()).hasSize(5);
  }

  @Test
  void rejectsWhatTheRankOrderCannotDo() {
    assertThatThrownBy(() -> StructuredQuery.oldestFirst(5).rankBy("views")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StructuredQuery.newestFirst(5).rankBy("views").after(new StructuredSearch.Keyset(1L, new RID(1, 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StructuredQuery.newestFirst(5).count(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StructuredSearch(database, TYPE, "tsPosted").search(StructuredQuery.newestFirst(5).rankBy("age")
        .where(Facet.eq("services", "s0")))).as("no (rank, sort) or (facet, rank, sort) composite for age").isInstanceOf(
        IllegalArgumentException.class);
  }
}
