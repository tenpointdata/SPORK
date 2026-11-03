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
import com.arcadedb.database.Document;
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.GeoIndexMetadata;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.TypeIndexBuilder;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.io.GeohashUtils;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.Shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The best-first nearest-neighbour walk of the geohash cells of a geospatial index: the answer is the brute-force answer,
 * and it is reached without loading the type.
 */
class GeoNearestSearchTest extends TestHelper {
  private static final double EPS = 1e-9;

  @Test
  void geohashDecodingMatchesTheGrid() {
    final Random random = new Random(7);
    final double[] box = new double[4];
    for (int i = 0; i < 500; i++) {
      final double lon = random.nextDouble() * 360 - 180;
      final double lat = random.nextDouble() * 180 - 90;
      final String hash = GeohashUtils.encodeLatLon(lat, lon, 1 + random.nextInt(11));
      final Rectangle expected = GeohashUtils.decodeBoundary(hash, GeoUtils.getSpatialContext());

      assertThat(GeoNearestSearch.decodeGeohash(hash, box)).isTrue();
      assertThat(box[0]).isCloseTo(expected.getMinX(), within(EPS));
      assertThat(box[1]).isCloseTo(expected.getMaxX(), within(EPS));
      assertThat(box[2]).isCloseTo(expected.getMinY(), within(EPS));
      assertThat(box[3]).isCloseTo(expected.getMaxY(), within(EPS));
    }
    // the leaf marker of the legacy FULL layout is not part of the cell
    assertThat(GeoNearestSearch.decodeGeohash("u0+", box)).isTrue();
    final Rectangle u0 = GeohashUtils.decodeBoundary("u0", GeoUtils.getSpatialContext());
    assertThat(box[0]).isCloseTo(u0.getMinX(), within(EPS));
    assertThat(GeoNearestSearch.decodeGeohash("u0!", box)).isFalse();
  }

  /**
   * The priority of a cell must never exceed the distance of any point inside it, or the walk could stop before a nearer
   * record was met. Checked against a dense sample of each cell, edges and corners included, from everywhere on the globe.
   */
  @Test
  void cellLowerBoundNeverExceedsTheDistanceOfAPointInside() {
    final Random random = new Random(11);
    for (int i = 0; i < 20000; i++) {
      final double qLon = random.nextDouble() * 360 - 180;
      final double qLat = random.nextDouble() * 180 - 90;
      final double cLon = random.nextDouble() * 360 - 180;
      final double cLat = random.nextDouble() * 180 - 90;
      final String hash = GeohashUtils.encodeLatLon(cLat, cLon, 1 + random.nextInt(5));
      final double[] box = new double[4];
      GeoNearestSearch.decodeGeohash(hash, box);

      final double bound = GeoNearestSearch.minRadiansToRectangle(qLon, qLat, box[0], box[1], box[2], box[3]);
      double sampledMin = Double.MAX_VALUE;
      for (int x = 0; x <= 20; x++)
        for (int y = 0; y <= 20; y++) {
          final double lon = box[0] + (box[1] - box[0]) * x / 20.0;
          final double lat = box[2] + (box[3] - box[2]) * y / 20.0;
          sampledMin = Math.min(sampledMin, GeoUtils.haversineRadians(qLon, qLat, lon, lat));
        }
      assertThat(bound).as("cell %s from (%s, %s)", hash, qLon, qLat).isLessThanOrEqualTo(sampledMin + EPS);
      // and it is a useful bound, not a constant 0: within the sampling step of the true minimum
      final double step = GeoUtils.haversineRadians(box[0], box[2], box[1], box[3]) / 20.0;
      assertThat(bound).as("cell %s from (%s, %s)", hash, qLon, qLat).isGreaterThanOrEqualTo(sampledMin - step - EPS);
    }
  }

  @Test
  void nearestMatchesBruteForce() {
    final List<double[]> points = createPoints("Spot", 4000, 23);

    final Random random = new Random(5);
    for (int q = 0; q < 25; q++) {
      final double lon = -100 + random.nextDouble() * 20;
      final double lat = 30 + random.nextDouble() * 15;
      for (final int k : new int[] { 1, 10, 100 }) {
        final List<GeoNearestSearch.Hit> hits = nearest("Spot", lon, lat, k, Double.POSITIVE_INFINITY, null);
        assertThat(radians(hits)).as("k=%d from (%s, %s)", k, lon, lat).hasSize(k);
        final double[] expected = bruteForce(points, lon, lat, k);
        final double[] actual = radians(hits);
        for (int i = 0; i < k; i++)
          assertThat(actual[i]).isCloseTo(expected[i], within(EPS));
      }
    }
  }

