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

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The JVM-wide admission of vector graph builds: {@code VECTOR_INDEX_MAX_CONCURRENT_REBUILDS} permits, plus a heap ledger
 * that makes more than one of them safe to hand out.
 * <p>
 * The permits alone bound how many builds run, which is not the question that kills a node: concurrent builds exhaust the
 * heap (issue #3868), and each build's own admission check ({@code admitOnlineRebuild}, {@code reclaimHeapForRebuild})
 * reads a heap figure measured after the last collection, which cannot see a build admitted a moment earlier and not yet
 * collected. Two builds would each be admitted on bytes only one of them gets. So every build reserves its estimated peak
 * ({@link VectorHeapBudget#estimateRebuildHeapBytes}) for its whole duration, and one that would push the sum of the
 * reservations past the budget waits for a running build to finish.
 * <p>
 * <b>The first build is never held back by the heap.</b> With nothing running it is admitted whatever its estimate, which
 * is exactly what the single-permit semaphore did, and what its callers rely on: a build a search needs has no later trigger
 * to retry it, and its own reclaim-or-warn path ({@code reclaimHeapForRebuild}) already deals with a lone build that does
 * not fit. With one permit, the default, this class therefore behaves as the semaphore it replaces.
 * <p>
 * Not fair: a waiting large build can be overtaken by smaller ones that fit beside a running one. Its wait is bounded by
 * the caller's timeout ({@code VECTOR_INDEX_REBUILD_PERMIT_TIMEOUT_MS}), past which the callers already proceed or skip a
 * cycle as they did when the semaphore timed out.
 */
final class VectorRebuildPermits {
  /** A budget meaning "no heap gate" ({@code VECTOR_INDEX_REBUILD_MAX_HEAP_PERCENT <= 0}): permits alone decide. */
  static final long HEAP_GATE_DISABLED = -1L;

  /**
   * Longest single park of a timed acquire before it reads the budget again. The budget moves without anybody releasing
   * anything - a collection frees heap - and only a release notifies, so a waiter rechecks on its own this often.
   */
  private static final long BUDGET_RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final int  maxPermits;
  private       int  running;
  private       long reservedBytes;

  VectorRebuildPermits(final int maxPermits) {
    this.maxPermits = Math.max(1, maxPermits);
  }

  /**
   * Takes a permit for a build expected to hold {@code bytes} at peak, if one can be had right now.
   *
   * @param budgetBytes the heap the running builds may hold together, or {@link #HEAP_GATE_DISABLED}
   */
  synchronized boolean tryAcquire(final long bytes, final long budgetBytes) {
    if (!admits(bytes, budgetBytes))
      return false;
    take(bytes);
    return true;
  }

  /**
   * {@link #tryAcquire(long, long)}, waiting up to {@code timeout} for a permit and room in the budget, which is read again
   * on every wake-up.
   */
  boolean tryAcquire(final long bytes, final LongSupplier budgetBytes, final long timeout, final TimeUnit unit)
      throws InterruptedException {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    synchronized (this) {
      while (!admits(bytes, running == 0 ? HEAP_GATE_DISABLED : budgetBytes.getAsLong())) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L)
          return false;
        TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, BUDGET_RECHECK_NANOS));
      }
      take(bytes);
      return true;
    }
  }

  /** Returns the permit and the reservation of a build admitted with {@code bytes}. */
  synchronized void release(final long bytes) {
    running--;
    reservedBytes -= Math.max(0L, bytes);
    notifyAll();
  }

  synchronized int availablePermits() {
    return maxPermits - running;
  }

  /** Heap the running builds have reserved, together. */
  synchronized long reservedBytes() {
    return reservedBytes;
  }

  /** Test hook: waits until no build runs, then holds every permit. Pair with {@link #releaseAll()}. */
  synchronized void acquireAllUninterruptibly() {
    boolean interrupted = false;
    while (running > 0) {
      try {
        wait();
      } catch (final InterruptedException e) {
        interrupted = true;
      }
    }
    running = maxPermits;
    if (interrupted)
      Thread.currentThread().interrupt();
  }

  /** Test hook: returns the permits {@link #acquireAllUninterruptibly()} took. */
  synchronized void releaseAll() {
    running -= maxPermits;
    notifyAll();
  }

  int maxPermits() {
    return maxPermits;
  }

  private boolean admits(final long bytes, final long budgetBytes) {
    if (running >= maxPermits)
      return false;
    if (running == 0 || budgetBytes == HEAP_GATE_DISABLED)
      return true;
    return reservedBytes + Math.max(0L, bytes) <= budgetBytes;
  }

  private void take(final long bytes) {
    running++;
    reservedBytes += Math.max(0L, bytes);
  }
}
