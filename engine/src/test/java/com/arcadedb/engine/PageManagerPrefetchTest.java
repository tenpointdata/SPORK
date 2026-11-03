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
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.utility.DedicatedThreadPool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SPORK page cache: {@link PageManager#prefetch(java.util.Collection)} loads the pages it is given on the dedicated
 * {@link PagePrefetchPool}, skipping the ones already cached, so a caller about to read many pages has them in flight
 * at once instead of one device round trip at a time.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class PageManagerPrefetchTest extends TestHelper {
  private static final int RECORDS = 2_000;

  private int bucketFileId;

  @AfterEach
  void clearInjector() {
    PageManager.setPageReadFaultInjector(null);
  }

  @Override
  protected void beginTest() {
    database.getSchema().createDocumentType("Item");
    database.transaction(() -> {
      for (int i = 0; i < RECORDS; i++) {
        final MutableDocument doc = database.newDocument("Item");
        doc.set("id", i);
        doc.set("payload", "x".repeat(2_000));
        doc.save();
      }
    });
    bucketFileId = ((LocalBucket) database.getSchema().getType("Item").getBuckets(false).getFirst()).getFileId();
    // A PAGE STILL IN THE FLUSH QUEUE IS ANSWERED FROM THE QUEUE, NOT READ: THE TESTS BELOW COUNT PHYSICAL READS
    ((DatabaseInternal) database).getPageManager().waitAllPagesOfDatabaseAreFlushed(database);
  }

  @Test
  void prefetchesTheMissesOnceAndSkipsTheHits() {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final LocalBucket bucket = (LocalBucket) db.getSchema().getBucketById(bucketFileId);
    final int totalPages = bucket.getTotalPages();
    assertThat(totalPages).isGreaterThan(8);

    final List<PageId> pages = new ArrayList<>();
    for (int i = 0; i < totalPages; i++)
      pages.add(new PageId(db, bucketFileId, i));

    pageManager.removeAllReadPagesOfDatabase(db);
    for (final PageId pageId : pages)
      assertThat(pageManager.isPageCached(pageId)).isFalse();

    final PageManager.PPageManagerStats before = pageManager.getStats();
    final DedicatedThreadPool.PoolStats poolBefore = PagePrefetchPool.getInstance().getPoolStats();
    final int issued = pageManager.prefetch(pages);
    assertThat(issued).as("reads issued for %d cold pages", totalPages).isEqualTo(totalPages);

    await().until(() -> pages.stream().allMatch(pageManager::isPageCached));
    final PageManager.PPageManagerStats after = pageManager.getStats();
    assertThat(after.pagesRead - before.pagesRead).isEqualTo(totalPages);
    assertThat(after.prefetchRequested - before.prefetchRequested).isEqualTo(totalPages);
    assertThat(after.prefetchIssued - before.prefetchIssued).isEqualTo(totalPages);

    // THE POOL DID THE WORK (OR, SATURATED, THE CALLER DID: BOTH ARE COUNTED)
    await().until(() -> {
      final DedicatedThreadPool.PoolStats poolAfter = PagePrefetchPool.getInstance().getPoolStats();
      return poolAfter.completedTasks() + poolAfter.callerRunFallbacks() - poolBefore.completedTasks()
          - poolBefore.callerRunFallbacks() >= totalPages;
    });

    // WARM: NOTHING TO DO
    assertThat(pageManager.prefetch(pages)).isZero();
    assertThat(pageManager.getStats().pagesRead).isEqualTo(after.pagesRead);
  }

  @Test
  void aPagePastTheEndOfTheFileIsIgnored() {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final LocalBucket bucket = (LocalBucket) db.getSchema().getBucketById(bucketFileId);
    final PageId beyond = new PageId(db, bucketFileId, bucket.getTotalPages() + 100);

    final PageManager.PPageManagerStats before = pageManager.getStats();
    pageManager.prefetch(List.of(beyond));
    await().until(() -> {
      final DedicatedThreadPool.PoolStats stats = PagePrefetchPool.getInstance().getPoolStats();
      return stats.queueDepth() == 0 && stats.activeThreads() == 0;
    });
    assertThat(pageManager.isPageCached(beyond)).isFalse();
    assertThat(pageManager.getStats().pagesRead).isEqualTo(before.pagesRead);
  }

  @Test
  void aSinglePagePrefetchIsAnsweredByTheNextRead() throws Exception {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final LocalBucket bucket = (LocalBucket) db.getSchema().getBucketById(bucketFileId);
    final PageId pageId = new PageId(db, bucketFileId, 3);
    pageManager.removePageFromCache(pageId);

    final PageManager.PPageManagerStats before = pageManager.getStats();
    assertThat(pageManager.prefetch(pageId)).isTrue();
    // THE READ EITHER FINDS THE PAGE CACHED OR JOINS THE READ IN FLIGHT: ONE PHYSICAL READ EITHER WAY
    assertThat(pageManager.getImmutablePage(pageId, bucket.getPageSize(), false, true)).isNotNull();
    await().until(() -> PagePrefetchPool.getInstance().getPoolStats().activeThreads() == 0);
    assertThat(pageManager.getStats().pagesRead - before.pagesRead).isEqualTo(1);
  }

  /**
   * Review M1: a prefetch that has read the page and not published it yet is held while the database is closed and opened
   * again at the same path. The prefetch holds the database's shared lock, so the close waits for it and its teardown sweeps
   * the page; before, the close ran under the prefetch and the image of the closed lifecycle was put in the cache after the
   * sweep, where the reopened database (same path, so the same page ids) was served it without reading the file. A second
   * database keeps the page manager alive across the close, as any server with more than one database does.
   */
  @Test
  void aPrefetchInFlightAcrossACloseLeavesNothingToTheReopenedDatabase() throws Exception {
    final Database keeper = new DatabaseFactory(getDatabasePath() + "-keeper").create();
    try {
      final DatabaseInternal db = (DatabaseInternal) database;
      final PageManager pageManager = db.getPageManager();
      final PageId pageId = new PageId(db, bucketFileId, 3);
      final int pageSize = ((LocalBucket) db.getSchema().getBucketById(bucketFileId)).getPageSize();
      pageManager.removePageFromCache(pageId);

      runWhileAPrefetchIsHeldAfterItsRead(pageManager, pageId, () -> database.close());
      assertThat(database.isOpen()).isFalse();

      reopenDatabase();
      final DatabaseInternal reopened = (DatabaseInternal) database;
      final PageId samePage = new PageId(reopened, bucketFileId, 3);
      assertThat(pageManager.isPageCached(samePage)).as("an image of the closed lifecycle is cached").isFalse();
      assertThat(pageManager.isReadInFlight(samePage)).isFalse();

      final long readsBefore = pageManager.getStats().pagesRead;
      assertThat(pageManager.getImmutablePage(samePage, pageSize, false, true)).isNotNull();
      assertThat(pageManager.getStats().pagesRead - readsBefore).as("the reopened database reads the page from disk").isEqualTo(1);
    } finally {
      keeper.drop();
    }
  }

  /** Review M1, the drop flavour: the database recreated at the same path must not be served the dropped one's page. */
  @Test
  void aPrefetchInFlightAcrossADropLeavesNothingToTheRecreatedDatabase() throws Exception {
    final Database keeper = new DatabaseFactory(getDatabasePath() + "-keeper").create();
    try {
      final DatabaseInternal db = (DatabaseInternal) database;
      final PageManager pageManager = db.getPageManager();
      final PageId pageId = new PageId(db, bucketFileId, 3);
      pageManager.removePageFromCache(pageId);

      runWhileAPrefetchIsHeldAfterItsRead(pageManager, pageId, () -> ((DatabaseInternal) database).getEmbedded().drop());
      assertThat(database.isOpen()).isFalse();

      database = factory.create();
      database.getSchema().createDocumentType("Item");
      final DatabaseInternal recreated = (DatabaseInternal) database;
      final int newFileId = ((LocalBucket) recreated.getSchema().getType("Item").getBuckets(false).getFirst()).getFileId();
      assertThat(newFileId).as("the recreated type reuses the file id, so the page ids collide").isEqualTo(bucketFileId);

      final PageId samePage = new PageId(recreated, bucketFileId, 3);
      assertThat(pageManager.isPageCached(samePage)).as("the dropped database's page is cached for the recreated one").isFalse();
      assertThat(pageManager.isReadInFlight(samePage)).isFalse();
    } finally {
      keeper.drop();
    }
  }

  /**
   * Issues a prefetch of {@code pageId}, holds it once it has the page's bytes, runs {@code teardown} on another thread, lets
   * the prefetch go once the teardown has either finished (the bug) or is waiting for the database (the fix), and waits for
   * both.
   */
  private void runWhileAPrefetchIsHeldAfterItsRead(final PageManager pageManager, final PageId pageId, final Runnable teardown)
      throws Exception {
    final CountDownLatch held = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    PageManager.setPageReadFaultInjector(new PageManager.PageReadFaultInjector() {
      @Override
      public void onPageRead(final PageId id) {
      }

      @Override
      public void onPhysicalPageReadCompleted(final PageId id) throws IOException {
        if (!id.equals(pageId))
          return;
        held.countDown();
        try {
          if (!release.await(30, TimeUnit.SECONDS))
            throw new IOException("never released");
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
    });

    final Thread teardownThread;
    final AtomicReference<Throwable> teardownFailure = new AtomicReference<>();
    try {
      assertThat(pageManager.prefetch(pageId)).isTrue();
      assertThat(held.await(30, TimeUnit.SECONDS)).as("the prefetch read the page").isTrue();

      teardownThread = new Thread(() -> {
        try {
          teardown.run();
        } catch (final Throwable e) {
          teardownFailure.set(e);
        }
      }, "teardown");
      teardownThread.start();
      // THE FIX: THE TEARDOWN WAITS FOR THE DATABASE'S EXCLUSIVE LOCK, WHICH THE PREFETCH'S SHARED LOCK HOLDS OFF. THE BUG: IT
      // RUNS TO THE END UNDER THE PREFETCH
      await().atMost(30, TimeUnit.SECONDS).until(() -> !teardownThread.isAlive() || teardownThread.getState() == Thread.State.WAITING
          || teardownThread.getState() == Thread.State.TIMED_WAITING);
    } finally {
      release.countDown();
    }
    teardownThread.join(60_000);
    assertThat(teardownThread.isAlive()).isFalse();
    assertThat(teardownFailure.get()).isNull();
    await().atMost(30, TimeUnit.SECONDS).until(() -> !pageManager.isReadInFlight(pageId));
    PageManager.setPageReadFaultInjector(null);
  }

  /**
   * Review M3: a pool that shuts down between the prefetch's check and its submission runs nothing (the caller-runs policy
   * drops a plain task on a shut-down pool, a {@code shutdownNow()} drains the queue). The future installed for the page must
   * not stay behind, or every later reader of the page joins a read nobody performs.
   */
  @Test
  void aPoolThatShutsDownUnderTheSubmissionLeavesNoReadInFlight() throws Exception {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final PageId pageId = new PageId(db, bucketFileId, 4);
    final int pageSize = ((LocalBucket) db.getSchema().getBucketById(bucketFileId)).getPageSize();
    pageManager.removePageFromCache(pageId);

    final ShuttingDownExecutor executor = new ShuttingDownExecutor();
    assertThat(pageManager.prefetch(pageId, executor)).as("nothing was issued").isFalse();
    assertThat(executor.dropped).as("the pool took the task and dropped it").isEqualTo(1);
    assertThat(pageManager.isReadInFlight(pageId)).as("the future of a task that never runs").isFalse();

    // THE NEXT READER READS THE PAGE ITSELF, AT ONCE, INSTEAD OF JOINING A READ NOBODY PERFORMS
    final PageManager.PPageManagerStats before = pageManager.getStats();
    assertThat(pageManager.getImmutablePage(pageId, pageSize, false, true)).isNotNull();
    final PageManager.PPageManagerStats after = pageManager.getStats();
    assertThat(after.coalescedReads - before.coalescedReads).isZero();
    assertThat(after.pagesRead - before.pagesRead).isEqualTo(1);
  }

  /** An executor that is open when asked, and shuts down as the task is handed to it: the task is neither run nor queued. */
  private static final class ShuttingDownExecutor extends AbstractExecutorService {
    private volatile boolean shutdown;
    private          int     dropped;

    @Override
    public void execute(final Runnable command) {
      shutdown = true;
      ++dropped;
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
      return true;
    }
  }
}
