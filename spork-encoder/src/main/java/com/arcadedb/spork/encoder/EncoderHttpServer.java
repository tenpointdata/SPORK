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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The sidecar's HTTP API, called by the SPORK plugin (probe encodes) and the ingest saga and backfill (bulk encodes) on
 * the same node. JSON in, JSON out; vectors and codes travel as base64 of their exact bytes ({@link VectorCodes}).
 * <pre>
 * POST /v1/encode/text   {"space": "S-TEXT" | "model": "algo@ver", "texts": [...], "lane": "probe" | "bulk",
 *                         "outputs": ["float32", "binary", "int4", "int8"]}
 *                     -> {"model": "algo@ver", "space", "dimensions", "items": [{"float32": b64, "binary": b64,
 *                         "binaryMedian", "int4": b64, "int4Min", "int4Max", "int8": b64, "int8Min", "int8Max", "cached"}]}
 * POST /v1/encode/image  501 until the image stages land (see image.MediaEncodeStage)
 * GET  /v1/models        the registry: manifests without file names, and which model is active per space
 * GET  /v1/health        {"status": "ok", "models": [...]}
 * GET  /v1/stats         queue depths, texts encoded, busy time, cache hits
 * </pre>
 * Status codes: 400 malformed, 401 missing or wrong token, 404 unknown space or model, 405 wrong method, 413 too many
 * texts or too large a body, 429 queues full (retry after a pause), 500 model failure, 504 timed out.
 * <p>
 * The server binds the loopback address by default: the callers are on the node. Binding anything else requires a
 * token. Requests run on virtual threads that only parse, wait and serialize; every model call runs on the
 * {@link EncodeScheduler}'s workers, so the request count never changes the CPU the process uses.
 */
