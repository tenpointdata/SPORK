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
package com.arcadedb.index.vector;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serial against parallel per-bucket search ({@link VectorSearchPool}) on synthetic 512-d face-embedding-like vectors spread
 * over 16 buckets: p50/p95 latency and recall@10 of {@code vector.neighbors} for one client, and throughput for several.
 * <p>
 * Run by hand, it logs one line per arm:
 * <pre>
 * mvn -pl engine test -Dtest=VectorParallelBucketSearchBenchmark -Dgroups=benchmark \
 *     -Dbench.rows=160000 -Dbench.buckets=16 -Dbench.quantizations=NONE,BINARY -Dbench.clients=1,4,8,16
 * </pre>
 * The data is clustered (rows drawn around a few hundred centroids, as embeddings of the same identity are), and the
 * similarity is COSINE, as ArcFace's is. The two arms alternate per round so drift in clock, caches and heap lands on both.
 * The only assertion is that both arms return the same rows: the latencies are a measurement, not a bound.
 */
@Tag("benchmark")
class VectorParallelBucketSearchBenchmark extends TestHelper {
  private static final int    DIMENSIONS = Integer.getInteger("bench.dimensions", 512);
  private static final int    ROWS       = Integer.getInteger("bench.rows", 160_000);
  private static final int    BUCKETS    = Integer.getInteger("bench.buckets", 16);
  private static final int    QUERIES    = Integer.getInteger("bench.queries", 200);
  private static final int    ROUNDS     = Integer.getInteger("bench.rounds", 4);
  private static final int    K          = 10;
  private static final float  NOISE      = 0.35f;
  private static final String QUANTS     = System.getProperty("bench.quantizations", "NONE,BINARY");
  private static final String CLIENTS    = System.getProperty("bench.clients", "1,4,8,16");
  // Each client runs the query set this many times per throughput measurement
  private static final int    THROUGHPUT_PASSES = Integer.getInteger("bench.throughputPasses", 5);

  @Test
  void serialAgainstParallelBucketSearch() throws Exception {
    final Random random = new Random(512);
    final float[][] centroids = new float[Math.max(1, ROWS / 400)][DIMENSIONS];
    for (final float[] c : centroids)
      for (int d = 0; d < DIMENSIONS; d++)
        c[d] = (float) random.nextGaussian();
    final float[][] rows = new float[ROWS][];
    for (int i = 0; i < ROWS; i++)
      rows[i] = around(centroids[random.nextInt(centroids.length)], random);
    final float[][] queries = new float[QUERIES][];
    for (int i = 0; i < QUERIES; i++)
      queries[i] = around(centroids[random.nextInt(centroids.length)], random);

    final int[][] truth = new int[QUERIES][];
    for (int q = 0; q < QUERIES; q++)
      truth[q] = exactTopK(rows, queries[q]);

    for (final String quantization : QUANTS.split(",")) {
      final String type = "V" + quantization;
      database.command("sql", "CREATE DOCUMENT TYPE " + type + " BUCKETS " + BUCKETS);
      database.command("sql", "CREATE PROPERTY " + type + ".vector ARRAY_OF_FLOATS");
      database.command("sql", "CREATE INDEX ON " + type + " (vector) LSM_VECTOR METADATA { \"dimensions\": " + DIMENSIONS
          + ", \"similarity\": \"COSINE\", \"quantization\": \"" + quantization + "\" }");

      final Map<RID, Integer> rowOf = new HashMap<>(ROWS * 2);
      for (int start = 0; start < ROWS; start += 2_000) {
        final int from = start;
        database.transaction(() -> {
          for (int i = from; i < Math.min(ROWS, from + 2_000); i++)
            rowOf.put(database.newDocument(type).set("vector", rows[i]).save().getIdentity(), i);
        });
      }

      // A search builds the graph of a bucket whose vectors are still in its delta buffer; the timed searches below must walk
      // graphs, not scan buffers, which the node and buffer counts logged here show
      final long buildStart = System.nanoTime();
      long graphNodes = 0;
      long deltaVectors = 0;
      for (final IndexInternal sub : ((TypeIndex) database.getSchema().getIndexByName(type + "[vector]")).getIndexesOnBuckets()) {
        final LSMVectorIndex index = (LSMVectorIndex) sub;
        index.findNeighborsFromVector(queries[0], K, 50);
        graphNodes += (Long) index.getStats().get("graphNodeCount");
        deltaVectors += (Long) index.getStats().get("deltaVectorsCount");
      }
      LogManager.instance().log(this, Level.SEVERE,
          "bench %s: %d rows x %d-d in %d buckets, graphs built in %.1f s (graph nodes=%d, delta-buffer vectors=%d)", quantization,
          ROWS, DIMENSIONS, BUCKETS, (System.nanoTime() - buildStart) / 1e9, graphNodes, deltaVectors);
      assertThat(graphNodes).as("every vector must be in a graph before timing").isEqualTo(ROWS);

      // Warm-up: both paths, JIT and caches
      for (final boolean parallel : new boolean[] { false, true }) {
        setParallel(parallel);
        for (final float[] q : queries)
          search(type, q);
      }

      final long[][] latencies = new long[2][ROUNDS * QUERIES];
      final int[] hits = new int[2];
      for (int round = 0; round < ROUNDS; round++)
        for (int turn = 0; turn < 2; turn++) {
          final int arm = round % 2 == 0 ? turn : 1 - turn;
          setParallel(arm == 1);
          for (int q = 0; q < QUERIES; q++) {
            final long start = System.nanoTime();
            final List<RID> found = search(type, queries[q]);
            latencies[arm][round * QUERIES + q] = System.nanoTime() - start;
            if (round == 0)
              hits[arm] += recallHits(found, truth[q], rowOf);
          }
        }

      assertThat(hits[1]).as("the parallel search must find what the serial one finds").isEqualTo(hits[0]);
      for (int arm = 0; arm < 2; arm++) {
        Arrays.sort(latencies[arm]);
        LogManager.instance().log(this, Level.SEVERE,
            "bench %s %-8s 1 client: p50=%.2f ms p95=%.2f ms recall@10=%.3f", quantization, arm == 0 ? "serial" : "parallel",
            latencies[arm][latencies[arm].length / 2] / 1e6, latencies[arm][(int) (latencies[arm].length * 0.95)] / 1e6,
            hits[arm] / (double) (QUERIES * K));
      }

      for (final String clientSpec : CLIENTS.split(",")) {
        final int clients = Integer.parseInt(clientSpec.trim());
        if (clients <= 1)
          continue;
        // Alternated per pass, as the single-client arms are
        final long[][] concurrent = new long[2][];
        final double[] qps = new double[2];
        for (int pass = 0; pass < 2; pass++)
          for (int turn = 0; turn < 2; turn++) {
            final int arm = pass % 2 == 0 ? turn : 1 - turn;
            setParallel(arm == 1);
            final long[] lat = new long[clients * THROUGHPUT_PASSES * QUERIES];
            qps[arm] += throughput(type, queries, clients, lat) / 2;
            concurrent[arm] = concurrent[arm] == null ? lat : concat(concurrent[arm], lat);
          }
        for (int arm = 0; arm < 2; arm++) {
          Arrays.sort(concurrent[arm]);
          LogManager.instance().log(this, Level.SEVERE, "bench %s %-8s %d clients: %.0f queries/s p50=%.2f ms p95=%.2f ms",
              quantization, arm == 0 ? "serial" : "parallel", clients, qps[arm], concurrent[arm][concurrent[arm].length / 2] / 1e6,
              concurrent[arm][(int) (concurrent[arm].length * 0.95)] / 1e6);
        }
      }
    }
  }

