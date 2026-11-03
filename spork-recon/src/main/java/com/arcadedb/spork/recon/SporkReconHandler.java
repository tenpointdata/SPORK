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

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.http.HttpServer;
import com.arcadedb.server.http.handler.AbstractServerHttpHandler;
import com.arcadedb.server.http.handler.ExecutionResponse;
import com.arcadedb.server.security.ServerSecurityUser;
import com.arcadedb.server.security.entry.EntryServerSecurityUser;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.List;
import java.util.UUID;

/**
 * The single entry point of {@code /api/spork/v1}: resolves the route from {@link SporkRoutes}, then, in this order and before
 * any data is read, the tenant, the Operating Context (428), the analyst identity check and the purpose gate (403). The person
 * is whoever the server authenticated, an Entry account ({@link EntryServerSecurityUser}) or a local break-glass user; the
 * header never names them.
 * <p>
 * Runs on a worker thread: hydrate reads pages synchronously and the audit append commits.
 */
final class SporkReconHandler extends AbstractServerHttpHandler {
  private final ArcadeDBServer          server;
  private final SporkReconConfiguration config;
  private final HydrateService          hydrate;
  private final SearchService           search;
  private final SporkAudit              audit;

  SporkReconHandler(final HttpServer httpServer, final SporkReconConfiguration config, final HydrateService hydrate,
      final SearchService search, final SporkAudit audit) {
    super(httpServer);
    this.server = httpServer != null ? httpServer.getServer() : null;
    this.config = config;
    this.hydrate = hydrate;
    this.search = search;
    this.audit = audit;
  }

  @Override
  protected boolean mustExecuteOnWorkerThread() {
    return true;
  }

  @Override
  protected ExecutionResponse execute(final HttpServerExchange exchange, final ServerSecurityUser user, final JSONObject payload) {
    final String method = exchange.getRequestMethod().toString();
    final String path = exchange.getRequestPath();
    try {
      if (SporkPath.OPENAPI_PATH.equals(path)) {
        if (!"GET".equals(method))
          return methodNotAllowed(List.of("GET"));
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        return new ExecutionResponse(200, SporkReconApiSpec.toJson());
      }

      final SporkPath target = SporkPath.parse(path);
      final SporkRoute route = SporkRoutes.find(method, target.route());
      if (route == null) {
        final List<String> allowed = SporkRoutes.allowedMethods(target.route());
        if (allowed.isEmpty())
          throw SporkPath.notFound();
        return methodNotAllowed(allowed);
      }
      if (config == null)
        throw new SporkRefusal(503, "notConfigured", "The spork-recon plugin has no " + SporkReconConfiguration.FILE_NAME);
      if (!config.isTenant(target.tenant()))
        throw SporkRefusal.notFound("unknownTenant", "No such tenant");

      final OperatingContext context = OperatingContext.parse(exchange.getRequestHeaders().getFirst(OperatingContext.HEADER));
      final String analyst = analystOf(user);
      if (context.analyst() != null && !context.analyst().equals(analyst))
        throw new SporkRefusal(403, "analystMismatch", "The context names another account than the one authenticated");
      if (!route.allows(context.purpose()))
        throw new SporkRefusal(403, "purposeDenied",
            "The purpose '" + context.purpose().wireName() + "' may not call " + route.method() + " " + route.path());

      final String requestId = UUID.randomUUID().toString();
      final JSONObject answer;
      if (route == SporkRoutes.HYDRATE)
        answer = hydrate(user, target.tenant(), context, route, requestId, payload);
      else if (route == SporkRoutes.SEARCH)
        answer = search(user, target.tenant(), context, route, requestId, payload);
      else
        answer = declare(user, target.tenant(), context, route, requestId);
      answer.put("requestId", requestId);
      answer.put("servedBy", server.getServerName());
      answer.put("consistencyBookmark", bookmark(exchange));
      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
      return new ExecutionResponse(200, answer.toString());
    } catch (final SporkRefusal refusal) {
      return refuse(refusal);
    }
  }

