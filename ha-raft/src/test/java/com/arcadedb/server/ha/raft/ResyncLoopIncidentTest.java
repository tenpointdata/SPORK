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
import com.arcadedb.exception.WALVersionGapException;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.ServerDatabase;
import com.sun.net.httpserver.HttpServer;
import org.apache.ratis.protocol.RaftPeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the resync loop of the SPORK incident of 2026-11-12 (item 2): after {@code stress_spork_r2}
 * diverged on a WAL version gap, each follower re-downloaded {@code dev_testing} and the 10.8 GB {@code stress_spork_b8}
 * every 65-78 s for 12 hours, never requested {@code stress_spork_r2}, and logged nothing between two downloads.
 * <p>
 * The root cause was the full resync iterating {@code ArcadeDBServer.getDatabaseNames()}, a LIVE view of the registry's
 * {@code ConcurrentHashMap}, while every install removes the database from that map and puts it back. Two names that
 * share a bin - the incident's own {@code dev_testing} and {@code stress_spork_b8} in a 16-bin table - send the
 * weakly consistent iterator back and forth between them for ever. These tests use the incident's database names, so
 * the collision is the real one.
 * <p>
 * Fixture as in {@link Issue8464ResyncCoversClosedDatabaseTest}: a real {@link ArcadeDBServer} (HA off), a real
 * {@link ArcadeStateMachine}, a {@link FakeRaftHAServer} follower view, and a local HTTP server standing in for the
 * leader's snapshot endpoint, whose copies carry a different record count so an install is observable.
 */
@Timeout(180)
class ResyncLoopIncidentTest {

  private static final String     BENCH          = "bench_spork";
  private static final String     DEV            = "dev_testing";
  private static final String     B8             = "stress_spork_b8";
  private static final String     R2             = "stress_spork_r2";
  private static final String     PASSWORD       = "DefaultPasswordForTests";
  private static final int        LIVE_COUNT     = 12;
  private static final int        SNAPSHOT_COUNT = 27;
  /** Past this many serves of one database the stub leader fails it, so a regression loops a bounded number of times. */
  private static final int        LOOP_BREAKER   = 6;
  private static final RaftPeerId LOCAL          = RaftPeerId.valueOf("local");
  private static final RaftPeerId LEADER         = RaftPeerId.valueOf("leader");

  @TempDir
  Path root;

  private       ArcadeDBServer                server;
  private       ArcadeStateMachine            sm;
  private       FakeRaftHAServer              raft;
  private       HttpServer                    leader;
  private       String                        leaderAddress;
  private final Map<String, AtomicInteger>    downloads = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() throws IOException {
    server = startServer();
    leader = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    leader.start();
    leaderAddress = "localhost:" + leader.getAddress().getPort();
    sm = newStateMachine(true);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (sm != null)
      sm.close();
    if (leader != null)
      leader.stop(0);
    stopServer();
  }

  /** The names are the incident's, and the collision they cause is the precondition of the loop. */
  @Test
  void theIncidentsDatabaseNamesShareARegistryBin() {
    assertThat(bin16(DEV)).as("dev_testing and stress_spork_b8 collide in a 16-bin ConcurrentHashMap").isEqualTo(bin16(B8));
    assertThat(bin16(R2)).as("the diverged database sits in a later bin, which the loop never reached")
        .isGreaterThan(bin16(B8));
  }

  /**
   * The loop itself: a full resync over the incident's databases installs each of them exactly once and completes. It
   * used to re-install dev_testing and stress_spork_b8 alternately until the stub leader stopped serving them, and never
   * asked for stress_spork_r2.
   */
  @Test
  void aFullResyncInstallsEveryDatabaseOnceDespiteTheBinCollision() throws Exception {
    for (final String name : List.of(BENCH, DEV, B8, R2)) {
      createLocalDatabase(name);
      leaderServes(name);
    }
    setStaleSnapshotAppliedFloor(5L);

    sm.triggerSnapshotDownload("test: stale snapshot marker");

    for (final String name : List.of(BENCH, DEV, B8, R2))
      assertThat(downloadsOf(name)).as("'%s' is downloaded exactly once", name).isEqualTo(1);
    for (final String name : List.of(BENCH, DEV, B8, R2))
      assertThat(liveCount(name)).as("'%s' carries the leader's copy", name).isEqualTo(SNAPSHOT_COUNT);
    assertThat(sm.getStaleSnapshotAppliedFloor()).as("the pass completed and resolved the floor").isEqualTo(-1L);
    assertThat(sm.getResyncLoopGuard().isIdle()).as("a completed pass leaves no attempt pending").isTrue();
  }

