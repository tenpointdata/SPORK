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
package com.arcadedb.query.search;

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.fulltext.FullTextSearch;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.BasicCommandContext;
import com.arcadedb.query.sql.executor.RIDFilter;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.RidBitmapSet;

import java.util.List;
import java.util.Map;

/**
 * The {@code selection} argument of the search services (docs/spork/03-f1-selections-design.md, 2.5): the name of a saved
 * selection the search stays inside. A vector leg hands it to {@code vector.neighbors} as its option of the same name; a
 * full-text leg turns it into the {@link RIDFilter} its index tests each match's RID against before scoring it.
 */
final class SearchSelection {
  static final String ARGUMENT = "selection";

  private SearchSelection() {
  }

  /** The selection a request names, or null for none; anything but a non-blank string is refused before any I/O. */
  static String of(final JSONObject args) {
    if (!args.has(ARGUMENT) || args.isNull(ARGUMENT))
      return null;
    final Object value = args.get(ARGUMENT);
    if (!(value instanceof String name) || name.isBlank())
      throw new IllegalArgumentException("'selection' must be the name of a saved selection, got " + value);
    return name;
  }

  /** The full-text search of {@code queryText}, restricted to the saved selection {@code selection} when it is not null. */
  static Map<RID, Float> fullTextSearch(final Database database, final TypeIndex typeIndex, final String queryText,
      final int limit, final String selection) {
    if (selection == null)
      return FullTextSearch.search(typeIndex, queryText, limit);

    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    final RidBitmapSet members = new RidBitmapSet(new SelectionStore(database, ARGUMENT).require(selection).members());
    final RIDFilter filter = RIDFilter.resolve(List.of(), members, context);
    return FullTextSearch.search(typeIndex, queryText, limit, WorkGuard.forCommandDeadline(null), null, filter);
  }
}
