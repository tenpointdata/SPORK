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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.security.entry.EntryPermissions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code <server>/config/server-spork-recon.json}; absent means the plugin serves nothing but its contract.
 *
 * <pre>
 * {
 *   "tenants": ["deliverfund"],            required: the tenant path segments this installation serves
 *   "auditDatabase": "identity",           the chain analyst actions append to (01-architecture 5.2)
 *   "directoryDatabase": "recdir",         upstreamRid -> period
 *   "partitionPrefix": "rec_",             rec_&lt;period&gt;
 *   "textPrefix": "txt_",                  txt_&lt;period&gt;, where search titles are read
 *   "hydrateThreads": 32,                  the spork-hydrate pool (2.5)
 *   "hydrateQueue": 1024,
 *   "searchEstimateBudget": 2000,          index entries a search's default (estimated) total may read
 *   "markings": {"spork.marking.minor": 0} Entry permission -> marking bit the holder may see
 *   "localMarkings": 0                     the marking bits of local (break-glass) users
 * }
 * </pre>
 * Markings come from the person's Entry permissions, never from the request (07-entry-identity-and-audit.md 2.5). Which permission
 * names map to which markings is an owner decision; until it is made the map is empty and only unmarked objects are visible.
 */
public final class SporkReconConfiguration {
  public static final  String  FILE_NAME = "server-spork-recon.json";
  private static final Pattern TENANT    = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

  private final Set<String> tenants;
  private final String      auditDatabase;
  private final String      directoryDatabase;
  private final String      partitionPrefix;
  private final String      textPrefix;
  private final int         hydrateThreads;
  private final int         hydrateQueue;
  private final int         searchEstimateBudget;
  private final String[]    markingPermissions;
  private final long[]      markingBits;
  private final long        localMarkings;

  private SporkReconConfiguration(final Set<String> tenants, final JSONObject json) {
    this.tenants = tenants;
    this.auditDatabase = name(json, "auditDatabase", "identity");
    this.directoryDatabase = name(json, "directoryDatabase", "recdir");
    this.partitionPrefix = name(json, "partitionPrefix", "rec_");
    this.textPrefix = name(json, "textPrefix", "txt_");
    this.hydrateThreads = positive(json, "hydrateThreads", 32);
    this.hydrateQueue = positive(json, "hydrateQueue", 1024);
    this.searchEstimateBudget = positive(json, "searchEstimateBudget", SearchService.DEFAULT_ESTIMATE_BUDGET);
    this.localMarkings = json.getLong("localMarkings", 0L);

    final JSONObject markings = json.getJSONObject("markings", new JSONObject());
    this.markingPermissions = new String[markings.length()];
    this.markingBits = new long[markings.length()];
    int i = 0;
    for (final String permission : markings.keySet()) {
      final int bit = markings.getInt(permission);
      if (permission.isEmpty() || bit < 0 || bit > 63)
        throw new IllegalArgumentException("A marking maps an Entry permission to a bit from 0 to 63: '" + permission + "'");
      markingPermissions[i] = permission;
      markingBits[i++] = 1L << bit;
    }
  }

  public static SporkReconConfiguration parse(final JSONObject json) {
    final JSONArray list = json.getJSONArray("tenants", null);
    if (list == null || list.length() == 0)
      throw new IllegalArgumentException(FILE_NAME + " needs at least one tenant in 'tenants'");
    final String[] names = new String[list.length()];
    for (int i = 0; i < names.length; i++) {
      names[i] = list.getString(i);
      if (!TENANT.matcher(names[i]).matches())
        throw new IllegalArgumentException("Invalid tenant '" + names[i] + "': lowercase letters, digits, '_' and '-', up to 64");
    }
    return new SporkReconConfiguration(Set.of(names), json);
  }

  /** The configuration in {@code configPath}, or null when the file is absent. */
  public static SporkReconConfiguration load(final String configPath) throws IOException {
    final File file = new File(configPath, FILE_NAME);
    if (!file.exists())
      return null;
    return parse(new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
  }

  public boolean isTenant(final String tenant) {
    return tenants.contains(tenant);
  }

  public String auditDatabase() {
    return auditDatabase;
  }

  public String directoryDatabase() {
    return directoryDatabase;
  }

  public String partitionPrefix() {
    return partitionPrefix;
  }

  public String textPrefix() {
    return textPrefix;
  }

  public int hydrateThreads() {
    return hydrateThreads;
  }

  public int hydrateQueue() {
    return hydrateQueue;
  }

  public int searchEstimateBudget() {
    return searchEstimateBudget;
  }

  public long localMarkings() {
    return localMarkings;
  }

  /** The marking bits a person holding these Entry permissions may see. */
  public long heldMarkings(final String[] permissions) {
    long held = 0L;
    for (int i = 0; i < markingPermissions.length; i++)
      if (EntryPermissions.holds(permissions, markingPermissions[i]))
        held |= markingBits[i];
    return held;
  }

  private static String name(final JSONObject json, final String key, final String defaultValue) {
    final String value = json.getString(key, defaultValue);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("'" + key + "' must name a database");
    return value;
  }

  private static int positive(final JSONObject json, final String key, final int defaultValue) {
    final int value = json.getInt(key, defaultValue);
    if (value < 1)
      throw new IllegalArgumentException("'" + key + "' must be at least 1");
    return value;
  }
}
