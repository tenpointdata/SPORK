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
package com.arcadedb.index.ivfvector;

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.vector.LSMVectorIndex;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code LSM_VECTOR} (BINARY, 16 buckets) against {@code LSM_IVF_VECTOR} (BINARY scan, INT8 rerank, one bucket) on a clustered
 * 512-d corpus: build time and peak heap, resident heap after a reopen, bytes on disk, p50/p95 latency and recall@10 at k=10,
 * and the same under allow-lists of 1% and 20% of the records. Sized with {@code -Divf.bench.rows} (default 1M; the brief
 * asks for 5M, 20M where the machine allows). Results go to stdout as a Markdown table.
 */
@Tag("benchmark")
class LSMIVFVectorIndexBenchmark extends TestHelper {
  private static final int   ROWS     = Integer.getInteger("ivf.bench.rows", 1_000_000);
  private static final int   DIMS     = 512;
  private static final int   CLUSTERS = Integer.getInteger("ivf.bench.clusters", 5_000);
  private static final float SPREAD   = 0.3f;
  private static final int   QUERIES  = Integer.getInteger("ivf.bench.queries", 200);
  private static final int   K        = 10;
  private static final int   NPROBE   = Integer.getInteger("ivf.bench.nprobe", 32);
  private static final int   BATCH    = 2_000;
  private static final boolean SKIP_GRAPH = Boolean.getBoolean("ivf.bench.skipGraph");
  /** Runs {@link #hardeningAB()} instead of {@link #graphAgainstIvf()}. */
  private static final boolean HARDENING = Boolean.getBoolean("ivf.bench.hardening");
  /** Runs {@link #stagedBuildAB()} instead of {@link #graphAgainstIvf()}. */
  private static final boolean STAGED_BUILD = Boolean.getBoolean("ivf.bench.stagedBuild");

  private final StringBuilder report = new StringBuilder();

