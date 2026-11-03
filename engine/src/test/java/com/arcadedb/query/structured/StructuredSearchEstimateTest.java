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
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The estimated total of a structured search (owner decision, 2026-11-03: Recon shows the page with an estimated total by
 * default, the exact count is opt-in). With {@link StructuredQuery#estimateTotal()} a search with at most one facet, no window
 * and a filter that hides nothing answers its total from the cardinality table the walk already keeps, without counting:
 * {@code total = -1}, {@code totalPartial}, {@code totalEstimate} = the table's sum. Any other search ignores the request.
 */
class StructuredSearchEstimateTest extends TestHelper {
  private static final String   TYPE     = "Advert";
  private static final String[] STATES   = { "CA", "CA", "CA", "CA", "TX", "TX", "FL", "NY", "WA", "VT" };
  private static final String[] SERVICES = { "s0", "s0", "s0", "s1", "s1", "s2", "s3", "s4" };
  private static final long     BASE_TS  = 1_790_000_000_000L;
  private static final int      ADVERTS  = 2_000;

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
    database.command("sql", "CREATE INDEX ON " + TYPE + " (services BY ITEM, tsPosted) NOTUNIQUE").close();
    insert(ADVERTS, new SplittableRandom(5), null);
  }

  private void insert(final int count, final SplittableRandom rnd, final String forcedState) {
    database.transaction(() -> {
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
        doc.set("tsPosted", BASE_TS + rnd.nextLong(30L * 24 * 3600 * 1000));
        doc.save();
      }
    });
  }

  // ---------------------------------------------------------------------------------------------------------------------

  @Test
  void aSingleFacetTotalComesFromTheTableWithoutCounting() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredSearch.Result result = search.search(StructuredQuery.newestFirst(20).where(Facet.eq("state", "FL")).estimateTotal());

    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.totalPartial()).isTrue();
    assertThat(result.totalCapped()).isFalse();
    assertThat(result.totalEstimate()).isEqualTo(exactCount(StructuredQuery.newestFirst(1).where(Facet.eq("state", "FL"))));
    assertThat(result.rows()).hasSize(20);
    // nothing was read for the total: the page's own records only
    assertThat(result.recordsRead()).isEqualTo(20);
    assertThat(result.scanned()).isEqualTo(20);
  }

  @Test
  void aRangeFacetSumsItsValues() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.intRange("age", 30, 34));
    assertThat(search.search(query.estimateTotal()).totalEstimate()).isEqualTo(exactCount(query));
  }

  @Test
  void aSingleValueByItemFacetCountsItsRecords() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.eq("services", "s2"));
    final StructuredSearch.Result result = search.search(query.estimateTotal());
    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.totalEstimate()).isEqualTo(exactCount(query));
  }

  @Test
  void noFacetEstimatesTheWholeType() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredSearch.Result result = search.search(StructuredQuery.newestFirst(5).estimateTotal());
    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.totalEstimate()).isEqualTo(ADVERTS);
  }

  @Test
  void theEstimateIsAsOldAsTheTable() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted"); // hourly TTL
    final StructuredQuery query = StructuredQuery.newestFirst(5).where(Facet.eq("state", "VT")).estimateTotal();
    final long before = search.search(query).totalEstimate();

    insert(300, new SplittableRandom(9), "VT");
    assertThat(search.search(query).totalEstimate()).as("within the TTL the table is not rebuilt").isEqualTo(before);

    search.refreshCardinality();
    assertThat(search.search(query).totalEstimate()).isEqualTo(before + 300);
  }

  @Test
  void aFilterThatHidesNothingKeepsTheTable() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final RecordFilter everythingVisible = new RecordFilter() {
      @Override
      public boolean accepts(final Document record) {
        return true;
      }

      @Override
      public boolean hidesNothing() {
        return true;
      }
    };
    final StructuredQuery query = StructuredQuery.newestFirst(5).where(Facet.eq("state", "TX"));
    final StructuredSearch.Result result = search.search(query.filter(everythingVisible).estimateTotal());
    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.totalEstimate()).isEqualTo(exactCount(query));
  }

  @Test
  void otherSearchesIgnoreTheRequestAndCountAsAsked() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final long from = BASE_TS + 3L * 24 * 3600 * 1000, to = BASE_TS + 9L * 24 * 3600 * 1000;
    final RecordFilter hidesSome = record -> !"WA".equals(record.getString("state")); // does not declare hidesNothing
    final List<StructuredQuery> notEstimable = List.of(
        StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA")).where(Facet.intRange("age", 20, 25)), // two facets
        StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA")).window(from, to),                     // a window
        StructuredQuery.newestFirst(10).where(Facet.in("state", "CA", "WA")).filter(hidesSome),              // a filter that may hide
        StructuredQuery.newestFirst(10).where(Facet.in("services", "s3", "s4")));                            // BY ITEM, several values

    for (final StructuredQuery query : notEstimable) {
      // with an exact count asked as well, the answer is the count's, exactly as without the request
      final StructuredSearch.Result counted = search.search(query.count(100_000).estimateTotal());
      assertThat(counted.total()).as("%s", query).isEqualTo(exactCount(query));
      assertThat(counted.totalPartial()).as("%s", query).isFalse();
      // with no count asked, there is no total at all
      final StructuredSearch.Result bare = search.search(query.estimateTotal());
      assertThat(bare.total()).as("%s", query).isEqualTo(-1L);
      assertThat(bare.totalEstimate()).as("%s", query).isEqualTo(-1L);
    }
  }

  @Test
  void anEstimableSearchSkipsTheExactCountItWasAlsoGiven() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA"));
    final StructuredSearch.Result result = search.search(query.count(100_000).estimateTotal());
    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.totalEstimate()).isEqualTo(exactCount(query));
    assertThat(result.recordsRead()).isEqualTo(10);
  }

  @Test
  void theRankOrderTakesTheEstimateToo() {
    database.transaction(() -> {
      database.getSchema().getType(TYPE).createProperty("views", Type.INTEGER);
      database.command("sql", "UPDATE " + TYPE + " SET views = tsPosted % 1000").close();
    });
    database.command("sql", "CREATE INDEX ON " + TYPE + " (state, views, tsPosted) NOTUNIQUE").close();
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.eq("state", "NY")).rankBy("views");
    final StructuredSearch.Result result = search.search(query.count(100_000).estimateTotal());
    assertThat(result.rows()).hasSize(10);
    assertThat(result.total()).isEqualTo(-1L);
    assertThat(result.totalEstimate()).isEqualTo(exactCount(query));
    assertThat(result.recordsRead()).as("the page only, no count").isLessThanOrEqualTo(result.scanned());
  }

  @Test
  void withoutTheRequestNothingChanges() {
    final StructuredSearch search = new StructuredSearch(database, TYPE, "tsPosted");
    final StructuredQuery query = StructuredQuery.newestFirst(10).where(Facet.eq("state", "CA"));
    assertThat(search.search(query).total()).isEqualTo(-1L);
    assertThat(search.search(query).totalEstimate()).isEqualTo(-1L);
    assertThat(search.search(query.count(100_000)).total()).isEqualTo(exactCount(query));
    assertThat(query.estimatesTotal()).isFalse();
    assertThat(query.estimateTotal().estimatesTotal()).isTrue();
  }

  // ---------------------------------------------------------------------------------------------------------------------

  /** Every advert the query's facets, window and filter accept, by a scan of the type. */
  private long exactCount(final StructuredQuery query) {
    long n = 0;
    final Iterator<?> it = database.iterateType(TYPE, false);
    while (it.hasNext()) {
      final Document d = ((Identifiable) it.next()).asDocument();
      final long ts = d.getLong("tsPosted");
      if (ts < query.windowFrom() || ts > query.windowTo())
        continue;
      boolean ok = query.filter() == null || query.filter().accepts(d);
      for (final Facet f : query.facets())
        ok &= f.matches(d.get(f.property()));
      if (ok)
        n++;
    }
    return n;
  }
}
