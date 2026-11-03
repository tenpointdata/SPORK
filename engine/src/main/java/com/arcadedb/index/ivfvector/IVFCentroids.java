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

import com.arcadedb.log.LogManager;

import java.util.logging.Level;

/**
 * The only per-index state an {@code LSM_IVF_VECTOR} index keeps resident: its centroids, read from the index pages once
 * per training and shared by every search and every write. Two levels, so that neither assigning a vector nor choosing
 * the lists to probe costs a pass over every centroid: {@link #coarseCount} coarse centroids partition the
 * {@link #nlist} fine ones (the posting lists), the fine centroids of coarse group {@code c} being the range
 * {@code [coarseStart[c], coarseStart[c+1])}. A small index has one coarse group holding everything, which makes both
 * operations the plain brute-force scan.
 * <p>
 * Immutable once built; a retraining publishes a new instance under a new {@link #version}.
 */
public final class IVFCentroids {
  /** Below this many fine centroids the training keeps a single coarse group: the brute-force scan is cheaper than a level. */
  public static final int SINGLE_LEVEL_MAX = 256;
  /** Coarse groups a search opens at least, so a query near a group border still sees the lists across it. */
  private static final int MIN_COARSE_PROBES  = 4;
  /** Fine candidates a search ranks per probed list, before keeping {@code nprobe} of them. */
  private static final int FINE_CANDIDATES_PER_PROBE = 16;
  /**
   * Lists from which an {@link IVFCentroidGraph} replaces the two levels for selection and assignment (measured winner at 50K:
   * 4.6x faster and closer to the exact probe set). Settable for tests, which exercise the graph on small indexes.
   */
  static volatile int graphMinLists = 20_000;

  public final int     dimensions;
  public final int     nlist;
  public final int     coarseCount;
  public final float[] fine;        // nlist x dimensions
  public final float[] coarse;      // coarseCount x dimensions
  public final int[]   coarseStart; // coarseCount + 1
  public final float[] thresholds;  // dimensions: the per-dimension BINARY threshold
  public final int     version;
  public final boolean trained;
  /** Seed of the SimHash projection of the BINARY codes, 0 for the plain per-dimension sign (HAMMING over bit vectors). */
  public final int     projectionSeed;
  /** The projection itself ({@code dimensions x dimensions}), rebuilt from the seed; null when the seed is 0. */
  public final float[] projection;
  /** The graph over the fine centroids once built (see {@link #graphMinLists}); the two levels serve until then. */
  private volatile IVFCentroidGraph graph;
  private volatile boolean          graphRequested;

  public IVFCentroids(final int dimensions, final float[] fine, final float[] coarse, final int[] coarseStart,
      final float[] thresholds, final int version, final boolean trained) {
    this(dimensions, fine, coarse, coarseStart, thresholds, version, trained, 0);
  }

  public IVFCentroids(final int dimensions, final float[] fine, final float[] coarse, final int[] coarseStart,
      final float[] thresholds, final int version, final boolean trained, final int projectionSeed) {
    this.projectionSeed = projectionSeed;
    this.projection = projectionSeed == 0 ? null : IVFCodec.gaussianProjection(projectionSeed, dimensions);
    this.dimensions = dimensions;
    this.nlist = fine.length / dimensions;
    this.coarseCount = coarseStart.length - 1;
    this.fine = fine;
    this.coarse = coarse;
    this.coarseStart = coarseStart;
    this.thresholds = thresholds;
    this.version = version;
    this.trained = trained;
    if (coarse.length != coarseCount * dimensions || coarseStart[coarseCount] != nlist)
      throw new IllegalArgumentException("Inconsistent centroid layout: nlist=" + nlist + " coarse=" + coarseCount);
  }

  /** The untrained layout: one pseudo-centroid at the origin takes every vector until a rebuild trains real ones. */
  public static IVFCentroids untrained(final int dimensions, final int version, final int projectionSeed) {
    return new IVFCentroids(dimensions, new float[dimensions], new float[dimensions], new int[] { 0, 1 }, new float[dimensions],
        version, false, projectionSeed);
  }

