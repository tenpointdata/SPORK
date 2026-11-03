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

import com.arcadedb.database.RID;
import com.arcadedb.utility.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The per-index answers of a multi-index k-NN search, read nearest first.
 * <p>
 * Every multi-index search used to concatenate its per-index lists and sort the concatenation by distance. This yields the
 * same sequence - ties keep the order of the concatenation, as the stable sort kept it - by merging the lists, each already
 * sorted, through a binary heap of source positions held in primitive arrays. The callers stop reading after their
 * {@code k} rows, so a merge pays {@code O(log sources)} per row read instead of sorting {@code sources * fetchK} pairs up
 * front, and allocates no comparator, boxed key or merged list.
 * <p>
 * The index answers are sorted ascending by contract; one that is not is sorted (stably) on its own first, which is what
 * the sort of the concatenation would have made of it.
 */
public final class NeighborMerge implements Iterable<Pair<RID, Float>> {
  private static final Comparator<Pair<RID, Float>> BY_DISTANCE = Comparator.comparing(Pair::getSecond);

  private final List<List<Pair<RID, Float>>> sources;
  private final int                          size;

  private NeighborMerge(final List<List<Pair<RID, Float>>> sources, final int size) {
    this.sources = sources;
    this.size = size;
  }

  /**
   * @param sources the per-index answers, in the order their concatenation used to be sorted in. Not copied: the lists
   *                must not change while the merge is read
   */
  public static NeighborMerge of(final List<List<Pair<RID, Float>>> sources) {
    final List<List<Pair<RID, Float>>> sorted = new ArrayList<>(sources.size());
    int size = 0;
    for (final List<Pair<RID, Float>> source : sources) {
      if (source == null || source.isEmpty())
        continue;
      sorted.add(isSorted(source) ? source : sortedCopy(source));
      size += source.size();
    }
    return new NeighborMerge(sorted, size);
  }

  /** How many candidates the merge holds in all, repeats across sources included. */
  public int size() {
    return size;
  }

  @Override
  public Iterator<Pair<RID, Float>> iterator() {
    return sources.size() == 1 ? sources.getFirst().iterator() : new HeapIterator();
  }

  private static boolean isSorted(final List<Pair<RID, Float>> source) {
    for (int i = 1; i < source.size(); i++)
      if (Float.compare(source.get(i - 1).getSecond(), source.get(i).getSecond()) > 0)
        return false;
    return true;
  }

  private static List<Pair<RID, Float>> sortedCopy(final List<Pair<RID, Float>> source) {
    final List<Pair<RID, Float>> copy = new ArrayList<>(source);
    copy.sort(BY_DISTANCE);
    return copy;
  }

  /**
   * A min-heap of source indexes keyed by the distance at each source's cursor, ties broken by source index: the
   * concatenation order, so the merge is the stable sort of the concatenation.
   */
  private final class HeapIterator implements Iterator<Pair<RID, Float>> {
    private final int[]   heap;
    private final int[]   cursor;
    private final float[] head;
    private       int     heapSize;

    private HeapIterator() {
      final int n = sources.size();
      heap = new int[n];
      cursor = new int[n];
      head = new float[n];
      for (int s = 0; s < n; s++) {
        head[s] = sources.get(s).getFirst().getSecond();
        heap[heapSize] = s;
        siftUp(heapSize++);
      }
    }

    @Override
    public boolean hasNext() {
      return heapSize > 0;
    }

    @Override
    public Pair<RID, Float> next() {
      if (heapSize == 0)
        throw new NoSuchElementException();
      final int s = heap[0];
      final List<Pair<RID, Float>> source = sources.get(s);
      final Pair<RID, Float> next = source.get(cursor[s]++);
      if (cursor[s] < source.size())
        head[s] = source.get(cursor[s]).getSecond();
      else
        heap[0] = heap[--heapSize];
      if (heapSize > 0)
        siftDown(0);
      return next;
    }

    private boolean before(final int a, final int b) {
      final int c = Float.compare(head[a], head[b]);
      return c < 0 || (c == 0 && a < b);
    }

    private void siftUp(int i) {
      final int s = heap[i];
      while (i > 0) {
        final int parent = (i - 1) >>> 1;
        if (!before(s, heap[parent]))
          break;
        heap[i] = heap[parent];
        i = parent;
      }
      heap[i] = s;
    }

    private void siftDown(int i) {
      final int s = heap[i];
      final int half = heapSize >>> 1;
      while (i < half) {
        int child = 2 * i + 1;
        final int right = child + 1;
        if (right < heapSize && before(heap[right], heap[child]))
          child = right;
        if (!before(heap[child], s))
          break;
        heap[i] = heap[child];
        i = child;
      }
      heap[i] = s;
    }
  }
}
