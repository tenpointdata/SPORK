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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The S-TEXT pipeline in Java (tokenizer, padding, ONNX Runtime, truncation to 256-d, L2 normalization) against the
 * reference {@code scripts/export_gte.py} computed once: the token ids of the Hugging Face tokenizer, the fp32 PyTorch
 * embeddings of the pinned revision, and the int8 model's embeddings in Python ONNX Runtime.
 * <p>
 * Needs the exported model in {@code spork-encoder/models} (git-ignored, 325 MB); without it the test is skipped, and
 * says so.
 */
class GteGoldenTest {
  private static final String MODEL = "gte-multilingual-base@1";

  private static JSONObject      golden;
  private static ModelRegistry   registry;
  private static OnnxTextEncoder encoder;

  @BeforeAll
  static void load() throws Exception {
    final Path models = Path.of(System.getProperty("spork.encoder.models", "models"));
    assumeTrue(Files.isRegularFile(models.resolve("gte-multilingual-base/1/model.json")),
        "S-TEXT model not exported: run spork-encoder/scripts/export_gte.py (see docs/spork/workstreams/ws13-encoder.md)");
    try (final InputStream in = GteGoldenTest.class.getResourceAsStream("/golden/" + MODEL + ".json")) {
      golden = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    System.setProperty("ai.djl.offline", "true");
    registry = ModelRegistry.load(models, (manifest, dir) -> new OnnxTextEncoder(manifest, dir, 1));
    encoder = (OnnxTextEncoder) registry.resolve("S-TEXT", null);
  }

  @AfterAll
  static void close() {
    if (registry != null)
      registry.close();
  }

  @Test
  void theGoldenFileWasComputedWithTheInstalledModel() {
    assertThat(encoder.id().toString()).isEqualTo(MODEL);
    assertThat(encoder.manifest().sha256()).isEqualTo(golden.getString("sha256"));
    assertThat(encoder.dimensions()).isEqualTo(golden.getInt("dimensions"));
    assertThat(encoder.manifest().maxTokens()).isEqualTo(golden.getInt("maxTokens"));
  }

  @Test
  void tokenizerProducesTheHuggingFaceIdsIncludingTruncation() {
    final JSONArray items = golden.getJSONArray("items");
    for (int i = 0; i < items.length(); i++) {
      final JSONObject item = items.getJSONObject(i);
      final JSONArray expected = item.getJSONArray("ids");
      final long[] ids = encoder.tokenize(item.getString("text"));
      assertThat(ids.length).as("token count of item %d", i).isEqualTo(expected.length());
      for (int t = 0; t < ids.length; t++)
        assertThat(ids[t]).as("item %d token %d", i, t).isEqualTo(expected.getLong(t));
    }
  }

  @Test
  void eachTextAloneMatchesTheReference() throws Exception {
    final JSONArray items = golden.getJSONArray("items");
    for (int i = 0; i < items.length(); i++) {
      final JSONObject item = items.getJSONObject(i);
      final float[] row = encoder.encode(new String[] { item.getString("text") });
      assertThat(row).hasSize(256);
      assertThat(norm(row, 0)).isCloseTo(1.0, within(1e-5));
      // The same int8 graph in Python ONNX Runtime 1.23: only the kernels differ (Java runs 1.31), worst seen 0.99984
      assertThat(cosine(row, 0, item.getJSONArray("int8"))).as("item %d vs Python int8", i).isGreaterThan(0.9995);
      // The fp32 PyTorch model of the pinned revision: the quantization error budget
      assertThat(cosine(row, 0, item.getJSONArray("fp32"))).as("item %d vs PyTorch fp32", i).isGreaterThan(0.999);
    }
  }

  @Test
  void aPaddedBatchGivesEveryTextTheVectorItGetsAlone() throws Exception {
    final JSONArray items = golden.getJSONArray("items");
    final String[] texts = new String[items.length()];
    for (int i = 0; i < texts.length; i++)
      texts[i] = items.getJSONObject(i).getString("text");
    final float[] rows = encoder.encode(texts);
    for (int i = 0; i < texts.length; i++) {
      final float[] alone = encoder.encode(new String[] { texts[i] });
      double dot = 0;
      for (int d = 0; d < 256; d++)
        dot += rows[i * 256 + d] * alone[d];
      assertThat(dot).as("item %d batched vs alone", i).isGreaterThan(0.9999);
      assertThat(cosine(rows, i * 256, items.getJSONObject(i).getJSONArray("fp32"))).as("item %d batched vs fp32", i).isGreaterThan(0.999);
    }
  }

  @Test
  void theSidecarServesTheSameVectorOnBothLanesOverHttp() throws Exception {
    final EncoderConfig config = EncoderConfig.fromJSON(new JSONObject().put("port", 0).put("workers", 2).put("intraOpThreads", 1)
        .put("modelsDirectory", System.getProperty("spork.encoder.models", "models")));
    final EncoderHttpServer server = EncoderMain.start(config);
    try {
      final HttpClient client = HttpClient.newHttpClient();
      final JSONObject item = golden.getJSONArray("items").getJSONObject(1);
      final float[][] lanes = new float[2][];
      int lane = 0;
      for (final String name : new String[] { "probe", "bulk" }) {
        final JSONObject request = new JSONObject().put("space", "S-TEXT").put("lane", name)
            .put("texts", new JSONArray(new String[] { item.getString("text"), "padding partner of another length entirely" }))
            .put("outputs", new JSONArray(new String[] { "float32", "binary", "int4" }));
        final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/encode/text"))
            .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        final JSONObject body = new JSONObject(response.body());
        assertThat(body.getString("model")).isEqualTo(MODEL);
        final JSONObject first = body.getJSONArray("items").getJSONObject(0);
        assertThat(Base64.getDecoder().decode(first.getString("binary"))).hasSize(32);
        assertThat(Base64.getDecoder().decode(first.getString("int4"))).hasSize(128);
        final ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(first.getString("float32"))).order(ByteOrder.LITTLE_ENDIAN);
        final float[] vector = new float[256];
        bytes.asFloatBuffer().get(vector);
        assertThat(cosine(vector, 0, item.getJSONArray("fp32"))).as(name).isGreaterThan(0.999);
        lanes[lane++] = vector;
      }
      double dot = 0;
      for (int d = 0; d < 256; d++)
        dot += lanes[0][d] * lanes[1][d];
      assertThat(dot).as("probe vs bulk").isGreaterThan(0.9999);
    } finally {
      server.close();
    }
  }

  private static double cosine(final float[] rows, final int off, final JSONArray reference) {
    double dot = 0;
    double norm = 0;
    for (int d = 0; d < 256; d++) {
      final double r = reference.getDouble(d);
      dot += rows[off + d] * r;
      norm += r * r;
    }
    return dot / (Math.sqrt(norm) * norm(rows, off));
  }

  private static double norm(final float[] rows, final int off) {
    double sum = 0;
    for (int d = 0; d < 256; d++)
      sum += rows[off + d] * rows[off + d];
    return Math.sqrt(sum);
  }
}
