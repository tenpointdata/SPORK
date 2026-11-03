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
package com.arcadedb.query.opencypher.procedures.similarity;

import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.similarity.ClusterSimilarity;
import com.arcadedb.similarity.ClusterSimilarity.ImageFeatures;
import com.arcadedb.similarity.ClusterSimilarity.NearPair;
import com.arcadedb.similarity.ClusterSimilarity.StyleFeatures;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: similarity.compare(clusterA, clusterB, options)
 * <p>
 * The soft evidence two clusters of records offer that they are one individual (docs/spork/06-stylometry-perceptual-hash.md;
 * slice 3 of docs/spork/04-identity-resolution-design.md): how alike they write and which of their images are near
 * duplicates. It answers with features, not a probability: {@code identity.compare} turns calibrated features into
 * likelihood ratios. Records in both clusters are set aside and counted as {@code sharedMembers}, as identity.compare
 * does, since a record compared with itself is perfectly similar. Either cluster may be the name of a saved selection.
 * </p>
 * <p>
 * Example:
 * <pre>
 * CALL similarity.compare($a, $b, {styleProperty: 'text', hashProperty: 'imagePhashes', qualityProperty: 'imageQuality'})
 * YIELD style, images
 * </pre>
 * </p>
 */
public class SimilarityCompare extends AbstractSimilarityProcedure {
  public static final String NAME = "similarity.compare";

  private static final Set<String> KEYS = Set.of("styleProperty", "styleMethod", "styleThreshold", "hashProperty", "maxDistance",
      "qualityProperty", "minQuality", "maxPairs", "pairLimit");

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 3;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public String getDescription() {
    return "Compares the writing style and the near-duplicate images of two clusters of records";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("sizeA", "sizeB", "sharedMembers", "style", "images");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Map<String, Object> config = extractConfig(args[2], "options", KEYS);
    final String styleProperty = optionalString(config, "styleProperty");
    final String hashProperty = optionalString(config, "hashProperty");
    if (styleProperty == null && hashProperty == null)
      throw new IllegalArgumentException(getName() + "(): give styleProperty, hashProperty or both");
    final StyleMethod styleMethod = styleMethod(config);
    final double styleThreshold = doubleOption(config, "styleThreshold", 0.95, -1, 1);
    final String qualityProperty = optionalString(config, "qualityProperty");
    final int minQuality = intOption(config, "minQuality", 50, 0);
    final long maxPairs = intOption(config, "maxPairs", 1_000_000, 1);
    final int pairLimit = intOption(config, "pairLimit", 25, 0);

    final List<Document> a = extractRecords(args[0], "clusterA", context.getDatabase());
    final List<Document> b = extractRecords(args[1], "clusterB", context.getDatabase());
    final Set<RID> inB = new HashSet<>();
    for (final Document d : b)
      inB.add(d.getIdentity());
    final Set<RID> shared = new HashSet<>();
    for (final Document d : a)
      if (inB.contains(d.getIdentity()))
        shared.add(d.getIdentity());
    a.removeIf(d -> shared.contains(d.getIdentity()));
    b.removeIf(d -> shared.contains(d.getIdentity()));
    final WorkGuard guard = newWorkGuard(context);

    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("sizeA", a.size());
    row.put("sizeB", b.size());
    row.put("sharedMembers", shared.size());
    row.put("style", styleProperty == null ? null : style(a, b, styleProperty, styleMethod, styleThreshold, maxPairs, guard));
    row.put("images", hashProperty == null ? null :
        images(a, b, hashProperty, config, qualityProperty, minQuality, pairLimit, guard));
    return Stream.of(new ResultInternal(row));
  }

