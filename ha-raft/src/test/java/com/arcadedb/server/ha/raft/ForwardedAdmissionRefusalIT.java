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
import com.arcadedb.database.Database;
import com.arcadedb.exception.QueryAdmissionException;
import com.arcadedb.query.sql.executor.QueryAdmissionGate;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Incident 2026-11-12 (df1db6, 06:39:17.288): a follower forwarded a write, the leader's query admission gate refused it
 * with {@code 503 QueryAdmissionException}, and the follower answered its client 500 "Error on transaction commit" naming
 * {@code TransactionException}, with no detail in production mode. The client must get what the leader said: 503, a
 * {@code Retry-After}, the exception class, and the gate's reason.
 * <p>
 * The servers share one JVM, and so one admission gate. With one slot and no queue wait, the follower's own admission takes
 * the free slot; the follower gives it back before it forwards, and the test hook takes it right then, so the leader is the
 * one that refuses. Production mode, because that is where the detail used to be lost.
 */
class ForwardedAdmissionRefusalIT extends BaseRaftHATest {

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  @Override
  protected int getServerCount() {
    return 2;
  }

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    config.setValue(GlobalConfiguration.SERVER_MODE, "production");
  }

  @Test
  @Timeout(120)
  void aWriteTheLeadersAdmissionGateRefusedIsAServiceUnavailableWithItsReason() throws Exception {
    final int leader = findLeaderIndex();
    assertThat(leader).as("a Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final int follower = leader == 0 ? 1 : 0;
    final String type = "Incident20261112Doc";
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("sql", "CREATE DOCUMENT TYPE " + type + " IF NOT EXISTS");
    await().atMost(30, TimeUnit.SECONDS)
        .until(() -> getServerDatabase(follower, getDatabaseName()).getSchema().existsType(type));

    final int previousMaxConcurrent = GlobalConfiguration.QUERY_MAX_CONCURRENT.getValueAsInteger();
    final long previousQueueTimeout = GlobalConfiguration.QUERY_QUEUE_TIMEOUT.getValueAsLong();
    final ExecutorService holder = Executors.newSingleThreadExecutor();
    final AtomicReference<QueryAdmissionGate.Ticket> held = new AtomicReference<>();
    final AtomicInteger forwards = new AtomicInteger();
    final HttpResponse<String> response;
    try {
      GlobalConfiguration.QUERY_MAX_CONCURRENT.setValue(1);
      GlobalConfiguration.QUERY_QUEUE_TIMEOUT.setValue(0L);
      RaftReplicatedDatabase.TEST_PRE_FORWARD_HOOK = database -> {
        forwards.incrementAndGet();
        try {
          // Another thread than the forwarding one: a ticket taken on the forwarding thread would join its request's slot
          held.compareAndSet(null, holder.submit(() -> QueryAdmissionGate.getInstance().admit(0)).get(10, TimeUnit.SECONDS));
        } catch (final Exception e) {
          throw new IllegalStateException("the test could not take the gate's last slot", e);
        }
      };

      response = postCommand(follower,
          new JSONObject().put("language", "sql").put("command", "INSERT INTO " + type + " SET tag = 'refused'").toString());
    } finally {
      RaftReplicatedDatabase.TEST_PRE_FORWARD_HOOK = null;
      final QueryAdmissionGate.Ticket ticket = held.get();
      if (ticket != null)
        holder.submit(ticket::close).get(10, TimeUnit.SECONDS);
      holder.shutdownNow();
      GlobalConfiguration.QUERY_MAX_CONCURRENT.setValue(previousMaxConcurrent);
      GlobalConfiguration.QUERY_QUEUE_TIMEOUT.setValue(previousQueueTimeout);
    }

    assertThat(forwards.get()).as("the follower forwarded the write once, and was refused by the leader").isEqualTo(1);
    assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(503);
    assertThat(response.headers().firstValue("Retry-After")).as("the back-off, relayed by the follower").contains("1");
    final JSONObject body = new JSONObject(response.body());
    assertThat(body.getString("exception")).isEqualTo(QueryAdmissionException.class.getName());
    assertThat(body.getString("detail", "")).as("the gate's reason, kept in production mode")
        .startsWith("Query not started because").contains(GlobalConfiguration.QUERY_QUEUE_TIMEOUT.getKey());
    assertThat(leaderDb.query("sql", "SELECT count(*) AS cnt FROM " + type).next().<Number>getProperty("cnt").longValue())
        .as("nothing of the refused write ran").isZero();
  }

  private HttpResponse<String> postCommand(final int serverIndex, final String body) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(getServerHttpUrl(serverIndex, "/api/v1/command/" + getDatabaseName())))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .header("Authorization", "Basic " + Base64.getEncoder()
            .encodeToString(("root:" + BaseGraphServerTest.DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8)))
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
