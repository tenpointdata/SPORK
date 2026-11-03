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
package com.arcadedb.engine;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.utility.DedicatedThreadPool;

/**
 * JVM-wide dedicated executor for the page reads {@link PageManager#prefetch(java.util.Collection)} issues ahead of a
 * caller that is about to read those pages: the misses of a batched record hydration
 * ({@code LocalDatabase.lookupByRIDs}), the pages of an index range being served in physical order, the next chunk of
 * a multi-page record. Lazy singleton; daemon threads, so the JVM exits cleanly even if nothing ever shuts it down.
 * <p>
 * <b>Why a pool of its own.</b> A prefetch task is one blocking {@code pread} of one page, 100 us on a cold NVMe
 * device and a few us from the OS page cache, with no computation around it. The {@code QueryEngineManager} pool is
 * sized to the cores for compute and would turn every cold hydrate into a convoy on its threads; the
 * {@code ParallelScanProducerPool} runs whole bucket scans that hold a thread for seconds. What a device wants from a
 * hydrate is QUEUE DEPTH, 32 reads in flight at once, which with blocking I/O is 32 threads parked in the kernel: this
 * pool therefore defaults to twice the cores, never fewer than 8, which an operator sizes with
 * {@code arcadedb.pagePrefetchPoolThreads} to the device's queue depth.
 * <p>
 * <b>Bounded queue, caller-runs.</b> A prefetch is a hint: a task the queue refuses runs on the submitting thread,
 * which simply means the caller performs that one read synchronously, exactly as it would have without the prefetch,
 * and every other reader of the same page joins the read in flight through the page manager's per-page read future
 * (SPORK S1), so nothing is read twice however the work is split. The throttled WARNING and the
 * {@code pool=page_prefetch} metrics row are the signals that the pool is undersized for the workload.
 * <p>
 * <b>Never wait on it.</b> No caller parks on a prefetch task: the reads it issues complete into the page cache, and
 * a caller that needs the page reads it through {@link PageManager} and joins the in-flight read if there is one. That
 * is what makes the pool deadlock-free by construction (the #6568 shape needs a waiter), and why its tasks hand back
 * no future.
 * <p>
 * <b>"No JDK common ForkJoinPool" rule.</b> See {@code com.arcadedb.query.QueryEngineManager}'s class javadoc.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
public final class PagePrefetchPool extends DedicatedThreadPool {
  /** Floor of the auto-sized thread count: a device needs more reads in flight than a laptop has cores. */
  public static final int DEFAULT_THREADS_FLOOR = 8;

  private static final class Holder {
    static final PagePrefetchPool INSTANCE = new PagePrefetchPool();
  }

  private PagePrefetchPool() {
    super("ArcadeDB-PagePrefetch-", sizeThreads(GlobalConfiguration.PAGE_PREFETCH_POOL_THREADS.getValueAsInteger()),
        queueSizeOrDefault(GlobalConfiguration.PAGE_PREFETCH_QUEUE_SIZE.getValueAsInteger()), SaturationPolicy.CALLER_RUNS,
        DedicatedThreadPool::plainWorker, "Page prefetch pool",
        "the submitting thread reads that page itself, as it would have without the prefetch",
        GlobalConfiguration.PAGE_PREFETCH_POOL_THREADS, GlobalConfiguration.PAGE_PREFETCH_QUEUE_SIZE);
  }

  /**
   * An explicit positive setting wins; otherwise twice the cores, never fewer than {@link #DEFAULT_THREADS_FLOOR}: the
   * tasks park in the kernel on a device read, so the right count is the device's queue depth, not the core count.
   */
  static int sizeThreads(final int configuredThreads) {
    return configuredThreads > 0 ?
        configuredThreads :
        Math.max(DEFAULT_THREADS_FLOOR, 2 * Runtime.getRuntime().availableProcessors());
  }

  public static PagePrefetchPool getInstance() {
    return Holder.INSTANCE;
  }
}
