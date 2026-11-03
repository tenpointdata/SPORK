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

import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.util.List;
import java.util.stream.Stream;

/**
 * Procedure: selection.purge()
 * <p>
 * Deletes every expired saved selection. Writes delete expired selections a batch at a time as they go, so this is only
 * needed to reclaim them all at once.
 * </p>
 */
public class SelectionPurge extends AbstractSelectionProcedure {
  public static final String NAME = "selection.purge";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 0;
  }

  @Override
  public int getMaxArgs() {
    return 0;
  }

  @Override
  public String getDescription() {
    return "Delete every expired saved selection";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("purged");
  }

  @Override
  public boolean isWriteProcedure() {
    return true;
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final ResultInternal row = new ResultInternal();
    row.setProperty("purged", new SelectionStore(context.getDatabase(), getName()).purgeExpired(0));
    return Stream.of(row);
  }
}
