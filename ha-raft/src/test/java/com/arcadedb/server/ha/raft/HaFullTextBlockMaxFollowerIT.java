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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.fulltext.FullTextSearch;
import com.arcadedb.index.fulltext.FullTextTopK;
import com.arcadedb.schema.Schema;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block-max sidecar of a full-text index on an HA follower. The sidecar is a derived file outside the WAL, so a follower writes
 * its own, and the follower applies a replicated compaction with a full schema load, which replaces every full-text wrapper. The
 * wrapper the compaction's listener belonged to was retired, and nothing asked the new one for its build: from its first replicated
 * compaction the follower answered every top-k query by reading the postings, until a restart. The new wrapper now asks for its build
 * once the reload has published it.
 */
class HaFullTextBlockMaxFollowerIT extends BaseRaftHATest {
  private static final String INDEX     = "Doc[text]";
  private static final int    DOCUMENTS = 4_000;

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    // Only the explicit compaction of the test runs
    config.setValue(GlobalConfiguration.INDEX_COMPACTION_MIN_PAGES_SCHEDULE, 0);
  }

  @Override
  protected void populateDatabase() {
  }

  @Override
  protected void checkDatabasesAreIdentical() {
    // A compaction renames the mutable index file with a per-node name, so the bucket-index names differ across nodes and
    // TypeIndex.equals, which pairs them by name, cannot compare the types. The test asserts the answers itself
  }

  @Test
  void aFollowerWritesTheSidecarAfterAReplicatedCompaction() throws Exception {
    final int leaderIndex = findLeaderIndex();
    assertThat(leaderIndex).as("A Raft leader must be elected").isGreaterThanOrEqualTo(0);
    final int followerIndex = leaderIndex == 0 ? 1 : 0;
    final Database leader = getServerDatabase(leaderIndex, getDatabaseName());

    leader.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
    leader.command("sql", "CREATE PROPERTY Doc.text STRING");
    leader.getSchema().buildTypeIndex("Doc", new String[] { "text" }).withType(Schema.INDEX_TYPE.FULL_TEXT).withPageSize(16_384)
        .create();
    // Every document holds "alpha", so its postings span enough blocks for a top-k query to read the sidecar
    for (int start = 0; start < DOCUMENTS; start += 500) {
      final int first = start;
      leader.transaction(() -> {
        for (int i = first; i < first + 500; i++)
          leader.newDocument("Doc").set("text", "alpha w" + (i % 37) + " " + "beta ".repeat(i % 5) + "w" + (i % 11)).save();
      });
    }

    final TypeIndex leaderIndexOnType = (TypeIndex) leader.getSchema().getIndexByName(INDEX);
    leaderIndexOnType.scheduleCompaction();
    leaderIndexOnType.compact();
    for (int i = 0; i < getServerCount(); i++)
      waitForReplicationIsCompleted(i);

    final Database follower = getServerDatabase(followerIndex, getDatabaseName());
    // Without the build the follower never reads a sidecar: the counter does not move however long it waits
    Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS).until(() -> {
      final long before = FullTextTopK.getBlockMaxInvocations();
      search(follower, "alpha", 10, true);
      return FullTextTopK.getBlockMaxInvocations() > before;
    });

    for (final String query : new String[] { "alpha", "alpha beta", "+alpha +w3", "w7 beta^2", "alpha w5 w9" })
      for (final int limit : new int[] { 1, 10, 100 })
        assertThat(search(follower, query, limit, true)).as("query='%s' limit=%d on the follower", query, limit)
            .isEqualTo(search(follower, query, limit, false));
  }

  private static Map<RID, Float> search(final Database database, final String query, final int limit, final boolean topK) {
    database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, topK);
    try {
      return FullTextSearch.search((TypeIndex) database.getSchema().getIndexByName(INDEX), query, limit);
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, true);
    }
  }
}
