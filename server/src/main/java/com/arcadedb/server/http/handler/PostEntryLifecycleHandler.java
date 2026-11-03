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

import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.http.HttpServer;
import com.arcadedb.server.security.ServerSecurityUser;
import com.arcadedb.server.security.entry.EntryConfiguration;
import com.arcadedb.server.security.entry.EntryLifecycleException;
import com.arcadedb.server.security.entry.EntryLifecycleReceiver;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.nio.charset.StandardCharsets;

/**
 * POST /api/v1/entry/lifecycle: Entry's lifecycle dispatcher delivering a {@code TLifecycleDelivery} v2 to this installation
 * (docs/spork/07-entry-identity-and-audit.md 2.3). The caller is Entry, not a user: the bearer is the installation credential
 * whose SHA-256 is in {@code server-entry.json}, so the route checks it itself and no user is bound. The answer is Entry's
 * {@code TLifecycleAcknowledgement}; any other status makes Entry retry.
 */
public class PostEntryLifecycleHandler extends AbstractServerHttpHandler {
  /** entry-lifecycle's transport limit, both for the delivery and for the receipt. */
  private static final int    MAX_DELIVERY_BYTES = 16_384;
  private static final String BEARER             = "Bearer ";

  public PostEntryLifecycleHandler(final HttpServer httpServer) {
    super(httpServer);
  }

  @Override
  public boolean isRequireAuthentication() {
    return false;
  }

  @Override
  protected boolean authenticatesItsOwnCredential() {
    return true;
  }

  @Override
  protected boolean mustExecuteOnWorkerThread() {
    return true;
  }

  @Override
  protected ExecutionResponse execute(final HttpServerExchange exchange, final ServerSecurityUser user, final JSONObject payload) {
    final ArcadeDBServer server = httpServer.getServer();
    final EntryConfiguration config = server.getSecurity().getEntryConfiguration();
    final EntryLifecycleReceiver receiver = server.getSecurity().getEntryLifecycleReceiver();
    if (config == null || receiver == null || !config.hasLifecycleCredential())
      return error(404, "not_configured");

    final String authorization = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith(BEARER)
        || !config.acceptsLifecycleCredential(authorization.substring(BEARER.length()).trim()))
      return error(401, "invalid_installation_credential");

    if (exchange.getRequestContentLength() > MAX_DELIVERY_BYTES
        || (payload != null && payload.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DELIVERY_BYTES))
      return error(413, "delivery_too_large");
    if (!server.existsDatabase(config.getDatabase()))
      return error(503, "state_unavailable");

    try {
      return new ExecutionResponse(200, receiver.receive(server.getDatabase(config.getDatabase()), payload).toString());
    } catch (final EntryLifecycleException e) {
      return error(e.getHttpStatus(), e.getCode());
    }
  }

  private static ExecutionResponse error(final int status, final String code) {
    return new ExecutionResponse(status, new JSONObject().put("error", code).toString());
  }
}
