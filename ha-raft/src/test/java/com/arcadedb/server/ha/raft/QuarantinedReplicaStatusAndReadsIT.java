/*
 * Copyright 2021-present Arcade Data Ltd (info@arcadedata.com)
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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Items 3 and 4 of the SPORK incident of 2026-11-12, on a live 3-node cluster.
 * <ul>
 *   <li><b>Item 4:</b> a follower that holds a database quarantined refuses every read of it - with and without
 *   {@code X-ArcadeDB-Read-After} - with a 503, a {@code Retry-After} and the reason, instead of answering from a copy
 *   missing committed entries. Its writes are still forwarded to the leader, and the other databases, the other follower
 *   and the leader keep serving.</li>
 *   <li><b>Item 3:</b> the leader's {@code GET /api/v1/cluster} reports that follower {@code QUARANTINED} with the
 *   database, its cause, applied index and resync state, and raises {@code peer-databases-quarantined}; the other
 *   follower stays {@code HEALTHY}. Both clear once the quarantine is lifted.</li>
 * </ul>
 * The leader is made unable to serve the database's snapshot for the length of the test, so the follower's own resync
 * (the health tick drives one) cannot lift the quarantine underneath the assertions.
 */
class QuarantinedReplicaStatusAndReadsIT extends BaseRaftHATest {

