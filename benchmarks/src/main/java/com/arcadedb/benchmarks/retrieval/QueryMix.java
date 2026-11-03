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
package com.arcadedb.benchmarks.retrieval;

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The nine retrieval shapes Recon issues, as parametrized SQL, with a {@link ParamSampler} that draws parameters the way
 * the application would: identifier lookups and pivots follow the same Zipf as the data (so hubs are hit as often as they
 * are referenced), windows are relative to the newest record, geo radii are around the cities the records cluster in.
 */
public final class QueryMix {
  public static final int PIVOT_LIMIT   = 500;
  public static final int SEARCH_LIMIT  = 100;
  public static final int FEED_LIMIT    = 200;
  public static final int TEXT_LIMIT    = 20;
  public static final int KNN_K         = 20;
  public static final int HYDRATE_COUNT = 200;
  /** An identifier with more records than this is a hub: the 2-hop pivot is not asked from it (its neighbourhood is the corpus). */
  public static final int HUB_DEGREE    = 2_000;
  /** 200 km in degrees of great-circle arc, the unit {@code geo.dWithin} takes. */
  public static final double RADIUS_200KM_DEG = 200.0 / 111.195;

  public enum Query {
    IDENT_LOOKUP("identLookup", "identifier point lookup", "SELECT FROM Phone WHERE key = :key"),
    PIVOT_1HOP("pivot1Hop", "identifier -> records, newest first, limit 500",
        "SELECT FROM (SELECT expand(in('HAS_PHONE')) FROM Phone WHERE key = :key) ORDER BY tsPosted DESC LIMIT " + PIVOT_LIMIT),
    PIVOT_2HOP("pivot2Hop", "identifier -> records -> their identifiers -> records, limit 500",
        "SELECT FROM (SELECT expand(in('HAS_PHONE').out('HAS_PHONE','HAS_IMAGE').in('HAS_PHONE','HAS_IMAGE')) FROM Phone WHERE key = :key) LIMIT "
            + PIVOT_LIMIT),
    STRUCTURED("structured", "state + age range + service, newest first, limit 100",
        "SELECT FROM Record WHERE state = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT "
            + SEARCH_LIMIT),
    FEED_24H("feed24h", "last 24h, located only, newest first, limit 200",
        "SELECT FROM Record WHERE tsPosted >= :since AND location IS NOT NULL ORDER BY tsPosted DESC LIMIT " + FEED_LIMIT),
    GEO_RADIUS_14D("geoRadius14d", "200 km radius + 14-day window, limit 500",
        "SELECT FROM Record WHERE geo.intersects(location, geo.geomFromText(:box)) = true AND geo.dWithin(location, geo.point(:lon, :lat), :radiusDeg) = true AND tsPosted >= :since LIMIT "
            + PIVOT_LIMIT),
    FULLTEXT_TOP20("fulltextTop20", "two-term full-text, BM25 top 20",
        "SELECT FROM Record WHERE SEARCH_INDEX('Record[text]', :q) = true ORDER BY $score DESC LIMIT " + TEXT_LIMIT),
    VECTOR_KNN20("vectorKnn20", "24-d style vector kNN, k=20",
        "SELECT expand(`vector.neighbors`('Record[styleVec]', :vector, " + KNN_K + "))"),
    HYDRATE_200("hydrate200", "200 records by RID", "SELECT FROM Record WHERE @rid IN :rids");

    public final String id;
    public final String description;
    public final String sql;

    Query(final String id, final String description, final String sql) {
      this.id = id;
      this.description = description;
      this.sql = sql;
    }

    public static Query byId(final String id) {
      for (final Query q : values())
        if (q.id.equals(id))
          return q;
      throw new IllegalArgumentException("Unknown query '" + id + "'");
    }
  }

  private QueryMix() {
  }

  /** Executes one query embedded and returns the number of rows it produced (every row is consumed). */
  public static int execute(final Database database, final Query query, final Map<String, Object> params) {
    int rows = 0;
    try (final ResultSet rs = database.query("sql", query.sql, params)) {
      while (rs.hasNext()) {
        rs.next();
        rows++;
      }
    }
    return rows;
  }

  /** Parameters as the HTTP body wants them: vectors as JSON arrays, RIDs as their string form. */
  public static JSONObject toJsonParams(final Map<String, Object> params) {
    final JSONObject json = new JSONObject();
    for (final Map.Entry<String, Object> e : params.entrySet()) {
      final Object v = e.getValue();
      if (v instanceof float[] vector) {
        final JSONArray array = new JSONArray(vector.length);
        for (final float f : vector)
          array.put(f);
        json.put(e.getKey(), array);
      } else if (v instanceof List<?> list) {
        final JSONArray array = new JSONArray(list.size());
        for (final Object o : list)
          array.put(o instanceof RID rid ? rid.toString() : o);
        json.put(e.getKey(), array);
      } else
        json.put(e.getKey(), v);
    }
    return json;
  }

