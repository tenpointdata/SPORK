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
package com.arcadedb.index.fulltext;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;
import com.arcadedb.database.async.DatabaseAsyncExecutorImpl;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.lsm.LSMTreeIndex;
import com.arcadedb.log.LogManager;
import com.arcadedb.log.Logger;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.FullTextIndexMetadata;
import com.arcadedb.schema.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.ClosedByInterruptException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The block-max sidecar of BM25 full-text indexes: the top-k scorer that skips postings with it must answer exactly what the
 * general path answers, whatever was written, deleted or compacted since the sidecar was written; an index in the older format,
 * or with a sidecar that cannot be read, answers the general way; REBUILD INDEX migrates.
 */
class FullTextBlockMaxTest extends TestHelper {
  private static final int VOCABULARY = 60;

  @BeforeEach
  void sendSmallCorporaThroughTheBlockMaxScorer() {
    LSMTreeFullTextIndex.minBlockMaxBlocks = 0;
  }

  @AfterEach
  void restoreTheBlockMaxThreshold() {
    LSMTreeFullTextIndex.minBlockMaxBlocks = 16;
    LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    LSMTreeFullTextIndex.blockMaxCompactionProbe = null;
    BlockMaxScorer.exactLeadBound = true;
    BlockMaxBuildScheduler.awaitIdle(60_000);
  }

  private static String word(final Random random) {
    return "w" + (int) Math.floor(Math.pow(VOCABULARY, random.nextDouble()) - 1);
  }

  private static String text(final Random random) {
    final StringBuilder text = new StringBuilder();
    final int length = 1 + random.nextInt(25);
    for (int i = 0; i < length; i++)
      text.append(i > 0 ? " " : "").append(word(random));
    return text.toString();
  }

  private void insert(final String type, final int documents, final Random random) {
    for (int start = 0; start < documents; start += 500)
      database.transaction(() -> {
        for (int i = 0; i < 500; i++)
          database.newDocument(type).set("text", text(random)).save();
      });
  }

  /** A full-text index with small pages, so a few thousand documents span enough pages to compact. */
  private void createIndex(final String type) {
    database.getSchema().buildTypeIndex(type, new String[] { "text" }).withType(Schema.INDEX_TYPE.FULL_TEXT).withPageSize(16_384)
        .create();
  }

  private TypeIndex index(final String name) {
    return (TypeIndex) database.getSchema().getIndexByName(name);
  }

  private void buildBlockMax(final String indexName) {
    for (final IndexInternal bucketIndex : index(indexName).getIndexesOnBuckets())
      assertThat(((LSMTreeFullTextIndex) bucketIndex).buildBlockMax()).isTrue();
  }

