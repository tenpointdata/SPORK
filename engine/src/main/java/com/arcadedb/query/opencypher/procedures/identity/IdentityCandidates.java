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
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.MatchResult;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.schema.DocumentType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: identity.candidates(cluster, options?)
 * <p>
 * The groups outside a cluster most likely to be the same individual, scored with the {@link IdentityMatchModel} of
 * {@code identity.compare}. Three steps:
 * </p>
 * <ol>
 *   <li><b>Blocking.</b> Every identifier of the cluster carried by at most {@code maxFrequency} records is followed back to
 *   those records. A more frequent identifier (a hub) is skipped after reading {@code maxFrequency + 1} of its entries, so a
 *   hub costs a bounded walk, never its full degree. At most {@code maxCandidates} records are collected.</li>
 *   <li><b>Grouping.</b> Without {@code groupBy} every record is its own candidate: what is missing from the footprint, the
 *   counterpart of {@code identity.cohesion}. With {@code groupBy}, records are grouped by that property (a persona, a
 *   component id) and each group is loaded whole through an index on the property, up to {@code maxGroupSize} members;
 *   with no index only the records reached are scored and the row says {@code complete: false}. A record without the
 *   property is a group of its own, keyed by its RID. Members of the cluster are set aside and counted as
 *   {@code overlap}.</li>
 *   <li><b>Scoring.</b> Groups are pre-ranked by how many distinct blocking identifiers reach them, the first
 *   {@code maxScored} are compared with the cluster, and the {@code limit} strongest are returned, best first.</li>
 * </ol>
 * <p>
 * Example:
 * <pre>
 * CALL identity.candidates($footprint, {via: ['HAS_PHONE', 'HAS_EMAIL'], timeProperty: 'tsPosted', groupBy: 'componentId'})
 * YIELD candidate, members, probability, lower, upper, verdict, evidence
 * </pre>
 * </p>
 */
public class IdentityCandidates extends AbstractIdentityProcedure {
  public static final String NAME = "identity.candidates";

  private static final Set<String> KEYS;

  static {
    final Set<String> keys = new HashSet<>(MODEL_KEYS);
    keys.addAll(Set.of("limit", "groupBy", "maxFrequency", "maxCandidates", "maxScored", "maxGroupSize", "memberLimit"));
    KEYS = Set.copyOf(keys);
  }

  private static final class Group {
    final Object    key;
    final List<RID> reached     = new ArrayList<>();
    final Set<RID>  blockingIds = new HashSet<>();

    Group(final Object key) {
      this.key = key;
    }
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
    return "The groups outside a cluster most likely to be the same individual, with a confidence interval";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("candidate", "members", "size", "complete", "overlap", "blockedOn", "probability", "lower", "upper",
        "logOdds", "matchWeight", "matchWeightStdDev", "verdict", "evidence", "modelVersion");
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
    final Map<String, Object> c = config == null ? Map.of() : config;
    final String groupBy = stringOption(c, "groupBy");
    final int limit = extractInt(numberOption(c, "limit", 25), "limit", 0);
    final int maxFrequency = extractInt(numberOption(c, "maxFrequency", 1000), "maxFrequency", 1);
    final int maxCandidates = extractInt(numberOption(c, "maxCandidates", 10_000), "maxCandidates", 1);
    final int maxScored = extractInt(numberOption(c, "maxScored", 500), "maxScored", 1);
    final int maxGroupSize = extractInt(numberOption(c, "maxGroupSize", 2000), "maxGroupSize", 1);
    final int memberLimit = extractInt(numberOption(c, "memberLimit", 100), "memberLimit", 0);

    final List<Vertex> members = extractMembers(args[0], "cluster", database);
    if (members.isEmpty())
      throw new IllegalArgumentException(getName() + "(): cluster holds no existing node");
    if (!settings.populationGiven)
      settings.defaultPopulation(members);

    final WorkGuard guard = newWorkGuard(context);
    final ClusterProfile profile = buildProfile(members, settings, guard);
    final Set<RID> memberRids = new HashSet<>();
    for (final Vertex v : members)
      memberRids.add(v.getIdentity());

    final Map<RID, List<RID>> blocked = block(database, profile, memberRids, settings, maxFrequency, maxCandidates, guard);

    // Group the records reached
    final Map<Object, Group> groups = new LinkedHashMap<>();
    final Map<Object, String> groupType = new HashMap<>();
    for (final Map.Entry<RID, List<RID>> e : blocked.entrySet()) {
      final RID rid = e.getKey();
      Object key = rid;
      if (groupBy != null) {
        final Vertex vertex = load(database, rid);
        if (vertex == null)
          continue;
        final Object value = vertex.get(groupBy);
        if (value != null) {
          key = value;
          groupType.putIfAbsent(value, vertex.getTypeName());
        }
      }
      final Group group = groups.computeIfAbsent(key, Group::new);
      group.reached.add(rid);
      group.blockingIds.addAll(e.getValue());
    }

