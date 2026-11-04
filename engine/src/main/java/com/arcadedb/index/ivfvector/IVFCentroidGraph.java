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

import com.arcadedb.index.vector.GraphSearcherPool;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * An in-heap jvector graph over the fine centroids of an {@code LSM_IVF_VECTOR} index, which replaces the two-level brute-force
 * selector ({@link IVFCentroids#select}) once an index has enough lists for it to pay. Measured on 256-d centroids
 * ({@code IVFCentroidSelectorBenchmark}, beam 4 x nprobe): at 50K lists picking 32 costs 0.21 ms p50 with 0.98 overlap with the
 * exact 32 nearest against 0.56 ms and 0.85 for the two levels (64 probes: 0.79 ms / 0.96 against 0.93 ms / 0.55); at 200K lists
 * 0.25 ms / 0.96 against 1.8 ms / 0.999.
 * <p>
 * Not persisted: every node builds it from the resident centroids it reads off the pages (the build after a training, in the
 * background after a reopen or a training replicated from the leader), and the two-level selector serves until it is ready.
 * jvector builds on a {@link ForkJoinPool}; the build gets its own, half the cores wide so queries keep the other half, and shuts
 * it down when done - a transient pool for a background build, the same shape as {@code LSMVectorIndex}'s graph builds (see the
 * engine-concurrency skill), never the JDK common pool. Searchers are checked out of a {@link GraphSearcherPool}.
 * <p>
 * Resident cost: a second copy of the fine centroids as jvector vectors plus the adjacency (M = 16, ~150 B per list), so about
 * twice the fine centroid bytes of {@link IVFCentroids#residentBytes()}.
 */
final class IVFCentroidGraph {
  static final         int                 MAX_CONNECTIONS = 16;
  static final         int                 BEAM_WIDTH      = 100;
  /** Beam of a selection: four times the lists wanted (0.96-0.98 overlap with the exact probe set, measured), at least this. */
  private static final int                 MIN_SEARCH_BEAM = 64;
  private static final VectorTypeSupport   VTS             = VectorizationProvider.getInstance().getVectorTypeSupport();

  private final ImmutableGraphIndex          graph;
  private final ListRandomAccessVectorValues vectors;
  private final GraphSearcherPool            searchers;

  private IVFCentroidGraph(final ImmutableGraphIndex graph, final ListRandomAccessVectorValues vectors) {
    this.graph = graph;
    this.vectors = vectors;
    this.searchers = new GraphSearcherPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
  }

  /** Builds the graph over the fine centroids of {@code centroids} on a transient pool, on the calling thread's watch. */
  static IVFCentroidGraph build(final IVFCentroids centroids) {
    final int dimensions = centroids.dimensions;
    final List<VectorFloat<?>> rows = new ArrayList<>(centroids.nlist);
    for (int f = 0; f < centroids.nlist; f++)
      rows.add(VTS.createFloatVector(Arrays.copyOfRange(centroids.fine, f * dimensions, (f + 1) * dimensions)));
    final ListRandomAccessVectorValues vectors = new ListRandomAccessVectorValues(rows, dimensions);
    final ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    try {
      final GraphIndexBuilder builder = new GraphIndexBuilder(
          BuildScoreProvider.randomAccessScoreProvider(vectors, VectorSimilarityFunction.EUCLIDEAN), dimensions, MAX_CONNECTIONS,
          BEAM_WIDTH, 1.2f, 1.2f, true, true, pool, pool);
      try {
        return new IVFCentroidGraph(builder.build(vectors), vectors);
      } finally {
        try {
          builder.close();
        } catch (final IOException e) {
          // In-heap builder: nothing on disk to release
        }
      }
    } finally {
      pool.shutdown();
    }
  }

  /** The {@code k} centroids nearest to {@code query}, nearest first. */
  int[] nearest(final float[] query, final int k) {
    final int wanted = Math.min(k, vectors.size());
    final GraphSearcher searcher = searchers.borrow(graph, 0L);
    try {
      final SearchResult result = searcher.search(
          DefaultSearchScoreProvider.exact(VTS.createFloatVector(query), VectorSimilarityFunction.EUCLIDEAN, vectors), wanted,
          Math.max(MIN_SEARCH_BEAM, 4 * wanted), 0f, 0f, Bits.ALL);
      final SearchResult.NodeScore[] nodes = result.getNodes();
      final int[] ids = new int[nodes.length];
      for (int i = 0; i < nodes.length; i++)
        ids[i] = nodes[i].node;
      return ids;
    } finally {
      searchers.release(searcher, graph, 0L);
    }
  }

  long residentBytes() {
    return (long) vectors.size() * (vectors.dimension() * 4L + MAX_CONNECTIONS * 2L * 4L + 32L);
  }
}
