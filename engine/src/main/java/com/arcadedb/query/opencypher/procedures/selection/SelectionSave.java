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
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.utility.RidBitmap;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: selection.save(name, source, options?)
 * <p>
 * Saves a selection under {@code name} (docs/spork/03-f1-selections-design.md, 2.3). {@code source} is a list of members
 * (nodes, RIDs or RID strings) or a query string, run read-only under the caller's user: every record identity in a row, or in
 * a list inside a row, becomes a member, and the query, its language and its parameters are kept as the handle's provenance.
 * </p>
 * <p>
 * Options: {@code ttl} (seconds, default 86400; 0 never expires), {@code replace} (default false), {@code language} (of a
 * query source, default {@code opencypher}), {@code params} (of a query source).
 * </p>
 * <p>
 * Example:
 * <pre>
 * CALL selection.save('on-p1', 'MATCH (a:Ad)-[:HAS_PHONE]->(:Phone {number: $n}) RETURN a', {params: {n: '5551234'}})
 * YIELD name, cardinality
 * </pre>
 * </p>
 */
public class SelectionSave extends AbstractSelectionProcedure {
  public static final String NAME = "selection.save";

  static final String TTL      = "ttl";
  static final String REPLACE  = "replace";
  static final String LANGUAGE = "language";
  static final String PARAMS   = "params";

  private static final Set<String> OPTIONS          = Set.of(TTL, REPLACE, LANGUAGE, PARAMS);
  private static final String      DEFAULT_LANGUAGE = "opencypher";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 3;
  }

  @Override
  public String getDescription() {
    return "Save a selection, from a list of members or a read-only query, under a name with an expiry";
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
    final String name = extractString(args[0], "name");
    final Map<String, Object> options = parseOptions(args, 2, OPTIONS);
    final int ttl = intOption(options, TTL, (int) SelectionStore.DEFAULT_TTL_SECONDS, 0, Integer.MAX_VALUE);
    final boolean replace = booleanOption(options, REPLACE, false);

    final RidBitmap members;
    final String source;
    final String language;
    final Map<String, Object> params;
    if (args[1] instanceof String query) {
      language = options.get(LANGUAGE) != null ? extractString(options.get(LANGUAGE), LANGUAGE) : DEFAULT_LANGUAGE;
      params = extractMap(options.get(PARAMS), PARAMS);
      members = collectQuery(database, language, query, params, newWorkGuard(context));
      source = query;
    } else {
      if (options.containsKey(LANGUAGE) || options.containsKey(PARAMS))
        throw new IllegalArgumentException(getName() + "(): options language and params apply to a query source only");
      if (args[1] instanceof Map<?, ?> map) {
        members = decodeBitmap(map);
        source = "bitmap";
      } else {
        members = parseMembers(args[1], "source");
        source = "members";
      }
      language = null;
      params = null;
    }

    final SelectionStore.Handle handle = new SelectionStore(database, getName()).save(name, members, ttl, source, language, params,
        replace);
    return Stream.of(handleRow(handle));
  }

  /**
   * A {@code {bitmap: '<base64>'}} source: the members in the {@link RidBitmap#toBytes()} envelope, as {@code selection.get}
   * exports them over HTTP, so a client can do set algebra locally and save the result without shipping RID lists.
   */
  private RidBitmap decodeBitmap(final Map<?, ?> map) {
    if (map.size() != 1 || !(map.get("bitmap") instanceof String encoded))
      throw new IllegalArgumentException(getName() + "(): a map source must be {bitmap: '<base64>'}");
    try {
      return RidBitmap.fromBytes(Base64.getDecoder().decode(encoded));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(getName() + "(): source.bitmap is not a base64 RID bitmap: " + e.getMessage(), e);
    }
  }

  /** Runs the query read-only and keeps every record identity it returns: whole rows, properties, and lists inside them. */
  private static RidBitmap collectQuery(final Database database, final String language, final String query,
      final Map<String, Object> params, final WorkGuard guard) {
    final RidBitmap members = new RidBitmap();
    int counter = 0;
    try (final ResultSet rs = database.query(language, query, params != null ? params : Map.of())) {
      while (rs.hasNext()) {
        guard.checkPeriodically(counter++);
        final Result row = rs.next();
        if (row.isElement()) {
          row.getIdentity().ifPresent(rid -> addIdentity(members, rid));
          continue;
        }
        for (final String property : row.getPropertyNames())
          addIdentities(members, row.getProperty(property));
      }
    }
    return members;
  }

  private static void addIdentities(final RidBitmap members, final Object value) {
    switch (value) {
    case Identifiable identifiable -> addIdentity(members, identifiable.getIdentity());
    case Result result -> result.getIdentity().ifPresent(rid -> addIdentity(members, rid));
    case Iterable<?> items -> {
      for (final Object item : items)
        addIdentities(members, item);
    }
    case null, default -> {
    }
    }
  }

  private static void addIdentity(final RidBitmap members, final RID rid) {
    // A projection can carry an identity that names no record (a temporary or a type marker): not a member
    if (rid != null && rid.getBucketId() >= 0 && rid.getPosition() >= 0)
      members.add(rid);
  }
}
