/*
 * Copyright 2021-present Arcade Data Ltd (info@arcadedata.com)
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
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.LocalDatabase;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.BaseGraphServerTest;
import com.arcadedb.server.ServerDatabase;
import com.arcadedb.utility.FileUtils;
import org.apache.ratis.protocol.RaftPeerId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPORK E26 spike (Phase 0, P0c): a server running {@code ha-raft} also serves a read-only, node-local database that
 * is not in the Raft group, set by {@link GlobalConfiguration#HA_LOCAL_DATABASES}.
 * <p>
 * Three nodes; nodes 0 and 1 hold their own copy of {@value #LOCAL_DB}, node 2 holds none. Each copy carries a marker
 * naming its holder, so a copy that any HA path refreshed, acquired or replaced from another node shows up as a
 * marker that names the wrong node.
 */
class NodeLocalDatabaseBesideRaftGroupIT extends BaseRaftHATest {
  private static final String LOCAL_DB      = "warm";
  /** The rewritten copy an aging rewrite admits beside it; covered by the same prefix entry of the setting. */
  private static final String REWRITTEN_DB  = "warm_w2";
  private static final int    LOCAL_RECORDS = 100;
  private static final int    NON_HOLDER    = 2;
  private static final String BUILD_DIRECTORY = "./target/e26-build";
  /** Entries written while a follower is down: about 1.2 MB of log, so several closed segments for the purge to drop. */
  private static final int    OFFLINE_WRITES  = 600;
  private static final String PAYLOAD         = "x".repeat(2_048);

  @Override
  protected int getServerCount() {
    return 3;
  }

