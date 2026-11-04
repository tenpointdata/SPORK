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

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Item 5 of the IVF hardening list: does an in-heap jvector graph over the fine centroids beat the two-level brute-force selector
 * ({@link IVFCentroids#select}) at 50K+ centroids? Both pick {@code nprobe} lists for the same queries, alternating per query so a
 * shared box loads both alike; reported per size: p50/p95 latency and the overlap of the picked lists with the exact
 * {@code nprobe} nearest centroids. Sized with {@code -Divf.selector.sizes=50000,200000} and {@code -Divf.selector.dims=256}.
 * The graph build here runs on jvector's default pool: this is a measurement, not the index.
 */
@Tag("benchmark")
class IVFCentroidSelectorBenchmark {
  private static final int[] SIZES   = Arrays.stream(System.getProperty("ivf.selector.sizes", "50000,200000").split(","))
      .mapToInt(Integer::parseInt).toArray();
  private static final int   DIMS    = Integer.getInteger("ivf.selector.dims", 256);
  private static final int   QUERIES = 500;

  @Test
  void graphAgainstTwoLevel() throws Exception {
    final VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    for (final int n : SIZES) {
      final Random random = new Random(n);
      // Centroids look like data: points around 2,000 regions of the space
      final float[][] regions = gaussian(random, 2_000, DIMS, 1f);
      final float[][] points = new float[n][];
      for (int i = 0; i < n; i++) {
        final float[] region = regions[random.nextInt(regions.length)];
        points[i] = new float[DIMS];
        for (int d = 0; d < DIMS; d++)
          points[i][d] = region[d] + (float) random.nextGaussian() * 0.4f;
      }
      long t0 = System.nanoTime();
      final IVFCentroids twoLevel = IVFKMeans.train(points, DIMS, n, 5, 1, 0, random);
      final long twoLevelBuild = System.nanoTime() - t0;

      final List<VectorFloat<?>> list = new ArrayList<>(n);
      for (int f = 0; f < twoLevel.nlist; f++)
        list.add(vts.createFloatVector(Arrays.copyOfRange(twoLevel.fine, f * DIMS, (f + 1) * DIMS)));
      final ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(list, DIMS);
      t0 = System.nanoTime();
      final ImmutableGraphIndex graph;
      try (final GraphIndexBuilder builder = new GraphIndexBuilder(
          BuildScoreProvider.randomAccessScoreProvider(ravv, VectorSimilarityFunction.EUCLIDEAN), DIMS, 16, 100, 1.2f, 1.2f, true)) {
        graph = builder.build(ravv);
      }
      final long graphBuild = System.nanoTime() - t0;
      final GraphSearcher searcher = new GraphSearcher(graph);

      final float[][] queries = new float[QUERIES][];
      for (int q = 0; q < QUERIES; q++) {
        final float[] base = points[random.nextInt(n)];
        queries[q] = new float[DIMS];
        for (int d = 0; d < DIMS; d++)
          queries[q][d] = base[d] + (float) random.nextGaussian() * 0.3f;
      }
      System.out.printf("[ivf-selector] n=%d dims=%d coarse=%d: two-level build %.1fs, graph build %.1fs (M=16, beam=100)%n", n, DIMS,
          twoLevel.coarseCount, twoLevelBuild / 1e9, graphBuild / 1e9);

      for (final int nprobe : new int[] { 32, 64 }) {
        final int[][] exact = new int[QUERIES][];
        for (int q = 0; q < QUERIES; q++)
          exact[q] = bruteTop(twoLevel, queries[q], nprobe);
        for (final int ef : new int[] { nprobe, 2 * nprobe, 4 * nprobe }) {
          final long[] twoLevelNanos = new long[QUERIES];
          final long[] graphNanos = new long[QUERIES];
          final long[] bruteNanos = new long[QUERIES];
          double twoLevelHits = 0, graphHits = 0;
          // Warm both paths
          for (int q = 0; q < 50; q++) {
            twoLevel.select(queries[q], nprobe);
            searcher.search(DefaultSearchScoreProvider.exact(vts.createFloatVector(queries[q]), VectorSimilarityFunction.EUCLIDEAN, ravv),
                nprobe, ef, 0f, 0f, Bits.ALL);
          }
          for (int q = 0; q < QUERIES; q++) {
            // Alternate which goes first, so neither always runs on the cache the other warmed
            final boolean graphFirst = (q & 1) == 0;
            int[] picked = null;
            SearchResult result = null;
            for (int pass = 0; pass < 2; pass++) {
              if ((pass == 0) == graphFirst) {
                final long s = System.nanoTime();
                result = searcher.search(
                    DefaultSearchScoreProvider.exact(vts.createFloatVector(queries[q]), VectorSimilarityFunction.EUCLIDEAN, ravv), nprobe,
                    ef, 0f, 0f, Bits.ALL);
                graphNanos[q] = System.nanoTime() - s;
              } else {
                final long s = System.nanoTime();
                picked = twoLevel.select(queries[q], nprobe);
                twoLevelNanos[q] = System.nanoTime() - s;
              }
            }
            if (ef == nprobe) {
              final long s = System.nanoTime();
              bruteTop(twoLevel, queries[q], nprobe);
              bruteNanos[q] = System.nanoTime() - s;
            }
            twoLevelHits += overlap(picked, exact[q]);
            final SearchResult.NodeScore[] nodes = result.getNodes();
            final int[] fromGraph = new int[nodes.length];
            for (int i = 0; i < nodes.length; i++)
              fromGraph[i] = nodes[i].node;
            graphHits += overlap(fromGraph, exact[q]);
          }
          Arrays.sort(twoLevelNanos);
          Arrays.sort(graphNanos);
          Arrays.sort(bruteNanos);
          System.out.printf(
              "[ivf-selector] n=%d nprobe=%d ef=%d: two-level p50 %.3f ms p95 %.3f ms recall %.3f | graph p50 %.3f ms p95 %.3f ms recall %.3f%s%n",
              n, nprobe, ef, twoLevelNanos[QUERIES / 2] / 1e6, twoLevelNanos[QUERIES * 95 / 100] / 1e6, twoLevelHits / (QUERIES * nprobe),
              graphNanos[QUERIES / 2] / 1e6, graphNanos[QUERIES * 95 / 100] / 1e6, graphHits / (QUERIES * nprobe),
              ef == nprobe ? String.format(" | full brute force p50 %.3f ms", bruteNanos[QUERIES / 2] / 1e6) : "");
        }
      }
    }
  }

  private static int[] bruteTop(final IVFCentroids centroids, final float[] query, final int k) {
    final float[] distances = new float[centroids.nlist];
    for (int f = 0; f < centroids.nlist; f++)
      distances[f] = IVFCodec.squaredDistance(query, centroids.fine, f * centroids.dimensions);
    return Arrays.copyOf(IVFCentroids.sortedIndexes(distances), k);
  }

  private static int overlap(final int[] picked, final int[] exact) {
    int hits = 0;
    for (final int p : picked)
      for (final int e : exact)
        if (p == e) {
          hits++;
          break;
        }
    return hits;
  }

  private static float[][] gaussian(final Random random, final int count, final int dims, final float sigma) {
    final float[][] out = new float[count][dims];
    for (final float[] row : out)
      for (int d = 0; d < dims; d++)
        row[d] = (float) random.nextGaussian() * sigma;
    return out;
  }
}
