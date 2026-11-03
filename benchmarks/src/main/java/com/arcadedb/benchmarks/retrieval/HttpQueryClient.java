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
package com.arcadedb.benchmarks.retrieval;

import com.arcadedb.serializer.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * A small JDK {@link HttpClient} over {@code POST /api/v1/query/{db}}, doing what a Recon service would: send the SQL with
 * named parameters, parse every row it gets back. Two encodings of the same answer: the buffered JSON envelope
 * ({@code {"result": [...]}}) and the streamed NDJSON ({@code Accept: application/x-ndjson}, one {@code {"record": ...}} line
 * per row, then a {@code stats} trailer).
 */
public final class HttpQueryClient {
  public static final String JSON   = "json";
  public static final String NDJSON = "ndjson";

  private final HttpClient client;
  private final String     queryUrl;
  private final String     authorization;

  public HttpQueryClient(final String baseUrl, final String database, final String user, final String password) {
    this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    this.queryUrl = baseUrl + "/api/v1/query/" + database;
    this.authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  /** Runs the query in the given encoding and returns the number of rows, every one of them parsed. */
  public int query(final String format, final String sql, final Map<String, Object> params) throws IOException, InterruptedException {
    return NDJSON.equals(format) ? queryNdjson(sql, params) : queryJson(sql, params);
  }

  public int queryJson(final String sql, final Map<String, Object> params) throws IOException, InterruptedException {
    final HttpResponse<String> response = client.send(request(sql, params, "application/json"), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
    return new JSONObject(response.body()).getJSONArray("result").length();
  }

  public int queryNdjson(final String sql, final Map<String, Object> params) throws IOException, InterruptedException {
    final HttpResponse<InputStream> response = client.send(request(sql, params, "application/x-ndjson"),
        HttpResponse.BodyHandlers.ofInputStream());
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      if (response.statusCode() != 200)
        throw new IOException("HTTP " + response.statusCode() + ": " + reader.readLine());
      int rows = 0;
      boolean trailer = false;
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty())
          continue; // keep-alive
        final JSONObject event = new JSONObject(line);
        if (event.has("record"))
          rows++;
        else if (event.has("stats"))
          trailer = true;
        else if (event.has("error"))
          throw new IOException("stream error: " + line);
      }
      if (!trailer)
        throw new IOException("NDJSON stream ended without a stats trailer: result incomplete");
      return rows;
    }
  }

  private HttpRequest request(final String sql, final Map<String, Object> params, final String accept) {
    final JSONObject body = new JSONObject();
    body.put("language", "sql");
    body.put("command", sql);
    body.put("serializer", "record");
    body.put("params", QueryMix.toJsonParams(params));
    return HttpRequest.newBuilder(URI.create(queryUrl)).timeout(Duration.ofMinutes(5)).header("Authorization", authorization)
        .header("Content-Type", "application/json").header("Accept", accept)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
  }
}