public final class EncoderHttpServer implements AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(EncoderHttpServer.class.getName());

  private enum Output {FLOAT32, BINARY, INT4, INT8}

  private final EncoderConfig   config;
  private final ModelRegistry   registry;
  private final EncodeScheduler scheduler;
  private final ProbeCache      cache;
  private final byte[]          token;
  private       HttpServer      server;
  private       ExecutorService executor;

  public EncoderHttpServer(final EncoderConfig config, final ModelRegistry registry, final EncodeScheduler scheduler, final ProbeCache cache) {
    this.config = config;
    this.registry = registry;
    this.scheduler = scheduler;
    this.cache = cache;
    this.token = config.authToken() != null ? config.authToken().getBytes(StandardCharsets.UTF_8) : null;
  }

  public void start() throws IOException {
    final InetAddress address = InetAddress.getByName(config.host());
    if (token == null && !address.isLoopbackAddress())
      throw new IllegalStateException("Binding " + config.host() + " needs an authToken: only a loopback bind may run without one");
    server = HttpServer.create(new InetSocketAddress(address, config.port()), 256);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/v1/encode/text", guarded("POST", this::encodeText));
    server.createContext("/v1/encode/image", guarded("POST", exchange -> send(exchange, 501,
        error("Image encoding is not wired yet: decode (libvips) and the S-IMG, S-FACE and S-PDQ stages land after P0d"))));
    server.createContext("/v1/models", guarded("GET", this::models));
    server.createContext("/v1/health", guarded("GET", exchange -> send(exchange, 200,
        new JSONObject().put("status", "ok").put("models", new JSONArray(registry.ids().toArray())))));
    server.createContext("/v1/stats", guarded("GET", this::stats));
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    if (server != null)
      server.stop(1);
    if (executor != null)
      executor.shutdownNow();
    scheduler.close();
  }

  private interface Handler {
    void handle(HttpExchange exchange) throws Exception;
  }

  private HttpHandler guarded(final String method, final Handler handler) {
    // The exchange closes after the catch blocks have answered: a try-with-resources would close it before them
    return exchange -> {
      try {
        if (!authorized(exchange)) {
          exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
          send(exchange, 401, error("Missing or wrong bearer token"));
          return;
        }
        if (!method.equals(exchange.getRequestMethod())) {
          exchange.getResponseHeaders().add("Allow", method);
          send(exchange, 405, error("Use " + method));
          return;
        }
        handler.handle(exchange);
      } catch (final RequestException e) {
        send(exchange, e.status, error(e.getMessage()));
      } catch (final UnknownModelException e) {
        send(exchange, 404, error(e.getMessage()));
      } catch (final EncoderBusyException e) {
        exchange.getResponseHeaders().add("Retry-After", "1");
        send(exchange, 429, error(e.getMessage()));
      } catch (final TimeoutException e) {
        send(exchange, 504, error("Timed out: " + e.getMessage()));
      } catch (final Exception e) {
        LOGGER.log(Level.WARNING, "Encoder request failed", e);
        send(exchange, 500, error(e.getMessage()));
      } finally {
        exchange.close();
      }
    };
  }

  private boolean authorized(final HttpExchange exchange) {
    if (token == null)
      return true;
    final String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer "))
      return false;
    return MessageDigest.isEqual(token, header.substring(7).getBytes(StandardCharsets.UTF_8));
  }

  private void encodeText(final HttpExchange exchange) throws Exception {
    final JSONObject request = readJSON(exchange);
    final JSONArray textsJSON = request.getJSONArray("texts", null);
    if (textsJSON == null || textsJSON.length() == 0)
      throw new RequestException(400, "'texts' must be a non-empty array of strings");
    if (textsJSON.length() > config.maxTextsPerRequest())
      throw new RequestException(413, textsJSON.length() + " texts, the limit per request is " + config.maxTextsPerRequest());
    final String[] texts = new String[textsJSON.length()];
    for (int i = 0; i < texts.length; i++) {
      if (!(textsJSON.get(i) instanceof String text))
        throw new RequestException(400, "texts[" + i + "] is not a string");
      texts[i] = text;
    }
    final String lane = request.getString("lane", "probe");
    if (!"probe".equals(lane) && !"bulk".equals(lane))
      throw new RequestException(400, "'lane' must be 'probe' or 'bulk'");
    final boolean[] outputs = parseOutputs(request.getJSONArray("outputs", null));

    final Encoder encoder = registry.resolve(request.getString("space", null), request.getString("model", null));
    if (!"text".equals(encoder.manifest().modality()))
      throw new RequestException(400, encoder.id() + " is not a text model");
    final int dims = encoder.dimensions();
    final float[] rows;
    final boolean[] cached = new boolean[texts.length];
    if ("bulk".equals(lane))
      rows = scheduler.encodeBulk(encoder, texts, config.bulkTimeoutMs());
    else
      rows = probe(encoder, texts, cached);

    final JSONArray items = new JSONArray(texts.length);
    final byte[] scratch = new byte[dims * 4];
    final float[] range = new float[2];
    final Base64.Encoder base64 = Base64.getEncoder();
    for (int i = 0; i < texts.length; i++) {
      final int off = i * dims;
      final JSONObject item = new JSONObject();
      if (outputs[Output.FLOAT32.ordinal()]) {
        VectorCodes.float32(rows, off, dims, scratch, 0);
        item.put("float32", base64.encodeToString(scratch));
      }
      if (outputs[Output.BINARY.ordinal()]) {
        final byte[] code = new byte[VectorCodes.binaryBytes(dims)];
        item.put("binaryMedian", VectorCodes.binary(rows, off, dims, code, 0));
        item.put("binary", base64.encodeToString(code));
      }
      if (outputs[Output.INT4.ordinal()]) {
        final byte[] code = new byte[VectorCodes.int4Bytes(dims)];
        VectorCodes.int4(rows, off, dims, code, 0, range);
        item.put("int4", base64.encodeToString(code)).put("int4Min", range[0]).put("int4Max", range[1]);
      }
      if (outputs[Output.INT8.ordinal()]) {
        final byte[] code = new byte[dims];
        VectorCodes.int8(rows, off, dims, code, 0, range);
        item.put("int8", base64.encodeToString(code)).put("int8Min", range[0]).put("int8Max", range[1]);
      }
      item.put("cached", cached[i]);
      items.put(item);
    }
    send(exchange, 200, new JSONObject().put("model", encoder.id().toString()).put("space", encoder.manifest().space())
        .put("dimensions", dims).put("items", items));
  }

  private float[] probe(final Encoder encoder, final String[] texts, final boolean[] cached) throws Exception {
    final String id = encoder.id().toString();
    final int dims = encoder.dimensions();
    final float[] rows = new float[texts.length * dims];
    @SuppressWarnings("unchecked")
    final CompletableFuture<float[]>[] pending = new CompletableFuture[texts.length];
    for (int i = 0; i < texts.length; i++) {
      final float[] hit = cache.get(id, texts[i]);
      if (hit != null) {
        System.arraycopy(hit, 0, rows, i * dims, dims);
        cached[i] = true;
      } else
        pending[i] = scheduler.submitProbe(encoder, texts[i]);
    }
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.probeTimeoutMs());
    for (int i = 0; i < texts.length; i++) {
      if (pending[i] == null)
        continue;
      final float[] vector;
      try {
        vector = pending[i].get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (final ExecutionException e) {
        throw e.getCause() instanceof RuntimeException r ? r : new EncoderException(e.getCause().getMessage(), e.getCause());
      }
      cache.put(id, texts[i], vector);
      System.arraycopy(vector, 0, rows, i * dims, dims);
    }
    return rows;
  }

  private void models(final HttpExchange exchange) throws IOException {
    final JSONArray models = new JSONArray();
    for (final Encoder encoder : registry.encoders())
      models.put(encoder.manifest().describe().put("active", registry.isActive(encoder)));
    send(exchange, 200, new JSONObject().put("models", models));
  }

  private void stats(final HttpExchange exchange) throws IOException {
    send(exchange, 200, new JSONObject().put("workers", scheduler.workers()).put("intraOpThreads", config.intraOpThreads())
        .put("probesQueued", scheduler.probesQueued()).put("bulkQueued", scheduler.bulkQueued()).put("rejected", scheduler.rejected())
        .put("textsEncoded", scheduler.textsEncoded()).put("batchesRun", scheduler.batchesRun())
        .put("busyMillis", TimeUnit.NANOSECONDS.toMillis(scheduler.busyNanos())).put("cacheEntries", cache.size())
        .put("cacheHits", cache.hits()).put("cacheMisses", cache.misses()));
  }

  private static boolean[] parseOutputs(final JSONArray json) {
    final boolean[] outputs = new boolean[Output.values().length];
    if (json == null || json.length() == 0) {
      outputs[Output.FLOAT32.ordinal()] = true;
      return outputs;
    }
    for (int i = 0; i < json.length(); i++)
      switch (String.valueOf(json.get(i))) {
      case "float32" -> outputs[Output.FLOAT32.ordinal()] = true;
      case "binary" -> outputs[Output.BINARY.ordinal()] = true;
      case "int4" -> outputs[Output.INT4.ordinal()] = true;
      case "int8" -> outputs[Output.INT8.ordinal()] = true;
      default -> throw new RequestException(400, "Unknown output '" + json.get(i) + "': use float32, binary, int4 or int8");
      }
    return outputs;
  }

  private JSONObject readJSON(final HttpExchange exchange) throws IOException {
    final byte[] body;
    try (final InputStream in = exchange.getRequestBody()) {
      body = in.readNBytes(config.maxRequestBytes() + 1);
    }
    if (body.length > config.maxRequestBytes())
      throw new RequestException(413, "Request body over " + config.maxRequestBytes() + " bytes");
    try {
      return new JSONObject(new String(body, StandardCharsets.UTF_8));
    } catch (final RuntimeException e) {
      throw new RequestException(400, "Malformed JSON: " + e.getMessage());
    }
  }

  private static JSONObject error(final String message) {
    return new JSONObject().put("error", message);
  }

  private static void send(final HttpExchange exchange, final int status, final JSONObject body) throws IOException {
    final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (final OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static final class RequestException extends RuntimeException {
    final int status;

    RequestException(final int status, final String message) {
      super(message);
      this.status = status;
    }
  }
}
