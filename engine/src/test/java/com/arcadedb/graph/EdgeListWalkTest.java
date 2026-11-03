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
package com.arcadedb.graph;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.LocalDatabase;
import com.arcadedb.database.RID;
import com.arcadedb.event.BeforeRecordReadListener;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retrieval speed S6 and S7 on the edge list: the walks step on {@link EdgeSegment#nextEntry} (four numbers per entry, no RID
 * unless one is handed out) and hop from chunk to chunk over read-only views of the chunks' pages
 * ({@link EdgeLinkedList#readChunkForWalk}), where the transaction's own written copy of a chunk wins. And review finding F4: a
 * chunk created to hold an entry never drops it silently.
 */
class EdgeListWalkTest extends TestHelper {

  @Override
  protected void beginTest() {
    database.transaction(() -> {
      database.getSchema().createVertexType("Hub");
      database.getSchema().createVertexType("Leaf");
      database.getSchema().createEdgeType("LINK");
    });
  }

  @Test
  void aPageViewOfAChunkRefusesEveryWrite() {
    final RID hub = newHub();
    link(hub, 10);
    final EdgeSegment view = headSegment(hub);
    assertThat(view.count(null)).isEqualTo(10);
    assertThatThrownBy(() -> view.add(new RID(1, 1), new RID(2, 2))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> view.addAtEnd(new RID(1, 1), new RID(2, 2))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> view.removeEdge(new RID(1, 1))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> view.removeEntry(MutableEdgeSegment.CONTENT_START_POSITION, view.getUsed()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ((MutableEdgeSegment) view).setPrevious(new RID(1, 1))).isInstanceOf(IllegalStateException.class);
    // A COPY OF IT IS AN ORDINARY, WRITABLE SEGMENT
    final EdgeSegment copy = view.copy();
    assertThat(copy.add(new RID(1, 1), new RID(2, 2))).isTrue();
    assertThat(copy.count(null)).isEqualTo(11);
    assertThat(view.count(null)).isEqualTo(10);
  }

  @Test
  void nextEntryDecodesWhatTheRidReadersDecode() {
    final MutableEdgeSegment segment = new MutableEdgeSegment((DatabaseInternal) database, 512);
    // SMALL, MULTI-BYTE AND NEGATIVE (LIGHTWEIGHT MARKER) NUMBERS, IN BOTH APPEND ORDERS
    assertThat(segment.add(new RID(3, 7), new RID(2, 1))).isTrue();
    assertThat(segment.add(new RID(130, 1L << 40), new RID(65_000, 123_456_789L))).isTrue();
    assertThat(segment.addAtEnd(new RID(9, -1), new RID(4, 99))).isTrue();
    assertThat(segment.addAtEndDirect(1_000_000, Long.MAX_VALUE / 3, 5, 0)).isTrue();

    final long[] entry = new long[4];
    final AtomicInteger cursor = new AtomicInteger(MutableEdgeSegment.CONTENT_START_POSITION);
    int position = MutableEdgeSegment.CONTENT_START_POSITION;
    for (int i = 0; i < 4; i++) {
      final RID edge = segment.getRID(cursor);
      final RID vertex = segment.getRID(cursor);
      position = segment.nextEntry(position, entry);
      assertThat(entry[0]).isEqualTo(edge.getBucketId());
      assertThat(entry[1]).isEqualTo(edge.getPosition());
      assertThat(entry[2]).isEqualTo(vertex.getBucketId());
      assertThat(entry[3]).isEqualTo(vertex.getPosition());
      assertThat(position).isEqualTo(cursor.get());
    }
    assertThat(position).isEqualTo(segment.getUsed());
    assertThat(segment.count(null)).isEqualTo(4);
    assertThat(segment.containsEdge(new RID(130, 1L << 40))).isTrue();
    assertThat(segment.containsLightEdge(9, new RID(4, 99))).isTrue();
    assertThat(segment.countLightEdges(9, new RID(4, 99))).isEqualTo(1);
    assertThat(segment.getFirstEdgeConnectedToVertex(new RID(65_000, 123_456_789L), null)).isEqualTo(new RID(130, 1L << 40));
  }

  @Test
  void thePreviousPointerIsReadWithoutTheSerializer() {
    final MutableEdgeSegment tail = new MutableEdgeSegment((DatabaseInternal) database, 64);
    assertThat(tail.getPreviousRID()).isNull();
    assertThat(tail.hasPrevious()).isFalse();
    tail.setPrevious(new RID(12, 1L << 33));
    assertThat(tail.getPreviousRID()).isEqualTo(new RID(12, 1L << 33));
    assertThat(tail.hasPrevious()).isTrue();
  }

  /**
   * Review finding F4: the floor of the first chunk holds the widest entry (two RIDs of a 5-byte bucket and a 10-byte position,
   * 30 bytes) after the 17-byte header, and a new chunk that cannot take the entry it was created for fails loudly instead of
   * losing the edge.
   */
  @Test
  void theSmallestFirstChunkHoldsTheWidestEntryAndANewChunkNeverDropsOne() {
    assertThat(LocalDatabase.MIN_EDGE_LIST_CHUNK_SIZE).isGreaterThanOrEqualTo(MutableEdgeSegment.CONTENT_START_POSITION + 30);
    final MutableEdgeSegment floor = new MutableEdgeSegment((DatabaseInternal) database, LocalDatabase.MIN_EDGE_LIST_CHUNK_SIZE);
    EdgeLinkedList.addToNewChunk(floor, new RID(Integer.MAX_VALUE, Long.MAX_VALUE), new RID(Integer.MAX_VALUE, Long.MAX_VALUE));
    assertThat(floor.count(null)).isEqualTo(1);

    // THE OLD FLOOR OF 32 BYTES LEFT 15 BYTES OF ROOM: AN ENTRY WITH LARGE POSITIONS DID NOT FIT AND WAS DROPPED
    final MutableEdgeSegment oldFloor = new MutableEdgeSegment((DatabaseInternal) database, 32);
    assertThatThrownBy(() -> EdgeLinkedList.addToNewChunk(oldFloor, new RID(300, 1L << 40), new RID(300, 1L << 40)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot hold the entry");
  }

  /** A walk inside a transaction reads the chunk the transaction wrote (its pending appends live only there), not the page. */
  @Test
  void aWalkInsideATransactionReadsItsOwnWrittenChunk() {
    final RID hub = newHub();
    link(hub, 3);
    database.transaction(() -> {
      hub.asVertex().newEdge("LINK", database.newVertex("Leaf").save());
      final RID head = ((VertexInternal) hub.asVertex(true)).getOutEdgesHeadChunk();
      assertThat(EdgeLinkedList.readChunkForWalk((DatabaseInternal) database, head).count(null)).isEqualTo(4);
      assertThat(hub.asVertex().countEdges(Vertex.DIRECTION.OUT)).isEqualTo(4);
      assertThat(countByWalk(hub)).isEqualTo(4);
    });
    assertThat(headSegment(hub).count(null)).isEqualTo(4);
  }

  /**
   * {@code Iterator.remove()} on an entry of a chunk the walk read as a page view: the removal writes the transaction's own copy
   * of the chunk, never the view, and the list, the count and {@code CHECK DATABASE} agree afterwards.
   */
  @Test
  void anIteratorRemovesAnEntryOfAChunkItReadAsAView() {
    final RID hub = newHub();
    final List<RID> edges = link(hub, 600);
    assertThat(chainLength(hub)).isGreaterThan(2);

    // THE OLDEST EDGE SITS IN THE TAIL CHUNK, READ AS A VIEW BY THE WALK
    final RID oldest = edges.getFirst();
    database.transaction(() -> {
      final Iterator<Edge> it = hub.asVertex().getEdges(Vertex.DIRECTION.OUT).iterator();
      while (it.hasNext())
        if (it.next().getIdentity().equals(oldest)) {
          it.remove();
          break;
        }
    });

    assertThat(database.existsRecord(oldest)).isFalse();
    final List<RID> listed = new ArrayList<>();
    for (final Iterator<Edge> it = hub.asVertex().getEdges(Vertex.DIRECTION.OUT).iterator(); it.hasNext(); )
      listed.add(it.next().getIdentity());
    assertThat(listed).doesNotContain(oldest).hasSize(599);
    assertThat(hub.asVertex().countEdges(Vertex.DIRECTION.OUT)).isEqualTo(599);
    assertThat(checkDatabase().<Long>getProperty("totalErrors")).isZero();
  }

  /**
   * Review minor: {@code countEntries()} walks the rest of the list through {@code next()}, and its chunk hop clears the flag that
   * says the walk is on the transaction's live copy of a chunk. Restored onto the live copy with the flag cleared, the next
   * {@code next()} remembered its entry by position only; the same transaction then shifted that chunk (deleted an edge before the
   * entry), and {@code remove()} decoded whatever sat at the old position and deleted the edge AFTER the one returned.
   */
  @Test
  void aCountBetweenNextAndRemoveOnTheLiveCopyDeletesTheEdgeReturned() {
    final RID hub = newHub();
    final List<RID> edges = link(hub, 600);
    assertThat(chainLength(hub)).isGreaterThan(2);
    final Map<RID, RID> edgeOfLeaf = new HashMap<>();
    for (final RID edge : edges)
      edgeOfLeaf.put(edge.asEdge().getIn(), edge);

    final Set<RID> deleted = new HashSet<>();
    database.transaction(() -> {
      final VertexIterator it = (VertexIterator) ((DatabaseInternal) database).getGraphEngine()
          .getEdgeHeadChunk((VertexInternal) hub.asVertex(true), Vertex.DIRECTION.OUT).vertexIterator();
      deleted.add(edgeOfLeaf.get(it.next().getIdentity()));
      // THE WALK GOES ON ON THE TRANSACTION'S LIVE COPY OF THE HEAD CHUNK
      it.remove();
      // HOPS THROUGH EVERY OLDER CHUNK, THEN PUTS THE CURSOR BACK ON THE LIVE COPY (THE COUNT INCLUDES THE ONE ALREADY BROWSED)
      assertThat(it.countEntries()).isEqualTo(600);

      final RID second = edgeOfLeaf.get(it.next().getIdentity());
      final RID third = edgeOfLeaf.get(it.next().getIdentity());
      // THE SAME TRANSACTION SHIFTS THE LIVE COPY: THE ENTRY OF THE FOURTH EDGE MOVES ONTO THE POSITION OF THE THIRD
      second.asEdge().delete();
      deleted.add(second);
      it.remove();
      deleted.add(third);
    });

    for (final RID edge : edges)
      assertThat(database.existsRecord(edge)).as("edge %s", edge).isEqualTo(!deleted.contains(edge));
    assertThat(hub.asVertex().countEdges(Vertex.DIRECTION.OUT)).isEqualTo(597);
    assertThat(checkDatabase().<Long>getProperty("totalErrors")).isZero();
  }

  /**
   * Review minor: opening the edge list of a promoted super-node read its head (a stripe directory, not an edge segment) twice,
   * once from the bucket to look at its type and once more through {@code lookupByRID}: two before-read events per list open
   * where the base fired one.
   */
  @Test
  void openingASuperNodeListReadsItsStripeDirectoryOnce() {
    final int savedThreshold = GlobalConfiguration.GRAPH_SUPERNODE_THRESHOLD.getValueAsInteger();
    final int savedStripes = GlobalConfiguration.GRAPH_SUPERNODE_STRIPES.getValueAsInteger();
    GlobalConfiguration.GRAPH_SUPERNODE_THRESHOLD.setValue(64);
    GlobalConfiguration.GRAPH_SUPERNODE_STRIPES.setValue(4);
    try {
      final RID hub = newHub();
      link(hub, 300);
      final RID headRID = ((VertexInternal) hub.asVertex(true)).getOutEdgesHeadChunk();
      // PRECONDITION: THE HUB WAS PROMOTED, OTHERWISE THE TEST PROVES NOTHING
      assertThat(database.lookupByRID(headRID, true)).isInstanceOf(StripeDirectory.class);

      final AtomicInteger headReads = new AtomicInteger();
      final BeforeRecordReadListener listener = rid -> {
        if (rid.equals(headRID))
          headReads.incrementAndGet();
        return true;
      };
      database.getEvents().registerListener(listener);
      try {
        database.transaction(() -> {
          final EdgeLinkedList list = ((DatabaseInternal) database).getGraphEngine()
              .getEdgeHeadChunk((VertexInternal) hub.asVertex(true), Vertex.DIRECTION.OUT);
          assertThat(list).isInstanceOf(StripedEdgeList.class);
          assertThat(headReads.get()).isEqualTo(1);
          assertThat(list.count()).isEqualTo(300);
        });
      } finally {
        database.getEvents().unregisterListener(listener);
      }
    } finally {
      GlobalConfiguration.GRAPH_SUPERNODE_THRESHOLD.setValue(savedThreshold);
      GlobalConfiguration.GRAPH_SUPERNODE_STRIPES.setValue(savedStripes);
    }
  }

  private Result checkDatabase() {
    try (final ResultSet result = database.command("sql", "CHECK DATABASE")) {
      return result.next();
    }
  }

  private RID newHub() {
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newVertex("Hub").save().getIdentity());
    return rid[0];
  }

  /** Links {@code count} new leaves to {@code hub}, in batches, and returns the edges in creation order. */
  private List<RID> link(final RID hub, final int count) {
    final List<RID> edges = new ArrayList<>(count);
    for (int batch = 0; batch < count; batch += 250) {
      final int from = batch;
      final int to = Math.min(count, batch + 250);
      database.transaction(() -> {
        for (int i = from; i < to; i++) {
          final MutableVertex leaf = database.newVertex("Leaf").set("n", i).save();
          edges.add(hub.asVertex().newEdge("LINK", leaf).getIdentity());
        }
      });
    }
    return edges;
  }

  private long countByWalk(final RID vertex) {
    long count = 0;
    for (final Iterator<Edge> it = vertex.asVertex().getEdges(Vertex.DIRECTION.OUT).iterator(); it.hasNext(); it.next())
      ++count;
    return count;
  }

  private int chainLength(final RID vertex) {
    int chunks = 0;
    EdgeSegment current = headSegment(vertex);
    while (current != null) {
      ++chunks;
      current = current.previousForWalk();
    }
    return chunks;
  }

  private EdgeSegment headSegment(final RID vertex) {
    return EdgeLinkedList.readChunkForWalk((DatabaseInternal) database,
        ((VertexInternal) vertex.asVertex(true)).getOutEdgesHeadChunk());
  }
}