  /** This set with another projection seed (a HAMMING index wants none) and the thresholds given. */
  public IVFCentroids withCodes(final float[] newThresholds, final int newProjectionSeed) {
    return new IVFCentroids(dimensions, fine, coarse, coarseStart, newThresholds, version, trained, newProjectionSeed);
  }

  /** This set under another version (a training laid out by a transaction that numbers it), sharing its arrays and its graph. */
  public IVFCentroids withVersion(final int newVersion) {
    final IVFCentroids copy = new IVFCentroids(dimensions, fine, coarse, coarseStart, thresholds, newVersion, trained, projectionSeed);
    copy.graph = graph;
    copy.graphRequested = graphRequested;
    return copy;
  }

  /** Whether this set is large enough for the graph selector. */
  boolean wantsGraph() {
    return trained && nlist >= graphMinLists;
  }

  boolean hasGraph() {
    return graph != null;
  }

  /** Builds the graph on the calling thread (a build, before it assigns every vector) when this set is large enough for one. */
  void buildGraph() {
    if (!wantsGraph() || graph != null)
      return;
    graphRequested = true;
    graph = IVFCentroidGraph.build(this);
  }

  /**
   * Builds the graph in the background (a set read back from the pages: a reopen, or a training replicated from the leader), once.
   * The build runs on its own transient jvector pool, see {@link IVFCentroidGraph}; the two levels answer until it is published.
   */
  void buildGraphInBackground() {
    if (!wantsGraph() || graphRequested)
      return;
    synchronized (this) {
      if (graphRequested)
        return;
      graphRequested = true;
    }
    final Thread builder = new Thread(() -> {
      try {
        graph = IVFCentroidGraph.build(this);
      } catch (final RuntimeException e) {
        LogManager.instance().log(this, Level.WARNING, "IVF centroid graph build failed, the two-level selector stays", e);
      }
    }, "ArcadeDB IVF centroid graph build");
    builder.setDaemon(true);
    builder.start();
  }

  /** The fine centroid (posting list) nearest to {@code vector}, looked up through its two nearest coarse groups. */
  public int assign(final float[] vector) {
    if (nlist == 1)
      return 0;
    final IVFCentroidGraph centroidGraph = graph;
    if (centroidGraph != null) {
      final int[] nearest = centroidGraph.nearest(vector, 1);
      if (nearest.length > 0)
        return nearest[0];
    }
    if (coarseCount == 1)
      return nearestFine(vector, 0, nlist);

    // A group with no fine centroid is skipped: its empty range would answer its start, past the last list for the last group
    // (a training no longer lays one out, but a layout read back from an older file can hold one)
    int best = -1, second = -1;
    float bestDistance = Float.MAX_VALUE, secondDistance = Float.MAX_VALUE;
    for (int c = 0; c < coarseCount; c++) {
      if (coarseStart[c + 1] == coarseStart[c])
        continue;
      final float distance = IVFCodec.squaredDistance(vector, coarse, c * dimensions);
      if (distance < bestDistance) {
        second = best;
        secondDistance = bestDistance;
        best = c;
        bestDistance = distance;
      } else if (distance < secondDistance) {
        second = c;
        secondDistance = distance;
      }
    }
    int nearest = nearestFine(vector, coarseStart[best], coarseStart[best + 1]);
    if (second >= 0) {
      final int other = nearestFine(vector, coarseStart[second], coarseStart[second + 1]);
      if (IVFCodec.squaredDistance(vector, fine, other * dimensions) < IVFCodec.squaredDistance(vector, fine, nearest * dimensions))
        nearest = other;
    }
    return nearest;
  }

  private int nearestFine(final float[] vector, final int from, final int to) {
    int best = from;
    float bestDistance = Float.MAX_VALUE;
    for (int f = from; f < to; f++) {
      final float distance = IVFCodec.squaredDistance(vector, fine, f * dimensions);
      if (distance < bestDistance) {
        best = f;
        bestDistance = distance;
      }
    }
    return best;
  }

