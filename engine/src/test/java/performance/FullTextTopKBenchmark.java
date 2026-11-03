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
import com.arcadedb.database.RID;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.fulltext.FullTextSearch;
import com.arcadedb.index.fulltext.FullTextTopK;
import com.arcadedb.index.fulltext.LSMTreeFullTextIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 top-20 latency over a Zipfian corpus, three ways on the same database: the general path (every match materialized,
 * postings read twice), the top-k scorer reading every posting once ({@code arcadedb.fullText.blockMax=false}), and the top-k
 * scorer skipping blocks with the block-max sidecar. A 1-term query at several frequency ranks shows how the cost scales with
 * the posting-list length; then 2-term AND and OR queries. All three must return the same matches with the same scores; the
 * benchmark checks it.
 * <p>
 * Run explicitly with:
 * {@code JAVA_TOOL_OPTIONS=-Xmx8g mvn -o -pl engine test -Dtest=FullTextTopKBenchmark -Dgroups=benchmark -DexcludedGroups=none}
 * <br>
 * {@code -Darcadedb.topKBenchmark.documents=1000000} (default) sets the corpus size.
 */
@Tag("benchmark")
class FullTextTopKBenchmark {
  private static final String DOCUMENTS_PROPERTY = "arcadedb.topKBenchmark.documents";
  private static final int    VOCABULARY         = 50_000;
  private static final double ZIPF_EXPONENT      = 1.0;
  private static final int    MIN_LENGTH         = 10;
  private static final int    MAX_LENGTH         = 30;
  private static final int    BATCH              = 20_000;
  private static final int    WARMUP             = 3;
  private static final int    RUNS               = Integer.getInteger("arcadedb.topKBenchmark.runs", 25);
  private static final int    K                  = 20;
  private static final int[]  PROBED_RANKS       = { 1, 3, 10, 30, 100, 300, 1_000, 10_000 };
  private static final String DB_PATH            = "target/databases/FullTextTopKBenchmark";

