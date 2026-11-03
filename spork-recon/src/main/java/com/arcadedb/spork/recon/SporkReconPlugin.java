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
package com.arcadedb.spork.recon;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.log.LogManager;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.ServerPlugin;
import com.arcadedb.server.http.HttpServer;
import com.arcadedb.server.http.PluginRoutes;
import io.undertow.util.Methods;

import java.io.IOException;
import java.util.logging.Level;

/**
 * The {@code spork-recon} ServerPlugin: Recon's API on SPORK under {@code /api/spork/v1/{tenant}/...}
 * (docs/spork/01-architecture.md 7, docs/spork/workstreams/ws3-recon-plugin.md). Opt-in: name it in {@code SERVER_PLUGINS}
 * ({@code spork-recon:com.arcadedb.spork.recon.SporkReconPlugin}) and write {@code server-spork-recon.json}.
 * <p>
 * The tenant is a path segment, so the plugin claims the namespace as one prefix per method it serves and dispatches inside it
 * from the declarative route table; any other method on the prefix is a 405 before the handler runs.
 */
public class SporkReconPlugin implements ServerPlugin {
  private ArcadeDBServer          server;
  private SporkReconConfiguration config;
  private HydratePool             pool;

  public SporkReconPlugin() {
  }

  /** For tests that register the routes without a server. */
  SporkReconPlugin(final SporkReconConfiguration config) {
    this.config = config;
  }

  @Override
  public void configure(final ArcadeDBServer server, final ContextConfiguration configuration) {
    this.server = server;
    try {
      config = SporkReconConfiguration.load(server.getConfigPath());
    } catch (final IOException | IllegalArgumentException e) {
      // Fails closed: a configuration that cannot be read serves nothing, rather than serving with defaults nobody chose
      throw new IllegalStateException("Cannot read " + SporkReconConfiguration.FILE_NAME + ": " + e.getMessage(), e);
    }
    if (config == null)
      LogManager.instance().log(this, Level.WARNING, "spork-recon has no %s: only its contract is served",
          SporkReconConfiguration.FILE_NAME);
  }

  @Override
  public void startService() {
    // the pool is created with the routes, on the first registerAPI
  }

  @Override
  public void stopService() {
    if (pool != null) {
      pool.close();
      pool = null;
    }
  }

  @Override
  public void registerAPI(final HttpServer httpServer, final PluginRoutes routes) {
    HydrateService hydrate = null;
    SearchService search = null;
    SporkAudit audit = null;
    if (config != null && pool == null)
      pool = new HydratePool(config.hydrateThreads(), config.hydrateQueue());
    if (config != null && server != null) {
      hydrate = new HydrateService(server, config, pool);
      search = new SearchService(server, config);
      audit = new SporkAudit(server, config.auditDatabase());
    }
    final SporkReconHandler handler = new SporkReconHandler(httpServer, server != null ? config : null, hydrate, search, audit);
    routes.prefix(Methods.POST, SporkPath.PREFIX, handler);
    routes.prefix(Methods.GET, SporkPath.PREFIX, handler);
  }
}