  private double throughput(final String type, final float[][] queries, final int clients, final long[] latencies)
      throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(clients);
    try {
      final List<Future<?>> futures = new ArrayList<>();
      final long start = System.nanoTime();
      for (int c = 0; c < clients; c++) {
        final int offset = c;
        futures.add(executor.submit(() -> {
          final int perClient = THROUGHPUT_PASSES * queries.length;
          for (int i = 0; i < perClient; i++) {
            final long t = System.nanoTime();
            search(type, queries[(i + offset * 7) % queries.length]);
            latencies[offset * perClient + i] = System.nanoTime() - t;
          }
        }));
      }
      for (final Future<?> f : futures)
        f.get();
      return clients * (double) THROUGHPUT_PASSES * queries.length / ((System.nanoTime() - start) / 1e9);
    } finally {
      executor.shutdownNow();
    }
  }

  private static long[] concat(final long[] a, final long[] b) {
    final long[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private void setParallel(final boolean parallel) {
    database.getConfiguration().setValue(GlobalConfiguration.VECTOR_INDEX_PARALLEL_SEARCH, parallel);
  }

  private List<RID> search(final String type, final float[] query) {
    final List<RID> out = new ArrayList<>(K);
    try (final ResultSet rs = database.query("sql", "SELECT expand(vector.neighbors('" + type + "[vector]', ?, ?))", query, K)) {
      while (rs.hasNext())
        out.add(rs.next().getIdentity().orElseThrow());
    }
    return out;
  }

  private static int recallHits(final List<RID> found, final int[] truth, final Map<RID, Integer> rowOf) {
    final Set<Integer> expected = new HashSet<>();
    for (final int t : truth)
      expected.add(t);
    int hits = 0;
    for (final RID rid : found)
      if (expected.contains(rowOf.get(rid)))
        hits++;
    return hits;
  }

  private static float[] around(final float[] centroid, final Random random) {
    final float[] v = new float[DIMENSIONS];
    for (int d = 0; d < DIMENSIONS; d++)
      v[d] = centroid[d] + (float) random.nextGaussian() * NOISE;
    return v;
  }

  private static int[] exactTopK(final float[][] rows, final float[] query) {
    final int[] best = new int[K];
    final double[] bestScore = new double[K];
    Arrays.fill(bestScore, -2);
    final double queryNorm = Math.sqrt(dot(query, query));
    for (int i = 0; i < rows.length; i++) {
      final double score = dot(rows[i], query) / (Math.sqrt(dot(rows[i], rows[i])) * queryNorm);
      if (score <= bestScore[K - 1])
        continue;
      int pos = K - 1;
      while (pos > 0 && bestScore[pos - 1] < score) {
        bestScore[pos] = bestScore[pos - 1];
        best[pos] = best[pos - 1];
        pos--;
      }
      bestScore[pos] = score;
      best[pos] = i;
    }
    return best;
  }

  private static double dot(final float[] a, final float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++)
      sum += a[i] * b[i];
    return sum;
  }
}
