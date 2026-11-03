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
package com.arcadedb.index;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.schema.Schema;
import com.arcadedb.utility.FileUtils;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Point lookups on a warm UNIQUE index from 1 and from 32 threads at once, on a HASH and on an LSM_TREE index: the
 * SPORK retrieval-speed workstream S17 (docs/spork/02-retrieval-speed.md, 4.2). Every {@code get} on either index used
 * to run under one single-stripe FAIR {@code ReentrantReadWriteLock}, so with 32 hydrate threads on one index the lock
 * word, not the page cache, bounded the warm lookup rate; the lock is now striped per thread and non-fair, and the HASH
 * bucket reads its pages through the page cache's shared view instead of allocating a view per touch.
 * <p>
 * The number that matters is the 32-thread lookups/s against the 1-thread figure: a lock-bound index scales far below
 * the thread count, an unbound one scales with the cores. The lookups find every key they ask for, which is the only
 * assertion, so a faster lookup that answered wrong would be no bargain.
 * <p>
 * Properties: {@code rows} (default 100,000), {@code threads} (default 32), {@code lookupsPerThread} (default 50,000),
 * {@code indexTypes} (default {@code HASH,LSM_TREE}).
 */
@Tag("benchmark")
class IndexPointLookupContentionBenchmark {
  private static final int    ROWS               = Integer.parseInt(System.getProperty("rows", "100000"));
  private static final int    THREADS            = Integer.parseInt(System.getProperty("threads", "32"));
  private static final int    LOOKUPS_PER_THREAD = Integer.parseInt(System.getProperty("lookupsPerThread", "50000"));
  private static final String INDEX_TYPES        = System.getProperty("indexTypes", "HASH,LSM_TREE");
  private static final String TYPE               = "Row";

  @Test
  void pointLookupsFromOneAndFromManyThreads() throws Exception {
    for (final String indexType : INDEX_TYPES.split(","))
      measure(Schema.INDEX_TYPE.valueOf(indexType.trim()));
  }

  private void measure(final Schema.INDEX_TYPE indexType) throws Exception {
    final String path = "./target/databases/idxPointLookupBench" + indexType;
    FileUtils.deleteRecursively(new File(path));
    try (final DatabaseFactory factory = new DatabaseFactory(path)) {
      final Database database = factory.create();
      try {
        database.getSchema().buildDocumentType().withName(TYPE).withTotalBuckets(1).create().createProperty("uid", String.class);
        database.getSchema().buildTypeIndex(TYPE, new String[] { "uid" }).withType(indexType).withUnique(true).create();

        for (int from = 0; from < ROWS; from += 10_000) {
          final int start = from;
          final int end = Math.min(ROWS, from + 10_000);
          database.transaction(() -> {
            for (int i = start; i < end; i++)
              database.newDocument(TYPE).set("uid", key(i)).save();
          });
        }

        final TypeIndex index = (TypeIndex) database.getSchema().getIndexByName(TYPE + "[uid]");

        // warm: every page the lookups touch is in the page cache, and the JIT has seen the path
        for (int round = 0; round < 2; round++)
          runLookups(database, index, 1, ROWS, 1L);

        final long single = runLookups(database, index, 1, LOOKUPS_PER_THREAD, 7L);
        final long many = runLookups(database, index, THREADS, LOOKUPS_PER_THREAD, 11L);
        final double bytesPerLookup = bytesPerLookup(database, index);

        final double singleRate = LOOKUPS_PER_THREAD * 1_000_000_000d / single;
        final double manyRate = (double) THREADS * LOOKUPS_PER_THREAD * 1_000_000_000d / many;

        // System.out, not LogManager: a benchmark's numbers ARE its output, and LogManager INFO does not surface in a surefire run
        System.out.printf("%-8s rows=%d  1 thread: %,.0f lookups/s (%.2f us each, %.0f B each)  %d threads: %,.0f lookups/s (%.2f us each per thread)  scaling x%.1f%n",
            indexType, ROWS, singleRate, single / 1_000d / LOOKUPS_PER_THREAD, bytesPerLookup, THREADS, manyRate,
            many / 1_000d / LOOKUPS_PER_THREAD, manyRate / singleRate);
      } finally {
        if (database.isOpen())
          database.drop();
      }
    } finally {
      FileUtils.deleteRecursively(new File(path));
    }
  }

  /** Runs {@code lookups} point lookups of existing keys on each of {@code threads} threads at once and returns the elapsed nanos. */
  private static long runLookups(final Database database, final TypeIndex index, final int threads, final int lookups, final long seed)
      throws Exception {
    final CountDownLatch ready = new CountDownLatch(threads);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final AtomicLong found = new AtomicLong();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final List<Thread> workers = new ArrayList<>(threads);

    for (int t = 0; t < threads; t++) {
      final SplittableRandom random = new SplittableRandom(seed * 1_000_003L + t);
      final Thread worker = new Thread(() -> {
        try {
          ready.countDown();
          go.await();
          database.transaction(() -> {
            long hits = 0;
            for (int i = 0; i < lookups; i++) {
              final IndexCursor cursor = index.get(new Object[] { key(random.nextInt(ROWS)) });
              if (cursor.hasNext() && cursor.next() != null)
                hits++;
            }
            found.addAndGet(hits);
          });
        } catch (final Throwable e) {
          failure.compareAndSet(null, e);
        } finally {
          done.countDown();
        }
      }, "lookup-" + t);
      workers.add(worker);
      worker.start();
    }

    ready.await();
    final long begin = System.nanoTime();
    go.countDown();
    done.await();
    final long elapsed = System.nanoTime() - begin;

    for (final Thread worker : workers)
      worker.join();
    if (failure.get() != null)
      throw new AssertionError("a lookup thread failed", failure.get());
    assertThat(found.get()).as("every lookup of an existing key finds it").isEqualTo((long) threads * lookups);
    return elapsed;
  }

  /** Bytes one warm point lookup allocates on the calling thread, inside a read-only transaction. */
  private static double bytesPerLookup(final Database database, final TypeIndex index) {
    final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long threadId = Thread.currentThread().threadId();
    final int lookups = 20_000;
    final Object[] kept = new Object[64];
    final long[] bytes = new long[1];
    database.transaction(() -> {
      for (int round = 0; round < 3; round++) {
        final long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < lookups; i++)
          kept[i & 63] = index.get(new Object[] { key(i % ROWS) });
        bytes[0] = threads.getThreadAllocatedBytes(threadId) - before;
      }
    });
    assertThat(kept[0]).isNotNull();
    return (double) bytes[0] / lookups;
  }

  private static String key(final int i) {
    return "uid-" + i;
  }
}
