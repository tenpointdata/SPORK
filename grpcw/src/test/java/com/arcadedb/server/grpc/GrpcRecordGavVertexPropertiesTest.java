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
package com.arcadedb.server.grpc;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.RID;
import com.arcadedb.graph.GAVVertex;
import com.arcadedb.graph.GraphTraversalProvider;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.server.TestServerHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A vertex bound by a Cypher expansion a Graph Analytical View serves is a {@link GAVVertex}: {@code get(name)} answers the
 * view's column-store snapshot, while {@code toMap(false)} delegates to the live OLTP record. With
 * {@code arcadedb.gavUseWhenStale=true}, or a commit landing between the snapshot pin and the serialization, the two
 * disagree, and a record read through {@code toMap(false)} would carry a value the projection of the same row did not see.
 * {@code convertToGrpcRecord} keeps the one-pass read for record-backed documents only and reads any other implementation
 * name by name, through {@code get(name)}.
 */
class GrpcRecordGavVertexPropertiesTest {
  private static final int SNAPSHOT_AGE = 30;
  private static final int LIVE_AGE     = 31;

  @TempDir
  Path root;

  private Database db;
  private RID      rid;

  @BeforeEach
  void setUp() {
    db = new DatabaseFactory(root.resolve("db").toString()).create();
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

  private ArcadeDbGrpcService service() {
    // No reaper: nothing here opens a transaction through the service
    return new ArcadeDbGrpcService("unused", TestServerHelper.unstartedServer(root.resolve("server"), new ContextConfiguration()),
        0L, 0L, 0L);
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
  void recordPropertiesAgreeWithTheViewTheProjectionRead() {
    final ArcadeDbGrpcService service = service();
    db.transaction(() -> {
      final GAVVertex vertex = new GAVVertex(rid, 0, staleView(rid), db);
      // PRECONDITION: THE VIEW AND THE RECORD DISAGREE, AS AFTER A COMMIT THE SNAPSHOT DOES NOT SEE
      assertThat(vertex.get("age")).isEqualTo(SNAPSHOT_AGE);
      assertThat(vertex.toMap(false).get("age")).isEqualTo(LIVE_AGE);

      final GrpcRecord record = service.convertToGrpcRecord(vertex, db);
      assertThat(record.getPropertiesMap().get("age").getInt32Value()).isEqualTo(SNAPSHOT_AGE);
      assertThat(record.getPropertiesMap().get("name").getStringValue()).isEqualTo("Ann");
    });
  }

  @Test
  void aRecordBackedVertexStillReadsEveryProperty() {
    final ArcadeDbGrpcService service = service();
    db.transaction(() -> {
      final GrpcRecord record = service.convertToGrpcRecord(rid.asVertex(), db);
      assertThat(record.getPropertiesMap().get("age").getInt32Value()).isEqualTo(LIVE_AGE);
      assertThat(record.getPropertiesMap().get("name").getStringValue()).isEqualTo("Ann");
    });
  }
}
