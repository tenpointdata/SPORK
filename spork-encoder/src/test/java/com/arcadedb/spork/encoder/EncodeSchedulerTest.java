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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class EncodeSchedulerTest {
  private static final int DIMS = 4;

  @Test
  void bulkReturnsRowsInInputOrderAndBatchesByLength() throws Exception {
    final FakeEncoder encoder = new FakeEncoder("m@1", "S-TEXT", DIMS);
    try (final EncodeScheduler scheduler = new EncodeScheduler(2, 8, 3, 64, 64)) {
      final String[] texts = { "ccccc", "a", "dddddddd", "bb", "e", "ffff", "gg" };
      final float[] rows = scheduler.encodeBulk(encoder, texts, 10_000);

      for (int i = 0; i < texts.length; i++)
        assertThat(Arrays.copyOfRange(rows, i * DIMS, (i + 1) * DIMS)).as(texts[i]).containsExactly(FakeEncoder.expected(texts[i], DIMS));

      assertThat(encoder.batches).hasSize(3);
      final List<Integer> lengths = new ArrayList<>();
      for (final String[] batch : encoder.batches) {
        assertThat(batch.length).isLessThanOrEqualTo(3);
        for (int i = 1; i < batch.length; i++)
          assertThat(batch[i].length()).isGreaterThanOrEqualTo(batch[i - 1].length());
        lengths.add(batch[0].length());
      }
    }
  }

  @Test
  void waitingProbesRunBeforeBulkAndCoalesceIntoOneBatch() throws Exception {
    final FakeEncoder encoder = new FakeEncoder("m@1", "S-TEXT", DIMS);
    final CountDownLatch release = new CountDownLatch(1);
    encoder.gate = release;
    try (final EncodeScheduler scheduler = new EncodeScheduler(1, 16, 4, 64, 64)) {
      // Occupies the only worker until released
      final CompletableFuture<float[]> first = CompletableFuture.supplyAsync(() -> bulk(scheduler, encoder, new String[] { "blocker" }));
      await().atMost(5, TimeUnit.SECONDS).until(() -> encoder.gate == null);

      final CompletableFuture<float[]> second = CompletableFuture.supplyAsync(() -> bulk(scheduler, encoder, new String[] { "bulk" }));
      await().atMost(5, TimeUnit.SECONDS).until(() -> scheduler.bulkQueued() == 1);
      final List<CompletableFuture<float[]>> probes = new ArrayList<>();
      for (int i = 0; i < 5; i++)
        probes.add(scheduler.submitProbe(encoder, "probe " + i));
      assertThat(scheduler.probesQueued()).isEqualTo(5);

      release.countDown();
      for (int i = 0; i < 5; i++)
        assertThat(probes.get(i).get(5, TimeUnit.SECONDS)).containsExactly(FakeEncoder.expected("probe " + i, DIMS));
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);

      assertThat(encoder.batches).hasSize(3);
      assertThat(encoder.batches.get(1)).hasSize(5).allMatch(t -> t.startsWith("probe"));
      assertThat(encoder.batches.get(2)).containsExactly("bulk");
    }
  }

  @Test
  void rejectsWorkBeyondTheQueueBounds() throws Exception {
    final FakeEncoder encoder = new FakeEncoder("m@1", "S-TEXT", DIMS);
    final CountDownLatch release = new CountDownLatch(1);
    encoder.gate = release;
    try (final EncodeScheduler scheduler = new EncodeScheduler(1, 4, 2, 2, 3)) {
      final CompletableFuture<float[]> blocker = scheduler.submitProbe(encoder, "blocker");
      await().atMost(5, TimeUnit.SECONDS).until(() -> encoder.gate == null);

      scheduler.submitProbe(encoder, "p1");
      scheduler.submitProbe(encoder, "p2");
      assertThatThrownBy(() -> scheduler.submitProbe(encoder, "p3")).isInstanceOf(EncoderBusyException.class);
      // 7 texts in micro-batches of 2 need 4 slots of the 3
      assertThatThrownBy(() -> scheduler.encodeBulk(encoder, new String[] { "1", "2", "3", "4", "5", "6", "7" }, 1_000))//
          .isInstanceOf(EncoderBusyException.class);
      assertThat(scheduler.rejected()).isEqualTo(2);

      release.countDown();
      blocker.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void anEncoderFailureReachesEveryCallerOfTheBatch() {
    final FakeEncoder encoder = new FakeEncoder("m@1", "S-TEXT", DIMS);
    encoder.failure = new IllegalStateException("boom");
    try (final EncodeScheduler scheduler = new EncodeScheduler(1, 4, 2, 8, 8)) {
      assertThatThrownBy(() -> scheduler.encodeBulk(encoder, new String[] { "a", "b", "c" }, 5_000))//
          .isInstanceOf(EncoderException.class).hasRootCauseMessage("boom");
      assertThatThrownBy(() -> scheduler.submitProbe(encoder, "x").get(5, TimeUnit.SECONDS))//
          .isInstanceOf(ExecutionException.class).hasRootCauseMessage("boom");
    }
  }

  @Test
  void closeFailsQueuedWork() throws Exception {
    final FakeEncoder encoder = new FakeEncoder("m@1", "S-TEXT", DIMS);
    final CountDownLatch release = new CountDownLatch(1);
    encoder.gate = release;
    final EncodeScheduler scheduler = new EncodeScheduler(1, 4, 2, 8, 8);
    final CompletableFuture<float[]> running = scheduler.submitProbe(encoder, "running");
    await().atMost(5, TimeUnit.SECONDS).until(() -> encoder.gate == null);
    final CompletableFuture<float[]> queued = scheduler.submitProbe(encoder, "queued");

    final Thread closer = new Thread(scheduler::close);
    closer.start();
    await().atMost(5, TimeUnit.SECONDS).until(queued::isDone);
    assertThat(queued).isCompletedExceptionally();
    release.countDown();
    closer.join(5_000);
    assertThat(running.get(5, TimeUnit.SECONDS)).isNotNull();
    assertThatThrownBy(() -> scheduler.submitProbe(encoder, "late")).isInstanceOf(EncoderBusyException.class);
  }

  private static float[] bulk(final EncodeScheduler scheduler, final Encoder encoder, final String[] texts) {
    try {
      return scheduler.encodeBulk(encoder, texts, 10_000);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }
}