  @Test
  void topKLatency() {
    final int documents = Integer.getInteger(DOCUMENTS_PROPERTY, 1_000_000);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final DatabaseFactory factory = new DatabaseFactory(DB_PATH); final Database database = factory.create()) {
      database.transaction(() -> {
        database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 8");
        database.command("sql", "CREATE PROPERTY Doc.text STRING");
        database.command("sql", "CREATE INDEX ON Doc (text) FULL_TEXT");
      });

      final long[] df = new long[PROBED_RANKS.length];
      final long loadStart = System.nanoTime();
      load(database, documents, df);
      final long loadMs = (System.nanoTime() - loadStart) / 1_000_000;
      final long compactStart = System.nanoTime();
      compactAll(database);
      final TypeIndex index = (TypeIndex) database.getSchema().getIndexByName("Doc[text]");
      // The compaction writes the sidecars; a bucket whose compaction had nothing to do gets its own now
      long sidecarBytes = 0;
      long indexBytes = 0;
      for (final IndexInternal bucketIndex : index.getIndexesOnBuckets()) {
        final LSMTreeFullTextIndex ftIndex = (LSMTreeFullTextIndex) bucketIndex;
        assertThat(ftIndex.buildBlockMax()).isTrue();
        // <bucket index name>.<generation>.bmx
        final File[] sidecars = new File(database.getDatabasePath(), "fulltext-blockmax").listFiles();
        if (sidecars != null)
          for (final File sidecar : sidecars)
            if (sidecar.getName().startsWith(ftIndex.getName() + ".") && sidecar.getName().endsWith(".bmx"))
              sidecarBytes += sidecar.length();
      }
      final long compactMs = (System.nanoTime() - compactStart) / 1_000_000;
      for (final File file : new File(database.getDatabasePath()).listFiles())
        if (file.getName().startsWith("Doc_") && (file.getName().contains(".nuidx") || file.getName().contains(".numtidx")
            || file.getName().contains("idx")))
          indexBytes += file.length();

      System.out.printf(Locale.ENGLISH, "%nTop-%d BM25 benchmark: %,d documents, %,d-term Zipf(%.1f) vocabulary, %d-%d tokens each, "
              + "%d buckets, load %,d ms, compaction + block-max sidecars %,d ms, sidecars %,d MB (LSM index files %,d MB)%n", K,
          documents, VOCABULARY, ZIPF_EXPONENT, MIN_LENGTH, MAX_LENGTH, index.getIndexesOnBuckets().length, loadMs, compactMs,
          sidecarBytes >> 20, indexBytes >> 20);
      System.out.printf(Locale.ENGLISH, "%-22s %10s %9s %9s %9s %9s %9s %9s %14s%n", "query", "postings", "gen p50", "gen p95",
          "scan p50", "scan p95", "bmax p50", "bmax p95", "blocks read");

      for (int i = 0; i < PROBED_RANKS.length; i++)
        row(database, index, "1-term rank " + PROBED_RANKS[i], word(PROBED_RANKS[i]), df[i]);
      row(database, index, "2-term AND r3+r30", "+" + word(3) + " +" + word(30), df[1] + df[3]);
      row(database, index, "2-term OR r3+r30", word(3) + " " + word(30), df[1] + df[3]);
      row(database, index, "2-term AND r30+r300", "+" + word(30) + " +" + word(300), df[3] + df[5]);
      row(database, index, "2-term OR r30+r300", word(30) + " " + word(300), df[3] + df[5]);
      row(database, index, "2-term OR r1+r10", word(1) + " " + word(10), df[0] + df[2]);

      sqlRow(database, "SQL 1-term rank 10", word(10));
      database.drop();
    }
  }

  private static String word(final int rank) {
    return "t" + rank;
  }

  private static void load(final Database database, final int documents, final long[] df) {
    // Cumulative Zipf distribution over ranks 1..VOCABULARY, sampled by binary search
    final double[] cdf = new double[VOCABULARY];
    double sum = 0;
    for (int r = 1; r <= VOCABULARY; r++) {
      sum += 1.0 / Math.pow(r, ZIPF_EXPONENT);
      cdf[r - 1] = sum;
    }
    for (int r = 0; r < VOCABULARY; r++)
      cdf[r] /= sum;

    final Random random = new Random(11);
    final boolean[] seen = new boolean[PROBED_RANKS.length];
    final StringBuilder text = new StringBuilder(256);
    for (int start = 0; start < documents; start += BATCH) {
      final int from = start;
      database.transaction(() -> {
        for (int d = from; d < Math.min(documents, from + BATCH); d++) {
          text.setLength(0);
          Arrays.fill(seen, false);
          final int length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
          for (int t = 0; t < length; t++) {
            int rank = Arrays.binarySearch(cdf, random.nextDouble());
            rank = (rank < 0 ? -rank - 1 : rank) + 1;
            text.append(t > 0 ? " " : "").append('t').append(rank);
            for (int p = 0; p < PROBED_RANKS.length; p++)
              if (PROBED_RANKS[p] == rank)
                seen[p] = true;
          }
          for (int p = 0; p < PROBED_RANKS.length; p++)
            if (seen[p])
              ++df[p];
          database.newDocument("Doc").set("text", text.toString()).save();
        }
      });
    }
  }

  private static void compactAll(final Database database) {
    for (final Index index : database.getSchema().getIndexes())
      if (index instanceof TypeIndex typeIndex)
        for (final IndexInternal bucketIndex : typeIndex.getIndexesOnBuckets())
          try {
            bucketIndex.scheduleCompaction();
            bucketIndex.compact();
          } catch (final Exception e) {
            throw new RuntimeException("Cannot compact " + bucketIndex.getName(), e);
          }
  }

  private enum Mode {GENERAL, SCAN, BLOCK_MAX}

  private static void row(final Database database, final TypeIndex index, final String label, final String query,
      final long postings) {
    final double[] general = measure(database, index, query, Mode.GENERAL);
    final double[] scan = measure(database, index, query, Mode.SCAN);
    final long decodedBefore = FullTextTopK.getBlocksDecoded();
    final double[] blockMax = measure(database, index, query, Mode.BLOCK_MAX);
    final long decodedPerQuery = (FullTextTopK.getBlocksDecoded() - decodedBefore) / (WARMUP + RUNS);

    setMode(database, Mode.GENERAL);
    final Map<RID, Float> expected = FullTextSearch.search(index, query, K);
    for (final Mode mode : new Mode[] { Mode.SCAN, Mode.BLOCK_MAX }) {
      setMode(database, mode);
      assertThat(FullTextSearch.search(index, query, K)).as(query + " " + mode).isEqualTo(expected);
    }
    setMode(database, Mode.BLOCK_MAX);

    System.out.printf(Locale.ENGLISH, "%-22s %,10d %9.1f %9.1f %9.1f %9.1f %9.2f %9.2f %,7d/%,-6d%n", label, postings, general[0],
        general[1], scan[0], scan[1], blockMax[0], blockMax[1], decodedPerQuery, (postings + 127) / 128);
  }

  private static void setMode(final Database database, final Mode mode) {
    database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, mode != Mode.GENERAL);
    database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_BLOCK_MAX, mode == Mode.BLOCK_MAX);
  }

  private static double[] measure(final Database database, final TypeIndex index, final String query, final Mode mode) {
    setMode(database, mode);
    try {
      for (int i = 0; i < WARMUP; i++)
        FullTextSearch.search(index, query, K);
      final long[] times = new long[RUNS];
      for (int i = 0; i < RUNS; i++) {
        final long start = System.nanoTime();
        FullTextSearch.search(index, query, K);
        times[i] = System.nanoTime() - start;
      }
      Arrays.sort(times);
      return new double[] { times[RUNS / 2] / 1e6, times[(int) Math.ceil(RUNS * 0.95) - 1] / 1e6 };
    } finally {
      setMode(database, Mode.BLOCK_MAX);
    }
  }

  private static void sqlRow(final Database database, final String label, final String query) {
    final String sql = "SELECT FROM Doc WHERE SEARCH_INDEX('Doc[text]', ?) = true ORDER BY $score DESC LIMIT " + K;
    final double[] times = new double[3];
    for (final Mode mode : Mode.values()) {
      setMode(database, mode);
      final long[] runs = new long[RUNS];
      for (int i = 0; i < WARMUP + RUNS; i++) {
        final long start = System.nanoTime();
        try (final ResultSet rs = database.query("sql", sql, query)) {
          while (rs.hasNext())
            rs.next();
        }
        if (i >= WARMUP)
          runs[i - WARMUP] = System.nanoTime() - start;
      }
      Arrays.sort(runs);
      times[mode.ordinal()] = runs[RUNS / 2] / 1e6;
    }
    setMode(database, Mode.BLOCK_MAX);
    System.out.printf(Locale.ENGLISH, "%-22s %10s %9.1f %9s %9.1f %9s %9.2f%n", label, "", times[0], "", times[1], "", times[2]);
  }
}
