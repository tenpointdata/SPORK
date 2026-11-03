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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The synthetic Recon-shaped domain: every constant and every deterministic function the data generator and the query
 * parameter sampler share, so a query asks for identifiers the generator actually wrote. Everything here is a pure function
 * of its arguments and of {@link #DOMAIN_SEED}; the data seed only drives the per-record draws.
 * <p>
 * Shape (after {@code docs/spork/00-research-findings.md}): a Record is an advert with source, posting and collection
 * times, city/state, a WKT point, age, view count, services and free text; it hangs off Phone / Email / Handle / ImageHash
 * identifier vertices through {@code HAS_*} edges. Identifiers are drawn with a Zipf skew so a few of them are hubs
 * (agency numbers, reused photos), most are singletons.
 */
public final class ReconDomain {
  /** Fixed "now": 2026-11-01T00:00:00Z. The dataset is the two years before it. */
  public static final long NOW_MS       = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
  public static final long SPAN_MS      = 730L * 24 * 3600 * 1000;
  public static final long DAY_MS       = 24L * 3600 * 1000;
  /** Seed of the domain tables (cities, vocabulary, style centroids); independent of the data seed on purpose. */
  public static final long DOMAIN_SEED  = 7L;

  public static final int    VECTOR_DIMENSIONS = 24;
  public static final int    STYLE_CLUSTERS    = 200;
  public static final double STYLE_NOISE       = 0.35;

  public static final int CITIES          = 300;
  public static final int VOCABULARY_SIZE = 6_000;
  public static final int SERVICES        = 40;
  public static final int SOURCES         = 12;
  public static final int MIN_WORDS       = 20;
  public static final int MAX_WORDS       = 60;

  /** US states and DC in rough population order, which is the Zipf rank used to place cities. */
  public static final String[] STATES = { "CA", "TX", "FL", "NY", "PA", "IL", "OH", "GA", "NC", "MI", "NJ", "VA", "WA", "AZ", "TN",
      "MA", "IN", "MD", "MO", "WI", "CO", "MN", "SC", "AL", "LA", "KY", "OR", "OK", "CT", "UT", "IA", "NV", "AR", "KS", "MS", "NM",
      "NE", "ID", "WV", "HI", "NH", "ME", "MT", "RI", "DE", "SD", "ND", "AK", "DC", "VT", "WY" };

  public static final String[] EMAIL_DOMAINS = { "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "icloud.com", "aol.com",
      "proton.me", "mail.com" };
  public static final String[] PLATFORMS     = { "instagram", "snapchat", "twitter", "telegram", "onlyfans" };

  // Zipf exponents. Lower = flatter head. Tuned so the biggest phone hub at 5M records is about 1% of the records.
  public static final double PHONE_ZIPF   = 0.8;
  public static final double EMAIL_ZIPF   = 0.9;
  public static final double HANDLE_ZIPF  = 1.0;
  public static final double IMAGE_ZIPF   = 0.7;
  public static final double CITY_ZIPF    = 1.0;
  public static final double SERVICE_ZIPF = 1.0;
  public static final double SOURCE_ZIPF  = 1.2;
  public static final double WORD_ZIPF    = 1.1;
  public static final double STYLE_ZIPF   = 0.8;

  /** A synthetic city: coordinates in the continental US bounding box, a state and an area code. */
  public record City(String name, String state, double lat, double lon, int areaCode) {
  }

  private static final City[]     CITY_TABLE;
  private static final String[]   VOCABULARY;
  private static final String[]   SERVICE_NAMES;
  private static final String[]   SOURCE_NAMES;
  private static final float[][]  STYLE_CENTROIDS;

  static {
    final SplittableRandom rnd = new SplittableRandom(DOMAIN_SEED);
    final ZipfSampler stateZipf = new ZipfSampler(STATES.length, 1.0);
    CITY_TABLE = new City[CITIES];
    for (int i = 0; i < CITIES; i++) {
      final String state = STATES[stateZipf.sample(rnd.nextDouble())];
      CITY_TABLE[i] = new City("city" + i, state, 25.5 + rnd.nextDouble() * 23.0, -124.5 + rnd.nextDouble() * 57.0,
          200 + rnd.nextInt(800));
    }

    final String[] consonants = { "b", "d", "f", "g", "k", "l", "m", "n", "p", "r", "s", "t", "v", "z", "sh", "ch", "th" };
    final String[] vowels = { "a", "e", "i", "o", "u", "ai", "ou", "ee" };
    final Set<String> seen = new HashSet<>();
    VOCABULARY = new String[VOCABULARY_SIZE];
    for (int i = 0; i < VOCABULARY_SIZE; i++) {
      String word;
      do {
        final StringBuilder sb = new StringBuilder();
        final int syllables = 2 + rnd.nextInt(3);
        for (int s = 0; s < syllables; s++)
          sb.append(consonants[rnd.nextInt(consonants.length)]).append(vowels[rnd.nextInt(vowels.length)]);
        word = sb.toString();
      } while (!seen.add(word));
      VOCABULARY[i] = word;
    }

    SERVICE_NAMES = new String[SERVICES];
    for (int i = 0; i < SERVICES; i++)
      SERVICE_NAMES[i] = "service" + i;
    SOURCE_NAMES = new String[SOURCES];
    for (int i = 0; i < SOURCES; i++)
      SOURCE_NAMES[i] = "source" + i;

    STYLE_CENTROIDS = new float[STYLE_CLUSTERS][];
    for (int c = 0; c < STYLE_CLUSTERS; c++)
      STYLE_CENTROIDS[c] = randomUnitVector(rnd);
  }

  private ReconDomain() {
  }

  public static City city(final int rank) {
    return CITY_TABLE[rank];
  }

  public static String word(final int rank) {
    return VOCABULARY[rank];
  }

  public static String service(final int rank) {
    return SERVICE_NAMES[rank];
  }

  public static String source(final int rank) {
    return SOURCE_NAMES[rank];
  }

  // Identifier pools scale with the record count. The pool index IS the Zipf rank: pool index 0 is the biggest hub.

  public static int phonePool(final long records) {
    return (int) Math.max(1_000L, records * 35 / 100);
  }

  public static int emailPool(final long records) {
    return (int) Math.max(1_000L, records * 10 / 100);
  }

  public static int handlePool(final long records) {
    return (int) Math.max(1_000L, records * 15 / 100);
  }

  public static int imagePool(final long records) {
    return (int) Math.max(1_000L, records * 60 / 100);
  }

  /** Ten digits, the Recon normal form ("last 10 digits"). */
  public static String phoneKey(final int rank) {
    return Long.toString(2_000_000_000L + rank);
  }

  public static String emailKey(final int rank) {
    return "u" + rank + "@" + EMAIL_DOMAINS[rank % EMAIL_DOMAINS.length];
  }

  /** {@code platform:handle}, the Recon normal form for socials. */
  public static String handleKey(final int rank) {
    return PLATFORMS[rank % PLATFORMS.length] + ":h" + rank;
  }

  /** Sixteen hex digits of a 64-bit mix, like a perceptual hash. */
  public static String imageKey(final int rank) {
    return String.format("%016x", mix64(0x9E3779B97F4A7C15L * (rank + 1)));
  }

  /** A unit style vector around centroid {@code cluster}, with fresh noise: the recipe for both stored and query vectors. */
  public static float[] styleVector(final int cluster, final SplittableRandom rnd) {
    final float[] centroid = STYLE_CENTROIDS[cluster];
    final float[] v = new float[VECTOR_DIMENSIONS];
    double norm = 0.0;
    for (int d = 0; d < VECTOR_DIMENSIONS; d++) {
      v[d] = centroid[d] + (float) (rnd.nextGaussian() * STYLE_NOISE);
      norm += v[d] * v[d];
    }
    final float inv = (float) (1.0 / Math.sqrt(norm));
    for (int d = 0; d < VECTOR_DIMENSIONS; d++)
      v[d] *= inv;
    return v;
  }

  /** WKT point, longitude first as WKT has it. */
  public static String wktPoint(final double lon, final double lat) {
    return "POINT (" + lon + " " + lat + ")";
  }

  /** WKT polygon of the box {@code [lonMin, lonMax] x [latMin, latMax]}, closed. */
  public static String wktBox(final double lonMin, final double latMin, final double lonMax, final double latMax) {
    return "POLYGON ((" + lonMin + " " + latMin + ", " + lonMax + " " + latMin + ", " + lonMax + " " + latMax + ", " + lonMin + " "
        + latMax + ", " + lonMin + " " + latMin + "))";
  }

  /** Posting time with a recency skew: {@code NOW - SPAN * u^2}, so the last day holds a few percent of two years. */
  public static long postedAt(final double u) {
    return NOW_MS - (long) (SPAN_MS * u * u);
  }

  private static float[] randomUnitVector(final SplittableRandom rnd) {
    final float[] v = new float[VECTOR_DIMENSIONS];
    double norm = 0.0;
    for (int d = 0; d < VECTOR_DIMENSIONS; d++) {
      v[d] = (float) rnd.nextGaussian();
      norm += v[d] * v[d];
    }
    final float inv = (float) (1.0 / Math.sqrt(norm));
    for (int d = 0; d < VECTOR_DIMENSIONS; d++)
      v[d] *= inv;
    return v;
  }

  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
