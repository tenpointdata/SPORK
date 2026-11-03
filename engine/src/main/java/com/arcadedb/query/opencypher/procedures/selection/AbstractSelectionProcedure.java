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
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.graph.EdgeEntryRIDIterator;
import com.arcadedb.graph.GhostEdgeReporter;
import com.arcadedb.graph.GraphEngine;
import com.arcadedb.graph.GraphTraversalProvider;
import com.arcadedb.graph.GraphTraversalProviderRegistry;
import com.arcadedb.graph.Vertex;
import com.arcadedb.graph.VertexInternal;
import com.arcadedb.query.opencypher.procedures.algo.AbstractAlgoProcedure;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.utility.RidBitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared parsing and set building of the {@code selection.*} procedures (docs/spork/03-f1-selections-design.md).
 * <p>
 * A call names 2 to 8 selections. The ELEMENTS of a selection are its members themselves, or, with the {@code via} option, the
 * vertices its members reach over one hop of the given edge types. Members that no longer exist are skipped, because a saved
 * selection can go stale; a member string that is not a RID is refused.
 */
public abstract class AbstractSelectionProcedure extends AbstractAlgoProcedure {
  static final int    MIN_SELECTIONS = 2;
  static final int    MAX_SELECTIONS = 8;
  static final String VIA            = "via";
  static final String DIRECTION      = "direction";
  static final String ALL_TYPES      = "*";

  @Override
  public int getMinArgs() {
    return 1;
  }

  @Override
  public int getMaxArgs() {
    return 2;
  }

  /** The expansion a call asked for: {@code edgeTypes} null means "the members themselves", empty means every edge type. */
  protected record Expansion(String[] edgeTypes, Vertex.DIRECTION direction) {
    boolean expands() {
      return edgeTypes != null;
    }
  }

  /** The options map at {@code index}, every key checked against {@code allowedKeys}; absent or null is an empty map. */
  protected Map<String, Object> parseOptions(final Object[] args, final int index, final Set<String> allowedKeys) {
    final Map<String, Object> options = extractConfig(argOrDefault(args, index, null), "options", allowedKeys);
    return options != null ? options : Map.of();
  }

  protected Expansion parseExpansion(final Map<String, Object> options, final CommandContext context) {
    final Vertex.DIRECTION direction = parseDirection(extractString(options.get(DIRECTION), DIRECTION));
    final Object via = options.get(VIA);
    if (via == null)
      return new Expansion(null, direction);
    if (ALL_TYPES.equals(via))
      return new Expansion(new String[0], direction);
    final String[] types = extractRelTypes(via, context);
    return new Expansion(types == null ? new String[0] : types, direction);
  }

  /**
   * Parses the members of every selection, deduplicated per selection, in RID order. A selection is a list of members or, as
   * a string, the name of a saved selection; a string INSIDE a list is a member RID.
   */
  protected RidBitmap[] parseSelections(final Object arg, final Database database) {
    if (!(arg instanceof List<?> lists) || lists.size() < MIN_SELECTIONS || lists.size() > MAX_SELECTIONS)
      throw new IllegalArgumentException(getName() + "(): selections must hold " + MIN_SELECTIONS + " to " + MAX_SELECTIONS
          + " lists, got " + (arg instanceof List<?> l ? l.size() : arg == null ? "null" : arg.getClass().getSimpleName()));

    final RidBitmap[] selections = new RidBitmap[lists.size()];
    for (int i = 0; i < selections.length; i++) {
      final Object list = lists.get(i);
      selections[i] = list instanceof String handleName ?
          new SelectionStore(database, getName()).require(handleName).members() :
          parseMembers(list, "selections[" + i + "]");
    }
    return selections;
  }

  /** The members of one list (or one lone member), deduplicated, in RID order; {@code label} names the argument in a refusal. */
  protected RidBitmap parseMembers(final Object list, final String label) {
    final RidBitmap members = new RidBitmap();
    if (list instanceof Iterable<?> items) {
      for (final Object item : items)
        members.add(toRid(item, label));
    } else if (list != null)
      members.add(toRid(list, label));
    return members;
  }

  /** The row {@code selection.save} and {@code selection.combine} yield for the handle they wrote. */
  protected static Result handleRow(final SelectionStore.Handle handle) {
    final ResultInternal row = new ResultInternal();
    row.setProperty("name", handle.name());
    row.setProperty("cardinality", handle.cardinality());
    row.setProperty("createdAt", handle.createdAt());
    row.setProperty("expiresAt", handle.expiresAt());
    return row;
  }

  protected boolean booleanOption(final Map<String, Object> options, final String key, final boolean defaultValue) {
    final Object value = options.get(key);
    if (value == null)
      return defaultValue;
    if (value instanceof Boolean b)
      return b;
    throw new IllegalArgumentException(
        getName() + "(): option " + key + " must be a boolean, got " + value.getClass().getSimpleName());
  }