  @Test
  void stopsAtTheMaximumDistance() {
    final List<double[]> points = createPoints("Spot", 2000, 3);
    final double maxRadians = 50.0 / GeoUtils.EARTH_RADIUS_KM; // 50 km

    final List<GeoNearestSearch.Hit> hits = nearest("Spot", -90, 38, 1000, maxRadians, null);
    final long expected = Arrays.stream(bruteForce(points, -90, 38, points.size())).filter(d -> d <= maxRadians).count();
    assertThat(hits).hasSize((int) expected);
    for (final GeoNearestSearch.Hit hit : hits)
      assertThat(hit.distanceRadians()).isLessThanOrEqualTo(maxRadians);
  }

  /**
   * The point of the walk: a k-NN over 20,000 records loads a handful of them, not the type.
   */
  @Test
  void loadsAFewRecordsNotTheType() {
    createPoints("Spot", 20000, 31);
    final AtomicInteger loaded = new AtomicInteger();
    final List<GeoNearestSearch.Hit> hits = nearest("Spot", -91.5, 37.2, 10, Double.POSITIVE_INFINITY, loaded);
    assertThat(hits).hasSize(10);
    assertThat(loaded.get()).isLessThan(100);
  }

  @Test
  void crossesTheAntimeridian() {
    database.command("sql", "CREATE DOCUMENT TYPE Spot");
    database.command("sql", "CREATE PROPERTY Spot.coords STRING");
    database.command("sql", "CREATE INDEX ON Spot (coords) GEOSPATIAL");
    database.transaction(() -> {
      database.newDocument("Spot").set("name", "east").set("coords", "POINT (179.9 0)").save();
      database.newDocument("Spot").set("name", "west").set("coords", "POINT (-179.7 0)").save();
      database.newDocument("Spot").set("name", "far").set("coords", "POINT (170 0)").save();
    });

    final List<GeoNearestSearch.Hit> hits = nearest("Spot", -179.95, 0, 2, Double.POSITIVE_INFINITY, null);
    assertThat(hits.stream().map(h -> h.record().getString("name")).toList()).containsExactly("east", "west");
  }

  /**
   * A polygon is measured from its closest edge, and is met through the coarse cells it is indexed under.
   */
  @Test
  void measuresShapesFromTheirClosestPoint() {
    database.command("sql", "CREATE DOCUMENT TYPE Area");
    database.command("sql", "CREATE PROPERTY Area.coords STRING");
    database.command("sql", "CREATE INDEX ON Area (coords) GEOSPATIAL");
    database.transaction(() -> {
      // a wide box 1 degree east of the query point: its edge is nearer than the centre of the point cloud
      database.newDocument("Area").set("name", "box").set("coords", "POLYGON ((11 -5, 30 -5, 30 5, 11 5, 11 -5))").save();
      database.newDocument("Area").set("name", "p1").set("coords", "POINT (8 0)").save();
      database.newDocument("Area").set("name", "p2").set("coords", "POINT (10.5 0)").save();
      database.newDocument("Area").set("name", "inside").set("coords", "POINT (20 0)").save();
    });

    final List<GeoNearestSearch.Hit> hits = nearest("Area", 10, 0, 4, Double.POSITIVE_INFINITY, null);
    assertThat(hits.stream().map(h -> h.record().getString("name")).toList()).containsExactly("p2", "box", "p1", "inside");
    assertThat(hits.get(1).distanceRadians()).isCloseTo(Math.toRadians(1), within(1e-6));
  }

  @Test
  void legacyFullLayoutAnswersTheSame() {
    database.command("sql", "CREATE DOCUMENT TYPE Legacy");
    database.command("sql", "CREATE PROPERTY Legacy.coords STRING");
    final TypeIndexBuilder builder = database.getSchema().buildTypeIndex("Legacy", new String[] { "coords" });
    builder.withType(Schema.INDEX_TYPE.GEOSPATIAL);
    final GeoIndexMetadata meta = new GeoIndexMetadata("Legacy", new String[] { "coords" }, -1);
    meta.setTokenization(GeoIndexMetadata.TOKENIZATION.FULL);
    builder.withMetadata(meta);
    builder.create();

    final List<double[]> points = insertPoints("Legacy", 1500, 41);
    final List<GeoNearestSearch.Hit> hits = nearest("Legacy", -95, 40, 25, Double.POSITIVE_INFINITY, null);
    final double[] expected = bruteForce(points, -95, 40, 25);
    final double[] actual = radians(hits);
    for (int i = 0; i < 25; i++)
      assertThat(actual[i]).isCloseTo(expected[i], within(EPS));
  }