  @Test
  void graphAgainstIvf() throws Exception {
    Assumptions.assumeFalse(HARDENING, "-Divf.bench.hardening=true runs hardeningAB() instead");
    Assumptions.assumeFalse(STAGED_BUILD, "-Divf.bench.stagedBuild=true runs stagedBuildAB() instead");
    final Random random = new Random(11);
    final float[][] centers = new float[CLUSTERS][DIMS];
    for (final float[] c : centers)
      for (int d = 0; d < DIMS; d++)
        c[d] = (float) random.nextGaussian();
    // The corpus, kept resident for the brute-force ground truth: ROWS rows of DIMS floats (one flat array overflows at 5M x 512)
    final float[][] corpus = new float[ROWS][];
    for (int i = 0; i < ROWS; i++) {
      final float[] center = centers[random.nextInt(CLUSTERS)];
      final float[] row = new float[DIMS];
      for (int d = 0; d < DIMS; d++)
        row[d] = center[d] + (float) random.nextGaussian() * SPREAD;
      corpus[i] = row;
    }
    final float[][] queries = new float[QUERIES][DIMS];
    for (final float[] q : queries) {
      final float[] row = corpus[random.nextInt(ROWS)];
      for (int d = 0; d < DIMS; d++)
        q[d] = row[d] + (float) random.nextGaussian() * 0.1f;
    }
    log("rows=%d dims=%d clusters=%d queries=%d k=%d nprobe=%d", ROWS, DIMS, CLUSTERS, QUERIES, K, NPROBE);
    report.append("| Metric | LSM_VECTOR BINARY, 16 buckets | LSM_IVF_VECTOR BINARY+INT8 |\n|---|---|---|\n");

    // Insert the same corpus twice: one type per index family
    final List<RID> ivfRids = insertCorpus("IvfBench", 1, corpus);
    final List<RID> graphRids = SKIP_GRAPH ? null : insertCorpus("GraphBench", 16, corpus);

    // Builds
    final HeapSampler sampler = new HeapSampler();
    final long[] graphBuild = SKIP_GRAPH ? new long[] { -1, -1 } : timedWithPeakHeap(sampler, () -> {
      database.getSchema().buildTypeIndex("GraphBench", new String[] { "embedding" }).withLSMVectorType().withDimensions(DIMS)
          .withSimilarity("COSINE").withQuantization("BINARY").create();
      for (final Index idx : typeIndex("GraphBench").getIndexesOnBuckets())
        ((LSMVectorIndex) idx).buildVectorGraphNow();
    });
    final long[] ivfBuild = timedWithPeakHeap(sampler, () -> database.getSchema().buildTypeIndex("IvfBench", new String[] { "embedding" })
        .withIVFVectorType().withDimensions(DIMS).withSimilarity("COSINE").withScanCode(System.getProperty("ivf.bench.scan", "BINARY"))
        .withRerankCode(System.getProperty("ivf.bench.rerank", "INT8"))
        .withTargetPostingSize(200).withTrainingSample(Math.min(ROWS, 200_000)).withKmeansIterations(8).create());
    row("build time (s)", seconds(graphBuild[0]), seconds(ivfBuild[0]));
    row("build peak heap above baseline (MB)", mb(graphBuild[1]), mb(ivfBuild[1]));

    // Bytes on disk
    row("index bytes on disk (MB)", mb(indexBytes("GraphBench")), mb(indexBytes("IvfBench")));

    // Resident heap after a reopen and one warm search per index
    database.close();
    final long baseline = usedHeapAfterGc();
    database = factory.open();
    final long afterOpen = usedHeapAfterGc() - baseline;
    final long ivfResident;
    final long graphResident;
    database.transaction(() -> ivf().findNeighbors(queries[0], K, NPROBE, -1, null));
    ivfResident = usedHeapAfterGc() - baseline - afterOpen;
    if (!SKIP_GRAPH) {
      database.transaction(() -> {
        for (final LSMVectorIndex g : graphs())
          g.findNeighborsFromVector(queries[0], K, -1, null);
      });
      graphResident = usedHeapAfterGc() - baseline - afterOpen - ivfResident;
    } else
      graphResident = -1;
    row("resident heap after open + first search (MB)", mb(graphResident), mb(ivfResident));
    row("IVF centroids resident (MB, exact)", "-", String.format("%.1f", ivf().getResidentBytes() / 1048576.0));

    // Latency and recall, unfiltered and filtered
    final boolean filterOnly = Boolean.getBoolean("ivf.bench.filterOnly");
    final int[][] truth = filterOnly ? null : groundTruth(corpus, queries, null);
    final Set<RID> onePercent = pick(ivfRids, ROWS / 100, random);
    final Set<RID> twentyPercent = pick(ivfRids, ROWS / 5, random);
    final boolean[] oneMask = mask(onePercent, ivfRids);
    final boolean[] twentyMask = mask(twentyPercent, ivfRids);
    final int[][] truthOne = groundTruth(corpus, queries, oneMask);
    final int[][] truthTwenty = groundTruth(corpus, queries, twentyMask);
    final Set<RID> graphOne = SKIP_GRAPH ? null : translate(onePercent, ivfRids, graphRids);
    final Set<RID> graphTwenty = SKIP_GRAPH ? null : translate(twentyPercent, ivfRids, graphRids);

    final Map<Long, Integer> ivfOrdinal = ordinalOf(ivfRids);
    final Map<Long, Integer> graphOrdinal = SKIP_GRAPH ? null : ordinalOf(graphRids);
    measure("unfiltered", queries, truth, graphOrdinal, ivfOrdinal, null, null, ivfRids, graphRids);
    sweep(queries, truth, ivfOrdinal);
    measure("filtered 1%", queries, truthOne, graphOrdinal, ivfOrdinal, graphOne, onePercent, ivfRids, graphRids);
    measure("filtered 20%", queries, truthTwenty, graphOrdinal, ivfOrdinal, graphTwenty, twentyPercent, ivfRids, graphRids);

    System.out.println("\n### LSM_IVF_VECTOR benchmark, rows=" + ROWS + "\n\n" + report);
  }

