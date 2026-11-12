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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts how many times this follower re-downloaded each database from the leader without the resync that asked for it
 * completing, and stops a resync from looping silently at line rate (SPORK incident 2026-11-12: a follower re-downloaded
 * two healthy databases, 10.8 GB each round, about 550 times in 12 hours, and never reached the diverged one).
 * <p>
 * Every install attempt is counted by {@link #beginAttempt}; only the COMPLETION of the resync that drove it resets the
 * count ({@link #completed}). A full resync completes when its whole pass does, so a pass that re-installs a database
 * over and over without ending is caught as well as a targeted resync whose install keeps failing. Past
 * {@code maxAttempts} the database is "tripped": the caller logs a SEVERE alert, it is published as the
 * {@code resync-loop} alert of {@code GET /api/v1/cluster} and in the capability answer the leader reads, and the
 * resync drivers wait {@link #backoffRemainingMs} before trying again, doubling on every further attempt up to
 * {@link #MAX_BACKOFF_MS}.
 * <p>
 * Lock-free and allocation-free on a healthy node: every reader starts with an empty-map check.
 */
public final class ResyncLoopGuard {

  /** Upper bound of the back-off between two attempts once a database is tripped. */
  static final long MAX_BACKOFF_MS = 3_600_000L;

  /** What is known about one database's resync attempts since its last completed resync. */
  public record Episode(String database, int attempts, String lastCause, String lastOutcome, long lastAttemptAtMs,
                        long backoffUntilMs, boolean tripped) {
  }

  private final ConcurrentHashMap<String, Episode> episodes = new ConcurrentHashMap<>();
  private final int                                maxAttempts;
  private final long                               backoffBaseMs;
  private final LongSupplier                       clock;

  public ResyncLoopGuard(final int maxAttempts, final long backoffBaseMs) {
    this(maxAttempts, backoffBaseMs, System::currentTimeMillis);
  }

  ResyncLoopGuard(final int maxAttempts, final long backoffBaseMs, final LongSupplier clock) {
    this.maxAttempts = Math.max(1, maxAttempts);
    this.backoffBaseMs = Math.max(1L, backoffBaseMs);
    this.clock = clock;
  }

  /**
   * Counts one more install attempt of {@code database}, requested because of {@code cause}.
   *
   * @return the episode after the count; {@link Episode#tripped()} is true from the attempt that exceeds the limit on,
   * and the caller is the one that logs it
   */
  public Episode beginAttempt(final String database, final String cause) {
    final long now = clock.getAsLong();
    return episodes.compute(database, (name, previous) -> {
      final int attempts = previous == null ? 1 : previous.attempts() + 1;
      final boolean tripped = attempts > maxAttempts;
      final long backoffUntil = tripped ? now + backoffAfter(attempts) : 0L;
      return new Episode(name, attempts, cause, previous != null ? previous.lastOutcome() : null, now, backoffUntil,
          tripped);
    });
  }

  /** Records how the last attempt of {@code database} ended, for the alert and the status document. */
  public void recordOutcome(final String database, final String outcome) {
    episodes.computeIfPresent(database,
        (name, e) -> new Episode(name, e.attempts(), e.lastCause(), outcome, e.lastAttemptAtMs(), e.backoffUntilMs(),
            e.tripped()));
  }

  /** The resync of {@code database} completed: its count starts over. */
  public void completed(final String database) {
    if (!episodes.isEmpty())
      episodes.remove(database);
  }

  /** Milliseconds a resync driver must still wait before downloading {@code database} again, {@code 0} when none. */
  public long backoffRemainingMs(final String database) {
    if (episodes.isEmpty())
      return 0L;
    final Episode e = episodes.get(database);
    if (e == null || !e.tripped())
      return 0L;
    return Math.max(0L, e.backoffUntilMs() - clock.getAsLong());
  }

  /** The episode of {@code database}, or {@code null} when it has no attempt pending completion. */
  public Episode episodeOf(final String database) {
    return episodes.isEmpty() ? null : episodes.get(database);
  }

  /** Every database past the limit, sorted by name; the empty list on a healthy node. */
  public List<Episode> tripped() {
    if (episodes.isEmpty())
      return Collections.emptyList();
    final List<Episode> result = new ArrayList<>();
    for (final Episode e : episodes.values())
      if (e.tripped())
        result.add(e);
    result.sort((a, b) -> a.database().compareTo(b.database()));
    return result;
  }

  /** Whether no database has an attempt pending completion: the healthy node's answer, one map test. */
  public boolean isIdle() {
    return episodes.isEmpty();
  }

  /** The databases with an attempt pending completion, as a copy. */
  public List<String> databases() {
    return episodes.isEmpty() ? Collections.emptyList() : new ArrayList<>(episodes.keySet());
  }

  int maxAttempts() {
    return maxAttempts;
  }

  /** Back-off after attempt {@code attempts}: base on the first attempt past the limit, doubled for each one after. */
  long backoffAfter(final int attempts) {
    final int doublings = Math.min(Math.max(attempts - maxAttempts - 1, 0), 20);
    return Math.min(backoffBaseMs << doublings, MAX_BACKOFF_MS);
  }
}
