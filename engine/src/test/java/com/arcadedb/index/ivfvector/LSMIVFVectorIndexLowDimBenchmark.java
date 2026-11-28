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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.vector.LSMVectorIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import com.arcadedb.utility.FileUtils;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * {@code LSM_VECTOR} against {@code LSM_IVF_VECTOR} on the Recon style vectors: 24-d unit vectors around 200 unit centroids drawn
 * with a Zipf 0.8 weight, Gaussian noise 0.35 per coordinate, normalized (the recipe of {@code ReconDomain.styleVector}, reproduced
 * here because the engine does not depend on the benchmarks module). Queries are fresh vectors of the same recipe, k = 20, recall
 * against an exact scan.
 * <p>
 * The machine this runs on is shared, so the cost of a query is measured in CPU time and allocated bytes, not wall clock: the
 * calling thread's CPU and allocation (exact for the serial scan and for {@code LSM_VECTOR}, which searches on the caller), and the
 * process CPU and the JVM-wide allocation around the call (what a parallel scan costs on the pool too). The two index kinds alternate
 * query by query, the order flipping each query, so a load spike falls on both.
 * <p>
 * Uses only API that the index had before the low-dimension defaults, so the same class measures the base and the change when run
 * against either build of the engine classes ({@link #main}). Properties: {@code ivf.lowdim.rows} (1M), {@code ivf.lowdim.queries}
 * (100), {@code ivf.lowdim.rounds} (3), {@code ivf.lowdim.configs} ({@code |}-separated METADATA fragments added to dimensions and
 * similarity, empty for the defaults), {@code ivf.lowdim.sweep} (comma-separated query nprobe values), {@code ivf.lowdim.sql}
 * (true: also the SQL {@code vector.neighbors} path), {@code ivf.lowdim.graph} (false skips {@code LSM_VECTOR}),
 * {@code ivf.lowdim.filters} (true: also allow-lists of 1% and 20%).
 */
@Tag("benchmark")
class LSMIVFVectorIndexLowDimBenchmark {
  static final int    DIMS     = 24;
  static final int    CLUSTERS = 200;
  static final double ZIPF     = 0.8;
  static final double NOISE    = 0.35;
  static final int    K        = 20;

  private static final com.sun.management.ThreadMXBean          THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
  private static final com.sun.management.OperatingSystemMXBean OS      =
      (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

  @Test
  void styleVectors() {
    run(new File("target/databases/ivf-lowdim-bench"));
  }

  /** Entry point for a run outside surefire, against whichever engine classes are on the class path (base or change). */
  public static void main(final String[] args) {
    run(new File(args.length > 0 ? args[0] : "target/databases/ivf-lowdim-bench"));
  }

  static void run(final File directory) {
    final int rows = Integer.getInteger("ivf.lowdim.rows", 1_000_000);
    final int queryCount = Integer.getInteger("ivf.lowdim.queries", 100);
    final int rounds = Integer.getInteger("ivf.lowdim.rounds", 3);
    final String[] configs = System.getProperty("ivf.lowdim.configs", "").split("\\|", -1);
    final String sweepProperty = System.getProperty("ivf.lowdim.sweep", "");
    final int[] sweep = sweepProperty.isBlank() ? new int[0] : Arrays.stream(sweepProperty.split(",")).mapToInt(Integer::parseInt).toArray();
    final boolean sql = Boolean.parseBoolean(System.getProperty("ivf.lowdim.sql", "true"));
    final boolean withGraph = Boolean.parseBoolean(System.getProperty("ivf.lowdim.graph", "true"));
    final boolean filters = Boolean.parseBoolean(System.getProperty("ivf.lowdim.filters", "true"));

    final float[][] corpus = new float[rows][];
    final float[][] queries = new float[queryCount][];
    generate(corpus, queries);
    final int[][] truth = groundTruth(corpus, queries, null);
    log("rows=%d queries=%d k=%d rounds=%d, ground truth done", rows, queryCount, K, rounds);

    FileUtils.deleteRecursively(directory);
    try (final DatabaseFactory factory = new DatabaseFactory(directory.getPath())) {
      final Database database = factory.create();
      try {
        final long[] graphPositions = withGraph ? insert(database, "G", corpus) : null;
        final long[] ivfPositions = insert(database, "V", corpus);
        final Map<Long, Integer> graphOrdinal = withGraph ? ordinalOf(graphPositions) : null;
        final Map<Long, Integer> ivfOrdinal = ordinalOf(ivfPositions);

        LSMVectorIndex graph = null;
        if (withGraph) {
          final long start = System.nanoTime();
          database.command("sql", "CREATE INDEX ON G (v) LSM_VECTOR METADATA {\"dimensions\": " + DIMS + ", \"similarity\": \"COSINE\"}");
          graph = (LSMVectorIndex) ((TypeIndex) database.getSchema().getIndexByName("G[v]")).getIndexesOnBuckets()[0];
          graph.buildVectorGraphNow();
          log("LSM_VECTOR built in %.1fs", (System.nanoTime() - start) / 1e9);
        }

        // Allow-lists: the same ordinals in both types
        final SplittableRandom pick = new SplittableRandom(5);
        final int[] onePercent = pick.ints(0, rows).distinct().limit(rows / 100).toArray();
        final int[] twentyPercent = pick.ints(0, rows).distinct().limit(rows / 5).toArray();

        for (final String config : configs) {
          final String metadata = "{\"dimensions\": " + DIMS + ", \"similarity\": \"COSINE\"" + (config.isBlank() ? "" : ", " + config) + "}";
          final long start = System.nanoTime();
          final long cpuStart = OS.getProcessCpuTime();
          database.command("sql", "CREATE INDEX ON V (v) LSM_IVF_VECTOR METADATA " + metadata);
          final double buildSeconds = (System.nanoTime() - start) / 1e9;
          final double buildCpu = (OS.getProcessCpuTime() - cpuStart) / 1e9;
          final LSMIVFVectorIndex ivf = (LSMIVFVectorIndex) ((TypeIndex) database.getSchema().getIndexByName("V[v]")).getIndexesOnBuckets()[0];
          final Map<String, Long> stats = ivf.getStats();
          log("\n### IVF %s: built in %.1fs (process CPU %.1fs), lists=%d, entry=%d B, postingPages=%d", metadata, buildSeconds, buildCpu,
              stats.get("centroids"), ivf.getEntrySize(), stats.get("postingPages"));

          final LSMVectorIndex g = graph;
          for (final boolean parallel : new boolean[] { false, true }) {
            ivf.setParallelScan(parallel);
            final String mode = parallel ? "parallel" : "serial";
            measure("unfiltered, default nprobe, " + mode, queries, truth, rounds, graphOrdinal, ivfOrdinal,
                g == null ? null : q -> g.findNeighborsFromVector(q, K), q -> ivf.findNeighbors(q, K, -1, -1, null));
            for (final int nprobe : sweep)
              measure("unfiltered, nprobe " + nprobe + ", " + mode, queries, truth, rounds, null, ivfOrdinal, null,
                  q -> ivf.findNeighbors(q, K, nprobe, -1, null));
          }
          ivf.setParallelScan(true);
          if (sql)
            measure("SQL vector.neighbors", queries, truth, rounds, graphOrdinal, ivfOrdinal,
                g == null ? null : q -> sqlNeighbors(database, "G[v]", q), q -> sqlNeighbors(database, "V[v]", q));
          if (filters)
            for (final int[] allowed : new int[][] { onePercent, twentyPercent }) {
              final int[][] filteredTruth = groundTruth(corpus, queries, allowed);
              final Set<RID> graphAllowed = g == null ? null : ridSet(database, "G", graphPositions, allowed);
              final Set<RID> ivfAllowed = ridSet(database, "V", ivfPositions, allowed);
              measure("filtered " + (allowed.length * 100 / rows) + "%, default nprobe, parallel", queries, filteredTruth, rounds,
                  graphOrdinal, ivfOrdinal, g == null ? null : q -> g.findNeighborsFromVector(q, K, graphAllowed),
                  q -> ivf.findNeighbors(q, K, -1, -1, ivfAllowed));
            }
          database.command("sql", "DROP INDEX `V[v]`");
        }
      } finally {
        database.drop();
      }
    }
  }

  /** The style vectors: {@code corpus.length} stored, {@code queries.length} fresh queries, both of the same recipe. */
  static void generate(final float[][] corpus, final float[][] queries) {
    final SplittableRandom random = new SplittableRandom(20261104L);
    final float[][] centroids = new float[CLUSTERS][];
    for (int c = 0; c < CLUSTERS; c++)
      centroids[c] = styleVector(null, random);
    final double[] cumulative = new double[CLUSTERS];
    double sum = 0;
    for (int r = 0; r < CLUSTERS; r++) {
      sum += 1.0 / Math.pow(r + 1, ZIPF);
      cumulative[r] = sum;
    }
    for (int r = 0; r < CLUSTERS; r++)
      cumulative[r] /= sum;
    for (int i = 0; i < corpus.length; i++)
      corpus[i] = styleVector(centroids[zipf(cumulative, random.nextDouble())], random);
    for (int i = 0; i < queries.length; i++)
      queries[i] = styleVector(centroids[zipf(cumulative, random.nextDouble())], random);
  }

  private static int zipf(final double[] cumulative, final double u) {
    int lo = 0, hi = cumulative.length - 1;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (cumulative[mid] >= u)
        hi = mid;
      else
        lo = mid + 1;
    }
    return lo;
  }

  /** A unit vector around {@code centroid} with noise {@link #NOISE} per coordinate, or a random unit vector without a centroid. */
  private static float[] styleVector(final float[] centroid, final SplittableRandom random) {
    final float[] v = new float[DIMS];
    double norm = 0;
    for (int d = 0; d < DIMS; d++) {
      v[d] = centroid == null ? (float) random.nextGaussian() : centroid[d] + (float) (random.nextGaussian() * NOISE);
      norm += v[d] * v[d];
    }
    final float inverse = (float) (1.0 / Math.sqrt(norm));
    for (int d = 0; d < DIMS; d++)
      v[d] *= inverse;
    return v;
  }

  /** The exact {@link #K} nearest ordinals by cosine, over all rows or over {@code allowed} only. */
  static int[][] groundTruth(final float[][] corpus, final float[][] queries, final int[] allowed) {
    final int[][] truth = new int[queries.length][];
    IntStream.range(0, queries.length).parallel().forEach(q -> {
      final float[] query = queries[q];
      final float[] best = new float[K];
      final int[] ids = new int[K];
      Arrays.fill(best, -Float.MAX_VALUE);
      final int n = allowed == null ? corpus.length : allowed.length;
      for (int j = 0; j < n; j++) {
        final int i = allowed == null ? j : allowed[j];
        final float[] row = corpus[i];
        float dot = 0;
        for (int d = 0; d < DIMS; d++)
          dot += query[d] * row[d];
        if (dot > best[K - 1]) {
          int at = K - 1;
          while (at > 0 && best[at - 1] < dot) {
            best[at] = best[at - 1];
            ids[at] = ids[at - 1];
            at--;
          }
          best[at] = dot;
          ids[at] = i;
        }
      }
      truth[q] = ids;
    });
    return truth;
  }

  private static long[] insert(final Database database, final String typeName, final float[][] corpus) {
    final long start = System.nanoTime();
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(typeName, 1);
      type.createProperty("v", Type.ARRAY_OF_FLOATS);
    });
    final long[] positions = new long[corpus.length];
    final int batch = 5_000;
    for (int from = 0; from < corpus.length; from += batch) {
      final int first = from;
      database.transaction(() -> {
        for (int i = first; i < Math.min(first + batch, corpus.length); i++) {
          final MutableDocument doc = database.newDocument(typeName);
          doc.set("v", corpus[i]);
          doc.save();
          positions[i] = doc.getIdentity().getPosition();
        }
      });
    }
    log("inserted %d rows into %s in %.1fs", corpus.length, typeName, (System.nanoTime() - start) / 1e9);
    return positions;
  }

  private static Map<Long, Integer> ordinalOf(final long[] positions) {
    final Map<Long, Integer> map = new HashMap<>(positions.length * 2);
    for (int i = 0; i < positions.length; i++)
      map.put(positions[i], i);
    return map;
  }

  private static Set<RID> ridSet(final Database database, final String typeName, final long[] positions, final int[] ordinals) {
    final int bucketId = database.getSchema().getType(typeName).getBuckets(false).getFirst().getFileId();
    final Set<RID> set = new HashSet<>(ordinals.length * 2);
    for (final int ordinal : ordinals)
      set.add(new RID(bucketId, positions[ordinal]));
    return set;
  }

  private static List<Pair<RID, Float>> sqlNeighbors(final Database database, final String index, final float[] query) {
    final List<Pair<RID, Float>> found = new ArrayList<>(K);
    try (final ResultSet rs = database.query("sql", "SELECT expand(`vector.neighbors`(?, ?, ?))", index, query, K)) {
      while (rs.hasNext())
        rs.next().getIdentity().ifPresent(rid -> found.add(new Pair<>(rid, 0f)));
    }
    return found;
  }

  private interface Search {
    List<Pair<RID, Float>> run(float[] query);
  }

  /** Per-call costs of one index over every query and round. */
  private static final class Costs {
    final long[] threadCpu;
    final long[] threadAlloc;
    final long[] processCpu;
    final long[] totalAlloc;
    long         hits;
    int          calls;

    Costs(final int n) {
      threadCpu = new long[n];
      threadAlloc = new long[n];
      processCpu = new long[n];
      totalAlloc = new long[n];
    }

    void record(final Search search, final float[] query, final int[] truth, final Map<Long, Integer> ordinal) {
      final long allocBefore = THREADS.getCurrentThreadAllocatedBytes();
      final long totalBefore = THREADS.getTotalThreadAllocatedBytes();
      final long processBefore = OS.getProcessCpuTime();
      final long cpuBefore = THREADS.getCurrentThreadCpuTime();
      final List<Pair<RID, Float>> found = search.run(query);
      final long cpuAfter = THREADS.getCurrentThreadCpuTime();
      final long processAfter = OS.getProcessCpuTime();
      final long totalAfter = THREADS.getTotalThreadAllocatedBytes();
      final long allocAfter = THREADS.getCurrentThreadAllocatedBytes();
      threadCpu[calls] = cpuAfter - cpuBefore;
      threadAlloc[calls] = allocAfter - allocBefore;
      processCpu[calls] = processAfter - processBefore;
      totalAlloc[calls] = totalAfter - totalBefore;
      calls++;
      if (truth != null) {
        final Set<Integer> expected = new HashSet<>();
        for (final int t : truth)
          expected.add(t);
        for (final Pair<RID, Float> pair : found) {
          final Integer o = ordinal.get(pair.getFirst().getPosition());
          if (o != null && expected.contains(o))
            hits++;
        }
      }
    }

    String summary(final int queries) {
      return String.format("recall@%d %.3f | thread CPU p50 %s mean %s | process CPU p50 %s | thread alloc p50 %s | JVM alloc p50 %s", K,
          hits / (double) (queries * K), micros(median(threadCpu)), micros(mean(threadCpu)), micros(median(processCpu)),
          kb(median(threadAlloc)), kb(median(totalAlloc)));
    }
  }

  private static void measure(final String label, final float[][] queries, final int[][] truth, final int rounds,
      final Map<Long, Integer> graphOrdinal, final Map<Long, Integer> ivfOrdinal, final Search graph, final Search ivf) {
    // Warm-up pass, not recorded
    for (final float[] query : queries) {
      ivf.run(query);
      if (graph != null)
        graph.run(query);
    }
    final Costs graphCosts = new Costs(queries.length * rounds);
    final Costs ivfCosts = new Costs(queries.length * rounds);
    for (int round = 0; round < rounds; round++)
      for (int q = 0; q < queries.length; q++) {
        final boolean graphFirst = ((q + round) & 1) == 0;
        if (graph != null && graphFirst)
          graphCosts.record(graph, queries[q], round == 0 ? truth[q] : null, graphOrdinal);
        ivfCosts.record(ivf, queries[q], round == 0 ? truth[q] : null, ivfOrdinal);
        if (graph != null && !graphFirst)
          graphCosts.record(graph, queries[q], round == 0 ? truth[q] : null, graphOrdinal);
      }
    if (graph != null)
      log("%-42s LSM_VECTOR | %s", label, graphCosts.summary(queries.length));
    log("%-42s IVF        | %s", label, ivfCosts.summary(queries.length));
  }

  private static long median(final long[] values) {
    final long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static long mean(final long[] values) {
    long sum = 0;
    for (final long v : values)
      sum += v;
    return sum / values.length;
  }

  private static String micros(final long nanos) {
    return String.format("%.0f us", nanos / 1e3);
  }

  private static String kb(final long bytes) {
    return String.format("%.1f KB", bytes / 1024.0);
  }

  private static void log(final String format, final Object... args) {
    System.out.println(String.format(format, args));
  }
}
