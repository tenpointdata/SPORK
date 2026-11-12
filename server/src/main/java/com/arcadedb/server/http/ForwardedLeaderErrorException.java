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

/**
 * The leader's error answer to a command a follower forwarded, when the follower cannot rebuild the exception it names
 * as its own type ({@code RaftReplicatedDatabase.reconstructLeaderException}). It carries what the leader said, so the
 * follower answers its client the way the leader would have.
 * <p>
 * Two shapes, told apart by {@link #isTyped()}:
 * <ul>
 * <li>A <b>typed</b> answer names an exception class: its body was written by the leader's own error classification, so
 * the status, label, exception class, {@code exceptionArgs} and {@code Retry-After} are the leader's verdict and are relayed
 * as they are. Before, every such answer the follower did not know by name became a 500 "Error on transaction commit"
 * naming {@code TransactionException}, with no detail in production mode: a leader's {@code 503 QueryAdmissionException}
 * reached the client as a failed transaction (incident 2026-11-12).</li>
 * <li>An <b>anonymous</b> answer names none, or is not JSON: it may come from something between the two nodes, after the
 * command ran (PR #8402 review), so its status is not relayed. It stays a 500, with {@code LEADER_ERROR} and the leader's
 * status in {@code exceptionArgs}.</li>
 * </ul>
 * Never a {@link com.arcadedb.exception.NeedRetryException}: a server-side retry loop on the follower would forward the
 * command again under a new forward ordinal, which the leader keys separately, and could run it twice.
 */
public class ForwardedLeaderErrorException extends ClusterTransactionException {
  private final int    leaderStatus;
  private final String leaderLabel;
  private final String leaderExceptionClass;
  private final String leaderExceptionArgs;
  private final String retryAfter;

  public ForwardedLeaderErrorException(final String message, final int leaderStatus, final String leaderLabel,
      final String leaderExceptionClass, final String leaderExceptionArgs, final String retryAfter) {
    super(message, Cause.LEADER_ERROR);
    this.leaderStatus = leaderStatus;
    this.leaderLabel = leaderLabel;
    this.leaderExceptionClass = leaderExceptionClass;
    this.leaderExceptionArgs = leaderExceptionArgs;
    this.retryAfter = retryAfter;
  }

  /** Whether the leader's body names an exception class, and so came from its error classification. */
  public boolean isTyped() {
    return leaderExceptionClass != null && !leaderExceptionClass.isBlank();
  }

  public int getLeaderStatus() {
    return leaderStatus;
  }

  /** The leader's {@code error} label, or null when it sent none. */
  public String getLeaderLabel() {
    return leaderLabel;
  }

  /** The exception class the leader named, or null for an anonymous answer. */
  public String getLeaderExceptionClass() {
    return leaderExceptionClass;
  }

  /** The leader's {@code exceptionArgs}, or null. */
  public String getLeaderExceptionArgs() {
    return leaderExceptionArgs;
  }

  /** The leader's {@code Retry-After} header, or null when it sent none. */
  public String getRetryAfter() {
    return retryAfter;
  }
}
