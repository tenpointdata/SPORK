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

import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.log.LogManager;
import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.audit.AuditActor;
import com.arcadedb.server.audit.AuditChain;
import com.arcadedb.server.audit.AuditEvent;
import com.arcadedb.server.security.ServerSecurityUser;

import java.util.logging.Level;

/**
 * Appends the plugin's entries to the analyst chain in {@code auditDatabase} (01-architecture 5.2, 07-entry-identity-and-audit.md
 * 2.4) and fails closed: when the entry cannot be committed, the caller gets a 503 and the response it would have released is
 * dropped.
 * <p>
 * A read spans databases ({@code recdir}, {@code rec_<p>}) and a transaction cannot, so a read route commits its entry on the
 * chain's database after the read and before the release: nothing leaves the server that the chain does not already hold. A
 * write route will append inside the transaction of the write itself, which {@link AuditChain#append} already requires.
 * <p>
 * The append runs as the system, not as the person: the person's engine grants decide what they may read, and nobody needs a
 * grant on {@code AuditEntry} to be audited.
 */
final class SporkAudit {
  /** Concurrent appends conflict on the single chain head and retry under MVCC. */
  private static final int ATTEMPTS = 20;

  private final ArcadeDBServer server;
  private final String         databaseName;
  private final AuditChain     chain = new AuditChain();

  SporkAudit(final ArcadeDBServer server, final String databaseName) {
    this.server = server;
    this.databaseName = databaseName;
  }

  /**
   * Commits one entry and returns it in Recon's {@code AuditEntry} shape.
   *
   * @throws SporkRefusal 503 {@code auditUnavailable} when the chain cannot take it
   */
  JSONObject append(final ServerSecurityUser user, final OperatingContext context, final String action, final JSONObject detail) {
    final AuditEvent event = new AuditEvent(AuditActor.of(user), action).caseRef(context.caseRef()).purpose(context.purpose().wireName())
        .detail(detail);
    try {
      if (!server.existsDatabase(databaseName))
        throw unavailable(null);
      final DatabaseInternal database = server.getDatabaseForClient(databaseName);
      if (!database.getSchema().existsType(AuditChain.ENTRY_TYPE) || !database.getSchema().existsType(AuditChain.HEAD_TYPE))
        ensureSchema(database);

      final DatabaseContext.DatabaseContextTL threadContext = DatabaseContext.INSTANCE.getContextIfExists(database.getDatabasePath());
      final SecurityDatabaseUser bound = threadContext != null ? threadContext.getCurrentUser() : null;
      if (threadContext != null)
        threadContext.setCurrentUser(null);
      try {
        final JSONObject[] entry = new JSONObject[1];
        database.transaction(() -> entry[0] = chain.append(database, event), false, ATTEMPTS);
        return entry[0];
      } finally {
        if (threadContext != null)
          threadContext.setCurrentUser(bound);
      }
    } catch (final SporkRefusal e) {
      throw e;
    } catch (final RuntimeException e) {
      throw unavailable(e);
    }
  }

  DatabaseInternal database() {
    return server.existsDatabase(databaseName) ? server.getDatabaseForClient(databaseName) : null;
  }

  /** Two schema lookups when the chain exists; the first entry of a new database creates it, outside any transaction. */
  private synchronized void ensureSchema(final DatabaseInternal database) {
    AuditChain.ensureSchema(database);
  }

  private SporkRefusal unavailable(final Throwable cause) {
    LogManager.instance().log(this, Level.WARNING, "SPORK audit chain in database '%s' refused an entry: nothing was released", cause,
        databaseName);
    return new SporkRefusal(503, "auditUnavailable", "The audit chain cannot record this request, so nothing is released");
  }
}
