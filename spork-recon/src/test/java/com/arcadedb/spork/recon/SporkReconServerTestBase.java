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
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.BaseGraphServerTest;
import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * A server running the {@code spork-recon} plugin, with helpers that speak to it the way Recon's gateway does. Every URL comes
 * from the port the server actually bound.
 */
abstract class SporkReconServerTestBase extends BaseGraphServerTest {
  static final String TENANT = "deliverfund";

  record Answer(int status, String body, HttpURLConnection connection) {
    JSONObject json() {
      return new JSONObject(body);
    }

    String header(final String name) {
      return connection.getHeaderField(name);
    }
  }

  /** The plugin's configuration file for this test, written before the server starts. */
  protected abstract JSONObject pluginConfiguration();

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    config.setValue(GlobalConfiguration.SERVER_PLUGINS, "spork-recon:" + SporkReconPlugin.class.getName());
  }

  @Override
  protected void onBeforeStarting(final ArcadeDBServer server) {
    writeConfig(server, SporkReconConfiguration.FILE_NAME, pluginConfiguration());
  }

  static void writeConfig(final ArcadeDBServer server, final String fileName, final JSONObject content) {
    try {
      Files.createDirectories(new File(server.getConfigPath()).toPath());
      Files.writeString(new File(server.getConfigPath(), fileName).toPath(), content.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterEach
  void removePluginConfiguration() {
    // The config directory outlives this class: a leftover file would configure the plugin for an unrelated test
    for (int i = 0; i < getServerCount(); i++)
      if (getServer(i) != null)
        new File(getServer(i).getConfigPath(), SporkReconConfiguration.FILE_NAME).delete();
  }

  static String context(final String caseRef, final String purpose) {
    final JSONObject context = new JSONObject().put("purpose", purpose);
    if (caseRef != null)
      context.put("caseRef", caseRef);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(context.toString().getBytes(StandardCharsets.UTF_8));
  }

  static String basicRoot() {
    return "Basic " + Base64.getEncoder().encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8));
  }

  Answer call(final String method, final String path, final String authorization, final String context, final String body)
      throws IOException {
    final HttpURLConnection connection = (HttpURLConnection) URI.create(getServerHttpUrl(0, path)).toURL().openConnection();
    connection.setRequestMethod(method);
    if (authorization != null)
      connection.setRequestProperty("Authorization", authorization);
    if (context != null)
      connection.setRequestProperty(OperatingContext.HEADER, context);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      try (final OutputStream out = connection.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
    }
    try {
      final int status = connection.getResponseCode();
      try (final InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
        return new Answer(status, in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "", connection);
      }
    } finally {
      connection.disconnect();
    }
  }

  Answer post(final String route, final String authorization, final String context, final JSONObject body) throws IOException {
    return call("POST", SporkPath.PREFIX + "/" + TENANT + "/" + route, authorization, context, body != null ? body.toString() : "{}");
  }
}
