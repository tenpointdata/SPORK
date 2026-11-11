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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read-your-writes contract of the plain HTTP API, the way a client that is not {@code RemoteDatabase} uses it
 * (the stress-test report behind this test: ~100 clients sent every read to the leader and queued on its admission
 * gate, because spreading reads to followers looked unsafe).
 * <p>
 * A write answered by the leader carries {@code X-ArcadeDB-Commit-Index: <n>}. A read sent to a follower with
 * {@code X-ArcadeDB-Read-After: <n>} is served only once that follower has applied entry {@code n}; the consistency
 * level is {@code arcadedb.ha.readConsistency}, {@code read_your_writes} by default, so no other header is needed.
 * <p>
 * The follower is held behind its own apply (the per-database install gate the apply thread takes), so the test does
 * not depend on winning a race: the follower provably has NOT applied the write while the bookmarked read waits, and an
 * {@code eventual} read sent at the same moment provably misses it.
 */
class RaftHttpFollowerReadYourWritesIT extends BaseRaftHATest {

  private static final String   TYPE_NAME    = "HttpRyw";
  private static final String   BOOKMARK     = "X-ArcadeDB-Commit-Index";
  private static final String   READ_AFTER   = "X-ArcadeDB-Read-After";
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    config.setValue(GlobalConfiguration.HA_QUORUM, "majority");
    // The bookmarked read waits up to the quorum timeout before it degrades to EVENTUAL: far more than the test holds
    // the follower back, so the read can only be released by the follower applying the write.
    config.setValue(GlobalConfiguration.HA_QUORUM_TIMEOUT, 60_000L);
  }

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Test
  void aFollowerServesTheClientsOwnWriteOnceItHasAppliedTheBookmark() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = firstFollowerOf(leader);

    final HttpResponse<String> ddl = httpCommand(leader, "CREATE DOCUMENT TYPE " + TYPE_NAME + " IF NOT EXISTS");
    // Make sure the follower has the type before it is held back: the read below must miss a ROW, not a type.
    assertThat(count(follower, Long.parseLong(bookmarkOf(ddl)), null).statusCode()).isEqualTo(200);

    final ArcadeStateMachine.InstallApplyGate gate = getRaftPlugin(follower).getRaftHAServer().getStateMachine()
        .installApplyGate(getDatabaseName());
    gate.lock();
    CompletableFuture<HttpResponse<String>> bookmarkedRead = null;
    long bookmark = -1;
    try {
      bookmark = Long.parseLong(bookmarkOf(httpCommand(leader, "INSERT INTO " + TYPE_NAME + " SET id = 'mine'")));

      // Without the bookmark the follower answers from what it has applied, which does not include the write.
      final HttpResponse<String> eventual = count(follower, -1, "eventual");
      assertThat(eventual.statusCode()).isEqualTo(200);
      assertThat(rowCount(eventual)).as("the follower is held back, so an eventual read misses the write").isZero();

      // With it, the follower waits for the entry instead of answering stale.
      bookmarkedRead = countAsync(follower, bookmark);
      final CompletableFuture<HttpResponse<String>> pending = bookmarkedRead;
      assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    } finally {
      gate.unlock();
    }

    final HttpResponse<String> read = bookmarkedRead.get(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(rowCount(read)).as("the bookmarked read on the follower sees the client's own write").isEqualTo(1);
    // The follower echoes its own applied index, at or past the bookmark: the client's next bookmark.
    assertThat(Long.parseLong(bookmarkOf(read))).isGreaterThanOrEqualTo(bookmark);
  }

  @Test
  void aMalformedBookmarkIsRefusedWith400() throws Exception {
    final int follower = firstFollowerOf(requireLeaderIndex());
    final HttpRequest request = queryRequest(follower).header(READ_AFTER, "not-a-number").build();
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
  }

  private static String bookmarkOf(final HttpResponse<String> response) {
    return response.headers().firstValue(BOOKMARK)
        .orElseThrow(() -> new AssertionError("no " + BOOKMARK + " header on " + response.uri()));
  }

  private static long rowCount(final HttpResponse<String> response) {
    return new JSONObject(response.body()).getJSONArray("result").getJSONObject(0).getLong("n");
  }

  private HttpResponse<String> httpCommand(final int serverIndex, final String sql) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(serverIndex) + "/command/" + getDatabaseName()))
        .timeout(HTTP_TIMEOUT)
        .header("Authorization", basicAuth())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("language", "sql").put("command", sql).toString()))
        .build();
    final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("'%s' failed: %s", sql, response.body()).isEqualTo(200);
    return response;
  }

  private HttpResponse<String> count(final int serverIndex, final long readAfter, final String consistency) throws Exception {
    return client.send(countRequest(serverIndex, readAfter, consistency), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> countAsync(final int serverIndex, final long readAfter) {
    return client.sendAsync(countRequest(serverIndex, readAfter, null), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest countRequest(final int serverIndex, final long readAfter, final String consistency) {
    final HttpRequest.Builder builder = queryRequest(serverIndex);
    if (readAfter >= 0)
      builder.header(READ_AFTER, String.valueOf(readAfter));
    if (consistency != null)
      builder.header("X-ArcadeDB-Read-Consistency", consistency);
    return builder.build();
  }

  private HttpRequest.Builder queryRequest(final int serverIndex) {
    final JSONObject payload = new JSONObject().put("language", "sql")
        .put("command", "SELECT count(*) AS n FROM " + TYPE_NAME + " WHERE id = 'mine'");
    return HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(serverIndex) + "/query/" + getDatabaseName()))
        .timeout(HTTP_TIMEOUT)
        .header("Authorization", basicAuth())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
  }

  /** The port the node actually bound, never a hand-picked one. */
  private String baseUrl(final int serverIndex) {
    return "http://127.0.0.1:" + getServerHttpPort(serverIndex) + "/api/v1";
  }

  private static String basicAuth() {
    return "Basic " + Base64.getEncoder().encodeToString(
        ("root:" + BaseGraphServerTest.DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8));
  }
}
