# Workstream: HA commit pipelining (stress report issue 4)

Issue 4 of the sarah-clusters stress report: on a 4-node Raft cluster (quorum MAJORITY) single-vertex commits into a
1-bucket type cap at about 250 tx/s with latency growing linearly with the number of clients, while a 16-bucket type
reaches about 480 tx/s at constant latency. This note confirms the cause from the code and a measurement, sets out what
relieving it would take and why that is not safe to land now, and recommends the relief available today.

Base commit `14a014f`. Owner: the session that wrote it (issue 4). Issue 5 (commit-time conflicts) is handled separately.

## Answer

**The hypothesis holds.** On the leader a replicated commit takes the commit lock of every file it writes in phase 1 and
gives it back only after its Raft entry has been majority-acknowledged, applied by the leader's own state machine and
completed on the committing thread. Every commit into the same bucket (and the same per-bucket index files) therefore
waits one full replication round trip behind the previous one. Throughput per file is `1 / round trip`, whatever the
group committer does, because the second commit cannot reach the group committer until the first has left it.

**Pipelining is not landed.** It is sound at the Raft level (a dependent entry sits after the entry it builds on in one
log, so it can never commit without it), but the engine has at least six invariants that are kept by "the file lock
spans the publication", and for the workload the reporter actually runs (unique edge indexes, vertex indexes) it would
not remove the wait: an index page cannot be rebased, so the second commit would still have to wait for the first one's
apply. The design is below, with what has to be true before anyone builds it.

**Relief today: more buckets.** A type index is one index per bucket, so N buckets spread the bucket file AND its index
files over N independent locks. The reporter measured it (16 buckets: 484 tx/s at 131 ms against 254 tx/s at 247 ms).
Recommendation in "Cheap relief" below: document it prominently for HA databases, do not change the compiled-in default.

## Evidence

### The lock window, from the code

Leader path, `RaftReplicatedDatabase.commit()` (ha-raft) and `TransactionContext` (engine):

| Step | Where | Commit locks of the tx's files |
|---|---|---|
| 1. `commit1stPhase(true)` under the database read lock: `lockFilesFromChanges`, `lockFilesInOrder` (the lock is taken here), index changes, page version checks and slot / edge-append rebases, WAL buffer | `TransactionContext.commit1stPhase` | **taken** |
| 2. read lock released, `beginCommitInFlight` | `RaftReplicatedDatabase.commit` | held (`lockedFiles` stays set) |
| 3. `LocalCommit` registered with the state machine | `replicateAndCommitLocally` | held |
| 4. `TEST_PRE_DISPATCH_HOOK`, `broker.replicateTransaction` -> `RaftGroupCommitter.submitAndWait`: queue, flusher batch, Ratis async send | `replicateAndConclude` | held |
| 5. Leader: `startTransaction` (ledger `validateAndReserve`), `preAppendTransaction` (`confirmAppended`), append, replication to a majority, commit index advances | `ArcadeStateMachine` | held |
| 6. Leader apply thread: `publishLocalCommit` -> `publishCommittedPages` (validate and bump versions, WAL append, `publishPages`, bucket counts, corpus deltas) | `ArcadeStateMachine.publishLocalCommit` | held (by the committing thread, which is parked) |
| 7. Ratis reply reaches the committing thread; `TEST_POST_REPLICATION_HOOK` | `replicateAndConclude` | held |
| 8. `concludeLocalCommit` -> `completeCommit()` -> `finishCommitBookkeeping` (`onAfterCommit` per locked file) -> `concludePhase2` -> `reset()` -> `unlockFilesInOrder` | `TransactionContext.completeCommit` | **released** |

The javadoc of `RaftReplicatedDatabase.commit()` says "Replication (no lock held)". That is true of the database read
lock only; the per-file commit locks (`TransactionManager.fileIdsLockManager`) are held from step 1 to step 8.

Why a second commit into the same bucket cannot overtake: it blocks in `lockFilesInOrder` of its own phase 1. It could
not proceed anyway without the lock: its page of the bucket was loaded at version v, the first commit reserved v+1 in the
ledger, and `PageManager.checkPageVersion` consults `PageVersionReservations`, so it would fail with a retryable
`ConcurrentModificationException` and the slot rebase could not help (it rebases on the committed page, which is still
v until step 6).

