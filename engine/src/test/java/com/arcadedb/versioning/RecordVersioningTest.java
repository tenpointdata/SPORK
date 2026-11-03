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
package com.arcadedb.versioning;

import com.arcadedb.TestHelper;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.graph.MutableEdge;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native, engine-managed record versioning (system-versioned types): the current record stays in place and the engine keeps every
 * superseded version in its own history store, read back through {@link RecordVersioning} and the {@code history.*} SQL functions.
 */
class RecordVersioningTest extends TestHelper {

  private RecordVersioning versioning;

  @BeforeEach
  void setUpTypes() {
    database.transaction(() -> {
      database.getSchema().createVertexType("Person").setVersioned(true);
      database.getSchema().createVertexType("Identity").setVersioned(true);
      database.getSchema().createEdgeType("MEMBER_OF").setVersioned(true);
      database.getSchema().createDocumentType("Plain");
    });
    versioning = RecordVersioning.of(database);
  }

  @Test
  void updatesKeepEveryPriorVersion() {
    final RID rid = createPerson("Ann", 30);
    updatePerson(rid, "Ann", 31);
    updatePerson(rid, "Anna", 31);

    final List<RecordVersion> versions = versioning.history(rid);
    assertThat(versions).hasSize(3);

    assertThat(versions.get(0).get("name")).isEqualTo("Ann");
    assertThat(versions.get(0).get("age")).isEqualTo(30);
    assertThat(versions.get(1).get("age")).isEqualTo(31);
    assertThat(versions.get(2).get("name")).isEqualTo("Anna");

    assertThat(versions.get(0).getVersion()).isEqualTo(1);
    assertThat(versions.get(2).getVersion()).isEqualTo(3);
    assertThat(versions.get(2).isCurrent()).isTrue();
    assertThat(versions.get(0).isCurrent()).isFalse();

    // contiguous intervals: each version ends exactly when the next one starts
    assertThat(versions.get(0).getTxToMicros()).isEqualTo(versions.get(1).getTxFromMicros());
    assertThat(versions.get(1).getTxToMicros()).isEqualTo(versions.get(2).getTxFromMicros());
    assertThat(versions.get(0).getTxFromMicros()).isLessThan(versions.get(0).getTxToMicros());
    assertThat(versions.get(2).getTxToMicros()).isEqualTo(Long.MAX_VALUE);

    for (final RecordVersion v : versions) {
      assertThat(v.getIdentity()).isEqualTo(rid);
      assertThat(v.getTypeName()).isEqualTo("Person");
    }
  }

  @Test
  void historyIsNotPartOfTheGraph() {
    final RID rid = createPerson("Ann", 30);
    updatePerson(rid, "Ann", 31);

    // the current record stays the only Person, with the same RID, and gains no edge
    assertThat(database.countType("Person", true)).isEqualTo(1);
    final Vertex current = rid.asVertex();
    assertThat(current.get("age")).isEqualTo(31);
    assertThat(current.countEdges(Vertex.DIRECTION.BOTH, null)).isZero();
  }

  @Test
  void asOfAnswersTheVersionValidAtAnInstant() {
    final RID rid = createPerson("Ann", 30);
    updatePerson(rid, "Ann", 31);
    updatePerson(rid, "Ann", 32);

    final List<RecordVersion> versions = versioning.history(rid);
    final long created = versions.get(0).getTxFromMicros();
    final long firstUpdate = versions.get(1).getTxFromMicros();
    final long secondUpdate = versions.get(2).getTxFromMicros();

    assertThat(versioning.asOfMicros(rid, created - 1)).isNull();
    assertThat(versioning.asOfMicros(rid, created).get("age")).isEqualTo(30);
    assertThat(versioning.asOfMicros(rid, firstUpdate - 1).get("age")).isEqualTo(30);
    assertThat(versioning.asOfMicros(rid, firstUpdate).get("age")).isEqualTo(31);
    assertThat(versioning.asOfMicros(rid, secondUpdate - 1).get("age")).isEqualTo(31);
    assertThat(versioning.asOfMicros(rid, secondUpdate).get("age")).isEqualTo(32);
    assertThat(versioning.asOfMicros(rid, Long.MAX_VALUE - 1).isCurrent()).isTrue();

    // the millisecond API answers the state at the END of that millisecond
    assertThat(versioning.asOf(rid, System.currentTimeMillis()).get("age")).isEqualTo(32);
    assertThat(versioning.asOf(rid, created / 1000 - 1)).isNull();
  }

