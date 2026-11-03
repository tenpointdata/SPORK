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
package com.arcadedb.graph.olap;

import com.arcadedb.TestHelper;
import com.arcadedb.engine.WALFile;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.InternalBucketNaming;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3 step 1 (docs/spork/09-f3-csr-on-followers.md): a follower applies replicated commits as page deltas, which no record listener
 * sees, so the engine tells the views which files a replicated transaction touched. A view must stop serving when the transaction
 * touched a type it covers - a vertex type, its edge-list buckets, an edge type - and keep serving when it touched only what it
 * does not read. Views here are in UPDATE MODE OFF, where "told" is plainly STALE, with no background rebuild to race.
 */
class GraphViewReplicatedChangesTest extends TestHelper {

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("RcN", 1);
    database.getSchema().createVertexType("RcOther", 1);
    database.getSchema().createEdgeType("RcE", 1);
    database.getSchema().createDocumentType("RcDoc", 1);
    database.command("sql", "CREATE PROPERTY RcDoc.k STRING");
    database.command("sql", "CREATE INDEX ON RcDoc (k) NOTUNIQUE");
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("RcN").set("name", "a").save();
      final MutableVertex b = database.newVertex("RcN").set("name", "b").save();
      a.newEdge("RcE", b).save();
      database.newVertex("RcOther").save();
      database.newDocument("RcDoc").set("k", "x").save();
    });
  }

  @Test
  void aCoveredVertexTypeMakesTheViewStale() {
    final GraphAnalyticalView view = createView();
    GraphAnalyticalViewRegistry.onReplicatedChanges(database, txTouching(bucketOf("RcN")));
    assertThat(view.getStatus()).isEqualTo(GraphAnalyticalView.Status.STALE);
  }

  @Test
  void aCoveredEdgeTypeMakesTheViewStale() {
    final GraphAnalyticalView view = createView();
    GraphAnalyticalViewRegistry.onReplicatedChanges(database, txTouching(bucketOf("RcE")));
    assertThat(view.getStatus()).isEqualTo(GraphAnalyticalView.Status.STALE);
  }

  @Test
  void theEdgeListBucketOfACoveredVertexTypeMakesTheViewStale() {
    final GraphAnalyticalView view = createView();
    final String vertexBucket = database.getSchema().getType("RcN").getBuckets(false).getFirst().getName();
    final int edgeList = database.getSchema().getBucketByName(InternalBucketNaming.outEdgesBucketName(vertexBucket)).getFileId();
    GraphAnalyticalViewRegistry.onReplicatedChanges(database, txTouching(edgeList));
    assertThat(view.getStatus()).isEqualTo(GraphAnalyticalView.Status.STALE);
  }

  @Test
  void aDocumentTypeAnIndexOrAnUncoveredVertexTypeLeaveTheViewReady() {
    final GraphAnalyticalView view = createView();
    final TypeIndex index = database.getSchema().getType("RcDoc").getIndexesByProperties("k").getFirst();
    GraphAnalyticalViewRegistry.onReplicatedChanges(database,
        txTouching(bucketOf("RcDoc"), index.getIndexesOnBuckets()[0].getFileId(), bucketOf("RcOther")));
    assertThat(view.getStatus()).isEqualTo(GraphAnalyticalView.Status.READY);
  }

  @Test
  void aDatabaseWithoutViewsIsANoOp() {
    GraphAnalyticalViewRegistry.onReplicatedChanges(database, txTouching(bucketOf("RcN")));
    assertThat(GraphAnalyticalViewRegistry.getAll(database)).isEmpty();
  }

  private GraphAnalyticalView createView() {
    database.command("sql", "CREATE GRAPH ANALYTICAL VIEW rcv VERTEX TYPES (RcN) EDGE TYPES (RcE) UPDATE MODE OFF");
    final GraphAnalyticalView view = GraphAnalyticalViewRegistry.get(database, "rcv");
    final long deadline = System.currentTimeMillis() + 60_000;
    while (!view.isReady() && System.currentTimeMillis() < deadline)
      Thread.onSpinWait();
    assertThat(view.getStatus()).isEqualTo(GraphAnalyticalView.Status.READY);
    return view;
  }

  private int bucketOf(final String typeName) {
    return database.getSchema().getType(typeName).getBuckets(false).getFirst().getFileId();
  }

  private static WALFile.WALTransaction txTouching(final int... fileIds) {
    final WALFile.WALTransaction tx = new WALFile.WALTransaction();
    tx.pages = new WALFile.WALPage[fileIds.length];
    for (int i = 0; i < fileIds.length; i++) {
      final WALFile.WALPage page = new WALFile.WALPage();
      page.fileId = fileIds[i];
      tx.pages[i] = page;
    }
    return tx;
  }
}