`GlobalConfiguration.TX_WAL_GROUP_COMMIT` is the non-replicated analogue (the lock used to span the WAL flush) and its
description says replicated commits "are not affected". Correct: the HA path has its own, longer, wait.

### The measurement

`ha-raft/src/test/java/com/arcadedb/server/ha/raft/HaSameBucketCommitSerializationBenchmark.java`, tagged `benchmark`,
on a 3-node `SharedClusterRaftHATest` cluster. Run:

```
mvn -o -pl ha-raft -am test -Dtest=HaSameBucketCommitSerializationBenchmark -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
```

1. `aReplicatingCommitHoldsItsBucketLockFromPhaseOneUntilItCompletes` parks one commit at the dispatch to Raft (step 4)
   and again after the acknowledgement (step 7) and reads the leader's lock table (`TransactionManager.getLockStats()`):
   the bucket file is held at both points; a second commit into the same bucket shows up as a waiter on that file and
   stays queued across the acknowledgement; a commit into another 1-bucket type completes while the first is parked.
   This is state, not timing, so it is deterministic.
2. `sameBucketCommitsQueueBehindReplicationWhileSpreadBucketsDoNot` runs 16 threads x 40 single-vertex commits into a
   1-bucket type and then into a 16-bucket type, and compares the leader's commit-lock counters (#10509:
   `commitLockHolds`, `commitLockHoldNanos`, `commitLockHandOffs`, `commitLockHandOffNanos`). The hand-off share
   (releases that went straight to a commit already waiting) is the queue; the mean hold is the replication round trip.
   Assertions are on the hand-off share only; the times are printed, never asserted.

Results on the shared 4-core development machine (3 nodes in one JVM, so the round trip is a loopback one; figures are
for orientation, the hand-off share is the claim):

| Type | Commits | Lock releases | Handed to a waiting commit | Mean hold | Mean hand-off | Commits/s |
|---|---|---|---|---|---|---|
| 1 bucket | 640 | 640 | 639 (100%) | 14.5 ms | 0.79 ms | 65 |
| 16 buckets | 640 | 640 | 166 (26%) | 94.6 ms | 0.65 ms | 159 |

(2026-11-11, load average about 6 on 4 cores; a first run gave 100% / 15 ms / 64 per s and 23% / 87 ms / 173 per s.)

How to read it:

- With 1 bucket every release but the last went straight to a commit already queued on the lock: the commits run
  strictly one at a time. Throughput is `1 / mean hold` (1 / 14.5 ms = 69 per s, measured 65): the hold, which spans
  the Raft round trip, is the whole cap. That is the reporter's shape (about 4 ms per commit on real nodes, 250 per s).
- With 16 buckets three quarters of the commits found their bucket free. The mean hold grows because 16 commits share the
  replication pipeline of three servers in one JVM on 4 busy cores (each commit's round trip is longer), but they
  overlap, so throughput is 2.4 times higher. On the reporter's separate nodes the round trip did not grow and latency
  stayed at the RTT.
- The hand-off itself (release to the next commit running) is under 1 ms: the cost is the hold, not the lock.

Side finding while writing the test: a property name the dictionary does not hold yet is added by a commit of its own,
which locks file 0 (the dictionary) for a full replication round trip. Every first use of a new property name is
therefore serialized cluster-wide; harmless with a fixed schema, worth knowing for schemaless loads.

## Design: pipelining same-file replicated commits

Goal: let commit B into a file start its Raft round trip while commit A on the same file is still in flight, without B
ever becoming visible or durable unless A does.

### Mechanism

1. **Release point.** A's commit locks may be released only once A's entry is at a fixed position in the leader's log:
   after `preAppendTransaction` has confirmed every reservation of A (`PageVersionLedger.confirmAppended`) and the append
   succeeded. Not earlier: before the append A can still be refused (ledger conflict, expired reservation, exclusive
   operation, `ReplicatedEntryTooLargeException`, a dropped request) and a B validated on top of it would then be
   appended with a target version nobody will ever produce (a version gap on every follower, `WALVersionGapException`,
   divergence and resync). The committing thread does not see that moment today; `LocalCommit` would need an
   `appended` state the state machine sets from `preAppendTransaction`, and the commit would wait for it instead of the
   reply before releasing.
2. **Pending images.** Between A's release and A's apply, the leader keeps A's prepared post-images (`TransactionPhase1.pages`,
   copied, versions as they will be after the bump) in a per-database map keyed by page, owned by the ledger, removed when
   A is applied or its registration withdrawn, and dropped wholesale with `pageVersions.clearAll()` on
   `notifyNotLeader` / `notifyLeaderReady`.
