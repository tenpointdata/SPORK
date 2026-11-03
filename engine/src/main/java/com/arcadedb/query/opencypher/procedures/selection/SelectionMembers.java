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
import com.arcadedb.database.RID;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: selection.members(name, options?)
 * <p>
 * The live members of a saved selection, in RID order, one row per member. Members deleted since the selection was saved are
 * skipped and do not count toward {@code skip} or {@code limit}.
 * </p>
 * <p>
 * Options: {@code skip} (default 0), {@code limit} (default 1000).
 * </p>
 */
public class SelectionMembers extends AbstractSelectionProcedure {
  public static final String NAME = "selection.members";

  private static final String      SKIP    = "skip";
  private static final String      LIMIT   = "limit";
  private static final Set<String> OPTIONS = Set.of(SKIP, LIMIT);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "The live members of a saved selection, in RID order";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("node");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Database database = context.getDatabase();
    final SelectionStore.Handle handle = new SelectionStore(database, getName()).require(extractString(args[0], "name"));
    final Map<String, Object> options = parseOptions(args, 1, OPTIONS);
    final int skip = intOption(options, SKIP, 0, 0, Integer.MAX_VALUE);
    final int limit = intOption(options, LIMIT, 1000, 1, Integer.MAX_VALUE);
    final WorkGuard guard = newWorkGuard(context);

    final List<Result> rows = new ArrayList<>();
    int live = 0;
    int counter = 0;
    for (final Iterator<RID> it = handle.members().iterator(database); it.hasNext() && rows.size() < limit; ) {
      guard.checkPeriodically(counter++);
      final RID member = it.next();
      if (!database.existsRecord(member) || live++ < skip)
        continue;
      final ResultInternal row = new ResultInternal();
      row.setProperty("node", database.lookupByRID(member, true));
      rows.add(row);
    }
    return rows.stream();
  }
}
