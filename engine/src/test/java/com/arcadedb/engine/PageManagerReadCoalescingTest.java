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
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPORK page cache, item S1: N threads missing the same page share ONE physical read instead of each issuing its own
 * (N reads, N page-sized allocations, and the N-1 losers' images thrown away by the version-monotonic put).
 * <p>
 * The physical read is held by the test-only fault injector until every thread has entered the page read, so the
 * threads provably arrive while the read is in flight: the assertion is exact, not statistical.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class PageManagerReadCoalescingTest extends TestHelper {
  private static final int THREADS = 32;

  @AfterEach
  void clearInjector() {
    PageManager.setPageReadFaultInjector(null);
    PageManager.inFlightReadWaitMs = 5_000L;
  }

  @Override
  protected void beginTest() {
    database.getSchema().createDocumentType("Item");
    database.transaction(() -> {
      for (int i = 0; i < 100; i++)
        database.newDocument("Item").set("id", i).save();
    });
  }

  @Test
  void threadsMissingTheSamePageShareOnePhysicalRead() throws Exception {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final RID rid = database.iterateType("Item", false).next().getIdentity();
    final PageId pageId = new PageId(db, rid.getBucketId(), 0);
    final int pageSize = ((LocalBucket) db.getSchema().getBucketById(rid.getBucketId())).getPageSize();

    pageManager.removePageFromCache(pageId);
    assertThat(pageManager.isPageCached(pageId)).isFalse();

    // EVERY THREAD ANNOUNCES ITS LOGICAL READ; THE ONE PHYSICAL READ WAITS FOR ALL OF THEM BEFORE TOUCHING THE FILE
    final CountDownLatch everyoneReading = new CountDownLatch(THREADS);
    final AtomicInteger physicalReads = new AtomicInteger();
    PageManager.setPageReadFaultInjector(new PageManager.PageReadFaultInjector() {
      @Override
      public void onPageRead(final PageId id) {
        if (id.equals(pageId))
          everyoneReading.countDown();
      }

      @Override
      public void onPhysicalPageRead(final PageId id) throws IOException {
        if (!id.equals(pageId))
          return;
        physicalReads.incrementAndGet();
        try {
          if (!everyoneReading.await(30, TimeUnit.SECONDS))
            throw new IOException("the other readers never arrived");
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
    });

    final PageManager.PPageManagerStats before = pageManager.getStats();
    final CyclicBarrier start = new CyclicBarrier(THREADS);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final List<ImmutablePage> pages = new ArrayList<>();
    final List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      final Thread t = new Thread(() -> {
        try {
          start.await();
          final ImmutablePage page = pageManager.getImmutablePage(pageId, pageSize, false, true);
          synchronized (pages) {
            pages.add(page);
          }
        } catch (final Throwable e) {
          failure.compareAndSet(null, e);
        }
      }, "reader-" + i);
      threads.add(t);
      t.start();
    }
    for (final Thread t : threads)
      t.join(60_000);

    assertThat(failure.get()).isNull();
    assertThat(pages).hasSize(THREADS);
    final PageManager.PPageManagerStats after = pageManager.getStats();
    assertThat(physicalReads.get()).as("physical reads of one page missed by %d threads", THREADS).isEqualTo(1);
    assertThat(after.pagesRead - before.pagesRead).as("pages loaded").isEqualTo(1);
    assertThat(after.coalescedReads - before.coalescedReads).as("readers that joined the in-flight read").isEqualTo(THREADS - 1);
    // EVERY READER GOT THE SAME IMAGE
    for (final ImmutablePage page : pages)
      assertThat(page.getVersion()).isEqualTo(pages.getFirst().getVersion());
    assertThat(pageManager.isPageCached(pageId)).isTrue();
  }

  @Test
  void aFailedReadFailsEveryJoinerAndIsRetriedOnTheNextRead() throws Exception {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final RID rid = database.iterateType("Item", false).next().getIdentity();
    final PageId pageId = new PageId(db, rid.getBucketId(), 0);
    final int pageSize = ((LocalBucket) db.getSchema().getBucketById(rid.getBucketId())).getPageSize();
    pageManager.removePageFromCache(pageId);

    final CountDownLatch everyoneReading = new CountDownLatch(THREADS);
    PageManager.setPageReadFaultInjector(new PageManager.PageReadFaultInjector() {
      @Override
      public void onPageRead(final PageId id) {
        if (id.equals(pageId))
          everyoneReading.countDown();
      }

      @Override
      public void onPhysicalPageRead(final PageId id) throws IOException {
        if (!id.equals(pageId))
          return;
        try {
          everyoneReading.await(30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        throw new IOException("simulated disk failure");
      }
    });

    final CyclicBarrier start = new CyclicBarrier(THREADS);
    final AtomicInteger failures = new AtomicInteger();
    final List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      final Thread t = new Thread(() -> {
        try {
          start.await();
          pageManager.getImmutablePage(pageId, pageSize, false, true);
        } catch (final IOException e) {
          failures.incrementAndGet();
        } catch (final Exception e) {
          // not expected: counted as a non-failure so the assertion below reports it
        }
      }, "reader-" + i);
      threads.add(t);
      t.start();
    }
    for (final Thread t : threads)
      t.join(60_000);

    assertThat(failures.get()).as("every reader, owner and joiners alike, sees the failure").isEqualTo(THREADS);
    assertThat(pageManager.isPageCached(pageId)).as("a failed read caches nothing").isFalse();

    // THE FAILED READ LEFT NOTHING BEHIND: THE NEXT READER READS THE PAGE AGAIN, AND SUCCEEDS
    PageManager.setPageReadFaultInjector(null);
    assertThat(pageManager.getImmutablePage(pageId, pageSize, false, true)).isNotNull();
    assertThat(pageManager.isPageCached(pageId)).isTrue();
  }

  /**
   * Review M3: a joiner whose wait for the read in flight runs out takes the read over and reads the page itself. Before, it
   * found the same unsettled future at the next lap and waited again, for ever if the owner never settled it.
   */
  @Test
  void aJoinerWhoseWaitRunsOutReadsThePageItself() throws Exception {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final RID rid = database.iterateType("Item", false).next().getIdentity();
    final PageId pageId = new PageId(db, rid.getBucketId(), 0);
    final int pageSize = ((LocalBucket) db.getSchema().getBucketById(rid.getBucketId())).getPageSize();
    pageManager.removePageFromCache(pageId);
    PageManager.inFlightReadWaitMs = 200L;

    // THE FIRST PHYSICAL READ (THE OWNER'S) STALLS UNTIL THE TEST LETS IT GO; EVERY LATER ONE PROCEEDS
    final CountDownLatch ownerReading = new CountDownLatch(1);
    final CountDownLatch releaseOwner = new CountDownLatch(1);
    final AtomicInteger physicalReads = new AtomicInteger();
    PageManager.setPageReadFaultInjector(new PageManager.PageReadFaultInjector() {
      @Override
      public void onPageRead(final PageId id) {
      }

      @Override
      public void onPhysicalPageRead(final PageId id) throws IOException {
        if (!id.equals(pageId) || physicalReads.incrementAndGet() > 1)
          return;
        ownerReading.countDown();
        try {
          releaseOwner.await(60, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });

    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<ImmutablePage> joinerPage = new AtomicReference<>();
    final Thread owner = new Thread(() -> {
      try {
        pageManager.getImmutablePage(pageId, pageSize, false, true);
      } catch (final Throwable e) {
        failure.compareAndSet(null, e);
      }
    }, "owner");
    final Thread joiner = new Thread(() -> {
      try {
        joinerPage.set(pageManager.getImmutablePage(pageId, pageSize, false, true));
      } catch (final Throwable e) {
        failure.compareAndSet(null, e);
      }
    }, "joiner");
    try {
      final PageManager.PPageManagerStats before = pageManager.getStats();
      owner.start();
      assertThat(ownerReading.await(30, TimeUnit.SECONDS)).isTrue();
      joiner.start();
      // A HANG DETECTOR, NOT A LATENCY BOUND: THE JOINER IS DONE 200 MS AFTER IT JOINED, THE BUG NEVER
      joiner.join(30_000);
      assertThat(joiner.isAlive()).as("the joiner is still waiting for a read that is not progressing").isFalse();
      assertThat(owner.isAlive()).as("the owner is still held").isTrue();
      assertThat(joinerPage.get()).isNotNull();
      assertThat(physicalReads.get()).as("the joiner read the page itself").isEqualTo(2);
      assertThat(pageManager.getStats().inFlightReadTimeouts - before.inFlightReadTimeouts).isEqualTo(1);
    } finally {
      releaseOwner.countDown();
      owner.join(60_000);
      joiner.join(60_000);
    }
    assertThat(failure.get()).isNull();
    assertThat(pageManager.isReadInFlight(pageId)).isFalse();
  }

  /**
   * An owner interrupted before its read sends its joiners to read the page themselves: its future fails with the
   * {@code InterruptedIOException} of the per-page I/O interlock's pre-acquire check, which a joiner reads as "read it
   * yourself", not as its own failure. An interrupt that lands DURING the channel read is a different story: the channel
   * closes, {@code PaginatedComponentFile} reopens it and the read completes, so the joiners get the page from the owner.
   */
  @Test
  void anInterruptedOwnerSendsItsJoinersToReadThePageThemselves() throws Exception {
    final DatabaseInternal db = (DatabaseInternal) database;
    final PageManager pageManager = db.getPageManager();
    final RID rid = database.iterateType("Item", false).next().getIdentity();
    final PageId pageId = new PageId(db, rid.getBucketId(), 0);
    final int pageSize = ((LocalBucket) db.getSchema().getBucketById(rid.getBucketId())).getPageSize();
    pageManager.removePageFromCache(pageId);

    final int joiners = 4;
    final long coalescedBefore = pageManager.getStats().coalescedReads;
    final AtomicInteger physicalReads = new AtomicInteger();
    PageManager.setPageReadFaultInjector(new PageManager.PageReadFaultInjector() {
      @Override
      public void onPageRead(final PageId id) {
      }

      @Override
      public void onPhysicalPageRead(final PageId id) {
        if (!id.equals(pageId) || physicalReads.incrementAndGet() > 1)
          return;
        // THE OWNER: WAIT FOR EVERY JOINER TO PARK ON ITS FUTURE, THEN GET INTERRUPTED BEFORE THE I/O INTERLOCK
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (pageManager.getStats().coalescedReads - coalescedBefore < joiners && System.nanoTime() < deadline)
          Thread.onSpinWait();
        Thread.currentThread().interrupt();
      }
    });

    final AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
    final AtomicReference<Throwable> joinerFailure = new AtomicReference<>();
    final List<ImmutablePage> pages = new ArrayList<>();
    final Thread owner = new Thread(() -> {
      try {
        pageManager.getImmutablePage(pageId, pageSize, false, true);
      } catch (final Throwable e) {
        ownerFailure.set(e);
      }
    }, "owner");
    owner.start();
    // THE JOINERS START ONCE THE OWNER HAS INSTALLED ITS FUTURE (ITS PHYSICAL READ HAS BEGUN)
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (physicalReads.get() == 0 && System.nanoTime() < deadline)
      Thread.onSpinWait();

    final List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < joiners; i++) {
      final Thread t = new Thread(() -> {
        try {
          final ImmutablePage page = pageManager.getImmutablePage(pageId, pageSize, false, true);
          synchronized (pages) {
            pages.add(page);
          }
        } catch (final Throwable e) {
          joinerFailure.compareAndSet(null, e);
        }
      }, "joiner-" + i);
      threads.add(t);
      t.start();
    }
    owner.join(60_000);
    for (final Thread t : threads)
      t.join(60_000);

    assertThat(ownerFailure.get()).as("the owner reports its interrupt").isInstanceOf(InterruptedIOException.class);
    assertThat(joinerFailure.get()).as("no joiner fails with the owner's interrupt").isNull();
    assertThat(pages).hasSize(joiners);
    assertThat(physicalReads.get()).as("the owner's read and the one a joiner made instead").isGreaterThanOrEqualTo(2);
    assertThat(pageManager.isPageCached(pageId)).isTrue();
    assertThat(pageManager.isReadInFlight(pageId)).isFalse();
  }
}