  /**
   * The build's pass 2 staged on the commit lane (review round 4) against the appends in the chunk's body it replaced, on the same
   * records in ONE run: staged (create), then direct, staged, direct (rebuilds), so a shared box loads both sides alike.
   * {@code -Divf.bench.stagedBuild=true}.
   */
  @Test
  void stagedBuildAB() {
    Assumptions.assumeTrue(STAGED_BUILD, "-Divf.bench.stagedBuild=true");
    final Random random = new Random(11);
    final float[][] centers = new float[CLUSTERS][DIMS];
    for (final float[] c : centers)
      for (int d = 0; d < DIMS; d++)
        c[d] = (float) random.nextGaussian();
    final float[][] corpus = new float[ROWS][];
    for (int i = 0; i < ROWS; i++) {
      final float[] center = centers[random.nextInt(CLUSTERS)];
      final float[] row = new float[DIMS];
      for (int d = 0; d < DIMS; d++)
        row[d] = center[d] + (float) random.nextGaussian() * SPREAD;
      corpus[i] = row;
    }
    log("staged build A/B rows=%d dims=%d clusters=%d", ROWS, DIMS, CLUSTERS);
    insertCorpus("IvfBench", 1, corpus);

    final boolean[] staged = { true, false, true, false };
    final long[] builds = new long[staged.length];
    try {
      for (int i = 0; i < staged.length; i++) {
        LSMIVFVectorIndex.setStagedBuild(staged[i]);
        if (i == 0)
          builds[i] = timed(() -> database.getSchema().buildTypeIndex("IvfBench", new String[] { "embedding" }).withIVFVectorType()
              .withDimensions(DIMS).withSimilarity("COSINE").withScanCode("BINARY").withRerankCode("INT8").withTargetPostingSize(200)
              .withTrainingSample(Math.min(ROWS, 200_000)).withKmeansIterations(8).create());
        else
          builds[i] = timed(() -> database.command("sql", "REBUILD INDEX `" + typeIndex("IvfBench").getName() + "`"));
        log("build %d (%s): %ss, %.0f vectors/s, %d entries", i, staged[i] ? "staged" : "direct", seconds(builds[i]),
            ROWS / (builds[i] / 1e9), ivf().countEntries());
      }
    } finally {
      LSMIVFVectorIndex.setStagedBuild(true);
    }
    report.append("| Build (").append(ROWS).append(" x ").append(DIMS).append(") | pass 2 | time (s) | vectors/s |\n|---|---|---|---|\n");
    for (int i = 0; i < staged.length; i++)
      report.append("| ").append(i == 0 ? "create" : "rebuild").append(" | ").append(staged[i] ? "staged on the lane" : "appended in the chunk")
          .append(" | ").append(seconds(builds[i])).append(" | ").append(String.format("%.0f", ROWS / (builds[i] / 1e9))).append(" |\n");
    System.out.println("\n### LSM_IVF_VECTOR staged build A/B, rows=" + ROWS + "\n\n" + report);
  }

