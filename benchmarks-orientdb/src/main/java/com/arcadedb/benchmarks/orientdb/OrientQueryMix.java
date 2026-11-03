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
package com.arcadedb.benchmarks.orientdb;

import com.arcadedb.benchmarks.retrieval.ReconDomain;
import com.arcadedb.benchmarks.retrieval.ZipfSampler;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/**
 * The nine retrieval shapes of {@code QueryMix}, in OrientDB 3.2 SQL, with a parameter sampler that makes the same draws as
 * {@code QueryMix.ParamSampler} (same order, same tables), so a given seed asks both engines the same questions. Query ids
 * match the ArcadeDB ones, so the result tables join on them.
 * <p>
 * Where OrientDB has no equivalent feature the closest thing an OrientDB user would write is used, and named as such:
 * {@link Query#VECTOR_KNN20} is an exact scan of the class with a bounded heap (OrientDB 3.2 has no vector index), and
 * {@link Query#HYDRATE_200} reads the RIDs as the query target, the form OrientDB plans as direct record loads.
 */
public final class OrientQueryMix {
  public static final int    PIVOT_LIMIT      = 500;
  public static final int    SEARCH_LIMIT     = 100;
  public static final int    FEED_LIMIT       = 200;
  public static final int    TEXT_LIMIT       = 20;
  public static final int    KNN_K            = 20;
  public static final int    HYDRATE_COUNT    = 200;
  public static final int    HUB_DEGREE       = 2_000;
  public static final double RADIUS_200KM_DEG = 200.0 / 111.195;

  public enum Query {
    IDENT_LOOKUP("identLookup", "SELECT FROM Phone WHERE key = :key"),
    PIVOT_1HOP("pivot1Hop",
        "SELECT FROM (SELECT expand(in('HAS_PHONE')) FROM Phone WHERE key = :key) ORDER BY tsPosted DESC LIMIT " + PIVOT_LIMIT),
    PIVOT_2HOP("pivot2Hop",
        "SELECT FROM (SELECT expand(in('HAS_PHONE').out('HAS_PHONE','HAS_IMAGE').in('HAS_PHONE','HAS_IMAGE')) FROM Phone WHERE key = :key) LIMIT "
            + PIVOT_LIMIT),
    STRUCTURED("structured",
        "SELECT FROM Record WHERE state = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT "
            + SEARCH_LIMIT),
    FEED_24H("feed24h", "SELECT FROM Record WHERE tsPosted >= :since AND location IS NOT NULL ORDER BY tsPosted DESC LIMIT " + FEED_LIMIT),
    GEO_RADIUS_14D("geoRadius14d",
        "SELECT FROM Record WHERE ST_Intersects(location, ST_GeomFromText(:box)) = true AND ST_DWithin(location, ST_GeomFromText(:point), :radiusDeg) = true AND tsPosted >= :since LIMIT "
            + PIVOT_LIMIT),
    FULLTEXT_TOP20("fulltextTop20", "SELECT FROM Record WHERE SEARCH_INDEX('Record.text', :q) = true ORDER BY $score DESC LIMIT " + TEXT_LIMIT),
    VECTOR_KNN20("vectorKnn20", "exact scan of Record.styleVec with a top-" + KNN_K + " heap (no vector index in OrientDB 3.2)"),
    HYDRATE_200("hydrate200", "SELECT FROM :rids");

    public final String id;
    public final String sql;

    Query(final String id, final String sql) {
      this.id = id;
      this.sql = sql;
    }

    public static Query byId(final String id) {
      for (final Query q : values())
        if (q.id.equals(id))
          return q;
      throw new IllegalArgumentException("Unknown query '" + id + "'");
    }
  }

  private OrientQueryMix() {
  }

  /** Executes one query and returns the number of rows it produced (every row is consumed). */
  public static int execute(final ODatabaseSession db, final Query query, final Map<String, Object> params) {
    if (query == Query.VECTOR_KNN20)
      return exactKnn(db, (float[]) params.get("vector"), KNN_K);
    int rows = 0;
    try (final OResultSet rs = db.query(query.sql, params)) {
      while (rs.hasNext()) {
        rs.next();
        rows++;
      }
    }
    return rows;
  }

  /**
   * Cosine kNN by scanning every Record: what an OrientDB deployment without a vector index has to do. The vectors are unit
   * length, so cosine similarity is the dot product.
   */
  static int exactKnn(final ODatabaseSession db, final float[] query, final int k) {
    final PriorityQueue<double[]> heap = new PriorityQueue<>(k + 1, (a, b) -> Double.compare(a[0], b[0]));
    long seen = 0;
    for (final ODocument doc : db.browseClass(OrientReconGenerator.RECORD)) {
      final byte[] bytes = doc.field("styleVec");
      if (bytes == null)
        continue;
      final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      double dot = 0.0;
      for (final float q : query)
        dot += q * buffer.getFloat();
      if (heap.size() < k)
        heap.add(new double[] { dot, seen });
      else if (dot > heap.peek()[0]) {
        heap.poll();
        heap.add(new double[] { dot, seen });
      }
      seen++;
    }
    return heap.size();
  }

