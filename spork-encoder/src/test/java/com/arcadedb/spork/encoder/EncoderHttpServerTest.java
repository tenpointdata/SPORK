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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.spork.encoder;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class EncoderHttpServerTest {
  private static final int        DIMS   = 8;
  private final        HttpClient client = HttpClient.newHttpClient();
  private              FakeEncoder        encoder;
  private              EncoderHttpServer  server;

  private void start(final JSONObject config) throws Exception {
    encoder = new FakeEncoder("gte-multilingual-base@1", "S-TEXT", DIMS);
    final EncoderConfig cfg = EncoderConfig.fromJSON(config.put("port", 0).put("maxTextsPerRequest", 4));
    server = new EncoderHttpServer(cfg, ModelRegistry.of(encoder), new EncodeScheduler(2, 8, 4, 64, 64), new ProbeCache(100));
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null)
      server.close();
  }

  @Test
  void encodesTextsIntoVectorsAndIndexCodes() throws Exception {
    start(new JSONObject());
    final HttpResponse<String> response = post("/v1/encode/text", new JSONObject().put("space", "S-TEXT")//
        .put("lane", "bulk").put("texts", new JSONArray(new String[] { "first ad", "second" }))//
        .put("outputs", new JSONArray(new String[] { "float32", "binary", "int4" })), null);

    assertThat(response.statusCode()).isEqualTo(200);
    final JSONObject body = new JSONObject(response.body());
    assertThat(body.getString("model")).isEqualTo("gte-multilingual-base@1");
    assertThat(body.getString("space")).isEqualTo("S-TEXT");
    assertThat(body.getInt("dimensions")).isEqualTo(DIMS);
    final JSONArray items = body.getJSONArray("items");
    assertThat(items.length()).isEqualTo(2);

    final String[] texts = { "first ad", "second" };
    for (int i = 0; i < 2; i++) {
      final JSONObject item = items.getJSONObject(i);
      final float[] expected = FakeEncoder.expected(texts[i], DIMS);
      final ByteBuffer floats = ByteBuffer.wrap(Base64.getDecoder().decode(item.getString("float32"))).order(ByteOrder.LITTLE_ENDIAN);
      for (int d = 0; d < DIMS; d++)
        assertThat(floats.getFloat()).isEqualTo(expected[d]);

      final byte[] binary = new byte[VectorCodes.binaryBytes(DIMS)];
      final float median = VectorCodes.binary(expected, 0, DIMS, binary, 0);
      assertThat(Base64.getDecoder().decode(item.getString("binary"))).containsExactly(binary);
      assertThat(item.getFloat("binaryMedian")).isEqualTo(median);

      final byte[] int4 = new byte[VectorCodes.int4Bytes(DIMS)];
      final float[] range = new float[2];
      VectorCodes.int4(expected, 0, DIMS, int4, 0, range);
      assertThat(Base64.getDecoder().decode(item.getString("int4"))).containsExactly(int4);
      assertThat(item.getFloat("int4Min")).isEqualTo(range[0]);
      assertThat(item.getFloat("int4Max")).isEqualTo(range[1]);
    }
  }

  @Test
  void probeLaneServesARepeatFromTheCache() throws Exception {
    start(new JSONObject());
    final JSONObject request = new JSONObject().put("model", "gte-multilingual-base@1").put("texts", new JSONArray(new String[] { "who is this" }));
    final JSONObject first = new JSONObject(post("/v1/encode/text", request, null).body());
    final JSONObject second = new JSONObject(post("/v1/encode/text", request, null).body());

    assertThat(first.getJSONArray("items").getJSONObject(0).getBoolean("cached")).isFalse();
    assertThat(second.getJSONArray("items").getJSONObject(0).getBoolean("cached")).isTrue();
    assertThat(second.getJSONArray("items").getJSONObject(0).getString("float32"))//
        .isEqualTo(first.getJSONArray("items").getJSONObject(0).getString("float32"));
    assertThat(encoder.encoded.get()).isEqualTo(1);
  }

  @Test
  void rejectsBadRequestsWithTheRightStatus() throws Exception {
    start(new JSONObject());
    assertThat(post("/v1/encode/text", new JSONObject().put("space", "S-IMG").put("texts", new JSONArray(new String[] { "x" })), null).statusCode())//
        .isEqualTo(404);
    assertThat(post("/v1/encode/text", new JSONObject().put("space", "S-TEXT").put("texts", new JSONArray()), null).statusCode()).isEqualTo(400);
    assertThat(post("/v1/encode/text", new JSONObject().put("space", "S-TEXT")//
        .put("texts", new JSONArray(new String[] { "1", "2", "3", "4", "5" })), null).statusCode()).isEqualTo(413);
    assertThat(post("/v1/encode/text", new JSONObject().put("space", "S-TEXT").put("lane", "fast")//
        .put("texts", new JSONArray(new String[] { "x" })), null).statusCode()).isEqualTo(400);
    assertThat(post("/v1/encode/text", new JSONObject().put("space", "S-TEXT").put("outputs", new JSONArray(new String[] { "pq" }))//
        .put("texts", new JSONArray(new String[] { "x" })), null).statusCode()).isEqualTo(400);

    final HttpResponse<String> malformed = client.send(HttpRequest.newBuilder(uri("/v1/encode/text"))//
        .POST(HttpRequest.BodyPublishers.ofString("{not json")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(malformed.statusCode()).isEqualTo(400);

    final HttpResponse<String> get = client.send(HttpRequest.newBuilder(uri("/v1/encode/text")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }

  @Test
  void imageEncodingIsNotWiredYet() throws Exception {
    start(new JSONObject());
    assertThat(post("/v1/encode/image", new JSONObject().put("space", "S-IMG"), null).statusCode()).isEqualTo(501);
  }

  @Test
  void healthAndModelsDescribeTheRegistryWithoutPaths() throws Exception {
    start(new JSONObject());
    final HttpResponse<String> health = client.send(HttpRequest.newBuilder(uri("/v1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(new JSONObject(health.body()).getString("status")).isEqualTo("ok");

    final HttpResponse<String> models = client.send(HttpRequest.newBuilder(uri("/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString());
    final JSONObject model = new JSONObject(models.body()).getJSONArray("models").getJSONObject(0);
    assertThat(model.getString("id")).isEqualTo("gte-multilingual-base@1");
    assertThat(model.getBoolean("active")).isTrue();
    assertThat(model.getInt("dimensions")).isEqualTo(DIMS);
    assertThat(model.has("file")).isFalse();
  }

  @Test
  void aConfiguredTokenIsRequiredOnEveryCall() throws Exception {
    start(new JSONObject().put("authToken", "s3cret-token-of-sufficient-length"));
    final JSONObject request = new JSONObject().put("space", "S-TEXT").put("texts", new JSONArray(new String[] { "x" }));
    assertThat(post("/v1/encode/text", request, null).statusCode()).isEqualTo(401);
    assertThat(post("/v1/encode/text", request, "wrong").statusCode()).isEqualTo(401);
    assertThat(post("/v1/encode/text", request, "s3cret-token-of-sufficient-length").statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> post(final String path, final JSONObject body, final String token) throws Exception {
    final HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")//
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    if (token != null)
      builder.header("Authorization", "Bearer " + token);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(final String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }
}