  /**
   * The hardening items as A/B pairs in ONE run, alternating per query (or per build) so a shared box loads both sides alike:
   * pass 2 of the build serial against parallel (vectors/s), posting reads serial against parallel, the old default rerank budget
   * (10 x k) against the absolute one (2,000), the exact rerank tier, the filters, and an INT4 rerank index against the INT8 one on
   * the same corpus (recall, bytes). IVF only. {@code -Divf.bench.hardening=true}.
   */
  @Test
  void hardeningAB() throws Exception {
    Assumptions.assumeTrue(HARDENING, "-Divf.bench.hardening=true");
    final Random random = new Random(11);
    final float[][] centers = new float[CLUSTERS][DIMS];
    for (final float[] c : centers)
      for (int d = 0; d < DIMS; d++)
        c[d] = (float) random.nextGaussian();
    final float[][] corpus = new float[ROWS][];
    for (int i = 0; i < ROWS; i++) {
      final float[] center = centers[random.nextInt(CLUSTERS)];
      final float[] row = new float[DIMS];
      for (int d = 0; d < DIMS; d++)
        row[d] = center[d] + (float) random.nextGaussian() * SPREAD;
      corpus[i] = row;
    }
    final float[][] queries = new float[QUERIES][DIMS];
    for (final float[] q : queries) {
      final float[] row = corpus[random.nextInt(ROWS)];
      for (int d = 0; d < DIMS; d++)
        q[d] = row[d] + (float) random.nextGaussian() * 0.1f;
    }
    log("hardening rows=%d dims=%d clusters=%d queries=%d k=%d nprobe=%d", ROWS, DIMS, CLUSTERS, QUERIES, K, NPROBE);
    final List<RID> ivfRids = insertCorpus("IvfBench", 1, corpus);

    // Build: parallel (create), serial (rebuild), parallel (rebuild): A/B/A on the same records
    final long[] builds = new long[3];
    builds[0] = timed(() -> database.getSchema().buildTypeIndex("IvfBench", new String[] { "embedding" }).withIVFVectorType()
        .withDimensions(DIMS).withSimilarity("COSINE").withScanCode("BINARY").withRerankCode("INT8").withTargetPostingSize(200)
        .withTrainingSample(Math.min(ROWS, 200_000)).withKmeansIterations(8).create());
    log("build (parallel pass 2): %ss, %.0f vectors/s, index %s MB", seconds(builds[0]), ROWS / (builds[0] / 1e9), mb(indexBytes("IvfBench")));
    if (!Boolean.getBoolean("ivf.bench.skipBuildAB")) {
      try {
        LSMIVFVectorIndex.setParallelBuild(false);
        builds[1] = timed(() -> database.command("sql", "REBUILD INDEX `" + typeIndex("IvfBench").getName() + "`"));
        LSMIVFVectorIndex.setParallelBuild(true);
        builds[2] = timed(() -> database.command("sql", "REBUILD INDEX `" + typeIndex("IvfBench").getName() + "`"));
      } finally {
        LSMIVFVectorIndex.setParallelBuild(true);
      }
      report.append("| Build (").append(ROWS).append(" x ").append(DIMS).append(") | time (s) | vectors/s |\n|---|---|---|\n");
      final String[] labels = { "parallel pass 2 (create)", "serial pass 2 (rebuild)", "parallel pass 2 (rebuild)" };
      for (int i = 0; i < 3; i++)
        report.append("| ").append(labels[i]).append(" | ").append(seconds(builds[i])).append(" | ")
            .append(String.format("%.0f", ROWS / (builds[i] / 1e9))).append(" |\n");
      report.append('\n');
    }
    final long int8Bytes = indexBytes("IvfBench");

    final boolean filterOnly = Boolean.getBoolean("ivf.bench.filterOnly");
    final int[][] truth = filterOnly ? null : groundTruth(corpus, queries, null);
    final Set<RID> onePercent = pick(ivfRids, ROWS / 100, random);
    final Set<RID> twentyPercent = pick(ivfRids, ROWS / 5, random);
    final Set<RID> exactSized = pick(ivfRids, 4_000, random);
    final int[][] truthOne = groundTruth(corpus, queries, mask(onePercent, ivfRids));
    final int[][] truthTwenty = groundTruth(corpus, queries, mask(twentyPercent, ivfRids));
    final int[][] truthExact = filterOnly ? null : groundTruth(corpus, queries, mask(exactSized, ivfRids));
    final Map<Long, Integer> ordinal = ordinalOf(ivfRids);

    if (!filterOnly) {
    report.append("| Search A/B (alternating per query) | p50 ms | p95 ms | recall@10 |\n|---|---|---|---|\n");
    abRow("unfiltered, default budget", queries, truth, ordinal, null, -1, -1);
    abRow("unfiltered, rerank 10 x k (old default)", queries, truth, ordinal, null, 10, -1);
    abRow("unfiltered, default budget + exactRerank 20", queries, truth, ordinal, null, -1, 20);
    abRow("unfiltered, default budget + exactRerank 50", queries, truth, ordinal, null, -1, 50);
    abRow("filtered 4,000 records (exact path)", queries, truthExact, ordinal, exactSized, -1, -1);
    abRow("filtered 1% (" + ROWS / 100 + " records)", queries, truthOne, ordinal, onePercent, -1, -1);
    abRow("filtered 20%", queries, truthTwenty, ordinal, twentyPercent, -1, -1);
    report.append('\n');
    }
    report.append("| Allow-list probe A/B (parallel scan, alternating per query) | p50 ms | p95 ms | recall@10 |\n|---|---|---|---|\n");
    filterRow("filtered 1%", queries, truthOne, ordinal, onePercent);
    filterRow("filtered 20%", queries, truthTwenty, ordinal, twentyPercent);
    report.append('\n');

    // INT4 on the same corpus: a second type, built in parallel
    if (!Boolean.getBoolean("ivf.bench.skipInt4") && !filterOnly) {
      final List<RID> int4Rids = insertCorpus("IvfBench4", 1, corpus);
      final long int4Build = timed(() -> database.getSchema().buildTypeIndex("IvfBench4", new String[] { "embedding" }).withIVFVectorType()
          .withDimensions(DIMS).withSimilarity("COSINE").withScanCode("BINARY").withRerankCode("INT4").withTargetPostingSize(200)
          .withTrainingSample(Math.min(ROWS, 200_000)).withKmeansIterations(8).create());
      final LSMIVFVectorIndex int4 = (LSMIVFVectorIndex) typeIndex("IvfBench4").getIndexesOnBuckets()[0];
      final Map<Long, Integer> int4Ordinal = ordinalOf(int4Rids);
      report.append("| Rerank code (same corpus) | entry B | index MB | build s | p50 ms | recall@10 | + exactRerank 50: p50 ms | recall@10 |\n");
      report.append("|---|---|---|---|---|---|---|---|\n");
      final LSMIVFVectorIndex int8 = ivf();
      for (final LSMIVFVectorIndex index : new LSMIVFVectorIndex[] { int8, int4 }) {
        final Map<Long, Integer> ord = index == int8 ? ordinal : int4Ordinal;
        final double[] plain = runQueries(index, queries, truth, ord, null, -1, 0, true);
        final double[] tier = runQueries(index, queries, truth, ord, null, -1, 50, true);
        report.append("| ").append(index == int8 ? "INT8" : "INT4").append(" | ").append(index.getEntrySize()).append(" | ")
            .append(mb(indexBytes(index == int8 ? "IvfBench" : "IvfBench4"))).append(" | ")
            .append(index == int8 ? seconds(builds[0]) : seconds(int4Build)).append(" | ").append(String.format("%.2f", plain[0])).append(" | ")
            .append(String.format("%.3f", plain[2])).append(" | ").append(String.format("%.2f", tier[0])).append(" | ")
            .append(String.format("%.3f", tier[2])).append(" |\n");
      }
      log("int8 bytes=%s", mb(int8Bytes));
    }
    System.out.println("\n### LSM_IVF_VECTOR hardening A/B, rows=" + ROWS + "\n\n" + report);
  }

