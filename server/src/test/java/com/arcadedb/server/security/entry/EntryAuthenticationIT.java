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
package com.arcadedb.server.security.entry;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.BaseGraphServerTest;
import com.arcadedb.server.StaticBaseServerTest;
import com.arcadedb.server.audit.AuditChain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole vertical through HTTP: a person's own Entry credential is the ArcadeDB principal, their Entry permission
 * picks the groups, Entry's lifecycle push takes the access away on the next request, and the push lands in the audit
 * chain. Entry itself is a stub serving its two consumer reads on a fixture-allocated port.
 */
class EntryAuthenticationIT extends BaseGraphServerTest {
  private static final String CONSOLE_TOKEN  = "entry-console-session.0123456789";
  private static final String OUTSIDER_TOKEN = "entry-outsider-session.0123456789";

  private HttpServer entry;

  @Override
  protected void onBeforeStarting(final ArcadeDBServer server) {
    try {
      final int port = StaticBaseServerTest.allocateFreePorts(1)[0];
      entry = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      entry.createContext("/", this::answerEntry);
      entry.start();

      final JSONObject config = EntryFixtures.configJson()//
          .put("origin", "http://127.0.0.1:" + port)//
          .put("database", getDatabaseName())//
          .put("grants", new JSONObject().put("spork.console", new JSONObject().put(getDatabaseName(), new JSONArray().put("admin"))));
      // ServerSecurity reads it in start(), after this hook; the directory may not exist before a first start
      Files.createDirectories(new File(server.getConfigPath()).toPath());
      Files.writeString(new File(server.getConfigPath(), EntryConfiguration.FILE_NAME).toPath(), config.toString(),
          StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterEach
  void stopEntry() {
    if (entry != null)
      entry.stop(0);
    // The config directory outlives this class: a leftover file would put every later IT behind Entry.
    for (int i = 0; i < getServerCount(); i++)
      if (getServer(i) != null)
        new File(getServer(i).getConfigPath(), EntryConfiguration.FILE_NAME).delete();
  }

  private void answerEntry(final HttpExchange exchange) throws IOException {
    final String token = exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length());
    final boolean known = CONSOLE_TOKEN.equals(token) || OUTSIDER_TOKEN.equals(token);
    final JSONObject body;
    if (!known)
      body = new JSONObject().put("error", "invalid_session");
    else if (exchange.getRequestURI().getPath().equals("/api/v1/consumer/session-proof"))
      body = EntryFixtures.proofJson().put("checkedAt", Instant.now().toString())
          .put("expiresAt", Instant.now().plusSeconds(3600).toString())
          .put("sourceUserId", CONSOLE_TOKEN.equals(token) ? EntryFixtures.USER_ID : EntryFixtures.uuid(42));
    else
      body = EntryFixtures.accountJson().put("id", CONSOLE_TOKEN.equals(token) ? EntryFixtures.USER_ID : EntryFixtures.uuid(42))
          .put("api", new JSONObject().put("permissions", new JSONArray().put(CONSOLE_TOKEN.equals(token) ? "spork.console" : "other.app")));
    final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(known ? 200 : 401, bytes.length);
    try (final OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private int query(final String bearer) throws IOException {
    final HttpURLConnection connection = (HttpURLConnection) URI.create(
        getServerHttpUrl(0, "/api/v1/query/" + getDatabaseName() + "/sql/select%201")).toURL().openConnection();
    connection.setRequestProperty("Authorization", "Bearer " + bearer);
    try {
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  private int deliver(final String credential, final JSONObject delivery, final StringBuilder answer) throws IOException {
    final HttpURLConnection connection = (HttpURLConnection) URI.create(getServerHttpUrl(0, "/api/v1/entry/lifecycle")).toURL()
        .openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Authorization", "Bearer " + credential);
    connection.setRequestProperty("Content-Type", "application/json");
    try (final OutputStream out = connection.getOutputStream()) {
      out.write(delivery.toString().getBytes(StandardCharsets.UTF_8));
    }
    try {
      final int status = connection.getResponseCode();
      try (final InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
        if (in != null)
          answer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
      return status;
    } finally {
      connection.disconnect();
    }
  }

  @Test
  void anEntryPersonIsAuthorizedByTheirPermissionAndLosesAccessWhenEntryDisablesThem() throws Exception {
    assertThat(query(CONSOLE_TOKEN)).as("spork.console maps to the admin group on this database").isEqualTo(200);
    assertThat(query(OUTSIDER_TOKEN)).as("an Entry account without a SPORK permission").isEqualTo(403);
    assertThat(query("entry-unknown-session.0123456789")).as("a credential Entry rejects").isEqualTo(401);

    final StringBuilder refused = new StringBuilder();
    assertThat(deliver("not-the-installation-credential", EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false), refused))
        .as("only Entry's installation credential may deliver lifecycle events").isEqualTo(401);
    assertThat(query(CONSOLE_TOKEN)).isEqualTo(200);

    final StringBuilder ack = new StringBuilder();
    assertThat(deliver(EntryFixtures.LIFECYCLE_SECRET, EntryFixtures.accountDelivery(EntryFixtures.uuid(1), 3, false), ack))
        .isEqualTo(200);
    final JSONObject receipt = new JSONObject(ack.toString());
    assertThat(receipt.getString("desiredState")).isEqualTo("disabled");
    assertThat(receipt.getBoolean("grantsRecreated")).isFalse();

    assertThat(query(CONSOLE_TOKEN)).as("the cached verdict does not outlive Entry's disablement").isEqualTo(403);
    assertThat(AuditChain.verify(getServerDatabase(0, getDatabaseName())).entries()).isEqualTo(1L);
  }
}