  @Override
  protected boolean persistentRaftStorage() {
    // restartServer() needs the Raft storage to survive the restart
    return true;
  }

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    config.setValue(GlobalConfiguration.HA_QUORUM, "majority");
    // Snapshot every 10 entries and purge in small log segments (only a closed segment is purged), as
    // Issue8643RecountDuringSnapshotCatchUpIT does: a follower down for OFFLINE_WRITES entries finds the leader's log
    // compacted past it and can only rejoin by a snapshot install
    config.setValue(GlobalConfiguration.HA_SNAPSHOT_THRESHOLD, 10L);
    config.setValue(GlobalConfiguration.HA_LOG_PURGE_GAP, 20);
    config.setValue(GlobalConfiguration.HA_LOG_PURGE_UPTO_SNAPSHOT, true);
    config.setValue(GlobalConfiguration.HA_APPEND_BUFFER_SIZE, "256KB");
    config.setValue(GlobalConfiguration.HA_WRITE_BUFFER_SIZE, "512KB");
    config.setValue(GlobalConfiguration.HA_LOG_SEGMENT_SIZE, "512KB");
    // The same setting on every node, as the plugin's placement would push it: holding the files is what differs. A prefix,
    // so the rewritten copies admitted later are node-local without a restart
    config.setValue(GlobalConfiguration.HA_LOCAL_DATABASES, LOCAL_DB + "*");
    // The rewritten copy is admitted from a local file:// archive
    config.setValue(GlobalConfiguration.SERVER_RESTORE_IMPORT_ALLOW_LOCAL_URLS, true);
  }

  /** Builds each holder's copy before its server starts, as the offline rewrite on n4 followed by an admission would. */
  @Override
  protected void onBeforeStarting(final ArcadeDBServer server) {
    final int index = serverIndexOf(server.getConfiguration());
    if (index == NON_HOLDER)
      return;
    final String path = server.getConfiguration().getValueAsString(GlobalConfiguration.SERVER_DATABASE_DIRECTORY) + File.separator + LOCAL_DB;
    if (new File(path).exists())
      return;
    try (final Database local = new DatabaseFactory(path).create()) {
      local.transaction(() -> {
        local.getSchema().createDocumentType("Ad");
        local.getSchema().createDocumentType("Holder");
        for (int i = 0; i < LOCAL_RECORDS; i++)
          local.newDocument("Ad").set("seq", i).save();
        local.newDocument("Holder").set("node", index).save();
      });
    }
  }

  @Override
  protected void deleteDatabaseFolders() {
    // A READ_ONLY database cannot be dropped through the server: close it and let the folder deletion remove it
    if (getServers() != null)
      for (int i = 0; i < getServerCount(); i++) {
        final ArcadeDBServer server = getServer(i);
        if (server != null && server.isStarted())
          for (final String name : new String[] { LOCAL_DB, REWRITTEN_DB })
            if (server.existsDatabase(name)) {
              ((DatabaseInternal) server.getDatabase(name)).getEmbedded().close();
              server.removeDatabase(name);
            }
      }
    super.deleteDatabaseFolders();
    FileUtils.deleteRecursively(new File(BUILD_DIRECTORY));
    FileUtils.deleteRecursively(new File("./target/backups/" + REWRITTEN_DB));
  }

  @Test
  void readsAreServedWritesRefusedAndTheGroupKeepsApplying() throws Exception {
    moveLeadershipTo(0);

    for (int holder = 0; holder < 2; holder++)
      assertServedLocally(holder);
    assertThat(getServer(NON_HOLDER).existsDatabase(LOCAL_DB)).as("node 2 never held the local database").isFalse();

    // Reads through HTTP on a follower holder are answered there, not forwarded to anyone
    assertThat(httpRequest(1, "query", LOCAL_DB, "select count(*) as c from Ad")).as("HTTP read on follower holder").isEqualTo(200);

    // Writes and schema changes are refused on the leader's copy and on a follower's copy, through Java and HTTP
    for (int holder = 0; holder < 2; holder++) {
      final Database local = getServer(holder).getDatabase(LOCAL_DB);
      assertThatThrownBy(() -> local.transaction(() -> local.newDocument("Ad").set("seq", -1).save()))
          .as("insert on node %d", holder).isNotNull();
      assertThatThrownBy(() -> local.getSchema().createDocumentType("Sneaky"))
          .as("schema change on node %d", holder).isNotNull();
    }
    assertThat(httpRequest(1, "command", LOCAL_DB, "insert into Ad set seq = -2")).as("HTTP write on follower holder").isNotEqualTo(200);
    assertThat(httpRequest(0, "command", LOCAL_DB, "insert into Ad set seq = -3")).as("HTTP write on leader holder").isNotEqualTo(200);

    // The replicated database keeps applying on every node, the one without the local database included
    writeReplicated(0, "after-refusals", 20);
    for (int i = 0; i < getServerCount(); i++)
      assertThat(awaitCountOn(i, "Item", 20)).as("replicated count on node %d", i).isEqualTo(20);

    for (int holder = 0; holder < 2; holder++)
      assertServedLocally(holder);
    assertThat(getServer(NON_HOLDER).existsDatabase(LOCAL_DB)).isFalse();
  }

  @Test
  void snapshotInstallAutoAcquireAndResyncLeaveItAlone() throws Exception {
    // The leader holds the local database, so its listing and snapshot endpoint are the ones that could leak it
    moveLeadershipTo(0);
    writeReplicated(0, "before", 5);

    // A non-holder that falls behind the compacted log rejoins (install or replay), with auto-acquire on
    catchUpAfterDowntime(NON_HOLDER, 0);
    assertThat(getServer(NON_HOLDER).existsDatabase(LOCAL_DB)).as("auto-acquire must not pull the local database").isFalse();
    assertThat(new File(databaseDirectory(NON_HOLDER), LOCAL_DB)).as("no copy on disk on the non-holder").doesNotExist();

    // A follower holder that rejoins keeps its own copy, not the leader's
    catchUpAfterDowntime(1, 0);
    assertServedLocally(1);

    // The reconcile step of an install, driven directly: the leader's listing (auto-acquire), the refresh of what this
    // node holds, and the leader-missing verdict all leave the local database out
    for (final int follower : new int[] { 1, NON_HOLDER }) {
      final DatabaseReconciler.ReconcileFromLeaderResult result = reconcileFromLeaderAsAnInstallDoes(follower, 0);
      assertThat(result.notInstalled()).as("nothing failed to install on node %d", follower).isEmpty();
      assertThat(result.leaderMissing()).as("the local database is not reported leader-missing on node %d", follower)
          .doesNotContain(LOCAL_DB);
    }
    assertServedLocally(1);
    assertThat(getServer(NON_HOLDER).existsDatabase(LOCAL_DB)).as("auto-acquire must not pull the local database").isFalse();

    // The stale-follower recovery downloads every database it holds from the leader: never the local one
    for (final int follower : new int[] { 1, NON_HOLDER })
      resyncEveryDatabaseFromLeader(follower);
    assertServedLocally(0);
    assertServedLocally(1);
    assertThat(getServer(NON_HOLDER).existsDatabase(LOCAL_DB)).isFalse();

    writeReplicated(0, "after", 5);
    for (int i = 0; i < getServerCount(); i++)
      assertThat(awaitCountOn(i, "Item", 5 + 2 * OFFLINE_WRITES + 5)).as("replicated count on node %d", i)
          .isEqualTo(5 + 2 * OFFLINE_WRITES + 5);
  }

  @Test
  void leaderChangeNeitherTouchesNorHaltsOnIt() throws Exception {
    long expected = 0;
    // Leadership through all three nodes: a holder, the non-holder (whose listing lacks the local database), the other holder
    for (final int target : new int[] { 0, NON_HOLDER, 1 }) {
      moveLeadershipTo(target);
      writeReplicated(target, "leader-" + target, 10);
      expected += 10;
      for (int i = 0; i < getServerCount(); i++)
        assertThat(awaitCountOn(i, "Item", expected)).as("replicated count on node %d under leader %d", i, target).isEqualTo(expected);
      assertServedLocally(0);
      assertServedLocally(1);
      assertThat(getServer(NON_HOLDER).existsDatabase(LOCAL_DB)).isFalse();
    }

    // A holder that rejoins under a leader that does not hold the local database keeps serving it, and the reconcile
    // against that leader does not mark its copy unverified (which would refuse every reopen on a follower)
    moveLeadershipTo(NON_HOLDER);
    catchUpAfterDowntime(0, NON_HOLDER);
    expected += OFFLINE_WRITES;
    assertServedLocally(0);
    assertServedLocally(1);

    // The idle closer closes a node-local database like any unreplicated one. A reconcile and a resync against a leader
    // that does not hold it must neither report nor mark the closed copy (a marked copy is never reopened on a follower)
    ((DatabaseInternal) getServer(1).getDatabase(LOCAL_DB)).getEmbedded().close();
    getServer(1).removeDatabase(LOCAL_DB);
    final DatabaseReconciler.ReconcileFromLeaderResult result = reconcileFromLeaderAsAnInstallDoes(1, NON_HOLDER);
    assertThat(result.notInstalled()).isEmpty();
    assertThat(result.leaderMissing()).doesNotContain(LOCAL_DB);
    resyncEveryDatabaseFromLeader(1);
    assertServedLocally(1);
    for (int i = 0; i < getServerCount(); i++)
      assertThat(awaitCountOn(i, "Item", expected)).as("replicated count on node %d", i).isEqualTo(expected);
  }

  /**
   * The aging rewrite's admission (architecture 3.5, step 3) targets the 2 holders instead of the group: the copy built
   * offline is packaged as a backup archive and restored on each holder, the leader and a follower alike, then the old
   * copy is dropped on each. Nothing goes through Raft, so the non-holder never sees either name and the group's applies
   * never stall.
   */
  @Test
  void rewrittenCopyIsAdmittedToEachHolderAloneAndTheOldOneDropped() throws Exception {
    moveLeadershipTo(0);

    // The offline rewrite on the builder: a new database under the next name, packaged as a backup archive
    final File archive;
    try (final Database rewritten = new DatabaseFactory(BUILD_DIRECTORY + File.separator + REWRITTEN_DB).create()) {
      rewritten.transaction(() -> {
        rewritten.getSchema().createDocumentType("Ad");
        rewritten.getSchema().createDocumentType("Holder");
        for (int i = 0; i < LOCAL_RECORDS - 10; i++) // the deletion batches the rewrite applied
          rewritten.newDocument("Ad").set("seq", i).save();
        rewritten.newDocument("Holder").set("node", -1).save();
      });
      try (final ResultSet backup = rewritten.command("sql", "backup database")) {
        archive = new File("target/backups/" + REWRITTEN_DB + "/" + backup.next().<String>getProperty("backupFile")).getAbsoluteFile();
      }
    }
    assertThat(archive).exists();

    writeReplicated(0, "before-admission", 5);
    final long before = awaitCountOn(NON_HOLDER, "Item", 5);
    for (int holder = 0; holder < 2; holder++)
      assertThat(httpServerCommand(holder, "restore database " + REWRITTEN_DB + " file://" + archive.getPath()))
          .as("admission on node %d (%s)", holder, holder == 0 ? "leader" : "follower").isEqualTo(200);

    for (int holder = 0; holder < 2; holder++) {
      final ServerDatabase admitted = getServer(holder).getDatabase(REWRITTEN_DB);
      assertThat(admitted.getWrappedDatabaseInstance()).as("admitted copy on node %d is not Raft-wrapped", holder)
          .isInstanceOf(LocalDatabase.class);
      assertThat(admitted.getMode()).isEqualTo(ComponentFile.MODE.READ_ONLY);
      assertThat(admitted.countType("Ad", true)).as("admitted records on node %d", holder).isEqualTo(LOCAL_RECORDS - 10);
    }
    assertThat(getServer(NON_HOLDER).existsDatabase(REWRITTEN_DB)).as("the admission never reached the non-holder").isFalse();
    assertThat(new File(databaseDirectory(NON_HOLDER), REWRITTEN_DB)).doesNotExist();

    // The partition map switches, the old copy drains and is dropped on each holder alone
    assertThat(httpServerCommand(1, "drop database " + LOCAL_DB)).as("drop on the follower holder").isEqualTo(200);
    assertThat(getServer(1).existsDatabase(LOCAL_DB)).isFalse();
    assertServedLocally(0);
    assertThat(httpServerCommand(0, "drop database " + LOCAL_DB)).as("drop on the leader holder").isEqualTo(200);
    assertThat(getServer(0).existsDatabase(LOCAL_DB)).isFalse();

    writeReplicated(0, "after-admission", 5);
    for (int i = 0; i < getServerCount(); i++)
      assertThat(awaitCountOn(i, "Item", before + 5)).as("replicated count on node %d", i).isEqualTo(before + 5);
  }

  /** Each holder answers from its own copy, opened read-only and outside the Raft wrapper. */
  private void assertServedLocally(final int holder) {
    final ServerDatabase serverDb = getServer(holder).getDatabase(LOCAL_DB);
    assertThat(serverDb.isReplicated()).as("local database on node %d is not replicated", holder).isFalse();
    assertThat(serverDb.getWrappedDatabaseInstance()).as("local database on node %d is not Raft-wrapped", holder)
        .isInstanceOf(LocalDatabase.class);
    assertThat(serverDb.getMode()).as("local database on node %d is read-only", holder).isEqualTo(ComponentFile.MODE.READ_ONLY);
    assertThat(serverDb.countType("Ad", true)).as("records on node %d", holder).isEqualTo(LOCAL_RECORDS);
    try (final ResultSet rs = serverDb.query("sql", "select node from Holder")) {
      assertThat(rs.next().<Integer>getProperty("node")).as("node %d still serves its own copy", holder).isEqualTo(holder);
    }
  }

  /**
   * Stops {@code follower}, writes past the compaction threshold on {@code leader} and restarts it. Whether Ratis catches
   * it up by an InstallSnapshot or by replay depends on what the leader purged meanwhile (Issue8643RecountDuringSnapshotCatchUpIT
   * needs up to four rounds for one install), so this records which happened; the install's database work is asserted
   * deterministically by {@link #reconcileFromLeaderAsAnInstallDoes}.
   */
  private void catchUpAfterDowntime(final int follower, final int leader) throws Exception {
    getServer(follower).stop();
    writeReplicated(leader, "while-" + follower + "-down", OFFLINE_WRITES);
    restartServer(follower);
    LogManager.instance().log(this, Level.INFO, "TEST: node %d caught up by %s", follower,
        getRaftPlugin(follower).getRaftHAServer().getStateMachine().isBelowInstalledRaftBoundary(0) ? "a snapshot install" : "log replay");
  }

  /** One transaction, so one Raft entry, per record: enough of them cross the compaction threshold. */
  /** The stale-follower recovery: downloads every database this follower holds from the leader. */
  private void resyncEveryDatabaseFromLeader(final int follower) {
    final ArcadeStateMachine machine = getRaftPlugin(follower).getRaftHAServer().getStateMachine();
    machine.recoverFromPersistentLag();
    Awaitility.await("resync on node " + follower).atMost(60, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
        .until(() -> !machine.isSnapshotDownloadPending());
  }

  private DatabaseReconciler.ReconcileFromLeaderResult reconcileFromLeaderAsAnInstallDoes(final int follower, final int leader)
      throws Exception {
    final RaftHAServer leaderRaft = getRaftPlugin(leader).getRaftHAServer();
    final RaftHAServer followerRaft = getRaftPlugin(follower).getRaftHAServer();
    return followerRaft.getStateMachine().getReconciler().reconcileDatabasesFromLeader(leaderRaft.getLocalPeerId().toString(),
        "localhost:" + getServer(leader).getHttpServer().getPort(), null, followerRaft.getClusterToken(), -1L);
  }

  private void writeReplicated(final int leader, final String tag, final int count) {
    final Database db = getServerDatabase(leader, getDatabaseName());
    db.transaction(() -> {
      if (!db.getSchema().existsType("Item"))
        db.getSchema().createVertexType("Item");
    });
    for (int i = 0; i < count; i++) {
      final int seq = i;
      db.transaction(() -> db.newVertex("Item").set("id", tag + "-" + seq).set("payload", PAYLOAD).save());
    }
  }

  private void moveLeadershipTo(final int target) throws Exception {
    final int leader = findLeaderIndex();
    assertThat(leader).as("a Raft leader must be elected").isGreaterThanOrEqualTo(0);
    if (leader == target)
      return;
    final RaftPeerId targetId = getRaftPlugin(target).getRaftHAServer().getLocalPeerId();
    getRaftPlugin(leader).getRaftHAServer().transferLeadership(targetId.toString(), 10_000);
    Awaitility.await("node " + target + " leads").atMost(30, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
        .until(() -> getRaftPlugin(target).isLeader());
  }

  private String databaseDirectory(final int serverIndex) {
    return getServer(serverIndex).getConfiguration().getValueAsString(GlobalConfiguration.SERVER_DATABASE_DIRECTORY);
  }

  private int httpServerCommand(final int serverIndex, final String command) throws Exception {
    final HttpURLConnection connection = (HttpURLConnection) new URI(getServerHttpUrl(serverIndex, "/api/v1/server")).toURL()
        .openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Authorization",
        "Basic " + Base64.getEncoder().encodeToString(("root:" + BaseGraphServerTest.DEFAULT_PASSWORD_FOR_TESTS).getBytes()));
    try {
      formatPayload(connection, new JSONObject().put("command", command));
      connection.connect();
      final int status = connection.getResponseCode();
      if (status != 200)
        LogManager.instance().log(this, Level.WARNING, "TEST: '%s' on node %d answered %d: %s", command, serverIndex, status,
            readError(connection));
      return status;
    } finally {
      connection.disconnect();
    }
  }

  private int httpRequest(final int serverIndex, final String route, final String dbName, final String sql) throws Exception {
    final HttpURLConnection connection = (HttpURLConnection) new URI(
        getServerHttpUrl(serverIndex, "/api/v1/" + route + "/" + dbName)).toURL().openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Authorization",
        "Basic " + Base64.getEncoder().encodeToString(("root:" + BaseGraphServerTest.DEFAULT_PASSWORD_FOR_TESTS).getBytes()));
    try {
      formatPayload(connection, "sql", sql, null, new HashMap<>());
      connection.connect();
      final int status = connection.getResponseCode();
      if (status != 200)
        LogManager.instance().log(this, Level.WARNING, "TEST: '%s' on node %d / '%s' answered %d: %s", sql, serverIndex, dbName,
            status, readError(connection));
      return status;
    } finally {
      connection.disconnect();
    }
  }
}
