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
package com.arcadedb.index.fulltext;

import com.arcadedb.log.LogManager;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Writes the block-max sidecars that automatic compactions leave outdated, in the background, one at a time, for every database
 * of the JVM.
 * <p>
 * <b>Why it exists.</b> Writing a sidecar reads the whole compacted part of a bucket index: seconds at a million documents,
 * minutes at hundreds of millions. Run inline at the end of a compaction it kept the compaction's async worker (and whatever
 * else was queued on it) busy for that long. Here the compaction only asks for it and returns.
 * <p>
 * <b>Shape.</b> One daemon thread at {@link Thread#MIN_PRIORITY}, started on the first request and gone once nothing is waiting,
 * so an idle JVM carries no thread. A request for an index already waiting is dropped: the build it joins reads the index as it is
 * when it runs, which covers both requests. Nothing else bounds the queue, nor needs to: it holds at most one entry per open
 * full-text bucket index. It is a thread of its own rather than a {@code DedicatedThreadPool} task on purpose: a pool answers
 * saturation by running the task on the submitter, which is the compaction thread this work is moved off. The build itself takes
 * the index's own build lock, so it never runs twice at once for one index, whoever asked for it.
 */
final class BlockMaxBuildScheduler {
  private static final Object                          MONITOR = new Object();
  // Guarded by MONITOR
  private static final ArrayDeque<LSMTreeFullTextIndex> PENDING = new ArrayDeque<>();
  private static       Thread                          worker;
  private static       boolean                         running;

  private BlockMaxBuildScheduler() {
  }

  /** Asks for the sidecar of {@code index} to be brought up to date, unless that is already waiting. */
  static void schedule(final LSMTreeFullTextIndex index) {
    synchronized (MONITOR) {
      for (final LSMTreeFullTextIndex waiting : PENDING)
        if (waiting == index)
          return;
      PENDING.addLast(index);
      if (worker == null) {
        worker = new Thread(BlockMaxBuildScheduler::runWorker, "ArcadeDB-FullTextBlockMax");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
      }
      MONITOR.notifyAll();
    }
  }

  /**
   * Drops the request waiting for {@code index}, if any: its database is closing. A build of it already running stops on its own,
   * at its next term.
   */
  static void cancel(final LSMTreeFullTextIndex index) {
    synchronized (MONITOR) {
      if (PENDING.removeIf(waiting -> waiting == index))
        MONITOR.notifyAll();
    }
  }

  /** True when a build of {@code index} is waiting for its turn. For the tests. */
  static boolean isPending(final LSMTreeFullTextIndex index) {
    synchronized (MONITOR) {
      for (final LSMTreeFullTextIndex waiting : PENDING)
        if (waiting == index)
          return true;
      return false;
    }
  }

  /**
   * Waits until nothing is waiting and nothing is running. For tests and tools that need the sidecars written before they carry
   * on.
   *
   * @return false when the timeout elapsed first
   */
  static boolean awaitIdle(final long timeoutMs) {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    synchronized (MONITOR) {
      while (!PENDING.isEmpty() || running) {
        final long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMs <= 0)
          return false;
        try {
          MONITOR.wait(remainingMs);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  private static void runWorker() {
    while (true) {
      final LSMTreeFullTextIndex index;
      synchronized (MONITOR) {
        index = PENDING.pollFirst();
        if (index == null) {
          // Decided under the monitor schedule() takes too, so a request never sees a worker that already decided to leave
          worker = null;
          running = false;
          MONITOR.notifyAll();
          return;
        }
        running = true;
      }
      try {
        index.refreshBlockMaxInBackground();
      } catch (final Throwable e) {
        LogManager.instance().log(BlockMaxBuildScheduler.class, Level.WARNING, "Error writing the block-max file of index '%s'", e,
            index.getName());
      } finally {
        synchronized (MONITOR) {
          running = false;
          MONITOR.notifyAll();
        }
      }
    }
  }
}
