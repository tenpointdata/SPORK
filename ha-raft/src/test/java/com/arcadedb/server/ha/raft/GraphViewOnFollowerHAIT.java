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

import com.arcadedb.database.Database;
import com.arcadedb.graph.GraphTraversalProvider;
import com.arcadedb.graph.GraphTraversalProviderRegistry;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.graph.olap.GraphAnalyticalView;
import com.arcadedb.graph.olap.GraphAnalyticalViewRegistry;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3 (docs/spork/09-f3-csr-on-followers.md): a graph analytical view on a Raft FOLLOWER. A follower applies replicated commits
 * as page deltas, where the record listeners that keep a view current on the leader do not fire. Before F3 a view created after
 * a follower started never reached it, and after a follower restart its view stayed READY over the graph as it stood at the
 * restart: Cypher on the follower answered an edge count of 1 where its own records held 2.
 * <p>
 * The reference for every answer is the follower's own records, read with SQL {@code out()}, which no view serves.
 */
class GraphViewOnFollowerHAIT extends BaseRaftHATest {

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Override
  protected boolean persistentRaftStorage() {
    return true; // restartServer() rejoins the same Raft group
  }

  @Test
  void aViewCreatedOnTheLeaderReachesTheFollowersAndFollowsReplicatedCommits() {
    final int leader = requireLeaderIndex();
    final int follower = firstFollowerOf(leader);
    final Database leaderDb = createGraphAndView(leader);

    final Database followerDb = getServerDatabase(follower, getDatabaseName());
    awaitViewReady(followerDb);
    assertThat(neighboursThroughTheView(followerDb)).as("the follower's view, built after the schema entry").containsExactly("b");

    addEdgeToC(leaderDb);
    waitForAllServers();
    assertFollowerNeverAnswersAnOlderGraph(followerDb);

    awaitViewReady(followerDb);
    assertThat(neighboursThroughTheView(followerDb)).as("the follower's view once rebuilt").containsExactlyInAnyOrder("b", "c");
  }

  @Test
  void aRestartedFollowersViewFollowsReplicatedCommits() {
    final int leader = requireLeaderIndex();
    final int follower = firstFollowerOf(leader);
    final Database leaderDb = createGraphAndView(leader);

    restartServer(follower);
    final Database followerDb = getServerDatabase(follower, getDatabaseName());
    awaitViewReady(followerDb);
    assertThat(cypherCount(followerDb)).isEqualTo(1L);

    addEdgeToC(leaderDb);
    waitForAllServers();
    assertFollowerNeverAnswersAnOlderGraph(followerDb);

    awaitViewReady(followerDb);
    assertThat(neighboursThroughTheView(followerDb)).as("the follower's view once rebuilt").containsExactlyInAnyOrder("b", "c");
  }

  @Test
  void aViewDroppedOnTheLeaderLeavesTheFollowers() {
    final int leader = requireLeaderIndex();
    final int follower = firstFollowerOf(leader);
    final Database leaderDb = createGraphAndView(leader);
    final Database followerDb = getServerDatabase(follower, getDatabaseName());
    awaitViewReady(followerDb);

    leaderDb.command("sql", "DROP GRAPH ANALYTICAL VIEW fv");
    waitForAllServers();

    final long deadline = System.currentTimeMillis() + 30_000;
    while (GraphAnalyticalViewRegistry.get(followerDb, "fv") != null && System.currentTimeMillis() < deadline)
      Thread.onSpinWait();
    assertThat(GraphAnalyticalViewRegistry.get(followerDb, "fv")).as("the follower's view after the drop").isNull();
    assertThat(cypherCount(followerDb)).isEqualTo(1L);
  }

  // ---- helpers ----

  /** a -> b, and a view over the edge type covering every vertex type (the fixture's own types included). */
  private Database createGraphAndView(final int leader) {
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.getSchema().createVertexType("FvN");
    leaderDb.getSchema().createEdgeType("FvE");
    leaderDb.transaction(() -> {
      final MutableVertex a = leaderDb.newVertex("FvN").set("name", "a").save();
      final MutableVertex b = leaderDb.newVertex("FvN").set("name", "b").save();
      a.newEdge("FvE", b).save();
    });
    // SYNCHRONOUS: an incremental view, which a follower rebuilds after a replicated change; an OFF view (the default) stays STALE
    // until rebuilt by hand, on the leader as on a follower
    leaderDb.command("sql", "CREATE GRAPH ANALYTICAL VIEW fv EDGE TYPES (FvE) UPDATE MODE SYNCHRONOUS");
    waitForAllServers();
    return leaderDb;
  }

  private static void addEdgeToC(final Database leaderDb) {
    leaderDb.transaction(() -> {
      final Vertex a = leaderDb.query("sql", "SELECT FROM FvN WHERE name = 'a'").next().getVertex().orElseThrow();
      final MutableVertex c = leaderDb.newVertex("FvN").set("name", "c").save();
      a.modify().newEdge("FvE", c).save();
    });
  }

  /** Cypher, which reads through a ready view, and the view itself while it is offered, both match the follower's records. */
  private static void assertFollowerNeverAnswersAnOlderGraph(final Database followerDb) {
    assertThat(recordCount(followerDb)).as("the follower's own records").isEqualTo(2L);
    assertThat(cypherCount(followerDb)).as("Cypher on the follower").isEqualTo(2L);
    final Set<String> throughTheView = neighboursThroughTheView(followerDb);
    if (throughTheView != null)
      assertThat(throughTheView).as("a view the follower still offers as ready").containsExactlyInAnyOrder("b", "c");
  }

  /** The names a's FvE edges reach through the follower's ready view, or null when it offers none right now. */
  private static Set<String> neighboursThroughTheView(final Database database) {
    final GraphTraversalProvider view = GraphTraversalProviderRegistry.findProvider(database, "FvE");
    if (view == null)
      return null;
    final Vertex a = database.query("sql", "SELECT FROM FvN WHERE name = 'a'").next().getVertex().orElseThrow();
    final int node = view.getNodeId(a.getIdentity());
    assertThat(node).as("a is mapped by the view").isGreaterThanOrEqualTo(0);
    final Set<String> names = new HashSet<>();
    for (final int neighbour : view.getNeighborIds(node, Vertex.DIRECTION.OUT, "FvE"))
      names.add(view.getRID(neighbour).asVertex().getString("name"));
    return names;
  }

  private static long cypherCount(final Database database) {
    try (final ResultSet rs = database.query("opencypher", "MATCH (a:FvN {name: 'a'})-[:FvE]->(x) RETURN count(x) AS n")) {
      return ((Number) rs.next().getProperty("n")).longValue();
    }
  }

  private static long recordCount(final Database database) {
    try (final ResultSet rs = database.query("sql", "SELECT out('FvE').size() AS n FROM FvN WHERE name = 'a'")) {
      return ((Number) rs.next().getProperty("n")).longValue();
    }
  }

  private static void awaitViewReady(final Database database) {
    final long deadline = System.currentTimeMillis() + 60_000;
    GraphAnalyticalView view = null;
    while (System.currentTimeMillis() < deadline) {
      view = GraphAnalyticalViewRegistry.get(database, "fv");
      if (view != null && view.isReady())
        return;
      Thread.onSpinWait();
    }
    assertThat(view).as("view fv registered").isNotNull();
    assertThat(view.isReady()).as("view fv ready").isTrue();
  }
}
