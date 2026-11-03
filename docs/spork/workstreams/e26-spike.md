# Workstream: E26 spike, a node-local database beside the Raft group

Phase 0 (P0c) gate for E26 (architecture 0.8, 3.5, 3.6 and the E26 row of section 8): warm sealed period partitions
(`rec_<p>`, `txt_<p>`, `media_<p>`) leave the Raft group and are held as read-only, node-local databases on 2 of the 4
nodes. The question this spike answers: can a server running `ha-raft` also serve a database that is not replicated, and
what does it take?

Branch `claude/e26-spike-2404a4`. Line numbers below are at the base commit `601343a5b` unless marked "patched".

## Answer

**No, not on stock ha-raft. Yes with a small, setting-gated change, which this branch carries and tests.**

A database placed beside the group on stock code is not left alone: it is wrapped for replication, elected into the
cold-start bootstrap baseline, listed to joiners, refreshed by snapshot installs and resyncs, and can be marked so it never
reopens. The first scenario of the new IT, run on stock ha-raft, showed the worst of it in its own log: at cluster
formation the bootstrap election took one holder's copy as the cluster baseline, **overwrote the other holder's copy**
("bootstrap mismatch ... reinstalling from leader-shipped full snapshot") and **created it on the node that never held
it** ("database not yet present locally, will be created via leader-shipped snapshot").

The change: a server setting `arcadedb.ha.localDatabases` (`GlobalConfiguration.HA_LOCAL_DATABASES`, names or `prefix*`
entries). A listed database is opened READ_ONLY, never wrapped, and filtered out of every ha-raft path that walks the
server's databases. About 140 lines of main code across 13 files, no new dependency, no wire-format or Raft-entry change,
default empty (stock behaviour).

## What stock ha-raft does to a database it should not own

Every finding is a path that iterates `ArcadeDBServer.getDatabaseNames()` (or the database directory) and assumes every
name is the group's. `ServerDatabase.isReplicated()` (`server/.../ServerDatabase.java:218`) is per database, but it only
reports what the wrapper is; nothing decides it.

| # | Path | Stock behaviour for a local database | Evidence (base commit) |
|---|---|---|---|
| 1 | Wrapping | Every database the server opens is wrapped in `RaftReplicatedDatabase`, so its writes and schema changes become Raft entries for every peer; a peer that lacks the database fails the apply | `ha-raft/.../RaftHAPlugin.java:199-203`, `server/.../ArcadeDBServer.java:2306` (`wrapForHA`), `:2339` (`rewrapDatabases`), `:2652` |
| 2 | Bootstrap election | Every local database gets a fingerprint and an elected cluster baseline; a peer whose copy differs reinstalls the winner's, a peer without it creates it | `BootstrapElection.java:995` (`computeLocalStates`), `:1015` (`collectLocalDatabaseNames`), `ArcadeStateMachine.java:5510` (`decideBootstrapFingerprintEntry`), `:5611`, `:5702`; observed in the stock run |
| 3 | Leader's listing (auto-acquire) | The bootstrap-state listing a joiner reads names every database the leader has, so a node without it acquires it | `PostBootstrapStateHandler.java:181` (`localDatabaseStates`), `DatabaseReconciler.java:175` (`classifyReconcile`) |
| 4 | Snapshot install reconcile | Every registered database is refreshed from the leader: a holder's copy is replaced by the leader's, or, under a leader that does not hold it, reported LEADER_MISSING | `DatabaseReconciler.java:472` (`localUserDatabaseNames`), `:577` (`refreshExistingDatabases`) |
| 5 | Stale-follower recovery | `downloadAllDatabasesFrom` reinstalls every database the follower holds | `ArcadeStateMachine.java:8971` |
| 6 | Closed copy under a leader that lacks it | A local database the idle closer closed (it is not replicated, so `DatabaseIdleCloser.java:216` does close it) is listed as a closed copy; resynced against a leader that does not hold it, it is **marked unverified, and a follower then refuses to reopen it** | `SnapshotInstaller.java:936` (`closedDatabaseNames`), `ArcadeStateMachine.java:9009`, `ArcadeDBServer.java:2602` |
| 7 | Stalled-replica resync orders | The leader orders a stalled follower to resync every database, local ones included | `RaftHAServer.java:932` |
| 8 | Snapshot endpoint | Serves any database by name, so a peer can install a holder's local copy | `SnapshotHttpHandler.java:328`, `:649` |
| 9 | Applied-index bookkeeping | Records the snapshot index as applied for every database name | `ArcadeStateMachine.java` `writePersistedAppliedIndexForAllDatabases`, the install bookkeeping and `accept-stale-snapshot` loops |
| 10 | Restore and drop | `restore database` and `drop database` sent over HTTP to a follower are forwarded to the leader (`PostServerCommandHandler.java:110`), and a restore takes a hold that must be the leader's (`RaftHAServer.java:3164`), so a holder cannot admit or drop its own copy | `PostServerCommandHandler.java:110`, `RaftHAServer.java:3164` (`ensureRestoreHoldStands`) |
| 11 | Mode | Opened READ_WRITE (the server default) | `ArcadeDBServer.java:2624` |