  /** One row: the same queries serial and parallel, interleaved query by query. */
  private void abRow(final String label, final float[][] queries, final int[][] truth, final Map<Long, Integer> ordinal,
      final Set<RID> allowed, final int oversample, final int exactRerank) {
    final LSMIVFVectorIndex index = ivf();
    final long[] serial = new long[queries.length];
    final long[] parallel = new long[queries.length];
    final int[] hits = new int[2];
    database.transaction(() -> {
      for (int q = 0; q < queries.length; q++) {
        for (int pass = 0; pass < 2; pass++) {
          final boolean parallelPass = (pass == 0) == ((q & 1) == 0);
          index.setParallelScan(parallelPass);
          final long t0 = System.nanoTime();
          final List<Pair<RID, Float>> found = index.findNeighbors(queries[q], K, NPROBE, oversample, exactRerank, allowed);
          final long elapsed = System.nanoTime() - t0;
          if (parallelPass) {
            parallel[q] = elapsed;
            hits[1] += hits(found, truth[q], ordinal);
          } else {
            serial[q] = elapsed;
            hits[0] += hits(found, truth[q], ordinal);
          }
        }
      }
    });
    index.setParallelScan(true);
    Arrays.sort(serial);
    Arrays.sort(parallel);
    report.append("| ").append(label).append(", serial | ").append(millis(percentile(serial, 50))).append(" | ")
        .append(millis(percentile(serial, 95))).append(" | ").append(String.format("%.3f", hits[0] / (double) (K * queries.length)))
        .append(" |\n");
    report.append("| ").append(label).append(", parallel | ").append(millis(percentile(parallel, 50))).append(" | ")
        .append(millis(percentile(parallel, 95))).append(" | ").append(String.format("%.3f", hits[1] / (double) (K * queries.length)))
        .append(" |\n");
    log("%s: serial p50=%s p95=%s | parallel p50=%s p95=%s | recall %.3f / %.3f", label, millis(percentile(serial, 50)),
        millis(percentile(serial, 95)), millis(percentile(parallel, 50)), millis(percentile(parallel, 95)),
        hits[0] / (double) (K * queries.length), hits[1] / (double) (K * queries.length));
  }

  /** One allow-list, probed through the sorted array and through the blocked bitmap, interleaved query by query. */
  private void filterRow(final String label, final float[][] queries, final int[][] truth, final Map<Long, Integer> ordinal,
      final Set<RID> allowed) {
    final LSMIVFVectorIndex index = ivf();
    final long[][] latency = new long[2][queries.length];
    final int[] hits = new int[2];
    try {
      database.transaction(() -> {
        for (int q = 0; q < queries.length; q++)
          for (int pass = 0; pass < 2; pass++) {
            final int mode = (pass == 0) == ((q & 1) == 0) ? 1 : 0;
            LSMIVFVectorIndex.setSortedFilter(mode == 0);
            final long t0 = System.nanoTime();
            final List<Pair<RID, Float>> found = index.findNeighbors(queries[q], K, NPROBE, -1, -1, allowed);
            latency[mode][q] = System.nanoTime() - t0;
            hits[mode] += hits(found, truth[q], ordinal);
          }
      });
    } finally {
      LSMIVFVectorIndex.setSortedFilter(false);
    }
    final String[] names = { "sorted array (spike)", "blocked bitmap" };
    for (int mode = 0; mode < 2; mode++) {
      Arrays.sort(latency[mode]);
      report.append("| ").append(label).append(", ").append(names[mode]).append(" | ").append(millis(percentile(latency[mode], 50)))
          .append(" | ").append(millis(percentile(latency[mode], 95))).append(" | ")
          .append(String.format("%.3f", hits[mode] / (double) (K * queries.length))).append(" |\n");
      log("%s %s: p50=%s p95=%s recall %.3f", label, names[mode], millis(percentile(latency[mode], 50)),
          millis(percentile(latency[mode], 95)), hits[mode] / (double) (K * queries.length));
    }
  }