3. **Rebase on the pending image.** B's phase 1, finding a page whose newest version is a reservation (the
   `PageVersionReservations` hit that today raises the conflict), rebases on the pending image instead of the committed
   page, with the existing merges only: `rebaseSlots` (disjoint slots, which `LocalBucket.reserveInsertSlot` already
   guarantees for concurrent inserts, since A's slot reservations live until A's `reset()`) and `rebaseEdgeAppends`. Any
   other page (index pages, a dictionary page, a page a merge declines) keeps today's behaviour: B waits for A's apply.
   That wait would be on the reservation, not the file lock, which needs a new primitive (a per-page latch the apply
   releases).
4. **Ledger chain.** B ships v+2 on a page A reserved at v+1. `validateAndReserve` already accepts it (expected =
   reserved version). The chain is then enforced by Raft itself: B is appended after A in the same log.

### Correctness argument (what holds)

- **A fails, B must not commit.** With the release after A's append, the only ways A can still fail are a leader change
  or a crash that truncates A. Raft's log matching property means any log holding B holds A at the same index, so B is
  truncated with it or committed after it; there is no log where B commits and A does not. A refusal of A before its
  append cannot affect B, which has not started its phase 1 yet (it is still waiting on A's lock).
- **Visibility.** On the leader pages become visible only in step 6, on the apply thread, in log order. B's pages are
  published after A's, by construction, so no reader ever sees B without A. Pending images are never readable by queries;
  only phase-1 rebases read them.
- **Follower apply order equals the leader's order.** Followers apply WAL bytes in log order and every page carries its
  version; B's delta is computed against A's post-image, which is exactly what the follower holds after applying A.
- **Leader step-down mid-flight.** Registrations not claimed by the apply thread are withdrawn; the entry, if the new
  leader commits it, is applied from its WAL bytes, which are self-consistent with the chain. Pending images are dropped
  with the ledger. Clients get the same indeterminate `ReplicationDispatchedTimeoutException` they get today.
- **MVCC.** B's version check runs against the reservation (v+1) and B's rebased page carries v+1 as its base, so
  `validateAndBumpVersions` at B's apply (after A's) sees v+1 and bumps to v+2.

### Why it does not land now (what does not hold yet)

These invariants are kept today by "the file lock spans the publication", and each needs its own argument or rework
before the release point can move:

