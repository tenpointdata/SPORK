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
package com.arcadedb.database;

import com.arcadedb.TestHelper;
import com.arcadedb.engine.LocalBucket;
import com.arcadedb.engine.PageManager;
import com.arcadedb.query.sql.executor.ExecutionStep;
import com.arcadedb.query.sql.executor.FetchFromRidsStep;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPORK page cache, item L1: {@link LocalDatabase#lookupByRIDs(RID[], boolean)} hydrates a set of records with one read
 * lock, one page read per distinct page and the misses issued in parallel, answering exactly what one
 * {@link Database#lookupByRID(RID, boolean)} per RID answers, in the caller's order, with {@code null} where the single
 * lookup would raise {@code RecordNotFoundException}.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class LocalDatabaseLookupByRIDsTest extends TestHelper {
  private static final int RECORDS = 2_000;

  private final List<RID> items = new ArrayList<>();
  private final List<RID> others = new ArrayList<>();

  @Override
  protected void beginTest() {
    database.getSchema().createDocumentType("Item");
    database.getSchema().createDocumentType("Other");
    database.transaction(() -> {
      for (int i = 0; i < RECORDS; i++) {
        items.add(database.newDocument("Item").set("id", i).set("payload", "x".repeat(i % 50 == 0 ? 200_000 : 300)).save()
            .getIdentity());
        if (i % 10 == 0)
          others.add(database.newDocument("Other").set("id", i).save().getIdentity());
      }
    });
  }

  private LocalDatabase local() {
    return (LocalDatabase) ((DatabaseInternal) database).getEmbedded();
  }

  @Test
  void answersTheSameRecordsAsOneLookupEachInTheCallersOrder() {
    // MIXED BUCKETS, UNSORTED, WITH A DUPLICATE
    final List<RID> asked = new ArrayList<>();
    for (int i = RECORDS - 1; i >= 0; i -= 3)
      asked.add(items.get(i));
    asked.addAll(others);
    asked.add(items.get(7));
    asked.add(items.get(7));
    Collections.shuffle(asked, new Random(42));

    final Record[] records = local().lookupByRIDs(asked.toArray(new RID[0]), true);
    assertThat(records).hasSize(asked.size());
    for (int i = 0; i < asked.size(); i++) {
      final Document expected = database.lookupByRID(asked.get(i), true).asDocument();
      assertThat(records[i]).as("record %d", i).isNotNull();
      assertThat(records[i].getIdentity()).isEqualTo(asked.get(i));
      final Document actual = records[i].asDocument();
      assertThat(actual.getTypeName()).isEqualTo(expected.getTypeName());
      assertThat(actual.getInteger("id")).isEqualTo(expected.getInteger("id"));
      assertThat(actual.getString("payload")).isEqualTo(expected.getString("payload"));
    }
  }

  @Test
  void aMissingRecordIsNullWhereTheSingleLookupThrows() {
    final RID deleted = items.get(5);
    database.transaction(() -> deleted.asDocument().delete());
    final RID neverExisted = new RID(items.getFirst().getBucketId(), 999_999_999L);
    final RID danglingBucket = new RID(9_999, 0L);

    final Record[] records = local().lookupByRIDs(new RID[] { items.get(1), deleted, neverExisted, danglingBucket, items.get(2) },
        true);
    assertThat(records[0]).isNotNull();
    assertThat(records[1]).as("deleted").isNull();
    assertThat(records[2]).as("past the bucket").isNull();
    assertThat(records[3]).as("bucket that does not exist").isNull();
    assertThat(records[4]).isNotNull();
    assertThatThrownBy(() -> database.lookupByRID(deleted, true)).isInstanceOf(com.arcadedb.exception.RecordNotFoundException.class);
  }

  @Test
  void aRecordTheTransactionHoldsIsAnsweredFromTheTransaction() {
    database.transaction(() -> {
      final MutableDocument modified = items.get(3).asDocument().modify();
      modified.set("id", -3);
      modified.save();
      // NOT COMMITTED YET: THE TRANSACTION HOLDS IT, THE PAGE DOES NOT
      final Record[] records = local().lookupByRIDs(new RID[] { items.get(2), items.get(3) }, true);
      assertThat(records[1]).isSameAs(database.lookupByRID(items.get(3), true));
      assertThat(records[1].asDocument().getInteger("id")).isEqualTo(-3);
      assertThat(records[0].asDocument().getInteger("id")).isEqualTo(2);
    });
  }

  @Test
  void readsEveryPageOnceAndOnlyTheDistinctPages() {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final LocalBucket bucket = (LocalBucket) db.getSchema().getBucketById(items.getFirst().getBucketId());

    // SMALL RECORDS ONLY: THE 200KB ONES SPAN PAGES OF THEIR OWN
    final List<RID> asked = new ArrayList<>();
    final Set<Long> pages = new HashSet<>();
    for (int i = 1; i < RECORDS; i += 2) {
      asked.add(items.get(i));
      pages.add(items.get(i).getPosition() / bucket.getMaxRecordsInPage());
    }
    assertThat(pages.size()).isGreaterThan(4);

    // EVERY PAGE ON DISK BEFORE THE CACHE IS EMPTIED, OR THE "MISSES" ARE NOT ALL DEVICE MISSES: beginTest's commit is flushed
    // asynchronously, and a page still in the flush queue is served from it by loadPage (counted in pagesRead, as every load
    // is) while prefetch() deliberately skips it, since it is in memory already and there is nothing to read ahead. On a busy
    // machine the flush thread lags and the test saw 2, 18 or 29 prefetches for 40 pages, with not one page left unread
    // through a path that bypasses the prefetch: the batch reads every page through tx.getPage, never through the shared view
    // getRecordInternal uses, since none of these records is a chunk chain or a placeholder.
    assertThat(pageManager.waitAllPagesOfDatabaseAreFlushed(db)).as("beginTest's pages reached the disk").isTrue();
    pageManager.removeAllReadPagesOfDatabase(db);
    final PageManager.PPageManagerStats before = pageManager.getStats();
    final Record[] records = local().lookupByRIDs(asked.toArray(new RID[0]), true);
    final PageManager.PPageManagerStats after = pageManager.getStats();

    assertThat(records).doesNotContainNull();
    assertThat(after.pagesRead - before.pagesRead).as("pages read for %d records on %d pages", asked.size(), pages.size())
        .isEqualTo(pages.size());
    assertThat(after.prefetchIssued - before.prefetchIssued).as("the misses were prefetched").isEqualTo(pages.size());
  }

  @Test
  void worksUnderRepeatableReadAndWithoutContent() {
    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    try {
      database.transaction(() -> {
        final Record[] records = local().lookupByRIDs(new RID[] { items.get(0), items.get(50), items.get(1) }, false);
        for (int i = 0; i < records.length; i++)
          assertThat(records[i].asDocument().getInteger("id")).isEqualTo(i == 0 ? 0 : i == 1 ? 50 : 1);
        // THE SECOND READ OF THE SAME TRANSACTION READS THE SAME
        final Record[] again = local().lookupByRIDs(new RID[] { items.get(50) }, true);
        assertThat(again[0].asDocument().getString("payload")).isEqualTo(records[1].asDocument().getString("payload"));
      });
    } finally {
      database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    }

    // READ_COMMITTED WITHOUT CONTENT: LAZY SHELLS, AS lookupByRID(rid, false) ANSWERS
    final Record[] shells = local().lookupByRIDs(new RID[] { items.get(10), items.get(11) }, false);
    assertThat(shells[0].getIdentity()).isEqualTo(items.get(10));
    assertThat(shells[1].asDocument().getInteger("id")).isEqualTo(11);
  }

  @Test
  void theRidListQueryPathHydratesThroughIt() {
    final List<RID> asked = new ArrayList<>();
    for (int i = 0; i < 300; i++)
      asked.add(items.get(i * 5));
    final RID deleted = items.get(1);
    database.transaction(() -> deleted.asDocument().delete());
    asked.add(2, deleted);

    final StringBuilder list = new StringBuilder("[");
    for (int i = 0; i < asked.size(); i++) {
      if (i > 0)
        list.append(',');
      list.append(asked.get(i));
    }
    list.append(']');

    final PageManager pageManager = ((DatabaseInternal) database).getPageManager();
    final long readsBefore = pageManager.getStats().batchedRecordLookups;
    final List<Integer> ids = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", "SELECT id FROM " + list)) {
      while (rs.hasNext())
        ids.add(rs.next().getProperty("id"));
    }
    assertThat(ids).hasSize(300);
    for (int i = 0; i < 300; i++)
      assertThat(ids.get(i)).isEqualTo(i * 5);
    assertThat(pageManager.getStats().batchedRecordLookups).as("the RID list was hydrated in batches").isGreaterThan(readsBefore);

    ids.clear();
    try (final ResultSet rs = database.query("sql", "SELECT id FROM Item WHERE @rid IN " + list + " ORDER BY id")) {
      while (rs.hasNext())
        ids.add(rs.next().getProperty("id"));
    }
    assertThat(ids).hasSize(300);
  }

  /**
   * Without content, a lazy shell is handed out only for a record that exists (the batch reads the slots, copies nothing): a
   * shell for a missing record is the #4643 bomb that went off only when something loaded it. A negative position, which no
   * record can have and which the position packing cannot hold, is a null entry (review m2).
   */
  @Test
  void withoutContentAShellIsHandedOutOnlyForARecordThatExists() {
    final RID deleted = items.get(3);
    database.transaction(() -> deleted.asDocument().delete());
    final RID negative = new RID(items.get(0).getBucketId(), -5L);

    for (final boolean loadContent : new boolean[] { false, true }) {
      final Record[] records = local().lookupByRIDs(new RID[] { items.get(2), deleted, negative, items.get(4) }, loadContent);
      assertThat(records[0].getIdentity()).isEqualTo(items.get(2));
      assertThat(records[1]).as("deleted, loadContent=%s", loadContent).isNull();
      assertThat(records[2]).as("negative position, loadContent=%s", loadContent).isNull();
      assertThat(records[3].asDocument().getInteger("id")).isEqualTo(4);
    }
  }

  /**
   * Review M2: a wrapper that does not override {@code lookupByRIDs} answers it through its OWN single lookup, so whatever the
   * wrapper guards there (the HA wrapper refuses a client while the directory is being replaced) guards the batch too. The
   * default used to route straight to {@link DatabaseInternal#getEmbedded()} and skip the wrapper.
   */
  @Test
  void aWrapperWithoutABatchedPathAnswersThroughItsOwnSingleLookup() {
    final LocalDatabase embedded = local();
    final AtomicInteger guardedLookups = new AtomicInteger();
    final InvocationHandler handler = (proxy, method, args) -> {
      if (method.getName().equals("lookupByRIDs"))
        return InvocationHandler.invokeDefault(proxy, method, args);
      if (method.getName().equals("lookupByRID"))
        guardedLookups.incrementAndGet();
      try {
        return method.invoke(embedded, args);
      } catch (final InvocationTargetException e) {
        throw e.getCause();
      }
    };
    final DatabaseInternal wrapper = (DatabaseInternal) Proxy.newProxyInstance(DatabaseInternal.class.getClassLoader(),
        new Class<?>[] { DatabaseInternal.class }, handler);

    final RID deleted = items.get(5);
    database.transaction(() -> deleted.asDocument().delete());
    final RID[] asked = { items.get(6), deleted, items.get(7) };
    final Record[] records = wrapper.lookupByRIDs(asked, true);
    assertThat(guardedLookups.get()).as("lookups that went through the wrapper's own guard").isEqualTo(asked.length);
    assertThat(records[0].asDocument().getInteger("id")).isEqualTo(6);
    assertThat(records[1]).isNull();
    assertThat(records[2].asDocument().getInteger("id")).isEqualTo(7);
  }

  /** Review m3: a pull that owes one row hydrates one RID, not a whole batch of 512. */
  @Test
  void aLimitedPullHydratesOnlyWhatItOwes() {
    final String list = ridList(300, null);
    final long readBefore = readRecords();
    try (final ResultSet rs = database.query("sql", "SELECT FROM " + list + " LIMIT 1")) {
      assertThat(rs.hasNext()).isTrue();
      assertThat(rs.next().<Integer>getProperty("id")).isZero();
      assertThat(rs.hasNext()).isFalse();
    }
    assertThat(readRecords() - readBefore).as("records read for LIMIT 1 over 300 RIDs").isLessThan(10);
  }

  /**
   * Review m3: {@code SELECT @rid FROM [...]} reads no content, so the step hands out the lazy shells it did before the batched
   * hydration (checked for existence, nothing copied), while {@code SELECT FROM [...]} loads the records in the batch.
   */
  @Test
  void anIdentityOnlyProjectionKeepsTheLazyShells() {
    final RID deleted = items.get(1);
    database.transaction(() -> deleted.asDocument().delete());
    final String list = ridList(300, deleted);

    final List<RID> rids = new ArrayList<>();
    try (final ResultSet rs = database.query("sql", "SELECT @rid FROM " + list)) {
      assertThat(fetchFromRidsSteps(rs)).as("the step of an identity-only projection").allMatch(step -> !step.isLoadContent())
          .isNotEmpty();
      while (rs.hasNext())
        rids.add(rs.next().getProperty("@rid"));
    }
    assertThat(rids).as("the deleted RID is skipped, as #4643 asks").hasSize(300).doesNotContain(deleted);
    for (int i = 0; i < 300; i++)
      assertThat(rids.get(i)).isEqualTo(items.get(i * 5));

    try (final ResultSet rs = database.query("sql", "SELECT FROM " + list)) {
      assertThat(fetchFromRidsSteps(rs)).allMatch(FetchFromRidsStep::isLoadContent).isNotEmpty();
      int count = 0;
      while (rs.hasNext()) {
        final Result result = rs.next();
        assertThat(result.<Integer>getProperty("id")).isEqualTo(count * 5);
        ++count;
      }
      assertThat(count).isEqualTo(300);
    }
  }

  private static List<FetchFromRidsStep> fetchFromRidsSteps(final ResultSet rs) {
    final List<FetchFromRidsStep> steps = new ArrayList<>();
    for (final ExecutionStep step : rs.getExecutionPlan().orElseThrow().getSteps())
      if (step instanceof FetchFromRidsStep fetch)
        steps.add(fetch);
    return steps;
  }

  /** {@code [#a, #b, ...]} of every fifth item, with {@code extra} inserted third when not null. */
  private String ridList(final int count, final RID extra) {
    final List<RID> asked = new ArrayList<>();
    for (int i = 0; i < count; i++)
      asked.add(items.get(i * 5));
    if (extra != null)
      asked.add(2, extra);
    final StringBuilder list = new StringBuilder("[");
    for (int i = 0; i < asked.size(); i++) {
      if (i > 0)
        list.append(',');
      list.append(asked.get(i));
    }
    return list.append(']').toString();
  }

  private long readRecords() {
    return ((Number) ((LocalDatabase) ((DatabaseInternal) database).getEmbedded()).getStats().get("readRecord")).longValue();
  }
}
