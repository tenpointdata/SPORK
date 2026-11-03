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
package com.arcadedb.engine;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Binary;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.exception.RecordNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPORK retrieval-speed workstream S3 (docs/spork/02-retrieval-speed.md, 4.2): {@link LocalBucket#getRecordInternal} reads the
 * record's page through the page cache's shared view under {@code READ_COMMITTED}, as the index lookups do since #10654, instead
 * of building an {@code ImmutablePage}, a {@code Binary} and a buffer per record read. Under {@code REPEATABLE_READ} the
 * transaction keeps the page it read, as before. What a read answers must not change, for a plain record, a record that
 * outgrew its page (a chunk chain) and a deleted one.
 */
class RecordReadSharedViewTest extends TestHelper {
  private static final String TYPE  = "Row";
  private static final int    ROWS  = 2_000;
  private static final String LARGE = "L".repeat(200_000);

  private final List<RID> rids = new ArrayList<>();
  private RID             largeRid;
  private RID             deletedRid;
  private LocalBucket     bucket;

  @Override
  protected void beginTest() {
    database.getSchema().buildDocumentType().withName(TYPE).withTotalBuckets(1).create();
    database.transaction(() -> {
      for (int i = 0; i < ROWS; i++)
        rids.add(database.newDocument(TYPE).set("n", i).set("name", "row-" + i).save().getIdentity());
      largeRid = database.newDocument(TYPE).set("n", -1).set("large", LARGE).save().getIdentity();
      deletedRid = database.newDocument(TYPE).set("n", -2).save().getIdentity();
    });
    database.transaction(() -> database.lookupByRID(deletedRid, true).delete());
    bucket = (LocalBucket) ((DatabaseInternal) database).getSchema().getBucketById(rids.getFirst().getBucketId());
  }

  @Override
  protected void endTest() {
    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
  }

  private PageId pageOf(final RID rid) {
    return new PageId((DatabaseInternal) database, bucket.getFileId(), (int) (rid.getPosition() / bucket.getMaxRecordsInPage()));
  }

  private CachedPage cachedPageOf(final RID rid) throws Exception {
    return ((DatabaseInternal) database).getPageManager().getCachedPageForRead(pageOf(rid), bucket.getPageSize());
  }

  @Test
  void readCommittedReadsAPlainRecordThroughTheSharedViewAndPinsNothing() throws Exception {
    final RID rid = rids.get(ROWS / 2);
    database.begin();
    try {
      final Document doc = database.lookupByRID(rid, true).asDocument();
      assertThat(doc.getInteger("n")).isEqualTo(ROWS / 2);
      assertThat(((DatabaseInternal) database).getTransaction().getPinnedPage(pageOf(rid))).as("READ_COMMITTED keeps no page").isNull();
      assertThat(cachedPageOf(rid).peekSharedImmutableView()).as("the read built the page's shared view").isNotNull();
    } finally {
      database.rollback();
    }
  }

  @Test
  void repeatableReadPinsThePageTheRecordWasReadFrom() {
    database.setTransactionIsolationLevel(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
    final RID rid = rids.get(7);
    database.begin();
    try {
      final Document doc = database.lookupByRID(rid, true).asDocument();
      assertThat(doc.getString("name")).isEqualTo("row-7");
      assertThat(((DatabaseInternal) database).getTransaction().getPinnedPage(pageOf(rid))).as("REPEATABLE_READ keeps the page")
          .isNotNull();
    } finally {
      database.rollback();
    }
  }

  @Test
  void theBinaryHandedOutReadsBackTheRecordAndIsIndependentOfLaterReads() {
    final RID rid = rids.get(11);
    final Binary first = bucket.getRecord(rid);
    final byte[] firstBytes = first.toByteArray();
    assertThat(first.size()).isEqualTo(firstBytes.length);

    // reading other records of the same page in between moves nothing in the one already handed out
    for (int i = 0; i < 50; i++)
      bucket.getRecord(rids.get(i));
    final Binary again = bucket.getRecord(rid);
    assertThat(again.toByteArray()).isEqualTo(firstBytes);
    assertThat(first.toByteArray()).isEqualTo(firstBytes);
    assertThat(first.position()).isZero();
  }

  @Test
  void chunkedAndDeletedRecordsAnswerAsBefore() {
    for (final Database.TRANSACTION_ISOLATION_LEVEL level : Database.TRANSACTION_ISOLATION_LEVEL.values()) {
      database.setTransactionIsolationLevel(level);
      database.transaction(() -> {
        assertThat(database.lookupByRID(largeRid, true).asDocument().getString("large")).as(level.name()).isEqualTo(LARGE);
        assertThatThrownBy(() -> database.lookupByRID(deletedRid, true)).as(level.name()).isInstanceOf(RecordNotFoundException.class);
        assertThatThrownBy(() -> bucket.getRecord(deletedRid)).as(level.name()).isInstanceOf(RecordNotFoundException.class);
      });
    }
  }

  @Test
  void readsUnderBothIsolationLevelsAgreeOnEveryRecord() {
    final List<byte[]> committed = new ArrayList<>(ROWS);
    for (final RID rid : rids)
      committed.add(bucket.getRecord(rid).toByteArray());

    for (final Database.TRANSACTION_ISOLATION_LEVEL level : Database.TRANSACTION_ISOLATION_LEVEL.values()) {
      database.setTransactionIsolationLevel(level);
      database.transaction(() -> {
        for (int i = 0; i < ROWS; i++) {
          assertThat(bucket.getRecord(rids.get(i)).toByteArray()).as("%s raw %d", level, i).isEqualTo(committed.get(i));
          final Document doc = database.lookupByRID(rids.get(i), true).asDocument();
          assertThat(doc.getInteger("n")).as("%s doc %d", level, i).isEqualTo(i);
          assertThat(doc.getString("name")).isEqualTo("row-" + i);
        }
      });
    }
  }

  /**
   * Review finding (blocker): the chunk-head branch read the slot offset, marker and header size from the shared view of the
   * head page and then fetched the page AGAIN for the chain walk. A commit landing between the two gets could re-flow the page
   * (compressPage) so that another record's chunk head sat at the old offset of the newer image, and the walk then returned
   * THAT record's content under the RID asked for, with no error: the trace recorded the newer version, which validation took
   * for unchanged. The head the walk starts from must be the very image the offsets were read from.
   * <p>
   * Fixture: two records R and B of one page spill into chunk heads in place, B right after R. The commit deleting R runs
   * from the read's own second page fetch: the second fetch of the head page before the fix, the first tail page after it.
   * The commit packs the page, which moves B's head to R's old offset (asserted, so the fixture proves it reproduces the
   * hazard). Whatever the read answers - R's content, or not found because R was deleted under it - it is never B's.
   */
  @Test
  void aChunkHeadIsWalkedFromTheImageItsOffsetsWereReadFrom() throws Exception {
    final String type = "Relocated";
    database.getSchema().buildDocumentType().withName(type).withTotalBuckets(1).create();
    final List<RID> small = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < 40; i++)
        small.add(database.newDocument(type).set("n", i).set("pad", "p".repeat(1_500)).save().getIdentity());
    });
    final LocalBucket relocated = (LocalBucket) ((DatabaseInternal) database).getSchema().getBucketById(small.getFirst().getBucketId());
    final RID r = small.get(10);
    final RID b = small.get(11);
    assertThat(r.getPosition() / relocated.getMaxRecordsInPage()).isEqualTo(b.getPosition() / relocated.getMaxRecordsInPage());

    final String rContent = "R".repeat(150_000);
    final String bContent = "B".repeat(150_000);
    database.transaction(() -> database.lookupByRID(r, true).asDocument().modify().set("large", rContent).save());
    database.transaction(() -> database.lookupByRID(b, true).asDocument().modify().set("large", bContent).save());

    final PageId headPageId = new PageId((DatabaseInternal) database, relocated.getFileId(),
        (int) (r.getPosition() / relocated.getMaxRecordsInPage()));
    final int rSlot = (int) (r.getPosition() % relocated.getMaxRecordsInPage());
    final int bSlot = (int) (b.getPosition() % relocated.getMaxRecordsInPage());
    final int rOffsetBefore = slotOffset(headPageId, relocated, rSlot);
    assertThat(headMarker(headPageId, relocated, rSlot)).as("R spilled into a chunk head in place").isEqualTo(LocalBucket.FIRST_CHUNK);
    assertThat(headMarker(headPageId, relocated, bSlot)).as("B spilled into a chunk head in place").isEqualTo(LocalBucket.FIRST_CHUNK);
    final byte[] bBytes = relocated.getRecord(b).toByteArray();
    final byte[] rBytes = relocated.getRecord(r).toByteArray();

    final Thread reader = Thread.currentThread();
    final AtomicInteger readsOfTheBucket = new AtomicInteger();
    final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    final AtomicInteger bOffsetAfter = new AtomicInteger(-1);
    PageManager.setPageReadFaultInjector(pageId -> {
      if (Thread.currentThread() != reader || pageId.getFileId() != relocated.getFileId()
          || readsOfTheBucket.incrementAndGet() != 2)
        return;
      // BETWEEN THE READ OF THE HEAD'S OFFSETS AND THE REST OF THE WALK: A COMMIT THAT RE-FLOWS THE HEAD PAGE
      final Thread writer = new Thread(() -> {
        try {
          database.transaction(() -> database.lookupByRID(r, true).delete());
          bOffsetAfter.set(slotOffset(headPageId, relocated, bSlot));
        } catch (final Throwable t) {
          writerFailure.set(t);
        }
      });
      writer.start();
      try {
        writer.join();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    byte[] read = null;
    database.begin();
    try {
      read = relocated.getRecord(r).toByteArray();
    } catch (final RecordNotFoundException e) {
      // R was deleted under the read: a legal answer
    } finally {
      PageManager.setPageReadFaultInjector(null);
      database.rollback();
    }

    assertThat(writerFailure.get()).isNull();
    assertThat(readsOfTheBucket.get()).as("the commit ran inside the read").isGreaterThanOrEqualTo(2);
    assertThat(bOffsetAfter.get()).as("the commit moved B's chunk head to R's old offset, the hazard this test is about")
        .isEqualTo(rOffsetBefore);
    if (read != null) {
      // compared as booleans: a 150 KB array in an assertion message is unreadable
      assertThat(Arrays.equals(read, bBytes)).as("R's read must never answer B's content").isFalse();
      assertThat(Arrays.equals(read, rBytes)).as("R's read answers R's content").isTrue();
    }
  }

  private int slotOffset(final PageId pageId, final LocalBucket bucket, final int slot) throws Exception {
    final CachedPage page = ((DatabaseInternal) database).getPageManager().getCachedPageForRead(pageId, bucket.getPageSize());
    return (int) page.getSharedImmutableView()
        .readUnsignedInt(LocalBucket.PAGE_RECORD_TABLE_OFFSET + slot * Binary.INT_SERIALIZED_SIZE);
  }

  private long headMarker(final PageId pageId, final LocalBucket bucket, final int slot) throws Exception {
    final CachedPage page = ((DatabaseInternal) database).getPageManager().getCachedPageForRead(pageId, bucket.getPageSize());
    return page.getSharedImmutableView().readNumber(slotOffset(pageId, bucket, slot));
  }
}