  private Map<RID, Float> search(final String indexName, final String query, final int limit, final boolean topK) {
    database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, topK);
    try {
      return FullTextSearch.search(index(indexName), query, limit);
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, true);
    }
  }

  private LSMTreeFullTextIndex bucket(final String indexName) {
    return (LSMTreeFullTextIndex) index(indexName).getIndexesOnBuckets()[0];
  }

  /**
   * Parks the background builder after the first term it writes, until the returned latch is counted down: the open of a database
   * asks it for every outdated sidecar, and a test reading the postings meanwhile must not see the rewritten one.
   */
  private static CountDownLatch holdTheBackgroundBuilder() {
    final CountDownLatch release = new CountDownLatch(1);
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (Thread.currentThread().getName().equals("ArcadeDB-FullTextBlockMax"))
        try {
          release.await(60, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
    };
    return release;
  }

  /** The files of the sidecar directory that belong to {@code bucketIndexName}: generations and temporary files. */
  private List<String> sidecarFiles(final String bucketIndexName) {
    final List<String> names = new ArrayList<>();
    final File[] files = new File(getDatabasePath(), "fulltext-blockmax").listFiles();
    if (files != null)
      for (final File file : files)
        if (file.getName().startsWith(bucketIndexName + "."))
          names.add(file.getName());
    return names;
  }

  private void createSingleBucketDoc() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
    });
  }

  /** Overwrites a few bytes of a sidecar file in place. */
  private static void patch(final File file, final long position, final byte[] bytes) throws Exception {
    try (final RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(position);
      raf.write(bytes);
    }
  }

  private static String query(final Random random) {
    final String a = word(random);
    final String b = word(random);
    final String c = word(random);
    return switch (random.nextInt(9)) {
      case 0, 1, 2 -> a;
      case 3 -> a + " " + b;
      case 4 -> a + " " + b + " " + c;
      case 5 -> "+" + a + " +" + b;
      case 6 -> "+" + a + " " + b + "^2";
      case 7 -> "+" + a + " +" + b + " +" + c;
      default -> a + "^0.5 " + b;
    };
  }

  private void assertSameAnswers(final String indexName, final Random random, final int rounds) {
    for (int round = 0; round < rounds; round++) {
      final String query = query(random);
      final int limit = 1 + random.nextInt(round % 4 == 0 ? 150 : 20);
      assertThat(search(indexName, query, limit, true)).as("query='%s' limit=%d", query, limit)
          .isEqualTo(search(indexName, query, limit, false));
    }
  }

  @Test
  void newIndexesAreInTheBlockMaxFormat() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 2");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
      database.command("sql", "CREATE DOCUMENT TYPE Plain BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Plain.text STRING");
      database.command("sql", "CREATE INDEX ON Plain (text) FULL_TEXT METADATA {\"blockMax\": false}");
    });
    final FullTextIndexMetadata metadata = LSMTreeFullTextIndex.metadataOf(index("Doc[text]"));
    assertThat(metadata.getFormatVersion()).isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_BLOCK_MAX);
    assertThat(metadata.isBlockMax()).isTrue();
    assertThat(LSMTreeFullTextIndex.metadataOf(index("Plain[text]")).isBlockMax()).isFalse();

    reopenDatabase();
    assertThat(LSMTreeFullTextIndex.metadataOf(index("Doc[text]")).getFormatVersion())
        .isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_BLOCK_MAX);
    assertThat(LSMTreeFullTextIndex.metadataOf(index("Plain[text]")).isBlockMax()).isFalse();
  }

  @Test
  void blockMaxAnswersExactlyWhatTheGeneralPathAnswersAfterWritesSinceTheSnapshot() {
    final Random random = new Random(42);
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 3");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
      database.command("sql", "CREATE DOCUMENT TYPE Single BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Single.text STRING");
      createIndex("Single");
    });
    insert("Doc", 4000, random);
    insert("Single", 2000, random);
    buildBlockMax("Doc[text]");
    buildBlockMax("Single[text]");

    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 120);
    assertSameAnswers("Single[text]", random, 60);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);

    // Written after the snapshot: new documents, deletions, updates (same RID, new terms), in separate transactions so the
    // deletions are tombstones the sidecar has to be corrected by
    insert("Doc", 600, random);
    insert("Single", 300, random);
    for (final String type : new String[] { "Doc", "Single" }) {
      database.transaction(() -> database.command("sql", "DELETE FROM " + type + " WHERE text LIKE '%w2 %' LIMIT 150"));
      database.transaction(() -> database.command("sql", "UPDATE " + type + " SET text = 'w1 w1 w1 w7 w30' WHERE text LIKE '%w4%' LIMIT 120"));
      database.transaction(() -> database.command("sql", "UPDATE " + type + " SET text = 'w0' WHERE text LIKE '%w9%' LIMIT 60"));
    }
    assertSameAnswers("Doc[text]", random, 150);
    assertSameAnswers("Single[text]", random, 80);

    // A compaction appends a series to the compacted part: newer than the sidecar, read with the mutable pages
    for (final IndexInternal bucketIndex : index("Doc[text]").getIndexesOnBuckets())
      try {
        bucketIndex.scheduleCompaction();
        bucketIndex.compact();
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    insert("Doc", 200, random);
    assertSameAnswers("Doc[text]", random, 120);

    // and the same after a reopen, which reads the sidecars back from disk
    reopenDatabase();
    assertSameAnswers("Doc[text]", random, 80);
    assertSameAnswers("Single[text]", random, 40);
  }

  @Test
  void selectiveTopKSkipsMostBlocks() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
    });
    createIndex("Doc");
    // w0 in every document, once in a long one; in one document of 1,000 it dominates a short one
    final String filler = " a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20";
    for (int start = 0; start < 30_000; start += 1_000) {
      final int from = start;
      database.transaction(() -> {
        for (int i = from; i < from + 1_000; i++)
          database.newDocument("Doc").set("text", i % 1_000 == 500 ? "w0 w0 w0 w0 b" + i : "w0" + filler).save();
      });
    }
    buildBlockMax("Doc[text]");

    final long decodedBefore = FullTextTopK.getBlocksDecoded();
    final Map<RID, Float> best = search("Doc[text]", "w0", 10, true);
    final long decoded = FullTextTopK.getBlocksDecoded() - decodedBefore;
    assertThat(best).isEqualTo(search("Doc[text]", "w0", 10, false));
    // 30,000 postings are 235 blocks; the 30 strong documents sit in at most 30 of them
    final long blocks = 30_000 / BlockMaxPostings.BLOCK_SIZE + 1;
    assertThat(decoded).as("blocks decoded %d of %d", decoded, blocks).isLessThanOrEqualTo(40);
  }

  @Test
  void pendingChangesOfTheTransactionAreSeen() {
    final Random random = new Random(5);
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
    });
    insert("Doc", 2000, random);
    buildBlockMax("Doc[text]");

    database.transaction(() -> {
      for (int i = 0; i < 20; i++)
        database.newDocument("Doc").set("text", "w1 w1 w1 w1 w1").save();
      database.command("sql", "DELETE FROM Doc WHERE text LIKE '%w3%' LIMIT 50");
      assertThat(search("Doc[text]", "w1", 15, true)).isEqualTo(search("Doc[text]", "w1", 15, false));
      assertThat(search("Doc[text]", "+w1 +w3", 15, true)).isEqualTo(search("Doc[text]", "+w1 +w3", 15, false));
    });
  }

  @Test
  void unreadableSidecarFallsBackToThePostings() throws Exception {
    final Random random = new Random(9);
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
    });
    insert("Doc", 2000, random);
    buildBlockMax("Doc[text]");
    final File sidecar = ((LSMTreeFullTextIndex) index("Doc[text]").getIndexesOnBuckets()[0]).blockMaxFile();
    assertThat(sidecar).exists();

    database.close();
    try (final RandomAccessFile file = new RandomAccessFile(sidecar, "rw")) {
      file.setLength(file.length() / 2);
    }
    // The open asks for a new sidecar in the background: held until the queries below have read the postings
    final CountDownLatch release = new CountDownLatch(1);
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (Thread.currentThread().getName().equals("ArcadeDB-FullTextBlockMax"))
        try {
          release.await(60, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
    };
    final long blockMaxBefore;
    try {
      reopenDatabase();

      blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
      assertSameAnswers("Doc[text]", random, 30);
      assertThat(FullTextTopK.getBlockMaxInvocations()).isEqualTo(blockMaxBefore);
    } finally {
      release.countDown();
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }

    // the build the open asked for writes it again
    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isTrue();
    assertSameAnswers("Doc[text]", random, 30);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }

  @Test
  void dropDeletesTheSidecar() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
    });
    insert("Doc", 500, new Random(1));
    buildBlockMax("Doc[text]");
    final File sidecar = ((LSMTreeFullTextIndex) index("Doc[text]").getIndexesOnBuckets()[0]).blockMaxFile();
    assertThat(sidecar).exists();
    database.command("sql", "DROP INDEX `Doc[text]`");
    assertThat(sidecar).doesNotExist();
  }

  @Test
  void legacyIndexAnswersTheGeneralWayAndRebuildMigratesIt() throws Exception {
    final Random random = new Random(11);
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 2");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
    });
    insert("Doc", 3000, random);

    // An index persisted before the format version existed: no ft_formatVersion in its definition
    database.close();
    final File schemaFile = new File(getDatabasePath(), "schema.json");
    final String schema = Files.readString(schemaFile.toPath(), StandardCharsets.UTF_8);
    assertThat(schema).contains("\"ft_formatVersion\"");
    Files.writeString(schemaFile.toPath(), schema.replaceAll("\"ft_formatVersion\"\\s*:\\s*\\d+\\s*,?", ""), StandardCharsets.UTF_8);
    final File previous = new File(getDatabasePath(), "schema.prev.json");
    if (previous.exists())
      Files.delete(previous.toPath());
    reopenDatabase();

    final FullTextIndexMetadata legacy = LSMTreeFullTextIndex.metadataOf(index("Doc[text]"));
    assertThat(legacy.getFormatVersion()).isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_LSM);
    assertThat(legacy.isBlockMax()).isFalse();
    for (final IndexInternal bucketIndex : index("Doc[text]").getIndexesOnBuckets())
      assertThat(((LSMTreeFullTextIndex) bucketIndex).buildBlockMax()).isFalse();

    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 40);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isEqualTo(blockMaxBefore);

    database.command("sql", "REBUILD INDEX `Doc[text]`");
    final FullTextIndexMetadata migrated = LSMTreeFullTextIndex.metadataOf(index("Doc[text]"));
    assertThat(migrated.getFormatVersion()).isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_BLOCK_MAX);
    for (final IndexInternal bucketIndex : index("Doc[text]").getIndexesOnBuckets())
      assertThat(((LSMTreeFullTextIndex) bucketIndex).blockMaxFile()).exists();
    assertSameAnswers("Doc[text]", random, 60);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }

  /**
   * Two builds of one index at once (REBUILD INDEX racing the build its compaction asked for, two COMPACT INDEX) used to write
   * the same fixed temporary files: the second truncated and interleaved with the first, which published a file with a valid
   * header and mixed content. Builds are now serialized per index, and each writes files of its own.
   */
  @Test
  void concurrentBuildsOfOneIndexNeverMixTheirFiles() throws Exception {
    final Random random = new Random(21);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");

    final ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      for (int round = 0; round < 3; round++) {
        insert("Doc", 300, random);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Boolean>> builds = new ArrayList<>();
        for (int i = 0; i < 4; i++)
          builds.add(executor.submit(() -> {
            start.await();
            return bucket.rebuildBlockMax();
          }));
        start.countDown();
        for (final Future<Boolean> build : builds)
          assertThat(build.get(120, TimeUnit.SECONDS)).isTrue();
        BlockMaxBuildScheduler.awaitIdle(60_000);

        // One generation on disk, no temporary file left, and it answers exactly
        assertThat(sidecarFiles(bucket.getName())).hasSize(1).allMatch(name -> name.endsWith(BlockMaxPostings.EXTENSION));
        final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
        assertSameAnswers("Doc[text]", random, 40);
        assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
      }
    } finally {
      executor.shutdownNow();
    }

    // and what is on disk reads back the same after a reopen
    reopenDatabase();
    assertSameAnswers("Doc[text]", random, 40);
  }

  /**
   * A sidecar build holds no LSM-Tree lock while it walks the index: a compaction must publish (it takes the write lock) while a
   * build is in progress. It used to hold the read lock for the whole build, which blocked the compaction's publication and,
   * the locks being fair, every reader and writer queued behind it.
   */
  @Test
  void aCompactionPublishesWhileASidecarIsBeingWritten() throws Exception {
    final Random random = new Random(31);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    insert("Doc", 1500, random);
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");

    final CountDownLatch building = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (first.compareAndSet(true, false)) {
        building.countDown();
        try {
          release.await(120, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };

    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<Boolean> build = executor.submit(bucket::rebuildBlockMax);
      assertThat(building.await(60, TimeUnit.SECONDS)).isTrue();

      // The build is parked in the middle of its walk: an automatic compaction runs to the end, publication included. (An explicit
      // one, COMPACT INDEX, then writes the sidecar itself, so it waits for the build in progress)
      final Future<Boolean> compaction = executor.submit(() -> {
        DatabaseContext.INSTANCE.init((DatabaseInternal) database);
        bucket.scheduleCompaction();
        return bucket.getUnderlyingIndex().compact();
      });
      // A hang detector, not a latency bound: blocked on the build's lock, it would wait for the release below forever
      assertThat(compaction.get(60, TimeUnit.SECONDS)).isTrue();

      release.countDown();
      build.get(120, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    LSMTreeFullTextIndex.blockMaxBuildProbe = null;

    // Whatever the parked build published was checked against the file it read; the compaction asked for its own
    assertThat(bucket.buildBlockMax()).isTrue();
    BlockMaxBuildScheduler.awaitIdle(60_000);
    assertSameAnswers("Doc[text]", random, 60);
  }

  /**
   * The sidecar is trusted only on the series it was written from. A sidecar whose mark lies past the compacted file's series end
   * (what a crash recovery that lost the last series leaves) used to be reported current, so it was never rewritten, and a later
   * compaction writing new series over those pages made it look valid again; one whose fingerprint does not match the series at
   * its mark is a snapshot of other content.
   */
  @Test
  void aSidecarOfOtherSeriesIsNeitherCurrentNorRead() throws Exception {
    final Random random = new Random(41);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    final File sidecar = bucket("Doc[text]").blockMaxFile();
    final int seriesEnd;
    try (final RandomAccessFile file = new RandomAccessFile(sidecar, "r")) {
      file.seek(16);
      seriesEnd = file.readInt();
    }

    // Mark past the series end of the file. The open asks for a new sidecar in the background: held until the queries below have
    // read the postings
    database.close();
    patch(sidecar, 16, ByteBuffer.allocate(4).putInt(seriesEnd + 3).array());
    CountDownLatch release = holdTheBackgroundBuilder();
    long blockMaxBefore;
    try {
      reopenDatabase();
      assertThat(bucket("Doc[text]").blockMaxFile()).isEqualTo(sidecar);
      assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isFalse();
      blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
      assertSameAnswers("Doc[text]", random, 30);
      assertThat(FullTextTopK.getBlockMaxInvocations()).isEqualTo(blockMaxBefore);
    } finally {
      release.countDown();
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }
    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    assertThat(bucket("Doc[text]").buildBlockMax()).isTrue();

    // Same mark, other content below it
    final File rewritten = bucket("Doc[text]").blockMaxFile();
    assertThat(rewritten).isNotEqualTo(sidecar);
    assertThat(sidecar).doesNotExist();
    database.close();
    patch(rewritten, 20, ByteBuffer.allocate(8).putLong(0x0123456789ABCDEFL).array());
    release = holdTheBackgroundBuilder();
    try {
      reopenDatabase();
      assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isFalse();
      blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
      assertSameAnswers("Doc[text]", random, 30);
      assertThat(FullTextTopK.getBlockMaxInvocations()).isEqualTo(blockMaxBefore);
    } finally {
      release.countDown();
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }
    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();

    assertThat(bucket("Doc[text]").buildBlockMax()).isTrue();
    assertSameAnswers("Doc[text]", random, 30);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }

  /**
   * A sidecar damaged after it was opened (truncated, an I/O error) used to fail every query until a compaction rewrote it. The
   * query now answers from the postings, the sidecar is set aside and written again in the background.
   */
  @Test
  void aSidecarDamagedWhileOpenFallsBackToThePostings() throws Exception {
    final Random random = new Random(51);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    final File sidecar = bucket("Doc[text]").blockMaxFile();

    // Payload gone under the open channel: only the header and a little more remain
    try (final RandomAccessFile file = new RandomAccessFile(sidecar, "rw")) {
      file.setLength(200);
    }
    for (final String query : new String[] { "w0", "w1 w2", "+w0 +w3", "w4" })
      assertThat(search("Doc[text]", query, 15, true)).as(query).isEqualTo(search("Doc[text]", query, 15, false));

    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isTrue();
    assertThat(bucket("Doc[text]").blockMaxFile()).isNotEqualTo(sidecar);
    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 30);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);

    // Garbage instead of a truncation: decoding fails with a runtime error rather than an I/O one
    final File second = bucket("Doc[text]").blockMaxFile();
    final long length = second.length();
    final byte[] garbage = new byte[(int) Math.min(4096, length - 64)];
    Arrays.fill(garbage, (byte) 0xFF);
    patch(second, 64, garbage);
    for (final String query : new String[] { "w0", "w1 w2", "+w0 +w3" })
      assertThat(search("Doc[text]", query, 15, true)).as(query).isEqualTo(search("Doc[text]", query, 15, false));
  }

  /**
   * A build that finishes after its index was dropped used to publish (and so re-create) the sidecar of an index that no longer
   * exists. Now it stops, and nothing of it is left on disk.
   */
  @Test
  void aBuildThatOutlivesTheDropOfItsIndexLeavesNothing() throws Exception {
    createSingleBucketDoc();
    insert("Doc", 3000, new Random(61));
    buildBlockMax("Doc[text]");
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");
    final String bucketName = bucket.getName();

    final CountDownLatch building = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (first.compareAndSet(true, false)) {
        building.countDown();
        try {
          release.await(120, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<Boolean> build = executor.submit(bucket::rebuildBlockMax);
      assertThat(building.await(60, TimeUnit.SECONDS)).isTrue();
      final Future<?> drop = executor.submit(() -> {
        DatabaseContext.INSTANCE.init((DatabaseInternal) database);
        database.command("sql", "DROP INDEX `Doc[text]`");
      });
      try {
        drop.get(5, TimeUnit.SECONDS);
      } catch (final TimeoutException e) {
        // A drop that waits for the build is released below
      }
      release.countDown();
      assertThat(build.get(120, TimeUnit.SECONDS)).isFalse();
      drop.get(120, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    assertThat(sidecarFiles(bucketName)).isEmpty();
  }

  /**
   * Conjunctions through the block-max scorer, with the candidate check on the lead's exact contribution, against the general
   * path; and that check decodes fewer blocks of the common term than the block bounds alone.
   */
  @Test
  void conjunctionsSkipTheBlocksOfTheCommonTermAndStayExact() {
    final Random random = new Random(71);
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 2");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      createIndex("Doc");
    });
    insert("Doc", 6000, random);
    buildBlockMax("Doc[text]");
    insert("Doc", 300, random);
    database.transaction(() -> database.command("sql", "DELETE FROM Doc WHERE text LIKE '%w5 %' LIMIT 100"));

    for (int round = 0; round < 200; round++) {
      final String a = word(random);
      final String b = word(random);
      final String c = word(random);
      final String query = switch (round % 4) {
        case 0 -> "+" + a + " +" + b;
        case 1 -> "+" + a + " +" + b + " " + c;
        case 2 -> "+w0 +" + b;
        default -> "+" + a + " +" + b + " +" + c;
      };
      final int limit = 1 + random.nextInt(round % 5 == 0 ? 120 : 15);
      assertThat(search("Doc[text]", query, limit, true)).as("query='%s' limit=%d", query, limit)
          .isEqualTo(search("Doc[text]", query, limit, false));
    }

    // A rare term with a common one: the common term's blocks are mostly passed over. Both counts are of the same sidecars:
    // nothing is left for the background builder to publish between them
    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    final long[] decoded = new long[2];
    for (int mode = 0; mode < 2; mode++) {
      BlockMaxScorer.exactLeadBound = mode == 1;
      final long before = FullTextTopK.getBlocksDecoded();
      for (int i = 20; i < 40; i++)
        search("Doc[text]", "+w0 +w" + i, 5, true);
      decoded[mode] = FullTextTopK.getBlocksDecoded() - before;
    }
    BlockMaxScorer.exactLeadBound = true;
    assertThat(decoded[1]).as("blocks decoded with the exact lead check %d, without %d", decoded[1], decoded[0])
        .isLessThan(decoded[0]);
  }

  /**
   * The commit that fills the mutable pages queues an automatic compaction, which could claim the index between buildBlockMax's
   * wait for a running compaction and its own compaction: that one then did not run, nothing waited for the automatic one, and the
   * sidecar was written while it was still merging, from no compacted part at all. The build answered false (a REBUILD INDEX
   * returned before the sidecar existed), seen as a rare failure of the tests that build right after their inserts. The automatic compaction is
   * started here at that point of the build.
   */
  @Test
  void anAutomaticCompactionThatStartsJustBeforeTheBuildCompactsIsWaitedFor() throws Exception {
    // No automatic compaction of the inserts: the one below is started at the point of the build where it used to be missed
    database.getConfiguration().setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
    final Random random = new Random(151);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");
    final LSMTreeIndex underlying = bucket.getUnderlyingIndex();
    assertThat(underlying.getMutableIndex().getSubIndex()).isNull();

    final AtomicBoolean started = new AtomicBoolean();
    LSMTreeFullTextIndex.blockMaxCompactionProbe = () -> {
      if (!started.compareAndSet(false, true))
        return;
      // What the commit's trigger does: the async worker claims the index and compacts it
      ((DatabaseAsyncExecutorImpl) database.async()).compact(underlying);
      // Until the worker has claimed the index, or already published its compaction
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (!underlying.isCompacting() && underlying.getMutableIndex().getSubIndex() == null && System.nanoTime() < deadline)
        Thread.onSpinWait();
    };
    assertThat(bucket.buildBlockMax()).isTrue();
    assertThat(started.get()).isTrue();
    assertThat(bucket.isBlockMaxCurrent()).isTrue();

    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 20);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }

  /**
   * A query thread interrupted while it reads the sidecar (a cancelled future, an executor shutting down) makes the shared file
   * channel throw ClosedByInterruptException, and closes it for every reader. That used to read as a damaged sidecar: the file was
   * deleted and rewritten in full, and every query scanned meanwhile. The interrupt is now the query's own error, the channel is
   * reopened for the next readers, and the sidecar stays.
   */
  @Test
  void anInterruptedQueryKeepsTheSidecar() {
    final Random random = new Random(81);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    final File sidecar = bucket("Doc[text]").blockMaxFile();
    assertThat(sidecar).exists();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> FullTextSearch.search(index("Doc[text]"), "w0 w1", 15))
          .hasRootCauseInstanceOf(ClosedByInterruptException.class);
    } finally {
      Thread.interrupted();
    }

    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    assertThat(bucket("Doc[text]").blockMaxFile()).isEqualTo(sidecar);
    assertThat(sidecar).exists();
    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 20);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
    assertThat(bucket("Doc[text]").blockMaxFile()).isEqualTo(sidecar);
  }

  /**
   * Only a compaction or an explicit command asked for a build. A build the database closed under (likely at scale, where it takes
   * minutes, during a deploy), or a sidecar lost while the database was closed, left the index scanning until it compacted again,
   * which a read-mostly database may never do. The open now asks for the build.
   */
  @Test
  void aSidecarMissingAtOpenIsWrittenInTheBackground() {
    createSingleBucketDoc();
    insert("Doc", 3000, new Random(91));
    buildBlockMax("Doc[text]");
    final File sidecar = bucket("Doc[text]").blockMaxFile();

    database.close();
    BlockMaxPostings.deleteQuietly(sidecar);
    reopenDatabase();

    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    assertThat(bucket("Doc[text]").blockMaxFile()).isNotNull();
    assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isTrue();
  }

  /** A build that fails (disk full, an I/O error) used to retire the sidecar in place, which was still exact. */
  @Test
  void aFailedBuildKeepsThePublishedSidecar() {
    final Random random = new Random(101);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");
    final File sidecar = bucket.blockMaxFile();

    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      throw new IllegalStateException("No space left on device (simulated)");
    };
    try {
      assertThat(bucket.rebuildBlockMax()).isFalse();
    } finally {
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }

    assertThat(bucket.blockMaxFile()).isEqualTo(sidecar);
    assertThat(sidecar).exists();
    assertThat(bucket.isBlockMaxCurrent()).isTrue();
    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 20);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }

  /**
   * COMPACT INDEX wrote the sidecar on the caller's thread only when there was nothing to compact: after a compaction that ran, it
   * only asked the background builder, so the statement returned before the sidecar existed. The background build is held here
   * until the statement has returned, so only a sidecar written by the statement itself is current.
   */
  @Test
  void compactIndexWritesTheSidecarBeforeItReturns() throws Exception {
    // No automatic compaction: the explicit one below is what compacts the documents written after the first sidecar
    database.getConfiguration().setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
    final Random random = new Random(111);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    insert("Doc", 3000, random);
    assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isTrue();

    final CountDownLatch statementReturned = new CountDownLatch(1);
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (Thread.currentThread().getName().equals("ArcadeDB-FullTextBlockMax"))
        try {
          statementReturned.await(60, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
    };
    try {
      try (final ResultSet result = database.command("sql", "COMPACT INDEX `Doc[text]`")) {
        assertThat(result.next().<Boolean>getProperty("compacted")).isTrue();
      }
      assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isTrue();
    } finally {
      statementReturned.countDown();
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }
    assertSameAnswers("Doc[text]", random, 20);
  }

  /**
   * Sidecar files were deleted only by the drop or the open of the index they belong to. An index dropped another way (the
   * documented downgrade: an older version's DROP or REBUILD INDEX of an index in the block-max format) left its files in
   * fulltext-blockmax for good. The read-write open (once the WAL is replayed) now deletes every file there whose index is not a live
   * full-text index.
   */
  @Test
  void theOpenDeletesTheSidecarsOfIndexesThatAreGone() throws Exception {
    createSingleBucketDoc();
    insert("Doc", 3000, new Random(121));
    buildBlockMax("Doc[text]");
    final File sidecar = bucket("Doc[text]").blockMaxFile();

    database.close();
    final File directory = sidecar.getParentFile();
    final File orphan = new File(directory, "Gone_0_1234567890.3.bmx");
    final File orphanTemporary = new File(directory, "Gone_0_1234567890.4.bmx.123456.tmp");
    final File orphanLegacy = new File(directory, "Gone_0_1234567890.bmx");
    for (final File file : new File[] { orphan, orphanTemporary, orphanLegacy })
      Files.copy(sidecar.toPath(), file.toPath());
    final File unrelated = new File(directory, "README.txt");
    Files.writeString(unrelated.toPath(), "not a sidecar");
    reopenDatabase();

    assertThat(orphan).doesNotExist();
    assertThat(orphanTemporary).doesNotExist();
    assertThat(orphanLegacy).doesNotExist();
    assertThat(unrelated).exists();
    assertThat(sidecar).exists();
    assertThat(bucket("Doc[text]").blockMaxFile()).isEqualTo(sidecar);
  }
  /**
   * The background builder checked whether the sidecar was current before it set up the database context the check's page reads
   * need: with a sidecar in place (every index past its first build) the check threw, the builder logged a warning and wrote
   * nothing, so a sidecar an automatic compaction had outdated stayed outdated, and its index scanning, until an explicit command.
   */
  @Test
  void anAutomaticCompactionRewritesAnOutdatedSidecarInTheBackground() throws Exception {
    database.getConfiguration().setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
    final Random random = new Random(141);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");
    final File first = bucket.blockMaxFile();
    insert("Doc", 3000, random);

    // What an automatic compaction does: its listener asks the background builder
    bucket.scheduleCompaction();
    assertThat(bucket.getUnderlyingIndex().compact()).isTrue();
    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();

    assertThat(bucket.isBlockMaxCurrent()).isTrue();
    assertThat(bucket.blockMaxFile()).isNotEqualTo(first);
    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 20);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }

  /**
   * The database close (and DROP DATABASE) never calls the index's close(): it flushes each index and calls
   * releaseBackgroundResources(), which the full-text index did not override. A sidecar build in progress (the one the open asks
   * for takes minutes at scale) then kept reading pages while the close flushed and purged the page cache and failed with a
   * warning, a queued one ran against the closed instance, and the sidecar's channel stayed open after the close. The build is
   * parked here in the middle of its walk until the close tells it to stop.
   */
  @Test
  void aDatabaseCloseStopsTheBuildInProgressAndClosesTheSidecar() throws Exception {
    database.getConfiguration().setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
    final Random random = new Random(131);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    insert("Doc", 3000, random);
    final LSMTreeFullTextIndex bucket = bucket("Doc[text]");
    final BlockMaxPostings sidecar = bucket.blockMaxPostings();
    assertThat(sidecar).isNotNull();
    assertThat(sidecar.isOpen()).isTrue();

    final CountDownLatch building = new CountDownLatch(1);
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (!Thread.currentThread().getName().equals("ArcadeDB-FullTextBlockMax"))
        return;
      building.countDown();
      // Bounded: without the stop signal the build goes on after the close, which the assertions below catch
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!bucket.isBlockMaxClosed() && System.nanoTime() < deadline)
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    };

    final Logger original = LogManager.instance().getLogger();
    final List<String> warnings = new CopyOnWriteArrayList<>();
    LogManager.instance().setLogger(new Logger() {
      @Override
      public void log(final Object requester, final Level level, final String message, final Throwable exception, final String context,
          final Object arg1, final Object arg2, final Object arg3, final Object arg4, final Object arg5, final Object arg6,
          final Object arg7, final Object arg8, final Object arg9, final Object arg10, final Object arg11, final Object arg12,
          final Object arg13, final Object arg14, final Object arg15, final Object arg16, final Object arg17) {
        record(requester, level, message);
        original.log(requester, level, message, exception, context, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10,
            arg11, arg12, arg13, arg14, arg15, arg16, arg17);
      }

      @Override
      public void log(final Object requester, final Level level, final String message, final Throwable exception, final String context,
          final Object... args) {
        record(requester, level, message);
        original.log(requester, level, message, exception, context, args);
      }

      private void record(final Object requester, final Level level, final String message) {
        if (level.intValue() >= Level.WARNING.intValue() && (requester instanceof LSMTreeFullTextIndex
            || requester == BlockMaxBuildScheduler.class))
          warnings.add(message);
      }

      @Override
      public void flush() {
        original.flush();
      }
    });
    try {
      // An automatic compaction: it asks the background builder for the sidecar, which parks after its first term
      bucket.scheduleCompaction();
      assertThat(bucket.getUnderlyingIndex().compact()).isTrue();
      assertThat(building.await(60, TimeUnit.SECONDS)).isTrue();
      // A second request, queued behind the one running
      BlockMaxBuildScheduler.schedule(bucket);
      assertThat(BlockMaxBuildScheduler.isPending(bucket)).isTrue();

      database.close();

      // The close returned once the build had stopped: it no longer reads pages, the queued request is gone
      assertThat(bucket.isBlockMaxBuilding()).isFalse();
      assertThat(BlockMaxBuildScheduler.isPending(bucket)).isFalse();
      assertThat(sidecar.isOpen()).isFalse();
      assertThat(sidecar.getFile()).exists();
      assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
      assertThat(warnings).isEmpty();
    } finally {
      LogManager.instance().setLogger(original);
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }

    // Nothing lost: the open asks for the build the close stopped
    reopenDatabase();
    assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
    assertThat(bucket("Doc[text]").isBlockMaxCurrent()).isTrue();
    assertSameAnswers("Doc[text]", random, 20);
  }

  /**
   * An HA follower applies every replicated compaction (and rename, and retired file) with a full schema load, which builds a new
   * full-text wrapper per bucket index and tells the previous one it is retired through onSuperseded(), a no-op for the full-text
   * index. The retired wrapper kept its sidecar channel open until GC (on Windows a handle that makes DROP DATABASE fail), its
   * build kept running against the superseded components and could publish into the dead instance, its queued request stayed
   * queued, the successor's open deleted the temporary files of that build, and nothing asked for the successor's own build, so
   * the follower answered top-k by reading the postings until a restart. The build is parked here in the middle of its walk, with
   * a second request queued behind it, while the schema loads the way a follower does.
   */
  @Test
  void aSchemaReloadRetiresTheSupersededSidecarAndBuildsTheSuccessors() throws Exception {
    database.getConfiguration().setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
    final Random random = new Random(151);
    createSingleBucketDoc();
    insert("Doc", 3000, random);
    buildBlockMax("Doc[text]");
    insert("Doc", 3000, random);
    final LSMTreeFullTextIndex superseded = bucket("Doc[text]");
    final BlockMaxPostings sidecar = superseded.blockMaxPostings();
    assertThat(sidecar).isNotNull();
    assertThat(sidecar.isOpen()).isTrue();

    final CountDownLatch building = new CountDownLatch(1);
    final CountDownLatch reloadChecked = new CountDownLatch(1);
    final AtomicBoolean temporaryFilesKept = new AtomicBoolean();
    LSMTreeFullTextIndex.blockMaxBuildProbe = () -> {
      if (!Thread.currentThread().getName().equals("ArcadeDB-FullTextBlockMax"))
        return;
      if (!superseded.isBlockMaxClosed()) {
        building.countDown();
        // Bounded: without the stop signal the build goes on after the reload, which the assertions below catch
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!superseded.isBlockMaxClosed() && System.nanoTime() < deadline)
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        // Still writing: the successor's open must have left the files of this build alone
        temporaryFilesKept.set(sidecarFiles(superseded.getName()).stream().anyMatch(name -> name.endsWith(".tmp")));
      }
      // Every build, the successor's too, waits for the checks right after the reload: the successor's would otherwise retire the
      // generation both wrappers opened before they could see it kept
      try {
        reloadChecked.await(60, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    try {
      // An automatic compaction: it asks the background builder for the sidecar, which parks after its first term
      superseded.scheduleCompaction();
      assertThat(superseded.getUnderlyingIndex().compact()).isTrue();
      assertThat(building.await(60, TimeUnit.SECONDS)).isTrue();
      // A second request, queued behind the one running
      BlockMaxBuildScheduler.schedule(superseded);
      assertThat(BlockMaxBuildScheduler.isPending(superseded)).isTrue();

      // What a follower does for every compaction entry it applies
      database.getSchema().getEmbedded().load(ComponentFile.MODE.READ_WRITE, true);

      final LSMTreeFullTextIndex successor = bucket("Doc[text]");
      assertThat(successor).isNotSameAs(superseded);
      // The reload retired the previous wrapper: no build of it is waiting, its sidecar's channel is closed and its file kept, since
      // the successor opened the same one
      assertThat(superseded.isBlockMaxClosed()).isTrue();
      assertThat(BlockMaxBuildScheduler.isPending(superseded)).isFalse();
      assertThat(sidecar.isOpen()).isFalse();
      assertThat(sidecar.getFile()).exists();
      assertThat(successor.isBlockMaxClosed()).isFalse();
      assertThat(successor.blockMaxFile()).isEqualTo(sidecar.getFile());
      reloadChecked.countDown();

      // The build of the retired wrapper stopped, and the successor asked for its own, without waiting for a restart
      assertThat(BlockMaxBuildScheduler.awaitIdle(60_000)).isTrue();
      assertThat(superseded.isBlockMaxBuilding()).isFalse();
      assertThat(temporaryFilesKept.get()).isTrue();
      assertThat(superseded.blockMaxPostings()).isNull();
      assertThat(successor.isBlockMaxCurrent()).isTrue();
      assertThat(successor.blockMaxFile()).exists();
      // Nothing of the retired build is left behind
      assertThat(sidecarFiles(successor.getName())).noneMatch(name -> name.endsWith(".tmp"));
    } finally {
      reloadChecked.countDown();
      LSMTreeFullTextIndex.blockMaxBuildProbe = null;
    }

    final long blockMaxBefore = FullTextTopK.getBlockMaxInvocations();
    assertSameAnswers("Doc[text]", random, 20);
    assertThat(FullTextTopK.getBlockMaxInvocations()).isGreaterThan(blockMaxBefore);
  }
}
