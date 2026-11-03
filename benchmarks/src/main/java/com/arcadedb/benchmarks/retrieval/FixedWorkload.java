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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The fixed workload: every query runs the SAME parameter sets on every engine, drawn from one seed, and each set keeps the
 * fastest of several timed runs. It is the comparison the JMH legs cannot make across engines: their samplers draw new
 * parameters per invocation, so a slow query that completes a dozen times in a window reports the median of whichever dozen
 * it drew. Here every engine answers the same 50 questions, and a second engine's row totals must equal the first's.
 * <p>
 * Order: a timed warm-up that cycles every query with OTHER parameters (so the JIT is warm and the measured sets are not
 * already cached as results), then per query one untimed pass that counts the rows, then {@code passes} timed passes. A timed
 * pass that answers a different row count than the untimed one fails the run: an engine whose answers drift cannot be
 * compared.
 * <p>
 * Pure JDK on purpose: the OrientDB module compiles it in, and nothing ArcadeDB may be on that classpath.
 */
public final class FixedWorkload {
  /** One engine's side of the workload. */
  public interface Engine {
    /** {@code count} parameter sets for {@code queryId}, a pure function of {@code seed}. */
    List<Map<String, Object>> draw(String queryId, long seed, int count);

    /** Runs one query, consumes every row and returns how many there were. */
    int execute(String queryId, Map<String, Object> params);
  }

  /**
   * @param seed           seed of the measured parameter sets ({@code fixedSeed}, default 12345)
   * @param draws          parameter sets per query ({@code fixedDraws}, default 50)
   * @param drawsPerQuery  overrides per query id ({@code fixedDrawsPerQuery}, e.g. {@code vectorKnn20:10})
   * @param passes         timed passes ({@code fixedPasses}, default 3)
   * @param warmupMillis   warm-up before the first measured query ({@code fixedWarmupSeconds}, default 20)
   */
  public record Settings(long seed, int draws, Map<String, Integer> drawsPerQuery, int passes, long warmupMillis) {
    public int drawsFor(final String queryId) {
      return drawsPerQuery.getOrDefault(queryId, draws);
    }

    public static Settings fromProperties() {
      final Map<String, Integer> perQuery = new HashMap<>();
      for (final String entry : BenchProps.string("fixedDrawsPerQuery", "").split(",")) {
        final String trimmed = entry.trim();
        if (trimmed.isEmpty())
          continue;
        final int colon = trimmed.indexOf(':');
        if (colon < 1)
          throw new IllegalArgumentException("fixedDrawsPerQuery entries are <queryId>:<draws>, got '" + trimmed + "'");
        perQuery.put(trimmed.substring(0, colon).trim(), Integer.parseInt(trimmed.substring(colon + 1).trim()));
      }
      return new Settings(BenchProps.longValue("fixedSeed", 12_345L), BenchProps.intValue("fixedDraws", 50), perQuery,
          BenchProps.intValue("fixedPasses", 3), BenchProps.intValue("fixedWarmupSeconds", 20) * 1_000L);
    }
  }

  /** One query's result: the distribution of the per-set best times, and the rows one pass answered. */
  public record Result(String query, int draws, long rows, double medianMs, double meanMs, double p90Ms, double maxMs) {
  }

  private final Engine       engine;
  private final Settings     settings;
  private final LongSupplier nanoClock;

  public FixedWorkload(final Engine engine, final Settings settings) {
    this(engine, settings, System::nanoTime);
  }

  FixedWorkload(final Engine engine, final Settings settings, final LongSupplier nanoClock) {
    this.engine = engine;
    this.settings = settings;
    this.nanoClock = nanoClock;
  }

  public List<Result> run(final List<String> queryIds) {
    warmUp(queryIds);
    final List<Result> results = new ArrayList<>(queryIds.size());
    for (final String queryId : queryIds)
      results.add(measure(queryId));
    return results;
  }

  /** Cycles every query with parameters from other seeds until the warm-up time is spent; at least one round when it is not 0. */
  private void warmUp(final List<String> queryIds) {
    if (settings.warmupMillis() <= 0)
      return;
    final long deadline = nanoClock.getAsLong() + settings.warmupMillis() * 1_000_000L;
    long round = 0;
    do {
      round++;
      for (final String queryId : queryIds)
        for (final Map<String, Object> params : engine.draw(queryId, settings.seed() + round * 7_919L, 1))
          engine.execute(queryId, params);
    } while (nanoClock.getAsLong() < deadline);
  }

  private Result measure(final String queryId) {
    final int draws = settings.drawsFor(queryId);
    final List<Map<String, Object>> params = engine.draw(queryId, settings.seed(), draws);
    final int[] rows = new int[draws];
    long totalRows = 0;
    for (int i = 0; i < draws; i++) {
      rows[i] = engine.execute(queryId, params.get(i));
      totalRows += rows[i];
    }

    final long[] best = new long[draws];
    Arrays.fill(best, Long.MAX_VALUE);
    for (int pass = 0; pass < settings.passes(); pass++)
      for (int i = 0; i < draws; i++) {
        final long start = nanoClock.getAsLong();
        final int answered = engine.execute(queryId, params.get(i));
        final long elapsed = nanoClock.getAsLong() - start;
        if (answered != rows[i])
          throw new IllegalStateException("Query '" + queryId + "' answered " + answered + " rows on timed pass " + (pass + 1) + " for parameter set " + i
              + ", " + rows[i] + " on the untimed pass: an engine whose answers change between runs cannot be compared");
        if (elapsed < best[i])
          best[i] = elapsed;
      }

    final LatencyStats stats = new LatencyStats(draws);
    for (final long b : best)
      stats.add(b);
    return new Result(queryId, draws, totalRows, stats.percentileNanos(50) / 1e6, stats.meanNanos() / 1e6, stats.percentileNanos(90) / 1e6,
        stats.maxNanos() / 1e6);
  }

  public static String toMarkdown(final String engineLabel, final List<Result> results) {
    final StringBuilder sb = new StringBuilder(
        "| engine | query | draws | rows | median ms | mean ms | p90 ms | max ms |\n|---|---|---:|---:|---:|---:|---:|---:|\n");
    for (final Result r : results)
      sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.3f | %.3f | %.3f | %.3f |%n", engineLabel, r.query(), r.draws(), r.rows(),
          r.medianMs(), r.meanMs(), r.p90Ms(), r.maxMs()));
    return sb.toString();
  }

  public static String toCsv(final String engineLabel, final List<Result> results) {
    final StringBuilder sb = new StringBuilder("engine,query,draws,rows,median_ms,mean_ms,p90_ms,max_ms\n");
    for (final Result r : results)
      sb.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f%n", engineLabel, r.query(), r.draws(), r.rows(), r.medianMs(),
          r.meanMs(), r.p90Ms(), r.maxMs()));
    return sb.toString();
  }

  /** Writes {@code <name>.md} and {@code <name>.csv} under {@code dir} and returns the Markdown. */
  public static String write(final Path dir, final String name, final String engineLabel, final List<Result> results) throws IOException {
    Files.createDirectories(dir);
    final String md = toMarkdown(engineLabel, results);
    Files.writeString(dir.resolve(name + ".md"), md, StandardCharsets.UTF_8);
    Files.writeString(dir.resolve(name + ".csv"), toCsv(engineLabel, results), StandardCharsets.UTF_8);
    return md;
  }
}
