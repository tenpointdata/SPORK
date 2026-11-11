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
import com.arcadedb.database.LocalDatabase;
import com.arcadedb.engine.PageConflictStats;
import com.arcadedb.engine.PageManager;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.management.ThreadMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * In-JVM reproduction of issue 5 of the SPORK stress report: concurrent writers each commit one {@code sqlscript} that
 * creates ~25 vertices and ~45 edges across ~40 types (every edge type {@code UNIQUE}, some UNIQUE / NOTUNIQUE vertex
 * indexes, a FULL_TEXT index receiving ~70 tokens per upload) and links a few existing shared hub vertices. Each
 * upload runs exactly as the HTTP auto-commit path runs it: {@code database.transaction(script, false, retries)}.
 * <p>
 * Per round it prints the page-version conflicts (the JVM-wide {@code concurrentModificationExceptions} counter) per
 * committed upload, the merges that absorbed some of them, the attempts that failed and re-ran the whole script, the
 * reasons of those failures, and the files the conflicts happened on ({@link PageConflictStats}). Counts, not wall
 * clock: the numbers are what to compare. Tagged {@code benchmark}: it prints and asserts nothing.
 * <p>
 * Knobs: {@code -Dbench.threads=32 -Dbench.rounds=4 -Dbench.uploadsPerRound=1000 -Dbench.buckets=8 -Dbench.hubs=300
 * -Dbench.hubUpdates=false}.
 */
@Tag("benchmark")
class MultiTypeUploadConflictBenchmark {
  private static final String DB_PATH          = "target/databases/MultiTypeUploadConflictBenchmark";
  private static final int    VERTEX_TYPES     = 20;
  private static final int    EDGE_TYPES       = 20;
  private static final int    VERTICES         = 25;
  private static final int    EDGES            = 45;
  private static final int    HUB_LINKS        = 4;
  private static final int    FULLTEXT_TOKENS  = 70;
  private static final int    VOCABULARY       = 5_000;
  private static final int    RETRIES          = 10;
  private static final String[] HUB_TYPES      = { "Phone", "Email", "Url" };
  private static final ThreadMXBean THREADS      = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  public static void main(final String[] args) throws Exception {
    new MultiTypeUploadConflictBenchmark().uploads();
  }