  @Test
  void manyUpdatesInOneTransactionMakeOneVersion() {
    final RID rid = createPerson("Ann", 30);
    database.transaction(() -> {
      for (int i = 31; i <= 35; i++) {
        final MutableDocument doc = rid.asDocument(true).modify();
        doc.set("age", i);
        doc.save();
      }
    });

    final List<RecordVersion> versions = versioning.history(rid);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).get("age")).isEqualTo(30);
    assertThat(versions.get(1).get("age")).isEqualTo(35);
  }

  @Test
  void aRecordCreatedAndUpdatedInOneTransactionHasOneVersion() {
    final RID[] rid = new RID[1];
    database.transaction(() -> {
      final MutableVertex v = database.newVertex("Person").set("name", "Ann", "age", 30).save();
      v.set("age", 31).save();
      rid[0] = v.getIdentity();
    });

    final List<RecordVersion> versions = versioning.history(rid[0]);
    assertThat(versions).hasSize(1);
    assertThat(versions.get(0).get("age")).isEqualTo(31);
    assertThat(versioning.historyEntryCount()).isZero();
  }

  @Test
  void deleteKeepsTheHistoryAndEndsTheLastVersion() {
    final RID rid = createPerson("Ann", 30);
    updatePerson(rid, "Ann", 31);
    database.transaction(() -> rid.asVertex().delete());

    final List<RecordVersion> versions = versioning.history(rid);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(1).get("age")).isEqualTo(31);
    assertThat(versions.get(1).isCurrent()).isFalse();
    assertThat(versions.get(1).getTxToMicros()).isLessThan(Long.MAX_VALUE);

    assertThat(versioning.asOfMicros(rid, versions.get(1).getTxToMicros())).isNull();
    assertThat(versioning.asOfMicros(rid, versions.get(1).getTxToMicros() - 1).get("age")).isEqualTo(31);
  }

  @Test
  void updateThenDeleteInOneTransactionRecordsOnlyTheCommittedImage() {
    final RID rid = createPerson("Ann", 30);
    database.transaction(() -> {
      rid.asVertex().modify().set("age", 99).save();
      rid.asVertex().delete();
    });

    final List<RecordVersion> versions = versioning.history(rid);
    assertThat(versions).hasSize(1);
    assertThat(versions.get(0).get("age")).isEqualTo(30);
    assertThat(versions.get(0).isCurrent()).isFalse();
  }

  @Test
  void rollbackDiscardsTheHistoryEntries() {
    final RID rid = createPerson("Ann", 30);
    database.begin();
    rid.asVertex().modify().set("age", 31).save();
    database.rollback();

    assertThat(versioning.history(rid)).hasSize(1);
    assertThat(versioning.historyEntryCount()).isZero();
    assertThat(rid.asVertex().get("age")).isEqualTo(30);
  }

  @Test
  void nonVersionedTypesWriteNoHistory() {
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newDocument("Plain").set("x", 1).save().getIdentity());
    database.transaction(() -> rid[0].asDocument(true).modify().set("x", 2).save());
    database.transaction(() -> rid[0].asDocument().delete());

    assertThat(versioning.historyEntryCount()).isZero();
    assertThat(versioning.history(rid[0])).isEmpty();
    assertThat(database.getSchema().getType("Plain").existsProperty(RecordVersioning.TX_FROM_PROPERTY)).isFalse();
  }

  @Test
  void addingEdgesToAVersionedVertexIsNotAVersion() {
    final RID ann = createPerson("Ann", 30);
    final RID id = createIdentity("I-1");
    database.transaction(() -> {
      for (int i = 0; i < 50; i++)
        ann.asVertex().newEdge("MEMBER_OF", id.asVertex(), "n", i);
    });

    assertThat(versioning.history(ann)).hasSize(1);
    assertThat(versioning.history(id)).hasSize(1);
  }

  @Test
  void edgesAsOfReplaysMembershipChanges() {
    final RID ann = createPerson("Ann", 30);
    final RID idA = createIdentity("A");
    final RID idB = createIdentity("B");

    final RID[] edge = new RID[1];
    database.transaction(() -> edge[0] = ann.asVertex().newEdge("MEMBER_OF", idA.asVertex(), "confidence", 0.7).getIdentity());
    final long joinedA = versioning.history(edge[0]).get(0).getTxFromMicros();

    database.transaction(() -> edge[0].asEdge().modify().set("confidence", 0.9).save());
    final long raised = versioning.history(edge[0]).get(1).getTxFromMicros();

    // split: the membership in A ends (the edge is DELETED, not kept with a validTo) and one in B starts
    final RID[] edgeB = new RID[1];
    database.transaction(() -> {
      edge[0].asEdge().delete();
      edgeB[0] = ann.asVertex().newEdge("MEMBER_OF", idB.asVertex(), "confidence", 0.8).getIdentity();
    });
    final long split = versioning.history(edgeB[0]).get(0).getTxFromMicros();

    // the live graph holds only the live membership
    assertThat(ann.asVertex().countEdges(Vertex.DIRECTION.OUT, "MEMBER_OF")).isEqualTo(1);

    assertThat(versioning.edgesAsOfMicros(ann, joinedA - 1, Vertex.DIRECTION.OUT, "MEMBER_OF")).isEmpty();

    List<RecordVersion> at = versioning.edgesAsOfMicros(ann, joinedA, Vertex.DIRECTION.OUT, "MEMBER_OF");
    assertThat(at).hasSize(1);
    assertThat(at.get(0).getIn()).isEqualTo(idA);
    assertThat(at.get(0).getOut()).isEqualTo(ann);
    assertThat(at.get(0).get("confidence")).isEqualTo(0.7);

    at = versioning.edgesAsOfMicros(ann, raised, Vertex.DIRECTION.OUT, "MEMBER_OF");
    assertThat(at).hasSize(1);
    assertThat(at.get(0).get("confidence")).isEqualTo(0.9);

    at = versioning.edgesAsOfMicros(ann, split, Vertex.DIRECTION.OUT, "MEMBER_OF");
    assertThat(at).hasSize(1);
    assertThat(at.get(0).getIn()).isEqualTo(idB);
    assertThat(at.get(0).isCurrent()).isTrue();

    // the other side sees the same history
    assertThat(versioning.edgesAsOfMicros(idA, raised, Vertex.DIRECTION.IN, "MEMBER_OF")).hasSize(1);
    assertThat(versioning.edgesAsOfMicros(idA, split, Vertex.DIRECTION.IN, "MEMBER_OF")).isEmpty();
    assertThat(versioning.edgesAsOfMicros(idA, raised, Vertex.DIRECTION.BOTH, null)).hasSize(1);
  }

  @Test
  void deletingAVertexKeepsTheHistoryOfItsEdges() {
    final RID ann = createPerson("Ann", 30);
    final RID id = createIdentity("A");
    final RID[] edge = new RID[1];
    database.transaction(() -> edge[0] = ann.asVertex().newEdge("MEMBER_OF", id.asVertex()).getIdentity());
    final long before = versioning.history(edge[0]).get(0).getTxFromMicros();

    database.transaction(() -> ann.asVertex().delete());

    assertThat(versioning.history(edge[0])).hasSize(1);
    assertThat(versioning.edgesAsOfMicros(id, before, Vertex.DIRECTION.IN, "MEMBER_OF")).hasSize(1);
    assertThat(versioning.asOfMicros(ann, before).get("name")).isEqualTo("Ann");
  }

  @Test
  void theStampIsEngineOwned() {
    final RID rid = createPerson("Ann", 30);
    final DocumentType person = database.getSchema().getType("Person");
    assertThat(person.existsProperty(RecordVersioning.TX_FROM_PROPERTY)).isTrue();
    assertThat(person.getProperty(RecordVersioning.TX_FROM_PROPERTY).getType()).isEqualTo(Type.LONG);

    assertThatThrownBy(() -> database.transaction(
        () -> database.command("sql", "UPDATE " + rid + " SET " + RecordVersioning.TX_FROM_PROPERTY + " = 1")))
        .isInstanceOf(RuntimeException.class);

    // a client value on create is overwritten by the engine's stamp
    final RID[] forged = new RID[1];
    database.transaction(() -> forged[0] = database.newVertex("Person").set(RecordVersioning.TX_FROM_PROPERTY, 1L).save().getIdentity());
    assertThat((Long) forged[0].asVertex().get(RecordVersioning.TX_FROM_PROPERTY)).isGreaterThan(1L);
  }

  @Test
  void theHistoryStoreRefusesClientWrites() {
    final RID rid = createPerson("Ann", 30);
    updatePerson(rid, "Ann", 31);
    assertThat(versioning.historyEntryCount()).isEqualTo(1);

    assertThatThrownBy(() -> database.transaction(
        () -> database.command("sql", "UPDATE " + RecordVersioning.HISTORY_TYPE + " SET txTo = 0")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> database.transaction(
        () -> database.command("sql", "DELETE FROM " + RecordVersioning.HISTORY_TYPE)))
        .isInstanceOf(RuntimeException.class);
    assertThat(versioning.historyEntryCount()).isEqualTo(1);
  }

  @Test
  void purgeRemovesARecordsHistory() {
    final RID ann = createPerson("Ann", 30);
    updatePerson(ann, "Ann", 31);
    updatePerson(ann, "Ann", 32);
    final RID bob = createPerson("Bob", 40);
    updatePerson(bob, "Bob", 41);

    final long[] purged = new long[1];
    database.transaction(() -> purged[0] = versioning.purge(ann));
    assertThat(purged[0]).isEqualTo(2);
    assertThat(versioning.history(ann)).hasSize(1);
    assertThat(versioning.history(bob)).hasSize(2);
  }

  @Test
  void versionedFlagSurvivesReopenAndIsInherited() {
    database.transaction(() -> database.getSchema().createVertexType("Employee").addSuperType("Person"));
    assertThat(database.getSchema().getType("Employee").isVersioned()).isTrue();

    reopenDatabase();
    versioning = RecordVersioning.of(database);

    assertThat(database.getSchema().getType("Person").isVersioned()).isTrue();
    assertThat(database.getSchema().getType("Employee").isVersioned()).isTrue();
    assertThat(database.getSchema().getType("Plain").isVersioned()).isFalse();

    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newVertex("Employee").set("name", "Eve").save().getIdentity());
    database.transaction(() -> rid[0].asVertex().modify().set("name", "Eva").save());
    assertThat(versioning.history(rid[0])).hasSize(2);
    assertThat(versioning.history(rid[0]).get(0).getTypeName()).isEqualTo("Employee");
  }

  @Test
  void sqlAlterTypeTurnsVersioningOnAndOff() {
    database.command("sql", "CREATE VERTEX TYPE Phone");
    assertThat(database.getSchema().getType("Phone").isVersioned()).isFalse();

    database.command("sql", "ALTER TYPE Phone VERSIONED true");
    assertThat(database.getSchema().getType("Phone").isVersioned()).isTrue();

    database.command("sql", "ALTER TYPE Phone VERSIONED false");
    assertThat(database.getSchema().getType("Phone").isVersioned()).isFalse();
  }

  @Test
  void lightweightEdgeTypesCannotBeVersioned() {
    database.command("sql", "CREATE EDGE TYPE Light LIGHTWEIGHT");
    assertThatThrownBy(() -> database.getSchema().getType("Light").setVersioned(true)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void sqlFunctionsReadTheHistory() {
    final RID rid = createPerson("Ann", 30);
    // the millisecond API resolves to the end of a millisecond: keep the two versions in different ones
    nextMillisecond();
    updatePerson(rid, "Ann", 31);
    final long firstUpdate = versioning.history(rid).get(1).getTxFromMicros();

    try (final ResultSet rs = database.query("sql", "SELECT history.of(@rid) AS h FROM Person")) {
      final List<Map<String, Object>> h = rs.next().getProperty("h");
      assertThat(h).hasSize(2);
      assertThat(h.get(0).get("age")).isEqualTo(30);
      assertThat(h.get(0).get("@version")).isEqualTo(1);
      assertThat(h.get(0).get("@txTo")).isInstanceOf(Date.class);
      assertThat(h.get(1).get("@current")).isEqualTo(true);
      assertThat(h.get(1).get("@txTo")).isNull();
    }

    // epoch millis: the instant before the update's millisecond still sees the first version
    try (final ResultSet rs = database.query("sql", "SELECT history.asOf(?, ?) AS v", rid, firstUpdate / 1000 - 1)) {
      final Map<String, Object> v = rs.next().getProperty("v");
      assertThat(v.get("age")).isEqualTo(30);
      assertThat(v.get("@rid")).isEqualTo(rid);
    }
    try (final ResultSet rs = database.query("sql", "SELECT history.asOf(?, ?) AS v", rid, new Date())) {
      final Map<String, Object> v = rs.next().getProperty("v");
      assertThat(v.get("age")).isEqualTo(31);
    }
  }

  @Test
  void sqlEdgesAndPurgeFunctions() {
    final RID ann = createPerson("Ann", 30);
    final RID id = createIdentity("A");
    final RID[] edge = new RID[1];
    database.transaction(() -> edge[0] = ann.asVertex().newEdge("MEMBER_OF", id.asVertex(), "confidence", 0.7).getIdentity());
    final long joinedMillis = versioning.history(edge[0]).get(0).getTxFromMicros() / 1000;
    nextMillisecond();
    database.transaction(() -> database.command("sql", "DELETE FROM MEMBER_OF"));

    assertThat(versioning.edgesAsOfMicros(ann, Long.MAX_VALUE - 1, Vertex.DIRECTION.OUT, null)).isEmpty();

    try (final ResultSet rs = database.query("sql", "SELECT history.edges(?, ?, 'out', 'MEMBER_OF') AS e", ann, joinedMillis)) {
      final Result row = rs.next();
      final List<Map<String, Object>> e = row.getProperty("e");
      assertThat(e).hasSize(1);
      assertThat(e.get(0).get("@in")).isEqualTo(id);
      assertThat(e.get(0).get("@type")).isEqualTo("MEMBER_OF");
      assertThat(e.get(0).get("confidence")).isEqualTo(0.7);
      assertThat(e.get(0).get("@current")).isEqualTo(false);
    }
    try (final ResultSet rs = database.query("sql", "SELECT history.edges(?, ?, 'out', 'MEMBER_OF') AS e", ann, new Date())) {
      assertThat(rs.next().<List<?>>getProperty("e")).isEmpty();
    }

    updatePerson(ann, "Ann", 31);
    database.transaction(() -> {
      try (final ResultSet rs = database.command("sql", "SELECT history.purge(?) AS n", ann)) {
        assertThat(rs.next().<Long>getProperty("n")).isEqualTo(1L);
      }
    });
    assertThat(versioning.history(ann)).hasSize(1);
  }

  @Test
  void aFailedStatementThatVersionedARecordDoesNotLoseItsHistory() {
    final RID ann = withAgeLimit();
    final long entriesBefore = versioning.historyEntryCount();

    database.begin();
    // keeps Ann's committed image in the history store, then fails validating Bob: the statement is taken back, entry included
    assertThatThrownBy(() -> database.command("sql", "UPDATE Person SET age = age + 50").close()).isInstanceOf(ValidationException.class);
    assertThat(ann.asVertex().get("age")).isEqualTo(30);
    // the transaction goes on: this update must keep the committed image again, not trust the entry the failed statement wrote
    database.command("sql", "UPDATE Person SET age = 31 WHERE name = 'Ann'").close();
    database.commit();

    final List<RecordVersion> versions = versioning.history(ann);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).get("age")).isEqualTo(30);
    assertThat(versions.get(1).get("age")).isEqualTo(31);
    assertThat(versions.get(0).getTxToMicros()).isEqualTo(versions.get(1).getTxFromMicros());
    assertThat(versioning.historyEntryCount()).isEqualTo(entriesBefore + 1);
  }

  @Test
  void aFailedStatementAfterAVersioningOneKeepsTheFirstEntry() {
    final RID ann = withAgeLimit();
    final long entriesBefore = versioning.historyEntryCount();

    database.begin();
    database.command("sql", "UPDATE Person SET age = 31 WHERE name = 'Ann'").close();
    assertThatThrownBy(() -> database.command("sql", "UPDATE Person SET age = age + 50").close()).isInstanceOf(ValidationException.class);
    database.command("sql", "UPDATE Person SET age = 32 WHERE name = 'Ann'").close();
    database.commit();

    final List<RecordVersion> versions = versioning.history(ann);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).get("age")).isEqualTo(30);
    assertThat(versions.get(1).get("age")).isEqualTo(32);
    assertThat(versioning.historyEntryCount()).isEqualTo(entriesBefore + 1);
  }

  /** Ann (30) then Bob (60) under an age limit of 100: adding 50 to every Person passes Ann, then fails on Bob in the same statement. */
  private RID withAgeLimit() {
    database.command("sql", "CREATE PROPERTY Person.age INTEGER (MAX 100)");
    final RID ann = createPerson("Ann", 30);
    createPerson("Bob", 60);
    return ann;
  }

  @Test
  void aFractionalInstantIsRefusedNotTruncated() {
    final RID rid = createPerson("Ann", 30);
    assertThatThrownBy(() -> {
      try (final ResultSet rs = database.query("sql", "SELECT history.asOf(?, 2.5) AS v", rid)) {
        rs.next();
      }
    }).hasMessageContaining("whole number");
  }

  @Test
  void aSecondUpdateInTheTransactionKeepsTheEngineStamp() {
    final RID rid = createPerson("Ann", 30);
    database.transaction(() -> {
      // two separate handles, each loaded after the previous save: the second carries the stamp the first save set
      rid.asVertex().modify().set("age", 31).save();
      rid.asVertex().modify().set("age", 32).save();
      database.command("sql", "UPDATE " + rid + " SET age = 33");
    });
    final List<RecordVersion> versions = versioning.history(rid);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(1).get("age")).isEqualTo(33);
  }

  @Test
  void cypherCallsTheSameFunctions() {
    final RID rid = createPerson("Ann", 30);
    updatePerson(rid, "Ann", 31);

    try (final ResultSet rs = database.query("opencypher", "MATCH (p:Person) RETURN history.of(p) AS h")) {
      final List<Map<String, Object>> h = rs.next().getProperty("h");
      assertThat(h).hasSize(2);
      assertThat(h.get(0).get("age")).isEqualTo(30);
    }
  }

  private static void nextMillisecond() {
    // spins for at most one millisecond: no sleep, so the test cannot overshoot on a loaded machine
    final long now = System.currentTimeMillis();
    while (System.currentTimeMillis() == now)
      Thread.onSpinWait();
  }

  private RID createPerson(final String name, final int age) {
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newVertex("Person").set("name", name, "age", age).save().getIdentity());
    return rid[0];
  }

  private RID createIdentity(final String key) {
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newVertex("Identity").set("key", key).save().getIdentity());
    return rid[0];
  }

  private void updatePerson(final RID rid, final String name, final int age) {
    database.transaction(() -> rid.asVertex().modify().set("name", name, "age", age).save());
  }
}
