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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code <config>/server-entry.json}: where Entry is, which installation this server is to Entry, and which ArcadeDB groups
 * each Entry permission grants (docs/spork/07-entry-identity-and-audit.md 3). No file, no Entry: the server behaves exactly as
 * before. A file that does not validate stops the server from starting rather than leaving Entry half-configured.
 */
public final class EntryConfiguration {
  public static final  String  FILE_NAME      = "server-entry.json";
  static final         Pattern UUID           = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  static final         Pattern INSTALLATION   = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");
  private static final Pattern SHA256_HEX     = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  private final String                         origin;
  private final String                         environmentId;
  private final String                         serviceId;
  private final String                         enterpriseOrgId;
  private final String                         database;
  private final byte[]                         lifecycleCredentialSha256;
  private final long                           verdictCacheMillis;
  private final int                            maxCachedVerdicts;
  private final int                            maxConcurrentLookups;
  private final int                            timeoutMillis;
  private final Map<String, Map<String, String[]>> grants;

  private EntryConfiguration(final JSONObject json) {
    origin = canonicalOrigin(json.getString("origin", null));
    environmentId = json.getString("environmentId", null);
    if (!isIdentifier(environmentId))
      throw new IllegalArgumentException("Entry 'environmentId' is required");
    serviceId = json.getString("serviceId", "spork");
    if (!INSTALLATION.matcher(serviceId).matches())
      throw new IllegalArgumentException("Entry 'serviceId' is invalid");
    enterpriseOrgId = json.getString("enterpriseOrgId", null);
    if (enterpriseOrgId != null && !UUID.matcher(enterpriseOrgId).matches())
      throw new IllegalArgumentException("Entry 'enterpriseOrgId' must be an Entry organization id");
    database = json.getString("database", "identity");
    if (database.isBlank())
      throw new IllegalArgumentException("Entry 'database' is invalid");

    final String credentialHash = json.getString("lifecycleCredentialSha256", null);
    if (credentialHash != null && !SHA256_HEX.matcher(credentialHash).matches())
      throw new IllegalArgumentException("Entry 'lifecycleCredentialSha256' must be a lowercase hex SHA-256");
    lifecycleCredentialSha256 = credentialHash != null ? HexFormat.of().parseHex(credentialHash) : null;

    verdictCacheMillis = bounded(json, "verdictCacheSeconds", 30, 0, 3_600) * 1_000L;
    maxCachedVerdicts = bounded(json, "maxCachedVerdicts", 10_000, 1, 1_000_000);
    maxConcurrentLookups = bounded(json, "maxConcurrentLookups", 32, 1, 1_024);
    timeoutMillis = bounded(json, "timeoutMillis", 5_000, 100, 15_000);
    grants = parseGrants(json.getJSONObject("grants", null));
  }

  /** @return the configuration in {@code configPath}, or null when the file is absent */
  public static EntryConfiguration load(final String configPath) {
    final File file = new File(configPath, FILE_NAME);
    if (!file.exists())
      return null;
    try {
      return parse(new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
    } catch (final IOException e) {
      throw new IllegalArgumentException("Cannot read " + file, e);
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException("Invalid " + file + ": " + e.getMessage(), e);
    }
  }

  public static EntryConfiguration parse(final JSONObject json) {
    return new EntryConfiguration(json);
  }

  /** The databases and groups the held permissions grant, as a user's {@code databases} block; empty when none. */
  public JSONObject databasesFor(final String[] heldPermissions) {
    final Map<String, Set<String>> union = new LinkedHashMap<>();
    for (final Map.Entry<String, Map<String, String[]>> grant : grants.entrySet())
      if (EntryPermissions.holds(heldPermissions, grant.getKey()))
        for (final Map.Entry<String, String[]> database : grant.getValue().entrySet())
          union.computeIfAbsent(database.getKey(), k -> new LinkedHashSet<>()).addAll(Set.of(database.getValue()));

    final JSONObject databases = new JSONObject();
    for (final Map.Entry<String, Set<String>> database : union.entrySet())
      databases.put(database.getKey(), new JSONArray(database.getValue()));
    return databases;
  }

  /** Constant-time comparison of the presented lifecycle credential's digest; false when no credential is configured. */
  public boolean acceptsLifecycleCredential(final String presented) {
    if (lifecycleCredentialSha256 == null || presented == null || presented.isEmpty())
      return false;
    return MessageDigest.isEqual(sha256(presented), lifecycleCredentialSha256);
  }

  public boolean hasLifecycleCredential() {
    return lifecycleCredentialSha256 != null;
  }

  public static String sha256Hex(final String value) {
    return HexFormat.of().formatHex(sha256(value));
  }

  static byte[] sha256(final String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  static boolean isIdentifier(final String value) {
    if (value == null || value.isEmpty() || value.length() > 200 || !value.strip().equals(value))
      return false;
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c < 0x20 || c == 0x7f)
        return false;
    }
    return true;
  }

  public String getOrigin() {
    return origin;
  }

  public String getEnvironmentId() {
    return environmentId;
  }

  public String getServiceId() {
    return serviceId;
  }

  public String getEnterpriseOrgId() {
    return enterpriseOrgId;
  }

  public String getDatabase() {
    return database;
  }

  public long getVerdictCacheMillis() {
    return verdictCacheMillis;
  }

  public int getMaxCachedVerdicts() {
    return maxCachedVerdicts;
  }

  public int getMaxConcurrentLookups() {
    return maxConcurrentLookups;
  }

  public int getTimeoutMillis() {
    return timeoutMillis;
  }

  /** Canonical origin only, as entry-lifecycle's {@code validateConsumerSessionSource}: cleartext on loopback only. */
  private static String canonicalOrigin(final String value) {
    if (value == null)
      throw new IllegalArgumentException("Entry 'origin' is required");
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("Entry 'origin' is not a URL");
    }
    final String scheme = uri.getScheme();
    final String host = uri.getHost();
    if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
      throw new IllegalArgumentException("Entry 'origin' must be scheme://host[:port] and nothing else");
    if (!"https".equals(scheme) && !("http".equals(scheme) && LOCAL_HOSTS.contains(host)))
      throw new IllegalArgumentException("Entry 'origin' must use https (http only on localhost)");
    return scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
  }

  private static Map<String, Map<String, String[]>> parseGrants(final JSONObject json) {
    if (json == null || json.length() == 0)
      throw new IllegalArgumentException("Entry 'grants' must map at least one Entry permission to ArcadeDB groups");
    final Map<String, Map<String, String[]>> parsed = new LinkedHashMap<>();
    for (final String permission : json.keySet()) {
      final JSONObject databases = json.getJSONObject(permission);
      if (!isIdentifier(permission) || databases.length() == 0)
        throw new IllegalArgumentException("Entry grant '" + permission + "' names no database");
      final Map<String, String[]> byDatabase = new LinkedHashMap<>();
      for (final String database : databases.keySet()) {
        final JSONArray groups = databases.getJSONArray(database);
        if (groups.length() == 0)
          throw new IllegalArgumentException("Entry grant '" + permission + "' gives database '" + database + "' no group");
        final String[] names = new String[groups.length()];
        for (int i = 0; i < names.length; i++)
          names[i] = groups.getString(i);
        byDatabase.put(database, names);
      }
      parsed.put(permission, byDatabase);
    }
    return parsed;
  }

  private static int bounded(final JSONObject json, final String key, final int defaultValue, final int min, final int max) {
    final long value = json.getLong(key, defaultValue);
    if (value < min || value > max)
      throw new IllegalArgumentException("Entry '" + key + "' must be between " + min + " and " + max);
    return (int) value;
  }
}
