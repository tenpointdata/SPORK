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

import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.utility.RidBitmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: selection.venn(selections, options?)
 * <p>
 * The size of every non-empty Venn region of 2 to 8 selections: the elements in exactly the selections of the region's
 * mask. The elements are the members themselves, or with {@code via} the vertices the members reach over one hop.
 * Options: {@code via} (edge types, or {@code '*'} for every type; absent compares the members), {@code direction}
 * ({@code OUT}, {@code IN} or {@code BOTH}, default {@code BOTH}).
 * </p>
 * <p>
 * Example: which phones and emails three groups of ads share
 * <pre>
 * CALL selection.venn($groups, {via: ['HAS_PHONE', 'HAS_EMAIL'], direction: 'OUT'})
 * YIELD mask, selections, count
 * </pre>
 * </p>
 */
public class SelectionVenn extends AbstractSelectionProcedure {
  public static final String NAME = "selection.venn";

  private static final Set<String> OPTIONS = Set.of(VIA, DIRECTION);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Size of every Venn region of 2 to 8 selections, over their members or the vertices they reach";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("mask", "selections", "count");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final RidBitmap[] selections = parseSelections(args[0], context.getDatabase());
    final Map<String, Object> options = parseOptions(args, 1, OPTIONS);
    final Expansion expansion = parseExpansion(options, context);
    final WorkGuard guard = newWorkGuard(context);

    final RidBitmap[] elements = buildElements(context, selections, expansion, guard);

    final List<Result> rows = new ArrayList<>();
    for (final int mask : masksBySharedDescending(elements.length)) {
      guard.check();
      final long count = region(elements, mask).cardinality();
      if (count == 0)
        continue;
      final ResultInternal row = new ResultInternal();
      row.setProperty("mask", mask);
      row.setProperty("selections", selectionIndexes(mask));
      row.setProperty("count", count);
      rows.add(row);
    }
    return rows.stream();
  }
}
