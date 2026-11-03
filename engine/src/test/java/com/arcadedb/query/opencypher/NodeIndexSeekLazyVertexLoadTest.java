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
package com.arcadedb.query.opencypher;

import com.arcadedb.database.BaseRecord;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.Record;
import com.arcadedb.event.AfterRecordReadListener;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.opencypher.executor.operators.NodeIndexSeek;
import com.arcadedb.query.sql.executor.BasicCommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An openCypher index seek hands its vertices out without loading their content (SPORK retrieval speed, item S5): what the
 * statement reads of a vertex loads it, so a seek whose rows are only counted, identified or checked for a label never
 * copies a record, and one that reads a property, a label of a sub-type or an adjacency reads exactly what it did before.
 * <p>
 * This holds for the seeks that stream the index - a prefix of a composite key, a range, a whole key of more than
 * {@code ConsistentKeyLookup.MAX_ENTRIES} entries. A whole-key seek of fewer entries is read by {@code ConsistentKeyLookup}
 * with its records loaded against one committed state (issues #9369, #9397), and keeps loading them: the consistency it buys
 * is the record and the entry belonging together, which a lazy load cannot promise. And a seek the planner built keeps
 * re-evaluating its own equality on the vertex (issue #8537: the index key conversion is not Cypher's {@code =}), which
 * loads it: the operator is lazy, the planned statement is not yet, see the workstream notes.
 * <p>
 * What a load did besides copying the content is still done at the seek: the READ_RECORD permission on the bucket is asked
 * (a user that may not read the type learns nothing of its records, not their count, not their RIDs), and a read listener or
 * trigger on the database or the type keeps the eager load, so it sees every vertex the seek hands out.
 * <p>
 * Whether a vertex is loaded is read off its buffer, null until the content is read. The after-read event fires when a record's
 * content is read from its page, so a registered listener counts the loads - and, being a listener, turns the eager load on.
 */
class NodeIndexSeekLazyVertexLoadTest {
  private static final String DATABASE_PATH = "./target/databases/node-index-seek-lazy-load";
  private static final int    ROWS          = 600;
  private static final int    GROUPS        = 4;

  private Database            database;
  private final AtomicInteger loads = new AtomicInteger();
  private final AfterRecordReadListener counter = record -> {
    if (record instanceof Vertex)
      loads.incrementAndGet();
    return record;
  };

  @BeforeEach
  void setUp() {
    database = new DatabaseFactory(DATABASE_PATH).create();
    database.getSchema().createVertexType("Node").createProperty("id", Type.LONG);
    database.getSchema().getType("Node").createProperty("group", Type.LONG);
    database.getSchema().createVertexType("Special").addSuperType("Node");
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "Node", "id");
    // A COMPOSITE KEY: A SEEK ON group ALONE IS A PREFIX SEEK, WHICH STREAMS THE INDEX
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "Node", "group", "id");
    database.getSchema().createEdgeType("LINK");

    database.transaction(() -> {
      MutableVertex previous = null;
      for (int i = 0; i < ROWS; i++) {
        final MutableVertex v = database.newVertex(i % 10 == 0 ? "Special" : "Node");
        v.set("id", (long) i).set("group", (long) (i % GROUPS)).set("name", "n" + i).save();
        if (previous != null)
          previous.newEdge("LINK", v).save();
        previous = v;
      }
    });
  }

  @AfterEach
  void tearDown() {
    database.getEvents().unregisterListener(counter);
    database.getSchema().getType("Node").getEvents().unregisterListener(counter);
    if (database != null)
      database.drop();
  }

  @Test
  void aPrefixSeekHandsItsVerticesOutUnloaded() {
    final List<Result> rows = seek("Node", 1L);
    assertThat(rows).hasSize(ROWS / GROUPS);
    // THE IDENTITY AND THE TYPE ARE KNOWN WITHOUT THE CONTENT
    for (final Result row : rows) {
      final Vertex vertex = row.getProperty("n");
      assertThat(isLoaded(vertex)).as("the seek must not load a vertex nobody reads").isFalse();
      assertThat(vertex.getIdentity()).isNotNull();
      assertThat(vertex.getTypeName()).isIn("Node", "Special");
      assertThat(isLoaded(vertex)).isFalse();
    }
    // THE FIRST PROPERTY READ LOADS THE VERTEX
    final Vertex first = rows.getFirst().getProperty("n");
    assertThat(first.getLong("group")).isEqualTo(1L);
    assertThat(first.getString("name")).isEqualTo("n" + first.getLong("id"));
    assertThat(isLoaded(first)).isTrue();
    assertThat(isLoaded(rows.get(1).getProperty("n"))).isFalse();
  }

  @Test
  void aDatabaseReadListenerSeesEveryVertexTheSeekHandsOut() {
    // A LISTENER MAY VETO OR REPLACE A RECORD: A VERTEX HANDED OUT UNLOADED WOULD NEVER REACH IT FOR A ROW THAT IS ONLY COUNTED
    database.getEvents().registerListener(counter);
    final List<Result> rows = seek("Node", 1L);
    assertThat(rows).hasSize(ROWS / GROUPS);
    assertThat(loads.get()).as("every vertex the seek hands out passed the read listener").isEqualTo(ROWS / GROUPS);
    for (final Result row : rows)
      assertThat(isLoaded(row.getProperty("n"))).isTrue();
  }

  @Test
  void aTypeReadListenerSeesEveryVertexTheSeekHandsOut() {
    database.getSchema().getType("Node").getEvents().registerListener(counter);
    final List<Result> rows = seek("Node", 2L);
    assertThat(rows).hasSize(ROWS / GROUPS);
    // THE LISTENER IS ON Node ONLY: ITS SUB-TYPE Special HAS NONE OF ITS OWN, SO ITS VERTICES MAY STAY UNLOADED
    int nodes = 0;
    for (final Result row : rows) {
      final Vertex vertex = row.getProperty("n");
      if ("Node".equals(vertex.getTypeName())) {
        nodes++;
        assertThat(isLoaded(vertex)).as("a Node passed its type's read listener").isTrue();
      }
    }
    assertThat(loads.get()).isEqualTo(nodes);
  }

  @Test
  void aSubTypeSeekRejectsTheOtherTypesWithoutLoadingThem() {
    // THE INDEX IS THE PARENT'S: THE ENTRIES OF THE PLAIN NODES ARE REJECTED BY THEIR BUCKET. A SPECIAL IS EVERY 10TH VERTEX, IN
    // GROUP 0 EVERY 20TH
    final List<Result> rows = seek("Special", 0L);
    assertThat(rows).hasSize(ROWS / 20);
    for (final Result row : rows)
      assertThat(isLoaded(row.getProperty("n"))).isFalse();

    // WITH A READ LISTENER THE SPECIALS HANDED OUT ARE LOADED, AND STILL NONE OF THE NODES THE BUCKET REJECTED
    database.getEvents().registerListener(counter);
    assertThat(seek("Special", 0L)).hasSize(ROWS / 20);
    assertThat(loads.get()).as("only the vertices handed out were read").isEqualTo(ROWS / 20);
  }

  @Test
  void aWholeKeySeekIsStillReadConsistentlyWithItsRecords() {
    database.getEvents().registerListener(counter);
    // THE CONTRACT OF ConsistentKeyLookup (#9369, #9397): the records of a whole-key lookup are loaded with their entries
    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    final NodeIndexSeek seek = new NodeIndexSeek("n", "Node", "id", 7L, "Node[id]", 1.0, 1L);
    final List<Result> rows = collect(seek.execute(context, -1));
    assertThat(rows).hasSize(1);
    assertThat(loads.get()).isEqualTo(1);
  }

  @Test
  void aPlannedSeekStillReadsWhatItsFilterNeeds() {
    database.getEvents().registerListener(counter);
    // THE PLANNER PUSHES THE SEEK'S OWN EQUALITY DOWN AS A FILTER ON THE VERTEX, WHICH LOADS IT ONCE PER ROW: THE SAME
    // READING AS BEFORE, NEITHER MORE NOR LESS
    final List<Result> rows = query("MATCH (n:Node) WHERE n.group = 1 RETURN count(n) AS c");
    assertThat(rows.getFirst().<Long>getProperty("c")).isEqualTo((long) ROWS / GROUPS);
    assertThat(loads.get()).isEqualTo(ROWS / GROUPS);
  }

  @Test
  void aPropertyReadLoadsEachVertexOnce() {
    database.getEvents().registerListener(counter);
    final List<Result> rows = query("MATCH (n:Node) WHERE n.group = 2 RETURN n.id AS id, n.name AS name ORDER BY id");
    assertThat(rows).hasSize(ROWS / GROUPS);
    for (final Result row : rows)
      assertThat(row.<String>getProperty("name")).isEqualTo("n" + row.<Long>getProperty("id"));
    assertThat(loads.get()).isEqualTo(ROWS / GROUPS);
  }

  @Test
  void aPushedDownFilterReadsTheVertex() {
    final List<Result> rows = query("MATCH (n:Node) WHERE n.group = 3 AND n.name = 'n7' RETURN n.id AS id");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().<Long>getProperty("id")).isEqualTo(7L);
  }

  @Test
  void anExpansionFromTheSeekedVertexReadsItsAdjacency() {
    final List<Result> rows = query("MATCH (n:Node)-[:LINK]->(m) WHERE n.group = 1 AND n.id = 5 RETURN m.id AS id, n.name AS name");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().<Long>getProperty("id")).isEqualTo(6L);
    assertThat(rows.getFirst().<String>getProperty("name")).isEqualTo("n5");
  }

  @Test
  void aReturnedVertexCarriesItsProperties() {
    final List<Result> rows = query("MATCH (n:Node) WHERE n.group = 1 AND n.id = 9 RETURN n");
    assertThat(rows).hasSize(1);
    final Vertex vertex = (Vertex) rows.getFirst().<Record>getProperty("n");
    assertThat(vertex.getString("name")).isEqualTo("n9");
    assertThat(vertex.getTypeName()).isEqualTo("Node");
  }

  /** The vertex's content has been read: its buffer is set. */
  private static boolean isLoaded(final Vertex vertex) {
    return ((BaseRecord) vertex).getBuffer() != null;
  }

  /** The rows of the operator alone, a prefix seek on the composite index with no filter pushed down. */
  private List<Result> seek(final String label, final long group) {
    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    final NodeIndexSeek seek = new NodeIndexSeek("n", label, "group", group, "Node[group,id]", List.of("group", "id"),
        List.<Object>of(group), 1.0, 1L);
    return collect(seek.execute(context, -1));
  }

  private static List<Result> collect(final ResultSet resultSet) {
    final List<Result> rows = new ArrayList<>();
    try (resultSet) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private List<Result> query(final String cypher) {
    try (final ResultSet resultSet = database.query("opencypher", cypher)) {
      return collect(resultSet);
    }
  }
}