    // Pre-rank by the number of distinct blocking identifiers, then by key for a deterministic order
    final List<Group> ranked = new ArrayList<>(groups.values());
    ranked.sort((x, y) -> {
      final int byBlocks = Integer.compare(y.blockingIds.size(), x.blockingIds.size());
      if (byBlocks != 0)
        return byBlocks;
      if (x.key instanceof RID rx && y.key instanceof RID ry)
        return rx.compareTo(ry);
      return x.key.toString().compareTo(y.key.toString());
    });

    final List<Map<String, Object>> rows = new ArrayList<>();
    for (int g = 0; g < ranked.size() && g < maxScored; g++) {
      guard.check();
      final Group group = ranked.get(g);

      // The members of the group: the whole group through the index when there is one, otherwise the records reached
      List<RID> groupRids = group.reached;
      boolean complete = true;
      if (groupBy != null && !(group.key instanceof RID)) {
        final DocumentType type = database.getSchema().getType(groupType.get(group.key));
        final TypeIndex index = type.getPolymorphicIndexByProperties(groupBy);
        if (index != null) {
          groupRids = new ArrayList<>();
          final IndexCursor cursor = index.get(new Object[] { group.key });
          while (cursor.hasNext()) {
            final RID rid = cursor.next().getIdentity();
            if (groupRids.size() == maxGroupSize) {
              complete = false;
              break;
            }
            groupRids.add(rid);
          }
        } else
          complete = false;
      }

      final List<Vertex> groupMembers = new ArrayList<>(groupRids.size());
      int overlap = 0;
      for (final RID rid : groupRids) {
        if (memberRids.contains(rid)) {
          overlap++;
          continue;
        }
        final Vertex vertex = load(database, rid);
        if (vertex != null)
          groupMembers.add(vertex);
      }
      if (groupMembers.isEmpty())
        continue;

      final MatchResult r = settings.model.compare(profile, buildProfile(groupMembers, settings, guard), settings.frequencyLookup);

      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("candidate", group.key);
      final List<RID> shown = new ArrayList<>(Math.min(memberLimit, groupMembers.size()));
      for (int i = 0; i < groupMembers.size() && i < memberLimit; i++)
        shown.add(groupMembers.get(i).getIdentity());
      row.put("members", shown);
      row.put("size", groupMembers.size());
      row.put("complete", complete);
      row.put("overlap", overlap);
      row.put("blockedOn", group.blockingIds.size());
      putMatch(row, r, settings);
      rows.add(row);
    }

    rows.sort((x, y) -> {
      final int byScore = Double.compare((Double) y.get("logOdds"), (Double) x.get("logOdds"));
      return byScore != 0 ? byScore : x.get("candidate").toString().compareTo(y.get("candidate").toString());
    });
    return rows.stream().limit(limit).map(ResultInternal::new);
  }

  /**
   * The records outside the cluster that carry one of its identifiers of at most {@code maxFrequency} records, each with the
   * identifiers it was reached through. Identifiers are walked in RID order so the {@code maxCandidates} cut is
   * deterministic.
   */
  private Map<RID, List<RID>> block(final Database database, final ClusterProfile profile, final Set<RID> memberRids,
      final Settings settings, final int maxFrequency, final int maxCandidates, final WorkGuard guard) {
    final List<RID> identifiers = new ArrayList<>(profile.identifierIds());
    identifiers.sort(RID::compareTo);
    final Vertex.DIRECTION back = opposite(settings.direction);
    final Map<RID, List<RID>> blocked = new LinkedHashMap<>();
    final List<RID> reached = new ArrayList<>();
    int iteration = 0;
    for (final RID id : identifiers) {
      if (settings.frequencyProperty != null) {
        final long frequency = settings.frequency(id);
        if (frequency > maxFrequency)
          continue;
      }
      final Vertex identifier = load(database, id);
      if (identifier == null)
        continue;

      reached.clear();
      boolean hub = false;
      for (final RID rid : identifier.getConnectedVertexRIDs(back, settings.via)) {
        guard.checkPeriodically(++iteration);
        if (reached.size() == maxFrequency) {
          hub = true;
          break;
        }
        reached.add(rid);
      }
      if (hub)
        continue;
      if (settings.frequencyProperty == null)
        // The walk counted the identifier's degree, which is the frequency the scoring would read again
        settings.cacheFrequency(id, reached.size());

      reached.sort(RID::compareTo);
      RID previous = null;
      for (final RID rid : reached) {
        // A record linked twice to the identifier counts once
        if (rid.equals(previous) || memberRids.contains(rid)) {
          previous = rid;
          continue;
        }
        previous = rid;
        List<RID> through = blocked.get(rid);
        if (through == null) {
          if (blocked.size() >= maxCandidates)
            continue;
          through = new ArrayList<>(2);
          blocked.put(rid, through);
        }
        through.add(id);
      }
    }
    return blocked;
  }

  private static Vertex load(final Database database, final RID rid) {
    try {
      final Record record = database.lookupByRID(rid, true);
      return record instanceof Vertex v ? v : null;
    } catch (final RecordNotFoundException e) {
      return null;
    }
  }
}
