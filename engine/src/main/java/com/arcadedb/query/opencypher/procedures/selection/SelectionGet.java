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
 * Procedure: selection.get(name)
 * <p>
 * The metadata and provenance of a live saved selection: refused when there is none or it has expired. Times are epoch
 * milliseconds, and a null {@code expiresAt} means it never expires.
 * </p>
 */
public class SelectionGet extends AbstractSelectionProcedure {
  public static final String NAME = "selection.get";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMaxArgs() {
    return 1;
  }

  @Override
  public String getDescription() {
    return "Metadata and provenance of a saved selection";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("name", "cardinality", "createdAt", "expiresAt", "source", "language", "params", "createdBy");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final SelectionStore.Handle handle = new SelectionStore(context.getDatabase(), getName()).require(
        extractString(args[0], "name"));

    final ResultInternal row = new ResultInternal();
    row.setProperty("name", handle.name());
    row.setProperty("cardinality", handle.cardinality());
    row.setProperty("createdAt", handle.createdAt());
    row.setProperty("expiresAt", handle.expiresAt());
    row.setProperty("source", handle.source());
    row.setProperty("language", handle.language());
    row.setProperty("params", handle.params());
    row.setProperty("createdBy", handle.createdBy());
    return Stream.of(row);
  }
}
