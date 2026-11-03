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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.RID;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
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
 * Block-max conjunctions with and without the candidate check on the lead's exact contribution
 * ({@link BlockMaxScorer#exactLeadBound}), as alternating A/B pairs on one database so that load from other processes falls on
 * both sides alike. Same Zipf corpus as {@code performance.FullTextTopKBenchmark}. Both sides must return the same top 20.
 * <p>
 * {@code mvn -o -pl engine test -Dtest=FullTextConjunctionBenchmark -Dgroups=benchmark -DexcludedGroups=none}, with
 * {@code -Darcadedb.conjunctionBenchmark.documents=1000000} (default) and {@code -Darcadedb.conjunctionBenchmark.pairs=30}.
 */
@Tag("benchmark")
class FullTextConjunctionBenchmark {
  private static final int    VOCABULARY = 50_000;
  private static final int    MIN_LENGTH = 10;
  private static final int    MAX_LENGTH = 30;
  private static final int    BATCH      = 20_000;
  private static final int    K          = 20;
  private static final int    PAIRS      = Integer.getInteger("arcadedb.conjunctionBenchmark.pairs", 30);
  private static final String DB_PATH    = "target/databases/FullTextConjunctionBenchmark";

  @Test
  void exactLeadBoundAgainstBlockBoundsAlone() {
    final int documents = Integer.getInteger("arcadedb.conjunctionBenchmark.documents", 1_000_000);
    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final DatabaseFactory factory = new DatabaseFactory(DB_PATH); final Database database = factory.create()) {
      database.transaction(() -> {
        database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 8");
        database.command("sql", "CREATE PROPERTY Doc.text STRING");
        database.command("sql", "CREATE INDEX ON Doc (text) FULL_TEXT");
      });
      load(database, documents);
      final TypeIndex index = (TypeIndex) database.getSchema().getIndexByName("Doc[text]");
      for (final IndexInternal bucketIndex : index.getIndexesOnBuckets())
        assertThat(((LSMTreeFullTextIndex) bucketIndex).buildBlockMax()).isTrue();
      BlockMaxBuildScheduler.awaitIdle(600_000);

      System.out.printf(Locale.ENGLISH, "%nBlock-max conjunctions, top-%d, %,d documents, %d A/B pairs (A = block bounds only, B = exact lead "
          + "bound)%n", K, documents, PAIRS);
      System.out.printf(Locale.ENGLISH, "%-26s %10s %10s %10s %10s %12s %12s%n", "query", "A p50 ms", "B p50 ms", "A p95", "B p95",
          "A blocks", "B blocks");
      for (final String query : new String[] { "+t3 +t30", "+t30 +t300", "+t1 +t10", "+t10 +t100", "+t3 +t30 +t300", "+t1 +t3" })
        pair(index, query);
    } finally {
      BlockMaxScorer.exactLeadBound = true;
    }
  }

  private static void pair(final TypeIndex index, final String query) {
    BlockMaxScorer.exactLeadBound = false;
    final Map<RID, Float> expected = FullTextSearch.search(index, query, K);
    BlockMaxScorer.exactLeadBound = true;
    assertThat(FullTextSearch.search(index, query, K)).as(query).isEqualTo(expected);

    // warm-up, both sides
    for (int i = 0; i < 3; i++)
      for (final boolean exact : new boolean[] { false, true }) {
        BlockMaxScorer.exactLeadBound = exact;
        FullTextSearch.search(index, query, K);
      }

    final long[] a = new long[PAIRS];
    final long[] b = new long[PAIRS];
    final long[] blocks = new long[2];
    for (int i = 0; i < PAIRS; i++) {
      // alternate which side goes first
      for (int side = 0; side < 2; side++) {
        final boolean exact = (side == 0) == (i % 2 == 1);
        BlockMaxScorer.exactLeadBound = exact;
        final long decodedBefore = FullTextTopK.getBlocksDecoded();
        final long start = System.nanoTime();
        FullTextSearch.search(index, query, K);
        final long elapsed = System.nanoTime() - start;
        blocks[exact ? 1 : 0] += FullTextTopK.getBlocksDecoded() - decodedBefore;
        if (exact)
          b[i] = elapsed;
        else
          a[i] = elapsed;
      }
    }
    Arrays.sort(a);
    Arrays.sort(b);
    System.out.printf(Locale.ENGLISH, "%-26s %10.2f %10.2f %10.2f %10.2f %,12d %,12d%n", query, a[PAIRS / 2] / 1e6, b[PAIRS / 2] / 1e6,
        a[(int) Math.ceil(PAIRS * 0.95) - 1] / 1e6, b[(int) Math.ceil(PAIRS * 0.95) - 1] / 1e6, blocks[0] / PAIRS, blocks[1] / PAIRS);
  }

  private static void load(final Database database, final int documents) {
    final double[] cdf = new double[VOCABULARY];
    double sum = 0;
    for (int r = 1; r <= VOCABULARY; r++) {
      sum += 1.0 / r;
      cdf[r - 1] = sum;
    }
    for (int r = 0; r < VOCABULARY; r++)
      cdf[r] /= sum;
    final Random random = new Random(11);
    final StringBuilder text = new StringBuilder(256);
    for (int start = 0; start < documents; start += BATCH) {
      final int from = start;
      database.transaction(() -> {
        for (int d = from; d < Math.min(documents, from + BATCH); d++) {
          text.setLength(0);
          final int length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
          for (int t = 0; t < length; t++) {
            int rank = Arrays.binarySearch(cdf, random.nextDouble());
            rank = (rank < 0 ? -rank - 1 : rank) + 1;
            text.append(t > 0 ? " " : "").append('t').append(rank);
          }
          database.newDocument("Doc").set("text", text.toString()).save();
        }
      });
    }
  }
}
