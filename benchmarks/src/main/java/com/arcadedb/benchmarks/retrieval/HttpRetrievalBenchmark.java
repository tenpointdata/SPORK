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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP leg: starts the server in-process over the dataset, then drives the query mix with closed-loop clients (each thread
 * sends the next request as soon as it has parsed the previous answer) at every concurrency level, in both encodings.
 * <p>
 * Reports p50/p95/p99, mean and throughput per (query, format, threads). The first request ever sent for each query, on a
 * server that just started, is reported as the {@code cold} phase: that is the latency a user sees after a restart.
 * <p>
 * Knobs ({@link BenchProps}): {@code root}, {@code threads} (default {@code 1,8,32}), {@code formats} (default
 * {@code json,ndjson}), {@code queries} (comma-separated ids, default all), {@code warmupSeconds} (2), {@code measureSeconds} (4).
 */
public final class HttpRetrievalBenchmark {
  private HttpRetrievalBenchmark() {
  }

  public static void main(final String[] args) throws Exception {
    final Path root = BenchProps.root();
    final int[] threadLevels = BenchProps.intList("threads", "1,8,32");
    final String[] formats = BenchProps.string("formats", HttpQueryClient.JSON + "," + HttpQueryClient.NDJSON).split(",");
    final List<QueryMix.Query> queries = selectedQueries();
    final long warmupNanos = BenchProps.longValue("warmupSeconds", 2) * 1_000_000_000L;
    final long measureNanos = BenchProps.longValue("measureSeconds", 4) * 1_000_000_000L;

    final DatasetManifest manifest = DatasetManifest.read(root);
    final QueryMix.Tables tables = new QueryMix.Tables(manifest);
    final BenchReport report = new BenchReport();

    System.out.println("Hardware: " + BenchReport.hardwareLine());
    try (final BenchServer server = BenchServer.start(root)) {
      final HttpQueryClient client = new HttpQueryClient(server.baseUrl(), DatasetManifest.DATABASE_NAME, BenchServer.ROOT_USER,
          BenchServer.ROOT_PASSWORD);
      System.out.println("Server bound HTTP port " + server.httpPort());

      // cold: the first request of each query after the server started, one encoding, one thread
      final QueryMix.ParamSampler coldSampler = new QueryMix.ParamSampler(tables, manifest.seed, null);
      for (final QueryMix.Query query : queries) {
        final Map<String, Object> params = coldSampler.params(query);
        final long start = System.nanoTime();
        final int rows = client.query(HttpQueryClient.JSON, query.sql, params);
        final LatencyStats one = new LatencyStats(1);
        one.add(System.nanoTime() - start);
        report.add(BenchReport.fromStats("http", query.id, HttpQueryClient.JSON, 1, "cold", one, 0.0, rows));
        System.out.printf("cold  %-14s %8.1f ms  %d rows%n", query.id, one.maxNanos() / 1e6, rows);
      }

      for (final QueryMix.Query query : queries)
        for (final String format : formats)
          for (final int threads : threadLevels) {
            final BenchReport.Row row = runClosedLoop(client, tables, query, format.trim(), threads, warmupNanos, measureNanos);
            report.add(row);
            System.out.printf("warm  %-14s %-6s t=%2d  p50 %8.2f ms  p95 %8.2f ms  p99 %8.2f ms  %8.0f q/s  %d rows%n", query.id, format,
                threads, row.p50Us() / 1e3, row.p95Us() / 1e3, row.p99Us() / 1e3, row.qps(), row.rows());
          }
    }

    report.write(BenchProps.results(), "http-" + manifest.records);
    System.out.println();
    System.out.println(report.toMarkdown());
  }

  static List<QueryMix.Query> selectedQueries() {
    final String filter = BenchProps.string("queries", "");
    final List<QueryMix.Query> queries = new ArrayList<>();
    if (filter.isEmpty()) {
      for (final QueryMix.Query q : QueryMix.Query.values())
        if (q.sqlServed())
          queries.add(q);
    } else
      for (final String id : filter.split(",")) {
        final QueryMix.Query q = QueryMix.Query.byId(id.trim());
        if (!q.sqlServed())
          throw new IllegalArgumentException("Query '" + q.id + "' is answered through a Java API, not SQL: the HTTP leg cannot run it");
        queries.add(q);
      }
    return queries;
  }

  /** {@code threads} closed-loop clients for warm-up then measurement; returns the merged statistics of the measured window. */
  static BenchReport.Row runClosedLoop(final HttpQueryClient client, final QueryMix.Tables tables, final QueryMix.Query query,
      final String format, final int threads, final long warmupNanos, final long measureNanos) throws InterruptedException {
    final LatencyStats[] perThread = new LatencyStats[threads];
    final AtomicLong rowsReturned = new AtomicLong();
    final AtomicLong requests = new AtomicLong();
    final List<Throwable> failures = new ArrayList<>();
    final CountDownLatch done = new CountDownLatch(threads);
    final long seedBase = tables.manifest().seed * 31 + query.ordinal() * 1_000L + format.hashCode();
    final long measureStart = System.nanoTime() + warmupNanos;
    final long end = measureStart + measureNanos;

    for (int t = 0; t < threads; t++) {
      final int threadNo = t;
      perThread[t] = new LatencyStats(4096);
      final Thread thread = new Thread(() -> {
        final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(tables, seedBase + threadNo, null);
        try {
          while (true) {
            final long now = System.nanoTime();
            if (now >= end)
              break;
            final Map<String, Object> params = sampler.params(query);
            final long start = System.nanoTime();
            final int rows = client.query(format, query.sql, params);
            final long elapsed = System.nanoTime() - start;
            if (start >= measureStart) {
              perThread[threadNo].add(elapsed);
              rowsReturned.addAndGet(rows);
              requests.incrementAndGet();
            }
          }
        } catch (final Throwable e) {
          synchronized (failures) {
            failures.add(e);
          }
        } finally {
          done.countDown();
        }
      }, "http-client-" + t);
      thread.setDaemon(true);
      thread.start();
    }
    done.await();
    if (!failures.isEmpty())
      throw new IllegalStateException("HTTP benchmark failed on " + query.id + "/" + format, failures.get(0));

    final LatencyStats merged = new LatencyStats();
    for (final LatencyStats s : perThread)
      merged.merge(s);
    final long count = Math.max(1L, requests.get());
    return BenchReport.fromStats("http", query.id, format, threads, "warm", merged, measureNanos / 1e9, rowsReturned.get() / count);
  }
}
