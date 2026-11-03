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
import java.util.ArrayList;
import java.util.List;

/**
 * Rows of a benchmark run (one per query, leg, format, concurrency) rendered as a Markdown table for the doc and as JSON for
 * machines. Latencies are microseconds, throughput is queries per second.
 */
public final class BenchReport {
  public record Row(String leg, String query, String format, int threads, String phase, long samples, double p50Us, double p95Us,
                    double p99Us, double meanUs, double maxUs, double qps, long rows) {
  }

  private final List<Row> rows = new ArrayList<>();

  public void add(final Row row) {
    rows.add(row);
  }

  public List<Row> rows() {
    return rows;
  }

  public static Row fromStats(final String leg, final String query, final String format, final int threads, final String phase,
      final LatencyStats stats, final double elapsedSeconds, final long rowsReturned) {
    return new Row(leg, query, format, threads, phase, stats.count(), stats.percentileNanos(50) / 1_000.0,
        stats.percentileNanos(95) / 1_000.0, stats.percentileNanos(99) / 1_000.0, stats.meanNanos() / 1_000.0,
        stats.maxNanos() / 1_000.0, elapsedSeconds > 0 ? stats.count() / elapsedSeconds : 0.0, rowsReturned);
  }

  public String toMarkdown() {
    final StringBuilder sb = new StringBuilder();
    sb.append("| leg | query | format | threads | phase | samples | p50 ms | p95 ms | p99 ms | mean ms | max ms | q/s | rows/q |\n");
    sb.append("|---|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    for (final Row r : rows)
      sb.append(String.format("| %s | %s | %s | %d | %s | %d | %s | %s | %s | %s | %s | %s | %d |%n", r.leg, r.query, r.format,
          r.threads, r.phase, r.samples, ms(r.p50Us), ms(r.p95Us), ms(r.p99Us), ms(r.meanUs), ms(r.maxUs), qps(r.qps), r.rows));
    return sb.toString();
  }

  public JSONArray toJson() {
    final JSONArray array = new JSONArray();
    for (final Row r : rows) {
      final JSONObject o = new JSONObject();
      o.put("leg", r.leg);
      o.put("query", r.query);
      o.put("format", r.format);
      o.put("threads", r.threads);
      o.put("phase", r.phase);
      o.put("samples", r.samples);
      o.put("p50Us", r.p50Us);
      o.put("p95Us", r.p95Us);
      o.put("p99Us", r.p99Us);
      o.put("meanUs", r.meanUs);
      o.put("maxUs", r.maxUs);
      o.put("qps", r.qps);
      o.put("rows", r.rows);
      array.put(o);
    }
    return array;
  }

  public void write(final Path directory, final String baseName) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(baseName + ".md"), toMarkdown(), StandardCharsets.UTF_8);
    Files.writeString(directory.resolve(baseName + ".json"), new JSONObject().put("rows", toJson()).toString(2), StandardCharsets.UTF_8);
  }

  static String ms(final double us) {
    final double ms = us / 1_000.0;
    if (ms >= 100)
      return String.format("%.0f", ms);
    if (ms >= 10)
      return String.format("%.1f", ms);
    if (ms >= 1)
      return String.format("%.2f", ms);
    return String.format("%.3f", ms);
  }

  static String qps(final double qps) {
    return qps >= 100 ? String.format("%.0f", qps) : String.format("%.1f", qps);
  }

  /** The machine the numbers came from, for the doc: CPU, cores, memory, JVM. */
  public static String hardwareLine() {
    final Runtime rt = Runtime.getRuntime();
    return System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + ", "
        + rt.availableProcessors() + " cores, JVM " + System.getProperty("java.vendor") + " " + System.getProperty("java.version")
        + ", max heap " + (rt.maxMemory() >> 20) + " MB";
  }
}
