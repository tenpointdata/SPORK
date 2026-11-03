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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fixed workload is what makes two engines comparable: the same parameter sets, the fastest of several runs per set. These
 * tests drive it with a fake engine on a fake clock, so every latency is exact.
 */
class FixedWorkloadTest {
  private static final long MS = 1_000_000L;

  /** A fake engine: each parameter set is {"i": n}; the latency of a call is scripted per query and draw, on a fake clock. */
  private static final class FakeEngine implements FixedWorkload.Engine {
    final long[]                         clock     = { 0L };
    final Map<String, List<Long>>        seeds     = new HashMap<>();
    final List<String>                   calls     = new ArrayList<>();
    final Map<String, long[][]>          latencies = new HashMap<>(); // query -> [pass][draw] nanos, pass 0 = the untimed one
    int                                  callIndexWithinQuery;
    boolean                              changeRowsOnSecondTimedPass;
    int                                  executions;

    @Override
    public List<Map<String, Object>> draw(final String queryId, final long seed, final int count) {
      seeds.computeIfAbsent(queryId, k -> new ArrayList<>()).add(seed);
      final List<Map<String, Object>> params = new ArrayList<>(count);
      for (int i = 0; i < count; i++)
        params.add(Map.of("i", (int) (seed % 1000) * 1000 + i));
      return params;
    }

    @Override
    public int execute(final String queryId, final Map<String, Object> params) {
      executions++;
      calls.add(queryId + ":" + params.get("i"));
      final int i = (Integer) params.get("i") % 1000;
      final long[][] script = latencies.get(queryId);
      final long seen = calls.stream().filter(c -> c.equals(queryId + ":" + params.get("i"))).count();
      final int pass = (int) seen - 1;
      if (script != null && pass < script.length)
        clock[0] += script[pass][i];
      else
        clock[0] += MS;
      return changeRowsOnSecondTimedPass && pass == 2 ? 99 : 10 + i;
    }
  }

  private static FixedWorkload.Settings settings(final int draws, final int passes, final long warmupMillis) {
    return new FixedWorkload.Settings(12_345L, draws, Map.of(), passes, warmupMillis);
  }

  @Test
  void keepsTheFastestRunOfEachParameterSet() {
    final FakeEngine engine = new FakeEngine();
    // pass 0 is the untimed one; three timed passes; the best per draw is 1, 2, 3, 4 ms
    engine.latencies.put("q", new long[][] {
        { 50 * MS, 50 * MS, 50 * MS, 50 * MS },
        { 9 * MS, 2 * MS, 7 * MS, 4 * MS },
        { 1 * MS, 8 * MS, 3 * MS, 9 * MS },
        { 5 * MS, 6 * MS, 9 * MS, 6 * MS } });

    final List<FixedWorkload.Result> results = new FixedWorkload(engine, settings(4, 3, 0), () -> engine.clock[0]).run(List.of("q"));

    assertThat(results).hasSize(1);
    final FixedWorkload.Result r = results.get(0);
    assertThat(r.query()).isEqualTo("q");
    assertThat(r.draws()).isEqualTo(4);
    assertThat(r.medianMs()).isEqualTo(2.0); // nearest rank of [1, 2, 3, 4]
    assertThat(r.meanMs()).isEqualTo(2.5);
    assertThat(r.p90Ms()).isEqualTo(4.0);
    assertThat(r.maxMs()).isEqualTo(4.0);
    assertThat(r.rows()).isEqualTo(10 + 11 + 12 + 13); // rows of one pass, not of all four
  }

  @Test
  void everyPassRunsTheSameParameterSetsFromTheSameSeed() {
    final FakeEngine engine = new FakeEngine();
    new FixedWorkload(engine, settings(3, 2, 0), () -> engine.clock[0]).run(List.of("a", "b"));

    // one draw per query, from the configured seed, then 1 untimed + 2 timed passes over the same three sets
    assertThat(engine.seeds.get("a")).containsExactly(12_345L);
    assertThat(engine.seeds.get("b")).containsExactly(12_345L);
    assertThat(engine.calls.stream().filter(c -> c.startsWith("a:")).toList()).containsExactly("a:345000", "a:345001", "a:345002", "a:345000",
        "a:345001", "a:345002", "a:345000", "a:345001", "a:345002");
  }

