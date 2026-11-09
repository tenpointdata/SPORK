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

import com.arcadedb.database.Database;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.MatchResult;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: identity.cohesion(cluster, options?)
 * <p>
 * The group operation over one cluster: scores every member against the rest of the cluster with the
 * {@link IdentityMatchModel} of {@code identity.compare}, and returns the members weakest first. A member whose
 * probability is low is the one that does not belong: the split candidate of a resolved identity, or the record that
 * should not have been pulled into a target's footprint.
 * </p>
 * <p>
 * The profile of the cluster is built once and every member is scored against a leave-one-out view of it
 * ({@link ClusterProfile#without(int)}), so the call reads the graph once and costs about the sum of the members'
 * identifiers rather than a rebuild per member.
 * </p>
 * <p>
 * Example:
 * <pre>
 * CALL identity.cohesion($records, {via: ['HAS_PHONE', 'HAS_EMAIL'], timeProperty: 'tsPosted', limit: 10})
 * YIELD member, probability, lower, upper, verdict, evidence
 * </pre>
 * </p>
 */
public class IdentityCohesion extends AbstractIdentityProcedure {
  public static final String NAME = "identity.cohesion";

  private static final Set<String> KEYS;

  static {
    final Set<String> keys = new HashSet<>(MODEL_KEYS);
    keys.add("limit");
    KEYS = Set.copyOf(keys);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 1;
  }

  @Override
  public int getMaxArgs() {
    return 2;
  }

  @Override
  public String getDescription() {
    return "Scores every member of a cluster against the rest of it, weakest first, to find the members that do not belong";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("member", "probability", "lower", "upper", "logOdds", "matchWeight", "matchWeightStdDev", "verdict",
        "evidence", "modelVersion");
  }

  @Override
  Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Database database = context.getDatabase();
    final Object options = argOrDefault(args, 1, null);
    final Settings settings = parseSettings(options, context, 5);
    final Map<String, Object> config = extractMap(options, "options");
    final Object limitOption = config == null ? null : config.get("limit");
    if (limitOption != null && !(limitOption instanceof Number))
      throw new IllegalArgumentException(getName() + "(): limit must be a number, got " + limitOption);
    final int limit = limitOption == null ? Integer.MAX_VALUE : extractInt((Number) limitOption, "limit", 0);

    final List<Vertex> members = extractMembers(args[0], "cluster", database);
    if (members.size() < 2)
      throw new IllegalArgumentException(getName() + "(): cluster must hold at least 2 existing nodes, got " + members.size());
    if (!settings.populationGiven)
      settings.defaultPopulation(members);

    final WorkGuard guard = newWorkGuard(context);
    final ClusterProfile full = buildProfile(members, settings, guard);

    final List<Map<String, Object>> rows = new ArrayList<>(members.size());
    for (int j = 0; j < members.size(); j++) {
      guard.check();
      final ClusterProfile one = full.only(j);
      final ClusterProfile rest = full.without(j);
      final MatchResult r = settings.model.compare(one, rest, settings.frequencyLookup);
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("member", members.get(j).getIdentity());
      putMatch(row, r, settings, new Side(one, List.of(members.get(j))), new Side(rest, members));
      rows.add(row);
    }

    rows.sort((x, y) -> Double.compare((Double) x.get("probability"), (Double) y.get("probability")));
    return rows.stream().limit(limit).map(ResultInternal::new);
  }
}
