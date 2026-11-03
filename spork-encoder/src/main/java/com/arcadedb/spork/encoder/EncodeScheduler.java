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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.spork.encoder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The sidecar's only compute pool: a fixed set of worker threads, sized to the {@code encode} cgroup, that run every
 * model call. Two lanes share it.
 * <ul>
 *   <li><b>Probe</b> (query time, one text per request): always served first. Probes waiting when a worker frees up are
 *   coalesced into one batch, up to {@code probeMaxBatch}, so a burst of concurrent searches costs one model call
 *   instead of a queue of them.</li>
 *   <li><b>Bulk</b> (ingest saga and backfill): a request is sorted by text length and cut into micro-batches of
 *   {@code bulkMaxBatch}, so each batch pads to texts of similar length. A worker takes a bulk micro-batch only when no
 *   probe waits, so the worst probe wait behind bulk work is one micro-batch.</li>
 * </ul>
 * Both queues are bounded and reject instead of blocking ({@link EncoderBusyException}, HTTP 429): back-pressure is the
 * caller's to apply, which keeps a backfill from queueing hours of work in front of the probes. Bulk admission is all or
 * nothing for a request.
 */
public final class EncodeScheduler implements AutoCloseable {
  private final int probeMaxBatch;
  private final int bulkMaxBatch;
  private final int probeCapacity;
  private final int bulkCapacity;

  private final ReentrantLock            lock     = new ReentrantLock();
  private final Condition                notEmpty = lock.newCondition();
  private final ArrayDeque<ProbeTask>    probes   = new ArrayDeque<>();
  private final ArrayDeque<BulkTask>     bulk     = new ArrayDeque<>();
  private final Thread[]                 workers;
  private       boolean                  closed;
  private final LongAdder                rejected = new LongAdder();
  private final LongAdder                texts    = new LongAdder();
  private final LongAdder                batches  = new LongAdder();
  private final LongAdder                busyNanos = new LongAdder();

  private record ProbeTask(Encoder encoder, String text, CompletableFuture<float[]> result) {
  }

  private static final class BulkRequest {
    final float[]                    rows;
    final CountDownLatch             pending;
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    BulkRequest(final int rows, final int tasks) {
      this.rows = new float[rows];
      this.pending = new CountDownLatch(tasks);
    }
  }

  private record BulkTask(Encoder encoder, String[] texts, int[] positions, BulkRequest request) {
  }

  /**
   * @param workers       the threads; each runs one model call at a time
   * @param probeMaxBatch the most probes one model call coalesces
   * @param bulkMaxBatch  the micro-batch a bulk request is cut into
   * @param probeCapacity the probes that may wait
   * @param bulkCapacity  the bulk micro-batches that may wait
   */
  public EncodeScheduler(final int workers, final int probeMaxBatch, final int bulkMaxBatch, final int probeCapacity, final int bulkCapacity) {
    if (workers < 1 || probeMaxBatch < 1 || bulkMaxBatch < 1 || probeCapacity < 1 || bulkCapacity < 1)
      throw new IllegalArgumentException("Every scheduler size must be at least 1");
    this.probeMaxBatch = probeMaxBatch;
    this.bulkMaxBatch = bulkMaxBatch;
    this.probeCapacity = probeCapacity;
    this.bulkCapacity = bulkCapacity;
    this.workers = new Thread[workers];
    for (int i = 0; i < workers; i++) {
      final Thread worker = new Thread(this::work, "spork-encoder-worker-" + i);
      worker.setDaemon(true);
      this.workers[i] = worker;
      worker.start();
    }
  }

  public CompletableFuture<float[]> submitProbe(final Encoder encoder, final String text) {
    final CompletableFuture<float[]> result = new CompletableFuture<>();
    lock.lock();
    try {
      if (closed)
        throw new EncoderBusyException("The encoder is shutting down");
      if (probes.size() >= probeCapacity) {
        rejected.increment();
        throw new EncoderBusyException("Probe queue full (" + probeCapacity + ")");
      }
      probes.addLast(new ProbeTask(encoder, text, result));
      notEmpty.signal();
    } finally {
      lock.unlock();
    }
    return result;
  }

  /** @return {@code texts.length x dimensions} floats in the order of {@code texts} */
  public float[] encodeBulk(final Encoder encoder, final String[] texts, final long timeoutMs) throws InterruptedException {
    final int dims = encoder.dimensions();
    final Integer[] order = new Integer[texts.length];
    for (int i = 0; i < order.length; i++)
      order[i] = i;
    // Character length is the cheap proxy for token length; sorting by it keeps each micro-batch's padding small
    Arrays.sort(order, (a, b) -> Integer.compare(texts[a].length(), texts[b].length()));

    final int taskCount = (texts.length + bulkMaxBatch - 1) / bulkMaxBatch;
    final BulkRequest request = new BulkRequest(texts.length * dims, taskCount);
    final List<BulkTask> tasks = new ArrayList<>(taskCount);
    for (int start = 0; start < texts.length; start += bulkMaxBatch) {
      final int size = Math.min(bulkMaxBatch, texts.length - start);
      final String[] batch = new String[size];
      final int[] positions = new int[size];
      for (int i = 0; i < size; i++) {
        positions[i] = order[start + i];
        batch[i] = texts[positions[i]];
      }
      tasks.add(new BulkTask(encoder, batch, positions, request));
    }

    lock.lock();
    try {
      if (closed)
        throw new EncoderBusyException("The encoder is shutting down");
      if (bulk.size() + taskCount > bulkCapacity) {
        rejected.increment();
        throw new EncoderBusyException("Bulk queue full: " + taskCount + " micro-batches requested, " + (bulkCapacity - bulk.size()) + " free");
      }
      bulk.addAll(tasks);
      if (taskCount == 1)
        notEmpty.signal();
      else
        notEmpty.signalAll();
    } finally {
      lock.unlock();
    }

    if (!request.pending.await(timeoutMs, TimeUnit.MILLISECONDS))
      throw new EncoderException("Bulk encode of " + texts.length + " texts timed out after " + timeoutMs + " ms");
    final Throwable failure = request.failure.get();
    if (failure != null)
      throw failure instanceof EncoderException e ? e : new EncoderException("Bulk encode failed: " + failure.getMessage(), failure);
    return request.rows;
  }

