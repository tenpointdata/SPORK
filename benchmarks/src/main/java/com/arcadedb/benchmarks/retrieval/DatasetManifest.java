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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a generated dataset is, written next to it as {@code manifest.json} so a benchmark can sample parameters without
 * scanning the database: the scale and seed (every identifier key is a pure function of them, see {@link ReconDomain}),
 * the time range the records cover, a sample of real Record RIDs for the hydration query, and the generation timings.
 * <p>
 * Layout of a dataset root: {@code <root>/manifest.json} and {@code <root>/databases/<DATABASE_NAME>}, the latter being
 * the directory an {@link com.arcadedb.server.ArcadeDBServer} can be pointed at as its database directory.
 */
public final class DatasetManifest {
  public static final String DATABASE_NAME = "recon";
  public static final String FILE_NAME     = "manifest.json";

  public long                records;
  public long                seed;
  public int                 buckets;
  public long                tsMin;
  public long                tsMax;
  public long                recordVertices;
  public long                identifierVertices;
  public long                edges;
  public String[]            sampleRecordRids = new String[0];
  public Map<String, Long>   timingsMs        = new LinkedHashMap<>();
  public Map<String, Long>   indexEntries     = new LinkedHashMap<>();

  public static Path databasePath(final Path root) {
    return root.resolve("databases").resolve(DATABASE_NAME);
  }

  public static Path manifestPath(final Path root) {
    return root.resolve(FILE_NAME);
  }

  public int phonePool() {
    return ReconDomain.phonePool(records);
  }

  public int emailPool() {
    return ReconDomain.emailPool(records);
  }

  public int handlePool() {
    return ReconDomain.handlePool(records);
  }

  public int imagePool() {
    return ReconDomain.imagePool(records);
  }

  public JSONObject toJson() {
    final JSONObject json = new JSONObject();
    json.put("records", records);
    json.put("seed", seed);
    json.put("buckets", buckets);
    json.put("tsMin", tsMin);
    json.put("tsMax", tsMax);
    json.put("recordVertices", recordVertices);
    json.put("identifierVertices", identifierVertices);
    json.put("edges", edges);
    json.put("sampleRecordRids", new JSONArray(sampleRecordRids));
    final JSONObject timings = new JSONObject();
    for (final Map.Entry<String, Long> e : timingsMs.entrySet())
      timings.put(e.getKey(), e.getValue());
    json.put("timingsMs", timings);
    final JSONObject entries = new JSONObject();
    for (final Map.Entry<String, Long> e : indexEntries.entrySet())
      entries.put(e.getKey(), e.getValue());
    json.put("indexEntries", entries);
    return json;
  }

  public static DatasetManifest fromJson(final JSONObject json) {
    final DatasetManifest m = new DatasetManifest();
    m.records = json.getLong("records");
    m.seed = json.getLong("seed");
    m.buckets = json.getInt("buckets", 1);
    m.tsMin = json.getLong("tsMin");
    m.tsMax = json.getLong("tsMax");
    m.recordVertices = json.getLong("recordVertices", 0L);
    m.identifierVertices = json.getLong("identifierVertices", 0L);
    m.edges = json.getLong("edges", 0L);
    final JSONArray rids = json.getJSONArray("sampleRecordRids");
    m.sampleRecordRids = new String[rids.length()];
    for (int i = 0; i < rids.length(); i++)
      m.sampleRecordRids[i] = rids.getString(i);
    final JSONObject timings = json.getJSONObject("timingsMs");
    for (final String key : timings.keySet())
      m.timingsMs.put(key, timings.getLong(key));
    if (json.has("indexEntries")) {
      final JSONObject entries = json.getJSONObject("indexEntries");
      for (final String key : entries.keySet())
        m.indexEntries.put(key, entries.getLong(key));
    }
    return m;
  }

  public void write(final Path root) throws IOException {
    Files.createDirectories(root);
    Files.writeString(manifestPath(root), toJson().toString(2), StandardCharsets.UTF_8);
  }

  public static DatasetManifest read(final Path root) throws IOException {
    return fromJson(new JSONObject(Files.readString(manifestPath(root), StandardCharsets.UTF_8)));
  }
}