  private Map<String, Object> style(final List<Document> a, final List<Document> b, final String property,
      final StyleMethod method, final double threshold, final long maxPairs, final WorkGuard guard) {
    final List<RID> ridsA = new ArrayList<>();
    final List<RID> ridsB = new ArrayList<>();
    final float[][] va = vectors(a, property, method, ridsA, guard);
    final float[][] vb = vectors(b, property, method, ridsB, guard);
    final StyleFeatures f;
    try {
      f = ClusterSimilarity.style(va, vb, threshold, maxPairs);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(getName() + "(): " + e.getMessage(), e);
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("membersA", f.sizeA());
    m.put("membersB", f.sizeB());
    m.put("pairs", f.pairs());
    m.put("pairsTruncated", f.pairsTruncated());
    m.put("pairsAbove", f.pairsAbove());
    m.put("threshold", threshold);
    m.put("max", nullIfNaN(f.max()));
    m.put("meanBest", nullIfNaN(f.meanBest()));
    m.put("centroid", nullIfNaN(f.centroid()));
    m.put("bestPair", f.bestA() < 0 ? null : Map.of("a", ridsA.get(f.bestA()), "b", ridsB.get(f.bestB()), "similarity", f.max()));
    return m;
  }

  private float[][] vectors(final List<Document> records, final String property, final StyleMethod method, final List<RID> rids,
      final WorkGuard guard) {
    final List<float[]> vectors = new ArrayList<>(records.size());
    for (int i = 0; i < records.size(); i++) {
      guard.checkPeriodically(i);
      final float[] v = readStyle(records.get(i), property, method);
      if (v != null) {
        vectors.add(v);
        rids.add(records.get(i).getIdentity());
      }
    }
    return vectors.toArray(new float[0][]);
  }

  private Map<String, Object> images(final List<Document> a, final List<Document> b, final String property,
      final Map<String, Object> config, final String qualityProperty, final int minQuality, final int pairLimit,
      final WorkGuard guard) {
    final List<long[]> ha = new ArrayList<>();
    final List<Integer> oa = new ArrayList<>();
    final List<long[]> hb = new ArrayList<>();
    final List<Integer> ob = new ArrayList<>();
    collect(a, property, qualityProperty, minQuality, ha, oa, guard);
    collect(b, property, qualityProperty, minQuality, hb, ob, guard);
    final int bits = !ha.isEmpty() ? ha.getFirst().length * 64 : !hb.isEmpty() ? hb.getFirst().length * 64 : 64;
    final int maxDistance = intOption(config, "maxDistance", defaultMaxDistance(bits), 0);

    final ImageFeatures f;
    try {
      f = ClusterSimilarity.images(ha.toArray(new long[0][]), oa.stream().mapToInt(Integer::intValue).toArray(),
          hb.toArray(new long[0][]), ob.stream().mapToInt(Integer::intValue).toArray(), maxDistance);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(getName() + "(): " + e.getMessage(), e);
    }
    final List<Map<String, Object>> pairs = new ArrayList<>();
    for (final NearPair p : f.pairs()) {
      if (pairs.size() >= pairLimit)
        break;
      pairs.add(Map.of("a", a.get(p.memberA()).getIdentity(), "b", b.get(p.memberB()).getIdentity(), "distance", p.distance()));
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("hashesA", f.hashesA());
    m.put("hashesB", f.hashesB());
    m.put("bits", bits);
    m.put("maxDistance", maxDistance);
    m.put("nearPairs", f.pairs().size());
    m.put("exactPairs", f.exactPairs());
    m.put("minDistance", f.minDistance() < 0 ? null : f.minDistance());
    m.put("pairs", pairs);
    return m;
  }

  private void collect(final List<Document> records, final String property, final String qualityProperty, final int minQuality,
      final List<long[]> hashes, final List<Integer> owners, final WorkGuard guard) {
    for (int i = 0; i < records.size(); i++) {
      guard.checkPeriodically(i);
      for (final long[] h : readHashes(records.get(i), property, qualityProperty, minQuality)) {
        hashes.add(h);
        owners.add(i);
      }
    }
  }

  private static Double nullIfNaN(final double v) {
    return Double.isNaN(v) ? null : v;
  }
}
