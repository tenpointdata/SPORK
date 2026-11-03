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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.server.security.ServerSecurityUser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The person bound as the engine user on this thread, per database, for as long as one chunk runs: grants are checked before the
 * first read of each database, and every binding is undone afterwards so nothing leaks onto a pooled worker or back onto the
 * request thread.
 */
final class EngineUserBinding {
  private final ServerSecurityUser                 user;
  private final Map<DatabaseInternal, SecurityDatabaseUser> previous = new HashMap<>(4);
  private final Set<DatabaseInternal>              created  = new HashSet<>(4);

  EngineUserBinding(final ServerSecurityUser user) {
    this.user = user;
  }

  Database bind(final Database database) {
    if (database == null)
      return null;
    final DatabaseInternal internal = (DatabaseInternal) database;
    if (previous.containsKey(internal) || created.contains(internal))
      return database;
    if (!user.canAccessToDatabase(database.getName()))
      throw new SecurityException("User '" + user.getName() + "' is not allowed to access database '" + database.getName() + "'");
    DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(internal.getDatabasePath());
    if (context == null) {
      context = DatabaseContext.INSTANCE.init(internal);
      created.add(internal);
    } else
      previous.put(internal, context.getCurrentUser());
    context.setCurrentUser(user.getDatabaseUser(database));
    return database;
  }

  void release() {
    for (final Map.Entry<DatabaseInternal, SecurityDatabaseUser> entry : previous.entrySet()) {
      final DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(entry.getKey().getDatabasePath());
      if (context != null)
        context.setCurrentUser(entry.getValue());
    }
    for (final DatabaseInternal database : created) {
      final DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(database.getDatabasePath());
      if (context != null) {
        context.setCurrentUser(null);
        if (!context.hasActiveTransaction())
          DatabaseContext.INSTANCE.removeContext(database.getDatabasePath());
      }
    }
  }
}
