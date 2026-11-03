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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.StaticBaseServerTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry's two consumer reads ({@code TConsumerSessionProof} v1 and {@code /api/auth/user}) for a fixed set of people, on a
 * fixture-allocated port. Shapes follow the server module's {@code EntryFixtures}.
 */
final class StubEntry implements AutoCloseable {
  static final String ENVIRONMENT = "test:http://localhost:3002";

  private record Person(String userId, String[] permissions) {
  }

  private final Map<String, Person> people = new ConcurrentHashMap<>();
  private final HttpServer          server;
  private final int                 port;

  StubEntry() throws IOException {
    port = StaticBaseServerTest.allocateFreePorts(1)[0];
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/", this::answer);
    server.start();
  }

  StubEntry person(final String token, final String userId, final String... permissions) {
    people.put(token, new Person(userId, permissions));
    return this;
  }

  /** {@code server-entry.json}: Entry on this stub, its state in {@code database}, {@code grants} as given. */
  JSONObject configuration(final String database, final JSONObject grants) {
    return new JSONObject()//
        .put("origin", "http://127.0.0.1:" + port)//
        .put("environmentId", ENVIRONMENT)//
        .put("serviceId", "spork")//
        .put("database", database)//
        .put("grants", grants);
  }

  private void answer(final HttpExchange exchange) throws IOException {
    final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    final Person person = authorization != null && authorization.startsWith("Bearer ") ?
        people.get(authorization.substring("Bearer ".length())) :
        null;
    final JSONObject body;
    if (person == null)
      body = new JSONObject().put("error", "invalid_session");
    else if (exchange.getRequestURI().getPath().equals("/api/v1/consumer/session-proof"))
      body = new JSONObject()//
          .put("schemaVersion", 1)//
          .put("environmentId", ENVIRONMENT)//
          .put("sourceUserId", person.userId())//
          .put("identityId", "google-oauth2|" + person.userId())//
          .put("accountSequence", 4)//
          .put("credentialId", "c".repeat(64))//
          .put("checkedAt", Instant.now().toString())//
          .put("expiresAt", Instant.now().plusSeconds(3600).toString());
    else {
      final JSONArray permissions = new JSONArray();
      for (final String permission : person.permissions())
        permissions.put(permission);
      body = new JSONObject()//
          .put("id", person.userId())//
          .put("email", "analyst@example.org")//
          .put("disabled", false)//
          .put("verified", true)//
          .put("api", new JSONObject().put("permissions", permissions));
    }
    final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(person != null ? 200 : 401, bytes.length);
    try (final OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
