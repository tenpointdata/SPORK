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

import com.arcadedb.TestHelper;
import com.arcadedb.database.Database;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.GeoIndexMetadata;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.TypeIndexBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.spatial4j.io.GeohashUtils;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.Shape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The geospatial index inside an open transaction, where two changes meet: a frontier cell scan merges the entries the
 * transaction queued (geo kNN), and adjacent frontier cells share one range scan (S12, {@link LSMTreeGeoIndex#openFrontierRunCursor}).
 * Neither change was tested with the other.
 * <p>
 * The records are placed under the exact cells the query reads: inside a coalesced run of frontier cells, on the first and
 * last cell of the run (at their corners), in the gap between two covering cells that are not adjacent, just past the end
 * of a run, and outside the shape. One committed record inside a run is deleted in the transaction, one is moved within the
 * run, one is moved out of the shape. Every answer - the index cursor, geo.within, geo.dWithinMeters, geo.nearest and the
 * Cypher geo seek - must equal a brute force over the committed-plus-pending records: an unindexed twin type holding the
 * same records and receiving the same changes, scanned in the same transaction, or a distance sort in the test.
 */
class LSMTreeGeoIndexPendingTransactionTest extends TestHelper {
  private static final String SHAPE      = "POLYGON ((10 10, 10.37 10, 10.37 10.23, 10 10.23, 10 10))";
  private static final double CENTRE_LON = 10.185;
  private static final double CENTRE_LAT = 10.115;
  private static final int    RADIUS_M   = 15_000;

  private static final int DELETED   = 100_001;
  private static final int MOVED_IN  = 100_002;
  private static final int MOVED_OUT = 100_003;

  /** id to {lon, lat} of every live record, committed and pending: the brute force reads it. */
  private final Map<Integer, double[]> model = new TreeMap<>();
  private       Cover                  cover;

  static Stream<Arguments> layoutsAndIsolations() {
    final List<Arguments> out = new ArrayList<>();
    for (final GeoIndexMetadata.TOKENIZATION layout : GeoIndexMetadata.TOKENIZATION.values())
      for (final Database.TRANSACTION_ISOLATION_LEVEL isolation : new Database.TRANSACTION_ISOLATION_LEVEL[] {
          Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED, Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ })
        out.add(Arguments.of(layout, isolation));
    return out.stream();
  }

  /**
   * The coalesced cursor and the one-scan-per-cell cursor answer the same RIDs, and exactly the records under the frontier
   * covering cells: the pending ones inside the run, none in the gap or past the run, not the deleted or moved-out one, and
   * the record moved within the run once although it has a committed and a pending entry.
   */
  @ParameterizedTest
  @EnumSource(value = Database.TRANSACTION_ISOLATION_LEVEL.class, names = { "READ_COMMITTED", "REPEATABLE_READ" })
  void aCoalescedRunAnswersThePendingRecordsExactlyAsOneScanPerCell(final Database.TRANSACTION_ISOLATION_LEVEL isolation) {
    setUp(GeoIndexMetadata.TOKENIZATION.FRONTIER, true);
    final Map<Integer, double[]> committed = new TreeMap<>(model);
    final Shape shape = GeoUtils.parseGeometry(SHAPE);
    database.begin(isolation);
    try {
      final Map<RID, Integer> rids = applyPendingChanges();
      final Set<Integer> expected = underFrontierCells();

      int coalesced = 0;
      final List<Integer> runs = new ArrayList<>();
      final List<Integer> cells = new ArrayList<>();
      for (final LSMTreeGeoIndex index : bucketIndexes()) {
        final GeoIndexCursor run = index.newCursor(new Object[] { shape }, shape, true);
        runs.addAll(ids(run, rids));
        coalesced += run.cellsCoalesced();
        cells.addAll(ids(index.newCursor(new Object[] { shape }, shape, false), rids));
      }

      assertThat(coalesced).as("the query must coalesce a run of frontier cells").isGreaterThan(0);
      assertThat(runs).doesNotHaveDuplicates();
      assertThat(cells).doesNotHaveDuplicates();
      assertThat(new HashSet<>(runs)).isEqualTo(new HashSet<>(cells)).isEqualTo(expected);
      assertThat(expected).contains(MOVED_IN, 1, 2, 3, 4).doesNotContain(DELETED, MOVED_OUT, 5, 6, 7);
    } finally {
      database.rollback();
    }

    // nothing of the transaction survives the rollback: the committed records only, the deleted and moved ones where they were
    model.clear();
    model.putAll(committed);
    database.transaction(() -> {
      final Map<RID, Integer> rids = ridsById();
      final Set<Integer> found = new HashSet<>();
      for (final LSMTreeGeoIndex index : bucketIndexes())
        found.addAll(ids(index.newCursor(new Object[] { shape }, shape, true), rids));
      assertThat(found).isEqualTo(underFrontierCells()).contains(DELETED, MOVED_IN, MOVED_OUT).doesNotContain(1, 2, 3, 4);
    });
  }

  /**
   * The FULL layout reads every covering cell with an exact lookup, which merges the pending entries itself: through
   * {@code get()} it answers every pending record under the frontier cells and none the transaction deleted or moved away.
   */
  @ParameterizedTest
  @EnumSource(value = Database.TRANSACTION_ISOLATION_LEVEL.class, names = { "READ_COMMITTED", "REPEATABLE_READ" })
  void theFullLayoutAnswersThePendingRecordsThroughGet(final Database.TRANSACTION_ISOLATION_LEVEL isolation) {
    setUp(GeoIndexMetadata.TOKENIZATION.FULL, true);
    database.begin(isolation);
    try {
      final Map<RID, Integer> rids = applyPendingChanges();
      final List<Integer> found = new ArrayList<>();
      for (final LSMTreeGeoIndex index : bucketIndexes())
        found.addAll(ids(index.get(new Object[] { SHAPE }), rids));
      assertThat(found).doesNotHaveDuplicates();
      // a superset (an exact lookup of an ANCESTOR covering cell answers every record below it, which is how the record moved
      // out of the shape can still come back as a candidate), but never the record the transaction deleted
      assertThat(new HashSet<>(found)).containsAll(underFrontierCells()).doesNotContain(DELETED);
    } finally {
      database.rollback();
    }
  }

  @ParameterizedTest
  @MethodSource("layoutsAndIsolations")
  void sqlPredicatesAnswerTheBruteForce(final GeoIndexMetadata.TOKENIZATION layout,
      final Database.TRANSACTION_ISOLATION_LEVEL isolation) {
    setUp(layout, true);
    database.begin(isolation);
    try {
      applyPendingChanges();
      final String within = "geo.within(loc, '" + SHAPE + "') = true";
      final String radius = "geo.dWithinMeters(loc, 'POINT (" + CENTRE_LON + " " + CENTRE_LAT + ")', " + RADIUS_M + ") = true";
      assertThat(bruteForceInBox()).as("the run must hold pending records inside the shape").containsAnyOf(1, 2, 3, 4, MOVED_IN);
      for (final String predicate : new String[] { within, radius })
        assertThat(explain("SELECT id FROM Place WHERE " + predicate)).as(predicate).contains("FETCH FROM INDEXED FUNCTION");
      assertThat(sqlIds("Place", within)).isEqualTo(sqlIds("Plain", within)).isEqualTo(bruteForceInBox());
      assertThat(sqlIds("Place", radius)).isEqualTo(sqlIds("Plain", radius)).isEqualTo(bruteForceInRadius());
    } finally {
      database.rollback();
    }
  }

  @ParameterizedTest
  @MethodSource("layoutsAndIsolations")
  void nearestAnswersTheBruteForce(final GeoIndexMetadata.TOKENIZATION layout,
      final Database.TRANSACTION_ISOLATION_LEVEL isolation) {
    setUp(layout, true);
    final double[] deletedAt = model.get(DELETED).clone();
    database.begin(isolation);
    try {
      applyPendingChanges();
      // from where the deleted record was, and from the centre of the shape
      for (final double[] from : new double[][] { deletedAt, { CENTRE_LON, CENTRE_LAT } }) {
        final List<Result> rows = new ArrayList<>();
        try (final ResultSet rs = database.query("sql",
            "SELECT id, distance FROM (SELECT expand(geo.nearest('Place[loc]', 'POINT (" + from[0] + " " + from[1] + ")', 15)))")) {
          while (rs.hasNext())
            rows.add(rs.next());
        }
        final List<Map.Entry<Integer, Double>> expected = bruteForceNearest(from, 15);
        assertThat(rows.stream().map(r -> r.<Integer>getProperty("id")).toList())
            .isEqualTo(expected.stream().map(Map.Entry::getKey).toList());
        for (int i = 0; i < rows.size(); i++)
          assertThat(rows.get(i).<Double>getProperty("distance")).isCloseTo(expected.get(i).getValue(), within(1e-3));
      }
    } finally {
      database.rollback();
    }
  }

  @ParameterizedTest
  @MethodSource("layoutsAndIsolations")
  void cypherGeoSeekAnswersTheBruteForce(final GeoIndexMetadata.TOKENIZATION layout,
      final Database.TRANSACTION_ISOLATION_LEVEL isolation) {
    setUp(layout, true);
    database.begin(isolation);
    try {
      applyPendingChanges();
      final String[] wheres = {
          "WHERE point.distance(n.loc, point({longitude: " + CENTRE_LON + ", latitude: " + CENTRE_LAT + "})) < " + RADIUS_M,
          "WHERE point.withinBBox(n.loc, point({longitude: 10.0, latitude: 10.0}), point({longitude: 10.37, latitude: 10.23}))" };
      for (final String where : wheres) {
        assertThat(cypher("EXPLAIN MATCH (n:Place) " + where + " RETURN n.id AS id").getFirst().toJSON().toString()).as(where)
            .contains("NodeGeoIndexSeek");
        assertThat(cypherIds("MATCH (n:Place) " + where + " RETURN n.id AS id")).as(where)
            .isEqualTo(cypherIds("MATCH (n:Plain) " + where + " RETURN n.id AS id"));
      }
      assertThat(cypherIds("MATCH (n:Place) " + wheres[0] + " RETURN n.id AS id")).isEqualTo(bruteForceInRadius());
      assertThat(cypherIds("MATCH (n:Place) " + wheres[1] + " RETURN n.id AS id")).isEqualTo(bruteForceInBox());
    } finally {
      database.rollback();
    }
  }

  /** The smallest case of the delete: a committed point deleted in the transaction, then queried over in it. */
  @Test
  void aPointDeletedInTheTransactionIsNotAnswered() {
    setUp(GeoIndexMetadata.TOKENIZATION.FRONTIER, true);
    final double[] p = model.get(DELETED);
    final String around = "POLYGON ((" + (p[0] - 0.001) + " " + (p[1] - 0.001) + ", " + (p[0] + 0.001) + " " + (p[1] - 0.001) + ", "
        + (p[0] + 0.001) + " " + (p[1] + 0.001) + ", " + (p[0] - 0.001) + " " + (p[1] + 0.001) + ", " + (p[0] - 0.001) + " "
        + (p[1] - 0.001) + "))";
    database.transaction(() -> {
      assertThat(sqlIds("Place", "geo.within(loc, '" + around + "') = true")).contains(DELETED);
      database.command("sql", "DELETE FROM Place WHERE id = " + DELETED);
      assertThat(sqlIds("Place", "geo.within(loc, '" + around + "') = true")).doesNotContain(DELETED);
      assertThat(sqlIds("Place", "geo.within(loc, '" + SHAPE + "') = true")).doesNotContain(DELETED);
    });
  }

  /** No committed entry at all: every candidate comes from the transaction, on every bucket's index. */
  @ParameterizedTest
  @EnumSource(GeoIndexMetadata.TOKENIZATION.class)
  void anIndexWithNothingCommittedAnswersThePendingRecords(final GeoIndexMetadata.TOKENIZATION layout) {
    setUp(layout, false);
    database.transaction(() -> {
      applyPendingChanges();
      final String within = "geo.within(loc, '" + SHAPE + "') = true";
      assertThat(sqlIds("Place", within)).isEqualTo(bruteForceInBox()).isNotEmpty();
      final List<Map.Entry<Integer, Double>> expected = bruteForceNearest(new double[] { CENTRE_LON, CENTRE_LAT }, 5);
      final List<Integer> nearest = new ArrayList<>();
      try (final ResultSet rs = database.query("sql",
          "SELECT id FROM (SELECT expand(geo.nearest('Place[loc]', 'POINT (" + CENTRE_LON + " " + CENTRE_LAT + ")', 5)))")) {
        while (rs.hasNext())
          nearest.add(rs.next().getProperty("id"));
      }
      assertThat(nearest).isEqualTo(expected.stream().map(Map.Entry::getKey).toList());
    });
  }

  // ---- fixture ----

  /**
   * The covering cells of {@link #SHAPE} as {@link GeoIndexCursor} reads them: the longest run of adjacent frontier cells, a
   * gap cell between two frontier siblings that are not adjacent, and the sibling right after a run when it is not covered.
   */
  private record Cover(List<String> frontier, List<String> run, String gap, String pastRun) {
  }

  private void setUp(final GeoIndexMetadata.TOKENIZATION layout, final boolean withCommittedRecords) {
    for (final String type : new String[] { "Place", "Plain" }) {
      database.command("sql", "CREATE VERTEX TYPE " + type);
      database.command("sql", "CREATE PROPERTY " + type + ".loc STRING");
    }
    final TypeIndexBuilder builder = database.getSchema().buildTypeIndex("Place", new String[] { "loc" });
    builder.withType(Schema.INDEX_TYPE.GEOSPATIAL);
    final GeoIndexMetadata meta = new GeoIndexMetadata("Place", new String[] { "loc" }, -1);
    meta.setTokenization(layout);
    builder.withMetadata(meta);
    builder.create();

    cover = findCover();

    final Random random = new Random(29);
    final List<double[]> committed = new ArrayList<>();
    for (int i = 0; i < 400; i++)
      committed.add(new double[] { 1000 + i, 9.9 + random.nextDouble() * 0.6, 9.9 + random.nextDouble() * 0.45 });
    final String middle = cover.run().get(cover.run().size() / 2);
    committed.add(at(DELETED, cell(middle), 0.3, 0.6));
    committed.add(at(MOVED_IN, cell(cover.run().getFirst()), 0.7, 0.2));
    committed.add(at(MOVED_OUT, cell(cover.run().getLast()), 0.4, 0.4));
    if (withCommittedRecords)
      database.transaction(() -> {
        for (final double[] p : committed)
          insert((int) p[0], p[1], p[2]);
      });
  }

  /**
   * Queues, in the open transaction, the pending inserts, the delete and the two moves on both types, and answers the RIDs
   * of the indexed type by id.
   */
  private Map<RID, Integer> applyPendingChanges() {
    final Rectangle first = cell(cover.run().getFirst());
    final Rectangle last = cell(cover.run().getLast());
    // 1, 2: on the first and the last cell of the run, at their outer corners
    insert(at(1, first, 0.001, 0.001));
    insert(at(2, last, 0.999, 0.999));
    // 3, 4: inside the run, one in the middle of a cell of it
    insert(at(3, cell(cover.run().get(cover.run().size() / 2)), 0.5, 0.5));
    insert(at(4, first, 0.5, 0.5));
    // 5: in the gap between two frontier siblings that are not adjacent
    insert(at(5, cell(cover.gap()), 0.5, 0.5));
    // 6: just past the run, when that sibling is not covered; else, like 7, well outside the shape
    insert(cover.pastRun() != null ? at(6, cell(cover.pastRun()), 0.001, 0.001) : new double[] { 6, 12.5, 12.5 });
    insert(new double[] { 7, 11.5, 11.5 });
    final Random random = new Random(31);
    for (int i = 0; i < 60; i++)
      insert(10 + i, 9.9 + random.nextDouble() * 0.6, 9.9 + random.nextDouble() * 0.45);

    // a committed record in the run deleted, one moved within the run, one moved out of the shape
    for (final String type : new String[] { "Place", "Plain" }) {
      database.command("sql", "DELETE FROM " + type + " WHERE id = " + DELETED);
      final double[] in = at(MOVED_IN, last, 0.2, 0.8);
      database.command("sql", "UPDATE " + type + " SET loc = 'POINT (" + in[1] + " " + in[2] + ")' WHERE id = " + MOVED_IN);
      database.command("sql", "UPDATE " + type + " SET loc = 'POINT (12 12)' WHERE id = " + MOVED_OUT);
    }
    model.remove(DELETED);
    if (model.containsKey(MOVED_IN)) {
      final double[] in = at(MOVED_IN, last, 0.2, 0.8);
      model.put(MOVED_IN, new double[] { in[1], in[2] });
      model.put(MOVED_OUT, new double[] { 12, 12 });
    }
    return ridsById();
  }

  private Map<RID, Integer> ridsById() {
    final Map<RID, Integer> rids = new HashMap<>();
    try (final ResultSet rs = database.query("sql", "SELECT @rid AS rid, id FROM Place")) {
      while (rs.hasNext()) {
        final Result r = rs.next();
        rids.put(r.<Identifiable>getProperty("rid").getIdentity(), r.getProperty("id"));
      }
    }
    return rids;
  }

  private Cover findCover() {
    final LSMTreeGeoIndex index = bucketIndexes().getFirst();
    final List<String> tokens = new ArrayList<>();
    final List<Boolean> frontiers = new ArrayList<>();
    final GeoCoveringCellWalk walk = index.newCoveringCellWalk(GeoUtils.parseGeometry(SHAPE));
    while (walk.advance()) {
      tokens.add(walk.getToken());
      frontiers.add(walk.isFrontier());
    }
    final Set<String> covering = new HashSet<>(tokens);
    final List<String> frontier = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++)
      if (frontiers.get(i))
        frontier.add(tokens.get(i));

    // the runs GeoIndexCursor forms, greedily from the first frontier cell of each scan
    List<String> longest = List.of();
    String pastRun = null;
    for (int i = 0; i < tokens.size(); ) {
      if (!frontiers.get(i)) {
        i++;
        continue;
      }
      int j = i;
      while (j + 1 < tokens.size() && frontiers.get(j + 1) && GeoIndexCursor.adjacentSiblings(tokens.get(j), tokens.get(j + 1)))
        j++;
      if (j > i) {
        final String next = nextSibling(tokens.get(j));
        if (j - i + 1 > longest.size() || (pastRun == null && next != null && !covering.contains(next))) {
          longest = tokens.subList(i, j + 1);
          pastRun = next != null && !covering.contains(next) ? next : null;
        }
      }
      i = j + 1;
    }
    assertThat(longest).as("the shape must cover a run of adjacent frontier cells").hasSizeGreaterThanOrEqualTo(2);

    String gap = null;
    for (int i = 0; i + 1 < tokens.size() && gap == null; i++) {
      final String a = tokens.get(i);
      final String b = tokens.get(i + 1);
      if (frontiers.get(i) && frontiers.get(i + 1) && a.length() == b.length() && a.regionMatches(0, b, 0, a.length() - 1)
          && !GeoIndexCursor.adjacentSiblings(a, b))
        gap = nextSibling(a);
    }
    assertThat(gap).as("the shape must leave a gap between two covering siblings").isNotNull();
    assertThat(covering).doesNotContain(gap);
    return new Cover(frontier, List.copyOf(longest), gap, pastRun);
  }

  private static String nextSibling(final String token) {
    final int at = GeoIndexCursor.GEOHASH_ALPHABET.indexOf(token.charAt(token.length() - 1));
    return at + 1 < GeoIndexCursor.GEOHASH_ALPHABET.length() ?
        token.substring(0, token.length() - 1) + GeoIndexCursor.GEOHASH_ALPHABET.charAt(at + 1) :
        null;
  }

  private static Rectangle cell(final String token) {
    return GeohashUtils.decodeBoundary(token, GeoUtils.getSpatialContext());
  }

  /** {id, lon, lat} of the point at the fractions {@code fx, fy} of the cell. */
  private static double[] at(final int id, final Rectangle cell, final double fx, final double fy) {
    return new double[] { id, cell.getMinX() + cell.getWidth() * fx, cell.getMinY() + cell.getHeight() * fy };
  }

  private void insert(final double[] p) {
    insert((int) p[0], p[1], p[2]);
  }

  private void insert(final int id, final double lon, final double lat) {
    final String wkt = "POINT (" + lon + " " + lat + ")";
    database.newVertex("Place").set("id", id).set("loc", wkt).save();
    database.newVertex("Plain").set("id", id).set("loc", wkt).save();
    model.put(id, new double[] { lon, lat });
  }

  /** The live records whose point lies under a frontier covering cell: what the FRONTIER index answers. */
  private Set<Integer> underFrontierCells() {
    final int precision = bucketIndexes().getFirst().getPrecision();
    final Set<Integer> out = new HashSet<>();
    for (final Map.Entry<Integer, double[]> e : model.entrySet()) {
      final String hash = GeohashUtils.encodeLatLon(e.getValue()[1], e.getValue()[0], precision);
      for (final String token : cover.frontier())
        if (hash.startsWith(token)) {
          out.add(e.getKey());
          break;
        }
    }
    return out;
  }

  /** {@link #SHAPE} is the box [10, 10.37] x [10, 10.23]. */
  private Set<Integer> bruteForceInBox() {
    final Set<Integer> out = new HashSet<>();
    for (final Map.Entry<Integer, double[]> e : model.entrySet())
      if (e.getValue()[0] >= 10 && e.getValue()[0] <= 10.37 && e.getValue()[1] >= 10 && e.getValue()[1] <= 10.23)
        out.add(e.getKey());
    return out;
  }

  private Set<Integer> bruteForceInRadius() {
    final Set<Integer> out = new HashSet<>();
    for (final Map.Entry<Integer, double[]> e : model.entrySet())
      if (GeoUtils.radiansToMetres(GeoUtils.haversineRadians(CENTRE_LON, CENTRE_LAT, e.getValue()[0], e.getValue()[1])) < RADIUS_M)
        out.add(e.getKey());
    return out;
  }

  private List<Map.Entry<Integer, Double>> bruteForceNearest(final double[] from, final int k) {
    final List<Map.Entry<Integer, Double>> all = new ArrayList<>();
    for (final Map.Entry<Integer, double[]> e : model.entrySet())
      all.add(Map.entry(e.getKey(),
          GeoUtils.radiansToMetres(GeoUtils.haversineRadians(from[0], from[1], e.getValue()[0], e.getValue()[1]))));
    all.sort(Comparator.comparingDouble(Map.Entry::getValue));
    return all.subList(0, Math.min(k, all.size()));
  }

  private List<LSMTreeGeoIndex> bucketIndexes() {
    final List<LSMTreeGeoIndex> indexes = new ArrayList<>();
    for (final IndexInternal index : ((TypeIndex) database.getSchema().getIndexByName("Place[loc]")).getIndexesOnBuckets())
      indexes.add((LSMTreeGeoIndex) index);
    return indexes;
  }

  private static List<Integer> ids(final IndexCursor cursor, final Map<RID, Integer> rids) {
    final List<Integer> out = new ArrayList<>();
    try (cursor) {
      while (cursor.hasNext())
        out.add(rids.getOrDefault(cursor.next().getIdentity(), -1));
    }
    return out;
  }

  private Set<Integer> sqlIds(final String type, final String predicate) {
    final Set<Integer> out = new LinkedHashSet<>();
    try (final ResultSet rs = database.query("sql", "SELECT id FROM " + type + " WHERE " + predicate)) {
      while (rs.hasNext())
        out.add(rs.next().getProperty("id"));
    }
    return out;
  }

  private String explain(final String sql) {
    try (final ResultSet rs = database.query("sql", "EXPLAIN " + sql)) {
      return rs.next().getProperty("executionPlanAsString");
    }
  }

  private List<Result> cypher(final String query) {
    final List<Result> out = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query)) {
      while (rs.hasNext())
        out.add(rs.next());
    }
    return out;
  }

  private Set<Integer> cypherIds(final String query) {
    final Set<Integer> out = new HashSet<>();
    for (final Result r : cypher(query))
      out.add(r.getProperty("id"));
    return out;
  }
}
