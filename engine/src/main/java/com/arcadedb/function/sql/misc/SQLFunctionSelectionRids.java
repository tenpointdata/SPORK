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
package com.arcadedb.function.sql.misc;

import com.arcadedb.database.Identifiable;
import com.arcadedb.exception.CommandSQLParsingException;
import com.arcadedb.function.sql.SQLFunctionAbstract;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.utility.RidBitmapSet;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code selection.rids(<name>)}: the members of a saved selection as a read-only {@code Set<RID>} backed by its bitmap
 * (docs/spork/03-f1-selections-design.md, 2.5). Its purpose is {@code @rid IN selection.rids('g0')}: the planner pushes that
 * condition into an indexed function (full-text {@code SEARCH_INDEX}, the geo predicates) as the allow-list its index tests
 * each candidate against, so a search over a selection never loads a record outside it.
 * <p>
 * The decoded set is cached per command context, so a plain scan that evaluates the condition once per record decodes the
 * saved bitmap once. An unknown or expired selection is refused.
 */
public class SQLFunctionSelectionRids extends SQLFunctionAbstract {
  public static final String NAME = "selection.rids";

  /** One variable holding every set this command decoded: a variable name with a dot in it would be read as a field access. */
  private static final String CACHE_VARIABLE = "__selectionRidsCache";

  public SQLFunctionSelectionRids() {
    super(NAME);
  }

  @Override
  public int getMinArgs() {
    return 1;
  }

  @Override
  public int getMaxArgs() {
    return 1;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object execute(final Object self, final Identifiable currentRecord, final Object currentResult, final Object[] params,
      final CommandContext context) {
    if (params == null || params.length != 1 || !(params[0] instanceof String name))
      throw new CommandSQLParsingException(NAME + "() takes the name of a saved selection: " + getSyntax());

    Map<String, RidBitmapSet> cache = (Map<String, RidBitmapSet>) context.getVariable(CACHE_VARIABLE);
    if (cache == null) {
      cache = new HashMap<>();
      context.setVariable(CACHE_VARIABLE, cache);
    }
    RidBitmapSet set = cache.get(name);
    if (set == null) {
      set = new RidBitmapSet(new SelectionStore(context.getDatabase(), NAME).require(name).members());
      cache.put(name, set);
    }
    return set;
  }

  @Override
  public String getSyntax() {
    return NAME + "(<selection name>)";
  }
}
