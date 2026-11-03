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
package com.arcadedb.server.http.handler;

import com.arcadedb.database.Database;
import com.arcadedb.query.selection.SelectionService;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.http.HttpServer;
import com.arcadedb.server.security.ServerSecurityUser;
import io.micrometer.core.instrument.Metrics;
import io.undertow.server.HttpServerExchange;

import java.util.function.BiFunction;

/**
 * The {@code POST /api/v1/selection/{database}/<operation>} routes (docs/spork/03-f1-selections-design.md, 2.4). Thin by
 * design, like the vector routes: the request contract, its validation and its HA routing all live in
 * {@link SelectionService}, which runs each operation as a Cypher {@code CALL selection.*} so a follower forwards the writes to
 * the leader on its own.
 * <p>
 * The write operations take the usual auto-commit transaction; none of them joins a session transaction a client opened
 * with {@code /begin} ({@link #participatesInSessionTransaction()} false), which keeps a write on the forwarded path and
 * keeps a 400 for malformed input from rolling back a transaction the client still owns (issue #8063's reasoning).
 */
public class PostSelectionHandler extends DatabaseAbstractHandler {
  public enum Operation {
    SAVE("save", true, SelectionService::save),
    COMBINE("combine", true, SelectionService::combine),
    COMPARE("compare", false, SelectionService::compare),
    VENN("venn", false, SelectionService::venn),
    GET("get", false, SelectionService::get),
    MEMBERS("members", false, SelectionService::members),
    DROP("drop", true, SelectionService::drop),
    PURGE("purge", true, SelectionService::purge);

    public final  String                                           path;
    private final boolean                                          writes;
    private final BiFunction<Database, JSONObject, JSONObject> service;

    Operation(final String path, final boolean writes, final BiFunction<Database, JSONObject, JSONObject> service) {
      this.path = path;
      this.writes = writes;
      this.service = service;
    }
  }

  private final Operation operation;

  public PostSelectionHandler(final HttpServer httpServer, final Operation operation) {
    super(httpServer);
    this.operation = operation;
  }

  @Override
  protected boolean participatesInSessionTransaction() {
    return false;
  }

  @Override
  protected boolean mustExecuteOnWorkerThread() {
    return true;
  }

  @Override
  protected boolean requiresTransaction() {
    return operation.writes;
  }

  @Override
  public ExecutionResponse execute(final HttpServerExchange exchange, final ServerSecurityUser user, final Database database,
      final JSONObject payload) {
    // PURGE is the one operation without a body; SelectionService refuses an absent one for every other, as a 400
    Metrics.counter("http.selection." + operation.path).increment();
    return new ExecutionResponse(200, operation.service.apply(database, payload).toString());
  }
}