  @Test
  void skipsWhatTheResolverRejects() {
    final List<double[]> points = createPoints("Spot", 1000, 13);
    // keep only the even ids: the answer is the brute force over them
    final List<double[]> even = new ArrayList<>();
    for (final double[] p : points)
      if (((int) p[2]) % 2 == 0)
        even.add(p);

    final List<LSMTreeGeoIndex> indexes = bucketIndexes("Spot");
    final Point query = GeoUtils.getSpatialContext().getShapeFactory().pointXY(-92, 39);
    final List<GeoNearestSearch.Hit> hits = GeoNearestSearch.search(indexes, -92, 39, 20, Double.POSITIVE_INFINITY, rid -> {
      final Document doc = rid.asDocument();
      return doc.getInteger("id") % 2 == 0 ? new GeoNearestSearch.Hit(doc, distance(query, doc)) : null;
    }, null);

    final double[] expected = bruteForce(even, -92, 39, 20);
    final double[] actual = radians(hits);
    assertThat(actual).hasSize(20);
    for (int i = 0; i < 20; i++)
      assertThat(actual[i]).isCloseTo(expected[i], within(EPS));
  }

  // ---- helpers ----

  private List<double[]> createPoints(final String type, final int count, final long seed) {
    database.command("sql", "CREATE DOCUMENT TYPE " + type);
    database.command("sql", "CREATE PROPERTY " + type + ".coords STRING");
    database.command("sql", "CREATE INDEX ON " + type + " (coords) GEOSPATIAL");
    return insertPoints(type, count, seed);
  }

  /**
   * Points over the continental US, a third of them packed into three small clusters so the walk meets both sparse and
   * dense cells. Each is {lon, lat, id}.
   */
  private List<double[]> insertPoints(final String type, final int count, final long seed) {
    final Random random = new Random(seed);
    final double[][] clusters = { { -96.8, 32.8 }, { -87.6, 41.9 }, { -118.2, 34.0 } };
    final List<double[]> points = new ArrayList<>(count);
    database.transaction(() -> {
      for (int i = 0; i < count; i++) {
        final double lon;
        final double lat;
        if (i % 3 == 0) {
          final double[] c = clusters[random.nextInt(clusters.length)];
          lon = c[0] + random.nextGaussian() * 0.05;
          lat = c[1] + random.nextGaussian() * 0.05;
        } else {
          lon = -124 + random.nextDouble() * 57;
          lat = 25 + random.nextDouble() * 24;
        }
        points.add(new double[] { lon, lat, i });
        database.newDocument(type).set("id", i).set("coords", "POINT (" + lon + " " + lat + ")").save();
      }
    });
    return points;
  }

  private List<LSMTreeGeoIndex> bucketIndexes(final String type) {
    final List<LSMTreeGeoIndex> indexes = new ArrayList<>();
    for (final IndexInternal index : ((TypeIndex) database.getSchema().getIndexByName(type + "[coords]")).getIndexesOnBuckets())
      indexes.add((LSMTreeGeoIndex) index);
    return indexes;
  }

  private List<GeoNearestSearch.Hit> nearest(final String type, final double lon, final double lat, final int k,
      final double maxRadians, final AtomicInteger loaded) {
    final Point query = GeoUtils.getSpatialContext().getShapeFactory().pointXY(lon, lat);
    return GeoNearestSearch.search(bucketIndexes(type), lon, lat, k, maxRadians, rid -> {
      if (loaded != null)
        loaded.incrementAndGet();
      final Document doc = rid.asDocument();
      return new GeoNearestSearch.Hit(doc, distance(query, doc));
    }, null);
  }

  private static double distance(final Point query, final Document doc) {
    final Shape shape = GeoUtils.parseGeometry(doc.getString("coords"));
    if (shape instanceof Point p)
      return GeoUtils.haversineRadians(query.getX(), query.getY(), p.getX(), p.getY());
    return Math.toRadians(GeoUtils.distanceDegrees(query, shape));
  }

  private static double[] bruteForce(final List<double[]> points, final double lon, final double lat, final int k) {
    final double[] all = new double[points.size()];
    for (int i = 0; i < all.length; i++)
      all[i] = GeoUtils.haversineRadians(lon, lat, points.get(i)[0], points.get(i)[1]);
    Arrays.sort(all);
    return Arrays.copyOf(all, Math.min(k, all.length));
  }

  private static double[] radians(final List<GeoNearestSearch.Hit> hits) {
    final double[] out = new double[hits.size()];
    for (int i = 0; i < out.length; i++)
      out[i] = hits.get(i).distanceRadians();
    return out;
  }
}
