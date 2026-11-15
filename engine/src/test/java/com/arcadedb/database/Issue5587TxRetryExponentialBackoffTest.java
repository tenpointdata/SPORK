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
package com.arcadedb.database;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.utility.StallAwareStopwatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for #5587: before this fix, every {@code database.transaction(...)} retry slept a flat
 * {@code U(1, TX_RETRY_DELAY)} interval no matter how many times the transaction had already lost, so a
 * transaction on attempt 50 had the same odds of winning the next race as one on attempt 1. This proves the
 * retry loop now feeds a widening per-attempt window to the backoff (exponential backoff with full jitter,
 * {@code min(TX_RETRY_DELAY, TX_RETRY_DELAY_BASE * 2^attempt)}) instead of a flat one. The window arithmetic
 * itself is covered deterministically by {@code RetryBackoffTest}; this test only has to show the loop uses it.
 * <p>
 * The {@code error} callback fires once per failed attempt, right before the backoff sleep, so the wall-clock
 * gap between consecutive callback invocations is (up to retry-loop overhead) that attempt's sleep.
 * <p>
 * Each half of the claim is asserted in the direction that scheduling noise cannot break, rather than comparing
 * the two halves against each other (which flaked: a single 40 ms scheduling hiccup in the early half, plus a
 * low jitter draw in the late half, is enough to invert a relative comparison of sums this small):
 * <ul>
 * <li>the late retries slept at least {@link #LATE_FLOOR_MS} in raw wall clock. A loop that never widened the
 * window (stuck at the base) cannot sleep more than {@code 4 * BASE_MS} there, and any stall only adds time, so
 * this is a lower bound with nothing to discount;</li>
 * <li>the early retries finished within {@link #EARLY_TRIPWIRE_MS} with the JVM's stalls discounted over that
 * one span. A flat {@code U(1, CAP_MS)} window (the pre-#5587 shape) sleeps ~2 s there, the exponential windows
 * at most 60 ms, so the tripwire sits far from both sides.</li>
 * </ul>
 */
class Issue5587TxRetryExponentialBackoffTest extends TestHelper {
  /** One more than the number of forced failures, so the last attempt succeeds. */
  private static final int  ATTEMPTS          = 10;
  /** Early windows (attempts 0-3) are 4, 8, 16, 32 ms: at most 60 ms of sleep in total... */
  private static final int  BASE_MS           = 4;
  /** ...and the late windows (attempts 4-7: 64, 128, 256, 512 ms) never saturate against the cap. */
  private static final int  CAP_MS            = 1_000;
  /** Retries per half: the first four gaps are the early half, the last four the late one. */
  private static final int  HALF              = 4;
  /**
   * Out of reach of a window stuck at the base (at most 4 * 4 = 16 ms), and missed by the four late full-jitter
   * draws (U(1,64) + U(1,128) + U(1,256) + U(1,512), expected ~480 ms) with a probability of about 1 in 10 000.
   */
  private static final long LATE_FLOOR_MS     = 40;
  /**
   * A tripwire between the exponential early windows (at most 60 ms) and a flat 1 s window (~2 s expected over
   * four draws, under this bound with a probability of about 1 in 1 000): generous on both sides.
   */
  private static final long EARLY_TRIPWIRE_MS = 400;

  @Test
  void laterRetriesBackOffFurtherThanEarlierOnes() {
    final int savedCap = GlobalConfiguration.TX_RETRY_DELAY.getValueAsInteger();
    final int savedBase = GlobalConfiguration.TX_RETRY_DELAY_BASE.getValueAsInteger();
    database.getConfiguration().setValue(GlobalConfiguration.TX_RETRY_DELAY, CAP_MS);
    database.getConfiguration().setValue(GlobalConfiguration.TX_RETRY_DELAY_BASE, BASE_MS);
    try {
      // Started before the loop, so the sampler is already ticking when the first gap opens
      StallAwareStopwatch.jvmStallNanos();

      final List<Long> failureTimestampsNanos = new ArrayList<>();
      final List<Long> failureStallNanos = new ArrayList<>();
      final AtomicInteger attempt = new AtomicInteger();

      database.transaction(() -> {
        if (attempt.incrementAndGet() < ATTEMPTS)
          throw new ConcurrentModificationException("forced retry");
      }, false, ATTEMPTS, null, e -> {
        failureTimestampsNanos.add(System.nanoTime());
        failureStallNanos.add(StallAwareStopwatch.jvmStallNanos());
      });

      assertThat(attempt.get()).isEqualTo(ATTEMPTS);
      // one error callback per failed attempt, i.e. every attempt but the last
      assertThat(failureTimestampsNanos).hasSize(ATTEMPTS - 1);

      final List<Long> gapsMs = new ArrayList<>();
      for (int i = 1; i < failureTimestampsNanos.size(); i++)
        gapsMs.add((failureTimestampsNanos.get(i) - failureTimestampsNanos.get(i - 1)) / 1_000_000);
      assertThat(gapsMs).hasSize(2 * HALF);

      // LATE HALF: A LOWER BOUND IN RAW WALL CLOCK. A STALL CAN ONLY LENGTHEN IT, SO NOTHING IS DISCOUNTED
      final int lateStart = failureTimestampsNanos.size() - 1 - HALF;
      final long lateMs = (failureTimestampsNanos.getLast() - failureTimestampsNanos.get(lateStart)) / 1_000_000;
      assertThat(lateMs)
          .as("the late retries (gaps %s) should sleep in widened windows (64-512 ms), not stay at the %d ms base",
              gapsMs.subList(HALF, 2 * HALF), BASE_MS)
          .isGreaterThanOrEqualTo(LATE_FLOOR_MS);

      // EARLY HALF: AN UPPER BOUND, SO THE JVM'S STALLS ARE DISCOUNTED OVER THE WHOLE SPAN (#6260). ONE SPAN, NOT
      // ONE PER GAP: THE SAMPLER CHARGES A STALL WHEN IT WAKES, SO PER-GAP DISCOUNTS LAND IN THE WRONG GAP
      final long earlySpanNanos = failureTimestampsNanos.get(HALF) - failureTimestampsNanos.getFirst();
      final long earlyStallNanos = Math.max(0L, failureStallNanos.get(HALF) - failureStallNanos.getFirst());
      final long earlyMs = Math.max(0L, earlySpanNanos - earlyStallNanos) / 1_000_000;
      assertThat(earlyMs)
          .as("the early retries (gaps %s, %d ms of JVM stall discounted) should sleep in small windows (4-32 ms), "
                  + "not a flat %d ms one. This bound is a tripwire between the two, not a latency budget: widening it "
                  + "is safe", gapsMs.subList(0, HALF), earlyStallNanos / 1_000_000, CAP_MS)
          .isLessThan(EARLY_TRIPWIRE_MS);
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.TX_RETRY_DELAY, savedCap);
      database.getConfiguration().setValue(GlobalConfiguration.TX_RETRY_DELAY_BASE, savedBase);
    }
  }

  @Test
  void aZeroCapDisablesTheDelayEntirelyJustLikeBeforeTheFix() {
    final int savedCap = GlobalConfiguration.TX_RETRY_DELAY.getValueAsInteger();
    database.getConfiguration().setValue(GlobalConfiguration.TX_RETRY_DELAY, 0);
    try {
      final AtomicInteger attempt = new AtomicInteger();
      final StallAwareStopwatch stopwatch = StallAwareStopwatch.start();

      database.transaction(() -> {
        if (attempt.incrementAndGet() < ATTEMPTS)
          throw new ConcurrentModificationException("forced retry");
      }, false, ATTEMPTS);

      assertThat(attempt.get()).isEqualTo(ATTEMPTS);
      stopwatch.assertStayedUnder(1_000, "10 retries with the delay disabled, not 10 retries that each sleep");
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.TX_RETRY_DELAY, savedCap);
    }
  }
}
