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
import com.arcadedb.database.RID;
import com.arcadedb.log.LogManager;
import com.arcadedb.utility.StallAwareStopwatch;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval speed S6 and S7 on the Recon pivot shape: an identifier vertex with thousands of edges whose newest records are
 * read, and the degree of a hub counted by a walk.
 * <ul>
 *   <li>1-hop pivot: 500 newest neighbours of a 10,000-degree vertex, bytes allocated and nanoseconds per entry. "Before" is
 *   the walk the iterators did until S6, reproduced here on the same chain: both RIDs of every entry decoded through
 *   {@code getRID(AtomicInteger)} and every chunk hopped to as a copy through {@code lookupByRID}. "After" is
 *   {@code getVertices()} and {@code getConnectedVertexRIDs()} as they now are.</li>
 *   <li>{@code countEdges} on a 100,000-degree hub: "before" is the chain walk over copies of every chunk, summing
 *   {@code count(null)}, which is what {@code EdgeLinkedList.count()} did until S7; "after" is the same walk over read-only
 *   views of the chunks' pages, as {@code countEdges} now does. The count stays a walk: an O(1) degree is not kept in the
 *   edge list (see docs/spork/workstreams/graph-adjacency.md).</li>
 * </ul>
 * Timing is wall clock discounted of the JVM stalls observed inside the window ({@link StallAwareStopwatch}); the per-entry
 * figures are the measured window divided by the entries read, over enough repetitions for the window to be milliseconds.
 * Allocation is {@link ThreadMXBean#getThreadAllocatedBytes} on the measuring thread. Nothing is asserted on time.
 * <p>
 * Run with: {@code mvn -o -pl engine test -Dsurefire.includes='**\/AdjacencyPivotBenchmark.java' -DexcludedGroups=none}
 */
@Tag("benchmark")
class AdjacencyPivotBenchmark extends TestHelper {
  private static final int PIVOT_DEGREE = 10_000;
  private static final int PIVOT_PAGE   = 500;
  private static final int PIVOT_RUNS   = 200;
  private static final int HUB_DEGREE   = 100_000;
  private static final int COUNT_RUNS   = 50;

  private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  @Override
  protected void beginTest() {
    // THE CLASSIC SINGLE-CHAIN LAYOUT, AS RECON RUNS ITS IDENTIFIER VERTICES (C7 of the retrieval-speed doc sets the promotion
    // threshold far above any identifier's degree); the "before" walk reproduced here reads that layout. TestHelper resets it.
    GlobalConfiguration.GRAPH_SUPERNODE_THRESHOLD.setValue(0);
  }

  @Test
  void pivotAndDegree() throws Exception {
    database.transaction(() -> {
      database.getSchema().createVertexType("Identifier", 1);
      database.getSchema().createVertexType("Record", 1);
      database.getSchema().createEdgeType("HAS", 1);
    });

    final RID pivot = hub(PIVOT_DEGREE);
    final RID hub = hub(HUB_DEGREE);

    final StringBuilder report = new StringBuilder();
    report.append("======== Adjacency pivot benchmark (S6, S7) ========\n");

    // ---- 1-hop pivot: 500 newest of a 10,000-degree identifier ----
    for (int warm = 0; warm < 20; warm++) {
      legacyVertexWalk(pivot, PIVOT_PAGE);
      newVertexWalk(pivot, PIVOT_PAGE);
      newRidWalk(pivot, PIVOT_PAGE);
    }
    report.append("1-hop pivot: %d newest neighbours of a %d-degree vertex, %d runs%n".formatted(PIVOT_PAGE, PIVOT_DEGREE, PIVOT_RUNS));
    report.append("%-44s %12s %12s %12s%n".formatted("walk", "ns/entry", "bytes/entry", "stall ms"));
    report.append(measure("before: getRID x2 + lookupByRID per entry", PIVOT_PAGE, () -> legacyVertexWalk(pivot, PIVOT_PAGE)));
    report.append(measure("after: getVertices (nextEntry, vertex only)", PIVOT_PAGE, () -> newVertexWalk(pivot, PIVOT_PAGE)));
    report.append(measure("after: getConnectedVertexRIDs", PIVOT_PAGE, () -> newRidWalk(pivot, PIVOT_PAGE)));
    report.append(measure("before: full walk getRID x2 + lookup", PIVOT_DEGREE, () -> legacyVertexWalk(pivot, PIVOT_DEGREE)));
    report.append(measure("after: full walk getVertices", PIVOT_DEGREE, () -> newVertexWalk(pivot, PIVOT_DEGREE)));

    // ---- countEdges on a 100,000-degree hub ----
    for (int warm = 0; warm < 5; warm++) {
      legacyCount(hub);
      hub.asVertex().countEdges(Vertex.DIRECTION.IN);
    }
    final long walked = legacyCount(hub);
    assertThat(walked).isEqualTo(HUB_DEGREE);
    assertThat(hub.asVertex().countEdges(Vertex.DIRECTION.IN)).isEqualTo(HUB_DEGREE);
    report.append("%ncountEdges on a %d-degree hub, %d runs%n".formatted(HUB_DEGREE, COUNT_RUNS));
    report.append("%-44s %12s %12s %12s%n".formatted("count", "us/call", "bytes/call", "stall ms"));
    report.append(measureCalls("before: chain walk over chunk copies", () -> legacyCount(hub)));
    report.append(measureCalls("after: countEdges, chain walk over views", () -> hub.asVertex().countEdges(Vertex.DIRECTION.IN)));
    report.append("============================================================");

    LogManager.instance().log(this, Level.INFO, report.toString());
    Files.writeString(new File("./target/adjacency-pivot-benchmark.txt").toPath(), report + System.lineSeparator(),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private String measure(final String label, final int entries, final Runnable walk) {
    final long threadId = Thread.currentThread().getId();
    final long bytesBefore = threads.getThreadAllocatedBytes(threadId);
    final StallAwareStopwatch watch = StallAwareStopwatch.start();
    final long begin = System.nanoTime();
    for (int run = 0; run < PIVOT_RUNS; run++)
      walk.run();
    final long nanos = System.nanoTime() - begin;
    final long bytes = threads.getThreadAllocatedBytes(threadId) - bytesBefore;
    final long total = (long) PIVOT_RUNS * entries;
    return "%-44s %12.0f %12.0f %12d%n".formatted(label, nanos / (double) total, bytes / (double) total, watch.stallMs());
  }

  private String measureCalls(final String label, final Runnable call) {
    final long threadId = Thread.currentThread().getId();
    final long bytesBefore = threads.getThreadAllocatedBytes(threadId);
    final StallAwareStopwatch watch = StallAwareStopwatch.start();
    final long begin = System.nanoTime();
    for (int run = 0; run < COUNT_RUNS; run++)
      call.run();
    final long nanos = System.nanoTime() - begin;
    final long bytes = threads.getThreadAllocatedBytes(threadId) - bytesBefore;
    return "%-44s %12.1f %12.0f %12d%n".formatted(label, nanos / 1_000.0 / COUNT_RUNS, bytes / (double) COUNT_RUNS, watch.stallMs());
  }

  /** The walk the iterators did until S6: both RIDs decoded per entry, every chunk a copy. */
  private int legacyVertexWalk(final RID vertex, final int limit) {
    final RID head = ((VertexInternal) vertex.asVertex(true)).getInEdgesHeadChunk();
    EdgeSegment segment = (EdgeSegment) database.lookupByRID(head, true);
    final AtomicInteger cursor = new AtomicInteger(MutableEdgeSegment.CONTENT_START_POSITION);
    int read = 0;
    while (segment != null && read < limit) {
      final int used = segment.getUsed();
      while (cursor.get() < used && read < limit) {
        segment.getRID(cursor); // THE EDGE
        final RID rid = segment.getRID(cursor);
        database.lookupByRID(rid, false);
        ++read;
      }
      if (read < limit) {
        final RID previous = segment.getPreviousRID();
        segment = previous == null ? null : (EdgeSegment) database.lookupByRID(previous, true);
        if (segment != null)
          cursor.set(MutableEdgeSegment.CONTENT_START_POSITION);
      }
    }
    return read;
  }

  private int newVertexWalk(final RID vertex, final int limit) {
    int read = 0;
    final Iterator<Vertex> it = vertex.asVertex().getVertices(Vertex.DIRECTION.IN).iterator();
    while (read < limit && it.hasNext()) {
      it.next();
      ++read;
    }
    return read;
  }

  private int newRidWalk(final RID vertex, final int limit) {
    int read = 0;
    final Iterator<RID> it = vertex.asVertex().getConnectedVertexRIDs(Vertex.DIRECTION.IN).iterator();
    while (read < limit && it.hasNext()) {
      it.next();
      ++read;
    }
    return read;
  }

  /** What {@code EdgeLinkedList.count()} did until S7: a walk over copies of every chunk of the chain. */
  private long legacyCount(final RID vertex) {
    final RID head = ((VertexInternal) vertex.asVertex(true)).getInEdgesHeadChunk();
    long total = 0;
    EdgeSegment segment = (EdgeSegment) database.lookupByRID(head, true);
    while (segment != null) {
      total += segment.count(null);
      final RID previous = segment.getPreviousRID();
      segment = previous == null ? null : (EdgeSegment) database.lookupByRID(previous, true);
    }
    return total;
  }

  private RID hub(final int degree) {
    final RID[] rid = new RID[1];
    database.transaction(() -> rid[0] = database.newVertex("Identifier").set("key", "k" + degree).save().getIdentity());
    for (int batch = 0; batch < degree; batch += 1_000) {
      final int from = batch;
      final int to = Math.min(degree, batch + 1_000);
      database.transaction(() -> {
        final Vertex identifier = rid[0].asVertex();
        for (int i = from; i < to; i++)
          database.newVertex("Record").set("n", i).save().newEdge("HAS", identifier);
      });
    }
    assertThat(((DatabaseInternal) database).getGraphEngine().countEdges((VertexInternal) rid[0].asVertex(true), Vertex.DIRECTION.IN))
        .isEqualTo(degree);
    return rid[0];
  }
}
