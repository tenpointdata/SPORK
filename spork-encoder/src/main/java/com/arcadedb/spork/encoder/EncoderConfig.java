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

import com.arcadedb.serializer.json.JSONObject;

import java.nio.file.Path;

/**
 * The sidecar's settings: a JSON file ({@code --config}), each key overridable by a {@code spork.encoder.<key>} system
 * property. The thread defaults follow {@link #defaultWorkers}: see docs/spork/workstreams/ws13-encoder.md for the
 * measurements behind them.
 *
 * @param cores              the CPUs this process may use; {@link Runtime#availableProcessors()} already honours the
 *                           cgroup's CPU quota, so in the {@code encode} cgroup it is 4 (2 on n1)
 * @param workers            concurrent model calls
 * @param intraOpThreads     ONNX Runtime threads inside one model call
 * @param authToken          required as {@code Authorization: Bearer} on every call when set; mandatory when the
 *                           server binds anything but a loopback address
 */
public record EncoderConfig(Path modelsDirectory, String host, int port, String authToken, int cores, int workers, int intraOpThreads,
                            int probeMaxBatch, int bulkMaxBatch, int probeQueueCapacity, int bulkQueueCapacity, int maxTextsPerRequest,
                            int maxRequestBytes, long probeTimeoutMs, long bulkTimeoutMs, int probeCacheEntries) {

  public static final int DEFAULT_PORT = 2495;

  public static EncoderConfig fromJSON(final JSONObject json) {
    final int cores = override(json, "cores", Runtime.getRuntime().availableProcessors());
    final int intraOp = override(json, "intraOpThreads", defaultIntraOpThreads(cores));
    final String token = override(json, "authToken", (String) null);
    if (token != null && token.length() < 16)
      throw new IllegalArgumentException("authToken must be at least 16 characters");
    return new EncoderConfig(Path.of(override(json, "modelsDirectory", "models")), override(json, "host", "127.0.0.1"),
        override(json, "port", DEFAULT_PORT), token, cores, override(json, "workers", defaultWorkers(cores, intraOp)), intraOp,
        override(json, "probeMaxBatch", 16), override(json, "bulkMaxBatch", 32), override(json, "probeQueueCapacity", 256),
        override(json, "bulkQueueCapacity", 64), override(json, "maxTextsPerRequest", 256), override(json, "maxRequestBytes", 8 << 20),
        override(json, "probeTimeoutMs", 10_000), override(json, "bulkTimeoutMs", 300_000), override(json, "probeCacheEntries", 50_000));
  }

  /**
   * One ONNX Runtime thread per model call, one call per core: on CPU int8 the transformer's matmuls at batch 32 keep a
   * core busy on their own, so independent calls scale better than one call split across cores, and a single-threaded
   * call never waits on its own helpers (ws13-encoder.md, "Thread and batch sizing").
   */
  static int defaultIntraOpThreads(final int cores) {
    return 1;
  }

  static int defaultWorkers(final int cores, final int intraOpThreads) {
    return Math.max(1, cores / Math.max(1, intraOpThreads));
  }

  private static String override(final JSONObject json, final String key, final String defaultValue) {
    return System.getProperty("spork.encoder." + key, json.getString(key, defaultValue));
  }

  private static int override(final JSONObject json, final String key, final int defaultValue) {
    final String property = System.getProperty("spork.encoder." + key);
    return property != null ? Integer.parseInt(property) : json.getInt(key, defaultValue);
  }

  private static long override(final JSONObject json, final String key, final long defaultValue) {
    final String property = System.getProperty("spork.encoder." + key);
    return property != null ? Long.parseLong(property) : json.getLong(key, defaultValue);
  }
}
