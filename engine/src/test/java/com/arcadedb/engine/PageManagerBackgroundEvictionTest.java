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

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPORK page cache, items S16 and M1: the read cache is kept under its limit by a dedicated evictor thread woken at 95%
 * of {@code arcadedb.maxPageRAM} with a CLOCK sweep, so a reader never copies and sorts the whole cache on its own
 * thread; page 0 of every file (the HASH directory start, the LSM and Dictionary header pages) is pinned and survives
 * a churn that evicts everything else.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class PageManagerBackgroundEvictionTest {
  private static final String DB_PATH    = "./target/databases/PageManagerBackgroundEvictionTest";
  private static final int    RECORDS    = 3_000;
  private static final int    PAYLOAD_KB = 10;
  private static final int    MAX_RAM_MB = 4;

  private Database db;

  @BeforeEach
  void setUp() {
    FileUtils.deleteRecursively(new File(DB_PATH));
    // SET BEFORE OPENING: PageManager.startup() (THE 0 -> 1 ACQUIRE) READS IT ONCE
    GlobalConfiguration.MAX_PAGE_RAM.setValue(MAX_RAM_MB);
    db = new DatabaseFactory(DB_PATH).create();
    db.getSchema().createDocumentType("Item");
  }

  @AfterEach
  void tearDown() {
    try {
      if (db != null && db.isOpen())
        db.drop();
    } finally {
      GlobalConfiguration.MAX_PAGE_RAM.reset();
    }
  }

  @Test
  void readersNeverEvictOnTheirOwnThreadAndTheCacheStaysBounded() {
    final PageManager pageManager = ((DatabaseInternal) db).getPageManager();
    final PageManager.PPageManagerStats before = pageManager.getStats();

    final List<RID> rids = new ArrayList<>();
    db.transaction(() -> {
      for (int i = 0; i < RECORDS; i++) {
        final MutableDocument doc = db.newDocument("Item");
        doc.set("val", i);
        doc.set("payload", "x".repeat(PAYLOAD_KB * 1024));
        rids.add(doc.save().getIdentity());
      }
    });

    // READ EVERYTHING TWICE, OUT OF ORDER: 30 MB OF PAGES THROUGH A 4 MB CACHE
    for (int round = 0; round < 2; round++)
      for (int i = 0; i < rids.size(); i++)
        db.lookupByRID(rids.get((i * 7919) % rids.size()), true);

    final PageManager.PPageManagerStats stats = pageManager.getStats();
    assertThat(stats.evictionRuns - before.evictionRuns).as("the evictor ran").isGreaterThan(0);
    assertThat(stats.pagesEvicted - before.pagesEvicted).as("pages evicted").isGreaterThan(0);
    assertThat(stats.synchronousEvictionRuns - before.synchronousEvictionRuns)
        .as("sort-the-world eviction runs on a reader's thread").isZero();
    // THE HARD LIMIT: READERS PARK ABOVE 110%, SO THE CACHE CAN OVERSHOOT BY A FEW PAGES AT MOST
    final long hardLimit = stats.maxRAM * 110 / 100 + 4L * 65_536L;
    assertThat(stats.readCacheRAM).as("read cache RAM against the hard limit").isLessThanOrEqualTo(hardLimit);
  }

  @Test
  void pageZeroOfEveryFileSurvivesTheChurn() {
    final DatabaseInternal database = (DatabaseInternal) db;
    final PageManager pageManager = database.getPageManager();

    final List<RID> rids = new ArrayList<>();
    db.transaction(() -> {
      for (int i = 0; i < RECORDS; i++) {
        final MutableDocument doc = db.newDocument("Item");
        doc.set("val", i);
        doc.set("payload", "x".repeat(PAYLOAD_KB * 1024));
        rids.add(doc.save().getIdentity());
      }
    });

    final int bucketFileId = rids.getFirst().getBucketId();
    final PageId pageZero = new PageId(database, bucketFileId, 0);
    // TOUCH IT ONCE SO IT IS IN THE CACHE, THEN NEVER AGAIN
    db.lookupByRID(rids.getFirst(), true);
    assertThat(pageManager.isPageCached(pageZero)).isTrue();

    // 30 MB OF OTHER PAGES THROUGH A 4 MB CACHE, NONE OF THEM PAGE 0 (THE FIRST PAGE'S RECORDS ARE SKIPPED)
    final int perPage = 5;
    for (int round = 0; round < 3; round++)
      for (int i = perPage * 2; i < rids.size(); i++)
        db.lookupByRID(rids.get(i), true);

    final PageManager.PPageManagerStats stats = pageManager.getStats();
    assertThat(stats.pagesEvicted).isGreaterThan(0);
    assertThat(pageManager.isPageCached(pageZero)).as("page 0 of the bucket file is pinned").isTrue();
  }

  @Test
  void reclaimStillFreesSynchronouslyForTheCallerThatAsksForHeap() {
    final PageManager pageManager = ((DatabaseInternal) db).getPageManager();
    db.transaction(() -> {
      for (int i = 0; i < 200; i++)
        db.newDocument("Item").set("val", i).set("payload", "x".repeat(PAYLOAD_KB * 1024)).save();
    });
    try (final ResultSet rs = db.query("sql", "SELECT FROM Item")) {
      while (rs.hasNext())
        rs.next();
    }

    final long before = pageManager.getReadCacheRAM();
    assertThat(before).isGreaterThan(0);
    final long freed = pageManager.reclaimReadCacheRAM(before / 2);
    assertThat(freed).isGreaterThanOrEqualTo(before / 2);
    assertThat(pageManager.getReadCacheRAM()).isLessThanOrEqualTo(before - freed);
  }

  /**
   * Review M4: a prefetch never publishes over the budget. Submitted past the evictor's wake threshold it is declined; a task
   * submitted below it that runs once the cache is past the hard limit drops the page instead of reading it. Before, prefetch
   * consulted no budget at all and its publication never parked, so 4,096 queued 64 KB prefetches could put 256 MB into a 16 MB
   * cache. The evictor is held off (the test holds its lock) so the cache can be driven past both thresholds deterministically.
   */
  @Test
  void aPrefetchNeverPublishesOverTheBudget() throws Exception {
    final DatabaseInternal database = (DatabaseInternal) db;
    final PageManager pageManager = database.getPageManager();
    db.transaction(() -> {
      for (int i = 0; i < RECORDS; i++)
        db.newDocument("Item").set("val", i).set("payload", "x".repeat(PAYLOAD_KB * 1024)).save();
    });
    pageManager.waitAllPagesOfDatabaseAreFlushed(db);
    final LocalBucket bucket = (LocalBucket) database.getSchema().getType("Item").getBuckets(false).getFirst();
    final int totalPages = bucket.getTotalPages();
    assertThat((long) totalPages * bucket.getPageSize()).as("the bucket is larger than twice the cache")
        .isGreaterThan(2 * pageManager.getReaderParkThreshold());
    pageManager.removeAllReadPagesOfDatabase(db);

    // A PREFETCH TASK SUBMITTED NOW, WHILE THE CACHE IS EMPTY, AND HELD UNTIL THE TEST RUNS IT
    final PageId late = new PageId(database, bucket.getFileId(), totalPages - 1);
    final List<Runnable> held = new ArrayList<>();
    final AbstractExecutorService holding = new AbstractExecutorService() {
      @Override
      public void execute(final Runnable command) {
        held.add(command);
      }

      @Override
      public void shutdown() {
      }

      @Override
      public List<Runnable> shutdownNow() {
        return List.of();
      }

      @Override
      public boolean isShutdown() {
        return false;
      }

      @Override
      public boolean isTerminated() {
        return false;
      }

      @Override
      public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        return true;
      }
    };
    assertThat(pageManager.prefetch(late, holding)).isTrue();
    assertThat(held).hasSize(1);

    final ReentrantLock evictionLock = pageManager.getEvictionLock();
    evictionLock.lock();
    try {
      // PAST THE WAKE THRESHOLD: A NEW PREFETCH IS DECLINED
      int next = 1;
      while (pageManager.getReadCacheRAM() < pageManager.getEvictorWakeThreshold() && next < totalPages - 1)
        pageManager.getImmutablePage(new PageId(database, bucket.getFileId(), next++), bucket.getPageSize(), false, true);
      assertThat(pageManager.getReadCacheRAM()).isGreaterThanOrEqualTo(pageManager.getEvictorWakeThreshold());

      final PageManager.PPageManagerStats before = pageManager.getStats();
      final PageId cold = new PageId(database, bucket.getFileId(), totalPages - 2);
      assertThat(pageManager.isPageCached(cold)).isFalse();
      assertThat(pageManager.prefetch(cold)).as("a prefetch submitted past the wake threshold").isFalse();
      assertThat(pageManager.isReadInFlight(cold)).isFalse();

      // PAST THE HARD LIMIT: THE TASK SUBMITTED BEFORE DROPS ITS PAGE
      while (pageManager.getReadCacheRAM() <= pageManager.getReaderParkThreshold() && next < totalPages - 2)
        pageManager.getImmutablePage(new PageId(database, bucket.getFileId(), next++), bucket.getPageSize(), false, true);
      assertThat(pageManager.getReadCacheRAM()).isGreaterThan(pageManager.getReaderParkThreshold());
      final long ramBeforeTheTask = pageManager.getReadCacheRAM();
      held.getFirst().run();
      assertThat(pageManager.isPageCached(late)).as("a prefetch published past the hard limit").isFalse();
      assertThat(pageManager.isReadInFlight(late)).isFalse();
      assertThat(pageManager.getReadCacheRAM()).isEqualTo(ramBeforeTheTask);

      assertThat(pageManager.getStats().prefetchSkippedOverBudget - before.prefetchSkippedOverBudget).isEqualTo(2);
    } finally {
      evictionLock.unlock();
    }
  }

  /**
   * Review M5: {@code arcadedb.maxPageRAM = 0} is a legal setting. It made every evictor threshold 0, and since the park test was
   * {@code >=} every page access parked a reader for up to 100 ms, an empty cache included. The thresholds are now derived from a
   * floored budget and the park test is strict.
   */
  @Test
  void aZeroPageRAMBudgetDoesNotParkEveryRead() {
    // THE PAGE MANAGER READS THE SETTING AT ITS 0 -> 1 STARTUP: CLOSE THE DATABASE OF setUp() AND OPEN ONE UNDER THE NEW VALUE
    db.drop();
    GlobalConfiguration.MAX_PAGE_RAM.setValue(0);
    FileUtils.deleteRecursively(new File(DB_PATH));
    db = new DatabaseFactory(DB_PATH).create();
    final PageManager pageManager = ((DatabaseInternal) db).getPageManager();
    assertThat(pageManager.getStats().maxRAM).as("the page manager restarted under maxPageRAM = 0").isZero();

    db.getSchema().createDocumentType("Item");
    final List<RID> rids = new ArrayList<>();
    db.transaction(() -> {
      for (int i = 0; i < 30; i++)
        rids.add(db.newDocument("Item").set("val", i).set("payload", "x".repeat(PAYLOAD_KB * 1024)).save().getIdentity());
    });

    final PageManager.PPageManagerStats before = pageManager.getStats();
    for (int round = 0; round < 3; round++)
      for (final RID rid : rids)
        db.lookupByRID(rid, true);
    assertThat(pageManager.getStats().readerParks - before.readerParks).as("readers parked under a 0 MB budget").isZero();
  }
}
