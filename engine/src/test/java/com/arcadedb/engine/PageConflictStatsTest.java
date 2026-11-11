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
package com.arcadedb.engine;

import com.arcadedb.Profiler;
import com.arcadedb.TestHelper;
import com.arcadedb.database.LocalDatabase;
import com.arcadedb.database.RID;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.schema.Schema;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 5 of the SPORK stress report: page-version conflicts are attributed to the file they happen on, with how many
 * a commit-time merge absorbed, and the profiler JSON lists the top files.
 */
class PageConflictStatsTest extends TestHelper {

  @BeforeEach
  void resetStats() {
    stats().reset();
  }

  @Test
  void aConflictOnTheSameRecordIsChargedToItsBucketAsFailed() throws Exception {
    database.getSchema().createDocumentType("Doc");
    final RID rid = inTx(() -> database.newDocument("Doc").set("n", 0).save().getIdentity());

    final Throwable error = commitInterleaved(//
        () -> rid.asDocument(true).modify().set("n", 1).save(),//
        () -> rid.asDocument(true).modify().set("n", 2).save());

    assertThat(error).isInstanceOf(ConcurrentModificationException.class);

    final PageConflictStats.Entry entry = entryOf(rid.getBucketId());
    assertThat(entry).isNotNull();
    assertThat(entry.kind).isEqualTo("bucket");
    assertThat(entry.detail).isEqualTo("Doc");
    assertThat(entry.fileName).startsWith("Doc_");
    assertThat(entry.getConflicts()).isEqualTo(1);
    assertThat(entry.getMerged()).isZero();
    assertThat(entry.getFailed()).isEqualTo(1);
  }

  @Test
  void anEdgeAppendToAHubIsChargedToTheEdgeListAsMerged() throws Exception {
    database.getSchema().createVertexType("Hub");
    database.getSchema().createVertexType("Leaf");
    database.getSchema().createEdgeType("Link");

    final RID hub = inTx(() -> {
      final MutableVertex h = database.newVertex("Hub").save();
      // AN EDGE ALREADY THERE, SO BOTH TRANSACTIONS APPEND TO THE SAME EXISTING SEGMENT
      h.newEdge("Link", database.newVertex("Leaf").save());
      return h.getIdentity();
    });

    final Throwable error = commitInterleaved(//
        () -> hub.asVertex(true).newEdge("Link", database.newVertex("Leaf").save()),//
        () -> hub.asVertex(true).newEdge("Link", database.newVertex("Leaf").save()));

    assertThat(error).isNull();
    assertThat(hub.asVertex(true).countEdges(Vertex.DIRECTION.OUT, "Link")).isEqualTo(3);

    final PageConflictStats.Entry edgeList = stats().getTop(100).stream().filter(e -> "edge-list".equals(e.kind)).findFirst()
        .orElse(null);
    assertThat(edgeList).isNotNull();
    assertThat(edgeList.fileName).contains("_out_edges");
    assertThat(edgeList.getConflicts()).isGreaterThanOrEqualTo(1);
    assertThat(edgeList.getMerged()).isEqualTo(edgeList.getConflicts());
    assertThat(edgeList.getFailed()).isZero();

    // EVERY CONFLICT OF THE COMMIT WAS ABSORBED, WHATEVER FILE IT WAS ON
    for (final PageConflictStats.Entry e : stats().getTop(100))
      assertThat(e.getFailed()).as("file %s", e.fileName).isZero();
  }

  @Test
  void anIndexFileIsDescribedByItsTypeAndProperties() {
    final var type = database.getSchema().createDocumentType("Doc");
    type.createProperty("k", String.class);
    type.createProperty("text", String.class);
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "Doc", "k");
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.FULL_TEXT, false, "Doc", "text");

    int unique = 0, fullText = 0;
    for (int fileId = 0; fileId < 256; fileId++) {
      final Component c = ((LocalDatabase) database).getSchema().getEmbedded().getFileByIdIfExists(fileId);
      if (c == null)
        continue;
      final PageConflictStats.Entry e = PageConflictStats.describe((LocalDatabase) database, c.getFileId());
      if (e.kind.equals("index-unique")) {
        assertThat(e.detail).isEqualTo("Doc[k]");
        unique++;
      } else if (e.kind.equals("index-fulltext")) {
        assertThat(e.detail).isEqualTo("Doc[text]");
        fullText++;
      }
    }
    assertThat(unique).isPositive();
    assertThat(fullText).isPositive();
  }

  @Test
  void theProfilerListsTheTopFiles() throws Exception {
    database.getSchema().createDocumentType("Doc");
    final RID rid = inTx(() -> database.newDocument("Doc").set("n", 0).save().getIdentity());

    commitInterleaved(//
        () -> rid.asDocument(true).modify().set("n", 1).save(),//
        () -> rid.asDocument(true).modify().set("n", 2).save());

    final JSONArray top = Profiler.INSTANCE.toJSON().getJSONObject("pageConflictsByFile").getJSONArray("top");
    JSONObject found = null;
    for (int i = 0; i < top.length(); i++) {
      final JSONObject row = top.getJSONObject(i);
      if (database.getName().equals(row.getString("database", null)) && row.getInt("fileId", -1) == rid.getBucketId())
        found = row;
    }
    assertThat(found).isNotNull();
    assertThat(found.getString("kind")).isEqualTo("bucket");
    assertThat(found.getLong("conflicts")).isEqualTo(1L);
    assertThat(found.getLong("failed")).isEqualTo(1L);
  }

  @Test
  void aCommitWithoutConflictsRecordsNothing() {
    database.getSchema().createDocumentType("Doc");
    for (int i = 0; i < 10; i++) {
      final int n = i;
      database.transaction(() -> database.newDocument("Doc").set("n", n).save());
    }
    assertThat(stats().getTotalConflicts()).isZero();
    assertThat(stats().getTop(10)).isEmpty();
  }

  private <T> T inTx(final Callable<T> work) throws Exception {
    database.begin();
    final T result = work.call();
    database.commit();
    return result;
  }

  private PageConflictStats stats() {
    return ((LocalDatabase) database).getPageConflictStats();
  }

  private PageConflictStats.Entry entryOf(final int fileId) {
    for (final PageConflictStats.Entry e : stats().getTop(1000))
      if (e.fileId == fileId)
        return e;
    return null;
  }

  /**
   * Runs {@code first} in a transaction on another thread, commits {@code second} on this thread while that one is
   * still open, then lets the first commit. Returns what the first commit threw, or null.
   */
  private Throwable commitInterleaved(final Runnable first, final Runnable second) throws Exception {
    final CountDownLatch ready = new CountDownLatch(1);
    final CountDownLatch go = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    final Thread t = new Thread(() -> {
      try {
        database.begin();
        first.run();
        ready.countDown();
        go.await();
        database.commit();
      } catch (final Throwable e) {
        error.set(e);
        if (database.isTransactionActive())
          database.rollback();
      } finally {
        ready.countDown();
      }
    });
    t.start();
    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    database.transaction(second::run);
    go.countDown();
    t.join(30_000);
    return error.get();
  }
}
