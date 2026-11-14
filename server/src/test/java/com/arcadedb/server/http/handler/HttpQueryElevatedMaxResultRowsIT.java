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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import com.arcadedb.server.security.ServerSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code arcadedb.server.httpQueryElevatedMaxResultRows}: a caller trusted with the database's resources (root, or a
 * user holding {@code updateDatabaseSettings} on it) may ask for more rows than
 * {@code arcadedb.server.httpQueryMaxResultRows} in one response, up to the elevated ceiling. A graph neighbourhood
 * read with a traversal cannot be paged without re-running the traversal per page, so a client that legitimately
 * needs a few thousand rows had no way to get them while the ceiling stayed protective for everybody else.
 * <p>
 * Everybody else keeps the ordinary ceiling, a caller that states no limit keeps the ordinary default cap, and with
 * the setting at its default (-1, follow the ordinary ceiling) nothing changes at all.
 */
class HttpQueryElevatedMaxResultRowsIT extends BaseGraphServerTest {
  private static final int    CEILING          = 20;
  private static final int    ELEVATED         = 50;
  private static final int    DEFAULT_LIMIT    = 10;
  private static final int    TOTAL_ROWS       = 60;
  private static final int    WANTED           = 40;
  private static final String TYPE_NAME        = "ElevatedRow";
  private static final String READER           = "elevatedReader";
  private static final String DB_ADMIN         = "elevatedDbAdmin";
  private static final String PASSWORD         = "ElevatedPwd123!";
  private static final String CEILING_SETTING  = GlobalConfiguration.SERVER_HTTP_QUERY_MAX_RESULT_ROWS.getKey();
  private static final String ELEVATED_SETTING = GlobalConfiguration.SERVER_HTTP_QUERY_ELEVATED_MAX_RESULT_ROWS.getKey();

