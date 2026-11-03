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

import com.arcadedb.database.ProtocolContext;
import com.arcadedb.utility.DedicatedThreadPool;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The {@code spork-hydrate} pool (01-architecture 2.5): ArcadeDB loads pages synchronously in the calling thread, so a cold
 * 2,000-id hydrate is about 8,000 page reads that only parallelism hides. None of the engine's pools fits: the query pool is for
 * query-time compute and is shared with every query, and a hydrate blocks on I/O for most of its life.
 * <p>
 * Bounded queue and caller-runs, per the engine-concurrency rules: a saturated pool degrades a hydrate to the request thread
 * instead of refusing it. The request thread waits through {@code GraphAlgorithms.awaitFutures} with this pool, which reclaims a
 * chunk no worker has started, so a hydrate never parks on work nobody runs. One pool per plugin instance, closed with it; its
 * gauges carry the same names as {@code PoolMetrics} with {@code pool=spork_hydrate}.
 * <p>
 * A chunk is a client's request running on a thread the plugin owns, so it runs under the request's
 * {@link ProtocolContext} ({@link #runTagged}): an untagged worker would read {@code INTERNAL}, which the HA snapshot gate
 * exempts and the query metrics and trace spans attribute to the engine (issue #10094).
 */
final class HydratePool extends DedicatedThreadPool {
  static final String POOL_TAG = "spork_hydrate";

  private final List<Meter> meters = new ArrayList<>();

  HydratePool(final int threads, final int queue) {
    super("SPORK-Hydrate-", threads, queue, SaturationPolicy.CALLER_RUNS, DedicatedThreadPool::plainWorker, "SPORK hydrate pool",
        "the chunk is read on the request thread, which costs parallelism but never the request");
    bindMetrics(Metrics.globalRegistry);
  }

  private void bindMetrics(final MeterRegistry registry) {
    final Tags tags = Tags.of("pool", POOL_TAG);
    gauge(registry, tags, "arcadedb.executor.pool.size", s -> s.poolSize());
    gauge(registry, tags, "arcadedb.executor.pool.active", s -> s.activeThreads());
    gauge(registry, tags, "arcadedb.executor.queue.depth", s -> s.queueDepth());
    gauge(registry, tags, "arcadedb.executor.queue.capacity_remaining", s -> s.queueCapacityRemaining());
    gauge(registry, tags, "arcadedb.executor.tasks.completed", s -> s.completedTasks());
    gauge(registry, tags, "arcadedb.executor.tasks.caller_run_fallbacks", s -> s.callerRunFallbacks());
    gauge(registry, tags, "arcadedb.executor.tasks.reclaimed", s -> s.reclaimedTasks());
  }

  private void gauge(final MeterRegistry registry, final Tags tags, final String name, final ToDoubleFunction<PoolStats> value) {
    meters.add(Gauge.builder(name, this, pool -> value.applyAsDouble(pool.getPoolStats())).tags(tags)
        .description("SPORK hydrate pool").register(registry));
  }

  /**
   * Runs {@code chunk} under {@code protocol} and puts back whatever tag the thread had: none on a pooled worker, the
   * request's own when the chunk was reclaimed or caller-run on the request thread.
   */
  static void runTagged(final String protocol, final Runnable chunk) {
    final String enclosing = ProtocolContext.get();
    ProtocolContext.set(protocol);
    try {
      chunk.run();
    } finally {
      if (ProtocolContext.INTERNAL.equals(enclosing))
        ProtocolContext.clear();
      else
        ProtocolContext.set(enclosing);
    }
  }

  @Override
  public void close() {
    for (final Meter meter : meters)
      Metrics.globalRegistry.remove(meter);
    meters.clear();
    super.close();
  }
}
