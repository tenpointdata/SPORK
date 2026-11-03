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

import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.MatchResult;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: identity.compare(clusterA, clusterB, options?)
 * <p>
 * Scores whether two clusters of nodes (lists of nodes, RIDs or RID strings) belong to the same individual, with a
 * confidence interval, from the identifiers they share or do not share, how far apart in time they were seen, and whether
 * the two clusters were active far apart at the same time. See {@link IdentityMatchModel} for the model and
 * docs/spork/04-identity-resolution-design.md for the options.
 * </p>
 * <p>
 * Example:
 * <pre>
 * CALL identity.compare($recordsOfA, $recordsOfB,
 *   {via: ['HAS_PHONE', 'HAS_EMAIL', 'HAS_HANDLE'], timeProperty: 'tsPosted', latProperty: 'lat', lonProperty: 'lon',
 *    frequencyProperty: 'recordCount', kinds: {Phone: {m: 0.7, halfLifeDays: 180}}})
 * YIELD probability, lower, upper, verdict, evidence
 * </pre>
 * </p>
 * Nodes in both clusters are set aside (reported as {@code sharedMembers}): they would make each of their identifiers
 * count as shared.
 */
public class IdentityCompare extends AbstractIdentityProcedure {
  public static final String NAME = "identity.compare";

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
    return "Probability, with a confidence interval, that two clusters of nodes are the same individual";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("probability", "lower", "upper", "logOdds", "matchWeight", "matchWeightStdDev", "verdict", "sizeA", "sizeB",
        "sharedMembers", "evidence", "temporal", "modelVersion");
  }

  @Override
  Set<String> allowedKeys() {
    return MODEL_KEYS;
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Settings settings = parseSettings(argOrDefault(args, 2, null), context, 25);

    final Pair pair = preparePair(args[0], args[1], settings, context);
    final MatchResult r = settings.model.compare(pair.a(), pair.b(), settings.frequencyLookup);

    final Map<String, Object> row = new LinkedHashMap<>();
    putMatch(row, r, settings);
    row.put("sizeA", r.sizeA());
    row.put("sizeB", r.sizeB());
    row.put("sharedMembers", pair.sharedMembers());
    row.put("temporal", temporal(r));
    return Stream.of(new ResultInternal(row));
  }
}
