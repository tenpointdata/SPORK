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
package com.arcadedb.benchmarks.orientdb;

import com.arcadedb.benchmarks.retrieval.BenchProps;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.db.OrientDBConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Where the OrientDB copy of the synthetic Recon dataset lives and what it is: the embedded OrientDB context, and the
 * manifest written next to the database ({@code orient-manifest.properties}) with the same facts the ArcadeDB
 * {@code manifest.json} carries (scale, seed, time range, sample record RIDs, timings). Same {@code records} and {@code seed}
 * as an ArcadeDB dataset means the same records, edges and query parameters: both generators draw from
 * {@link com.arcadedb.benchmarks.retrieval.ReconDomain} in the same order.
 */
public final class OrientDataset {
  public static final String DATABASE_NAME = "recon";
  public static final String USER          = "admin";
  public static final String PASSWORD      = "admin";
  static final String        MANIFEST      = "orient-manifest.properties";

  public long                records;
  public long                seed;
  public long                tsMin;
  public long                tsMax;
  public long                identifierVertices;
  public long                edges;
  public boolean             lightweightEdges;
  public String[]            sampleRecordRids = new String[0];
  public Map<String, Long>   timingsMs        = new LinkedHashMap<>();

  /** Dataset root ({@code orientRoot}), default {@code target/retrieval-bench-orientdb/<records>} under the working directory. */
  public static Path root() {
    final String explicit = BenchProps.string("orientRoot", null);
    return explicit != null ? Paths.get(explicit) : Paths.get("target", "retrieval-bench-orientdb", Long.toString(BenchProps.records()));
  }

  /**
   * The embedded OrientDB context over {@code <root>/databases}. The disk cache is set explicitly ({@code orientDiskCacheMB},
   * default 4096) so it matches the page cache the ArcadeDB leg is given rather than whatever OrientDB derives from the
   * machine's free memory.
   */
  public static OrientDB open(final Path root) {
    OGlobalConfiguration.DISK_CACHE_SIZE.setValue(BenchProps.intValue("orientDiskCacheMB", 4096));
    return new OrientDB("embedded:" + root.resolve("databases").toAbsolutePath(), OrientDBConfig.defaultConfig());
  }

  public static ODatabaseSession session(final OrientDB orientDB) {
    return orientDB.open(DATABASE_NAME, USER, PASSWORD);
  }

  public void write(final Path root) throws IOException {
    final Properties p = new Properties();
    p.setProperty("records", Long.toString(records));
    p.setProperty("seed", Long.toString(seed));
    p.setProperty("tsMin", Long.toString(tsMin));
    p.setProperty("tsMax", Long.toString(tsMax));
    p.setProperty("identifierVertices", Long.toString(identifierVertices));
    p.setProperty("edges", Long.toString(edges));
    p.setProperty("lightweightEdges", Boolean.toString(lightweightEdges));
    p.setProperty("sampleRecordRids", String.join(",", sampleRecordRids));
    for (final Map.Entry<String, Long> e : timingsMs.entrySet())
      p.setProperty("timing." + e.getKey(), Long.toString(e.getValue()));
    Files.createDirectories(root);
    try (final Writer w = Files.newBufferedWriter(root.resolve(MANIFEST), StandardCharsets.UTF_8)) {
      p.store(w, "OrientDB copy of the synthetic Recon dataset");
    }
  }

  public static OrientDataset read(final Path root) throws IOException {
    final Properties p = new Properties();
    try (final Reader r = Files.newBufferedReader(root.resolve(MANIFEST), StandardCharsets.UTF_8)) {
      p.load(r);
    }
    final OrientDataset d = new OrientDataset();
    d.records = Long.parseLong(p.getProperty("records"));
    d.seed = Long.parseLong(p.getProperty("seed"));
    d.tsMin = Long.parseLong(p.getProperty("tsMin"));
    d.tsMax = Long.parseLong(p.getProperty("tsMax"));
    d.identifierVertices = Long.parseLong(p.getProperty("identifierVertices", "0"));
    d.edges = Long.parseLong(p.getProperty("edges", "0"));
    d.lightweightEdges = Boolean.parseBoolean(p.getProperty("lightweightEdges", "true"));
    final String rids = p.getProperty("sampleRecordRids", "");
    d.sampleRecordRids = rids.isEmpty() ? new String[0] : rids.split(",");
    for (final String key : p.stringPropertyNames())
      if (key.startsWith("timing."))
        d.timingsMs.put(key.substring("timing.".length()), Long.parseLong(p.getProperty(key)));
    return d;
  }

  public static boolean exists(final Path root, final long records, final long seed) {
    if (!Files.isRegularFile(root.resolve(MANIFEST)))
      return false;
    try {
      final OrientDataset d = read(root);
      return d.records == records && d.seed == seed;
    } catch (final IOException | RuntimeException e) {
      return false;
    }
  }
}
