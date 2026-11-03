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
package com.arcadedb.query.sql.executor;

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPORK page cache, review finding on L1: {@link FetchFromRidsStep} hydrates its RIDs in batches, ahead of the rows a DML
 * statement processes one at a time. A record handed out of the batch must reflect what the earlier rows of the same
 * statement did in the transaction, as the single lookup at pull time did: a record they deleted is skipped, a record they
 * changed is answered from the transaction.
 * <p>
 * Re-review of M6: the same must hold when an earlier row's write is already COMMITTED by the time a later row's buffered
 * record is handed out, which the transaction's own deleted and written sets no longer show. A {@code BATCH n} clause does that,
 * committing and re-beginning every {@code n} rows, in an explicit transaction and in {@code autoTransaction} mode (how the server
 * opens every database) alike. Without a {@code BATCH} clause an {@code autoTransaction} statement runs in ONE statement-level
 * implicit transaction (issue #7096), which the tests below pin as well. A statement run as a line of a SQL script opens none,
 * so there each row opens and commits an implicit transaction of its own and every later buffered record is handed out with
 * no transaction active at all (third review).
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class FetchFromRidsStepStatementWritesTest extends TestHelper {

  private long count(final String type) {
    return database.countType(type, false);
  }

  /** Runs a DELETE in its own transaction and answers the count it reports. */
  private long deleted(final String sql) {
    final long[] count = new long[1];
    database.transaction(() -> {
      try (final ResultSet rs = database.command("sql", sql)) {
        count[0] = rs.next().<Number>getProperty("count").longValue();
      }
    });
    return count[0];
  }

  /** Runs a DELETE with no transaction open, each row committing an implicit one, and answers the count it reports. */
  private long deletedInAutoTransaction(final String sql) {
    assertThat(database.isTransactionActive()).isFalse();
    try (final ResultSet rs = database.command("sql", sql)) {
      return rs.next().<Number>getProperty("count").longValue();
    }
  }

  private String twice(final List<RID> rids) {
    final StringBuilder list = new StringBuilder("[");
    for (final RID r : rids)
      list.append(r).append(',').append(r).append(',');
    list.setLength(list.length() - 1);
    return list.append(']').toString();
  }

  @Test
  void deletingTheSameRidTwiceInOneStatementDeletesOneRecord() {
    database.getSchema().createDocumentType("Doc");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Doc").set("n", 0).save().getIdentity());
    database.transaction(() -> database.newDocument("Doc").set("n", 1).save());

    assertThat(deleted("DELETE FROM [" + rid[0] + ", " + rid[0] + "]")).isEqualTo(1L);
    assertThat(database.existsRecord(rid[0])).isFalse();
    assertThat(count("Doc")).isEqualTo(1L);
  }

  @Test
  void deletingAVertexAndItsEdgeInOneStatementSucceeds() {
    database.getSchema().createVertexType("V1");
    database.getSchema().createEdgeType("E1");
    final RID[] rids = new RID[3];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("V1").set("name", "a").save();
      final MutableVertex b = database.newVertex("V1").set("name", "b").save();
      rids[0] = a.getIdentity();
      rids[1] = b.getIdentity();
      rids[2] = a.newEdge("E1", b).getIdentity();
    });

    // THE VERTEX FIRST: ITS DELETE CASCADES TO THE EDGE, WHICH THE BATCH HAD ALREADY LOADED
    assertThat(deleted("DELETE FROM [" + rids[0] + ", " + rids[2] + "]")).isEqualTo(1L);
    assertThat(database.existsRecord(rids[0])).isFalse();
    assertThat(database.existsRecord(rids[2])).isFalse();
    assertThat(database.existsRecord(rids[1])).isTrue();
    assertThat(count("E1")).isZero();
  }

  @Test
  void updatingTheSameRidTwiceInOneStatementAppliesBothUpdates() {
    database.getSchema().createDocumentType("Counter");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Counter").set("n", 0).save().getIdentity());

    database.transaction(() -> database.command("sql", "UPDATE [" + rid[0] + ", " + rid[0] + "] SET n = n + 1").close());

    assertThat(database.lookupByRID(rid[0], true).asDocument().getInteger("n")).isEqualTo(2);
  }

  @Test
  void aBatchLargerThanOnePullStillSkipsWhatTheStatementDeleted() {
    // EVERY RID TWICE, ACROSS MORE THAN ONE DML PULL (100) AND ONE HYDRATION BATCH: EACH RECORD IS DELETED ONCE
    database.getSchema().createDocumentType("Many");
    final List<RID> created = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < 700; i++)
        created.add(database.newDocument("Many").set("i", i).save().getIdentity());
    });
    assertThat(deleted("DELETE FROM " + twice(created))).isEqualTo(700L);
    assertThat(count("Many")).isZero();
  }

  @Test
  void deletingTheSameRidTwiceWithImplicitTransactionsDeletesOneRecord() {
    database.getSchema().createDocumentType("Doc");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Doc").set("n", 0).save().getIdentity());
    database.transaction(() -> database.newDocument("Doc").set("n", 1).save());
    database.setAutoTransaction(true);

    assertThat(deletedInAutoTransaction("DELETE FROM [" + rid[0] + ", " + rid[0] + "]")).isEqualTo(1L);
    assertThat(database.existsRecord(rid[0])).isFalse();
    assertThat(count("Doc")).isEqualTo(1L);
  }

  @Test
  void deletingAVertexAndItsEdgeWithImplicitTransactionsSucceeds() {
    database.getSchema().createVertexType("V1");
    database.getSchema().createEdgeType("E1");
    final RID[] rids = new RID[3];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("V1").set("name", "a").save();
      final MutableVertex b = database.newVertex("V1").set("name", "b").save();
      rids[0] = a.getIdentity();
      rids[1] = b.getIdentity();
      rids[2] = a.newEdge("E1", b).getIdentity();
    });
    database.setAutoTransaction(true);

    // THE VERTEX FIRST: ITS IMPLICIT TRANSACTION COMMITS THE CASCADED EDGE DELETE BEFORE THE BUFFERED EDGE IS HANDED OUT
    assertThat(deletedInAutoTransaction("DELETE FROM [" + rids[0] + ", " + rids[2] + "]")).isEqualTo(1L);
    assertThat(database.existsRecord(rids[0])).isFalse();
    assertThat(database.existsRecord(rids[2])).isFalse();
    assertThat(database.existsRecord(rids[1])).isTrue();
    assertThat(count("E1")).isZero();
  }

  @Test
  void updatingTheSameRidTwiceWithImplicitTransactionsAppliesBothUpdates() {
    database.getSchema().createDocumentType("Counter");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Counter").set("n", 0).save().getIdentity());
    database.setAutoTransaction(true);

    assertThat(database.isTransactionActive()).isFalse();
    database.command("sql", "UPDATE [" + rid[0] + ", " + rid[0] + "] SET n = n + 1").close();

    assertThat(database.lookupByRID(rid[0], true).asDocument().getInteger("n")).isEqualTo(2);
  }

  @Test
  void aBatchLargerThanOnePullStillSkipsWhatImplicitTransactionsDeleted() {
    database.getSchema().createDocumentType("Many");
    final List<RID> created = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < 700; i++)
        created.add(database.newDocument("Many").set("i", i).save().getIdentity());
    });
    database.setAutoTransaction(true);

    assertThat(deletedInAutoTransaction("DELETE FROM " + twice(created))).isEqualTo(700L);
    assertThat(count("Many")).isZero();
  }

  /**
   * Re-review of M6 (third round): a statement run as a line of a SQL script opens no statement-level transaction, so in
   * {@code autoTransaction} mode, how the server opens every database, each row's delete or update opens and commits an
   * implicit transaction of its own. Every buffered record after the first row is then handed out with NO transaction
   * active, and what the earlier rows did is already committed.
   */
  private long deletedByScriptInAutoTransaction(final String script) {
    database.setAutoTransaction(true);
    assertThat(database.isTransactionActive()).isFalse();
    final long count;
    try (final ResultSet rs = database.command("sqlscript", script)) {
      count = rs.next().<Number>getProperty("count").longValue();
    }
    assertThat(database.isTransactionActive()).isFalse();
    return count;
  }

  private void runScriptInAutoTransaction(final String script) {
    database.setAutoTransaction(true);
    assertThat(database.isTransactionActive()).isFalse();
    database.command("sqlscript", script).close();
    assertThat(database.isTransactionActive()).isFalse();
  }

  @Test
  void deletingTheSameRidTwiceWithPerRowImplicitTransactionsDeletesOneRecord() {
    database.getSchema().createDocumentType("Doc");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Doc").set("n", 0).save().getIdentity());
    database.transaction(() -> database.newDocument("Doc").set("n", 1).save());

    assertThat(deletedByScriptInAutoTransaction("DELETE FROM [" + rid[0] + ", " + rid[0] + "];")).isEqualTo(1L);

    assertThat(database.existsRecord(rid[0])).isFalse();
    assertThat(count("Doc")).isEqualTo(1L);
  }

  @Test
  void deletingAVertexAndItsEdgeWithPerRowImplicitTransactionsSucceeds() {
    database.getSchema().createVertexType("V1");
    database.getSchema().createEdgeType("E1");
    final RID[] rids = new RID[3];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("V1").set("name", "a").save();
      final MutableVertex b = database.newVertex("V1").set("name", "b").save();
      rids[0] = a.getIdentity();
      rids[1] = b.getIdentity();
      rids[2] = a.newEdge("E1", b).getIdentity();
    });

    // THE VERTEX ROW COMMITS THE CASCADED EDGE DELETE BEFORE THE BUFFERED EDGE IS HANDED OUT, WITH NO TRANSACTION ACTIVE
    assertThat(deletedByScriptInAutoTransaction("DELETE FROM [" + rids[0] + ", " + rids[2] + "];")).isEqualTo(1L);

    assertThat(database.existsRecord(rids[0])).isFalse();
    assertThat(database.existsRecord(rids[2])).isFalse();
    assertThat(database.existsRecord(rids[1])).isTrue();
    assertThat(count("E1")).isZero();
  }

  @Test
  void updatingTheSameRidTwiceWithPerRowImplicitTransactionsAppliesBothUpdates() {
    database.getSchema().createDocumentType("Counter");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Counter").set("n", 0).save().getIdentity());

    // THE SECOND ROW MUST START FROM THE IMAGE THE FIRST ROW'S IMPLICIT TRANSACTION COMMITTED
    runScriptInAutoTransaction("UPDATE [" + rid[0] + ", " + rid[0] + "] SET n = n + 1;");

    assertThat(database.lookupByRID(rid[0], true).asDocument().getInteger("n")).isEqualTo(2);
  }

  @Test
  void aBatchLargerThanOnePullStillSkipsWhatPerRowImplicitTransactionsDeleted() {
    database.getSchema().createDocumentType("Many");
    final List<RID> created = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < 700; i++)
        created.add(database.newDocument("Many").set("i", i).save().getIdentity());
    });

    assertThat(deletedByScriptInAutoTransaction("DELETE FROM " + twice(created) + ";")).isEqualTo(700L);

    assertThat(count("Many")).isZero();
  }

  @Test
  void aSelectWithImplicitTransactionsStillAnswersEveryRecord() {
    // THE READ SIDE OF THE SAME MODE: NOTHING COMMITS DURING A SELECT, SO THE BATCH IS HANDED OUT AS LOADED
    database.getSchema().createDocumentType("Read");
    final List<RID> created = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < 600; i++)
        created.add(database.newDocument("Read").set("i", i).save().getIdentity());
    });
    database.setAutoTransaction(true);

    long sum = 0;
    try (final ResultSet rs = database.query("sql", "SELECT i FROM " + twice(created))) {
      while (rs.hasNext())
        sum += rs.next().<Integer>getProperty("i");
    }
    assertThat(sum).isEqualTo(2L * 599 * 600 / 2);
  }

  @Test
  void deletingTheSameRidTwiceAcrossABatchBoundaryDeletesOneRecord() {
    database.getSchema().createDocumentType("Doc");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Doc").set("n", 0).save().getIdentity());
    database.transaction(() -> database.newDocument("Doc").set("n", 1).save());

    // BATCH 1 COMMITS THE FIRST DELETE BEFORE THE SECOND, BUFFERED COPY IS HANDED OUT
    assertThat(deleted("DELETE FROM [" + rid[0] + ", " + rid[0] + "] BATCH 1")).isEqualTo(1L);
    assertThat(database.existsRecord(rid[0])).isFalse();
    assertThat(count("Doc")).isEqualTo(1L);
  }

  @Test
  void deletingTheSameRidTwiceAcrossABatchBoundaryWithImplicitTransactionsDeletesOneRecord() {
    database.getSchema().createDocumentType("Doc");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Doc").set("n", 0).save().getIdentity());
    database.transaction(() -> database.newDocument("Doc").set("n", 1).save());
    database.setAutoTransaction(true);

    assertThat(deletedInAutoTransaction("DELETE FROM [" + rid[0] + ", " + rid[0] + "] BATCH 1")).isEqualTo(1L);
    assertThat(database.existsRecord(rid[0])).isFalse();
    assertThat(count("Doc")).isEqualTo(1L);
  }

  @Test
  void deletingAVertexAndItsEdgeAcrossABatchBoundarySucceeds() {
    database.getSchema().createVertexType("V1");
    database.getSchema().createEdgeType("E1");
    final RID[] rids = new RID[3];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("V1").set("name", "a").save();
      final MutableVertex b = database.newVertex("V1").set("name", "b").save();
      rids[0] = a.getIdentity();
      rids[1] = b.getIdentity();
      rids[2] = a.newEdge("E1", b).getIdentity();
    });
    database.setAutoTransaction(true);

    assertThat(deletedInAutoTransaction("DELETE FROM [" + rids[0] + ", " + rids[2] + "] BATCH 1")).isEqualTo(1L);
    assertThat(database.existsRecord(rids[0])).isFalse();
    assertThat(database.existsRecord(rids[2])).isFalse();
    assertThat(database.existsRecord(rids[1])).isTrue();
    assertThat(count("E1")).isZero();
  }

  @Test
  void updatingTheSameRidTwiceAcrossABatchBoundaryAppliesBothUpdates() {
    database.getSchema().createDocumentType("Counter");
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Counter").set("n", 0).save().getIdentity());
    database.setAutoTransaction(true);

    // THE SECOND ROW MUST START FROM THE IMAGE THE FIRST ONE COMMITTED, NOT FROM THE ONE THE BATCH BUFFERED
    database.command("sql", "UPDATE [" + rid[0] + ", " + rid[0] + "] SET n = n + 1 BATCH 1").close();

    assertThat(database.lookupByRID(rid[0], true).asDocument().getInteger("n")).isEqualTo(2);
  }

  @Test
  void aBatchLargerThanOnePullStillSkipsWhatEarlierBatchBoundariesCommitted() {
    database.getSchema().createDocumentType("Many");
    final List<RID> created = new ArrayList<>();
    database.transaction(() -> {
      for (int i = 0; i < 700; i++)
        created.add(database.newDocument("Many").set("i", i).save().getIdentity());
    });

    assertThat(deleted("DELETE FROM " + twice(created) + " BATCH 7")).isEqualTo(700L);
    assertThat(count("Many")).isZero();
  }
}