  /**
   * The {@code nprobe} fine centroids nearest to {@code query}, nearest first. Ranks the coarse groups, opens them in that
   * order until enough fine candidates are in view, then ranks those.
   */
  public int[] select(final float[] query, final int nprobe) {
    final int wanted = Math.min(nprobe, nlist);
    if (coarseCount == 1 || wanted == nlist)
      return topFine(query, 0, nlist, null, wanted);
    final IVFCentroidGraph centroidGraph = graph;
    if (centroidGraph != null) {
      final int[] nearest = centroidGraph.nearest(query, wanted);
      if (nearest.length == wanted)
        return nearest;
    }

    final float[] coarseDistances = new float[coarseCount];
    for (int c = 0; c < coarseCount; c++)
      coarseDistances[c] = IVFCodec.squaredDistance(query, coarse, c * dimensions);
    final int[] coarseOrder = sortedIndexes(coarseDistances);

    final int candidatesWanted = Math.max(wanted * FINE_CANDIDATES_PER_PROBE, wanted);
    int candidates = 0;
    int groups = 0;
    int opened = 0; // groups holding lists: an empty one (an older layout) does not count towards the minimum
    while (groups < coarseCount && (opened < MIN_COARSE_PROBES || candidates < candidatesWanted)) {
      final int c = coarseOrder[groups++];
      final int lists = coarseStart[c + 1] - coarseStart[c];
      if (lists > 0)
        opened++;
      candidates += lists;
    }
    final int[] candidateIds = new int[candidates];
    int filled = 0;
    for (int g = 0; g < groups; g++) {
      final int c = coarseOrder[g];
      for (int f = coarseStart[c]; f < coarseStart[c + 1]; f++)
        candidateIds[filled++] = f;
    }
    return topFine(query, 0, candidateIds.length, candidateIds, wanted);
  }

  /** The {@code wanted} nearest fine centroids among {@code ids} (or the range when {@code ids} is null), nearest first. */
  private int[] topFine(final float[] query, final int from, final int to, final int[] ids, final int wanted) {
    final int count = to - from;
    final float[] distances = new float[count];
    for (int i = 0; i < count; i++) {
      final int f = ids == null ? from + i : ids[from + i];
      distances[i] = IVFCodec.squaredDistance(query, fine, f * dimensions);
    }
    final int[] order = sortedIndexes(distances);
    final int[] result = new int[Math.min(wanted, count)];
    for (int i = 0; i < result.length; i++)
      result[i] = ids == null ? from + order[i] : ids[from + order[i]];
    return result;
  }

  /** The indexes of {@code values} in ascending order of value, an allocation-light insertion/merge sort on primitives. */
  static int[] sortedIndexes(final float[] values) {
    final int n = values.length;
    final int[] order = new int[n];
    for (int i = 0; i < n; i++)
      order[i] = i;
    // Heap sort on the index array keyed by values: O(n log n), no boxing
    for (int i = n / 2 - 1; i >= 0; i--)
      siftDown(order, values, i, n);
    for (int end = n - 1; end > 0; end--) {
      final int tmp = order[0];
      order[0] = order[end];
      order[end] = tmp;
      siftDown(order, values, 0, end);
    }
    return order;
  }

  private static void siftDown(final int[] order, final float[] values, int root, final int end) {
    while (true) {
      final int left = 2 * root + 1;
      if (left >= end)
        return;
      int child = left;
      if (left + 1 < end && values[order[left + 1]] > values[order[left]])
        child = left + 1;
      if (values[order[child]] <= values[order[root]])
        return;
      final int tmp = order[root];
      order[root] = order[child];
      order[child] = tmp;
      root = child;
    }
  }

  /** Approximate resident bytes of this instance: the figure a capacity plan multiplies by the number of indexes. */
  public long residentBytes() {
    final IVFCentroidGraph centroidGraph = graph;
    return 4L * (fine.length + coarse.length + thresholds.length + coarseStart.length + (projection == null ? 0 : projection.length)) + 64
        + (centroidGraph == null ? 0 : centroidGraph.residentBytes());
  }
}