  public int probesQueued() {
    lock.lock();
    try {
      return probes.size();
    } finally {
      lock.unlock();
    }
  }

  public int bulkQueued() {
    lock.lock();
    try {
      return bulk.size();
    } finally {
      lock.unlock();
    }
  }

  public long rejected() {
    return rejected.sum();
  }

  public long textsEncoded() {
    return texts.sum();
  }

  public long batchesRun() {
    return batches.sum();
  }

  /** @return the nanoseconds workers spent inside model calls, summed over workers */
  public long busyNanos() {
    return busyNanos.sum();
  }

  public int workers() {
    return workers.length;
  }

  /** Fails everything still queued, lets running batches finish, and joins the workers. */
  @Override
  public void close() {
    final List<ProbeTask> droppedProbes;
    final List<BulkTask> droppedBulk;
    lock.lock();
    try {
      if (closed)
        return;
      closed = true;
      droppedProbes = new ArrayList<>(probes);
      droppedBulk = new ArrayList<>(bulk);
      probes.clear();
      bulk.clear();
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
    final EncoderBusyException shutdown = new EncoderBusyException("The encoder shut down before this work ran");
    for (final ProbeTask task : droppedProbes)
      task.result.completeExceptionally(shutdown);
    for (final BulkTask task : droppedBulk)
      fail(task, shutdown);
    for (final Thread worker : workers)
      try {
        worker.join(60_000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
  }

  private void work() {
    final List<ProbeTask> probeBatch = new ArrayList<>(probeMaxBatch);
    while (true) {
      BulkTask bulkTask = null;
      probeBatch.clear();
      lock.lock();
      try {
        while (!closed && probes.isEmpty() && bulk.isEmpty())
          notEmpty.awaitUninterruptibly();
        if (closed)
          return;
        if (!probes.isEmpty()) {
          final ProbeTask first = probes.pollFirst();
          probeBatch.add(first);
          while (probeBatch.size() < probeMaxBatch && !probes.isEmpty() && probes.peekFirst().encoder == first.encoder)
            probeBatch.add(probes.pollFirst());
        } else
          bulkTask = bulk.pollFirst();
      } finally {
        lock.unlock();
      }

      if (bulkTask != null)
        runBulk(bulkTask);
      else
        runProbes(probeBatch);
    }
  }

  private void runProbes(final List<ProbeTask> batch) {
    final Encoder encoder = batch.get(0).encoder;
    final String[] input = new String[batch.size()];
    for (int i = 0; i < input.length; i++)
      input[i] = batch.get(i).text;
    final float[] rows;
    try {
      rows = timed(encoder, input);
    } catch (final Throwable t) {
      final EncoderException failure = new EncoderException("Probe encode failed with " + encoder.id() + ": " + t.getMessage(), t);
      for (final ProbeTask task : batch)
        task.result.completeExceptionally(failure);
      return;
    }
    final int dims = encoder.dimensions();
    for (int i = 0; i < input.length; i++)
      batch.get(i).result.complete(Arrays.copyOfRange(rows, i * dims, (i + 1) * dims));
  }

  private void runBulk(final BulkTask task) {
    final BulkRequest request = task.request;
    if (request.failure.get() != null) {
      // An earlier micro-batch of the request failed: the request is lost, do not spend the cores on the rest
      request.pending.countDown();
      return;
    }
    try {
      final float[] rows = timed(task.encoder, task.texts);
      final int dims = task.encoder.dimensions();
      for (int i = 0; i < task.positions.length; i++)
        System.arraycopy(rows, i * dims, request.rows, task.positions[i] * dims, dims);
      request.pending.countDown();
    } catch (final Throwable t) {
      fail(task, new EncoderException("Bulk encode failed with " + task.encoder.id() + ": " + t.getMessage(), t));
    }
  }

  private float[] timed(final Encoder encoder, final String[] input) throws Exception {
    final long start = System.nanoTime();
    try {
      final float[] rows = encoder.encode(input);
      texts.add(input.length);
      batches.increment();
      return rows;
    } finally {
      busyNanos.add(System.nanoTime() - start);
    }
  }

  private static void fail(final BulkTask task, final RuntimeException failure) {
    task.request.failure.compareAndSet(null, failure);
    task.request.pending.countDown();
  }
}