  @Test
  void perQueryDrawCountOverridesTheDefault() {
    final FakeEngine engine = new FakeEngine();
    final FixedWorkload.Settings s = new FixedWorkload.Settings(12_345L, 5, Map.of("slow", 2), 1, 0);
    final List<FixedWorkload.Result> results = new FixedWorkload(engine, s, () -> engine.clock[0]).run(List.of("fast", "slow"));
    assertThat(results.get(0).draws()).isEqualTo(5);
    assertThat(results.get(1).draws()).isEqualTo(2);
  }

  @Test
  void warmUpUsesOtherParametersAndCoversEveryQuery() {
    final FakeEngine engine = new FakeEngine();
    // every call costs 1 ms on the fake clock: a 10 ms warm-up runs several rounds of both queries
    new FixedWorkload(engine, settings(2, 1, 10), () -> engine.clock[0]).run(List.of("a", "b"));

    assertThat(engine.seeds.get("a")).hasSizeGreaterThan(1);
    assertThat(engine.seeds.get("a").subList(0, engine.seeds.get("a").size() - 1)).doesNotContain(12_345L);
    assertThat(engine.seeds.get("b").subList(0, engine.seeds.get("b").size() - 1)).doesNotContain(12_345L);
    assertThat(engine.seeds.get("a").get(engine.seeds.get("a").size() - 1)).isEqualTo(12_345L);
  }

  @Test
  void anEngineThatAnswersDifferentlyOnARepeatIsRefused() {
    final FakeEngine engine = new FakeEngine();
    engine.changeRowsOnSecondTimedPass = true;
    assertThatThrownBy(() -> new FixedWorkload(engine, settings(2, 3, 0), () -> engine.clock[0]).run(List.of("q")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("q");
  }

  @Test
  void reportTablesNameTheEngineAndEveryQuery() {
    final List<FixedWorkload.Result> results = List.of(new FixedWorkload.Result("identLookup", 50, 50, 0.0042, 0.005, 0.007, 0.02),
        new FixedWorkload.Result("structured", 50, 3455, 88.16, 103.8, 224.4, 410.0));
    final String md = FixedWorkload.toMarkdown("orientdb-3.2.24", results);
    assertThat(md).contains("| orientdb-3.2.24 | identLookup | 50 | 50 | 0.004 |").contains("| orientdb-3.2.24 | structured | 50 | 3455 | 88.160 |");
    final String csv = FixedWorkload.toCsv("arcadedb", results);
    assertThat(csv.lines().toList()).hasSize(3);
    assertThat(csv).startsWith("engine,query,draws,rows,median_ms,mean_ms,p90_ms,max_ms").contains("arcadedb,structured,50,3455,88.160,103.800,224.400,410.000");
  }

  @Test
  void settingsComeFromTheSharedBenchProperties() {
    System.setProperty(BenchProps.PREFIX + "fixedDraws", "7");
    System.setProperty(BenchProps.PREFIX + "fixedDrawsPerQuery", "vectorKnn20:3, structured:4");
    try {
      final FixedWorkload.Settings s = FixedWorkload.Settings.fromProperties();
      assertThat(s.draws()).isEqualTo(7);
      assertThat(s.drawsFor("vectorKnn20")).isEqualTo(3);
      assertThat(s.drawsFor("structured")).isEqualTo(4);
      assertThat(s.drawsFor("identLookup")).isEqualTo(7);
      assertThat(s.seed()).isEqualTo(12_345L);
      assertThat(s.passes()).isEqualTo(3);
    } finally {
      System.clearProperty(BenchProps.PREFIX + "fixedDraws");
      System.clearProperty(BenchProps.PREFIX + "fixedDrawsPerQuery");
    }
  }
}
