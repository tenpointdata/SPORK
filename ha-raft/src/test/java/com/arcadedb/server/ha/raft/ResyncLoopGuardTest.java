/*
 * Copyright 2021-present Arcade Data Ltd (info@arcadedata.com)
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
package com.arcadedb.server.ha.raft;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The counting and the exponential back-off of {@link ResyncLoopGuard} (SPORK incident 2026-11-12, item 2). */
class ResyncLoopGuardTest {

  private final AtomicLong      now   = new AtomicLong(1_000_000L);
  private final ResyncLoopGuard guard = new ResyncLoopGuard(3, 1_000L, now::get);

  @Test
  void aHealthyNodeHasNothingToReport() {
    assertThat(guard.isIdle()).isTrue();
    assertThat(guard.tripped()).isEmpty();
    assertThat(guard.backoffRemainingMs("db")).isZero();
  }

  @Test
  void attemptsUpToTheLimitDoNotTrip() {
    for (int i = 1; i <= 3; i++)
      assertThat(guard.beginAttempt("db", "cause").tripped()).as("attempt %d", i).isFalse();
    assertThat(guard.backoffRemainingMs("db")).isZero();
    assertThat(guard.tripped()).isEmpty();
  }

  @Test
  void theAttemptPastTheLimitTripsAndTheBackOffDoublesUpToTheCap() {
    for (int i = 0; i < 3; i++)
      guard.beginAttempt("db", "cause");

    final ResyncLoopGuard.Episode fourth = guard.beginAttempt("db", "WAL version gap");
    assertThat(fourth.tripped()).isTrue();
    assertThat(fourth.attempts()).isEqualTo(4);
    assertThat(guard.backoffRemainingMs("db")).isEqualTo(1_000L);
    assertThat(guard.tripped()).extracting(ResyncLoopGuard.Episode::database).containsExactly("db");

    guard.beginAttempt("db", "retry");
    assertThat(guard.backoffRemainingMs("db")).isEqualTo(2_000L);
    guard.beginAttempt("db", "retry");
    assertThat(guard.backoffRemainingMs("db")).isEqualTo(4_000L);

    now.addAndGet(4_000L);
    assertThat(guard.backoffRemainingMs("db")).as("the back-off runs out").isZero();

    assertThat(guard.backoffAfter(100)).as("capped at one hour").isEqualTo(ResyncLoopGuard.MAX_BACKOFF_MS);
  }

  @Test
  void aCompletedResyncStartsTheCountOver() {
    for (int i = 0; i < 4; i++)
      guard.beginAttempt("db", "cause");
    guard.recordOutcome("db", "failed: HTTP 503");
    assertThat(guard.episodeOf("db").lastOutcome()).isEqualTo("failed: HTTP 503");

    guard.completed("db");

    assertThat(guard.isIdle()).isTrue();
    assertThat(guard.backoffRemainingMs("db")).isZero();
    assertThat(guard.beginAttempt("db", "cause").attempts()).isEqualTo(1);
  }

  @Test
  void databasesAreCountedApart() {
    for (int i = 0; i < 4; i++)
      guard.beginAttempt("looping", "cause");
    guard.beginAttempt("other", "cause");

    assertThat(guard.backoffRemainingMs("looping")).isPositive();
    assertThat(guard.backoffRemainingMs("other")).isZero();
    assertThat(guard.databases()).containsExactlyInAnyOrder("looping", "other");
  }
}
