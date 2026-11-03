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

import com.arcadedb.engine.LocalBucket;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one warm record read allocates, and how many of them 32 threads get through per second: the SPORK retrieval-speed
 * workstream S3 and S14 (docs/spork/02-retrieval-speed.md, 4.2). {@code LocalBucket.getRecordInternal} used to read the
 * record's page through {@code getImmutablePage}, which builds an {@code ImmutablePage}, a {@code Binary} and a buffer per
 * touch, then handed out a {@code slice} view that {@code lookupByRID} copied again; under {@code READ_COMMITTED} it now
 * reads the page cache's shared view and copies the record straight into a right-sized array. The per-record permission
 * verdict is cached per thread as well.
 * <p>
 * Allocation is measured with {@code ThreadMXBean.getThreadAllocatedBytes} on the measuring thread, for the two shapes the
 * engine reads a record in: the raw {@code LocalBucket.getRecord} (the {@code Binary} alone) and the full
 * {@code Database.lookupByRID} (the record object included). The contents read are checked against what was written, so a
 * cheaper read that answered wrong would be no bargain.
 * <p>
 * Properties: {@code rows} (default 20,000), {@code reads} (default 200,000), {@code threads} (default 32).
 */
@Tag("benchmark")
class RecordReadAllocationBenchmark {
  private static final int    ROWS    = Integer.parseInt(System.getProperty("rows", "20000"));
  private static final int    READS   = Integer.parseInt(System.getProperty("reads", "200000"));
  private static final int    THREADS = Integer.parseInt(System.getProperty("threads", "32"));
  private static final String TYPE    = "Row";
  private static final String PAD     = "p".repeat(160);
  private static final String LARGE   = "L".repeat(100_000);

  @Test
  void bytesPerWarmRecordRead() throws Exception {
    final String path = "./target/databases/recordReadAllocationBench";
    FileUtils.deleteRecursively(new File(path));
    try (final DatabaseFactory factory = new DatabaseFactory(path)) {
      final Database database = factory.create();
      try {
        database.getSchema().buildDocumentType().withName(TYPE).withTotalBuckets(1).create();
        final List<RID> rids = new ArrayList<>(ROWS);
        database.transaction(() -> {
          for (int i = 0; i < ROWS; i++)
            // one row in a hundred outgrows its page: the chain-walk branch of the read is compiled rather than left as an
            // uncommon trap, as it is on any real bucket, so the per-touch page view cannot be scalar-replaced out of existence
            rids.add(database.newDocument(TYPE).set("n", i).set("name", "row-" + i).set("pad", i % 100 == 0 ? LARGE : PAD).save()
                .getIdentity());
        });

        final LocalBucket bucket = (LocalBucket) ((DatabaseInternal) database).getSchema().getBucketById(rids.getFirst().getBucketId());
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long threadId = Thread.currentThread().threadId();
        final SplittableRandom random = new SplittableRandom(42);
        // the measured reads are of plain rows only: a chunk chain read copies its 100 KB and would drown the per-read figure.
        // The chain rows are read in the warm-up below, so the chain branch is in the compiled profile all the same
        final int[] order = new int[READS];
        for (int i = 0; i < READS; i++) {
          int row = random.nextInt(ROWS);
          if (row % 100 == 0)
            row++;
          order[i] = row;
        }

        // the results are kept, so the JIT cannot drop the allocations it is asked to measure
        final Object[] kept = new Object[256];
        for (int round = 0; round < 3; round++)
          for (int i = 0; i < ROWS; i++) {
            kept[i & 255] = bucket.getRecord(rids.get(i));
            kept[(i + 1) & 255] = database.lookupByRID(rids.get(i), true);
          }
        long rawBytes = 0, fullBytes = 0, rawNanos = 0, fullNanos = 0;
        for (int round = 0; round < 4; round++) {
          final long beforeRaw = threads.getThreadAllocatedBytes(threadId);
          final long beginRaw = System.nanoTime();
          for (int i = 0; i < READS; i++)
            kept[i & 255] = bucket.getRecord(rids.get(order[i]));
          rawNanos = System.nanoTime() - beginRaw;
          rawBytes = threads.getThreadAllocatedBytes(threadId) - beforeRaw;

          final long beforeFull = threads.getThreadAllocatedBytes(threadId);
          final long beginFull = System.nanoTime();
          for (int i = 0; i < READS; i++)
            kept[i & 255] = database.lookupByRID(rids.get(order[i]), true);
          fullNanos = System.nanoTime() - beginFull;
          fullBytes = threads.getThreadAllocatedBytes(threadId) - beforeFull;
        }

        for (int i = 0; i < 1_000; i++) {
          final int n = order[i];
          final Document doc = database.lookupByRID(rids.get(n), true).asDocument();
          assertThat(doc.getInteger("n")).isEqualTo(n);
          assertThat(doc.getString("name")).isEqualTo("row-" + n);
        }

        final long manyNanos = runConcurrentLookups(database, rids);

        // System.out, not LogManager: a benchmark's numbers ARE its output, and LogManager INFO does not surface in a surefire run
        System.out.printf("rows=%d reads=%d%n  LocalBucket.getRecord : %6.0f B/read  %6.2f us/read%n  Database.lookupByRID : %6.0f B/read  %6.2f us/read%n  %d threads lookupByRID: %,.0f reads/s (%.2f us each per thread)%n",
            ROWS, READS, (double) rawBytes / READS, rawNanos / 1_000d / READS, (double) fullBytes / READS, fullNanos / 1_000d / READS,
            THREADS, (double) THREADS * READS * 1_000_000_000d / manyNanos, manyNanos / 1_000d / READS);
      } finally {
        if (database.isOpen())
          database.drop();
      }
    } finally {
      FileUtils.deleteRecursively(new File(path));
    }
  }

  private static long runConcurrentLookups(final Database database, final List<RID> rids) throws Exception {
    final CountDownLatch ready = new CountDownLatch(THREADS);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(THREADS);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final List<Thread> workers = new ArrayList<>(THREADS);
    for (int t = 0; t < THREADS; t++) {
      final SplittableRandom random = new SplittableRandom(1_000L + t);
      final Thread worker = new Thread(() -> {
        try {
          ready.countDown();
          go.await();
          int checksum = 0;
          for (int i = 0; i < READS; i++)
            checksum += database.lookupByRID(rids.get(random.nextInt(ROWS)), true).asDocument().getInteger("n");
          if (checksum == Integer.MIN_VALUE)
            System.out.println(checksum);
        } catch (final Throwable e) {
          failure.compareAndSet(null, e);
        } finally {
          done.countDown();
        }
      }, "read-" + t);
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
      throw new AssertionError("a read thread failed", failure.get());
    return elapsed;
  }
}
