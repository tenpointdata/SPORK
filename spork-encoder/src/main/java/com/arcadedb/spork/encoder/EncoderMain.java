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

import com.arcadedb.log.LogManager;
import com.arcadedb.serializer.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

/**
 * Starts the sidecar: {@code java -cp ... com.arcadedb.spork.encoder.EncoderMain [--config encoder.json]}. Run it in
 * the {@code encode} cgroup with a heap of about 1 GB; the models' weights live in ONNX Runtime's native memory, not on
 * the heap (01-architecture.md 1.4 budgets 3 GB for the whole process).
 */
public final class EncoderMain {
  private EncoderMain() {
  }

  public static void main(final String[] args) throws Exception {
    JSONObject json = new JSONObject();
    for (int i = 0; i < args.length; i++)
      if ("--config".equals(args[i]) && i + 1 < args.length)
        json = new JSONObject(Files.readString(Path.of(args[++i])));
      else
        throw new IllegalArgumentException("Usage: EncoderMain [--config encoder.json]");

    final EncoderConfig config = EncoderConfig.fromJSON(json);
    final EncoderHttpServer server = start(config);
    LogManager.instance().log(EncoderMain.class, Level.INFO, "SPORK encoder listening on %s:%d with %d workers x %d ONNX Runtime threads",
        config.host(), server.port(), config.workers(), config.intraOpThreads());

    final CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.close();
      stopped.countDown();
    }, "spork-encoder-shutdown"));
    stopped.await();
  }

  public static EncoderHttpServer start(final EncoderConfig config) throws Exception {
    // The tokenizer's natives ship inside its jar: never let it fetch a library at runtime
    System.setProperty("ai.djl.offline", "true");
    final ModelRegistry registry = ModelRegistry.load(config.modelsDirectory(),
        (manifest, dir) -> new OnnxTextEncoder(manifest, dir, config.intraOpThreads()));
    final EncodeScheduler scheduler = new EncodeScheduler(config.workers(), config.probeMaxBatch(), config.bulkMaxBatch(),
        config.probeQueueCapacity(), config.bulkQueueCapacity());
    final EncoderHttpServer server = new EncoderHttpServer(config, registry, scheduler, new ProbeCache(config.probeCacheEntries()));
    try {
      server.start();
    } catch (final Exception e) {
      server.close();
      registry.close();
      throw e;
    }
    return server;
  }
}