  /** {p50 ms, p95 ms, recall} of {@code queries} on {@code index}. */
  private double[] runQueries(final LSMIVFVectorIndex index, final float[][] queries, final int[][] truth, final Map<Long, Integer> ordinal,
      final Set<RID> allowed, final int oversample, final int exactRerank, final boolean parallel) {
    final long[] latency = new long[queries.length];
    final int[] hits = new int[1];
    index.setParallelScan(parallel);
    database.transaction(() -> {
      for (int q = 0; q < queries.length; q++) {
        final long t0 = System.nanoTime();
        final List<Pair<RID, Float>> found = index.findNeighbors(queries[q], K, NPROBE, oversample, exactRerank, allowed);
        latency[q] = System.nanoTime() - t0;
        hits[0] += hits(found, truth[q], ordinal);
      }
    });
    index.setParallelScan(true);
    Arrays.sort(latency);
    return new double[] { percentile(latency, 50) / 1e6, percentile(latency, 95) / 1e6, hits[0] / (double) (K * queries.length) };
  }

  private static long timed(final Runnable work) {
    final long t0 = System.nanoTime();
    work.run();
    return System.nanoTime() - t0;
  }

  // ---------------------------------------------------------------- phases

  private List<RID> insertCorpus(final String typeName, final int buckets, final float[][] corpus) {
    final long start = System.nanoTime();
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(typeName, buckets);
      type.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    });
    final List<RID> rids = new ArrayList<>(ROWS);
    for (int from = 0; from < ROWS; from += BATCH) {
      final int start0 = from;
      database.transaction(() -> {
        for (int i = start0; i < Math.min(start0 + BATCH, ROWS); i++) {
          final MutableDocument doc = database.newDocument(typeName);
          doc.set("embedding", corpus[i]);
          doc.save();
          rids.add(doc.getIdentity());
        }
      });
    }
    log("inserted %d records into %s (%d buckets) in %ss", ROWS, typeName, buckets, seconds(System.nanoTime() - start));
    return rids;
  }

  private void measure(final String label, final float[][] queries, final int[][] truth, final Map<Long, Integer> graphOrdinal,
      final Map<Long, Integer> ivfOrdinal, final Set<RID> graphAllowed, final Set<RID> ivfAllowed, final List<RID> ivfRids,
      final List<RID> graphRids) {
    final long[] ivfLatency = new long[queries.length];
    final double[] ivfRecall = new double[1];
    database.transaction(() -> {
      final LSMIVFVectorIndex index = ivf();
      int hits = 0;
      for (int q = 0; q < queries.length; q++) {
        final long t0 = System.nanoTime();
        final List<Pair<RID, Float>> found = index.findNeighbors(queries[q], K, NPROBE, -1, ivfAllowed);
        ivfLatency[q] = System.nanoTime() - t0;
        hits += hits(found, truth[q], ivfOrdinal);
      }
      ivfRecall[0] = hits / (double) (K * queries.length);
    });

    final long[] graphLatency = new long[queries.length];
    final double[] graphRecall = new double[] { -1 };
    if (!SKIP_GRAPH)
      database.transaction(() -> {
        final List<LSMVectorIndex> indexes = graphs();
        int hits = 0;
        for (int q = 0; q < queries.length; q++) {
          final long t0 = System.nanoTime();
          final List<Pair<RID, Float>> all = new ArrayList<>();
          for (final LSMVectorIndex g : indexes)
            all.addAll(g.findNeighborsFromVector(queries[q], K, -1, graphAllowed));
          all.sort((a, b) -> Float.compare(a.getSecond(), b.getSecond()));
          final List<Pair<RID, Float>> found = all.subList(0, Math.min(K, all.size()));
          graphLatency[q] = System.nanoTime() - t0;
          hits += hits(found, truth[q], graphOrdinal);
        }
        graphRecall[0] = hits / (double) (K * queries.length);
      });

    Arrays.sort(ivfLatency);
    Arrays.sort(graphLatency);
    row(label + " p50 latency (ms)", SKIP_GRAPH ? "-" : millis(percentile(graphLatency, 50)), millis(percentile(ivfLatency, 50)));
    row(label + " p95 latency (ms)", SKIP_GRAPH ? "-" : millis(percentile(graphLatency, 95)), millis(percentile(ivfLatency, 95)));
    row(label + " recall@10", SKIP_GRAPH ? "-" : String.format("%.3f", graphRecall[0]), String.format("%.3f", ivfRecall[0]));
    log("%s: ivf p50=%s p95=%s recall=%.3f | graph p50=%s p95=%s recall=%.3f", label, millis(percentile(ivfLatency, 50)),
        millis(percentile(ivfLatency, 95)), ivfRecall[0], millis(percentile(graphLatency, 50)), millis(percentile(graphLatency, 95)),
        graphRecall[0]);
  }

  /** Recall and p50 over nprobe x rerankOversample, IVF only: where the recall is lost, the partition or the one-bit scan. */
  private void sweep(final float[][] queries, final int[][] truth, final Map<Long, Integer> ivfOrdinal) {
    database.transaction(() -> {
      final LSMIVFVectorIndex index = ivf();
      for (final int oversample : new int[] { 10, 50, 200 })
        for (final int nprobe : new int[] { 32, 64, 128, 256 }) {
          final long[] latency = new long[queries.length];
          int hits = 0;
          for (int q = 0; q < queries.length; q++) {
            final long t0 = System.nanoTime();
            final List<Pair<RID, Float>> found = index.findNeighbors(queries[q], K, nprobe, oversample, null);
            latency[q] = System.nanoTime() - t0;
            hits += hits(found, truth[q], ivfOrdinal);
          }
          Arrays.sort(latency);
          log("sweep nprobe=%d oversample=%d: recall=%.3f p50=%s p95=%s", nprobe, oversample, hits / (double) (K * queries.length),
              millis(percentile(latency, 50)), millis(percentile(latency, 95)));
        }
    });
  }

  // ---------------------------------------------------------------- ground truth

  /** The brute-force top-K ordinals of every query under cosine, restricted to {@code mask} when given; fanned out over threads. */
  private static int[][] groundTruth(final float[][] corpus, final float[][] queries, final boolean[] mask) throws InterruptedException {
    final int[][] truth = new int[queries.length][];
    final int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
    final Thread[] workers = new Thread[threads];
    final AtomicLong next = new AtomicLong();
    final float[] norms = new float[ROWS];
    for (int i = 0; i < ROWS; i++)
      norms[i] = (float) Math.sqrt(dot(corpus[i], corpus[i]));
    for (int t = 0; t < threads; t++) {
      workers[t] = new Thread(() -> {
        final float[] best = new float[K];
        final int[] bestIdx = new int[K];
        for (int q; (q = (int) next.getAndIncrement()) < queries.length; ) {
          final float[] query = queries[q];
          final float queryNorm = (float) Math.sqrt(dot(query, query));
          Arrays.fill(best, Float.MAX_VALUE);
          for (int i = 0; i < ROWS; i++) {
            if (mask != null && !mask[i])
              continue;
            final float distance = 1f - dot(query, corpus[i]) / (queryNorm * norms[i]);
            if (distance < best[K - 1]) {
              int pos = K - 1;
              while (pos > 0 && best[pos - 1] > distance) {
                best[pos] = best[pos - 1];
                bestIdx[pos] = bestIdx[pos - 1];
                pos--;
              }
              best[pos] = distance;
              bestIdx[pos] = i;
            }
          }
          truth[q] = bestIdx.clone();
        }
      });
      workers[t].start();
    }
    for (final Thread w : workers)
      w.join();
    return truth;
  }

  private static float dot(final float[] a, final float[] b) {
    float sum = 0f;
    for (int d = 0; d < DIMS; d++)
      sum += a[d] * b[d];
    return sum;
  }

  private static int hits(final List<Pair<RID, Float>> found, final int[] truth, final Map<Long, Integer> ordinal) {
    int hits = 0;
    for (final Pair<RID, Float> f : found) {
      final int o = ordinalOfRid(f.getFirst(), ordinal);
      for (final int t : truth)
        if (t == o) {
          hits++;
          break;
        }
    }
    return hits;
  }

  /** Records were inserted in corpus order, so the ordinal of a RID is its index in the insertion list: a map by bucket and position. */
  private static Map<Long, Integer> ordinalOf(final List<RID> rids) {
    final Map<Long, Integer> byKey = new HashMap<>(rids.size() * 2);
    for (int i = 0; i < rids.size(); i++)
      byKey.put(key(rids.get(i)), i);
    return byKey;
  }

  private static int ordinalOfRid(final RID rid, final Map<Long, Integer> ordinal) {
    final Integer o = ordinal.get(key(rid));
    return o == null ? -1 : o;
  }

  private static long key(final RID rid) {
    return ((long) rid.getBucketId() << 48) | rid.getPosition();
  }

  private static Set<RID> pick(final List<RID> rids, final int count, final Random random) {
    final Set<RID> picked = new HashSet<>(count * 2);
    while (picked.size() < count)
      picked.add(rids.get(random.nextInt(rids.size())));
    return picked;
  }

  private static boolean[] mask(final Set<RID> picked, final List<RID> rids) {
    final boolean[] mask = new boolean[rids.size()];
    for (int i = 0; i < rids.size(); i++)
      mask[i] = picked.contains(rids.get(i));
    return mask;
  }

  private static Set<RID> translate(final Set<RID> picked, final List<RID> from, final List<RID> to) {
    final Set<RID> translated = new HashSet<>(picked.size() * 2);
    for (int i = 0; i < from.size(); i++)
      if (picked.contains(from.get(i)))
        translated.add(to.get(i));
    return translated;
  }

  // ---------------------------------------------------------------- plumbing

  private TypeIndex typeIndex(final String typeName) {
    return database.getSchema().getType(typeName).getPolymorphicIndexByProperties("embedding");
  }

  private LSMIVFVectorIndex ivf() {
    return (LSMIVFVectorIndex) typeIndex("IvfBench").getIndexesOnBuckets()[0];
  }

  private List<LSMVectorIndex> graphs() {
    final List<LSMVectorIndex> list = new ArrayList<>();
    for (final Index idx : typeIndex("GraphBench").getIndexesOnBuckets())
      list.add((LSMVectorIndex) idx);
    return list;
  }

  private long indexBytes(final String typeName) {
    if (!database.getSchema().existsType(typeName))
      return -1;
    long bytes = 0;
    final Set<String> prefixes = new HashSet<>();
    for (final Index idx : typeIndex(typeName).getIndexesOnBuckets())
      prefixes.add(idx.getName());
    for (final ComponentFile file : ((DatabaseInternal) database).getFileManager().getFiles()) {
      if (file == null)
        continue;
      final String component = file.getComponentName();
      for (final String prefix : prefixes)
        if (component.startsWith(prefix)) {
          bytes += file.getOSFile().length();
          break;
        }
    }
    return bytes;
  }

  private static long usedHeapAfterGc() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  /** Runs {@code work}, answering {elapsed nanos, peak used heap above the pre-work baseline}. */
  private static long[] timedWithPeakHeap(final HeapSampler sampler, final Runnable work) throws InterruptedException {
    final long baseline = usedHeapAfterGc();
    sampler.start();
    final long t0 = System.nanoTime();
    work.run();
    final long elapsed = System.nanoTime() - t0;
    final long peak = sampler.stop();
    return new long[] { elapsed, Math.max(0, peak - baseline) };
  }

  /** Samples the used heap every 20 ms while a phase runs; the peak is an upper bound of the live set, garbage included. */
  private static final class HeapSampler {
    private volatile boolean running;
    private volatile long    peak;
    private Thread           thread;

    void start() {
      peak = 0;
      running = true;
      thread = new Thread(() -> {
        while (running) {
          final long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
          if (used > peak)
            peak = used;
          try {
            Thread.sleep(20);
          } catch (final InterruptedException e) {
            return;
          }
        }
      }, "heap-sampler");
      thread.setDaemon(true);
      thread.start();
    }

    long stop() throws InterruptedException {
      running = false;
      thread.join();
      return peak;
    }
  }

  private static long percentile(final long[] sorted, final int p) {
    if (sorted.length == 0)
      return 0;
    return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
  }

  private static String millis(final long nanos) {
    return String.format("%.2f", nanos / 1_000_000.0);
  }

  private static String seconds(final long nanos) {
    return nanos < 0 ? "-" : String.format("%.1f", nanos / 1_000_000_000.0);
  }

  private static String mb(final long bytes) {
    return bytes < 0 ? "-" : String.format("%.0f", bytes / 1048576.0);
  }

  private void row(final String metric, final String graph, final String ivf) {
    report.append("| ").append(metric).append(" | ").append(graph).append(" | ").append(ivf).append(" |\n");
  }

  private static void log(final String format, final Object... args) {
    System.out.printf("[ivf-bench] " + format + "%n", args);
  }
}
