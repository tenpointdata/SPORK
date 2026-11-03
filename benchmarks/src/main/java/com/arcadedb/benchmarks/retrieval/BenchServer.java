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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.server.ArcadeDBServer;

import java.nio.file.Path;

/**
 * An in-process {@link ArcadeDBServer} over a generated dataset root. It binds the default HTTP range (2480-2489) and
 * reports the port it actually got: nothing here, nor in any client, names a port.
 */
public final class BenchServer implements AutoCloseable {
  public static final String ROOT_USER     = "root";
  public static final String ROOT_PASSWORD = "RetrievalBenchPassword1";

  private final ArcadeDBServer server;

  private BenchServer(final ArcadeDBServer server) {
    this.server = server;
  }

  public static BenchServer start(final Path datasetRoot) {
    final ContextConfiguration configuration = new ContextConfiguration();
    configuration.setValue(GlobalConfiguration.SERVER_NAME, "retrieval-bench");
    configuration.setValue(GlobalConfiguration.SERVER_ROOT_PATH, datasetRoot.resolve("server").toAbsolutePath().toString());
    configuration.setValue(GlobalConfiguration.SERVER_DATABASE_DIRECTORY, datasetRoot.resolve("databases").toAbsolutePath().toString());
    configuration.setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, ROOT_PASSWORD);
    configuration.setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_HOST, "localhost");
    configuration.setValue(GlobalConfiguration.HA_ENABLED, false);
    final ArcadeDBServer server = new ArcadeDBServer(configuration);
    server.start();
    return new BenchServer(server);
  }

  public ArcadeDBServer server() {
    return server;
  }

  /** The port the HTTP listener bound, read back from the server. */
  public int httpPort() {
    return server.getHttpServer().getPort();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + httpPort();
  }

  @Override
  public void close() {
    server.stop();
  }
}
