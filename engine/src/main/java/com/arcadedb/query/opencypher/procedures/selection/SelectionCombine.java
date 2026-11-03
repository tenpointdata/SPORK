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
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.utility.RidBitmap;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * Procedure: selection.combine(op, inputs, name, options?)
 * <p>
 * Saves the set algebra of 2 to 8 inputs (saved selection names or member lists) under {@code name}: {@code and} (members of
 * every input), {@code or} (of any), {@code andNot} (of the first and none of the others) or {@code xor} (of an odd number of
 * inputs). The handle's source records the operation, as {@code combine:and(x, y)}.
 * </p>
 * <p>
 * Options: {@code ttl} (seconds, default 86400; 0 never expires), {@code replace} (default false).
 * </p>
 */
public class SelectionCombine extends AbstractSelectionProcedure {
  public static final String NAME = "selection.combine";

  private static final Set<String> OPTIONS = Set.of(SelectionSave.TTL, SelectionSave.REPLACE);

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
    return 4;
  }

  @Override
  public String getDescription() {
    return "Save the and, or, andNot or xor of 2 to 8 selections under a name";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("name", "cardinality", "createdAt", "expiresAt");
  }

  @Override
  public boolean isWriteProcedure() {
    return true;
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Database database = context.getDatabase();
    final String op = extractString(args[0], "op");
    final RidBitmap[] inputs = parseSelections(args[1], database);
    final String name = extractString(args[2], "name");
    final Map<String, Object> options = parseOptions(args, 3, OPTIONS);
    final int ttl = intOption(options, SelectionSave.TTL, (int) SelectionStore.DEFAULT_TTL_SECONDS, 0, Integer.MAX_VALUE);
    final boolean replace = booleanOption(options, SelectionSave.REPLACE, false);

    final RidBitmap result = inputs[0].copy();
    for (int i = 1; i < inputs.length; i++) {
      switch (op) {
      case "and" -> result.andWith(inputs[i]);
      case "or" -> result.orWith(inputs[i]);
      case "andNot" -> result.andNotWith(inputs[i]);
      case "xor" -> result.xorWith(inputs[i]);
      case null, default -> throw new IllegalArgumentException(
          getName() + "(): unknown op '" + op + "', expected one of and, or, andNot, xor");
      }
    }

    final SelectionStore.Handle handle = new SelectionStore(database, getName()).save(name, result, ttl,
        describe(op, (List<?>) args[1], inputs), "selection", null, replace);
    return Stream.of(handleRow(handle));
  }

  /** {@code combine:<op>(<input>, ...)}, naming saved inputs and sizing the inline lists. */
  private static String describe(final String op, final List<?> rawInputs, final RidBitmap[] inputs) {
    final StringJoiner joiner = new StringJoiner(", ", "combine:" + op + "(", ")");
    for (int i = 0; i < inputs.length; i++)
      joiner.add(rawInputs.get(i) instanceof String handleName ? handleName : "list(" + inputs[i].cardinality() + ")");
    return joiner.toString();
  }
}
