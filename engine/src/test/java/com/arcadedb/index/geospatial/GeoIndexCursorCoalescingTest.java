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
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.serializer.BinaryComparator;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.shape.Shape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval speed S12: a run of adjacent frontier cells is answered by ONE prefix range scan instead of one per cell. Driven
 * through {@link GeoIndexCursor.CellCursorFactory} over a sorted fake index, so the range a run opens is checked against the
 * keys it would really cover, deeper descendants and foreign siblings included, and the rows are compared with the one-scan-
 * per-cell cursor over the same index.
 */
class GeoIndexCursorCoalescingTest {
  private static final Object[] KEYS = new Object[] { "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))" };
  private static final String   WKT  = "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))";

  /** A sorted token-to-RIDs index, answering a cell by prefix and a run by the key range the real index would scan. */
  private static class FakeIndexFactory implements GeoIndexCursor.CellCursorFactory {
    final TreeMap<String, List<RID>> index        = new TreeMap<>();
    final List<String>               openedCells  = new ArrayList<>();
    final List<String>               openedRuns   = new ArrayList<>();

    @Override
    public IndexCursor open(final String token, final boolean frontier) {
      openedCells.add(token);
      final List<RID> rids = new ArrayList<>();
      if (frontier)
        index.subMap(token, true, token + "￿", true).values().forEach(rids::addAll);
      else
        rids.addAll(index.getOrDefault(token, List.of()));
      return new ListCursor(rids);
    }

    @Override
    public IndexCursor openFrontierRun(final String firstToken, final String lastToken) {
      openedRuns.add(firstToken + ".." + lastToken);
      final List<RID> rids = new ArrayList<>();
      index.subMap(firstToken, true, lastToken + "￿", true).values().forEach(rids::addAll);
      return new ListCursor(rids);
    }
  }

  private static class ListCursor implements IndexCursor {
    private final List<RID> rids;
    private       int       position;
    private       RID       current;

    ListCursor(final List<RID> rids) {
      this.rids = rids;
    }

    @Override
    public boolean hasNext() {
      return position < rids.size();
    }

    @Override
    public Identifiable next() {
      current = rids.get(position++);
      return current;
    }

    @Override
    public Object[] getKeys() {
      return null;
    }

    @Override
    public Identifiable getRecord() {
      return current;
    }

    @Override
    public BinaryComparator getComparator() {
      return null;
    }

    @Override
    public byte[] getBinaryKeyTypes() {
      return new byte[0];
    }

    @Override
    public long estimateSize() {
      return rids.size();
    }

    @Override
    public void close() {
    }

    @Override
    public Iterator<Identifiable> iterator() {
      return this;
    }
  }

  @Test
  void adjacentSiblingsAreTheOnesWithNothingBetweenThemInKeyOrder() {
    assertThat(GeoIndexCursor.adjacentSiblings("ab0", "ab1")).isTrue();
    // '9' AND 'b' ARE CONSECUTIVE IN THE GEOHASH ALPHABET: NO INDEXED TOKEN SORTS BETWEEN THEM
    assertThat(GeoIndexCursor.adjacentSiblings("ab9", "abb")).isTrue();
    assertThat(GeoIndexCursor.adjacentSiblings("aby", "abz")).isTrue();
    assertThat(GeoIndexCursor.adjacentSiblings("ab0", "ab2")).isFalse();
    assertThat(GeoIndexCursor.adjacentSiblings("ab1", "ab0")).isFalse();
    assertThat(GeoIndexCursor.adjacentSiblings("abz", "ac0")).as("a different parent").isFalse();
    assertThat(GeoIndexCursor.adjacentSiblings("ab", "ab1")).as("a different level").isFalse();
    assertThat(GeoIndexCursor.adjacentSiblings("ab1", "ab")).isFalse();
    assertThat(GeoIndexCursor.adjacentSiblings("", "0")).isFalse();
  }

