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
package com.arcadedb.similarity;

import com.arcadedb.TestHelper;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.arcadedb.similarity.SimilarityTestImages.encode;
import static com.arcadedb.similarity.SimilarityTestImages.scene;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDQ throughput for docs/spork/workstreams/ws12-pdq.md: decode and hash time per image by size, bytes allocated per hash
 * against the literal reference structure, multi-threaded images per second, and the LSM probe cost of {@code pdqBands} at
 * radius 15 and 31. Prints a table; asserts nothing about time.
 */
@Tag("benchmark")
class PdqThroughputBenchmark extends TestHelper {
  private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  @Test
  void hashThroughput() throws Exception {
    final int[][] sizes = { { 640, 480 }, { 1024, 768 }, { 1600, 1200 }, { 4032, 3024 } };
    System.out.printf("%-11s %8s %10s %10s %10s %12s %14s%n", "size", "jpeg KB", "decode ms", "hash ms", "ref ms",
        "port alloc", "reference alloc");
    for (final int[] size : sizes) {
      final byte[] jpeg = encode(scene(size[0] * 31L + size[1], size[0], size[1]), "jpg");
      final int rounds = size[0] >= 4000 ? 8 : 30;
      final PdqHasher hasher = new PdqHasher();
      BufferedImage image = null;
      long decode = 0;
      long hash = 0;
      long reference = 0;
      for (int warm = 0; warm < 3; warm++) {
        image = PerceptualHash.decodeImage(jpeg, PerceptualHash.DEFAULT_MAX_PIXELS, 0);
        hasher.hash(image);
        PdqReferenceTranscription.hash(image);
      }
      for (int i = 0; i < rounds; i++) {
        long t = System.nanoTime();
        image = PerceptualHash.decodeImage(jpeg, PerceptualHash.DEFAULT_MAX_PIXELS, 0);
        decode += System.nanoTime() - t;
        t = System.nanoTime();
        hasher.hash(image);
        hash += System.nanoTime() - t;
        t = System.nanoTime();
        PdqReferenceTranscription.hash(image);
        reference += System.nanoTime() - t;
      }
      final long id = Thread.currentThread().threadId();
      long before = THREADS.getThreadAllocatedBytes(id);
      hasher.hash(image);
      final long portAlloc = THREADS.getThreadAllocatedBytes(id) - before;
      before = THREADS.getThreadAllocatedBytes(id);
      PdqReferenceTranscription.hash(image);
      final long referenceAlloc = THREADS.getThreadAllocatedBytes(id) - before;
      System.out.printf("%-11s %8d %10.2f %10.2f %10.2f %12s %14s%n", size[0] + "x" + size[1], jpeg.length / 1024,
          decode / 1e6 / rounds, hash / 1e6 / rounds, reference / 1e6 / rounds, bytes(portAlloc), bytes(referenceAlloc));
    }

    // images per second end to end (decode + PDQ + 8 dihedral hashes), one hasher per thread
    final byte[] jpeg = encode(scene(77, 1024, 768), "jpg");
    for (final int threads : new int[] { 1, 4, 16 }) {
      final ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        final int perThread = 40;
        final long t = System.nanoTime();
        final List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++)
          futures.add(pool.submit(() -> {
            final PdqHasher hasher = new PdqHasher();
            for (int k = 0; k < perThread; k++)
              hasher.dihedral(jpeg);
          }));
        for (final Future<?> f : futures)
          f.get();
        final double seconds = (System.nanoTime() - t) / 1e9;
        System.out.printf("1024x768 JPEG, %2d threads: %7.0f images/s%n", threads, threads * perThread / seconds);
      } finally {
        pool.shutdown();
      }
    }
  }

  @Test
  void probeCost() {
    final Random random = new Random(3);
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Img");
      database.command("sql", "CREATE PROPERTY Img.pdq BINARY");
      database.command("sql", "CREATE PROPERTY Img.pdqBands LIST OF LONG");
      database.command("sql", "CREATE INDEX ON Img (pdqBands BY ITEM) NOTUNIQUE");
    });
    final int population = 200_000;
    for (int batch = 0; batch < population / 10_000; batch++)
      database.transaction(() -> {
        for (int i = 0; i < 10_000; i++) {
          final long[] pdq = PdqBandsTest.randomHash(random);
          final List<Long> bands = new ArrayList<>(8);
          for (final long k : PdqBands.keys(pdq))
            bands.add(k);
          database.newDocument("Img").set("pdq", BitHash.toBytes(pdq)).set("pdqBands", bands).save();
        }
      });
    final TypeIndex index = database.getSchema().getType("Img").getPolymorphicIndexByProperties("pdqBands by item");
    for (final int radius : new int[] { 15, 31 }) {
      final int queries = radius == 15 ? 2_000 : 50;
      long candidates = 0;
      for (int i = 0; i < 5; i++)
        lookup(index, PdqBands.probeKeys(PdqBandsTest.randomHash(random), radius));
      final long t = System.nanoTime();
      for (int i = 0; i < queries; i++)
        candidates += lookup(index, PdqBands.probeKeys(PdqBandsTest.randomHash(random), radius));
      final double ms = (System.nanoTime() - t) / 1e6 / queries;
      System.out.printf("radius %2d, %d stored: %6d probe keys, %8.3f ms per query, %.2f candidates%n", radius, population,
          PdqBands.probeCount(radius), ms, (double) candidates / queries);
    }
    assertThat(index).isNotNull();
  }

  private static int lookup(final TypeIndex index, final long[] keys) {
    int candidates = 0;
    for (final long key : keys) {
      final IndexCursor cursor = index.get(new Object[] { key });
      while (cursor.hasNext()) {
        cursor.next();
        candidates++;
      }
    }
    return candidates;
  }

  private static String bytes(final long bytes) {
    return bytes >= 1 << 20 ? String.format("%.1f MB", bytes / 1048576.0) : String.format("%.1f KB", bytes / 1024.0);
  }
}