  private static final String TYPE = "IncidentRecord";

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    config.setValue(GlobalConfiguration.HA_SNAPSHOT_INSTALL_RETRIES, 0);
    config.setValue(GlobalConfiguration.HA_SNAPSHOT_INSTALL_RETRY_BASE_MS, 10L);
  }

  @AfterEach
  void clearSeam() {
    SnapshotHttpHandler.afterQuarantineCheckForTesting = null;
  }

  @Test
  @Timeout(240)
  void aQuarantinedFollowerRefusesReadsAndTheLeaderReportsIt() throws Exception {
    final int leaderIndex = findLeaderIndex();
    assertThat(leaderIndex).as("A Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final int quarantinedIndex = (leaderIndex + 1) % getServerCount();
    final int healthyIndex = (leaderIndex + 2) % getServerCount();
    final String dbName = getDatabaseName();

    final Database leaderDb = getServerDatabase(leaderIndex, dbName);
    leaderDb.transaction(() -> {
      leaderDb.getSchema().createVertexType(TYPE);
      for (int i = 0; i < 10; i++)
        leaderDb.newVertex(TYPE).set("id", i).save();
    });
    assertClusterConsistency();

    final RaftHAServer leaderRaft = getRaftPlugin(leaderIndex).getRaftHAServer();
    final ArcadeStateMachine followerMachine = getRaftPlugin(quarantinedIndex).getRaftHAServer().getStateMachine();
    final String quarantinedPeer = getRaftPlugin(quarantinedIndex).getRaftHAServer().getLocalPeerId().toString();
    final String healthyPeer = getRaftPlugin(healthyIndex).getRaftHAServer().getLocalPeerId().toString();

    // The leader cannot serve the snapshot, so the follower's resync fails and the quarantine stands
    SnapshotHttpHandler.afterQuarantineCheckForTesting = () -> {
      throw new IllegalStateException("snapshot serving disabled by the test");
    };
    followerMachine.markStateDiverged(dbName, DivergenceCause.WAL_VERSION_GAP);
    try {
      // ---- item 4: no read of the quarantined copy, whatever the consistency asked ----
      final long bookmark = leaderRaft.getCommitIndex();
      final String select = "{\"language\":\"sql\",\"command\":\"select count(*) as c from " + TYPE + "\"}";
      for (final Map<String, String> headers : new Map[] { Map.of(),
          Map.of("X-ArcadeDB-Read-After", String.valueOf(bookmark)),
          Map.of("X-ArcadeDB-Read-After", String.valueOf(bookmark), "X-ArcadeDB-Read-Consistency", "READ_YOUR_WRITES") }) {
        for (final String route : new String[] { "query", "command" }) {
          final Response refused = post(quarantinedIndex, "/api/v1/" + route + "/" + dbName, select, headers);
          assertThat(refused.status).as("%s with %s on the quarantined follower", route, headers).isEqualTo(503);
          assertThat(refused.retryAfter).as("a Retry-After tells the client when to come back").isNotNull();
          assertThat(refused.body).as("the reason is given").containsAnyOf("quarantined", "being replaced");
        }
      }
      // The other follower and the leader answer the same read
      assertThat(post(healthyIndex, "/api/v1/query/" + dbName, select,
          Map.of("X-ArcadeDB-Read-After", String.valueOf(bookmark))).status).isEqualTo(200);
      assertThat(post(leaderIndex, "/api/v1/query/" + dbName, select, Map.of()).status).isEqualTo(200);
      // A write sent to the quarantined follower does not read its copy: it is forwarded and succeeds
      final Response write = post(quarantinedIndex, "/api/v1/command/" + dbName,
          "{\"language\":\"sql\",\"command\":\"insert into " + TYPE + " set id = 100\"}", Map.of());
      assertThat(write.status).as("a write is still forwarded to the leader: %s", write.body).isEqualTo(200);

      // ---- item 3: the leader reports the follower QUARANTINED (its capability poll runs every 5 s) ----
      final long deadline = System.currentTimeMillis() + 60_000L;
      JSONObject quarantinedEntry = null;
      JSONObject status = null;
      while (System.currentTimeMillis() < deadline) {
        status = clusterStatus(leaderIndex);
        quarantinedEntry = peer(status, quarantinedPeer);
        if (quarantinedEntry != null && "QUARANTINED".equals(quarantinedEntry.getString("replicaStatus", "")))
          break;
        Thread.sleep(250);
      }
      assertThat(quarantinedEntry).isNotNull();
      assertThat(quarantinedEntry.getString("replicaStatus")).as("not HEALTHY while a database is quarantined")
          .isEqualTo("QUARANTINED");
      assertThat(quarantinedEntry.getJSONArray("quarantinedDatabases").toList()).containsExactly(dbName);
      final JSONObject resync = quarantinedEntry.getJSONObject("databaseResync");
      assertThat(resync.getLong("appliedIndex")).as("the peer's applied index").isPositive();
      final JSONObject dbEntry = resync.getJSONObject("databases").getJSONObject(dbName);
      assertThat(dbEntry.getBoolean("quarantined")).isTrue();
      assertThat(dbEntry.getString("cause")).isEqualTo(DivergenceCause.WAL_VERSION_GAP.name());
      assertThat(dbEntry.has("appliedIndex")).as("the database's own applied index").isTrue();
      assertThat(dbEntry.getString("resyncState")).isIn("PENDING", "INSTALLING", "BACKING_OFF");

      final JSONObject healthyEntry = peer(status, healthyPeer);
      assertThat(healthyEntry.getString("replicaStatus")).isNotEqualTo("QUARANTINED");
      assertThat(healthyEntry.getJSONArray("quarantinedDatabases").length()).isZero();
      assertThat(alertIds(status)).contains("peer-databases-quarantined");
    } finally {
      SnapshotHttpHandler.afterQuarantineCheckForTesting = null;
      followerMachine.clearDivergedDatabase(dbName);
      followerMachine.getResyncLoopGuard().completed(dbName);
    }

    // Lifted: the follower reads again, and the leader stops reporting it
    assertThat(post(quarantinedIndex, "/api/v1/query/" + dbName,
        "{\"language\":\"sql\",\"command\":\"select from " + TYPE + " limit 1\"}", Map.of()).status).isEqualTo(200);
    final long deadline = System.currentTimeMillis() + 60_000L;
    JSONObject entry = null;
    while (System.currentTimeMillis() < deadline) {
      entry = peer(clusterStatus(leaderIndex), quarantinedPeer);
      if (entry != null && entry.getJSONArray("quarantinedDatabases").length() == 0
          && !"QUARANTINED".equals(entry.getString("replicaStatus", "")))
        break;
      Thread.sleep(250);
    }
    assertThat(entry.getJSONArray("quarantinedDatabases").length()).isZero();
    assertThat(entry.getString("replicaStatus")).isNotEqualTo("QUARANTINED");
  }

  // ------------------------------------------------------------------------------------------------------------

  private record Response(int status, String retryAfter, String body) {
  }

  private static JSONObject peer(final JSONObject status, final String peerId) {
    final JSONArray peers = status.getJSONArray("peers");
    for (int i = 0; i < peers.length(); i++)
      if (peerId.equals(peers.getJSONObject(i).getString("id")))
        return peers.getJSONObject(i);
    return null;
  }

  private static String alertIds(final JSONObject status) {
    final StringBuilder ids = new StringBuilder();
    final JSONArray alerts = status.getJSONArray("alerts");
    for (int i = 0; i < alerts.length(); i++)
      ids.append(alerts.getJSONObject(i).getString("id")).append(' ');
    return ids.toString();
  }

  private JSONObject clusterStatus(final int serverIndex) throws Exception {
    final HttpURLConnection conn = open(serverIndex, "/api/v1/cluster");
    conn.setRequestMethod("GET");
    try {
      assertThat(conn.getResponseCode()).isEqualTo(200);
      try (final InputStream in = conn.getInputStream()) {
        return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      conn.disconnect();
    }
  }

  private Response post(final int serverIndex, final String path, final String body, final Map<String, String> headers)
      throws Exception {
    final HttpURLConnection conn = open(serverIndex, path);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    for (final Map.Entry<String, String> h : headers.entrySet())
      conn.setRequestProperty(h.getKey(), h.getValue());
    try (final OutputStream out = conn.getOutputStream()) {
      out.write(body.getBytes(StandardCharsets.UTF_8));
    }
    try {
      final int status = conn.getResponseCode();
      final String text;
      try (final InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
        text = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "";
      }
      return new Response(status, conn.getHeaderField("Retry-After"), text);
    } finally {
      conn.disconnect();
    }
  }

  private HttpURLConnection open(final int serverIndex, final String path) throws Exception {
    final HttpURLConnection conn = (HttpURLConnection) new URI(getServerHttpUrl(serverIndex, path)).toURL()
        .openConnection();
    conn.setRequestProperty("Authorization",
        "Basic " + Base64.getEncoder().encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8)));
    return conn;
  }
}
