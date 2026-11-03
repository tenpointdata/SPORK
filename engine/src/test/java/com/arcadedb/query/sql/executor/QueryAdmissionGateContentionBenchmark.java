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
package com.arcadedb.query.sql.executor;

import com.arcadedb.GlobalConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admit-and-close round trips through {@link QueryAdmissionGate} from 32 threads with the gate never full: the SPORK
 * retrieval-speed workstream S8 (docs/spork/02-retrieval-speed.md, 4.2). Every remote request takes the gate's one JVM-wide
 * {@code ReentrantLock} twice (admit, release); the fast path takes a slot with a CAS when nobody waits. The number is the
 * round trips per second with every thread admitted at once, which is the shape of a read node under load.
 * <p>
 * Properties: {@code threads} (default 32), {@code iterations} (default 200,000 per thread).
 */
@Tag("benchmark")
class QueryAdmissionGateContentionBenchmark {
  private static final int THREADS    = Integer.parseInt(System.getProperty("threads", "32"));
  private static final int ITERATIONS = Integer.parseInt(System.getProperty("iterations", "200000"));

  @AfterEach
  void tearDown() {
    GlobalConfiguration.QUERY_MAX_CONCURRENT.reset();
  }

  @Test
  void admitAndCloseFromManyThreadsWithFreeSlots() throws Exception {
    GlobalConfiguration.QUERY_MAX_CONCURRENT.setValue(THREADS * 2);
    final QueryAdmissionGate gate = new QueryAdmissionGate();

    // warm
    runRoundTrips(gate, THREADS, ITERATIONS / 10);
    final long single = runRoundTrips(gate, 1, ITERATIONS);
    final long many = runRoundTrips(gate, THREADS, ITERATIONS);

    assertThat(gate.getRunning()).isZero();
    assertThat(gate.getAdmitted()).isEqualTo((long) THREADS * ITERATIONS / 10 + ITERATIONS + (long) THREADS * ITERATIONS);

    // System.out, not LogManager: a benchmark's numbers ARE its output, and LogManager INFO does not surface in a surefire run
    System.out.printf("admission gate: 1 thread %,.0f round trips/s (%.0f ns each)  %d threads %,.0f round trips/s (%.0f ns each per thread)%n",
        ITERATIONS * 1_000_000_000d / single, (double) single / ITERATIONS, THREADS,
        (double) THREADS * ITERATIONS * 1_000_000_000d / many, (double) many / ITERATIONS);
  }

  private static long runRoundTrips(final QueryAdmissionGate gate, final int threads, final int iterations) throws Exception {
    final CountDownLatch ready = new CountDownLatch(threads);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final List<Thread> workers = new ArrayList<>(threads);
    for (int t = 0; t < threads; t++) {
      final Thread worker = new Thread(() -> {
        try {
          ready.countDown();
          go.await();
          for (int i = 0; i < iterations; i++)
            gate.admit().close();
        } catch (final Throwable e) {
          failure.compareAndSet(null, e);
        } finally {
          done.countDown();
        }
      }, "gate-" + t);
      workers.add(worker);
      worker.start();
    }
    ready.await();
    final long begin = System.nanoTime();
    go.countDown();
    done.await();
    final long elapsed = System.nanoTime() - begin;
    for (final Thread worker : workers)
      worker.join();
    if (failure.get() != null)
      throw new AssertionError("a gate thread failed", failure.get());
    return elapsed;
  }
}
