# Index locks and record read path workstream: S17, S3, S14, S8, S10

Status: implemented and tested in the `agent-af71298f8778713e2` worktree (branch `worktree-agent-af71298f8778713e2`), not
committed. Items 4.2 S17, S3, S14, S8 and S10 of `docs/spork/02-retrieval-speed.md`. Benchmark numbers are in
[Measurements](#measurements); what was left out, and why, in [Deferred](#deferred); what the independent re-review found and
how each finding was fixed, in [Review findings and fixes](#review-findings-and-fixes).

## What changed

| Item | Before | Now |
|---|---|---|
| S17 `HashIndex` / `LSMTreeIndex` lock | one single-stripe FAIR `ReentrantReadWriteLock` per index (`new RWLockContext()`): every `get`, `range`, cursor open and `put` outside a transaction writes the same lock word, in arrival order | `new RWLockContext(RWLockContext.indexStripeCount(), false)`: `min(8, max(4, cores))` stripes (power of two, `RWLockContext.MAX_INDEX_STRIPES`), NON-fair. About 1.2 KB of locks per index object instead of about 150 B before; the cap keeps it from growing to 7 to 10 KB on a 64-core host (review finding 3). A reader locks only its stripe; the writers (close, drop, the LSM compaction's file swap) take every stripe in order. `RWLockContext` gains the public `(stripeCount, fair)` constructor; the `(stripeCount)` one stays fair and is what `LocalDatabase` uses |
| S17 `HashIndexBucket` page reads | `readPage` (page 0, directory), `searchBucket` (bucket and overflow pages) and `readRidList` read through `tx.getPage`, which under `READ_COMMITTED` built an `ImmutablePage`, a `Binary` and a `ByteBuffer` per page touched (3 pages per point lookup, 4 to 5 with an overflow or RID list page) | `tx.getPageForAbsoluteReads`: the page cache's shared `SharedImmutablePage` under `READ_COMMITTED` (#10654, the same path the LSM lookups take), the transaction's own page when it holds one (its modified or new pages, every page under `REPEATABLE_READ`). Outside a transaction `getSharedImmutablePage` replaces `getImmutablePage`. Every read of the bucket is at an absolute position (`readInt`, `readShort`, `readLong`, `readByte`, `readByteArray(index, ...)`), which is the shared view's contract |
| S3 `LocalBucket.getRecordInternal` | the head page came from `getPageNotHeld` -> `getImmutablePage` -> `useAsImmutable()`: a new `ImmutablePage`, `Binary` and buffer per record read | under `READ_COMMITTED`, when the transaction holds no page for it, the head page is `PageManager.getSharedImmutablePage`; `REPEATABLE_READ` still goes through `getPageNotHeld`, which pins. The chunk-chain branch walks the very image the slot offset, marker and header size were read from (the shared view under `READ_COMMITTED`), never a second fetch of the page (review finding 1); the walk's self-loop check compares page ids, not `BasePage.equals`, which also compares the class. The returned `Binary` is still a slice view of the page, so every caller that copies (`lookupByRID`'s `copyOfContent`, the edge segment loaders) copies exactly once, as before |
| S14 permission verdict | `checkPermissionsOnFile` asked `SecurityDatabaseUser.requestAccessOnFile` on every record read, update, create and delete | the last GRANTED `(fileId, access)` of the thread's current user is kept on `DatabaseContextTL` (`isFileAccessGranted` / `rememberFileAccessGranted`) together with the user's access version (`SecurityDatabaseUser.getAccessVersion()`, compared by identity, read before the user is asked): a hydrate of N records of one bucket asks once, and any change of the user's permission state forgets the verdict at the next check, rebind or not (review finding 2). A denial is never kept, `setCurrentUser` forgets it, and a user without an access version (the interface default, `null`) is asked every time. `ServerSecurityDatabaseUser` answers its published `AccessSnapshot`, which every refresh replaces |
| S14 `stats.readRecord` | an `AtomicLong` bumped with `incrementAndGet` by every `lookupByRID` / `lookupByKey` of every thread: one contended CAS word | a `LongAdder` (`DatabaseStats.readRecord`), exact, read with `sum()` by `total()` and `toMap()`. Not sampled: `VertexIteratorSingleLookupTest`, `Issue9909RidConditionPushdownTest`, `Issue10170VectorRerankersCarriedRecordTest` and others pin exact per-read deltas, and a `LongAdder` keeps them exact while removing the shared write |
| S8 `QueryAdmissionGate` | `admit()` took the JVM-wide `ReentrantLock` on every admission and `release()` took it again on every ticket close | `running` is an `AtomicInteger`; `tryTakeSlot(max)` is one CAS loop guarded by `n < max && heapBelowWatermark(n)`, used by the lock-free fast path (nobody queued), by the locked path and by the head of the queue, so the limit holds exactly however many threads race for the last slot. A fast-path taker that finds a waiter queued after its CAS gives the slot back and queues behind it (arrival order kept). `release()` decrements without the lock and takes it only to signal a waiter when `queued > 0`; the waiter re-checks the count under the lock after enqueueing, so the two volatile words make one side always see the other |
| S10 vector metrics | `LSMVectorIndexMetrics`: 25 `AtomicLong`s, two of them (`searchOperations`, `searchLatencyMs`) bumped per search by every searching thread | `LongAdder`s, `sum()` on read, `reset()` on reset. Same `populateStats` keys |
| S10 allow-list filter | `LiveVectorBitsFilter.accepts` materialized a `RID` per visited node (`vectorIndex.getRid`) and hashed it boxed in the `Set<RID>` allow-list | `VectorLocationIndex.isLocationIn(vectorId, RidHashSet)` and `isLocationIn(vectorId, RidSet)` answer liveness and membership from the two words the location index holds. The filter takes that path for a `RidHashSet` allow-list (new package-private constructor) and for an allow-list whose class is exactly `RidSet` (a subclass keeps the object path, so an override of `contains(Object)` is honoured). A `HashSet<RID>` or a predicate-backed `RecordPredicateRidSet` keeps the object path: converting a set per search costs more than it saves (see Deferred). **Groundwork only**: no production caller hands either primitive form in today (review finding 4), so the allocation win is measured in tests only |

Files: `engine/src/main/java/com/arcadedb/{utility/RWLockContext, index/hash/HashIndex, index/hash/HashIndexBucket,
index/lsm/LSMTreeIndex, engine/LocalBucket, database/LocalDatabase, database/DatabaseContext, database/DatabaseStats,
query/sql/executor/QueryAdmissionGate, index/vector/LSMVectorIndexMetrics, index/vector/LiveVectorBitsFilter,
index/vector/VectorLocationIndex, security/SecurityDatabaseUser}.java` and
`server/src/main/java/com/arcadedb/server/security/ServerSecurityDatabaseUser.java` (the access version, review finding 2), plus the five `stats.readRecord.incrementAndGet()` calls of the remote clients
(`network/.../RemoteDatabase.java`, `grpc-client/.../RemoteGrpcDatabase.java`) that follow the counter's type. `LocalBucket`
is touched in `getRecordInternal` only and `LocalDatabase` in `checkPermissionsOnFile` plus the three `stats.readRecord`
calls, so the branch merges with the page-cache workstream
(`PageManager`, `CachedPage`, `PaginatedComponentFile`, `BucketIterator`, `LocalBucket.getRecords` / `loadMultiPageRecord`,
`LocalDatabase.lookupByRIDs`).

## Tests

Regression tests (run with the existing hash, LSM, transaction isolation, security and vector classes; 77 classes, 578
tests, all green on 2026-11-02):

- `index/hash/HashIndexSharedPageViewLookupTest`: same answers inside and outside a transaction under both isolation
  levels (unique key, hot non-unique key with a RID list chain, limited lookup, absent key); a lookup sees its own
  transaction's pending adds and removes; `READ_COMMITTED` pins no index page while `REPEATABLE_READ` does; 8 readers through
  `lookupByKey` never miss a committed key while a writer splits buckets and doubles the directory (`@Tag("slow")`), and a bare
  `index.get` never answers another key's RID; a warm `READ_COMMITTED` point lookup stays under 2,000 bytes.
- `engine/RecordReadSharedViewTest`: a `READ_COMMITTED` plain record read builds the page's shared view and pins nothing;
  `REPEATABLE_READ` pins the page; the `Binary` handed out reads back the record and is independent of later reads; a chunk
  chain and a deleted slot answer as before under both levels; every record agrees under both levels.
- `database/PermissionVerdictCacheTest`: 100 reads of one file ask the user once; another file asks again; a denial is never
  cached; another user, and the same user re-bound, are asked again; a different access on the same file asks again; a thread
  without a user is not checked; a revocation on the same bound user refuses the very next read without a rebind, and a later
  grant is asked again; a grant given before the state was replaced is forgotten with it; a user without an access version
  is asked on every read.
- `engine/RecordReadSharedViewTest` (+1): `aChunkHeadIsWalkedFromTheImageItsOffsetsWereReadFrom`, a commit that deletes a
  chunked record and so packs a neighbour's chunk head onto its old offset, landing inside the read through
  `PageReadFaultInjector`; the read never answers the neighbour's content.
- `server/security/Issue6806GroupPermissionRefreshTest` (+1): the cached database user's access version moves on a group
  refresh, on `updateFileAccess` alone and on `updateDatabaseConfiguration` alone.
- `utility/RWLockContextTest` (+5): the non-fair striped lock excludes readers on every stripe, lets readers on different
  stripes run at once, refuses a reader's upgrade, lets a writer re-enter and downgrade; an index lock keeps 4 to 8 stripes.
- `query/sql/executor/QueryAdmissionGateTest` (+2): 32 threads admitting with no queue (timeout 0) never exceed the limit and
  every slot comes back, nothing counted as waited; a newcomer that cannot wait is refused while the running queries hold the
  heap above the watermark, and starts once the heap is given back.
- `index/vector/LiveVectorBitsFilterAllowListTest`: `isLocationIn` for live, deleted and out-of-range ids, empty set;
  `RidSet` and `RidHashSet` allow-lists give the verdict of the `HashSet<RID>` one over every ordinal; superseded RIDs still
  rejected; an empty primitive set means every live vector; the primitive path allocates less than a quarter of the boxed one
  over 200,000 calls; a `RidSet` subclass overriding `contains(Object)` is asked through its override.
- `index/vector/LSMVectorIndexMetricsTest`: 16 threads x 20,000 increments add up exactly; stats keys and reset.

Benchmarks (`@Tag("benchmark")`, excluded from CI, run with `-Dgroups=benchmark -DexcludedGroups=`):

- `index/IndexPointLookupContentionBenchmark`: 100,000-row UNIQUE HASH and LSM_TREE indexes, warm, 50,000 point lookups per
  thread from 1 and from 32 threads, reports lookups/s and the 32-thread scaling factor. Every lookup must find its key.
- `database/RecordReadAllocationBenchmark`: 20,000 rows (one in a hundred a 100 KB chunk chain, so the chain branch is
  compiled and not an uncommon trap the JIT folds the page view away under), `ThreadMXBean.getThreadAllocatedBytes` per
  `LocalBucket.getRecord` and per `Database.lookupByRID` over 200,000 warm reads, and the 32-thread `lookupByRID` rate.
- `query/sql/executor/QueryAdmissionGateContentionBenchmark`: admit-and-close round trips from 1 and from 32 threads with
  free slots.

## Measurements

Machine: 18-core Apple Silicon laptop (6 performance cores), JDK 26, `mvn -o -pl engine test`. The machine was shared with
other agents' Maven test runs the whole time (load average 15 to 36), so absolute numbers are low and noisy; the before /
after pairs below were run back to back, alternated twice, and only the ratios inside a pair are meaningful.

BEFORE is the committed engine (the seven touched main files checked out from HEAD, the new tests kept), AFTER is this
worktree; the load average at the start of each run is in brackets. Allocation figures come from
`ThreadMXBean.getThreadAllocatedBytes` and do not depend on the load.

| Measure | Before, pair 1 [92] | After, pair 1 [137] | Before, pair 2 [152] | After, pair 2 [74] |
|---|---|---|---|---|
| HASH point lookup, 1 thread | 439 K/s (2.28 us) | 1,092 K/s (0.92 us) | 1,018 K/s (0.98 us) | 1,044 K/s (0.96 us) |
| HASH point lookup, 32 threads | 1,428 K/s, scaling x3.2 | 8,013 K/s, x7.3 | 2,743 K/s, x2.7 | **8,824 K/s, x8.4** |
| HASH bytes allocated per warm lookup | n/a (counted from pair 2) | n/a | 1,240 B | **800 B** |
| LSM point lookup, 1 thread | 717 K/s (1.39 us) | 803 K/s (1.25 us) | 828 K/s (1.21 us) | 899 K/s (1.11 us) |
| LSM point lookup, 32 threads | 1,578 K/s, x2.2 | 5,084 K/s, x6.3 | 3,582 K/s, x4.3 | **4,531 K/s, x5.0** |
| LSM bytes allocated per warm lookup | n/a | n/a | 1,808 B | 1,808 B (the LSM lookup already read the shared view, #10654) |
| `LocalBucket.getRecord`, bytes per warm read | 1,872 B (a) | 1,745 B (a) | 256 B | **128 B** |
| `Database.lookupByRID`, bytes per warm read | 3,238 B (a) | 3,112 B (a) | 608 B | **480 B** |
| `Database.lookupByRID`, 1 thread | 2.62 us | 0.98 us | 0.49 us | 0.28 us |
| `Database.lookupByRID`, 32 threads | 2,989 K/s (10.7 us each) | 5,607 K/s (5.7 us) | 3,511 K/s (9.1 us) | **6,946 K/s (4.6 us)** |
| Admission gate round trip, 1 thread | 85 ns | 36 ns | 69 ns | **35 ns** |
| Admission gate round trips, 32 threads | 13.4 M/s (2.4 us each) | 17.6 M/s (1.8 us) | 10.9 M/s (2.9 us) | 13.3 M/s (2.4 us) |

(a) pair 1 ran the first version of the allocation benchmark, in which one measured read in a hundred was a 100 KB chunk
chain, so its 100 KB copy sits in the average; pair 2 reads the chain rows in the warm-up only. The difference between the
two columns of a pair is the per-touch page view either way: 127 to 128 bytes, which is an `ImmutablePage` (32 B), a
`Binary` (48 B) and a `HeapByteBuffer` (48 B).

What the numbers say:

- **S17 HASH.** The 32-thread rate is 3.2x (pair 2) to 5.6x (pair 1, under a heavier load after than before) the committed
  engine's, and the 32-thread scaling factor goes from 2.7 to 3.2 to 7.3 to 8.4 on an 18-core machine whose 6 performance
  cores and a load average above 70 bound it. The single-thread figure is the same within noise: the lock was never the
  single-thread cost, the convoy was. 440 bytes (35%) less per lookup: the three page views.
- **S17 LSM.** 1.3x (pair 2) to 3.2x (pair 1) at 32 threads, from the lock alone (the LSM lookup already read shared views).
  The single-thread figure improves 8% in both pairs, the non-fair stripe being cheaper to take than the fair single lock.
- **S3 + S14.** Half the bytes of a raw record read (256 to 128) and 21% fewer of a `lookupByRID` (608 to 480; what remains is
  the slice view, the right-sized copy and the `ImmutableDocument`). The 32-thread `lookupByRID` rate doubles in both pairs
  (2,989 to 5,607 K/s; 3,511 to 6,946 K/s): with no security manager the only thing S14 changes on this path is the
  `readRecord` counter, so the contended `AtomicLong` was worth about as much as the page view. The permission verdict cache
  is measured by `PermissionVerdictCacheTest` (100 reads, 1 call) rather than timed.
- **S8.** The uncontended round trip halves (69 to 85 ns to 35 to 36 ns: two lock acquisitions gone). At 32 threads with free
  slots the fast path is still a CAS on one word, so it is bounded by that word's cache line: 1.2x to 1.3x. The gate was never
  the bottleneck of a 2,000 request/s node (35 ns x 2,000/s is nothing); what S8 buys is that a remote request no longer takes a
  JVM-wide `ReentrantLock` twice, which under the Undertow worker pool's 500 threads was a convoy waiting to happen.
- **S10.** The filter's primitive path allocates less than a quarter of the boxed one over 200,000 calls
  (`thePrimitiveAllowListAllocatesNothingPerVisitedNode` holds it under 1/4: the boxed path builds one `RID` per call, the
  primitive one nothing); the metrics change is not timed separately, a `LongAdder` against an `AtomicLong` under 32 threads
  is a textbook result.

## Risks

- **Non-fair index lock and writer starvation.** `ReentrantReadWriteLock(false)` lets a reader barge while the lock is
  shared, but its non-fair `Sync` refuses new readers once a writer is first in the queue
  (`apparentlyFirstQueuedIsExclusive`), so a close, drop or compaction swap still gets in. The stripes are taken in a fixed
  order, so two writers cannot deadlock each other. `nonFairStripedWriterExcludesReadersOnEveryStripe` holds a 32-reader
  stream against a writer loop.
- **Shared page view and torn reads.** `getPageForAbsoluteReads` answers the same cached version `getPage` did, so a bare
  `index.get` that overlaps a commit can still see page 0 of one commit and a bucket page of another, before this change
  exactly as after it; the guarantee lives in `ConsistentKeyLookup` (#9369, #9397), which `lookupByKey` and the query engine
  use. The concurrent test pins both facts. The one new dependency is that every `HashIndexBucket` read is position-free,
  which `Issue10930SharedPageViewVarIntTest` enforces for the page API and the bucket's own code honours (`readByteArray` is
  answered on a private buffer by the shared view).
- **Permission verdict staleness.** Closed (review finding 2): the verdict is kept with the user's access version, so a
  revocation refuses the next record read on every protocol, in flight or not, exactly as before S14. The earlier claim that
  every protocol binds the user per request was wrong: Postgres binds once per connection (and again only on a reopen or a
  new `ServerSecurityUser` object), a gRPC client-managed transaction once per transaction. With the version check that no
  longer matters for the verdict. A third-party `SecurityDatabaseUser` that does not implement `getAccessVersion()` gets no
  caching at all, so it cannot be made stale either.
- **`DatabaseStats.readRecord` type.** `LongAdder` instead of `AtomicLong`: `sum()` is not atomic with respect to the other
  counters (`total()` was never a consistent snapshot either). Inside the repository the field is read by `DatabaseStats` only
  and the Profiler reads the map, but it is a PUBLIC field, so code outside it that calls `db.getStats().readRecord.get()`
  (embedded users, plugins, downstream forks) stops compiling and linking. Accepted, to be called out in the release notes
  (review finding 5): `readRecord.sum()` is the replacement, `toMap().get("readRecord")` works across both versions.
- **Admission gate fairness window.** A query on the fast path can take the last slot between a waiter's enqueue and its
  blocker check; the waiter then sees the count under the lock and parks, and the fast-path taker, seeing `queued != 0` after
  its CAS, gives the slot back and queues behind it. The window where it does NOT see the waiter (the waiter enqueues after
  the taker's second `queued` read) is the same as the waiter arriving a moment later. `manyThreadsAdmittingAtOnce...` and the
  existing `underContentionTheLimitHoldsAndEverySlotComesBack` hold the limit and the accounting.
- **Vector filter, `RidSet` detection.** An allow-list of class exactly `RidSet` is answered by its bitmap; a subclass is
  asked through `contains(Object)`. The SQL `filter` option still builds a `HashSet<RID>`
  (`SQLFunctionVectorAbstract.parseRidFilter`) and so stays on the object path.

## Deferred

- **S10 `StampedLock` over the (graph, ordinal map, locations) triple in `LSMVectorIndex`.** The index's
  `ReentrantReadWriteLock` has about 30 read and 20 write sites in a 14,800-line class, the read lock is held for the whole
  search (graph walk included) and is re-entered (the search re-takes it through `vectorIndex()` and the rebuild paths note
  that it "cannot upgrade"), and the writers include every insert. A `StampedLock` is not reentrant and its optimistic read
  cannot cover a walk that reads a graph a rebuild may close; the change is a redesign of the index's publication protocol
  (publish the triple as one immutable snapshot object, search on the snapshot without the lock), outside "metrics and lock
  fields only". The `LongAdder` metrics remove the per-search shared writes that are measurable without it.
- **S10 primitive allow-list from SQL.** `findNeighborsFromVector` takes a `Set<RID>`; a `RidHashSet` overload (and the
  plugin's set-scoped kNN handing one in) is the caller side of `isLocationIn`. Converting a `HashSet<RID>` to a `RidHashSet`
  per search was measured against the cost model and left out: 2,000 RIDs cost about 40 us to convert and the filtered walk
  saves 30 to 50 ns per visited node, so it only pays past about 1,000 visited nodes, which is where the pre-filter plan
  (#6502) already scores the allow-list directly.
- **S3 right-sized copy inside `getRecordInternal`.** The method keeps returning a slice view: every caller that keeps the
  bytes already copies once (`lookupByRID` -> `copyOfContent`, the edge segment and stripe directory loaders), and returning a
  copy would make those copy twice, 64 KB edge segments included. Moving the one copy into the bucket needs the callers to
  stop copying, which is the `BucketIterator` / `getRecords` territory of the page-cache workstream.
- **`stats.readRecord` sampling.** Replaced by the exact `LongAdder`; sampling would break the tests that assert per-read
  deltas.
- **Server-module coverage of the gate** is the existing `QueryAdmissionGateHttpIssue9518Test` (9), `AdmittedWorkIsListedTest`
  (2) and `PoolMetricsTest` (14), run green through `mvn -o -pl server,grpc-client -am test` on 2026-11-03; the broader
  server suite was not run.

## Review findings and fixes

An independent re-review of this worktree found one blocker, one major and three minors. Every blocker and major is fixed
with a regression test that fails without the fix; the minors are fixed or documented as below. Run on 2026-11-03.

1. **Blocker, chunk head read from two images (`LocalBucket.getRecordInternal`).** Under `READ_COMMITTED` the slot offset,
   marker and header size were read from the shared view of the head page, then the chunk branch fetched the page AGAIN
   (`getPageNotHeld`) for `loadMultiPageRecord`. A commit landing between the two fetches that packs the page
   (`compressPage`) can leave another record's chunk head at the old offset; the walk then assembled THAT record under the
   RID asked for, and the trace recorded the newer page version, which `validateChainRead` took for unchanged. Fixed by
   handing the walk the very image the offsets came from (the shared view: every read of the walk is at an absolute
   position, and a retry still re-reads the head and re-derives all three). The shared view exposed one more thing: the
   walk's self-loop check used `BasePage.equals`, which also compares the class, so a `SharedImmutablePage` head never
   equalled the per-call `ImmutablePage` of the next hop, and a self-referencing chunk on the head page was reported as
   "unexpected marker" instead of a loop (`Issue6258ChunkedReadRetryAndCorruptionTest.aChainThatLoopsIsReportedAsCorruptionAndNotRetried`
   caught it); it now compares page ids. Regression test:
   `RecordReadSharedViewTest.aChunkHeadIsWalkedFromTheImageItsOffsetsWereReadFrom` spills two neighbouring records R and B
   into chunk heads in place, and a `PageReadFaultInjector` runs the commit deleting R on the read's second page fetch of the
   bucket. It asserts that the fixture reproduces the hazard (B's head sits at R's old offset after the commit) and that the
   read answers R's content or "not found", never B's. Before the fix it returned B's 150 KB content under R's RID.
2. **Major, permission verdict outliving a revocation (`LocalDatabase.checkPermissionsOnFile`, `DatabaseContext`).** The
   verdict was keyed on the user object, but a group change swaps the `AccessSnapshot` inside the same
   `ServerSecurityDatabaseUser`, and Postgres binds the user once per connection (a gRPC client-managed transaction once per
   transaction), so a revoked `readRecord` kept being granted on a polling connection; a grant from the "file not yet
   registered, allowing by default" answer could likewise outlive the refresh that would deny it. Fixed by keying the verdict
   on the permission state: `SecurityDatabaseUser.getAccessVersion()` (default `null`, meaning never cache);
   `ServerSecurityDatabaseUser` returns its published `AccessSnapshot` (one volatile read; every refresh, `updateFileAccess`
   and `updateDatabaseConfiguration` publish a new one, so the provisional default is forgotten with the snapshot it came
   from); the context keeps it next to the user and the (file, access) pair. The version is read before the user is asked,
   so a state published in between makes the kept verdict stale at once. Postgres was not changed to rebind per message:
   with the version check the binding's lifetime no longer bounds the verdict's correctness. Regression tests:
   `PermissionVerdictCacheTest.aRevocationOnTheSameBoundUserRefusesTheNextReadWithoutARebind` (read, revoke on the same bound
   user, the next read must throw; before the fix it was answered from the cached grant),
   `aGrantGivenBeforeTheStateWasRefreshedIsForgottenWithIt`, `aUserWithoutAnAccessVersionIsAskedEveryTime`, and on the
   server `Issue6806GroupPermissionRefreshTest.everyRefreshMovesTheAccessVersionOfTheCachedDatabaseUser`.
3. **Minor, index lock footprint.** Fixed: index locks take `RWLockContext.indexStripeCount()` = `min(8, max(4, cores))`
   stripes instead of up to 64. Measured as two alternating A/B pairs in one run of `IndexPointLookupContentionBenchmark`
   (cap 32, the previous value on this 18-core machine, against cap 8; machine shared, load average in brackets):

   | Measure | cap 32, pair 1 [27] | cap 8, pair 1 [18] | cap 32, pair 2 [18] | cap 8, pair 2 [87] |
   |---|---|---|---|---|
   | HASH, 32 threads | 2,911 K/s | 4,473 K/s | 1,375 K/s | 2,140 K/s |
   | LSM, 32 threads | 3,908 K/s | 3,812 K/s | 2,741 K/s | 2,399 K/s |

   No loss within this noise: HASH is ahead with 8 stripes in both pairs, LSM within 3% in pair 1 and 12% behind in pair 2
   under almost five times the load. The scaling the stripes buy is bounded by the 6 performance cores either way. Test:
   `RWLockContextTest.anIndexLockKeepsAtMostEightStripes`.
4. **Minor, S10 allow-list paths unreached and `RidSet` detected by `instanceof`.** Detection fixed: exactly `RidSet`
   (`getClass() == RidSet.class`), so a subclass overriding `contains(Object)` is asked through its override (test:
   `LiveVectorBitsFilterAllowListTest.aRidSetSubclassThatOverridesContainsIsAskedThroughItsOverride`). Wiring a real caller
   is deferred: every production construction in `LSMVectorIndex` passes a `HashSet<RID>` (SQL filter) or a
   `RecordPredicateRidSet`, the conversion cost model under [Deferred](#deferred) still holds, and a `RidHashSet` overload of
   `findNeighborsFromVector` belongs with the set-scoped kNN caller that would use it. The S10 filter change is groundwork.
5. **Minor, `DatabaseStats.readRecord` public field type.** Accepted and documented under [Risks](#risks) for the release
   notes; an accessor that kept the old `AtomicLong` type would keep the contended write the change removes.

Tests run for these fixes, all green on 2026-11-03:

- engine, the classes touched: `RecordReadSharedViewTest` (6), `PermissionVerdictCacheTest` (8), `RWLockContextTest` (21),
  `LiveVectorBitsFilterAllowListTest` (6), `HashIndexSharedPageViewLookupTest` (6);
- engine, the chunk-chain and record-read classes (32 classes, every one of them green): `ConcurrentMultiPageRecordReadTest`,
  `Issue6217ChunkedReadFalseConflictTest`, `Issue6258ChunkedReadRetryAndCorruptionTest`, `Issue10987TailsFreedBeforeHeadTest`,
  `Issue6178ChunkCollapseTest`, `Issue6196PlaceholderContentChainTest`, `Issue8713ChunkChainCycleTest`,
  `Issue9620RepeatableReadFirstReadOfMultiPageRecordTest`, `Issue9070RepeatableReadNeighbourPinnedHeadTest`,
  `Issue8987RepeatableReadLargeRecordTest`, `Issue10304CommittedRecordReadTest`, `ChunkChainConcurrentResizeStressTest`,
  `Issue6282BrokenChainDeleteAndProbeTest`, `Issue10853NegativeNextChunkLinkTest`, `Issue6175ChunkOnHeadPageTest`,
  `Issue8982LostUpdateLargeDocumentTest`, `Issue10701ContinuationChunkNeverAtPositionZeroTest`,
  `BucketIteratorBrokenChunkChainTest`, `BrokenMultiPageRecordDeleteTest`, `Issue6129ChunkedSlotMergeTest`,
  `Issue6163HeadChunkRoomTest`, `Issue8312ScanEagerRecordTest`, `Issue8265ScanRecordLookupTest`,
  `Issue6950RecordImageGuardTest`, `Issue7227ExistsRecordCauseTest`, `LSMTreeIndexBloomFilterTest`,
  `Issue10709ReinsertAfterBulkDeleteTest`, `CrossLinkedChunkDetectionTest`, `Issue6319ChunkChainTailLeakTest`,
  `Issue10875FreeChunkChainValidatesBeforeFreeingTest`, `DeleteToleratesBrokenChainToggleTest`,
  `Issue4432CorruptVertexDeleteTest`;
- server, through `mvn -o -pl server -am test`: `Issue6806GroupPermissionRefreshTest` (8),
  `ServerSecurityDatabaseUserConcurrencyTest` (2), `ServerSecurityDatabaseUserServerAdministratorTest` (2),
  `Issue7510ReplicatedGroupRefreshTest` (6), `Issue7373ReplicatedGroupAuthorizationTest` (2).
