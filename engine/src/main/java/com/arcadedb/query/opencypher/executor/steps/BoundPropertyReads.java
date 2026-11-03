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
package com.arcadedb.query.opencypher.executor.steps;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Binary;
import com.arcadedb.database.Database;
import com.arcadedb.database.ImmutableDocument;
import com.arcadedb.engine.Dictionary;
import com.arcadedb.query.opencypher.ast.PropertyAccessExpression;
import com.arcadedb.query.opencypher.ast.ReturnClause;
import com.arcadedb.query.opencypher.temporal.TemporalUtil;
import com.arcadedb.query.sql.executor.Result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code n.a, n.b, n.c} items of a RETURN or WITH that read the same bound record, located in ONE pass over the
 * record header per row instead of one pass per item (SPORK retrieval speed, item S4, on the primitive of issue #9496:
 * {@link ImmutableDocument#locateProperties} and {@link ImmutableDocument#getPropertyAt}).
 * <p>
 * Covers only what a plain {@code variable.property} item reads of a variable bound to an immutable record read from
 * disk, which is the row of a scan, a seek or an expansion: every other item, and every row whose variable is bound to
 * anything else (a mutable record, a RID, a map, a nested row), is evaluated as it always was. The value of a covered
 * item is exactly what {@code PropertyAccessExpression} answers, through the same {@link TemporalUtil#convertFromStorage}
 * reading of the stored value.
 * <p>
 * One instance serves the rows of one result set, one after the other, on one thread: it holds the per-row positions.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
final class BoundPropertyReads {
  // A DICTIONARY ID PAST THIS IS NOT INDEXED BY ID: ITS ITEM IS EVALUATED ON ITS OWN
  private static final int MAX_DICTIONARY_ID = 1 << 16;

  // PER GROUP (ONE GROUP PER VARIABLE WITH AT LEAST TWO PROPERTY READS): THE DISTINCT NAMES READ, AND WHERE EACH IS, BY ID
  private final String[]   variables;
  private final String[][] names;
  private final int[][]    slotByNameId;
  private final int[][]    positions;
  // PER ROW, PER GROUP: THE RECORD THE VARIABLE IS BOUND TO AND THE CONTENT THE POSITIONS REFER TO, NULL WHEN NOT COVERED
  private final ImmutableDocument[] documents;
  private final Binary[]            located;
  // PER ITEM: THE GROUP AND THE SLOT OF ITS NAME IN IT, -1 FOR AN ITEM EVALUATED ON ITS OWN
  private final int[] itemGroup;
  private final int[] itemSlot;

  private BoundPropertyReads(final String[] variables, final String[][] names, final int[][] slotByNameId, final int[] itemGroup,
      final int[] itemSlot) {
    this.variables = variables;
    this.names = names;
    this.slotByNameId = slotByNameId;
    this.itemGroup = itemGroup;
    this.itemSlot = itemSlot;
    this.positions = new int[variables.length][];
    for (int g = 0; g < variables.length; g++)
      positions[g] = new int[names[g].length];
    this.documents = new ImmutableDocument[variables.length];
    this.located = new Binary[variables.length];
  }

  /**
   * The reads of {@code items} worth locating together, or null when no variable is read for two or more properties by
   * plain {@code variable.property} items (a single read walks the header once either way), or when
   * {@code arcadedb.queryProjectionPropertyCache} is off.
   */
  static BoundPropertyReads of(final List<ReturnClause.ReturnItem> items, final Database database) {
    if (items == null || items.size() < 2 || database == null
        || !database.getConfiguration().getValueAsBoolean(GlobalConfiguration.QUERY_PROJECTION_PROPERTY_CACHE))
      return null;

    final Dictionary dictionary = database.getSchema().getDictionary();
    // VARIABLE -> THE INDEXES OF THE ITEMS THAT READ A PROPERTY OF IT
    final Map<String, List<Integer>> readsByVariable = new LinkedHashMap<>();
    for (int i = 0; i < items.size(); i++) {
      final ReturnClause.ReturnItem item = items.get(i);
      if (item.isStar() || !(item.getExpression() instanceof PropertyAccessExpression access))
        continue;
      final int nameId = dictionary.getIdByName(access.getPropertyName(), false);
      // A NAME THE DICTIONARY DOES NOT KNOW IS IN NO RECORD: LEFT TO THE EXPRESSION, WHICH ANSWERS IT AS IT ALWAYS DID
      if (nameId < 0 || nameId >= MAX_DICTIONARY_ID)
        continue;
      readsByVariable.computeIfAbsent(access.getVariableName(), k -> new ArrayList<>(2)).add(i);
    }
    readsByVariable.values().removeIf(reads -> reads.size() < 2);
    if (readsByVariable.isEmpty())
      return null;

    final int groups = readsByVariable.size();
    final String[] variables = new String[groups];
    final String[][] names = new String[groups][];
    final int[][] slotByNameId = new int[groups][];
    final int[] itemGroup = new int[items.size()];
    final int[] itemSlot = new int[items.size()];
    Arrays.fill(itemGroup, -1);
    Arrays.fill(itemSlot, -1);

    int g = 0;
    for (final Map.Entry<String, List<Integer>> entry : readsByVariable.entrySet()) {
      final List<Integer> reads = entry.getValue();
      variables[g] = entry.getKey();
      // THE DISTINCT NAMES, IN THE ORDER THEY ARE FIRST READ: n.a AS x, n.a AS y IS ONE NAME LOCATED ONCE
      final List<String> distinct = new ArrayList<>(reads.size());
      final List<Integer> distinctIds = new ArrayList<>(reads.size());
      for (final int item : reads) {
        final PropertyAccessExpression access = (PropertyAccessExpression) items.get(item).getExpression();
        int slot = distinct.indexOf(access.getPropertyName());
        if (slot < 0) {
          slot = distinct.size();
          distinct.add(access.getPropertyName());
          distinctIds.add(dictionary.getIdByName(access.getPropertyName(), false));
        }
        itemGroup[item] = g;
        itemSlot[item] = slot;
      }
      names[g] = distinct.toArray(new String[0]);
      int maxId = -1;
      for (final int id : distinctIds)
        maxId = Math.max(maxId, id);
      slotByNameId[g] = new int[maxId + 1];
      Arrays.fill(slotByNameId[g], -1);
      for (int s = 0; s < distinctIds.size(); s++)
        slotByNameId[g][distinctIds.get(s)] = s;
      g++;
    }
    return new BoundPropertyReads(variables, names, slotByNameId, itemGroup, itemSlot);
  }

  /** Starts a row: locates, for every variable bound to an immutable record in it, the properties its items read. */
  void begin(final Result row) {
    for (int g = 0; g < variables.length; g++) {
      located[g] = null;
      documents[g] = null;
      final Object bound = row.getProperty(variables[g]);
      if (bound instanceof ImmutableDocument document) {
        located[g] = document.locateProperties(slotByNameId[g], positions[g], names[g].length);
        if (located[g] != null)
          documents[g] = document;
      }
    }
  }

  /** Whether the item at {@code index} is answered by {@link #value} for the row {@link #begin} was given. */
  boolean covers(final int index) {
    final int g = itemGroup[index];
    return g >= 0 && located[g] != null;
  }

  /** The value of the item at {@code index}: what its {@code PropertyAccessExpression} answers on the row. */
  Object value(final int index) {
    final int g = itemGroup[index];
    final int slot = itemSlot[index];
    final String name = names[g][slot];
    final ImmutableDocument document = documents[g];
    final int position = positions[g][slot];
    final Object raw = position < 0 ? null : document.getPropertyAt(located[g], name, position, null);
    return TemporalUtil.convertFromStorage(document, name, raw);
  }
}
