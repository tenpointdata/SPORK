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
package com.arcadedb.index.hash;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.engine.PageId;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.Schema;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPORK retrieval-speed workstream S17 (docs/spork/02-retrieval-speed.md, 4.2): a HASH point lookup reads its pages through
 * {@link TransactionContext#getPageForAbsoluteReads} - the page cache's shared view under {@code READ_COMMITTED}, the page the
 * transaction keeps under {@code REPEATABLE_READ} - instead of {@code getPage}, which built an {@code ImmutablePage}, a
 * {@code Binary} and a buffer per page touched, and the index lock is striped and non-fair. What a lookup answers must not
 * change: inside and outside a transaction, under both isolation levels, against the transaction's own pending changes, and
 * while a writer splits buckets and doubles the directory underneath concurrent readers.
 */
class HashIndexSharedPageViewLookupTest extends TestHelper {
  private static final String TYPE    = "Row";
  private static final int    ROWS    = 20_000;
  private static final int    HOT_RID = 4;

  private TypeIndex         uidIndex;
  private TypeIndex         tagIndex;
  private final Map<String, RID> ridOf = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().buildDocumentType().withName(TYPE).withTotalBuckets(1).create();
    database.getSchema().getType(TYPE).createProperty("uid", String.class);
    database.getSchema().getType(TYPE).createProperty("tag", String.class);
    database.getSchema().buildTypeIndex(TYPE, new String[] { "uid" }).withType(Schema.INDEX_TYPE.HASH).withUnique(true).create();
    database.getSchema().buildTypeIndex(TYPE, new String[] { "tag" }).withType(Schema.INDEX_TYPE.HASH).withUnique(false).create();

    database.transaction(() -> {
      for (int i = 0; i < ROWS; i++) {
        final MutableDocument doc = database.newDocument(TYPE).set("uid", uid(i)).set("tag", tag(i));
        doc.save();
        ridOf.put(uid(i), doc.getIdentity());
      }
    });

    uidIndex = (TypeIndex) database.getSchema().getIndexByName(TYPE + "[uid]");
    tagIndex = (TypeIndex) database.getSchema().getIndexByName(TYPE + "[tag]");
  }

  @Override
  protected void endTest() {
    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
  }

  private static String uid(final int i) {
    return "uid-" + i;
  }

  /** Every 4th row shares one hot key, so its RIDs live in a RID list chain (#9228); the others spread over 97 keys. */
  private static String tag(final int i) {
    return i % HOT_RID == 0 ? "hot" : "t" + (i % 97);
  }

  private static List<RID> ridsOf(final IndexCursor cursor) {
    final List<RID> rids = new ArrayList<>();
    while (cursor.hasNext())
      rids.add(cursor.next().getIdentity());
    return rids;
  }

  private void assertAnswers() {
    for (int i = 0; i < ROWS; i += 7) {
      final List<RID> rids = ridsOf(uidIndex.get(new Object[] { uid(i) }));
      assertThat(rids).as("unique key %s", uid(i)).containsExactly(ridOf.get(uid(i)));
    }
    assertThat(ridsOf(uidIndex.get(new Object[] { "uid-absent" }))).isEmpty();
    assertThat(ridsOf(tagIndex.get(new Object[] { "hot" }))).as("the RID list of the hot key").hasSize(ROWS / HOT_RID);
    assertThat(ridsOf(tagIndex.get(new Object[] { "t5" }))).as("an inline non-unique key").hasSize(countTag("t5"));
    assertThat(ridsOf(tagIndex.get(new Object[] { "hot" }, 10))).as("a limited lookup").hasSize(10);
  }

  private static int countTag(final String tag) {
    int n = 0;
    for (int i = 0; i < ROWS; i++)
      if (tag(i).equals(tag))
        n++;
    return n;
  }

  @Test
  void lookupsAnswerTheSameInsideAndOutsideATransactionUnderBothIsolationLevels() {
    for (final Database.TRANSACTION_ISOLATION_LEVEL level : Database.TRANSACTION_ISOLATION_LEVEL.values()) {
      database.setTransactionIsolationLevel(level);
      assertAnswers();
      database.transaction(this::assertAnswers);
    }
  }

  @Test
  void aLookupSeesTheChangesOfItsOwnTransaction() {
    database.begin();
    try {
      final RID added = database.newDocument(TYPE).set("uid", "uid-new").set("tag", "hot").save().getIdentity();
      assertThat(ridsOf(uidIndex.get(new Object[] { "uid-new" }))).containsExactly(added);
      assertThat(ridsOf(tagIndex.get(new Object[] { "hot" }))).hasSize(ROWS / HOT_RID + 1);

      database.lookupByRID(ridOf.get(uid(8)), true).delete();
      assertThat(ridsOf(uidIndex.get(new Object[] { uid(8) }))).isEmpty();
      assertThat(ridsOf(tagIndex.get(new Object[] { "hot" }))).hasSize(ROWS / HOT_RID);
    } finally {
      database.rollback();
    }
    assertThat(ridsOf(uidIndex.get(new Object[] { uid(8) }))).containsExactly(ridOf.get(uid(8)));
    assertThat(ridsOf(uidIndex.get(new Object[] { "uid-new" }))).isEmpty();
  }

  /**
   * The shared view is what a {@code READ_COMMITTED} lookup reads, so the transaction keeps no page of the index; a
   * {@code REPEATABLE_READ} lookup keeps the pages it read, as every other read of that level does.
   */
  @Test
  void aReadCommittedLookupPinsNoPageWhileARepeatableReadLookupDoes() {
    final HashIndex index = (HashIndex) uidIndex.getIndexesOnBuckets()[0];
    final PageId metaPage = new PageId((DatabaseInternal) database, index.bucket.getFileId(), 0);

    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    database.begin();
    try {
      assertThat(ridsOf(uidIndex.get(new Object[] { uid(5) }))).containsExactly(ridOf.get(uid(5)));
      assertThat(((DatabaseInternal) database).getTransaction().getPinnedPage(metaPage)).as("READ_COMMITTED keeps no page").isNull();
    } finally {
      database.rollback();
    }

    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    database.begin();
    try {
      assertThat(ridsOf(uidIndex.get(new Object[] { uid(5) }))).containsExactly(ridOf.get(uid(5)));
      assertThat(((DatabaseInternal) database).getTransaction().getPinnedPage(metaPage)).as("REPEATABLE_READ keeps the pages it read")
          .isNotNull();
    } finally {
      database.rollback();
    }
  }

  /**
   * The readers go through {@code lookupByKey}, which is {@code ConsistentKeyLookup}: a bare {@code index.get} reads page 0, a
   * directory page and a bucket page one after the other and a commit publishes its pages one at a time, so a bare lookup that
   * overlaps a split can miss a key (issues #9369, #9397), before this change exactly as after it. The consistent lookup
   * retries on the publication sequence and must never miss.
   */
  @Test
  @Tag("slow")
  void concurrentReadersAlwaysFindTheCommittedKeysWhileAWriterSplitsBuckets() throws Exception {
    final int readers = 8;
    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicLong lookups = new AtomicLong();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final CountDownLatch done = new CountDownLatch(readers);

    for (int r = 0; r < readers; r++) {
      final SplittableRandom random = new SplittableRandom(r);
      new Thread(() -> {
        try {
          final long[] n = new long[1];
          // a thread of its own has no database context: the lookups run inside read-only transactions, as a request's do
          while (!stop.get())
            database.transaction(() -> {
              for (int k = 0; k < 200; k++) {
                final int i = random.nextInt(ROWS);
                final List<RID> rids = ridsOf(database.lookupByKey(TYPE, "uid", uid(i)));
                if (rids.size() != 1 || !rids.getFirst().equals(ridOf.get(uid(i))))
                  throw new AssertionError("key " + uid(i) + " answered " + rids + " instead of " + ridOf.get(uid(i)));
                // the bare lookup may miss a key while a split publishes, but must never answer another key's RID
                final List<RID> bare = ridsOf(uidIndex.get(new Object[] { uid(i) }));
                if (bare.size() > 1 || (bare.size() == 1 && !bare.getFirst().equals(ridOf.get(uid(i)))))
                  throw new AssertionError("key " + uid(i) + " answered " + bare + " on the bare lookup");
                n[0]++;
              }
            });
          lookups.addAndGet(n[0]);
        } catch (final Throwable e) {
          failure.compareAndSet(null, e);
        } finally {
          done.countDown();
        }
      }, "reader-" + r).start();
    }

    // the writer doubles the directory and splits buckets under the readers
    for (int from = ROWS; from < ROWS * 3; from += 1_000) {
      final int start = from;
      database.transaction(() -> {
        for (int i = start; i < start + 1_000; i++)
          database.newDocument(TYPE).set("uid", uid(i)).set("tag", tag(i)).save();
      });
    }
    stop.set(true);
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    if (failure.get() != null)
      throw new AssertionError("a reader failed", failure.get());
    assertThat(lookups.get()).isPositive();

    for (int i = ROWS; i < ROWS * 3; i += 101)
      assertThat(ridsOf(uidIndex.get(new Object[] { uid(i) }))).as("a key the writer added").hasSize(1);
  }

  /**
   * A warm {@code READ_COMMITTED} point lookup touches three pages (page 0, a directory page, the bucket page) and used to
   * build an {@code ImmutablePage}, a {@code Binary} and a buffer for each, about 130 bytes per touch. The ceiling leaves the
   * lookup's own result (serialized key, cursor, entry, RID; see {@code IndexPointLookupContentionBenchmark} for the measured
   * figure) and fails a return of the per-touch views.
   */
  @Test
  void aWarmReadCommittedPointLookupBuildsNoPageView() {
    final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long threadId = Thread.currentThread().threadId();
    final int lookups = 20_000;
    final Object[] kept = new Object[64];

    long bytes = 0;
    for (int round = 0; round < 4; round++) {
      final long before = threads.getThreadAllocatedBytes(threadId);
      for (int i = 0; i < lookups; i++)
        kept[i & 63] = uidIndex.get(new Object[] { uid(i % ROWS) });
      bytes = threads.getThreadAllocatedBytes(threadId) - before;
    }
    assertThat(kept[0]).isNotNull();
    assertThat((double) bytes / lookups).as("bytes per warm point lookup").isLessThan(2_000d);
  }

  @Test
  void aDocumentLoadedThroughTheIndexIsTheOneWritten() {
    database.transaction(() -> {
      for (int i = 0; i < ROWS; i += 503) {
        final IndexCursor cursor = uidIndex.get(new Object[] { uid(i) });
        assertThat(cursor.hasNext()).isTrue();
        final Document doc = cursor.next().asDocument();
        assertThat(doc.getString("uid")).isEqualTo(uid(i));
        assertThat(doc.getString("tag")).isEqualTo(tag(i));
      }
    });
  }
}