  /** Draws parameters with the same sequence of random calls as {@code QueryMix.ParamSampler}. One per thread. */
  public static final class ParamSampler {
    private final Tables           tables;
    private final SplittableRandom rnd;

    public ParamSampler(final Tables tables, final long seed) {
      this.tables = tables;
      this.rnd = new SplittableRandom(seed);
    }

    public Map<String, Object> params(final Query query) {
      final Map<String, Object> p = new LinkedHashMap<>();
      final OrientDataset m = tables.dataset;
      switch (query) {
      case IDENT_LOOKUP, PIVOT_1HOP -> p.put("key", ReconDomain.phoneKey(tables.phoneZipf.sample(rnd.nextDouble() * tables.referencedPhoneMass)));
      case PIVOT_2HOP -> p.put("key", ReconDomain.phoneKey(
          tables.phoneZipf.sample(tables.hubPhoneMass + rnd.nextDouble() * (tables.referencedPhoneMass - tables.hubPhoneMass))));
      case STRUCTURED -> {
        final ReconDomain.City city = ReconDomain.city(tables.cityZipf.sample(rnd.nextDouble()));
        final int ageMin = 18 + rnd.nextInt(25);
        p.put("state", city.state());
        p.put("ageMin", ageMin);
        p.put("ageMax", ageMin + 4);
        p.put("service", ReconDomain.service(tables.serviceZipf.sample(rnd.nextDouble())));
      }
      case FEED_24H -> p.put("since", m.tsMax - ReconDomain.DAY_MS);
      case GEO_RADIUS_14D -> {
        final ReconDomain.City city = ReconDomain.city(tables.cityZipf.sample(rnd.nextDouble()));
        final double lat = city.lat(), lon = city.lon();
        final double dLat = RADIUS_200KM_DEG;
        final double dLon = RADIUS_200KM_DEG / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        p.put("box", ReconDomain.wktBox(lon - dLon, lat - dLat, lon + dLon, lat + dLat));
        p.put("point", ReconDomain.wktPoint(lon, lat));
        p.put("radiusDeg", RADIUS_200KM_DEG);
        p.put("since", m.tsMax - 14 * ReconDomain.DAY_MS);
      }
      case FULLTEXT_TOP20 -> {
        final int a = 30 + rnd.nextInt(570), b = 30 + rnd.nextInt(570);
        p.put("q", ReconDomain.word(a) + " " + ReconDomain.word(b));
      }
      case VECTOR_KNN20 -> p.put("vector", ReconDomain.styleVector(tables.styleZipf.sample(rnd.nextDouble()), rnd));
      case HYDRATE_200 -> {
        final int available = m.sampleRecordRids.length;
        final int wanted = Math.min(HYDRATE_COUNT, available);
        final LinkedHashSet<String> picked = new LinkedHashSet<>(wanted * 2);
        while (picked.size() < wanted)
          picked.add(m.sampleRecordRids[rnd.nextInt(available)]);
        final List<ORecordId> rids = new ArrayList<>(wanted);
        for (final String rid : picked)
          rids.add(new ORecordId(rid));
        p.put("rids", rids);
      }
      }
      return p;
    }
  }

  /** Shared, read-only sampling tables, built the way {@code QueryMix.Tables} builds them. */
  public static final class Tables {
    final OrientDataset dataset;
    final ZipfSampler   phoneZipf;
    final ZipfSampler   cityZipf;
    final ZipfSampler   serviceZipf;
    final ZipfSampler   styleZipf;
    final double        referencedPhoneMass;
    final double        hubPhoneMass;

    public Tables(final OrientDataset dataset) {
      this.dataset = dataset;
      this.phoneZipf = new ZipfSampler(ReconDomain.phonePool(dataset.records), ReconDomain.PHONE_ZIPF);
      final double phoneDraws = 1.2 * dataset.records;
      this.referencedPhoneMass = phoneZipf.cumulativeMass(phoneZipf.maxRankWithExpectedCount(phoneDraws, 1.0));
      final int lastHub = phoneZipf.maxRankWithExpectedCount(phoneDraws, HUB_DEGREE);
      this.hubPhoneMass = phoneDraws * phoneZipf.probability(0) >= HUB_DEGREE ? phoneZipf.cumulativeMass(lastHub) : 0.0;
      this.cityZipf = new ZipfSampler(ReconDomain.CITIES, ReconDomain.CITY_ZIPF);
      this.serviceZipf = new ZipfSampler(ReconDomain.SERVICES, ReconDomain.SERVICE_ZIPF);
      this.styleZipf = new ZipfSampler(ReconDomain.STYLE_CLUSTERS, ReconDomain.STYLE_ZIPF);
    }
  }
}