  @Test
  void uploads() throws Exception {
    final int threads = Integer.getInteger("bench.threads", 32);
    final int rounds = Integer.getInteger("bench.rounds", 4);
    final int uploadsPerRound = Integer.getInteger("bench.uploadsPerRound", 1000);
    final int buckets = Integer.getInteger("bench.buckets", 8);
    final int hubs = Integer.getInteger("bench.hubs", 300);
    final boolean hubUpdates = Boolean.parseBoolean(System.getProperty("bench.hubUpdates", "false"));

    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final DatabaseFactory factory = new DatabaseFactory(DB_PATH)) {
      final Database db = factory.create();
      try {
        db.getConfiguration().setValue(GlobalConfiguration.TYPE_DEFAULT_BUCKETS, buckets);
        createSchema(db, hubs);

        System.out.printf(Locale.ENGLISH, "%nthreads=%d buckets=%d hubs=%d hubUpdates=%b%n", threads, buckets, hubs, hubUpdates);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicLong uploadSeq = new AtomicLong();
        try {
          for (int round = 0; round < rounds; round++)
            runRound(db, pool, round, uploadsPerRound, hubs, hubUpdates, uploadSeq);
        } finally {
          pool.shutdownNow();
          pool.awaitTermination(1, TimeUnit.MINUTES);
        }
      } finally {
        db.drop();
      }
    }
  }

  private static void createSchema(final Database db, final int hubs) {
    final StringBuilder ddl = new StringBuilder();
    for (int i = 0; i < VERTEX_TYPES; i++) {
      ddl.append("CREATE VERTEX TYPE V").append(i).append(";\n");
      ddl.append("CREATE PROPERTY V").append(i).append(".key STRING;\n");
      ddl.append("CREATE PROPERTY V").append(i).append(".n LONG;\n");
      if (i < 5)
        ddl.append("CREATE INDEX ON V").append(i).append(" (key) UNIQUE;\n");
      else if (i < 10)
        ddl.append("CREATE INDEX ON V").append(i).append(" (n) NOTUNIQUE;\n");
    }
    ddl.append("CREATE PROPERTY V10.text STRING;\n");
    ddl.append("CREATE INDEX ON V10 (text) FULL_TEXT;\n");
    for (int i = 0; i < EDGE_TYPES; i++)
      ddl.append("CREATE EDGE TYPE E").append(i).append(" UNIQUE;\n");
    for (final String hub : HUB_TYPES) {
      ddl.append("CREATE VERTEX TYPE ").append(hub).append(";\n");
      ddl.append("CREATE PROPERTY ").append(hub).append(".key STRING;\n");
      ddl.append("CREATE PROPERTY ").append(hub).append(".seen LONG;\n");
      ddl.append("CREATE INDEX ON ").append(hub).append(" (key) UNIQUE;\n");
    }
    db.command("sqlscript", ddl.toString());

    db.transaction(() -> {
      for (final String hub : HUB_TYPES)
        for (int i = 0; i < hubs; i++)
          db.newVertex(hub).set("key", hub + i).set("seen", 0L).save();
    });
  }

  private static void runRound(final Database db, final ExecutorService pool, final int round, final int uploads, final int hubs,
      final boolean hubUpdates, final AtomicLong uploadSeq) throws Exception {
    final LocalDatabase local = (LocalDatabase) db;
    local.getPageConflictStats().reset();
    final long cmeBefore = PageManager.INSTANCE.getStats().concurrentModificationExceptions;
    final long edgeMergesBefore = PageManager.INSTANCE.getStats().edgeAppendMerges;
    final long slotMergesBefore = PageManager.INSTANCE.getStats().txPageSlotMerges;
    final long txRetriesBefore = ((Number) local.getStats().get("txRetries")).longValue();
    final long walPagesBefore = ((Number) local.getTransactionManager().getStats().get("pagesWritten")).longValue();
    final long walBytesBefore = ((Number) local.getTransactionManager().getStats().get("bytesWritten")).longValue();

    final AtomicLong committed = new AtomicLong();
    final AtomicLong failedAttempts = new AtomicLong();
    final AtomicLong givenUp = new AtomicLong();
    final LongAdder allocated = new LongAdder();
    final ConcurrentHashMap<String, LongAdder> failureReasons = new ConcurrentHashMap<>();

    final List<Future<?>> futures = new ArrayList<>(uploads);
    for (int u = 0; u < uploads; u++)
      futures.add(pool.submit(() -> {
        final long id = uploadSeq.incrementAndGet();
        final String script = buildScript(id, hubs, hubUpdates);
        final long allocatedBefore = THREADS.getCurrentThreadAllocatedBytes();
        try {
          db.transaction(() -> db.command("sqlscript", script).close(), false, RETRIES, null, e -> {
            failedAttempts.incrementAndGet();
            failureReasons.computeIfAbsent(reason(db, e), k -> new LongAdder()).increment();
          });
          committed.incrementAndGet();
          allocated.add(THREADS.getCurrentThreadAllocatedBytes() - allocatedBefore);
        } catch (final RuntimeException e) {
          givenUp.incrementAndGet();
          failureReasons.computeIfAbsent("GAVE UP: " + reason(db, e), k -> new LongAdder()).increment();
        }
      }));
    for (final Future<?> f : futures)
      f.get();

    final long cme = PageManager.INSTANCE.getStats().concurrentModificationExceptions - cmeBefore;
    final long edgeMerges = PageManager.INSTANCE.getStats().edgeAppendMerges - edgeMergesBefore;
    final long slotMerges = PageManager.INSTANCE.getStats().txPageSlotMerges - slotMergesBefore;
    final long records = countRecords(db);
    final double perTx = committed.get() > 0 ? (double) cme / committed.get() : 0;

    System.out.printf(Locale.ENGLISH,
        "%nround %d: records=%,d committed=%d gaveUp=%d | conflicts=%,d (%.2f per committed upload) edgeAppendMerges=%,d "
            + "slotMerges=%,d | failed attempts (whole-script re-runs)=%,d (%.3f per committed upload)%n", round, records,
        committed.get(), givenUp.get(), cme, perTx, edgeMerges, slotMerges, failedAttempts.get(),
        committed.get() > 0 ? (double) failedAttempts.get() / committed.get() : 0);
    System.out.printf(Locale.ENGLISH, "  WAL pages per committed upload=%.1f (%,d KB of page deltas)%n",
        committed.get() > 0 ? (double) (((Number) local.getTransactionManager().getStats().get("pagesWritten")).longValue() - walPagesBefore)
            / committed.get() : 0,
        committed.get() > 0 ? (((Number) local.getTransactionManager().getStats().get("bytesWritten")).longValue() - walBytesBefore)
            / committed.get() / 1024 : 0);
    System.out.printf(Locale.ENGLISH, "  txRetries (profiler)=%,d | heap allocated per committed upload (all attempts): %,d KB%n",
        ((Number) local.getStats().get("txRetries")).longValue() - txRetriesBefore,
        committed.get() > 0 ? allocated.sum() / committed.get() / 1024 : 0);
    final Map<String, long[]> byKind = new TreeMap<>();
    for (final PageConflictStats.Entry e : local.getPageConflictStats().getTop(Integer.MAX_VALUE)) {
      final long[] k = byKind.computeIfAbsent(e.kind, x -> new long[3]);
      k[0] += e.getConflicts();
      k[1] += e.getMerged();
      k[2] += e.getFailed();
    }
    for (final Map.Entry<String, long[]> k : byKind.entrySet())
      System.out.printf(Locale.ENGLISH, "  by kind %-16s conflicts=%,9d merged=%,9d failed=%,7d%n", k.getKey(), k.getValue()[0],
          k.getValue()[1], k.getValue()[2]);
    failureReasons.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(8)
        .forEach(e -> System.out.printf(Locale.ENGLISH, "  failure %,8d  %s%n", e.getValue().sum(), e.getKey()));
    System.out.printf(Locale.ENGLISH, "  %10s %10s %10s  %-16s %-28s %s%n", "conflicts", "merged", "failed", "kind", "detail", "file");
    for (final PageConflictStats.Entry e : local.getPageConflictStats().getTop(15))
      System.out.printf(Locale.ENGLISH, "  %,10d %,10d %,10d  %-16s %-28s %s%n", e.getConflicts(), e.getMerged(), e.getFailed(), e.kind,
          e.detail != null ? e.detail : "", e.fileName);
  }

  /** The exception class and the part of its message that names the file, without the per-attempt numbers. */
  private static final Pattern PAGE_ID = Pattern.compile("PageId\\([^/]+/(\\d+)/(\\d+)\\)");

  private static String reason(final Database db, final Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t && !(t instanceof NeedRetryException))
      t = t.getCause();
    String msg = String.valueOf(t.getMessage());
    final int file = msg.indexOf(" in file '");
    if (file > 0)
      msg = msg.substring(file + 1, msg.indexOf('\'', file + 10) + 1);
    final Matcher m = PAGE_ID.matcher(msg);
    if (m.find()) {
      final PageConflictStats.Entry entry = PageConflictStats.describe((LocalDatabase) db, Integer.parseInt(m.group(1)));
      msg = msg.replace(m.group(), entry.kind + " " + (entry.detail != null ? entry.detail : entry.fileName) + " page " + m.group(2));
    }
    if (msg.length() > 200)
      msg = msg.substring(0, 200);
    msg = msg.replaceAll("#\\d+:\\d+", "#R").replaceAll("slot \\d+", "slot S").replaceAll("threadId=\\d+", "");
    return t.getClass().getSimpleName() + ": " + msg;
  }

  private static long countRecords(final Database db) {
    long total = 0;
    for (final var type : db.getSchema().getTypes())
      total += db.countType(type.getName(), false);
    return total;
  }

  /**
   * One upload: VERTICES new vertices spread over the vertex types (one of them carrying FULLTEXT_TOKENS tokens of
   * text), EDGES new edges between them over the edge types, and HUB_LINKS edges to existing shared hub vertices.
   */
  static String buildScript(final long id, final int hubs, final boolean hubUpdates) {
    final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    final StringBuilder s = new StringBuilder(8192);
    for (int v = 0; v < VERTICES; v++) {
      final int type = v % VERTEX_TYPES;
      s.append("LET v").append(v).append(" = CREATE VERTEX V").append(type).append(" SET key = 'u").append(id).append('_')
          .append(v).append("', n = ").append(rnd.nextInt(1000));
      if (type == 10) {
        s.append(", text = '");
        for (int t = 0; t < FULLTEXT_TOKENS; t++) {
          // SKEWED, LIKE REAL TEXT: A FEW TOKENS ARE IN EVERY UPLOAD
          final int word = (int) Math.min(VOCABULARY - 1, Math.abs(rnd.nextGaussian()) * VOCABULARY / 6);
          s.append('w').append(word).append(' ');
        }
        s.append('\'');
      }
      s.append(";\n");
    }
    final Set<Long> pairs = new HashSet<>();
    int edges = 0;
    while (edges < EDGES - HUB_LINKS) {
      final int from = rnd.nextInt(VERTICES);
      final int to = rnd.nextInt(VERTICES);
      if (from == to || !pairs.add((long) from * VERTICES + to))
        continue;
      s.append("CREATE EDGE E").append(edges % EDGE_TYPES).append(" FROM $v").append(from).append(" TO $v").append(to)
          .append(";\n");
      edges++;
    }
    for (int h = 0; h < HUB_LINKS; h++) {
      final String hub = HUB_TYPES[h % HUB_TYPES.length];
      final int hubIndex = rnd.nextInt(hubs);
      s.append("LET h").append(h).append(" = SELECT FROM ").append(hub).append(" WHERE key = '").append(hub).append(hubIndex)
          .append("';\n");
      s.append("CREATE EDGE E").append(EDGE_TYPES - 1 - h).append(" FROM $v").append(h).append(" TO $h").append(h).append(";\n");
      if (hubUpdates)
        s.append("UPDATE ").append(hub).append(" SET seen = ").append(id).append(" WHERE key = '").append(hub).append(hubIndex)
            .append("';\n");
    }
    return s.toString();
  }
}