  private JSONObject hydrate(final ServerSecurityUser user, final String tenant, final OperatingContext context, final SporkRoute route,
      final String requestId, final JSONObject payload) {
    final List<String> ids = HydrateService.ids(payload);
    final RecordProjection[] fields = HydrateService.projection(payload);
    final HydrateService.Result result = hydrate.hydrate(user, heldMarkings(user), tenant, ids, fields);

    final JSONObject entry = audit.append(user, context, route.action(), new JSONObject()//
        .put("tenant", tenant)//
        .put("requestId", requestId)//
        .put("requested", result.requested())//
        .put("records", result.records().length())//
        .put("missing", result.missing().length())//
        .put("invalid", result.invalid().length())//
        .put("keysRoot", MerkleRoot.ofRecordIds(result.recordIds(), result.recordIds().length)));

    return new JSONObject()//
        .put("records", result.records())//
        .put("missing", result.missing())//
        .put("invalid", result.invalid())//
        .put("partial", result.partial())//
        .put("auditSeq", entry.getLong("seq"));
  }

  private JSONObject search(final ServerSecurityUser user, final String tenant, final OperatingContext context, final SporkRoute route,
      final String requestId, final JSONObject payload) {
    final SearchService.Request request = SearchService.parse(payload);
    final SearchService.Result result = search.search(user, heldMarkings(user), tenant, request);

    final JSONObject entry = audit.append(user, context, route.action(), new JSONObject()//
        .put("tenant", tenant)//
        .put("requestId", requestId)//
        .put("records", result.records().length())//
        .put("total", result.total())//
        .put("totalCapped", result.totalCapped())//
        .put("partial", result.partial())//
        .put("sort", request.sort())//
        .put("filters", String.join(",", request.filters()))//
        .put("titles", request.title())//
        .put("keysRoot", MerkleRoot.ofRecordIds(result.recordIds(), result.recordIds().length)));

    final JSONObject answer = new JSONObject()//
        .put("records", result.records())//
        .put("total", result.total())//
        .put("totalCapped", result.totalCapped())//
        .put("partial", result.partial())//
        .put("auditSeq", entry.getLong("seq"));
    if (result.partial())
      answer.put("totalEstimate", result.totalEstimate());
    return answer;
  }

  private JSONObject declare(final ServerSecurityUser user, final String tenant, final OperatingContext context, final SporkRoute route,
      final String requestId) {
    final JSONObject entry = audit.append(user, context, route.action(),
        new JSONObject().put("tenant", tenant).put("requestId", requestId));
    return new JSONObject()//
        .put("tenant", tenant)//
        .put("analyst", analystOf(user))//
        .put("caseRef", context.caseRef())//
        .put("purpose", context.purpose().wireName())//
        .put("partial", false)//
        .put("auditSeq", entry.getLong("seq"));
  }

  /** The account the person is: the Entry account id, or {@code local:<name>} for a local user, as the audit chain names them. */
  static String analystOf(final ServerSecurityUser user) {
    if (user instanceof EntryServerSecurityUser entry)
      return entry.getPrincipal().sourceUserId();
    return "local:" + user.getName();
  }

  private long heldMarkings(final ServerSecurityUser user) {
    if (user instanceof EntryServerSecurityUser entry)
      return config.heldMarkings(entry.getPrincipal().permissions());
    return config.localMarkings();
  }

  /** The commit index a client passes back as X-ArcadeDB-Read-After; null outside HA. */
  private String bookmark(final HttpServerExchange exchange) {
    final DatabaseInternal database = audit.database();
    if (database != null)
      emitCommitIndexBookmark(exchange, resolveHAReplicatedDatabase(database));
    return exchange.getResponseHeaders().getFirst(COMMIT_INDEX_HEADER_NAME);
  }

  private static ExecutionResponse methodNotAllowed(final List<String> allowed) {
    return refuse(new SporkRefusal(405, "methodNotAllowed", "Allowed: " + String.join(", ", allowed))).setHeader("Allow",
        String.join(", ", allowed));
  }

  private static ExecutionResponse refuse(final SporkRefusal refusal) {
    return new ExecutionResponse(refusal.status(),
        new JSONObject().put("error", refusal.getMessage()).put("code", refusal.code()).toString()).setHeader("Content-Type",
        "application/json");
  }
}
