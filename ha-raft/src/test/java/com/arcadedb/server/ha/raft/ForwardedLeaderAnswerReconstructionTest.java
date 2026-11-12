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
package com.arcadedb.server.ha.raft;

import com.arcadedb.exception.DatabaseNotAvailableException;
import com.arcadedb.exception.DatabaseNotFoundException;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.QueryAdmissionException;
import com.arcadedb.exception.TransactionException;
import com.arcadedb.exception.WALVersionGapException;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.http.ForwardedLeaderErrorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-12: a follower forwarded a command, the leader refused it with {@code 503 QueryAdmissionException}, and
 * the follower rebuilt the answer as a plain {@code TransactionException}: its client got a 500 "Error on transaction
 * commit" with no detail, for a command that never ran and is safe to retry. Every type the follower did not know by name
 * collapsed the same way. Now a type is rebuilt as itself when this build can, and relayed with the leader's status, label,
 * exception class, exceptionArgs and Retry-After when it cannot.
 */
class ForwardedLeaderAnswerReconstructionTest {

  /** The body the leader sends in production mode, which conceals 'detail' for everything but client errors. */
  private static String productionBody(final String label, final String exceptionClass, final String exceptionArgs) {
    final JSONObject json = new JSONObject().put("error", label).put("requestId", "a9bf2cd61eadc4af-2c7660")
        .put("exception", exceptionClass);
    if (exceptionArgs != null)
      json.put("exceptionArgs", exceptionArgs);
    return json.toString();
  }

  /** The exact answer of the incident (df1db6, 06:39:17.288). */
  @Test
  void theLeadersAdmissionRefusalIsRebuiltAsItself() {
    final RuntimeException rebuilt = RaftReplicatedDatabase.reconstructLeaderException(503,
        productionBody("Cannot execute command", QueryAdmissionException.class.getName(), null), null);

    assertThat(rebuilt).isInstanceOf(QueryAdmissionException.class);
    // Not retried on the spot by this node: it would only queue again behind the same load
    assertThat(rebuilt).isNotInstanceOf(NeedRetryException.class);
  }

  /** The leader keeps the gate's reason in production mode now, and the follower keeps it as the message. */
  @Test
  void theAdmissionReasonTheLeaderGaveSurvives() {
    final String reason = "Query not started because the queue already holds 64 queries (arcadedb.queryQueueMaxSize)";
    final String body = new JSONObject(productionBody("Cannot execute command", QueryAdmissionException.class.getName(), null))
        .put("detail", reason).toString();

    assertThat(RaftReplicatedDatabase.reconstructLeaderException(503, body, "1").getMessage()).isEqualTo(reason);
  }

  @Test
  void anUnavailableDatabaseKeepsTheReasonTheLeaderGave() {
    final RuntimeException rebuilt = RaftReplicatedDatabase.reconstructLeaderException(503,
        productionBody("Database is being restored", DatabaseNotAvailableException.class.getName(), "RESTORING"), null);

    assertThat(rebuilt).isInstanceOf(DatabaseNotAvailableException.class);
    assertThat(((DatabaseNotAvailableException) rebuilt).getReason()).isEqualTo(DatabaseNotAvailableException.Reason.RESTORING);
  }

  /** A message-only type of the drivers' table is rebuilt as itself, so this node's classification answers the same status. */
  @Test
  void aTypeTheDriversRebuildIsRebuiltAsItself() {
    assertThat(RaftReplicatedDatabase.reconstructLeaderException(404,
        productionBody("Database not found", DatabaseNotFoundException.class.getName(), null), null))
        .isInstanceOf(DatabaseNotFoundException.class);
    assertThat(RaftReplicatedDatabase.reconstructLeaderException(400,
        productionBody("Cannot execute command", IllegalArgumentException.class.getName(), null), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A retryable type the HA table does not list is relayed, not rebuilt: whether a retry loop on this node may re-forward
   * it is decided case by case, and an unlisted one could run the command a second time under a new forward ordinal.
   */
  @Test
  void aRetryableTypeTheHaTableDoesNotListIsRelayedNotRetried() {
    final RuntimeException rebuilt = RaftReplicatedDatabase.reconstructLeaderException(503,
        productionBody("Cannot execute command", WALVersionGapException.class.getName(), null), "2");

    assertThat(rebuilt).isNotInstanceOf(NeedRetryException.class).isInstanceOf(ForwardedLeaderErrorException.class);
    final ForwardedLeaderErrorException relayed = (ForwardedLeaderErrorException) rebuilt;
    assertThat(relayed.isTyped()).isTrue();
    assertThat(relayed.getLeaderStatus()).isEqualTo(503);
    assertThat(relayed.getLeaderExceptionClass()).isEqualTo(WALVersionGapException.class.getName());
    assertThat(relayed.getRetryAfter()).isEqualTo("2");
  }

  /** A class this build does not know - a newer leader's, during a rolling upgrade - is relayed with everything it said. */
  @Test
  void anUnknownTypeIsRelayedWithTheLeadersVerdict() {
    final RuntimeException rebuilt = RaftReplicatedDatabase.reconstructLeaderException(409,
        productionBody("Something already exists", "com.example.NewerServerException", "KIND|x"), null);

    assertThat(rebuilt).isInstanceOf(ForwardedLeaderErrorException.class).isInstanceOf(TransactionException.class);
    final ForwardedLeaderErrorException relayed = (ForwardedLeaderErrorException) rebuilt;
    assertThat(relayed.getLeaderStatus()).isEqualTo(409);
    assertThat(relayed.getLeaderLabel()).isEqualTo("Something already exists");
    assertThat(relayed.getLeaderExceptionArgs()).isEqualTo("KIND|x");
  }

  /** The leader's own 500 keeps the cause category it put in exceptionArgs. */
  @Test
  void theLeadersFailedTransactionKeepsItsCauseCategory() {
    final RuntimeException rebuilt = RaftReplicatedDatabase.reconstructLeaderException(500,
        productionBody("Error on transaction commit", TransactionException.class.getName(), "QUORUM_TIMEOUT"), null);

    assertThat(rebuilt).isInstanceOf(ForwardedLeaderErrorException.class);
    assertThat(((ForwardedLeaderErrorException) rebuilt).getLeaderExceptionArgs()).isEqualTo("QUORUM_TIMEOUT");
  }

  /** An answer that names no exception, or is not JSON, may not be the leader's own: its status is not relayed. */
  @Test
  void anAnonymousAnswerIsNotTyped() {
    for (final String body : new String[] { null, "", "{}", "<html>Bad Gateway</html>", "{\"error\":\"upstream connect error\"}" }) {
      final RuntimeException rebuilt = RaftReplicatedDatabase.reconstructLeaderException(503, body, "5");
      assertThat(rebuilt).as("body=%s", body).isInstanceOf(ForwardedLeaderErrorException.class);
      assertThat(((ForwardedLeaderErrorException) rebuilt).isTyped()).as("body=%s", body).isFalse();
      assertThat(((ForwardedLeaderErrorException) rebuilt).getLeaderStatus()).isEqualTo(503);
    }
  }
}
