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
package com.arcadedb.server.http;

import com.arcadedb.database.Database;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 slice 3 (docs/spork/03-f1-selections-design.md, 2.4): the {@code /api/v1/selection/{database}/*} routes end to end.
 * The JSON contract itself is {@code SelectionServiceTest}'s; this covers what only a server adds: routing, the status
 * codes, authentication and the auto-commit of the write routes. Fixture: Sel1 -> Ph1, Sel2 -> Ph1 and Ph2, Sel3 -> Ph2.
 */
public class SelectionHttpIT extends BaseGraphServerTest {
  private final Map<String, String> rids = new HashMap<>();

  @Override
  protected void populateDatabase() {
    super.populateDatabase();

    final Database db = getDatabase(0);
    db.transaction(() -> {
      db.getSchema().createVertexType("SelAd");
      db.getSchema().createVertexType("SelPhone");
      db.getSchema().createEdgeType("SEL_HAS_PHONE");
      db.command("sql", "CREATE PROPERTY SelAd.text STRING");
      db.command("sql", "CREATE INDEX ON SelAd (text) FULL_TEXT");
      final Map<String, MutableVertex> v = new HashMap<>();
      for (final String name : List.of("Sel1", "Sel2", "Sel3"))
        v.put(name, db.newVertex("SelAd").set("name", name).set("text", "escort ad " + name).save());
      for (final String name : List.of("Ph1", "Ph2"))
        v.put(name, db.newVertex("SelPhone").set("name", name).save());
      v.get("Sel1").newEdge("SEL_HAS_PHONE", v.get("Ph1")).save();
      v.get("Sel2").newEdge("SEL_HAS_PHONE", v.get("Ph1")).save();
      v.get("Sel2").newEdge("SEL_HAS_PHONE", v.get("Ph2")).save();
      v.get("Sel3").newEdge("SEL_HAS_PHONE", v.get("Ph2")).save();
      for (final Map.Entry<String, MutableVertex> e : v.entrySet())
        rids.put(e.getKey(), e.getValue().getIdentity().toString());
    });
  }

  @Test
  void theWholeLifecycleOverHttp() throws Exception {
    final JSONObject saved = post("save", new JSONObject().put("name", "g0").put("members", rids("Sel1", "Sel2")));
    assertThat(saved.getLong("cardinality")).isEqualTo(2L);
    post("save", new JSONObject().put("name", "g1").put("query", "SELECT FROM SelAd WHERE name = 'Sel3'").put("language", "sql"));

    final JSONObject handle = post("get", new JSONObject().put("name", "g1").put("bitmap", true));
    assertThat(handle.getString("language")).isEqualTo("sql");
    assertThat(Base64.getDecoder().decode(handle.getString("bitmap"))).isNotEmpty();

    final JSONObject members = post("members", new JSONObject().put("name", "g0"));
    assertThat(members.getInt("count")).isEqualTo(2);
    assertThat(members.getJSONArray("members").getJSONObject(0).getJSONObject("properties").getString("name")).isEqualTo("Sel1");

    final JSONObject compared = post("compare", new JSONObject().put("selections", new JSONArray(List.of("g0", "g1")))
        .put("via", "SEL_HAS_PHONE").put("direction", "OUT"));
    assertThat(compared.getInt("count")).isEqualTo(1);
    assertThat(compared.getJSONArray("rows").getJSONObject(0).getJSONObject("properties").getString("name")).isEqualTo("Ph2");

    final JSONObject venn = post("venn", new JSONObject().put("selections", new JSONArray(List.of("g0", "g1"))));
    assertThat(venn.getJSONArray("regions").length()).isEqualTo(2);

    assertThat(post("combine", new JSONObject().put("op", "or").put("inputs", new JSONArray(List.of("g0", "g1")))
        .put("name", "all")).getLong("cardinality")).isEqualTo(3L);

    // A copy made from the bitmap another handle exported, the client-side set algebra path
    final String bitmap = post("get", new JSONObject().put("name", "all").put("bitmap", true)).getString("bitmap");
    assertThat(post("save", new JSONObject().put("name", "copy").put("bitmap", bitmap)).getLong("cardinality")).isEqualTo(3L);

    assertThat(post("drop", new JSONObject().put("name", "all")).getBoolean("dropped")).isTrue();
    assertThat(post("purge", new JSONObject()).getLong("purged")).isZero();

    // The write routes committed: the handles are visible to a fresh read of the database
    assertThat(getServerDatabase(0, getDatabaseName()).query("sql", "SELECT count() AS c FROM SelectionHandle").next()
        .<Number>getProperty("c").longValue()).isEqualTo(3L);
  }

  @Test
  void theSearchRoutesTakeASelection() throws Exception {
    post("save", new JSONObject().put("name", "two").put("members", rids("Sel1", "Sel3")));

    final HttpResponse<String> response = rawPostTo("/api/v1/vector/" + getDatabaseName() + "/fulltext",
        new JSONObject().put("indexName", "SelAd[text]").put("queryText", "escort").put("selection", "two").toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    final JSONArray results = new JSONObject(response.body()).getJSONArray("results");
    final List<String> names = new ArrayList<>();
    for (int i = 0; i < results.length(); i++)
      names.add(results.getJSONObject(i).getJSONObject("properties").getString("name"));
    assertThat(names).containsExactlyInAnyOrder("Sel1", "Sel3");

    final HttpResponse<String> unknown = rawPostTo("/api/v1/vector/" + getDatabaseName() + "/fulltext",
        new JSONObject().put("indexName", "SelAd[text]").put("queryText", "escort").put("selection", "nope").toString());
    assertThat(unknown.statusCode()).isEqualTo(400);
    assertThat(unknown.body()).contains("no selection named 'nope'");
  }

  @Test
  void aRefusalIsAClientError() throws Exception {
    final HttpResponse<String> unknown = rawPost("get", new JSONObject().put("name", "nope").toString(), true);
    assertThat(unknown.statusCode()).isEqualTo(400);
    assertThat(unknown.body()).contains("no selection named 'nope'");

    final HttpResponse<String> twoSources = rawPost("save",
        new JSONObject().put("name", "x").put("members", rids("Sel1")).put("query", "SELECT FROM SelAd").toString(), true);
    assertThat(twoSources.statusCode()).isEqualTo(400);
    assertThat(twoSources.body()).contains("exactly one of");

    final HttpResponse<String> badOp = rawPost("combine", new JSONObject().put("op", "nand")
        .put("inputs", new JSONArray().put(rids("Sel1")).put(rids("Sel2"))).put("name", "z").toString(), true);
    assertThat(badOp.statusCode()).isEqualTo(400);
    assertThat(badOp.body()).contains("unknown op 'nand'");
  }

  @Test
  void anEmptyBodyIsRefusedExceptByPurge() throws Exception {
    assertThat(rawPost("save", "", true).statusCode()).isEqualTo(400);
    assertThat(rawPost("purge", "", true).statusCode()).isEqualTo(200);
  }

  @Test
  void theRoutesNeedCredentials() throws Exception {
    assertThat(rawPost("get", new JSONObject().put("name", "g0").toString(), false).statusCode()).isEqualTo(401);
  }

  // ---- plumbing ----

  private JSONArray rids(final String... names) {
    final List<String> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name));
    return new JSONArray(list);
  }

  private JSONObject post(final String operation, final JSONObject payload) throws Exception {
    final HttpResponse<String> response = rawPost(operation, payload.toString(), true);
    assertThat(response.statusCode()).as("%s answered: %s", operation, response.body()).isEqualTo(200);
    return new JSONObject(response.body());
  }

  private HttpResponse<String> rawPost(final String operation, final String body, final boolean authenticated) throws Exception {
    return rawPostTo("/api/v1/selection/" + getDatabaseName() + "/" + operation, body, authenticated);
  }

  private HttpResponse<String> rawPostTo(final String path, final String body) throws Exception {
    return rawPostTo(path, body, true);
  }

  private HttpResponse<String> rawPostTo(final String path, final String body, final boolean authenticated) throws Exception {
    final HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create(getServerHttpUrl(path)))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (authenticated)
      request.header("Authorization", "Basic " + Base64.getEncoder()
          .encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8)));
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        .send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