  /**
   * The elements of every selection: the live members, or with an expansion the vertices they reach.
   * <p>
   * An expansion walks the CSR of a graph analytical view when one can answer it ({@link #findView}): no record is loaded. A
   * member the view does not map, and every member when there is no usable view, takes the record walk, where a neighbour
   * reached only through a ghost entry (an adjacency entry whose edge record is gone) is not an element; the probe is paid once
   * per vertex reached, not once per entry, as the engine's other visited-set walks do. The view follows entries by contract,
   * as every analytical view does (docs/spork/03-f1-selections-design.md, 2.8).
   */
  protected RidBitmap[] buildElements(final CommandContext context, final RidBitmap[] selections, final Expansion expansion,
      final WorkGuard guard) {
    final Database database = context.getDatabase();
    final GraphEngine graph = ((DatabaseInternal) database).getGraphEngine();
    final boolean probing = GhostEdgeReporter.isProbing(database);
    final GraphTraversalProvider view = findView(context, expansion);
    final RidBitmap[] elements = new RidBitmap[selections.length];
    int counter = 0;

    for (int i = 0; i < selections.length; i++) {
      final RidBitmap result = new RidBitmap();
      for (final Iterator<RID> it = selections[i].iterator(database); it.hasNext(); ) {
        guard.checkPeriodically(counter++);
        final RID member = it.next();
        if (!expansion.expands()) {
          if (database.existsRecord(member))
            result.add(member);
          continue;
        }

        final int node = view != null ? view.getNodeId(member) : -1;
        if (node >= 0) {
          for (final int neighbour : view.getNeighborIds(node, expansion.direction(), expansion.edgeTypes()))
            result.add(view.getRID(neighbour));
          continue;
        }

        final VertexInternal vertex = loadVertex(database, member, i);
        if (vertex == null)
          continue;
        for (final Vertex.DIRECTION direction : directions(expansion.direction())) {
          final EdgeEntryRIDIterator entries = graph.getConnectedVertexEntries(vertex, direction, expansion.edgeTypes());
          while (entries.hasNext()) {
            guard.checkPeriodically(counter++);
            final RID neighbour = entries.next();
            if (result.contains(neighbour))
              continue;
            if (probing && !GhostEdgeReporter.isLiveEntry(database, entries.getEdgeRID()))
              continue;
            result.add(neighbour);
          }
        }
      }
      elements[i] = result;
    }
    return elements;
  }

  /**
   * A graph analytical view that can answer the expansion for the whole statement, or null for the record walk: ready, covering
   * the {@code via} edge types (every type for {@code '*'}) and every vertex type, pinned to one snapshot, and none while the
   * transaction holds uncommitted changes, which only the records show.
   */
  protected static GraphTraversalProvider findView(final CommandContext context, final Expansion expansion) {
    return expansion.expands() ? GraphTraversalProviderRegistry.findProvider(context, expansion.edgeTypes()) : null;
  }

  /**
   * The Venn region of {@code mask}: the elements in every selection of the mask and in none outside it. Built from bitmap
   * operations, never per element.
   */
  protected static RidBitmap region(final RidBitmap[] elements, final int mask) {
    RidBitmap region = null;
    for (int i = 0; i < elements.length; i++)
      if ((mask & (1 << i)) != 0) {
        region = region == null ? elements[i].copy() : region.andWith(elements[i]);
        if (region.isEmpty())
          return region;
      }
    for (int i = 0; i < elements.length && !region.isEmpty(); i++)
      if ((mask & (1 << i)) == 0)
        region.andNotWith(elements[i]);
    return region;
  }

  /** Every non-empty mask, most selections first, then by mask value: the order rows come out in. */
  protected static int[] masksBySharedDescending(final int selectionCount) {
    final int total = (1 << selectionCount) - 1;
    final int[] masks = new int[total];
    int n = 0;
    for (int shared = selectionCount; shared >= 1; shared--)
      for (int m = 1; m <= total; m++)
        if (Integer.bitCount(m) == shared)
          masks[n++] = m;
    return masks;
  }

  protected static List<Integer> selectionIndexes(final int mask) {
    final List<Integer> indexes = new ArrayList<>(Integer.bitCount(mask));
    for (int i = 0; i < Integer.SIZE; i++)
      if ((mask & (1 << i)) != 0)
        indexes.add(i);
    return Collections.unmodifiableList(indexes);
  }

  protected static Vertex.DIRECTION[] directions(final Vertex.DIRECTION direction) {
    return direction == Vertex.DIRECTION.BOTH ?
        new Vertex.DIRECTION[] { Vertex.DIRECTION.OUT, Vertex.DIRECTION.IN } :
        new Vertex.DIRECTION[] { direction };
  }

  /** The member as a vertex, or null when it no longer exists. A member that exists but is not a vertex cannot be expanded. */
  protected VertexInternal loadVertex(final Database database, final RID member, final int selectionIndex) {
    if (!database.existsRecord(member))
      return null;
    final Record record = database.lookupByRID(member, true);
    if (record instanceof VertexInternal vertex)
      return vertex;
    throw new IllegalArgumentException(getName() + "(): selections[" + selectionIndex + "] holds " + member
        + ", which is not a node and cannot be expanded via edges");
  }

  protected int intOption(final Map<String, Object> options, final String key, final int defaultValue, final int minimum,
      final int maximum) {
    final Object value = options.get(key);
    if (value == null)
      return defaultValue;
    if (!(value instanceof Number number))
      throw new IllegalArgumentException(
          getName() + "(): option " + key + " must be a number, got " + value.getClass().getSimpleName());
    final long v = number.longValue();
    if (v < minimum || v > maximum)
      throw new IllegalArgumentException(
          getName() + "(): option " + key + " must be between " + minimum + " and " + maximum + ", got " + v);
    return (int) v;
  }

  private RID toRid(final Object item, final String label) {
    final RID rid = switch (item) {
      case Identifiable identifiable -> identifiable.getIdentity();
      case Result result -> result.getIdentity().orElse(null);
      case String s -> parseRid(s);
      case null -> null;
      default -> null;
    };
    if (rid == null || rid.getBucketId() < 0 || rid.getPosition() < 0)
      throw new IllegalArgumentException(getName() + "(): " + label + " holds '" + item
          + "', expected a node, a RID or a RID string such as '#12:5'");
    return rid;
  }

  private static RID parseRid(final String value) {
    try {
      return new RID(value.trim());
    } catch (final RuntimeException e) {
      return null;
    }
  }
}
