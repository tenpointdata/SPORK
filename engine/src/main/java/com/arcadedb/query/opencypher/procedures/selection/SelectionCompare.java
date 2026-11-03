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
package com.arcadedb.query.opencypher.procedures.selection;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;
import com.arcadedb.graph.EdgeEntryRIDIterator;
import com.arcadedb.graph.GhostEdgeReporter;
import com.arcadedb.graph.GraphEngine;
import com.arcadedb.graph.GraphTraversalProvider;
import com.arcadedb.graph.Vertex;
import com.arcadedb.graph.VertexInternal;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.utility.RidBitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: selection.compare(selections, options?)
 * <p>
 * The elements that 2 to 8 selections share: one row per element in at least {@code minShared} of them, most shared first
 * (then by region mask, then in RID order), with the selections it is in and, unless {@code memberCap} is 0, how many members
 * of each selection reach it and up to {@code memberCap} of those members per selection. The elements are the members
 * themselves, or with {@code via} the vertices the members reach over one hop: the identifiers a group of ads shares with
 * another, which is Recon's compare in its entities mode.
 * </p>
 * <p>
 * Options: {@code via}, {@code direction} (as {@code selection.venn}), {@code minShared} (default 2), {@code limit}
 * (default 1000), {@code memberCap} (default 10; 0 skips the second walk that collects the evidence).
 * </p>
 * <p>
 * Example:
 * <pre>
 * CALL selection.compare($groups, {via: ['HAS_PHONE', 'HAS_EMAIL'], direction: 'OUT'})
 * YIELD node, selections, sharedBy, counts, members
 * </pre>
 * </p>
 */
public class SelectionCompare extends AbstractSelectionProcedure {
  public static final String NAME = "selection.compare";

  private static final String      MIN_SHARED = "minShared";
  private static final String      LIMIT      = "limit";
  private static final String      MEMBER_CAP = "memberCap";
  private static final Set<String> OPTIONS    = Set.of(VIA, DIRECTION, MIN_SHARED, LIMIT, MEMBER_CAP);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Elements shared by 2 to 8 selections, with the members of each selection that reach them";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("node", "mask", "selections", "sharedBy", "counts", "members");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Database database = context.getDatabase();
    final RidBitmap[] selections = parseSelections(args[0], database);
    final Map<String, Object> options = parseOptions(args, 1, OPTIONS);
    final Expansion expansion = parseExpansion(options, context);
    final int minShared = intOption(options, MIN_SHARED, 2, 1, selections.length);
    final int limit = intOption(options, LIMIT, 1000, 1, Integer.MAX_VALUE);
    final int memberCap = intOption(options, MEMBER_CAP, 10, 0, 10_000);
    final WorkGuard guard = newWorkGuard(context);

    final RidBitmap[] elements = buildElements(context, selections, expansion, guard);

    // ROWS COME OUT REGION BY REGION, MOST SHARED FIRST, SO THE LIMIT KEEPS THE MOST SHARED ELEMENTS WITHOUT A SORT
    final List<RID> rowRids = new ArrayList<>();
    final List<Integer> rowMasks = new ArrayList<>();
    for (final int mask : masksBySharedDescending(elements.length)) {
      if (Integer.bitCount(mask) < minShared || rowRids.size() >= limit)
        break;
      guard.check();
      for (final Iterator<RID> it = region(elements, mask).iterator(database); it.hasNext() && rowRids.size() < limit; ) {
        rowRids.add(it.next());
        rowMasks.add(mask);
      }
    }

    long[][] counts = null;
    List<RID>[][] members = null;
    if (memberCap > 0 && !rowRids.isEmpty()) {
      counts = new long[rowRids.size()][selections.length];
      members = newMembers(rowRids.size(), selections.length);
      // the view buildElements walked: pinned for the statement, so the evidence reads the same graph
      collectEvidence(database, findView(context, expansion), selections, expansion, rowRids, memberCap, counts, members, guard);
    }

