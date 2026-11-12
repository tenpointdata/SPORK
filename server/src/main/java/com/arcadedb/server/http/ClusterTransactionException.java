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
package com.arcadedb.server.http;

import com.arcadedb.exception.TransactionException;

/**
 * A transaction or forwarded command failed for a reason that belongs to the cluster rather than to the statement, and
 * says which ({@link Cause}). The HTTP layer answers it like any {@link TransactionException} (500 "Error on transaction
 * commit") and puts the cause's name in the body's {@code exceptionArgs}, a bounded token that is emitted in production
 * mode too: before, a follower answered these failures with nothing but the label, because production mode conceals the
 * free-form {@code detail} (incident 2026-11-12: 197 such answers, none of them in the leader's log).
 */
public class ClusterTransactionException extends TransactionException {
  /**
   * The cause categories a client is told. Part of the wire contract (the names travel in {@code exceptionArgs}): add
   * constants, never rename one.
   */
  public enum Cause {
    /** The leader answered a command this node forwarded to it with an error. */
    LEADER_ERROR,
    /**
     * No leader was known, or the exchange with the leader failed in transport. Whether the command ran there is known only
     * when the failure was to connect; read the write back before re-issuing it otherwise.
     */
    LEADER_UNREACHABLE,
    /** The leader accepted the forwarded command and did not answer in time: whether it ran is unknown. */
    LEADER_TIMEOUT,
    /** The replication of the transaction was handed to Raft and its quorum wait ended without an outcome. */
    QUORUM_TIMEOUT,
    /**
     * This node's copy of the database is quarantined until a resync restores it. For the refusal a quarantined follower
     * answers a request on that database with (raised by the HA module's quarantine handling).
     */
    DATABASE_QUARANTINED,
    /** A failure the server did not classify: the server log, under the response's request id, holds the rest. */
    INTERNAL
  }

  private final Cause cause;

  public ClusterTransactionException(final String message, final Cause cause) {
    super(message);
    this.cause = cause != null ? cause : Cause.INTERNAL;
  }

  public ClusterTransactionException(final String message, final Throwable e, final Cause cause) {
    super(message, e);
    this.cause = cause != null ? cause : Cause.INTERNAL;
  }

  public Cause getCauseCategory() {
    return cause;
  }
}
