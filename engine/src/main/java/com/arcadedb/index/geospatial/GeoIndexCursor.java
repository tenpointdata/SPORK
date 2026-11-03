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
package com.arcadedb.index.geospatial;

import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.serializer.BinaryComparator;
import com.arcadedb.utility.RidHashSet;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Lazy cursor over the GeoHash cells covering a search shape (#5601).
 * <p>
 * A query decomposes its shape into covering cells - a 10x10-degree box resolves into roughly 4,200 of them - and each
 * cell is answered by the underlying LSM-Tree with one prefix range scan (frontier cell) or one exact lookup (internal
 * cell). The previous implementation drained every one of those scans into a {@code LinkedHashSet} and then copied it
 * into a list of {@code IndexCursorEntry} before the caller saw the first row, so the whole candidate set was resident
 * even when the query only needed the first page of results. This cursor opens ONE underlying scan at a time, closes
 * it as soon as it is drained, and never touches a cell the consumer did not ask to reach.
 * <p>
 * ADJACENT FRONTIER CELLS SHARE ONE SCAN (retrieval speed, S12). The walk is a pre-order over the cell tree, so the frontier
 * cells of one parent come out one after the other in GeoHash order, and two siblings whose last characters are consecutive
 * in the GeoHash alphabet have no cell between them in the index's key order: the prefix range of the first and the prefix
 * range of the second are contiguous, and ONE range from the first token to the last token's upper bound covers exactly the
 * run of them. A wide radius decomposes into mostly such runs (a 200 km circle at the default precision is about 200 cells,
 * of which the interior ones come in runs of siblings), and every run saved is a cursor open saved: on a live LSM index that
 * is one seek per mutable page and per compacted series. Coalescing is off for the FULL layout, whose frontier cells are
 * exact lookups rather than prefix scans, and for a factory that does not open runs.
 * <p>
 * Deduplication is still needed and is still a set of RIDs: the covering cells of a query are disjoint, but a single
 * record is not indexed under a single cell - a polygon or a line decomposes into many - so the same RID can be reached
 * through several of them. What the set no longer costs is a second, parallel copy of the same information: it holds
 * only what has actually been EMITTED, so a consumer that stops early (a {@code LIMIT} applied after the geo.*
 * predicate re-check) never pays for the candidates it did not read. On the legacy {@code FULL} layout, which stores
 * the whole ancestor chain of every cell, the set additionally absorbs the same record being found under a cell and
 * under one of its ancestors. The set is a {@link RidHashSet}: two primitive arrays, no boxed RID per candidate.
 * <p>
 * {@link #hasNext()} is exact: the lookahead needed to skip already-seen RIDs also makes it honest, so {@link #next()}
 * never answers null and throws {@link NoSuchElementException} when exhausted, per the {@link Iterator} contract. Since
 * #5635 that is the contract of every {@link IndexCursor}, {@code LSMTreeIndexCursor} included, so the cell scans this
 * one drains need no null guard.
 * <p>
 * A geospatial hit has no relevance to report - the materialising implementation stamped a constant 1 on every entry -
 * so the score is left at the interface default, the documented "scoring not supported" value. Nothing reads a score
 * off a geo cursor: only the full-text path propagates one ({@code TypeIndex.get}, {@code FetchFromIndexStep}).
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class GeoIndexCursor implements IndexCursor {
  /** Shared: {@link #getBinaryKeyTypes()} is on the cursor surface, so it must not allocate per call. */
  private static final byte[] NO_KEY_TYPES = new byte[0];

  /**
   * The GeoHash alphabet, in the order the cells of one parent sort in: it is also ASCII order, which is what makes a run of
   * consecutive siblings one contiguous key range (no indexed token uses a character outside it).
   */
  static final String GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";

  private final Object[]            keys;
  private final GeoCoveringCellWalk walk;
  private final CellCursorFactory   cellCursorFactory;
  private final boolean             coalesceFrontierRuns;
  private final RidHashSet          seen = new RidHashSet();
  private       IndexCursor         cellCursor;
  private       RID                 nextRID;
  private       RID                 currentRID;
  private       boolean             closed;
  // THE CELL THE RUN DETECTION READ PAST: THE FIRST CELL OF THE NEXT SCAN, SEE openNextScan()
  private       String              pendingToken;
  private       boolean             pendingFrontier;
  // THE RUNS OPENED AND THE CELLS THEY SAVED, FOR THE TESTS AND THE BENCHMARK OF THE COALESCING
  private       int                 scansOpened;
  private       int                 cellsCoalesced;

  /**
   * Opens the underlying scan that answers one covering cell. Kept as a factory rather than a direct call into
   * {@link LSMTreeGeoIndex} so the streaming behaviour can be exercised without a database.
   */
  @FunctionalInterface
  interface CellCursorFactory {
    IndexCursor open(String token, boolean frontier);

    /**
     * Opens ONE scan over the run of adjacent frontier cells from {@code firstToken} to {@code lastToken} inclusive, both of
     * the same level and the same parent: the prefix range from the first token to the upper bound of the last. A factory
     * that cannot (an exact-lookup layout) leaves the default, and the cursor must then be built without coalescing.
     */
    default IndexCursor openFrontierRun(final String firstToken, final String lastToken) {
      throw new UnsupportedOperationException("This cell cursor factory opens one cell at a time");
    }
  }

  GeoIndexCursor(final Object[] keys, final GeoCoveringCellWalk walk, final CellCursorFactory cellCursorFactory) {
    this(keys, walk, cellCursorFactory, false);
  }

  /**
   * @param coalesceFrontierRuns true to answer a run of adjacent frontier cells with one scan through
   *                             {@link CellCursorFactory#openFrontierRun}, false to open every cell on its own
   */
  GeoIndexCursor(final Object[] keys, final GeoCoveringCellWalk walk, final CellCursorFactory cellCursorFactory,
      final boolean coalesceFrontierRuns) {
    this.keys = keys;
    this.walk = walk;
    this.cellCursorFactory = cellCursorFactory;
    this.coalesceFrontierRuns = coalesceFrontierRuns;
  }

  @Override
  public boolean hasNext() {
    if (nextRID == null)
      fetchNext();
    return nextRID != null;
  }

  @Override
  public Identifiable next() {
    if (nextRID == null) {
      fetchNext();
      if (nextRID == null)
        throw new NoSuchElementException();
    }
    currentRID = nextRID;
    nextRID = null;
    return currentRID;
  }

  /**
   * Advances to the next RID never emitted before, opening as many cell scans as it takes. Leaves {@link #nextRID}
   * null when the covering-cell walk is over.
   */
  private void fetchNext() {
    if (closed)
      return;

    while (true) {
      if (cellCursor != null) {
        while (cellCursor.hasNext()) {
          final RID rid = cellCursor.next().getIdentity();
          if (seen.add(rid)) {
            nextRID = rid;
            return;
          }
        }

        // drained: release its pages and, for a compacted series, its retire guard before opening the next cell
        cellCursor.close();
        cellCursor = null;
      }

      if (!openNextScan()) {
        // The walk is over, so nothing more can be emitted and the seen-set has no reader left. Release it here rather
        // than waiting for close(): on a wide-area query it holds one entry per candidate, and a cursor that empties
        // itself on exhaustion does not depend on every caller remembering to close a fully drained scan.
        closed = true;
        seen.clear();
        return;
      }
    }
  }

  /**
   * Opens the scan of the next covering cell - or of the run of adjacent frontier cells it begins, when coalescing - into
   * {@link #cellCursor}. False when the walk has no cell left.
   */
  private boolean openNextScan() {
    if (!nextCell())
      return false;
    final String first = pendingToken;
    final boolean frontier = pendingFrontier;
    pendingToken = null;

    if (!frontier || !coalesceFrontierRuns) {
      cellCursor = cellCursorFactory.open(first, frontier);
      ++scansOpened;
      return true;
    }

    // EXTEND THE RUN WHILE THE NEXT CELL IS THE FRONTIER SIBLING RIGHT AFTER THE LAST ONE; THE CELL THAT ENDS IT IS KEPT FOR
    // THE NEXT SCAN
    String last = first;
    while (walk.advance()) {
      if (walk.isFrontier() && adjacentSiblings(last, walk.getToken())) {
        last = walk.getToken();
        ++cellsCoalesced;
      } else {
        pendingToken = walk.getToken();
        pendingFrontier = walk.isFrontier();
        break;
      }
    }

    cellCursor = last.equals(first) ? cellCursorFactory.open(first, true) : cellCursorFactory.openFrontierRun(first, last);
    ++scansOpened;
    return true;
  }

  /** Puts the next covering cell into the pending slot: the one the run detection read past, or the walk's next. */
  private boolean nextCell() {
    if (pendingToken != null)
      return true;
    if (!walk.advance())
      return false;
    pendingToken = walk.getToken();
    pendingFrontier = walk.isFrontier();
    return true;
  }

  /**
   * Whether {@code next} is the sibling cell right after {@code previous} in GeoHash order: same level, same parent, last
   * characters consecutive in {@link #GEOHASH_ALPHABET}. Only then is the key range from one to the other free of any other
   * cell, so a range over both answers exactly the two of them.
   */
  static boolean adjacentSiblings(final String previous, final String next) {
    final int length = previous.length();
    if (length == 0 || next.length() != length)
      return false;
    for (int i = 0; i < length - 1; i++)
      if (previous.charAt(i) != next.charAt(i))
        return false;
    final int before = GEOHASH_ALPHABET.indexOf(previous.charAt(length - 1));
    return before >= 0 && before + 1 < GEOHASH_ALPHABET.length() && GEOHASH_ALPHABET.charAt(before + 1) == next.charAt(length - 1);
  }

  /**
   * Number of RIDs the dedup set is currently holding - the cursor's only unbounded footprint. Package-private
   * introspection: the retained size is a property worth asserting on, since a cursor that keeps it after the walk is
   * over would hold one entry per candidate of a wide-area query for as long as the caller keeps the cursor.
   */
  int seenSize() {
    return seen.size();
  }

  /** How many underlying scans this cursor opened so far: one per cell, or one per run of adjacent frontier cells. */
  int scansOpened() {
    return scansOpened;
  }

  /** How many covering cells were answered by the scan of the run they belong to instead of a scan of their own. */
  int cellsCoalesced() {
    return cellsCoalesced;
  }

  @Override
  public Object[] getKeys() {
    // The cell tokens are an internal encoding of the search shape, not something a caller can use as a key, so a geo
    // cursor echoes the query keys - which is what the materialising implementation put into every IndexCursorEntry.
    return keys;
  }

  @Override
  public Identifiable getRecord() {
    return currentRID;
  }

  /**
   * A geospatial cursor exposes NO comparator, like the {@code TempIndexCursor} it replaced: its rows come from many
   * cell scans merged by a hash set, so they carry no index order a caller could compare them in. Callers that need to
   * order geo results must do so on the records, after the {@code geo.*} predicate has re-checked them.
   */
  @Override
  public BinaryComparator getComparator() {
    return null;
  }

  /** Empty for the same reason as {@link #getKeys()}: the cell tokens are not a key type a caller can interpret. */
  @Override
  public byte[] getBinaryKeyTypes() {
    return NO_KEY_TYPES;
  }

  /**
   * Unknown - it would take walking every covering cell, which is exactly what this cursor exists not to do up front.
   * <p>
   * No cardinality estimator can see this {@code -1}: the only reader of {@code estimateSize()} in the engine is
   * {@code WhereClause.estimateFromIndex}, which is gated on {@code supportsOrderedIterations()} - false for a
   * geospatial index - and reaches the cursor through {@code range()}, which this index does not implement at all.
   */
  @Override
  public long estimateSize() {
    return -1L;
  }

  @Override
  public void close() {
    closed = true;
    nextRID = null;
    if (cellCursor != null) {
      cellCursor.close();
      cellCursor = null;
    }
    seen.clear();
  }

  @Override
  public Iterator<Identifiable> iterator() {
    return this;
  }
}