  private final HttpClient client = HttpClient.newHttpClient();

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    config.setValue(GlobalConfiguration.SERVER_HTTP_QUERY_DEFAULT_LIMIT, DEFAULT_LIMIT);
    config.setValue(GlobalConfiguration.SERVER_HTTP_QUERY_MAX_RESULT_ROWS, CEILING);
    config.setValue(GlobalConfiguration.SERVER_HTTP_QUERY_ELEVATED_MAX_RESULT_ROWS, ELEVATED);
  }

  @BeforeEach
  void createRowsAndUsers() {
    final Database database = getServerDatabase(0, getDatabaseName());
    database.getSchema().createVertexType(TYPE_NAME);
    database.transaction(() -> {
      for (int i = 0; i < TOTAL_ROWS; i++)
        database.newVertex(TYPE_NAME).set("i", i).save();
    });

    createUser(READER, "readonly");
    createUser(DB_ADMIN, "admin");
  }

  @Test
  void rootIsServedARequestLimitAboveTheOrdinaryCeiling() throws Exception {
    final JSONObject response = query("root", new JSONObject()
        .put("language", "sql")
        .put("command", "SELECT i FROM " + TYPE_NAME)
        .put("limit", WANTED));

    assertThat(response.getJSONArray("result").length()).isEqualTo(WANTED);
    assertThat(response.getInt("returned")).isEqualTo(WANTED);
    assertThat(response.getInt("limit")).isEqualTo(WANTED);
    assertThat(response.getBoolean("truncated")).isTrue();
  }

  @Test
  void aTraversalAboveTheOrdinaryCeilingIsServedWhole() throws Exception {
    // The shape of the report: a neighbourhood read with TRAVERSE, which paging would re-run once per page.
    final Database database = getServerDatabase(0, getDatabaseName());
    database.getSchema().createEdgeType("ElevatedLink");
    database.transaction(() -> database.command("sql",
        "CREATE EDGE ElevatedLink FROM (SELECT FROM " + TYPE_NAME + " WHERE i = 0) TO (SELECT FROM " + TYPE_NAME
            + " WHERE i > 0 AND i < " + WANTED + ")").close());

    final JSONObject response = query(DB_ADMIN, new JSONObject()
        .put("language", "sql")
        .put("command", "TRAVERSE out('ElevatedLink') FROM (SELECT FROM " + TYPE_NAME + " WHERE i = 0) WHILE $depth <= 6")
        .put("limit", ELEVATED));

    assertThat(response.getJSONArray("result").length()).isEqualTo(WANTED);
    assertThat(response.getBoolean("truncated")).isFalse();
  }

  @Test
  void aUserHoldingUpdateDatabaseSettingsIsServedTheElevatedCeiling() throws Exception {
    final JSONObject response = query(DB_ADMIN, new JSONObject()
        .put("language", "sql")
        .put("command", "SELECT i FROM " + TYPE_NAME + " WHERE i < " + WANTED)
        .put("limit", -1));

    assertThat(response.getJSONArray("result").length()).isEqualTo(WANTED);
    assertThat(response.getBoolean("truncated")).isFalse();
  }

  @Test
  void aReaderKeepsTheOrdinaryCeiling() throws Exception {
    final HttpResponse<String> response = send(READER, "query", new JSONObject()
        .put("language", "sql")
        .put("command", "SELECT i FROM " + TYPE_NAME)
        .put("limit", WANTED));

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).contains(CEILING_SETTING);
    assertThat(response.body()).doesNotContain(ELEVATED_SETTING);
    assertThat(response.body()).contains("maximum of " + CEILING + " rows");
  }

  @Test
  void theElevatedCeilingStillRefusesWhatExceedsIt() throws Exception {
    final HttpResponse<String> response = send("root", "query", new JSONObject()
        .put("language", "sql")
        .put("command", "SELECT i FROM " + TYPE_NAME)
        .put("limit", -1));

    assertThat(response.statusCode()).isEqualTo(413);
    // Names the setting that bounds this caller, not the ordinary one it is already past.
    assertThat(response.body()).contains(ELEVATED_SETTING);
    assertThat(response.body()).contains("maximum of " + ELEVATED + " rows");
  }

  @Test
  void aCallerThatStatesNoLimitKeepsTheOrdinaryDefaultCap() throws Exception {
    final JSONObject response = query("root", new JSONObject()
        .put("language", "sql")
        .put("command", "SELECT i FROM " + TYPE_NAME));

    assertThat(response.getJSONArray("result").length()).isEqualTo(DEFAULT_LIMIT);
    assertThat(response.getBoolean("truncated")).isTrue();
  }

  @Test
  void theDefaultSettingFollowsTheOrdinaryCeiling() throws Exception {
    getServer(0).getConfiguration().setValue(GlobalConfiguration.SERVER_HTTP_QUERY_ELEVATED_MAX_RESULT_ROWS,
        GlobalConfiguration.SERVER_HTTP_QUERY_ELEVATED_MAX_RESULT_ROWS.getDefValue());
    try {
      final HttpResponse<String> response = send("root", "query", new JSONObject()
          .put("language", "sql")
          .put("command", "SELECT i FROM " + TYPE_NAME)
          .put("limit", WANTED));

      assertThat(response.statusCode()).isEqualTo(413);
      assertThat(response.body()).contains(CEILING_SETTING);
    } finally {
      getServer(0).getConfiguration().setValue(GlobalConfiguration.SERVER_HTTP_QUERY_ELEVATED_MAX_RESULT_ROWS, ELEVATED);
    }
  }

  @Test
  void theStreamingEncodingHonorsTheElevatedCeiling() throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(new URI(baseUrl() + "/api/v1/query/" + getDatabaseName()))
        .POST(HttpRequest.BodyPublishers.ofString(new JSONObject()
            .put("language", "sql")
            .put("command", "SELECT i FROM " + TYPE_NAME)
            .put("limit", WANTED).toString()))
        .setHeader("Content-Type", "application/json")
        .setHeader("Accept", "application/x-ndjson")
        .setHeader("Authorization", authorization(DB_ADMIN))
        .build();
    final HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    final String[] lines = response.body().strip().split("\n");
    assertThat(lines).hasSize(WANTED + 1);
    final JSONObject stats = new JSONObject(lines[lines.length - 1]).getJSONObject("stats");
    assertThat(stats.getInt("returned")).isEqualTo(WANTED);
  }

  @Test
  void theGetQueryEndpointHonorsTheElevatedCeiling() throws Exception {
    final String url = baseUrl() + "/api/v1/query/" + getDatabaseName() + "/sql/"
        + URLEncoder.encode("SELECT i FROM " + TYPE_NAME, StandardCharsets.UTF_8).replace("+", "%20") + "?limit=" + WANTED;
    final HttpResponse<String> admin = client.send(HttpRequest.newBuilder().uri(URI.create(url))
        .header("Authorization", authorization(DB_ADMIN)).GET().build(), BodyHandlers.ofString());
    assertThat(admin.statusCode()).isEqualTo(200);
    assertThat(new JSONObject(admin.body()).getJSONArray("result").length()).isEqualTo(WANTED);

    final HttpResponse<String> reader = client.send(HttpRequest.newBuilder().uri(URI.create(url))
        .header("Authorization", authorization(READER)).GET().build(), BodyHandlers.ofString());
    assertThat(reader.statusCode()).isEqualTo(413);
  }

  private void createUser(final String name, final String group) {
    final ServerSecurity security = getServer(0).getSecurity();
    if (security.getUser(name) == null)
      security.createUser(new JSONObject().put("name", name).put("password", security.encodePassword(PASSWORD))
          .put("databases", new JSONObject().put(getDatabaseName(), new JSONArray(new String[] { group }))));
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + getServer(0).getHttpServer().getPort();
  }

  private static String authorization(final String user) {
    final String password = "root".equals(user) ? DEFAULT_PASSWORD_FOR_TESTS : PASSWORD;
    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private JSONObject query(final String user, final JSONObject payload) throws Exception {
    final HttpResponse<String> response = send(user, "query", payload);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return new JSONObject(response.body());
  }

  private HttpResponse<String> send(final String user, final String endpoint, final JSONObject payload) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(new URI(baseUrl() + "/api/v1/" + endpoint + "/" + getDatabaseName()))
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        .setHeader("Content-Type", "application/json")
        .setHeader("Authorization", authorization(user))
        .build();
    return client.send(request, BodyHandlers.ofString());
  }
}