    final List<Result> rows = new ArrayList<>(rowRids.size());
    for (int r = 0; r < rowRids.size(); r++) {
      final int mask = rowMasks.get(r);
      final ResultInternal row = new ResultInternal();
      row.setProperty("node", database.lookupByRID(rowRids.get(r), true));
      row.setProperty("mask", mask);
      row.setProperty("selections", selectionIndexes(mask));
      row.setProperty("sharedBy", Integer.bitCount(mask));
      if (counts != null) {
        final List<Long> rowCounts = new ArrayList<>(selections.length);
        for (final long c : counts[r])
          rowCounts.add(c);
        row.setProperty("counts", rowCounts);
        row.setProperty("members", Arrays.asList(members[r]));
      } else {
        row.setProperty("counts", null);
        row.setProperty("members", null);
      }
      rows.add(row);
    }
    return rows.stream();
  }

  /**
   * The second walk: for every returned element, how many members of each selection reach it, and the first
   * {@code memberCap} of them. A member is counted once per element however many entries lead there, which a stamp per
   * row tracks without allocating; the ghost probe is paid once per (member, element) pair, like the first walk's.
   */
  private void collectEvidence(final Database database, final GraphTraversalProvider view, final RidBitmap[] selections,
      final Expansion expansion, final List<RID> rowRids, final int memberCap, final long[][] counts, final List<RID>[][] members,
      final WorkGuard guard) {
    final Map<RID, Integer> rowOf = new HashMap<>(rowRids.size() * 2);
    for (int r = 0; r < rowRids.size(); r++)
      rowOf.put(rowRids.get(r), r);

    final GraphEngine graph = ((DatabaseInternal) database).getGraphEngine();
    final boolean probing = GhostEdgeReporter.isProbing(database);
    final int[] stamp = new int[rowRids.size()];
    Arrays.fill(stamp, -1);
    int memberSeq = 0;
    int counter = 0;

    for (int i = 0; i < selections.length; i++) {
      for (final Iterator<RID> it = selections[i].iterator(database); it.hasNext(); ) {
        guard.checkPeriodically(counter++);
        final RID member = it.next();
        final int seq = memberSeq++;

        if (!expansion.expands()) {
          final Integer r = rowOf.get(member);
          if (r != null)
            record(r, i, member, memberCap, counts, members);
          continue;
        }

        final int node = view != null ? view.getNodeId(member) : -1;
        if (node >= 0) {
          for (final int neighbour : view.getNeighborIds(node, expansion.direction(), expansion.edgeTypes())) {
            final Integer r = rowOf.get(view.getRID(neighbour));
            if (r == null || stamp[r] == seq)
              continue;
            stamp[r] = seq;
            record(r, i, member, memberCap, counts, members);
          }
          continue;
        }

        final VertexInternal vertex = loadVertex(database, member, i);
        if (vertex == null)
          continue;
        for (final Vertex.DIRECTION direction : directions(expansion.direction())) {
          final EdgeEntryRIDIterator entries = graph.getConnectedVertexEntries(vertex, direction, expansion.edgeTypes());
          while (entries.hasNext()) {
            guard.checkPeriodically(counter++);
            final Integer r = rowOf.get(entries.next());
            if (r == null || stamp[r] == seq)
              continue;
            if (probing && !GhostEdgeReporter.isLiveEntry(database, entries.getEdgeRID()))
              continue;
            stamp[r] = seq;
            record(r, i, member, memberCap, counts, members);
          }
        }
      }
    }
  }

  private static void record(final int row, final int selection, final RID member, final int memberCap, final long[][] counts,
      final List<RID>[][] members) {
    counts[row][selection]++;
    if (members[row][selection].size() < memberCap)
      members[row][selection].add(member);
  }

  @SuppressWarnings("unchecked")
  private static List<RID>[][] newMembers(final int rows, final int selections) {
    final List<RID>[][] members = new List[rows][selections];
    for (final List<RID>[] row : members)
      for (int i = 0; i < selections; i++)
        row[i] = new ArrayList<>();
    return members;
  }
}
