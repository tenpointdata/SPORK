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
package com.arcadedb.server.security.entry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The JDK HTTP client, configured as entry-lifecycle requires of a consumer read: no redirects (a redirect could carry the
 * bearer elsewhere), no cookie handler, a connect and a request timeout, and a body read no further than one byte past the
 * largest answer the client accepts, so a hostile or broken peer cannot make the server buffer an unbounded body.
 */
public final class HttpEntryTransport implements EntryTransport {
  private static final int MAX_BODY = 262_145;

  private final HttpClient client;
  private final Duration   timeout;

  public HttpEntryTransport(final int timeoutMillis) {
    this.timeout = Duration.ofMillis(timeoutMillis);
    this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
  }

  @Override
  public EntryResponse get(final String url, final String bearerToken) throws IOException {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET()//
        .header("Authorization", "Bearer " + bearerToken)//
        .header("Accept", "application/json")//
        .header("Cache-Control", "no-store")//
        .build();
    try {
      final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (final InputStream body = response.body()) {
        return new EntryResponse(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
            body.readNBytes(MAX_BODY));
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while reading from Entry", e);
    }
  }
}
