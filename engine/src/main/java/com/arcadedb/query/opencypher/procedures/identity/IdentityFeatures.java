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
package com.arcadedb.query.opencypher.procedures.identity;

import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.DisjointFeature;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.Features;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.SharedFeature;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.SoftSignal;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.similarity.SoftSimilarity;
import com.arcadedb.similarity.SoftSimilarity.ImageStats;
import com.arcadedb.similarity.SoftSimilarity.StyleStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: identity.features(clusterA, clusterB, options?)
 * <p>
 * The raw terms {@code identity.compare} scores for a pair, before any parameter is applied: what a training export reads
 * (docs/spork/05-identity-model-mlops.md, section 3). It runs the same {@code preparePair} and the same
 * {@link IdentityMatchModel#features} as compare, and compare is {@code score(features)}, so the two cannot drift.
 * </p>
 * <ul>
 *   <li>{@code shared}: per identifier both clusters reach, {@code {kind, identifier, countA, countB, frequency, population,
 *   sizeA, sizeB, gapDays}}; frequency is the one the score uses (reported, raised to the observed count), gapDays the
 *   shortest time between sightings across the pair, null when unknown.</li>
 *   <li>{@code disjoint}: per kind both use with nothing shared, {@code {kind, countA, countB, gapDays}}, gapDays between
 *   the two windows of use (0 when they overlap).</li>
 *   <li>{@code travel}: {@code {comparablePairs, conflicts, pairsTruncated}}.</li>
 *   <li>{@code soft}: per soft signal both clusters carry, the raw pair statistics with the threshold that defined
 *   agreement: {@code style: {pairs, pairsAbove, best, mean, threshold}}, {@code image: {pairs, pairsWithin,
 *   memberPairsWithin, minDistance, meanDistance, maxDistance}}; best, mean and the distances are null when no pair was
 *   compared.</li>
 * </ul>
 * The travel bounds ({@code maxSpeedKmh}, {@code conflictWindowHours}, {@code minConflictKm}, {@code maxPairs}) and the
 * soft thresholds define what a conflict and an agreement are, so they are read here; every other parameter only matters
 * to the score.
 */
public class IdentityFeatures extends AbstractIdentityProcedure {
  public static final String NAME = "identity.features";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public String getDescription() {
    return "The raw comparison terms of two clusters, before the model's parameters are applied, for training exports";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("sizeA", "sizeB", "population", "temporal", "sharedMembers", "shared", "disjoint", "travel", "soft", "modelVersion");
  }

  @Override
  Set<String> allowedKeys() {
    return MODEL_KEYS;
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Settings settings = parseSettings(argOrDefault(args, 2, null), context, 0);
    final Pair pair = preparePair(args[0], args[1], settings, context);
    final IdentityMatchModel model = settings.model;
    final Features f = model.features(pair.a(), pair.b(), settings.frequencyLookup);

    final List<Map<String, Object>> shared = new ArrayList<>(f.shared().size());
    for (final SharedFeature s : f.shared()) {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("kind", model.kindName(s.kind()));
      m.put("identifier", s.identifier());
      m.put("countA", s.countA());
      m.put("countB", s.countB());
      m.put("frequency", s.frequency());
      m.put("population", f.population());
      m.put("sizeA", f.sizeA());
      m.put("sizeB", f.sizeB());
      m.put("gapDays", days(s.gap()));
      shared.add(m);
    }
    final List<Map<String, Object>> disjoint = new ArrayList<>(f.disjoint().size());
    for (final DisjointFeature d : f.disjoint()) {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("kind", model.kindName(d.kind()));
      m.put("countA", d.countA());
      m.put("countB", d.countB());
      m.put("gapDays", days(d.gap()));
      disjoint.add(m);
    }
    final Map<String, Object> travel = new LinkedHashMap<>();
    travel.put("comparablePairs", f.comparablePairs());
    travel.put("conflicts", f.conflicts());
    travel.put("pairsTruncated", f.pairsTruncated());

    final Map<String, Object> soft = new LinkedHashMap<>();
    if (f.style() != null) {
      final StyleStats st = f.style();
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("pairs", st.pairs());
      m.put("pairsAbove", st.pairsAbove());
      m.put("best", finite(st.best()));
      m.put("mean", finite(st.mean()));
      m.put("threshold", model.softThreshold(SoftSignal.STYLE));
      soft.put("style", m);
    }
    if (f.image() != null) {
      final ImageStats im = f.image();
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("pairs", im.pairs());
      m.put("pairsWithin", im.pairsWithin());
      m.put("memberPairsWithin", im.memberPairsWithin());
      m.put("minDistance", im.minDistance() == SoftSimilarity.NONE ? null : im.minDistance());
      m.put("meanDistance", finite(im.meanDistance()));
      m.put("maxDistance", f.imageMaxDistance());
      soft.put("image", m);
    }

    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("sizeA", f.sizeA());
    row.put("sizeB", f.sizeB());
    row.put("population", f.population());
    row.put("temporal", f.temporal());
    row.put("sharedMembers", pair.sharedMembers());
    row.put("shared", shared);
    row.put("disjoint", disjoint);
    row.put("travel", travel);
    row.put("soft", soft);
    row.put("modelVersion", settings.modelVersion);
    return Stream.of(new ResultInternal(row));
  }

  private static Double finite(final double value) {
    return Double.isNaN(value) ? null : value;
  }

  private static Double days(final long gap) {
    return gap < 0 ? null : gap / 86_400_000.0;
  }
}