  @Test
  void aRunOfAdjacentFrontierCellsOpensOneScanAndAnswersTheSameRows() {
    final FakeIndexFactory perCell = new FakeIndexFactory();
    final FakeIndexFactory perRun = new FakeIndexFactory();
    // ONE RECORD UNDER EVERY COVERING CELL, ONE UNDER A DEEPER DESCENDANT OF SOME FRONTIER CELLS (A DESCENDANT OF AN INTERNAL
    // CELL IS REACHED ONLY THROUGH THE FRONTIER CHILDREN THE SHAPE COVERS, SO IT IS NOT A GIVEN), AND A FOREIGN ONE UNDER A CELL
    // THE SHAPE DOES NOT COVER, WHICH NO SCAN MAY REACH
    final List<String> cells = new ArrayList<>();
    final List<Boolean> frontier = new ArrayList<>();
    final GeoCoveringCellWalk cellWalk = newWalk(WKT);
    while (cellWalk.advance()) {
      cells.add(cellWalk.getToken());
      frontier.add(cellWalk.isFrontier());
    }
    final Set<RID> expected = new HashSet<>();
    int n = 0;
    for (final String cell : cells) {
      final RID rid = new RID(1, n++);
      expected.add(rid);
      perCell.index.put(cell, List.of(rid));
      perRun.index.put(cell, List.of(rid));
      if (n % 7 == 0 && frontier.get(n - 1)) {
        final RID deeper = new RID(2, n);
        expected.add(deeper);
        perCell.index.put(cell + "7q", List.of(deeper));
        perRun.index.put(cell + "7q", List.of(deeper));
      }
    }
    final RID foreign = new RID(9, 9);
    perCell.index.put("zzzzzz", List.of(foreign));
    perRun.index.put("zzzzzz", List.of(foreign));

    final Set<RID> rowsPerCell = drain(new GeoIndexCursor(KEYS, newWalk(WKT), perCell, false));
    final GeoIndexCursor coalescing = new GeoIndexCursor(KEYS, newWalk(WKT), perRun, true);
    final Set<RID> rowsPerRun = drain(coalescing);

    assertThat(rowsPerCell).isEqualTo(expected);
    assertThat(rowsPerRun).isEqualTo(expected);
    assertThat(perCell.openedRuns).isEmpty();
    assertThat(perRun.openedRuns).as("a wide shape has runs of frontier siblings").isNotEmpty();
    assertThat(coalescing.cellsCoalesced()).isPositive();
    assertThat(coalescing.scansOpened()).isEqualTo(perRun.openedCells.size() + perRun.openedRuns.size());
    assertThat(coalescing.scansOpened() + coalescing.cellsCoalesced()).as("every cell is answered by exactly one scan")
        .isEqualTo(cells.size());
    assertThat(coalescing.scansOpened()).isLessThan(perCell.openedCells.size());
    for (final String run : perRun.openedRuns) {
      // A RUN IS BOUNDED BY TWO SIBLINGS OF THE SAME PARENT, THE FIRST BEFORE THE LAST IN GEOHASH ORDER
      final String[] bounds = run.split("\\.\\.");
      assertThat(bounds[0].length()).isEqualTo(bounds[1].length());
      assertThat(bounds[0].substring(0, bounds[0].length() - 1)).isEqualTo(bounds[1].substring(0, bounds[1].length() - 1));
      assertThat(GeoIndexCursor.GEOHASH_ALPHABET.indexOf(bounds[0].charAt(bounds[0].length() - 1)))
          .isLessThan(GeoIndexCursor.GEOHASH_ALPHABET.indexOf(bounds[1].charAt(bounds[1].length() - 1)));
    }
  }

  @Test
  void aCursorBuiltWithoutCoalescingNeverOpensARun() {
    final FakeIndexFactory factory = new FakeIndexFactory();
    final List<String> cells = tokens(WKT);
    for (int i = 0; i < cells.size(); i++)
      factory.index.put(cells.get(i), List.of(new RID(1, i)));

    final GeoIndexCursor cursor = new GeoIndexCursor(KEYS, newWalk(WKT), factory);
    assertThat(drain(cursor)).hasSize(cells.size());
    assertThat(factory.openedRuns).isEmpty();
    assertThat(factory.openedCells).hasSize(cells.size());
    assertThat(cursor.cellsCoalesced()).isZero();
  }

  @Test
  void aConsumerThatStopsEarlyLeavesTheRestOfTheWalkUntouched() {
    final FakeIndexFactory factory = new FakeIndexFactory();
    final List<String> cells = tokens(WKT);
    for (int i = 0; i < cells.size(); i++)
      factory.index.put(cells.get(i), List.of(new RID(1, i)));

    final GeoIndexCursor cursor = new GeoIndexCursor(KEYS, newWalk(WKT), factory, true);
    assertThat(cursor.hasNext()).isTrue();
    cursor.next();
    assertThat(cursor.scansOpened()).isEqualTo(1);
    assertThat(factory.openedCells.size() + factory.openedRuns.size()).isEqualTo(1);
    cursor.close();
    assertThat(cursor.hasNext()).isFalse();
    assertThat(cursor.seenSize()).isZero();
  }

  private static Set<RID> drain(final GeoIndexCursor cursor) {
    final Set<RID> rows = new HashSet<>();
    while (cursor.hasNext())
      rows.add(cursor.next().getIdentity());
    return rows;
  }

  private static GeoCoveringCellWalk newWalk(final String wkt) {
    final Shape shape = GeoUtils.parseGeometry(wkt);
    final GeohashPrefixTree grid = new GeohashPrefixTree(GeoUtils.getSpatialContext(), 11);
    return new GeoCoveringCellWalk(grid.getTreeCellIterator(shape, 5));
  }

  private static List<String> tokens(final String wkt) {
    final GeoCoveringCellWalk walk = newWalk(wkt);
    final List<String> tokens = new ArrayList<>();
    while (walk.advance())
      tokens.add(walk.getToken());
    return tokens;
  }
}