What already works on stock: Ratis runs one group per cluster (`RaftHAServer.java:693`, group id from the cluster name), and
the group never learns about a database unless one of the paths above names it. `HA_AUTO_ACQUIRE_DATABASES=false`
(`GlobalConfiguration.java:2867`) only stops path 3; paths 1, 2, 4 to 10 remain. A drop of an unwrapped database is local
(`ServerControlPlane.java:1140`) and a restore of one is not replicated (`ServerControlPlane.java:2807`), which is what
makes a per-holder admission possible.

## The change (patched)

| Where | What |
|---|---|
| `GlobalConfiguration.HA_LOCAL_DATABASES` | `arcadedb.ha.localDatabases`, comma-separated names; an entry ending in `*` is a prefix (so admitted copies such as `rec_<p>_w<n>` need no restart). Read at startup. Same value on every node |
| `ArcadeDBServer` | `isHALocalDatabase(name)` (parsed once in `init()`); opens a listed database READ_ONLY whatever the default; refuses to create one (`createDatabase`, and the create arm of `getDatabase`) |
| `RaftHAPlugin` | the wrapper returns a listed database unwrapped; the three restore-hold hooks skip it (a restore of it is this node's alone, on any role) |
| `BootstrapElection`, `PostBootstrapStateHandler` | left out of the election and of the listing peers read (paths 2, 3) |
| `DatabaseReconciler`, `SnapshotInstaller.closedDatabaseNames` | left out of the reconcile's local set, the refresh and the closed-copy scan (paths 4, 6) |
| `ArcadeStateMachine` | left out of `downloadAllDatabasesFrom` and the three applied-index loops (paths 5, 9); an entry naming it is applied as a no-op and logged SEVERE (only a differently configured leader can write one) |
| `RaftHAServer`, `SnapshotHttpHandler`, `PostResyncDatabaseHandler`, `GetClusterHandler` | no stalled-resync order, 404 on the snapshot and checksum routes, 400 on a manual resync, not in the presence matrix (paths 7, 8) |
| `PostServerCommandHandler`, `ArcadeDBServer.dropLocalDatabase` | `restore database` and `drop database` of a listed name are not forwarded to the leader and need no leadership: they act on the node they are sent to (path 10; on stock the follower's request ran on the leader). A drop closes the READ_ONLY instance and deletes its files, since the engine's `drop()` refuses a READ_ONLY database |

Writes are refused by the engine's own READ_ONLY mode (`DatabaseIsReadOnlyException`), so nothing in the commit path
changed. One consequence the plugin must know: `/api/v1/command` refuses even a `SELECT` on a READ_ONLY database; reads
use `/api/v1/query` (or the embedded API).

## Evidence: `NodeLocalDatabaseBesideRaftGroupIT`

`ha-raft/src/test/java/com/arcadedb/server/ha/raft/NodeLocalDatabaseBesideRaftGroupIT.java`, a `BaseRaftHATest` fixture
(ports from the fixture), 3 nodes, majority quorum, persistent Raft storage, snapshot every 10 entries with log purge.
Nodes 0 and 1 hold `warm` (100 records plus a `Holder` record naming the node), node 2 holds none; the setting is
`warm*` on every node. Any path that refreshed, acquired or replaced a copy would show as a missing database on a holder,
a copy on node 2, or a holder marker naming the wrong node.

| Test | Shows |
|---|---|
| `readsAreServedWritesRefusedAndTheGroupKeepsApplying` | reads from each holder's own copy (embedded and HTTP `/api/v1/query` on a follower); READ_ONLY, unwrapped, `isReplicated()` false; inserts and DDL refused on the leader's and a follower's copy, embedded and over HTTP; the replicated database keeps applying on all 3 nodes after the refusals |
| `snapshotInstallAutoAcquireAndResyncLeaveItAlone` | with the leader holding `warm` and auto-acquire on: a non-holder and a holder rejoin after 600 writes past the compacted log; the install's reconcile (leader listing, acquire, refresh, leader-missing) driven against the live leader; the stale-follower resync on both; `warm` never appears on node 2 (not even on disk), each holder keeps its own copy, the group converges |
| `leaderChangeNeitherTouchesNorHaltsOnIt` | leadership moved to a holder, to the non-holder (whose listing lacks `warm`) and to the other holder, with writes and convergence after each; a holder rejoins under the non-holder leader; then its copy is closed (as the idle closer does), reconciled and resynced against that leader, and reopens (no unverified mark) |
| `rewrittenCopyIsAdmittedToEachHolderAloneAndTheOldOneDropped` | the aging admission targeted at the 2 holders: an offline-built `warm_w2` archive restored with `restore database` on the leader holder and on the follower holder; admitted READ_ONLY and unwrapped on both, never on node 2; the old `warm` dropped on each holder alone; the group keeps applying |

Failing first: on stock ha-raft (only the setting key added) all scenarios fail, see "Runs" below. A real Ratis
InstallSnapshot is not reproducible on every restart in process (`Issue8643RecountDuringSnapshotCatchUpIT` needs up to
four rounds for one), so the restart catch-up records whether an install ran and the install's database work is asserted
by calling `DatabaseReconciler.reconcileDatabasesFromLeader` directly, the call `notifyInstallSnapshotFromLeader` makes.

### Runs

All with `mvn -o -pl ha-raft -am verify -DskipITs=false`, 2026-11-03, macOS, JDK 26.

| Run | Result |
|---|---|
| Stock ha-raft and server (main sources at `601343a5b`, only the setting key added so the IT compiles) | `NodeLocalDatabaseBesideRaftGroupIT` **4 of 4 fail**: `warm` is Raft-wrapped (`isReplicated()` true) on both holders; at formation the log shows `Bootstrap source for 'warm'`, then `'warm' bootstrap mismatch ... reinstalling from leader-shipped full snapshot` on one holder and `will be created via leader-shipped snapshot` on the non-holder; the admission on the follower holder is refused (400, forwarded to the leader, where the name now exists) |
| Patched (commit `e0f3cf593`) | `NodeLocalDatabaseBesideRaftGroupIT` **4 of 4 pass** (122 s); `HALocalDatabasesSettingTest` (server) 4 of 4 |
| Connected ha-raft ITs, patched | pass: `BootstrapElectionIT`, `RaftBootstrapLeadershipTransferIT`, `PostBootstrapStateHandlerIT`, `SnapshotAcquireNewDatabaseIT`, `SnapshotInstallerIntegrationIT`, `RaftFullSnapshotResyncIT`, `Issue8346SnapshotCaughtUpJoinerConvergesByMatchIT`, `Issue8468QuarantinedSnapshotSourceIT`, `Issue4740Phase2ReconcileIT`, `RaftRestoreDatabase3NodesIT`, `RaftDropDatabase3NodesIT`, `HaRestoreClaimIT`, `GetClusterHandlerIT`, `RaftLeaderFailoverIT`; `HaLeadershipTransferIT` 8 of 9, see below |
| Connected ha-raft unit tests, patched | 142 of 142 pass: `DatabaseReconcilerTest`, the closed-copy family (`Issue8464`, `Issue8589`, `Issue8605`, `Issue8606`, `Issue8641`), the install family (`Issue6202`, `Issue6760`, `Issue8360`, `Issue8702`, `Issue10051`, `Issue8374`), the bootstrap-state family (`Issue8658`, `Issue8843`, `BootstrapStateProbeParsingTest`), `Issue6125SnapshotRouteValidationTest` |
| Connected server unit tests, patched | pass: `PostServerCommandHandlerSsrfTest`, `Issue7400ServerCommandSpecTest`, `Issue7641DropDatabaseSlotTest` |

**Open: `HaLeadershipTransferIT.aTargetedTransferUnderConcurrentWritesHandsLeadershipToTheTarget`.** It passed 4 of 4
on stock and 2 of 5 on the patched build (the other 8 methods of the class pass). Each failure is one round of 12 with
`TIMED_OUT(10s)`; in the one examined, a second targeted transfer to the third peer started 0.5 s into the first
(`RaftClusterManager.sendTargetedTransfer`) and the leader stepped down to that peer instead. With the setting empty
every line of this change is a lookup in an empty set, and none is on the transfer path, so this reads as a timing race
the run order exposed rather than a regression, but the numbers do not prove it. Before the patch leaves the spike branch,
run the method 20 times on each build.

## Production E26: sizing

Inputs from the architecture (0.8, 3.5, section 8): a mid period group is `rec` 120 + `txt` warm 60 + `media` 36 =
216 GB; E26 saves 658 GB per node at month 12 and 1,166 GB at mid 3x; it must be in production before combination A
reaches the 2,400 GB trigger, about 2027-07 (train 3).

### Placement (plugin)

- Each warm partition (the three databases of a period, or each database independently) gets 2 holders: one of n2/n3
  and one of n1/n4, as the architecture fixes. n2 and n3 together then hold every warm partition, so a corpus-wide warm
  query is served by n2 + n3 (each answers its half, about 4.7 partitions per node at 3x) and degrades to the n1/n4
  partner of any partition whose n2/n3 holder is down.
- The partition map in `ops` (replicated) records, per partition: database name, holders, archive URL and checksum, the
  copy's lastTxId and fingerprint, state (`admitting`, `serving`, `draining`, `dropped`). Every node's plugin reads it from
  the Outbox event, as it does today.
- Names: every node-local name carries a reserved prefix (for example `w_`, setting `w_*` on all 4 nodes), so a
  replicated database can never take a local name and no restart is needed per admission. `createDatabase` refuses the
  prefix (patched), so a typo cannot create a replicated database under it.
- Remote reads: a node that does not hold a partition reads it through an internal plugin route on a holder (mTLS, the
  OC carried as for audited reads), with results merged as the fan-out across period databases already merges them.

### Admission of a rewritten partition to its 2 holders

The architecture's 3.5 admission (a `ServerControlPlane` restore on the leader, `INSTALL_DATABASE_ENTRY` with
forceSnapshot, every follower pulling at once on the apply thread) is replaced for warm partitions by a local restore on
each holder, which the IT exercises:

1. n4 builds the rewrite (aging, or a deletion batch) from the partition's latest archive, as today, and uploads the
   new archive; the map records it as `admitting` with its checksum.
2. Each holder in turn runs `restore database <name>_w<n> <archive url>`; no Raft entry, no hold on the group's writes,
   on any role. Each holder verifies the restored copy (record counts and fingerprint against the builder's) and reports.
3. One `ops` transaction switches the map; after the drain, `drop database <old>` on each holder (local, patched path
   tested).

Costs against today's path, per 120 GB database at the same 0.6 to 1.0 GB/s link: **no apply stall on any follower**
(today 6 to 10 minutes on all three, R18, and the stale-follower-recovery exposure it brings), 2 transfers instead of 3,
and a holder is unready only for its own swap. Disk per holder during admission: steady + archive 60 + new copy 120 =
+180 GB, the same envelope as the leader's today; only the 2 holders pay it, sequentially. The `transient-slot` lease
still serializes admissions with n4 builds and backups.

### Deletions against warm data

A read-only copy takes no in-place deletes. Deletion orders that touch a warm partition are recorded in a replicated
suppression list in `ops` that the plugin applies to every warm read at once (the record is invisible from the order's
commit), and are materialized by the next deletion-batch rewrite and admission, after which their suppression entries
are retired. The aging protocol's step 4 replay therefore moves to n4's build (the orders are applied before the archive
is cut), and step 7's acknowledgements wait for the switch. The deletion SLA becomes the batch cadence for physical
removal; the owner should confirm that cadence (governance, 9.2).

### Monitoring

- Per node: the local databases it holds, with mode, open state, size, lastTxId and fingerprint (a `localDatabases`
  section in `GET /api/v1/cluster`, engine, and a Micrometer gauge per database).
- Placement check (plugin, every minute): every `serving` partition has exactly its 2 holders reporting the map's
  fingerprint. Alerts: 1 holder (degraded, page in hours), 0 holders (warm data unavailable, page now), a holder with a
  different fingerprint, a local database the map does not know.
- Setting consistency: each node advertises its `HA_LOCAL_DATABASES` value (as peer capabilities are advertised today);
  a mismatch is an alert, and the SEVERE "Ignoring Raft entry ... node-local here" log line is an alert on its own.
- Free space before an admission (E14) on each holder, not only the leader.

### Failure cases

| Case | Behaviour | Recovery |
|---|---|---|
| One holder down | its partitions are served by the other holder; corpus-wide warm queries route the missing half to the n1/n4 partner | none needed; if the outage is long, re-admit its partitions to a third node from the archives (the map allows a temporary third holder) |
| Holder replaced (empty node) | auto-acquire pulls only the replicated databases (patched: local ones are not listed), so the replacement transfers about 1,166 GB less at mid 3x | the plugin re-admits the node's warm partitions from their archives, one at a time under the lease |
| Both holders of a partition lost | the partition is unavailable | restore from the archive to two nodes; the archive is the durable third copy, so it must exist and be verified **before** the hot copy leaves the group (aging step 1) |
| Holder copies diverge | cannot happen through the engine (READ_ONLY, never written) | the placement check catches disk-level damage by fingerprint; re-admit from the archive |
| Leader change, snapshot install, auto-acquire, stale-follower or stalled-replica resync | the local databases are not touched and nothing halts (IT) | none |
| Idle close of a local database | reopens READ_ONLY on the next request; no unverified mark from a resync (IT) | none |
| Setting differs across nodes | a leader without the setting treats the name as replicated (it could create or restore it cluster-wide; peers that list it ignore the entries, SEVERE) | the consistency alert; the prefix convention keeps names apart; fix the configuration and re-admit |
| Upstream ha-raft adds a new loop over `getDatabaseNames()` | it would include local databases again | E26 engine item: one `getReplicatedDatabaseNames()` accessor for ha-raft, and a source scan test (like `HardcodedTestServerPortsTest`) that refuses a bare `getDatabaseNames()` in ha-raft |

### Size (scope, not a schedule; gates as in section 8)

| Item | Scope (ew) | Notes |
|---|---|---|
| Engine: harden this patch | 1.5 | unit tests per filtered path; one `getReplicatedDatabaseNames()` accessor and the source-scan guard; `localDatabases` in `GET /api/v1/cluster` and the gauges; setting advertised for the consistency check |
| Engine: verified local restore | 0.5 | archive checksum check and a fingerprint in the restore result |
| Plugin: placement, map, remote read route, routing on holder loss | 3 | WS3 / WS7 |
| Plugin: admission orchestration (build, archive, per-holder restore, verify, switch, drain, drop) | 2 | replaces the leader-restore admission for warm partitions |
| Plugin: warm deletion suppression list and its retirement at rewrite | 1.5 | owner confirms the physical-deletion cadence |
| Plugin: holder replacement and re-admission playbook | 1 | |
| Monitoring and alerts | 1 | |
| P0c-style drill (holder loss, both-holder loss from archive, leader change during admission) | 0.5 | gate for train 3 |
| **Total** | **11** | engine 2, plugin 7.5, monitoring and drill 1.5 |

The engine share is small because ha-raft never needs to know about the warm partitions beyond leaving them alone; the
work is the plugin's placement, routing, admission and deletion handling. R18's admission stall disappears for warm
partitions as a side effect, which also shrinks the case for E16 (forced installs off the apply thread) to the hot
backfill admissions.

## Open points

- Ratis-level proof of a real InstallSnapshot with local databases present belongs in the P0c drill at full size, where
  purges are routine; in process the reconcile is asserted directly.
- Whether a warm partition's three databases share holders (simpler map, one switch) or are placed independently (finer
  balance) is a WS7 choice; nothing in the engine depends on it.
- The setting is read at startup; with the prefix convention that is enough. A runtime-reloadable list is not needed
  unless names cannot follow a prefix.