1. **#4936 / #4937:** `publishCommittedPagesInternal` validates and bumps versions before the WAL append on the
   assumption that every modified file's lock is held until `reset()`. On the apply thread that becomes "every earlier
   entry has been applied", which is probably sufficient because only the apply thread publishes replicated pages, but
   the leader also runs local, non-replicated commits (`txWalGroupCommit` covers "the internal commits of a replicated
   database (compaction, schema saves)") which take file locks and do not go through the ledger.
2. **#8986 unidirectional edges:** `unidirectionalEdgesCommitted` is bumped "while their targets' files are still
   locked", so a concurrent delete either takes its epoch before the bump and fails, or after it and sees the edges.
3. **Index compaction and the HANDOFF item 33 hazard:** `runWithCompactionReplication` swaps index files under the file
   lock (compaction waits indefinitely, `timeout <= 0`). A committer that released its lock with pages of the old file
   still pending would race the swap; item 33 shows how a write landing during a chunked build already diverges
   followers.
4. **Unique indexes:** `indexChanges.commit()` checks uniqueness against committed pages under the lock. With A
   released but not applied, B does not see A's key. Today the ledger turns that into a conflict on the shared index
   page (correct, but a retry loop rather than a wait); under pipelining the wait-on-reservation primitive of step 3 is
   mandatory, not an optimisation, or a duplicate could slip through on an index page split.
5. **`finishCommitBookkeeping`:** `onAfterCommit()` runs per locked file after publication (LSM compaction scheduling,
   vector bookkeeping) and assumes no other commit on the file has started.
6. **Insert slot reservations and `inFlightNewPages` (#5279, #9694)** are released in `reset()`. With pipelining they must
   outlive the lock until A is applied (they do today because `reset()` is after the apply), and a page A created must
   stay off-limits to B's allocator until then, which is a change of meaning for "in flight".

And the gain is narrow: only pages the slot and edge-append merges cover. Every LSM index page, every full-text page
and the unique edge indexes the reporter declares on all edge types would still serialize per file. For the reporter's
real upload (around 40 types, unique edge types, vertex indexes) pipelining would move little; more buckets moves all of
it, because the type index is per bucket.

Failure-case tests the implementation would need, none of which can be written meaningfully before the mechanism exists:
leader killed with B pipelined behind A in flight (both lost or both kept, never B alone, on every node); A refused at
`preAppendTransaction` with B waiting (B must not have started); A's apply failing (`publishLocalCommit` reconcile) with
B pending on its image; a step-down between A's append and apply with B pending; a compaction swap with a pending image
on the old file; unique-key race on one index page.

### A smaller step that is closer to safe (not landed either)

Release the commit locks right after the leader's apply thread has published A (step 6) instead of after the reply
reaches the committing thread (step 8). That removes the reply hop and the thread wake-up from every hold without
changing what a later commit can see: the pages are published and the ledger released. It still has to move
`finishCommitBookkeeping` and the slot-reservation release onto the apply thread (or keep them on the committing thread
without the lock, which item 5 above forbids), and the lock owner is the transaction's requester, so the apply thread
would unlock on its behalf. The gain is bounded by the reply latency (a fraction of a millisecond in process, more under
load), so it does not change the shape of the cap. Worth doing only together with the per-page wait primitive.

## Cheap relief: more buckets per type

Why it works: each bucket is its own file with its own commit lock, and a type index is one LSM index per bucket
(`TypeIndex` over bucket indexes), so a commit into bucket k locks bucket k and the index files of bucket k only. With
the default round-robin selection, N concurrent commits into a type hit N/B commits per file on average.

Trade-offs:

- **Scans and lookups:** a full scan is the same number of pages split over more files (and parallel scans gain). An
  index lookup without the bucket known probes every bucket's index: B lookups instead of 1, each in a smaller tree. For
  a UNIQUE check at insert that is B probes per key; the stress workload has many unique edge types, so 8 to 16 is the
  range, not 64.
- **Page cache:** each bucket and each bucket index keeps its own partially filled last pages hot. With 64 KB bucket pages
  and 256 KB index pages, 40 types x 8 buckets x (1 bucket + 2 or 3 indexes) is a few hundred MB of mostly-empty hot
  pages: acceptable on 126 GB nodes, worth it on the write-heavy partitions only.
- **Files and compaction:** B times more files and index compactions per type.
- **It does not fix issue 5:** the reporter's 8-bucket run moved the write step from about 1.4 s to 0.5 s at 96 uploads
  but uploads/s stayed at 49 to 62, capped by the commit-time conflicts of issue 5 (shared vertices' edge segments,
  shared index pages). More buckets take the replication wait out; they do not take the retries out.

Recommendation:

1. Do not change the compiled-in default of `arcadedb.typeDefaultBuckets` (1): it applies to every database, embedded
   ones included, where the per-file wait is a WAL flush (already relieved by `txWalGroupCommit`), and the lookup fan-out
   cost lands on read-heavy partitions that never see concurrent writers.
2. Document, in `docs/spork/config/README.md` (owned by the architecture session) and the HA operator notes: on an HA
   database with concurrent writers, set `ALTER DATABASE \`arcadedb.typeDefaultBuckets\` 8` (or `BUCKETS n` per type) BEFORE
   creating the schema, sized to the number of concurrent writers into the hottest type, capped around 16. Existing types
   keep their bucket count (`ALTER TYPE x BUCKET +name` adds buckets).
3. Do not pair it with the `thread` bucket selection strategy on HA for this purpose: the commits run on the HTTP worker
   threads, whose number (500) is unrelated to the bucket count, and the reporter saw no gain from it.
4. Revisit pipelining only if issue 5's fixes leave the replication wait as the cap again on a type that cannot be
   split (a single hot type with no indexes is the only shape it fully helps).