  /**
   * A WAL version gap quarantines ONE database and resyncs that one alone: the larger healthy databases are not
   * downloaded at all (they were, ~550 times each, in the incident), and the diverged one converges.
   */
  @Test
  void aWalGapResyncsTheDivergedDatabaseFirstAndAlone() throws Exception {
    for (final String name : List.of(BENCH, DEV, B8, R2)) {
      createLocalDatabase(name);
      leaderServes(name);
    }

    divergeOnWalVersionGap(R2);

    waitFor(() -> !sm.isDatabaseDiverged(R2), "the targeted resync of the diverged database lifts its quarantine");
    assertThat(downloadsOf(R2)).as("the diverged database is downloaded").isEqualTo(1);
    assertThat(liveCount(R2)).as("and carries the leader's copy").isEqualTo(SNAPSHOT_COUNT);
    for (final String healthy : List.of(BENCH, DEV, B8)) {
      assertThat(downloadsOf(healthy)).as("healthy '%s' is not re-downloaded", healthy).isZero();
      assertThat(liveCount(healthy)).as("healthy '%s' keeps its own copy", healthy).isEqualTo(LIVE_COUNT);
    }
    assertThat(sm.isResyncInProgress()).isFalse();
  }

  /**
   * An install that keeps failing trips the loop guard after {@code arcadedb.ha.resyncLoopMaxAttempts} attempts (2 here):
   * the third attempt raises the alert, and the retries after it back off instead of downloading again.
   */
  @Test
  void theLoopGuardTripsAndBacksOffWhenAnInstallKeepsFailing() throws Exception {
    createLocalDatabase(B8);
    createLocalDatabase(R2);
    leaderServes(B8);
    leaderFails(R2);
    sm.markStateDiverged(R2, DivergenceCause.WAL_VERSION_GAP);

    for (int attempt = 1; attempt <= 3; attempt++) {
      final int expected = attempt;
      tick();
      waitFor(() -> attemptsOf(R2) == expected && !sm.getLocalResyncState().snapshotDownloadInProgress(),
          "attempt " + expected + " of the targeted resync ran");
    }
    final ResyncLoopGuard.Episode episode = sm.getResyncLoopGuard().episodeOf(R2);
    assertThat(episode.tripped()).as("the third attempt exceeds the limit of 2").isTrue();
    assertThat(episode.lastOutcome()).as("the reason is kept").contains("failed").contains("HTTP 503");
    assertThat(sm.getResyncLoopGuard().backoffRemainingMs(R2)).as("and the next one waits").isPositive();

    final int downloadsWhenTripped = requestsOf(R2);
    for (int i = 0; i < 3; i++) {
      tick();
      Thread.sleep(200);
    }
    assertThat(requestsOf(R2)).as("no download while backing off").isEqualTo(downloadsWhenTripped);
    assertThat(attemptsOf(R2)).isEqualTo(3);
    assertThat(downloadsOf(B8)).as("the healthy database is never touched").isZero();
    assertThat(sm.isDatabaseDiverged(R2)).as("still quarantined, so still refused for reads").isTrue();

    // Published as the resync-loop alert...
    final JSONArray alerts = new JSONArray();
    ClusterAlerts.addResyncLoopAlert(sm.getResyncLoopGuard().tripped(), null, alerts);
    assertThat(alerts.length()).isEqualTo(1);
    final JSONObject alert = alerts.getJSONObject(0);
    assertThat(alert.getString("id")).isEqualTo("resync-loop");
    assertThat(alert.getString("severity")).isEqualTo("critical");
    final JSONObject entry = alert.getJSONObject("details").getJSONArray("databases").getJSONObject(0);
    assertThat(entry.getString("database")).isEqualTo(R2);
    assertThat(entry.getInt("attempts")).isEqualTo(3);
    assertThat(entry.getString("lastOutcome")).contains("HTTP 503");

    // ...and in the report the leader reads for its per-peer status (item 3)
    final JSONObject report = sm.getDatabaseResyncReport();
    final JSONObject r2 = report.getJSONObject("databases").getJSONObject(R2);
    assertThat(r2.getBoolean("quarantined")).isTrue();
    assertThat(r2.getString("cause")).isEqualTo(DivergenceCause.WAL_VERSION_GAP.name());
    assertThat(r2.getString("resyncState")).isEqualTo(ArcadeStateMachine.RESYNC_STATE_BACKING_OFF);
    assertThat(r2.getBoolean("resyncLoop")).isTrue();
    assertThat(r2.getInt("resyncAttempts")).isEqualTo(3);
    assertThat(report.getJSONObject("databases").has(B8)).as("a healthy database is not reported").isFalse();
  }

