# Page cache workstream (S16, S1, S2, M1, prefetch, L1)

Status: implemented and measured in worktree `.claude/worktrees/agent-a6b83e7aba0748e90` (branch
`worktree-agent-a6b83e7aba0748e90`), on top of 4bc577f11. Items refer to `docs/spork/02-retrieval-speed.md` section 4.
Nothing is committed; the engine tests named in section 5 are green.

## 1. What changed

| Item | Where | What |
|---|---|---|
| S16 eviction run hygiene | `PageManager.evictOldestPages` | The legacy sort-the-world run (now the fallback behind a test-only switch, see M1) stamps `lastCheckForRAM` on ENTRY and re-checks `totalReadCacheRAM < maxRAM` under the monitor, so a reader that arrives during a run neither repeats the copy-and-sort nor queues for a run of its own. `reclaimReadCacheRAM` (#7184) no longer sorts at all: it runs the CLOCK sweep synchronously (cost proportional to the bytes asked, not to the cache) |
| S1 read coalescing | `PageManager.loadPage`, `readPage`, `awaitInFlightRead`, `inFlightReads` | One `CompletableFuture<CachedPage>` per page in flight. The first thread to miss installs it and reads; every thread missing the same page while the read is in flight parks on the future and gets the same image, which is what each would have read itself (all of them passed the read-cache and flush-queue checks before the read began). A failed read fails every joiner with the same exception and leaves nothing behind. An abandoned prefetch, and an owner interrupted BEFORE its read (the pre-acquire check of the per-page I/O interlock throws `InterruptedIOException`), send the joiners to read themselves; an interrupt that lands DURING the channel read is absorbed by `PaginatedComponentFile`'s reopen-and-retry, the read completes and the joiners get the owner's image. A joiner whose wait runs out (5 s, a safety net never hit in practice) takes the read over: its future replaces the owner's (`replace(key, owner, mine)`) and it reads the page itself, counted in `inFlightReadTimeouts`; before the review fix it re-joined the same unsettled future for ever. `pagesRead` now counts pages LOADED; joiners are `coalescedReads`. After a read lands, the flush queue is re-checked once (one map get) so the commit-time version probe can never be answered by an image older than a published, evicted, not-yet-written page |
| S2 per-miss CPU tax | `PageManager.getCachedPage`, `loadPage`, `putPageInReadCache`, `checkForPageDisposal`; `PaginatedComponentFile.read` | One disposal check per logical read (the duplicate in `loadPage` is gone, the one in `putPageInReadCache` only wakes the evictor); the check is one `AtomicLong` read and a compare in the steady state, no clock at all; the legacy throttle reads `CoarseClock`. The page read goes through a per-thread direct `ByteBuffer` (grown to the largest page the thread has read, pages above 512 KB read in place) and ONE bulk copy into the heap page. `FileChannel.read(heapBuffer)` also copies once, through `sun.nio.ch.Util`'s per-thread temporary direct buffer, but measured on the OS page cache it costs 22 us per 64 KB page against 13 us for the explicit direct read (benchmark (d)): the JDK takes and returns its buffer per call and copies through `ByteBuffer.put(ByteBuffer)`. What is left per miss is the zeroed `byte[]` of the page, which is M18/L4 |
| M1 eviction without sort-the-world | `PageManager.PageCacheEvictor`, `sweep`, `CachedPage.referenced`/`pinned` | A dedicated daemon thread per page-manager lifecycle, parked until a page access finds the cache past 95% of `maxPageRAM` (a CAS and, when parked, one unpark: a thousand readers past the threshold cost one syscall), sweeps down to 90% and parks again. The sweep is CLOCK over the read cache's own `ConcurrentHashMap` (nothing extra to maintain): a page a bulk scan alone read goes first (#11011), a pinned page is passed over while the pinned pages seen in the last lap fit 10% of the budget, a page with the reference bit set gets the bit cleared and one more lap, a page with the bit clear is evicted. The reference bit is set by `updateLastAccesses` only when it was clear (no cache-line fight on hot pages, same reasoning as #8523). `remove(key, instance)` evicts only the instance inspected, so a newer version a commit published meanwhile stays. Readers park (200 us slices, 100 ms at most, then go on with a counter) only above 110%; the publication path (a committer under the page-manager lock, the flush thread) never parks, it only wakes. Pinned by construction: page 0 of every file (HASH page 0 with the directory start, LSM page 0, Dictionary); pinned by the loader through `PageManager.pinPage`: the tail and root page of every LSM series on the lookup path (`LSMTreeIndexCompacted.searchInCompactedIndex`, one map get each per lookup). HASH directory pages beyond page 0 are not pinned (see deferred) |
| prefetch | `PageManager.prefetch(Collection<PageId>)`, `prefetch(PageId)`, `PagePrefetchPool` | A new `DedicatedThreadPool` (`ArcadeDB-PagePrefetch-N`, `arcadedb.pagePrefetchPoolThreads` default `max(8, 2 x cores)`, `arcadedb.pagePrefetchQueueSize` default 4096, bounded queue, caller-runs with the throttled WARNING, `pool=page_prefetch` row in `PoolMetrics`). A prefetch installs the page's in-flight future AT SUBMISSION, so a reader arriving before the pool starts the task joins it instead of reading too; a page cached, in flight, in the flush queue (`PageManagerFlushThread.isPageInQueue`, a map probe) or past its file is skipped (allocation-free when cached), and so is every page once the cache is past the evictor's wake threshold (`prefetchSkippedOverBudget`). The task runs under the database's shared lock, taken with a `tryLock` that never waits, re-checks under it that the database is open, the file is the same registered file and the cache is under the hard limit, and abandons the future otherwise; the joiners then read the page themselves. Nothing ever waits on a prefetch, which is what makes the pool deadlock-free by construction (the #6568 shape needs a waiter) |
| L1 batched hydration | `LocalDatabase.lookupByRIDs(RID[], boolean)`, `DatabaseInternal.lookupByRIDs` (default), `LocalBucket.getRecords(long[], int, int)`, `FetchFromRidsStep`, `BucketIterator` positions mode, `LocalBucket.loadMultiPageRecord` | One read lock, the transaction's own records and the lazy shells answered first, the rest grouped by bucket then sorted by position with two primitive `long[]` sorts (index packed under bucket id, then position over run index; a shape that does not fit the packing falls back to one lookup per RID), one `getRecords` per bucket: distinct pages collected once, prefetched together when there are at least two, each page read once; a plain in-page record is a view of the page in hand, every other shape (placeholder, chunk head) is handed to `getRecordInternal`, which finds the page cached. Output aligned with the input, `null` where the single lookup raises `RecordNotFoundException`. `FetchFromRidsStep` (`SELECT FROM [#rids]`, `WHERE @rid IN [...]`) hydrates in batches of 512 through it instead of an `existsRecord` probe plus a lazy `lookupByRID` per RID; the index-hit path (`GetValueFromIndexEntryStep` -> `PhysicalOrderRidFetcher` -> `LocalBucket.iterator(positions)`) prefetches the distinct pages of each batch of up to 1,024 positions before reading them; `loadMultiPageRecord` issues the next chunk's page read as soon as the pointer is decoded, before the current chunk is copied, reusing the identifier for the hop |

Preserved exactly: the write side (`flushPage`, `writePageWithLock`, `concurrentPageAccess`) and its per-page interlock; the
version-monotonic put (#4925); pages immutable once published; `BulkScan` semantics (#11011); transaction isolation (a
prefetch only fills the shared cache, `tx.getPage` pins under `REPEATABLE_READ` as before); every read event. No new
dependency. Configuration added: `arcadedb.pagePrefetchPoolThreads`, `arcadedb.pagePrefetchQueueSize` (both `SCOPE.JVM`).
`arcadedb.freePageRAM` keeps its meaning only for the legacy fallback path; the evictor works to the 95/90/110 constants
on `PageManager`.

New counters on `PageManager.PPageManagerStats`: `coalescedReads`, `prefetchRequested`, `prefetchIssued`,
`prefetchSkippedOverBudget`, `inFlightReadTimeouts`, `batchedRecordLookups`, `synchronousEvictionRuns`, `evictorWakeups`,
`readerParks`.

## 2. Numbers

Measured by `engine/src/test/java/performance/PageCacheBenchmark.java` (`@Tag("benchmark")`), one JVM, before and after
through the test-only switches, on this machine (Apple silicon laptop, OS page cache warm, so a "cold" page read is a
syscall and one copy, about 6 us, not a 100 us device round trip: the device-bound gains of 02-retrieval-speed.md section
1 are not reproducible here and are not claimed below).

Two runs: run 1 on a quiet machine (before the read-path window fix of 2(a) and before the direct read buffer of S2),
run 2 with the fixes but while another worktree ran a 5M-document benchmark on the same machine, so run 2's absolute
numbers are 2 to 3x slower across the board and only its before/after pairs are comparable. `-Xmx7g`, 64 KB bucket pages.

**(a) 32 threads missing the same page, 200 pages** (`pagesRead` = physical page loads; `coalesced` = readers that joined a
read in flight; `allocMB` = page bytes allocated by the loads).

| run | mode | pagesRead | coalesced | allocMB | ms |
|---|---|---|---|---|---|
| 1 | before | 688 / 684 | 0 | 43 | 4857 |
| 1 | after (window still open) | 244 / 238 | 451 / 850 | 15 | 3489 / 2379 |
| 2 | before | 927 / 792 | 0 | 57 / 49 | 8987 / 16450 |
| 2 | after | 206 / 206 | 784 / 667 | 12 | 1447 / 7307 |

The ideal is 200 (one load per page). Run 1's 244 came from the instant between the owner completing its future and
putting the page in the cache, closed in run 2 by caching before completing (206 = 200 + 6 pages whose 32 readers all
arrived after the load finished). "Before" is far under the 6,400 of the storm the design doc describes because on the
OS page cache a 64 KB read takes about 10 us and most of the 32 threads arrive after it; the regression test holds the
read with the fault injector and shows the exact 32 -> 1. The ms column is dominated by thread creation and says nothing.

**(b) Eviction stall, 2,048 MB cache (32,768 pages), 314,572 records of 10 KB (1.5x the cache), 8 reader threads, 5 s per
mode, two passes** (run 1 only; `syncRuns` = whole-cache copy-and-sort runs on a reader's thread; `wakeups`/`parks` = evictor
wake-ups and readers parked above 110%).

| mode | lookups/s | mean us | p99 us | p99.9 us | max us | evicted | syncRuns | wakeups | parks | cache MB at end |
|---|---|---|---|---|---|---|---|---|---|---|
| before (pass 1) | 29,569 | 264 | 3,636 | 15,397 | 934,389 | 81,925 | 40 | 0 | 0 | 1,624 |
| after (pass 1) | 59,352 | 134 | 1,307 | 13,592 | 563,523 | 150,819 | 0 | 147 | 70 | 1,886 |
| before (pass 2) | 126,043 | 67 | 393 | 3,257 | 522,564 | 323,012 | 43 | 0 | 0 | 1,825 |
| after (pass 2) | 205,137 | 40 | 352 | 2,555 | 247,221 | 527,262 | 0 | 574 | 0 | 1,880 |

Throughput 2.0x and 1.6x, mean 2.0x and 1.7x, p99 2.8x and 1.1x, p99.9 1.1x and 1.3x, max 1.7x and 2.1x. The max column of
both modes is GC: 2 GB of `byte[]` churned at 100K+ pages per second on a 7 GB heap (the eviction run itself, at 32K
pages, sorts in a few ms; the 0.2 to 0.5 s of the design doc is at 800K pages, where the sort is 25x larger and the
after column does not change). The after cache sits at 92% of its budget (the evictor's 90% low-water plus the lag) where
before oscillated between 50% and 100% (each run dropped half the cache); 70 reader parks in pass 1 (the first 5 s, the
evictor catching up from a full cache with 8 readers missing at 60K/s) and none in pass 2.

**(c) Hydrate of 2,000 random RIDs over 200,000 records of 600 B (1,320 distinct pages)**, median and best of 7 (run 1)
and 15 (run 2) rounds. Cold = evicted from the page cache (the OS cache stays warm, so a miss is about 6 to 20 us, not a
device round trip).

| run | mode | ms median | ms best | pagesRead |
|---|---|---|---|---|
| 1 | cold serial `lookupByRID` x 2,000 | 61.65 | 23.99 | 1,320 |
| 1 | cold `lookupByRIDs` | 31.22 | 10.47 | 1,320 |
| 1 | warm serial | 2.70 | 1.54 | 0 |
| 1 | warm `lookupByRIDs` | 3.72 | 3.32 | 0 |
| 2 | cold serial | 127.20 | 59.70 | 1,320 |
| 2 | cold `lookupByRIDs` | 77.96 | 13.68 | 1,320 |
| 2 | warm serial | 4.83 | 1.43 | 0 |
| 2 | warm `lookupByRIDs` | 7.82 | 4.66 | 0 |

Cold: 2.0x and 1.6x at the median, 2.3x and 4.4x at the best; the same 1,320 page loads, in flight together instead of one
after the other. Run 3 (after the review fixes, 15 rounds, quieter machine): cold serial 30.94 ms median / 13.74 best, cold
`lookupByRIDs` 12.44 / 5.28 (2.5x / 2.6x), same 1,320 page loads. On NVMe, where a miss is 100 us and not 6 us, the serial figure is 1,320 x 106 us = 140 ms and the batched
one about 1,320 / 32 x 128 us = 5 ms plus bandwidth, which is the P9b arithmetic of 02-retrieval-speed.md 2.9 and is
not reproducible on this machine. Warm: runs 1 and 2 showed the batched call 1 to 3 ms SLOWER per 2,000 records. That was a measurement artefact, re-measured
in 2(c') below: the warm-up loop of this benchmark primes the single-lookup path, and 7 or 15 rounds of `lookupByRIDs` never
reach C2.

**(c') Warm hydrate, re-measured after the review** (`PageCacheBenchmark.warmHydrate`): 2,000 cached RIDs, serial and batched
run as an A/B PAIR in every round (alternating which goes first), the first 50 rounds discarded, and each path split into
its phases by replaying them through the same public calls. Two runs, us per 2,000 records:

| phase | run A median (200 pairs) | run B median (350 pairs) | ns/record (B) |
|---|---|---|---|
| serial `lookupByRID` x 2,000 | 1,217.8 | 737.0 | 368.5 |
| batched `lookupByRIDs` | 1,194.0 | 700.7 | 350.4 |
| batched: pack + two `long[]` sorts | 108.3 | 92.6 | 46.3 |
| batched: `LocalBucket.getRecords` (page touches, prefetch probes, views) | 541.4 | 379.5 | 189.8 |
| batched: record construction (copy + `ImmutableDocument`) | 431.0 | 220.3 | 110.2 |
| serial: `LocalBucket.getRecord` x 2,000 | 641.6 | 414.9 | 207.5 |
| serial: record construction | 445.3 | 223.3 | 111.6 |

Batched won 121 of 200 and 186 of 350 pairs: warm, the two paths are at parity (the batched one 2 to 5% faster at the
median). The real overhead the reviewer estimated is the pack and the two sorts, 90 to 110 us per 2,000 records (about 50
ns a record), and it is paid back by `getRecords` touching each of the 1,320 pages once instead of 2,000 times (35 to 100 us
saved). Construction is identical. The warm path is therefore no reason to keep hydrates on the single lookup.

**(d) The page read itself: 50,000 random 64 KB preads from a 256 MB file in the OS cache, us per read.**

| run | pass | `read(heap)` | `read(direct)` + one copy |
|---|---|---|---|
| 1 | 0 | 22.32 | 14.15 |
| 1 | 1 | 22.61 | 12.54 |
| 2 (loaded) | 0 | 74.44 | 64.99 |
| 2 (loaded) | 1 | 39.12 | 33.40 |

9 us per 64 KB page on a quiet machine, 6 to 10 us under load: the reason the per-thread direct buffer of S2 was
implemented after all (1,320 pages x 9 us is 12 ms of a cold 2,000-RID hydrate). On a device read the saving is the same
absolute 9 us on top of 100 us.

## 3. Tests

New: `PageManagerReadCoalescingTest` (32 threads, one physical read, exact through the `onPhysicalPageRead` hook of the
read fault injector; a failed read fails every joiner and is retried by the next read), `PageManagerBackgroundEvictionTest`
(zero sort-the-world runs on reader threads, the cache stays under the hard limit, page 0 of the bucket file survives a
30 MB churn through a 4 MB cache, `reclaimReadCacheRAM` still frees), `PageManagerPrefetchTest` (misses issued once,
hits skipped, a page past the file ignored, a reader joins a prefetch in flight), `LocalDatabaseLookupByRIDsTest`
(alignment and content against one lookup each over mixed buckets with duplicates and shuffled input; deleted, past-bucket
and dangling-bucket RIDs are `null`; the transaction's own record is answered; one page read per distinct page and the
misses prefetched; `REPEATABLE_READ` and lazy shells; the `SELECT FROM [#rids]` and `WHERE @rid IN` paths).

Added by the review fixes (section 6): `PageManagerPrefetchTest` (a prefetch held after its read across a close and reopen,
and across a drop and recreate at the same path, leaves nothing to the next lifecycle; a pool that shuts down under the
submission leaves no read in flight), `PageManagerReadCoalescingTest` (a joiner whose wait runs out reads the page itself;
an owner interrupted before its read sends its joiners to read themselves), `PageManagerBackgroundEvictionTest` (a prefetch
never publishes over the budget, at submission or in the task; `maxPageRAM = 0` parks no reader),
`LocalDatabaseLookupByRIDsTest` (shells only for records that exist, negative positions are null entries, a wrapper without
a batched path answers through its own guarded single lookup, a `LIMIT 1` pull hydrates one RID, `SELECT @rid FROM [...]`
keeps lazy shells), and `Issue8363DirectoryReplacementClientGateTest` in `ha-raft` (`lookupByRIDs` is refused while the
directory is being replaced). Each of the major-fix tests was checked to fail with its fix reverted.

Run green before the review (`mvn -o -pl engine test -Dtest=...`): the four above plus `PageManager*`, `LocalBucket*`, `Bucket*`,
`Transaction*`, `PaginatedComponentFile*`, `WAL*`, `Issue6129*`, `Issue6217*`, `Issue6258*`, `Issue9070*`, `Issue9620*`,
`*ChunkChain*`, `*Placeholder*`, `Issue7184*`, `Issue8333*`, `Issue11011*`, `Issue11012*`, `Issue10654*`, `Issue5855*`,
`Issue4643*`, `LowRamProfileTest`, `ContextConfigurationTest`: 89 classes, 396 tests, 0 failures. `mvn -o -pl engine,server
compile` green (the `PoolMetrics` row).

## 4. Risks and what to watch

- **Evictor lag.** Readers no longer evict, so between 95% and 110% the cache can overshoot while the evictor sweeps; above
  110% readers park in 200 us slices for at most 100 ms and then go on, counted in `readerParks`. A sustained non-zero
  `readerParks` means the miss bandwidth outruns one sweeping thread (about 1 us per page visited); the remedy is a lower
  low-water mark or a second hand, both constants on `PageManager`. The 4 MB-cache test overshoots by a few pages at most.
- **CLOCK versus the old LRU.** The old run evicted exactly the oldest `freePageRAM`% by timestamp; CLOCK approximates LRU
  with one bit and evicts in small increments down to 90%, which keeps far more of the warm set between runs (the old
  default dropped half the cache each time). Pages a commit published and nobody read are evicted after two laps, which is
  correct (they are in the flush queue or on disk) and was already possible under the old policy.
- **Pinned budget.** Pinned pages lose their immunity when the pinned pages seen in the LAST lap exceed 10% of `maxPageRAM`;
  the first lap after startup treats all of them as immune. A database with thousands of tiny LSM series (every tail and
  root is pinned on first lookup) on a tiny cache is the shape that trips it; at the 23 GB cache of 3.1 the pinned set is
  1.3 GB at 3x, under the 2.3 GB budget.
- **Coalesced image age.** A joiner receives the owner's image, which the owner started reading before the joiner arrived.
  This is the same race the independent reads had (a read that began before a publish returns the older version), and
  the version-monotonic put keeps the cache right; the flush-queue re-check after the read makes the commit-time version
  probe strictly safer than before. A joiner waits on the owner's I/O; a stalled disk stalls them as it stalled their
  own reads.
- **Prefetch pool saturation** runs the read on the submitting thread (as it would have run anyway) and logs the throttled
  WARNING; watch `caller_run_fallbacks` on `pool=page_prefetch`. A prefetch of a page the caller then never reads is a
  wasted read: the three callers (a hydrate, a positions batch, the next chunk) read what they prefetch.
- **`FetchFromRidsStep` loads content eagerly** where it used to hand out lazy shells after an `existsRecord` probe. The
  probe already read the page, so the eager load is one page touch instead of two; a record an after-read listener
  filters is now skipped at the step, as `lookupByRID(rid, true)` skips it, instead of surfacing as a shell. Since the
  review an identity-only statement (`SELECT @rid FROM [...]` with no WHERE, LET, GROUP BY, ORDER BY or UNWIND) keeps the
  lazy shells: the planner sets `FetchFromRidsStep.setLoadContent(false)` and `lookupByRIDs(rids, false)` answers a shell
  for a record that exists (slots read, nothing copied) and `null` for one that does not.
- **Per-thread direct read buffers** (review m5, documented only). `PaginatedComponentFile.READ_BUFFER` grows to the
  largest page a thread has read, up to 512 KB, and lives as long as the thread. A JVM with hundreds of threads that read
  pages (500 Undertow workers, a large prefetch pool, the query pool) can hold hundreds of MB of direct memory in them,
  outside the heap and outside `maxPageRAM`. The JDK's own per-thread temporary buffer cache, which the heap read path used,
  has the same shape but is capped by `jdk.nio.maxCachedBufferSize` when set. If it shows up, the remedy is a bounded pool of
  buffers (or a cap on the size kept per thread, reading larger pages in place); watch `BufferPoolMXBean` "direct".
- **Pinned budget is all-or-nothing and lags a lap** (review m6, documented only). Pinned pages are passed over while the
  pinned weight seen in the LAST lap fits 10% of the budget, and all lose their immunity at once when it does not; the
  first lap after startup treats every pinned page as immune. A ranked or partial budget (keep the most recently pinned
  10%) would degrade gradually; not needed at the sizes measured (section 4, "Pinned budget").
- **`REPEATABLE_READ` chunk chains through `getRecords`** (review m10, documented only). `getRecords` hands every non-plain
  slot to `getRecordInternal`, so a multi-page record read in a batch takes the same #9070/#9620 path as a single read (the
  snapshot chain, the re-pin retry, `getSnapshotRecordContent`). Correct by construction, but a batch of chunked records
  under `REPEATABLE_READ` gets no batching of its continuation pages beyond the one-ahead prefetch in
  `loadMultiPageRecord`.
- **A writer holding the database drops prefetches.** The prefetch task takes the shared lock with a `tryLock`, so while a
  schema change (or any other exclusive-lock holder) runs, prefetches issued on other threads are dropped and their readers
  read the pages themselves. A prefetch run on the writer's own thread (caller-runs) still runs.
- **LSM pin cost**: two map gets per series per point lookup (`pinPage` on the tail and root). On the 23-series composites of
  2.0 that is about 1 us against the 49 cached touches the lookup already pays; M2's series directory removes both.
- **Merge with the concurrent branch** (`LocalBucket.getRecordInternal`, `LocalDatabase` permission checks, index lock
  classes): this branch adds methods beside `getRecordInternal` and `lookupByRID` and changes no line of either; the two
  one-line pins in `LSMTreeIndexCompacted` are additive. `getRecords` delegates every non-plain slot to `getRecordInternal`,
  so a change to that method's semantics is inherited, not duplicated.

## 5. Deferred

- HASH directory pages beyond page 0 are not pinned: `HashIndexBucket` reads them through `readPage`/`tx.getPage`, and the
  file is in the other agent's S17 scope. The primitive (`PageManager.pinPage`) is in place; one call after
  `readDirectoryEntry`'s page read does it.
- The zeroed heap `byte[]` per miss stays until M18/L4; the direct read buffer of S2 removes the copy overhead, not the
  allocation.
- The evictor's thresholds (95/90/110/10%) are constants, not settings. `arcadedb.freePageRAM` is honoured only by the
  legacy path; 02-retrieval-speed.md 4.1 C1's advice ("do not lower it") no longer applies after this change.
- `GetValueFromIndexEntryStep`'s point-lookup path (`toResult` -> `lookupByRID` per entry, under `MAX_POINT_LOOKUP_ENTRIES`)
  still hydrates one record at a time; the range path is covered through the positions iterator's prefetch.

## 6. Review findings and fixes

An independent adversarial review found five majors and several minors. Every major is fixed with a regression test, and
each of those tests was checked to fail with its fix reverted (the source restored from a copy afterwards).

| Item | Finding | Fix | Test |
|---|---|---|---|
| M1 | A prefetch task held no database lock and no page-manager lifecycle reference. A close or drop ran under it (the exclusive lock excludes readers, not this task), and the image it read was put into the cache after the teardown's sweep: a database reopened or recreated at the same path (`PageId.equals` is by path) was served the old lifecycle's page, and a put after `shutdown()` drifted `totalReadCacheRAM` | The task runs under the database's shared lock (`RWLockContext.tryExecuteInReadLock`: a `tryLock` that barges past queued writers and never waits, so a task can never be queued behind a writer that waits for a reader parked on this prefetch) and re-checks under it that the database is open and `getFileIfExists(fileId)` is still the same file. `removeAllReadPagesOfDatabase`, `deleteFile`, `overwritePage` and `shutdown` abandon the reads in flight of what they tear down BEFORE sweeping the cache; a read whose future was abandoned while in flight takes its own put back out (`readPage`), so a put that lands after the sweep is undone. `overwritePage` now also accounts the page it removes | `PageManagerPrefetchTest.aPrefetchInFlightAcrossACloseLeavesNothingToTheReopenedDatabase`, `...AcrossADropLeavesNothingToTheRecreatedDatabase` (the read is held after it has the bytes, through a new `onPhysicalPageReadCompleted` hook of the test-only fault injector; a second database keeps the page manager alive) |
| M2 | The `DatabaseInternal.lookupByRIDs` default routed to `getEmbedded()`, so `RaftReplicatedDatabase` and `ServerDatabase` skipped `refuseClientWhileDirectoryIsReplaced`: during a snapshot install `SELECT FROM [#rid...]` and `WHERE @rid IN` read the discarded copy | `RaftReplicatedDatabase.lookupByRIDs` guards and delegates to the proxied database, `ServerDatabase.lookupByRIDs` delegates to `current()`; the default no longer unwraps and answers one `lookupByRID` at a time on the wrapper itself | `Issue8363DirectoryReplacementClientGateTest` (ha-raft, `lookupByRIDs` added to the client entry points); `LocalDatabaseLookupByRIDsTest.aWrapperWithoutABatchedPathAnswersThroughItsOwnSingleLookup` |
| M3a | On the 5 s timeout a joiner looped, found the same owner future and waited again, for ever if it never settled | The joiner takes the read over: `inFlightReads.replace(pageId, owner, mine)` and reads; a joiner that loses the replace joins the winner. Counted in `inFlightReadTimeouts`; the wait is a package-private volatile so the test can shorten it | `PageManagerReadCoalescingTest.aJoinerWhoseWaitRunsOutReadsThePageItself` |
| M3b | `prefetch()` checked `isShutdown()` and then submitted; a pool that shut down in between runs nothing (its caller-runs policy drops a plain task on a shut-down pool, `shutdownNow()` drains the queue), leaving the future in `inFlightReads` for ever | After the submission, `isShutdown() && !mine.isDone()` abandons the prefetch; a task that does run later finds its future settled and does nothing. `prefetch(PageId, ExecutorService)` is package-private for the test | `PageManagerPrefetchTest.aPoolThatShutsDownUnderTheSubmissionLeavesNoReadInFlight` |
| M4 | Prefetch never consulted the budget and its publication never parks: thousands of queued prefetches could publish far past the 110% hard limit | `prefetch(PageId)` declines once the cache is at the evictor's wake threshold (95%); the task re-checks under the lock and drops the page above the hard limit (110%). Both counted in `prefetchSkippedOverBudget` | `PageManagerBackgroundEvictionTest.aPrefetchNeverPublishesOverTheBudget` (the test holds the evictor's lock so the cache can be driven past both thresholds) |
| M5 | `maxPageRAM = 0` (legal) made every threshold 0 and, with a `>=` park test, every page access parked for up to 100 ms | The thresholds derive from `max(maxPageRAM, 16 x 256 KB)` (`MIN_EVICTION_BUDGET`, 4 MB) and the park test is strict (`>`) | `PageManagerBackgroundEvictionTest.aZeroPageRAMBudgetDoesNotParkEveryRead` |
| m1 | `shutdown()` did not join the evictor: a sweep in progress could subtract after the reset | `shutdown()` joins it (10 s bound); `startup()` resets `totalReadCacheRAM` too | covered by the eviction tests |
| m2 | A negative RID position broke the position packing of `lookupByRIDs` | A negative position (or bucket id) is a `null` entry; the one-by-one fallback skips them too | `LocalDatabaseLookupByRIDsTest.withoutContentAShellIsHandedOutOnlyForARecordThatExists` |
| m3 | `FetchFromRidsStep` hydrated 512 RIDs per pull whatever the pull owed, and `SELECT @rid FROM [...]` copied content it used to leave in lazy shells | The batch is `min(512, nRecords - internalNext)`. The planner marks an identity-only statement (`SelectExecutionPlanner.projectsOnlyTheRecordIdentity`) and the step then asks `lookupByRIDs(rids, false)`, which now answers existence-checked shells (#4643 still holds: a missing RID is skipped) | `LocalDatabaseLookupByRIDsTest.aLimitedPullHydratesOnlyWhatItOwes`, `...anIdentityOnlyProjectionKeepsTheLazyShells` |
| m4 | The prefetch's flush-queue check copied a whole page to answer "is it queued" | `PageManagerFlushThread.isPageInQueue` (two map probes) | existing prefetch tests |
| m7 | `setReadCoalescingEnabled` / `setBackgroundEvictionEnabled` are JVM-wide statics a test could leave flipped | Reset to `true` by every `startup()` | n/a (the benchmark flips them after opening its database) |
| m8 | `BucketIterator.prefetchPositionPages` ran before the batch's `executeInReadLock`, so a caller-runs prefetch read a page with no database lock | Moved inside the lock body | existing positions-mode tests |
| m9 | This document claimed "a joiner waits at most 5 s before reading itself" (false until M3a) and that an interrupted owner sends joiners to read themselves (true only for an interrupt before the I/O interlock) | Text corrected in section 1 | `PageManagerReadCoalescingTest.aJoinerWhoseWaitRunsOutReadsThePageItself`, `...anInterruptedOwnerSendsItsJoinersToReadThePageThemselves` |
| m5, m6, m10 | Per-thread direct buffers, the all-or-nothing pinned budget, `REPEATABLE_READ` chunk chains through `getRecords` | Documented only (section 4) | n/a |
| skill | The pool inventory lacked the two new threads | `PagePrefetchPool` and `PageCacheEvictor` rows added to `.claude/skills/engine-concurrency/SKILL.md` | n/a |

The reviewer's "verified sound" list is unchanged: the version-monotonic put under coalescing (cache before complete), the
flush-queue re-check after the read, `REPEATABLE_READ` isolation through `tx.getPage` pinning, eviction removing only the
inspected instance, the failure fan-out, the HA follower's `materializePendingFlushOfPage`, `lookupByRIDs` alignment and
null semantics, the `FetchFromRidsStep` deque, the direct-buffer read, the legacy hygiene and the LSM pins. The warm-path
question is answered in 2(c'): parity, not a slowdown.

Measured after the fixes (same machine, shared with other worktrees' builds, so only the pairs are comparable): (a) 32
threads on one page, 200 pages: before 804 and 1,094 page loads, after 215 and 212 (ideal 200), 406 and 571 coalesced; (c)
cold hydrate 2.5x at the median; (c') warm hydrate at parity.

### Second review: DML over a RID list

A re-review found one more major. `FetchFromRidsStep` hydrates up to `nRecords` RIDs in one `lookupByRIDs` call (100 for a
DML pull) and hands them out one at a time, while DELETE and UPDATE process their rows lazily. The step used to probe and
load each RID when that RID was pulled, so it saw what the earlier rows of the same statement had done in the transaction;
the batch did not. `DELETE FROM [#x, #x]` loaded both copies, deleted the first, and the second reached
`deleteRecordInternal` on a freed slot: `RecordNotFoundException`, rethrown because the RID was deleted in the transaction,
and the statement failed. `DELETE FROM [#v, #e]` with `e` an edge of `v` deleted the edge twice and reported 2 rows instead
of 1 (the vertex delete cascades to the edge the batch had already loaded).

| Item | Finding | Fix | Test |
|---|---|---|---|
| M6 | A record buffered by the batch was handed out as it was when the batch loaded it, after earlier rows of the statement had deleted it or written a newer copy | The step buffers `Record`s (not `Result`s) and re-checks each one when it hands it out (`currentInTransaction`): skipped if `tx.isDeletedInTransaction(rid)`, replaced by `tx.getWrittenRecord(rid)` when the transaction holds a written copy. The transaction is resolved for each record (see the third review below). A new `TransactionContext.hasDeletedRecords()` (one size read) and the existing `hasWrittenRecords()` make a transaction that has neither deleted nor written cost two field reads per record; outside a transaction it is one null check. No page is touched | `FetchFromRidsStepStatementWritesTest`: `deletingTheSameRidTwiceInOneStatementDeletesOneRecord`, `deletingAVertexAndItsEdgeInOneStatementSucceeds`, `aBatchLargerThanOnePullStillSkipsWhatTheStatementDeleted` (1,400 RIDs, each twice, across several pulls and batches) fail without the fix (two with `RecordNotFoundException`, one with a count of 2 for 1). `updatingTheSameRidTwiceInOneStatementAppliesBothUpdates` passes either way (`prepareForModify` already finds the transaction's copy) and is kept as a guard |

Cost, measured by `PageCacheBenchmark.ridListRecheck` (warm, 2,000 RIDs, inside a transaction that has already deleted
1,000 and modified 1,000 other records, so the re-check takes its slow path for every record; `SELECT FROM [N RIDs]` and
the re-check replayed alone as an A/B pair per round, 200 pairs after 50 discarded, machine shared with other worktrees'
builds):

| version | statement median us | re-check median us | re-check ns/record | re-check share |
|---|---|---|---|---|
| first cut (`getTransactionIfExists` per record, `getRecordFromCache`) | 1,452 | 336 | 168 | 23% |
| shipped, run 1 | 1,238 | 167 | 84 | 14% |
| shipped, run 2 | 1,172 | 241 | 121 | 21% |

That is the worst case: a read-only `SELECT` in a transaction that has written. Outside a transaction, or in one that has
not written, the re-check is two field reads. Inside a DELETE or UPDATE the statement writes on every row, so the probes are
small next to the write. Compared with what the step did before L1 (`existsRecord` plus `lookupByRID` per RID, each with its
own read lock, cache probe and page access), the re-check is still far cheaper.

Deferred: a transaction write stamp (a counter bumped by every write to the transaction's record caches and deleted set,
recorded when a batch loads) would let a statement that writes nothing skip the re-check entirely, even inside a transaction
that has written. It would need a bump at about ten mutation and reset sites of `TransactionContext` (cache puts and removes,
statement restore, commit, rollback, reset), and one site missed would bring back a silent stale read. The gain is about
100 ns per record in that one case, so it is not worth the risk now.

### Third review: DML over a RID list with no transaction active

A third review raised one major against M6: the re-check only ran inside an active transaction, `tx` was resolved once per
pull, and `tx == null || !tx.isActive()` handed the buffered record back unchecked. In `autoTransaction` mode (how
`ArcadeDBServer` opens every database) with no `begin()`, a delete or update that opens and commits an implicit transaction
for each row leaves no transaction active between rows, and what the earlier rows did is already committed. The review named
three statements: `DELETE FROM [#x, #x]` (the second copy reaches the bucket delete on a freed slot:
`RecordNotFoundException` after the first delete committed), `DELETE FROM [#v, #e]` (the edge deleted twice, or counted
twice) and `UPDATE [#x, #x] SET n = n + 1` (the second row starts from the stale buffered image: the #6950 check raises
`ConcurrentModificationException` instead of producing n = 2).

Two corrections to the premise, then the fix that covers it:

- A plain `db.command("sql", ...)` DML does NOT run one implicit transaction per row in this mode: since #7096
  `Statement.beginImplicitTransaction` opens ONE statement-level transaction, so the rows run with a transaction active and
  the M6 check sees them. The per-row shape exists only where the statement's own `execute` is bypassed: a script line
  (`ScriptLineStep` runs `DeleteExecutionPlan.executeInternal()` / `UpdateExecutionPlan.executeInternal()` directly), so
  `db.command("sqlscript", "DELETE FROM [#x, #x];")` with no `begin()` is the reproducer, and a `BATCH n` clause, which
  commits inside the statement.
- The step in this worktree already resolves the transaction for each record and records, when a batch loads, the thread's
  transaction context and its commit count (`loadedUnder`, `loadedAtCommitCount`). A different context (a nested one popped
  or pushed) or a moved commit count re-reads the buffered record exactly as the pre-L1 step did, `existsRecord` and then
  `lookupByRID`. `LocalDatabase.begin()` reuses the thread's inactive context and `TransactionContext.resetAndFireCallbacks`
  bumps its commit count on every commit, so each per-row implicit commit is seen. The case left unchecked, no commit and
  no active transaction since the load, means the thread wrote nothing (a write outside a transaction opens one or is
  refused), so the buffered record is current as of the load and is answered without a probe.

The review's suggested alternative, an `existsRecord` probe for every buffered record whenever no transaction is active or
the database is in `autoTransaction` mode, was not taken: every database the server opens is in that mode, so it would put
a read lock, a cache probe and a page access back on every `SELECT FROM [rids]` and `WHERE @rid IN` served by the server,
the cost L1 exists to remove, to guard a case the commit count already catches.

| Item | Finding | Fix | Test |
|---|---|---|---|
| M7 | A buffered record handed out with no transaction active was not re-checked, so per-row implicit transactions in `autoTransaction` mode published deletes and updates the batch did not see | Covered by the per-record transaction and commit-count re-read described above; the field comment of `FetchFromRidsStep` now names the per-row implicit transaction of a script line and why the inactive, uncommitted case needs no probe | `FetchFromRidsStepStatementWritesTest`: `deletingTheSameRidTwiceWithPerRowImplicitTransactionsDeletesOneRecord`, `deletingAVertexAndItsEdgeWithPerRowImplicitTransactionsSucceeds`, `updatingTheSameRidTwiceWithPerRowImplicitTransactionsAppliesBothUpdates`, `aBatchLargerThanOnePullStillSkipsWhatPerRowImplicitTransactionsDeleted` (each runs the statement as a `sqlscript` line with `setAutoTransaction(true)` and asserts no transaction is active before and after). With the context and commit-count re-read removed all four fail with the review's symptoms (`RecordNotFoundException` twice, a count of 2 for 1, `ConcurrentModificationException`), as do the five `BATCH n` tests; with it, 18 of 18 pass |

The hot path is unchanged by this round (the re-read was already in place; only a comment and tests were added), so no
benchmark was re-run: the `ridListRecheck` numbers of the second review stand.
