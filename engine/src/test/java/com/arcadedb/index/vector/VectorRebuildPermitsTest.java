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
package com.arcadedb.index.vector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VectorRebuildPermits}: with one permit it is the old JVM-wide semaphore; with more, a rebuild past the first is
 * admitted only while the heap reservations of every rebuild running, its own included, fit the budget.
 */
class VectorRebuildPermitsTest {
  private static final long MB = 1024L * 1024;

  @Test
  void onePermitBehavesAsTheSemaphoreItReplaces() {
    final VectorRebuildPermits permits = new VectorRebuildPermits(1);
    assertThat(permits.tryAcquire(10 * MB, 1_000 * MB)).isTrue();
    assertThat(permits.availablePermits()).isZero();
    assertThat(permits.tryAcquire(1, 1_000 * MB)).as("one permit, one build").isFalse();
    permits.release(10 * MB);
    assertThat(permits.availablePermits()).isEqualTo(1);
    assertThat(permits.reservedBytes()).isZero();
  }

  @Test
  void theFirstBuildIsNeverHeldBackByTheHeapBudget() {
    final VectorRebuildPermits permits = new VectorRebuildPermits(4);
    assertThat(permits.tryAcquire(5_000 * MB, 100 * MB))
        .as("a lone build over the budget runs, as it did under the single permit: refusing it would turn slower into never")
        .isTrue();
    assertThat(permits.tryAcquire(1 * MB, 100 * MB)).as("but nothing joins it while it is over the budget").isFalse();
    permits.release(5_000 * MB);
  }

  @Test
  void concurrentBuildsAreAdmittedOnlyWhileTheirReservationsFit() {
    final VectorRebuildPermits permits = new VectorRebuildPermits(4);
    final long budget = 1_000 * MB;
    assertThat(permits.tryAcquire(400 * MB, budget)).isTrue();
    assertThat(permits.tryAcquire(400 * MB, budget)).isTrue();
    assertThat(permits.reservedBytes()).isEqualTo(800 * MB);
    assertThat(permits.tryAcquire(300 * MB, budget)).as("800 + 300 MB does not fit 1000 MB").isFalse();
    assertThat(permits.tryAcquire(200 * MB, budget)).as("800 + 200 MB does").isTrue();
    assertThat(permits.tryAcquire(0, budget)).as("the fourth permit, for a build of nothing").isTrue();
    assertThat(permits.tryAcquire(0, budget)).as("no fifth permit, whatever the heap says").isFalse();

    permits.release(400 * MB);
    assertThat(permits.tryAcquire(300 * MB, budget)).as("600 + 300 MB fits again once a build ended").isTrue();
  }

  @Test
  void aDisabledHeapGateAdmitsByPermitsAlone() {
    final VectorRebuildPermits permits = new VectorRebuildPermits(2);
    assertThat(permits.tryAcquire(5_000 * MB, VectorRebuildPermits.HEAP_GATE_DISABLED)).isTrue();
    assertThat(permits.tryAcquire(5_000 * MB, VectorRebuildPermits.HEAP_GATE_DISABLED)).isTrue();
    assertThat(permits.tryAcquire(1, VectorRebuildPermits.HEAP_GATE_DISABLED)).isFalse();
  }

  @Test
  @Timeout(30)
  void aWaitingBuildIsWokenWhenARunningOneReleasesItsReservation() throws Exception {
    final VectorRebuildPermits permits = new VectorRebuildPermits(2);
    final long budget = 1_000 * MB;
    assertThat(permits.tryAcquire(900 * MB, budget)).isTrue();

    final CountDownLatch started = new CountDownLatch(1);
    final AtomicBoolean admitted = new AtomicBoolean();
    final Thread waiter = new Thread(() -> {
      started.countDown();
      try {
        admitted.set(permits.tryAcquire(500 * MB, () -> budget, 20, TimeUnit.SECONDS));
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    waiter.start();
    started.await();
    // A short wait expected to time out needs no stall-aware bound: the waiter cannot be admitted while 900 MB is held
    waiter.join(200);
    assertThat(admitted.get()).isFalse();

    permits.release(900 * MB);
    waiter.join();
    assertThat(admitted.get()).isTrue();
    assertThat(permits.reservedBytes()).isEqualTo(500 * MB);
  }

  @Test
  void aTimedAcquireGivesUpWhenNothingIsReleased() throws Exception {
    final VectorRebuildPermits permits = new VectorRebuildPermits(1);
    assertThat(permits.tryAcquire(1, 1_000 * MB)).isTrue();
    assertThat(permits.tryAcquire(1, () -> 1_000 * MB, 50, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  @Timeout(30)
  void holdingEveryPermitBlocksEveryBuildUntilReleased() throws Exception {
    final VectorRebuildPermits permits = new VectorRebuildPermits(3);
    permits.acquireAllUninterruptibly();
    assertThat(permits.availablePermits()).isZero();
    assertThat(permits.tryAcquire(0, VectorRebuildPermits.HEAP_GATE_DISABLED)).isFalse();
    permits.releaseAll();
    assertThat(permits.availablePermits()).isEqualTo(3);
    assertThat(permits.tryAcquire(0, VectorRebuildPermits.HEAP_GATE_DISABLED)).isTrue();
  }
}