  /**
   * The report's restart: the node stops mid-download with {@code stress_spork_b8}'s {@code .snapshot-new} and a
   * 0-byte {@code .snapshot-pending} on disk while {@code stress_spork_r2} is quarantined. On restart the interrupted
   * swap is recovered, the quarantine is restored, nothing is downloaded while no leader is known (in the incident the
   * other followers were stopped, so there was no quorum), and once a leader is known only the quarantined database is
   * resynced.
   */
  @Test
  void aRestartWithALeftoverPendingSwapAndAQuarantineResyncsOnlyTheQuarantinedDatabase() throws Exception {
    createLocalDatabase(B8);
    createLocalDatabase(R2);
    leaderServes(B8);
    leaderServes(R2);
    sm.markStateDiverged(R2, DivergenceCause.WAL_VERSION_GAP);
    sm.close();
    sm = null;
    stopServer();

    // The disk state the operator found at stop time
    final Path b8Dir = root.resolve("databases").resolve(B8);
    Files.writeString(Files.createDirectories(b8Dir.resolve(SnapshotInstaller.SNAPSHOT_NEW_DIR)).resolve("partial.bin"),
        "half of a download");
    Files.createFile(b8Dir.resolve(SnapshotInstaller.SNAPSHOT_PENDING_FILE));

    server = startServer();
    SnapshotInstaller.recoverPendingSnapshotSwaps(root.resolve("databases"), server);
    assertThat(Files.exists(b8Dir.resolve(SnapshotInstaller.SNAPSHOT_PENDING_FILE))).as("the swap is recovered").isFalse();
    assertThat(liveCount(B8)).as("the interrupted swap kept the copy it had").isEqualTo(LIVE_COUNT);

    // No leader yet: nothing can be downloaded, and the tick says so instead of nothing
    sm = newStateMachine(false);
    assertThat(sm.isDatabaseDiverged(R2)).as("the quarantine survives the restart").isTrue();
    tick();
    Thread.sleep(300);
    assertThat(requestsOf(R2) + requestsOf(B8)).as("no download without a leader").isZero();

    // A leader is elected: the quarantined database alone is resynced
    raft.leaderId(LEADER);
    raft.peerHttpAddress(LEADER, leaderAddress);
    tick();
    waitFor(() -> !sm.isDatabaseDiverged(R2), "the quarantined database is resynced once a leader is known");
    assertThat(liveCount(R2)).isEqualTo(SNAPSHOT_COUNT);
    assertThat(downloadsOf(B8)).as("the recovered database is not re-downloaded").isZero();
    assertThat(liveCount(B8)).isEqualTo(LIVE_COUNT);
  }

  // ------------------------------------------------------------------------------------------------------------

  private ArcadeStateMachine newStateMachine(final boolean withLeader) {
    raft = FakeRaftHAServer.detached();
    raft.leader(false);
    raft.localPeerId(LOCAL);
    raft.localHttpAddress("local-host:2480");
    raft.clusterToken(null);
    if (withLeader) {
      raft.leaderId(LEADER);
      raft.peerHttpAddress(LEADER, leaderAddress);
    }
    final ArcadeStateMachine machine = new ArcadeStateMachine();
    machine.setServer(server);
    machine.setRaftHAServer(raft);
    return machine;
  }

  /** One HealthMonitor tick with the throttle reset, so each call is a real retry. */
  private void tick() throws Exception {
    final Field f = ArcadeStateMachine.class.getDeclaredField("lastStaleSnapshotRetryMs");
    f.setAccessible(true);
    ((AtomicLong) f.get(sm)).set(0L);
    sm.retryUnfilledSnapshotGap();
  }

  private int attemptsOf(final String name) {
    final ResyncLoopGuard.Episode e = sm.getResyncLoopGuard().episodeOf(name);
    return e == null ? 0 : e.attempts();
  }

  /** What the apply thread does on the incident's first gap. */
  private void divergeOnWalVersionGap(final String name) throws Exception {
    final Method m = ArcadeStateMachine.class.getDeclaredMethod("divergedOnWalVersionGap", String.class, long.class,
        long.class, WALVersionGapException.class);
    m.setAccessible(true);
    m.invoke(sm, name, 984019L, 4383041L, new WALVersionGapException(
        "Cannot apply changes to the database because modified page PageId(" + name + "/2276/1) version in WAL (3) "
            + "does not match with existent version (1) fileId=2276"));
  }

