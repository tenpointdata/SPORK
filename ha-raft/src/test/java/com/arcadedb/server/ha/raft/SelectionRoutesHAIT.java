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
package com.arcadedb.server.ha.raft;

import com.arcadedb.database.Database;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 slice 3 (docs/spork/03-f1-selections-design.md, 2.4): the selection routes on a 3-node Raft cluster. The write routes
 * run their operation as a Cypher write-procedure call, which a follower forwards to the leader; the test proves that by
 * sending every write to a FOLLOWER, the very first save included (it creates the {@code SelectionHandle} type, so the DDL
 * must happen on the leader too), and by reading the result back on every node.
 */
class SelectionRoutesHAIT extends BaseRaftHATest {
  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(30)).build();

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Test
  void selectionsSavedThroughAFollowerReplicateAndCompareEverywhere() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = firstFollowerOf(leader);
    final int otherFollower = 3 - leader - follower;

    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.getSchema().createVertexType("HaSelAd");
    leaderDb.getSchema().createVertexType("HaSelPhone");
    leaderDb.getSchema().createEdgeType("HA_SEL_PHONE");
    final List<String> ads = new ArrayList<>();
    leaderDb.transaction(() -> {
      final MutableVertex p1 = leaderDb.newVertex("HaSelPhone").set("name", "p1").save();
      final MutableVertex p2 = leaderDb.newVertex("HaSelPhone").set("name", "p2").save();
      for (int i = 0; i < 4; i++) {
        final MutableVertex ad = leaderDb.newVertex("HaSelAd").set("name", "ad" + i).save();
        ad.newEdge("HA_SEL_PHONE", i < 2 ? p1 : p2).save();
        if (i == 1)
          ad.newEdge("HA_SEL_PHONE", p2).save();
        ads.add(ad.getIdentity().toString());
      }
    });
    waitForAllServers();

    // ad0, ad1 -> p1 (ad1 also -> p2); ad2, ad3 -> p2
    assertThat(post(follower, "save", new JSONObject().put("name", "left").put("members", new JSONArray(ads.subList(0, 2))))
        .getLong("cardinality")).isEqualTo(2L);
    assertThat(post(otherFollower, "save", new JSONObject().put("name", "right")
        .put("query", "SELECT FROM HaSelAd WHERE name IN ['ad2', 'ad3']").put("language", "sql")).getLong("cardinality"))
        .isEqualTo(2L);
    waitForAllServers();

    for (int server = 0; server < getServerCount(); server++) {
      final JSONObject handle = post(server, "get", new JSONObject().put("name", "left").put("bitmap", true));
      assertThat(handle.getLong("cardinality")).as("server %d", server).isEqualTo(2L);
      assertThat(Base64.getDecoder().decode(handle.getString("bitmap"))).isNotEmpty();

      final JSONObject compared = post(server, "compare", new JSONObject().put("selections", new JSONArray(List.of("left", "right")))
          .put("via", "HA_SEL_PHONE").put("direction", "OUT"));
      assertThat(compared.getInt("count")).as("server %d", server).isEqualTo(1);
      assertThat(compared.getJSONArray("rows").getJSONObject(0).getJSONObject("properties").getString("name")).isEqualTo("p2");
    }

    assertThat(post(follower, "combine", new JSONObject().put("op", "or").put("inputs", new JSONArray(List.of("left", "right")))
        .put("name", "both")).getLong("cardinality")).isEqualTo(4L);
    assertThat(post(otherFollower, "drop", new JSONObject().put("name", "left")).getBoolean("dropped")).isTrue();
    waitForAllServers();

    for (int server = 0; server < getServerCount(); server++) {
      final HttpResponse<String> gone = rawPost(server, "get", new JSONObject().put("name", "left"));
      assertThat(gone.statusCode()).as("server %d: %s", server, gone.body()).isEqualTo(400);
      assertThat(post(server, "get", new JSONObject().put("name", "both")).getLong("cardinality")).isEqualTo(4L);
    }

    assertClusterConsistency();
  }

  private JSONObject post(final int server, final String operation, final JSONObject body) throws Exception {
    final HttpResponse<String> response = rawPost(server, operation, body);
    assertThat(response.statusCode()).as("server %d %s answered: %s", server, operation, response.body()).isEqualTo(200);
    return new JSONObject(response.body());
  }

  private HttpResponse<String> rawPost(final int server, final String operation, final JSONObject body) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(getServerHttpUrl(server, "/api/v1/selection/" + getDatabaseName() + "/" + operation)))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .header("Authorization", "Basic " + Base64.getEncoder()
            .encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8)))
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
