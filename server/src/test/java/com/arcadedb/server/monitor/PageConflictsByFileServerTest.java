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
package com.arcadedb.server.monitor;

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 5 of the SPORK stress report, the server side: {@code GET /api/v1/server} reports the page-version conflicts by
 * file ({@code metrics.profiler.pageConflictsByFile.top}) and the transaction retries ({@code txRetries}).
 */
class PageConflictsByFileServerTest extends BaseGraphServerTest {
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void theServerEndpointListsTheConflictingFile() throws Exception {
    final Database db = getServerDatabase(0, getDatabaseName());
    db.getSchema().createDocumentType("ConflictDoc");
    db.begin();
    final RID rid = db.newDocument("ConflictDoc").set("n", 0).save().getIdentity();
    db.commit();

    // TWO TRANSACTIONS UPDATING THE SAME RECORD: THE SECOND TO COMMIT FAILS ON THE BUCKET PAGE
    final CountDownLatch ready = new CountDownLatch(1);
    final CountDownLatch go = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final Thread t = new Thread(() -> {
      try {
        db.begin();
        rid.asDocument(true).modify().set("n", 1).save();
        ready.countDown();
        go.await();
        db.commit();
      } catch (final Throwable e) {
        error.set(e);
        if (db.isTransactionActive())
          db.rollback();
      } finally {
        ready.countDown();
      }
    });
    t.start();
    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    db.transaction(() -> rid.asDocument(true).modify().set("n", 2).save());
    go.countDown();
    t.join(30_000);
    assertThat(error.get()).isNotNull();

    final HttpRequest request = HttpRequest.newBuilder()
        .uri(new URI(getServerHttpUrl("/api/v1/server")))
        .GET()
        .setHeader("Authorization",
            "Basic " + Base64.getEncoder().encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes()))
        .build();
    final var response = client.send(request, BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    final JSONObject profiler = new JSONObject(response.body()).getJSONObject("metrics").getJSONObject("profiler");
    assertThat(profiler.getJSONObject("txRetries").has("count")).isTrue();

    final JSONArray top = profiler.getJSONObject("pageConflictsByFile").getJSONArray("top");
    JSONObject row = null;
    for (int i = 0; i < top.length(); i++) {
      final JSONObject r = top.getJSONObject(i);
      if (getDatabaseName().equals(r.getString("database", null)) && r.getInt("fileId", -1) == rid.getBucketId())
        row = r;
    }
    assertThat(row).as("the bucket of the conflicting record is listed").isNotNull();
    assertThat(row.getString("kind")).isEqualTo("bucket");
    assertThat(row.getString("detail")).isEqualTo("ConflictDoc");
    assertThat(row.getLong("conflicts")).isGreaterThanOrEqualTo(1L);
    assertThat(row.getLong("failed")).isGreaterThanOrEqualTo(1L);
  }
}