  private static void waitFor(final BooleanSupplier condition, final String what) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + 60_000L;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline)
        throw new AssertionError("Timed out waiting until " + what);
      Thread.sleep(25);
    }
  }

  private static int bin16(final String name) {
    final int h = name.hashCode();
    return (h ^ (h >>> 16)) & 15;
  }

  private void createLocalDatabase(final String name) {
    final ServerDatabase live = server.getOrCreateDatabase(name);
    live.transaction(() -> {
      live.getSchema().createVertexType("Node");
      for (int i = 0; i < LIVE_COUNT; i++)
        live.newVertex("Node").set("v", i).save();
    });
  }

  private long liveCount(final String name) {
    return server.getDatabaseForOperator(name).countType("Node", true);
  }

  /** Downloads served in full. */
  private int downloadsOf(final String name) {
    final AtomicInteger count = downloads.get(name);
    return count == null ? 0 : count.get();
  }

  /** Every request for {@code name}, served or failed. */
  private int requestsOf(final String name) {
    final AtomicInteger count = downloads.get(name + "#requests");
    return count == null ? 0 : count.get();
  }

  private void setStaleSnapshotAppliedFloor(final long floor) throws Exception {
    final Field f = ArcadeStateMachine.class.getDeclaredField("staleSnapshotAppliedFloor");
    f.setAccessible(true);
    ((AtomicLong) f.get(sm)).set(floor);
  }

  private void leaderFails(final String name) {
    final AtomicInteger requests = downloads.computeIfAbsent(name + "#requests", k -> new AtomicInteger());
    leader.createContext("/api/v1/ha/snapshot/" + name, exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
    });
  }

  /** Serves a real database with a DIFFERENT record count under {@code name}, failing it past {@link #LOOP_BREAKER}. */
  private void leaderServes(final String name) throws IOException {
    final Path source = root.resolve("leader-copy").resolve(name);
    try (final Database snap = new DatabaseFactory(source.toString()).create()) {
      snap.transaction(() -> {
        snap.getSchema().createVertexType("Node");
        for (int i = 0; i < SNAPSHOT_COUNT; i++)
          snap.newVertex("Node").set("v", i).save();
      });
    }
    final byte[] zip = zipDirectory(source);
    final AtomicInteger counter = downloads.computeIfAbsent(name, k -> new AtomicInteger());
    final AtomicInteger requests = downloads.computeIfAbsent(name + "#requests", k -> new AtomicInteger());
    leader.createContext("/api/v1/ha/snapshot/" + name, exchange -> {
      requests.incrementAndGet();
      if (counter.get() >= LOOP_BREAKER) {
        exchange.sendResponseHeaders(503, -1);
        exchange.close();
        return;
      }
      counter.incrementAndGet();
      exchange.sendResponseHeaders(200, zip.length);
      exchange.getResponseBody().write(zip);
      exchange.close();
    });
  }

  private static byte[] zipDirectory(final Path dir) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (final ZipOutputStream zip = new ZipOutputStream(bytes); final Stream<Path> files = Files.walk(dir)) {
      for (final Path file : files.filter(Files::isRegularFile).toList()) {
        zip.putNextEntry(new ZipEntry(dir.relativize(file).toString().replace('\\', '/')));
        zip.write(Files.readAllBytes(file));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private ArcadeDBServer startServer() throws IOException {
    final Path databasesDir = root.resolve("databases");
    Files.createDirectories(databasesDir);

    final ContextConfiguration config = new ContextConfiguration();
    config.setValue(GlobalConfiguration.SERVER_NAME, "ArcadeDB_ResyncLoop");
    config.setValue(GlobalConfiguration.SERVER_DATABASE_DIRECTORY, databasesDir.toString());
    config.setValue(GlobalConfiguration.SERVER_ROOT_PATH, root.toString());
    config.setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, PASSWORD);
    config.setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_HOST, "localhost");
    config.setValue(GlobalConfiguration.HA_ENABLED, false);
    // Fail a download on its first attempt: the exponential backoff would only make the test slow.
    config.setValue(GlobalConfiguration.HA_SNAPSHOT_INSTALL_RETRIES, 0);
    config.setValue(GlobalConfiguration.HA_SNAPSHOT_INSTALL_RETRY_BASE_MS, 0L);
    config.setValue(GlobalConfiguration.NETWORK_USE_SSL, false);
    config.setValue(GlobalConfiguration.HA_RESYNC_LOOP_MAX_ATTEMPTS, 2);
    config.setValue(GlobalConfiguration.HA_RESYNC_LOOP_BACKOFF_BASE, 60_000L);

    final ArcadeDBServer started = new ArcadeDBServer(config);
    started.start();
    return started;
  }

  private void stopServer() {
    if (server == null)
      return;
    for (final String name : List.of(BENCH, DEV, B8, R2))
      try {
        if (server.existsDatabase(name))
          ((DatabaseInternal) server.getDatabaseForOperator(name)).getEmbedded().close();
      } catch (final Exception ignore) {
        // best-effort; the @TempDir is removed regardless
      }
    server.stop();
    server = null;
  }
}