  /**
   * Draws parameters for a query. One instance per thread (it owns a random generator); the Zipf tables are shared through
   * {@link Tables} because they are one double per identifier.
   */
  public static final class ParamSampler {
    private final Tables           tables;
    private final SplittableRandom rnd;
    private final Database         database;

    public ParamSampler(final Tables tables, final long seed, final Database databaseForRids) {
      this.tables = tables;
      this.rnd = new SplittableRandom(seed);
      this.database = databaseForRids;
    }

    public Map<String, Object> params(final Query query) {
      final Map<String, Object> p = new LinkedHashMap<>();
      final DatasetManifest m = tables.manifest;
      switch (query) {
      // a user pivots on an identifier taken from a record, so the draw is the data's Zipf truncated to the ranks that are
      // expected to have at least one record (the tail of the pool exists but was never referenced)
      case IDENT_LOOKUP, PIVOT_1HOP -> p.put("key",
          ReconDomain.phoneKey(tables.phoneZipf.sample(rnd.nextDouble() * tables.referencedPhoneMass)));
      // the 2-hop neighbourhood of a hub is the whole corpus: the application refuses to expand an identifier past
      // HUB_DEGREE records two hops out, so the sampler starts below the hubs (they stay in the 1-hop pivot)
      case PIVOT_2HOP -> p.put("key", ReconDomain.phoneKey(tables.phoneZipf.sample(
          tables.hubPhoneMass + rnd.nextDouble() * (tables.referencedPhoneMass - tables.hubPhoneMass))));
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
        // the bounding box is the index-backed coarse filter; the great-circle radius is the exact one
        final double dLat = RADIUS_200KM_DEG;
        final double dLon = RADIUS_200KM_DEG / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        p.put("box", ReconDomain.wktBox(lon - dLon, lat - dLat, lon + dLon, lat + dLat));
        p.put("lon", lon);
        p.put("lat", lat);
        p.put("radiusDeg", RADIUS_200KM_DEG);
        p.put("since", m.tsMax - 14 * ReconDomain.DAY_MS);
      }
      case FULLTEXT_TOP20 -> {
        // two mid-frequency words: common enough to have thousands of postings, rare enough to rank
        final int a = 30 + rnd.nextInt(570), b = 30 + rnd.nextInt(570);
        p.put("q", ReconDomain.word(a) + " " + ReconDomain.word(b));
      }
      case VECTOR_KNN20 -> p.put("vector", ReconDomain.styleVector(tables.styleZipf.sample(rnd.nextDouble()), rnd));
      case HYDRATE_200 -> {
        // 200 DISTINCT records, as a page of search hits would be
        final int available = m.sampleRecordRids.length;
        final int wanted = Math.min(HYDRATE_COUNT, available);
        final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>(wanted * 2);
        while (picked.size() < wanted)
          picked.add(m.sampleRecordRids[rnd.nextInt(available)]);
        final List<Object> rids = new ArrayList<>(wanted);
        for (final String rid : picked)
          rids.add(database != null ? RID.create(database, rid) : new RID(rid));
        p.put("rids", rids);
      }
      }
      return p;
    }
  }

  /** The shared, read-only sampling tables of a dataset. Build once, hand to every {@link ParamSampler}. */
  public static final class Tables {
    final DatasetManifest manifest;
    final ZipfSampler     phoneZipf;
    final ZipfSampler     cityZipf;
    final ZipfSampler     serviceZipf;
    final ZipfSampler     styleZipf;
    /** Cumulative Zipf mass of the phone ranks expected to be referenced by at least one record (1.2 phones per record). */
    final double          referencedPhoneMass;
    /** Cumulative Zipf mass of the phone ranks expected to be hubs ({@link #HUB_DEGREE} records or more). */
    final double          hubPhoneMass;

    public Tables(final DatasetManifest manifest) {
      this.manifest = manifest;
      this.phoneZipf = new ZipfSampler(manifest.phonePool(), ReconDomain.PHONE_ZIPF);
      final double phoneDraws = 1.2 * manifest.records;
      this.referencedPhoneMass = phoneZipf.cumulativeMass(phoneZipf.maxRankWithExpectedCount(phoneDraws, 1.0));
      final int lastHub = phoneZipf.maxRankWithExpectedCount(phoneDraws, HUB_DEGREE);
      this.hubPhoneMass = phoneDraws * phoneZipf.probability(0) >= HUB_DEGREE ? phoneZipf.cumulativeMass(lastHub) : 0.0;
      this.cityZipf = new ZipfSampler(ReconDomain.CITIES, ReconDomain.CITY_ZIPF);
      this.serviceZipf = new ZipfSampler(ReconDomain.SERVICES, ReconDomain.SERVICE_ZIPF);
      this.styleZipf = new ZipfSampler(ReconDomain.STYLE_CLUSTERS, ReconDomain.STYLE_ZIPF);
    }

    public DatasetManifest manifest() {
      return manifest;
    }
  }
}
