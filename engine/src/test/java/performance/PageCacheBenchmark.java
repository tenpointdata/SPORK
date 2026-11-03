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
package performance;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.LocalDatabase;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.Binary;
import com.arcadedb.database.Record;
import com.arcadedb.database.RecordFactory;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.engine.LocalBucket;
import com.arcadedb.engine.PageId;
import com.arcadedb.engine.PageManager;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The SPORK page-cache work, measured before and after in one JVM through the page manager's test-only switches
 * ({@link PageManager#setReadCoalescingEnabled}, {@link PageManager#setBackgroundEvictionEnabled}):
 * <ol>
 *   <li>32 threads missing the same page: physical reads issued and wall time, every reader its own read (before) against one
 *   shared read (after, SPORK S1);</li>
 *   <li>eviction stall: readers' lookup latency distribution while the cache churns at the configured size, sort-the-world on the
 *   reader's thread (before) against the CLOCK evictor thread (after, SPORK S16/M1);</li>
 *   <li>cold hydrate of 2,000 RIDs: one {@code lookupByRID} after the other against {@code lookupByRIDs} (SPORK L1);</li>
 *   <li>a micro of the page read itself: {@code FileChannel.read} into the heap page against a per-thread direct buffer and one
 *   copy, which is what decided that SPORK S2's "pooled direct buffer" was not worth duplicating (the JDK already does it);</li>
 *   <li>the per-record re-check {@code FetchFromRidsStep} makes when it hands out a record its batch loaded ahead of the row,
 *   inside a transaction that has written (the worst case).</li>
 * </ol>
 * Tagged {@code benchmark}: it prints tables and asserts nothing. Narrow it with {@code -Dbench.cacheMB=2048 -Dbench.seconds=5
 * -Dbench.readers=8 -Dbench.rids=2000}. "Cold" here means evicted from the page cache; the OS page cache stays warm, so a cold
 * read costs a syscall and a copy (about 6 us a page), not a device round trip.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
@Tag("benchmark")
class PageCacheBenchmark {
  private static final String DB_PATH    = "target/databases/PageCacheBenchmark";
  private static final int    PAYLOAD    = 10 * 1024;
  private static final int    THREADS    = 32;

  /** Runs outside Maven too, so two builds can be compared side by side on the same machine. */
  public static void main(final String[] args) throws Exception {
    final PageCacheBenchmark benchmark = new PageCacheBenchmark();
    benchmark.readCoalescing();
    benchmark.evictionStall();
    benchmark.hydrate();
    benchmark.warmHydrate();
    benchmark.ridListRecheck();
    benchmark.pageReadCopy();
  }

  @Test
  void readCoalescing() throws Exception {
    final int pages = Integer.parseInt(System.getProperty("bench.pages", "200"));
    GlobalConfiguration.MAX_PAGE_RAM.setValue(256);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final Database db = new DatabaseFactory(DB_PATH).create()) {
      final List<RID> rids = fill(db, pages * 5);
      final DatabaseInternal database = (DatabaseInternal) db;
      final PageManager pageManager = database.getPageManager();
      pageManager.waitAllPagesOfDatabaseAreFlushed(db);
      final int fileId = rids.getFirst().getBucketId();
      final int pageSize = ((LocalBucket) database.getSchema().getBucketById(fileId)).getPageSize();
      final int totalPages = Math.min(pages, ((LocalBucket) database.getSchema().getBucketById(fileId)).getTotalPages());

      System.out.printf(Locale.ENGLISH, "%n(a) %d threads missing the same page, %d pages%n", THREADS, totalPages);
      System.out.printf(Locale.ENGLISH, "%-8s %12s %12s %12s %10s%n", "mode", "pagesRead", "coalesced", "allocMB", "ms");
      for (final boolean coalescing : new boolean[] { false, true, false, true }) {
        PageManager.setReadCoalescingEnabled(coalescing);
        final PageManager.PPageManagerStats before = pageManager.getStats();
        final long begin = System.nanoTime();
        for (int p = 0; p < totalPages; p++) {
          final PageId pageId = new PageId(database, fileId, p);
          pageManager.removePageFromCache(pageId);
          final CyclicBarrier start = new CyclicBarrier(THREADS);
          final Thread[] threads = new Thread[THREADS];
          for (int t = 0; t < THREADS; t++) {
            threads[t] = new Thread(() -> {
              try {
                start.await();
                pageManager.getImmutablePage(pageId, pageSize, false, true);
              } catch (final Exception e) {
                throw new RuntimeException(e);
              }
            });
            threads[t].start();
          }
          for (final Thread t : threads)
            t.join();
        }
        final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        final PageManager.PPageManagerStats after = pageManager.getStats();
        System.out.printf(Locale.ENGLISH, "%-8s %12d %12d %12d %10d%n", coalescing ? "after" : "before", after.pagesRead - before.pagesRead,
            after.coalescedReads - before.coalescedReads, (after.pagesReadSize - before.pagesReadSize) / (1024 * 1024), ms);
      }
    } finally {
      PageManager.setReadCoalescingEnabled(true);
      GlobalConfiguration.MAX_PAGE_RAM.reset();
      FileUtils.deleteRecursively(new File(DB_PATH));
    }
  }

  @Test
  void evictionStall() throws Exception {
    final long heapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
    final long cacheMB = Long.parseLong(System.getProperty("bench.cacheMB", String.valueOf(Math.min(2048L, heapMB / 3))));
    final int readers = Integer.parseInt(System.getProperty("bench.readers", "8"));
    final long seconds = Long.parseLong(System.getProperty("bench.seconds", "5"));
    // 1.5x THE CACHE OF RECORDS, SO THE READERS MISS AND THE CACHE CHURNS
    final int records = (int) (cacheMB * 1024 * 1024 * 3 / 2 / PAYLOAD);

    GlobalConfiguration.MAX_PAGE_RAM.setValue(cacheMB);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final Database db = new DatabaseFactory(DB_PATH).create()) {
      final List<RID> rids = fill(db, records);
      final PageManager pageManager = ((DatabaseInternal) db).getPageManager();
      pageManager.waitAllPagesOfDatabaseAreFlushed(db);
      System.out.printf(Locale.ENGLISH, "%n(b) eviction stall: cache %d MB (%d pages at 64KB), %d records of %d KB, %d readers, %d s per mode%n",
          cacheMB, cacheMB * 16, records, PAYLOAD / 1024, readers, seconds);
      System.out.printf(Locale.ENGLISH, "%-8s %10s %9s %9s %9s %9s %9s %9s %8s %8s %8s%n", "mode", "lookups/s", "mean us", "p99 us",
          "p99.9 us", "max us", "evicts", "syncRuns", "wakeups", "parks", "cacheMB");

      for (final boolean background : new boolean[] { false, true, false, true }) {
        PageManager.setBackgroundEvictionEnabled(background);
        // WARM THE CACHE TO ITS LIMIT BEFORE MEASURING, SO EVERY MODE STARTS FROM A FULL CACHE
        for (int i = 0; i < rids.size(); i++)
          db.lookupByRID(rids.get(i), true);

        final PageManager.PPageManagerStats before = pageManager.getStats();
        final AtomicBoolean stop = new AtomicBoolean();
        final long[][] samples = new long[readers][];
        final int[] counts = new int[readers];
        final Thread[] threads = new Thread[readers];
        for (int r = 0; r < readers; r++) {
          final int reader = r;
          samples[r] = new long[4_000_000];
          threads[r] = new Thread(() -> {
            final Random random = new Random(reader);
            int n = 0;
            while (!stop.get() && n < samples[reader].length) {
              final RID rid = rids.get(random.nextInt(rids.size()));
              final long begin = System.nanoTime();
              db.lookupByRID(rid, true);
              samples[reader][n++] = System.nanoTime() - begin;
            }
            counts[reader] = n;
          });
          threads[r].start();
        }
        Thread.sleep(seconds * 1000);
        stop.set(true);
        for (final Thread t : threads)
          t.join();

        int total = 0;
        for (final int c : counts)
          total += c;
        final long[] all = new long[total];
        int k = 0;
        for (int r = 0; r < readers; r++) {
          System.arraycopy(samples[r], 0, all, k, counts[r]);
          k += counts[r];
        }
        Arrays.sort(all);
        long sum = 0;
        for (final long s : all)
          sum += s;
        final PageManager.PPageManagerStats after = pageManager.getStats();
        System.out.printf(Locale.ENGLISH, "%-8s %10d %9d %9d %9d %9d %9d %9d %8d %8d %8d%n", background ? "after" : "before",
            total / seconds, total == 0 ? 0 : sum / total / 1000, percentile(all, 0.99) / 1000, percentile(all, 0.999) / 1000,
            total == 0 ? 0 : all[total - 1] / 1000, after.pagesEvicted - before.pagesEvicted,
            after.synchronousEvictionRuns - before.synchronousEvictionRuns, after.evictorWakeups - before.evictorWakeups,
            after.readerParks - before.readerParks, after.readCacheRAM / (1024 * 1024));
      }
    } finally {
      PageManager.setBackgroundEvictionEnabled(true);
      GlobalConfiguration.MAX_PAGE_RAM.reset();
      FileUtils.deleteRecursively(new File(DB_PATH));
    }
  }

  @Test
  void hydrate() throws Exception {
    final int count = Integer.parseInt(System.getProperty("bench.rids", "2000"));
    final int rounds = Integer.parseInt(System.getProperty("bench.rounds", "7"));
    GlobalConfiguration.MAX_PAGE_RAM.setValue(1024);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final Database db = new DatabaseFactory(DB_PATH).create()) {
      // 609-BYTE ROWS, AS THE RECON RECORD, OVER ENOUGH PAGES THAT 2,000 RANDOM RIDs TOUCH ABOUT 2,000 DISTINCT PAGES
      final List<RID> rids = fill(db, 200_000, 600);
      final DatabaseInternal database = (DatabaseInternal) db;
      final LocalDatabase local = (LocalDatabase) database.getEmbedded();
      final PageManager pageManager = database.getPageManager();
      pageManager.waitAllPagesOfDatabaseAreFlushed(db);

      final Random random = new Random(7);
      final RID[] asked = new RID[count];
      for (int i = 0; i < count; i++)
        asked[i] = rids.get(random.nextInt(rids.size()));

      System.out.printf(Locale.ENGLISH, "%n(c) hydrate of %d RIDs over %d records, %d rounds (median | best)%n", count, rids.size(), rounds);
      System.out.printf(Locale.ENGLISH, "%-22s %10s %10s %10s%n", "mode", "ms median", "ms best", "pagesRead");
      for (final String mode : new String[] { "cold serial", "cold lookupByRIDs", "warm serial", "warm lookupByRIDs" }) {
        final long[] times = new long[rounds];
        long pagesRead = 0;
        for (int round = 0; round < rounds; round++) {
          if (mode.startsWith("cold"))
            pageManager.removeAllReadPagesOfDatabase(db);
          else
            for (final RID rid : asked)
              db.lookupByRID(rid, true);
          final PageManager.PPageManagerStats before = pageManager.getStats();
          final long begin = System.nanoTime();
          if (mode.endsWith("serial")) {
            for (final RID rid : asked)
              db.lookupByRID(rid, true);
          } else {
            final Record[] records = local.lookupByRIDs(asked, true);
            if (records.length != count)
              throw new IllegalStateException();
          }
          times[round] = System.nanoTime() - begin;
          pagesRead = pageManager.getStats().pagesRead - before.pagesRead;
        }
        Arrays.sort(times);
        System.out.printf(Locale.ENGLISH, "%-22s %10.2f %10.2f %10d%n", mode, times[rounds / 2] / 1e6, times[0] / 1e6, pagesRead);
      }
    } finally {
      GlobalConfiguration.MAX_PAGE_RAM.reset();
      FileUtils.deleteRecursively(new File(DB_PATH));
    }
  }

  /**
   * The warm-path question of the review: is the batched hydrate of cached records slower than one lookup each, and where does
   * the time go. Unlike {@link #hydrate()} it runs enough rounds for C2 to compile both paths ({@code bench.warmRounds}, default
   * 250, the first {@code bench.warmDiscard} = 50 discarded), runs the serial and the batched call as an A/B PAIR in every round
   * (alternating which goes first, so the machine's noise lands on both), and splits each path into its phases by replaying them
   * through the same public calls: the packing and the two sorts, {@code LocalBucket.getRecords} (page touches, the prefetch
   * probes, the views) and the record construction, against {@code LocalBucket.getRecord} per RID and the same construction.
   */
  @Test
  void warmHydrate() throws Exception {
    final int count = Integer.parseInt(System.getProperty("bench.rids", "2000"));
    final int rounds = Integer.parseInt(System.getProperty("bench.warmRounds", "250"));
    final int discard = Integer.parseInt(System.getProperty("bench.warmDiscard", "50"));
    GlobalConfiguration.MAX_PAGE_RAM.setValue(1024);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final Database db = new DatabaseFactory(DB_PATH).create()) {
      final List<RID> rids = fill(db, 200_000, 600);
      final DatabaseInternal database = (DatabaseInternal) db;
      final LocalDatabase local = (LocalDatabase) database.getEmbedded();
      final RecordFactory recordFactory = local.getRecordFactory();
      final PageManager pageManager = database.getPageManager();
      pageManager.waitAllPagesOfDatabaseAreFlushed(db);

      final Random random = new Random(7);
      final RID[] asked = new RID[count];
      for (int i = 0; i < count; i++)
        asked[i] = rids.get(random.nextInt(rids.size()));
      for (final RID rid : asked)
        db.lookupByRID(rid, true);

      final int kept = rounds - discard;
      final long[] serial = new long[kept], batched = new long[kept];
      final long[] pack = new long[kept], getRecords = new long[kept], buildBatched = new long[kept];
      final long[] getRecordEach = new long[kept], buildSerial = new long[kept];
      final long[] sink = new long[1];

      for (int round = 0; round < rounds; round++) {
        final boolean batchedFirst = (round & 1) == 1;
        long tSerial = 0, tBatched = 0;
        for (int half = 0; half < 2; half++) {
          final long begin = System.nanoTime();
          if ((half == 0) == batchedFirst) {
            final Record[] records = local.lookupByRIDs(asked, true);
            tBatched = System.nanoTime() - begin;
            sink[0] += records.length;
          } else {
            for (final RID rid : asked)
              sink[0] += db.lookupByRID(rid, true).getIdentity().getPosition();
            tSerial = System.nanoTime() - begin;
          }
        }

        // THE PHASES OF THE BATCHED CALL, REPLAYED: PACK AND SORT, getRecords PER BUCKET, CONSTRUCTION
        final long[] phase = new long[5];
        db.executeInReadLock(() -> {
          long t0 = System.nanoTime();
          final long[] byBucket = new long[asked.length];
          for (int i = 0; i < asked.length; i++)
            byBucket[i] = ((long) asked[i].getBucketId() << 32) | i;
          Arrays.sort(byBucket);
          final long[] byPosition = new long[asked.length];
          final long[] positions = new long[asked.length];
          final int[] runStarts = new int[asked.length + 1];
          int runs = 0;
          for (int runStart = 0; runStart < asked.length; ) {
            final int bucketId = (int) (byBucket[runStart] >>> 32);
            int runEnd = runStart + 1;
            while (runEnd < asked.length && (int) (byBucket[runEnd] >>> 32) == bucketId)
              ++runEnd;
            for (int k = runStart; k < runEnd; k++)
              byPosition[k] = (asked[(int) byBucket[k]].getPosition() << 22) | (k - runStart);
            Arrays.sort(byPosition, runStart, runEnd);
            for (int k = runStart; k < runEnd; k++)
              positions[k] = byPosition[k] >>> 22;
            runStarts[runs++] = runStart;
            runStart = runEnd;
          }
          runStarts[runs] = asked.length;
          phase[0] = System.nanoTime() - t0;

          t0 = System.nanoTime();
          final Binary[][] contents = new Binary[runs][];
          for (int r = 0; r < runs; r++) {
            final LocalBucket bucket = (LocalBucket) local.getSchema()
                .getBucketById((int) (byBucket[runStarts[r]] >>> 32));
            contents[r] = bucket.getRecords(positions, runStarts[r], runStarts[r + 1]);
          }
          phase[1] = System.nanoTime() - t0;

          t0 = System.nanoTime();
          for (int r = 0; r < runs; r++) {
            final int bucketId = (int) (byBucket[runStarts[r]] >>> 32);
            final DocumentType type = local.getSchema().getTypeByBucketId(bucketId);
            for (int k = 0; k < contents[r].length; k++) {
              final Binary content = contents[r][k];
              final RID rid = new RID(bucketId, positions[runStarts[r] + k]);
              sink[0] += recordFactory.newImmutableRecord(local, type, rid, content.copyOfContent(), null).getIdentity()
                  .getBucketId();
            }
          }
          phase[2] = System.nanoTime() - t0;

          // THE SERIAL PATH'S PAGE TOUCH AND CONSTRUCTION, PER RID
          t0 = System.nanoTime();
          final Binary[] each = new Binary[asked.length];
          for (int i = 0; i < asked.length; i++)
            each[i] = ((LocalBucket) local.getSchema().getBucketById(asked[i].getBucketId())).getRecord(asked[i]);
          phase[3] = System.nanoTime() - t0;

          t0 = System.nanoTime();
          for (int i = 0; i < asked.length; i++) {
            final DocumentType type = local.getSchema().getTypeByBucketId(asked[i].getBucketId());
            sink[0] += recordFactory.newImmutableRecord(local, type, asked[i], each[i].copyOfContent(), null).getIdentity()
                .getBucketId();
          }
          phase[4] = System.nanoTime() - t0;
          return null;
        });

        if (round >= discard) {
          final int k = round - discard;
          serial[k] = tSerial;
          batched[k] = tBatched;
          pack[k] = phase[0];
          getRecords[k] = phase[1];
          buildBatched[k] = phase[2];
          getRecordEach[k] = phase[3];
          buildSerial[k] = phase[4];
        }
      }

      System.out.printf(Locale.ENGLISH, "%n(c') warm hydrate of %d cached RIDs, %d rounds after %d discarded, A/B pairs (us)%n", count,
          kept, discard);
      System.out.printf(Locale.ENGLISH, "%-34s %10s %10s %10s %12s%n", "phase", "p10", "median", "p90", "ns/record");
      printRow("serial lookupByRID x N", serial, count);
      printRow("batched lookupByRIDs", batched, count);
      printRow("  batched: pack + 2 sorts", pack, count);
      printRow("  batched: getRecords", getRecords, count);
      printRow("  batched: build records", buildBatched, count);
      printRow("  serial: getRecord x N", getRecordEach, count);
      printRow("  serial: build records", buildSerial, count);
      long wins = 0;
      for (int k = 0; k < kept; k++)
        if (batched[k] < serial[k])
          ++wins;
      System.out.printf(Locale.ENGLISH, "pairs where batched < serial: %d of %d (sink %d)%n", wins, kept, sink[0] & 1);
    } finally {
      GlobalConfiguration.MAX_PAGE_RAM.reset();
      FileUtils.deleteRecursively(new File(DB_PATH));
    }
  }

  /**
   * The cost of the re-check {@code FetchFromRidsStep} makes when it hands out a record it hydrated ahead of the row (review of
   * L1: a record an earlier row of the statement deleted is skipped, a newer copy in the transaction is answered). Inside a
   * transaction that has already deleted and modified records, so its sets are not empty: {@code SELECT FROM [N RIDs]} whole,
   * and the re-check alone replayed over the same records, as an A/B pair in every round (alternating which goes first), the
   * first {@code bench.warmDiscard} rounds discarded.
   */
  @Test
  void ridListRecheck() throws Exception {
    final int count = Integer.parseInt(System.getProperty("bench.rids", "2000"));
    final int rounds = Integer.parseInt(System.getProperty("bench.warmRounds", "250"));
    final int discard = Integer.parseInt(System.getProperty("bench.warmDiscard", "50"));
    GlobalConfiguration.MAX_PAGE_RAM.setValue(1024);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final Database db = new DatabaseFactory(DB_PATH).create()) {
      final List<RID> rids = fill(db, 200_000, 600);
      final DatabaseInternal database = (DatabaseInternal) db;
      final Random random = new Random(7);
      final RID[] asked = new RID[count];
      final StringBuilder list = new StringBuilder("SELECT FROM [");
      for (int i = 0; i < count; i++) {
        asked[i] = rids.get(10_000 + random.nextInt(rids.size() - 10_000));
        list.append(i == 0 ? "" : ",").append(asked[i]);
      }
      final String sql = list.append(']').toString();

      final int kept = rounds - discard;
      final long[] statement = new long[kept], recheck = new long[kept];
      final long[] sink = new long[1];
      db.begin();
      try {
        // THE TRANSACTION HAS DELETED AND MODIFIED RECORDS OF ITS OWN (NONE OF THE ASKED ONES), AS A DML STATEMENT'S HAS
        for (int i = 0; i < 1_000; i++)
          db.deleteRecord(db.lookupByRID(rids.get(i), true));
        for (int i = 1_000; i < 2_000; i++)
          db.lookupByRID(rids.get(i), true).asDocument().modify().set("val", -i).save();
        final Record[] records = database.lookupByRIDs(asked, true);

        for (int round = 0; round < rounds; round++) {
          final boolean recheckFirst = (round & 1) == 1;
          long tStatement = 0, tRecheck = 0;
          for (int half = 0; half < 2; half++) {
            final long begin = System.nanoTime();
            if ((half == 0) == recheckFirst) {
              // THE STEP'S currentInTransaction, PER RECORD, THE TRANSACTION RESOLVED ONCE PER PULL
              final TransactionContext tx = database.getTransactionIfExists();
              for (final Record record : records) {
                if (tx == null || !tx.isActive() || (tx.hasDeletedRecords() && tx.isDeletedInTransaction(record.getIdentity())))
                  continue;
                final Record inTx = tx.getWrittenRecord(record.getIdentity());
                sink[0] += (inTx != null ? inTx : record).getIdentity().getPosition();
              }
              tRecheck = System.nanoTime() - begin;
            } else {
              try (final ResultSet rs = db.query("sql", sql)) {
                while (rs.hasNext())
                  sink[0] += rs.next().getIdentity().get().getPosition();
              }
              tStatement = System.nanoTime() - begin;
            }
          }
          if (round >= discard) {
            statement[round - discard] = tStatement;
            recheck[round - discard] = tRecheck;
          }
        }
      } finally {
        db.rollback();
      }

      System.out.printf(Locale.ENGLISH, "%n(e) re-check of %d RIDs handed out by FetchFromRidsStep in a transaction, %d rounds after %d discarded, A/B pairs (us)%n",
          count, kept, discard);
      System.out.printf(Locale.ENGLISH, "%-34s %10s %10s %10s %12s%n", "phase", "p10", "median", "p90", "ns/record");
      printRow("SELECT FROM [N RIDs], whole", statement, count);
      printRow("  hand-out re-check alone", recheck, count);
      System.out.printf(Locale.ENGLISH, "(sink %d)%n", sink[0] & 1);
    } finally {
      GlobalConfiguration.MAX_PAGE_RAM.reset();
      FileUtils.deleteRecursively(new File(DB_PATH));
    }
  }

  private static void printRow(final String label, final long[] nanos, final int records) {
    final long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    final long median = sorted[sorted.length / 2];
    System.out.printf(Locale.ENGLISH, "%-34s %10.1f %10.1f %10.1f %12.1f%n", label, percentile(sorted, 0.10) / 1e3, median / 1e3,
        percentile(sorted, 0.90) / 1e3, (double) median / records);
  }

  @Test
  void pageReadCopy() throws Exception {
    final int pageSize = 64 * 1024;
    final int pages = 4096; // 256 MB
    final int reads = Integer.parseInt(System.getProperty("bench.reads", "50000"));
    final File file = new File("target/PageCacheBenchmark.pages");
    try (final RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.setLength((long) pageSize * pages);
      final FileChannel channel = raf.getChannel();
      final Random random = new Random(1);
      final ByteBuffer heap = ByteBuffer.allocate(pageSize);
      final ByteBuffer direct = ByteBuffer.allocateDirect(pageSize);
      final byte[] page = new byte[pageSize];
      System.out.printf(Locale.ENGLISH, "%n(d) %d random %dKB preads from a %d MB file in the OS cache (us per read)%n", reads, pageSize / 1024,
          (long) pageSize * pages / (1024 * 1024));
      for (int pass = 0; pass < 2; pass++) {
        long heapNanos = 0, directNanos = 0;
        for (int i = 0; i < reads; i++) {
          final long pos = (long) random.nextInt(pages) * pageSize;
          long begin = System.nanoTime();
          heap.clear();
          while (heap.hasRemaining())
            channel.read(heap, pos + heap.position());
          heapNanos += System.nanoTime() - begin;

          begin = System.nanoTime();
          direct.clear();
          while (direct.hasRemaining())
            channel.read(direct, pos + direct.position());
          direct.flip();
          direct.get(page);
          directNanos += System.nanoTime() - begin;
        }
        System.out.printf(Locale.ENGLISH, "pass %d: read(heap) %.2f us, read(direct)+copy %.2f us%n", pass, heapNanos / 1e3 / reads,
            directNanos / 1e3 / reads);
      }
    } finally {
      file.delete();
    }
  }

  private static long percentile(final long[] sorted, final double p) {
    if (sorted.length == 0)
      return 0;
    return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))];
  }

  private static List<RID> fill(final Database db, final int records) {
    return fill(db, records, PAYLOAD);
  }

  private static List<RID> fill(final Database db, final int records, final int payload) {
    db.getSchema().createDocumentType("Item");
    final List<RID> rids = new ArrayList<>(records);
    final String value = "x".repeat(payload);
    final AtomicLong inserted = new AtomicLong();
    while (inserted.get() < records) {
      db.transaction(() -> {
        for (int i = 0; i < 2_000 && inserted.get() < records; i++) {
          final MutableDocument doc = db.newDocument("Item");
          doc.set("val", inserted.getAndIncrement());
          doc.set("payload", value);
          rids.add(doc.save().getIdentity());
        }
      });
    }
    return rids;
  }
}
