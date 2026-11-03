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
package com.arcadedb.bolt;

import com.arcadedb.bolt.structure.BoltNode;
import com.arcadedb.bolt.structure.BoltStructureMapper;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.RID;
import com.arcadedb.graph.GAVVertex;
import com.arcadedb.graph.GraphTraversalProvider;
import com.arcadedb.graph.MutableVertex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node bound by a Cypher expansion a Graph Analytical View serves is a {@link GAVVertex}: {@code get(name)} answers the
 * view's column-store snapshot, while {@code toMap(false)} delegates to the live OLTP record. With
 * {@code arcadedb.gavUseWhenStale=true}, or a commit landing between the snapshot pin and the serialization, the two
 * disagree, so a node's properties read through {@code toMap(false)} could tell a Bolt client one age for {@code b} in
 * {@code RETURN b.age, b} and another in {@code b} itself. The one-pass read is kept for record-backed documents only; any
 * other implementation is read name by name, through the same {@code get(name)} the projection used.
 */
class BoltGavVertexPropertiesTest {
  private static final String DB_PATH       = "./target/databases/BoltGavVertexPropertiesTest";
  private static final int    SNAPSHOT_AGE  = 30;
  private static final int    LIVE_AGE      = 31;
  private Database            db;
  private RID                 rid;

  @BeforeEach
  void setUp() {
    final DatabaseFactory factory = new DatabaseFactory(DB_PATH);
    if (factory.exists())
      factory.open().drop();
    db = factory.create();
    db.getSchema().createVertexType("Person");
    db.transaction(() -> {
      final MutableVertex v = db.newVertex("Person").set("name", "Ann").set("age", LIVE_AGE).save();
      rid = v.getIdentity();
    });
  }

  @AfterEach
  void tearDown() {
    if (db != null && db.isOpen())
      db.drop();
  }

  /** A view whose column store holds {@code age} at its snapshot value and nothing else. */
  private static GraphTraversalProvider staleView(final RID rid) {
    final InvocationHandler handler = (proxy, method, args) -> {
      if (method.getName().equals("getProperty"))
        return "age".equals(args[1]) ? SNAPSHOT_AGE : null;
      if (method.getName().equals("getRID"))
        return rid;
      if (method.getName().equals("getNodeId"))
        return 0;
      if (method.isDefault())
        return InvocationHandler.invokeDefault(proxy, method, args);
      throw new UnsupportedOperationException(method.getName());
    };
    return (GraphTraversalProvider) Proxy.newProxyInstance(GraphTraversalProvider.class.getClassLoader(),
        new Class<?>[] { GraphTraversalProvider.class }, handler);
  }

  @Test
  void nodePropertiesAgreeWithTheViewTheProjectionRead() {
    db.transaction(() -> {
      final GAVVertex vertex = new GAVVertex(rid, 0, staleView(rid), db);
      // PRECONDITION: THE VIEW AND THE RECORD DISAGREE, AS AFTER A COMMIT THE SNAPSHOT DOES NOT SEE
      assertThat(vertex.get("age")).isEqualTo(SNAPSHOT_AGE);
      assertThat(vertex.toMap(false).get("age")).isEqualTo(LIVE_AGE);

      final Map<String, Object> properties = BoltStructureMapper.toProperties(vertex);
      assertThat(properties.get("age")).isEqualTo(BoltStructureMapper.toPackStreamValue(vertex.get("age")));
      assertThat(properties.get("name")).isEqualTo("Ann");
      assertThat(properties).containsOnlyKeys("name", "age");

      final BoltNode node = BoltStructureMapper.toNode(vertex);
      assertThat(node.getProperties().get("age")).isEqualTo(BoltStructureMapper.toPackStreamValue(SNAPSHOT_AGE));
    });
  }

  @Test
  void aRecordBackedVertexStillReadsEveryProperty() {
    db.transaction(() -> {
      final Map<String, Object> properties = BoltStructureMapper.toProperties(rid.asVertex());
      assertThat(properties).containsOnlyKeys("name", "age");
      assertThat(properties.get("age")).isEqualTo(BoltStructureMapper.toPackStreamValue(LIVE_AGE));
    });
  }
}
