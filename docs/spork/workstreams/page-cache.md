# Page cache workstream (S16, S1, S2, M1, prefetch, L1)

Status: implemented and measured in worktree `.claude/worktrees/agent-a6b83e7aba0748e90` (branch
`worktree-agent-a6b83e7aba0748e90`), on top of 4bc577f11. Items refer to `docs/spork/02-retrieval-speed.md` section 4.
Nothing is committed; the engine tests named in section 5 are green.

## 1. What changed

| Item | Where | What |
|---|---|---|
| S16 eviction run hygiene | `PageManager.evictOldestPages` | The legacy sort-the-world run (now the fallback behind a test-only switch, see M1) stamps `lastCheckForRAM` on ENTRY and re-checks `totalReadCacheRAM < maxRAM` under the monitor, so a reader that arrives during a run neither repeats the copy-and-sort nor queues for a run of its own. `reclaimReadCacheRAM` (#7184) no longer sorts at all: it runs the CLOCK sweep synchronously (cost proportional to the bytes asked, not to the cache) |
| S1 read coalescing | `PageManager.loadPage`, `readPage`, `awaitInFlightRead`, `inFlightReads` | One `CompletableFuture<CachedPage>` per page in flight. The first thread to miss installs it and reads; every thread missing the same page while the read is in flight parks on the future and gets the same image, which is what each would have read itself (all of them passed the read-cache and flush-queue checks before the read began). A failed read fails every joiner with the same exception and leaves nothing behind; an interrupted or abandoned owner sends the joiners to read themselves; a joiner waits at most 5 s before reading itself (a safety net, never hit in practice). `pagesRead` now counts pages LOADED; joiners are `coalescedReads`. After a read lands, the flush queue is re-checked once (one map get) so the commit-time version probe can never be answered by an image older than a published, evicted, not-yet-written page |
| S2 per-miss CPU tax | `PageManager.getCachedPage`, `loadPage`, `putPageInReadCache`, `checkForPageDisposal`; `PaginatedComponentFile.read` | One disposal check per logical read (the duplicate in `loadPage` is gone, the one in `putPageInReadCache` only wakes the evictor); the check is one `AtomicLong` read and a compare in the steady state, no clock at all; the legacy throttle reads `CoarseClock`. The page read goes through a per-thread direct `ByteBuffer` (grown to the largest page the thread has read, pages above 512 KB read in place) and ONE bulk copy into the heap page. `FileChannel.read(heapBuffer)` also copies once, through `sun.nio.ch.Util`'s per-thread temporary direct buffer, but measured on the OS page cache it costs 22 us per 64 KB page against 13 us for the explicit direct read (benchmark (d)): the JDK takes and returns its buffer per call and copies through `ByteBuffer.put(ByteBuffer)`. What is left per miss is the zeroed `byte[]` of the page, which is M18/L4 |
| M1 eviction without sort-the-world | `PageManager.PageCacheEvictor`, `sweep`, `CachedPage.referenced`/`pinned` | A dedicated daemon thread per page-manager lifecycle, parked until a page access finds the cache past 95% of `maxPageRAM` (a CAS and, when parked, one unpark: a thousand readers past the threshold cost one syscall), sweeps down to 90% and parks again. The sweep is CLOCK over the read cache's own `ConcurrentHashMap` (nothing extra to maintain): a page a bulk scan alone read goes first (#11011), a pinned page is passed over while the pinned pages seen in the last lap fit 10% of the budget, a page with the reference bit set gets the bit cleared and one more lap, a page with the bit clear is evicted. The reference bit is set by `updateLastAccesses` only when it was clear (no cache-line fight on hot pages, same reasoning as #8523). `remove(key, instance)` evicts only the instance inspected, so a newer version a commit published meanwhile stays. Readers park (200 us slices, 100 ms at most, then go on with a counter) only above 110%; the publication path (a committer under the page-manager lock, the flush thread) never parks, it only wakes. Pinned by construction: page 0 of every file (HASH page 0 with the directory start, LSM page 0, Dictionary); pinned by the loader through `PageManager.pinPage`: the tail and root page of every LSM series on the lookup path (`LSMTreeIndexCompacted.searchInCompactedIndex`, one map get each per lookup). HASH directory pages beyond page 0 are not pinned (see deferred) |
| prefetch | `PageManager.prefetch(Collection<PageId>)`, `prefetch(PageId)`, `PagePrefetchPool` | A new `DedicatedThreadPool` (`ArcadeDB-PagePrefetch-N`, `arcadedb.pagePrefetchPoolThreads` default `max(8, 2 x cores)`, `arcadedb.pagePrefetchQueueSize` default 4096, bounded queue, caller-runs with the throttled WARNING, `pool=page_prefetch` row in `PoolMetrics`). A prefetch installs the page's in-flight future AT SUBMISSION, so a reader arriving before the pool starts the task joins it instead of reading too; a page cached, in flight, in the flush queue or past its file is skipped (allocation-free when cached); a task that cannot be honoured (database closed, file dropped) abandons the future and the joiners read the page themselves. Nothing ever waits on a prefetch, which is what makes the pool deadlock-free by construction (the #6568 shape needs a waiter) |
| L1 batched hydration | `LocalDatabase.lookupByRIDs(RID[], boolean)`, `DatabaseInternal.lookupByRIDs` (default), `LocalBucket.getRecords(long[], int, int)`, `FetchFromRidsStep`, `BucketIterator` positions mode, `LocalBucket.loadMultiPageRecord` | One read lock, the transaction's own records and the lazy shells answered first, the rest grouped by bucket then sorted by position with two primitive `long[]` sorts (index packed under bucket id, then position over run index; a shape that does not fit the packing falls back to one lookup per RID), one `getRecords` per bucket: distinct pages collected once, prefetched together when there are at least two, each page read once; a plain in-page record is a view of the page in hand, every other shape (placeholder, chunk head) is handed to `getRecordInternal`, which finds the page cached. Output aligned with the input, `null` where the single lookup raises `RecordNotFoundException`. `FetchFromRidsStep` (`SELECT FROM [#rids]`, `WHERE @rid IN [...]`) hydrates in batches of 512 through it instead of an `existsRecord` probe plus a lazy `lookupByRID` per RID; the index-hit path (`GetValueFromIndexEntryStep` -> `PhysicalOrderRidFetcher` -> `LocalBucket.iterator(positions)`) prefetches the distinct pages of each batch of up to 1,024 positions before reading them; `loadMultiPageRecord` issues the next chunk's page read as soon as the pointer is decoded, before the current chunk is copied, reusing the identifier for the hop |

Preserved exactly: the write side (`flushPage`, `writePageWithLock`, `concurrentPageAccess`) and its per-page interlock; the
version-monotonic put (#4925); pages immutable once published; `BulkScan` semantics (#11011); transaction isolation (a
prefetch only fills the shared cache, `tx.getPage` pins under `REPEATABLE_READ` as before); every read event. No new
dependency. Configuration added: `arcadedb.pagePrefetchPoolThreads`, `arcadedb.pagePrefetchQueueSize` (both `SCOPE.JVM`).
`arcadedb.freePageRAM` keeps its meaning only for the legacy fallback path; the evictor works to the 95/90/110 constants
on `PageManager`.

New counters on `PageManager.PPageManagerStats`: `coalescedReads`, `prefetchRequested`, `prefetchIssued`,
`batchedRecordLookups`, `synchronousEvictionRuns`, `evictorWakeups`, `readerParks`.

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
after the other. On NVMe, where a miss is 100 us and not 6 us, the serial figure is 1,320 x 106 us = 140 ms and the batched
one about 1,320 / 32 x 128 us = 5 ms plus bandwidth, which is the P9b arithmetic of 02-retrieval-speed.md 2.9 and is
not reproducible on this machine. Warm: the batched call is 1 to 3 ms SLOWER per 2,000 records on both runs (0.5 to 1.5 us a
record), more than the two `long[]` sorts and the 1,320 cache probes of the prefetch account for; run 2's spread (serial
best 1.4 ms against median 4.8 ms) says the machine was too loaded to profile it, so it stays an open item (section 5).
Nothing in the warm path is I/O, so a server that serves its warm hydrates through the plugin's single lookups loses
nothing by keeping them there until this is understood.

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

Run green (`mvn -o -pl engine test -Dtest=...`): the four above plus `PageManager*`, `LocalBucket*`, `Bucket*`,
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
  filters is now skipped at the step, as `lookupByRID(rid, true)` skips it, instead of surfacing as a shell.
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
- `lookupByRIDs` on the HA wrapper (`RaftReplicatedDatabase`) routes through the `DatabaseInternal` default to the embedded
  database and so skips the wrapper's `refuseClientWhileDirectoryIsReplaced` check for the batch; a one-line override in
  the HA module restores it. `ServerDatabase` delegates through `getEmbedded()` correctly.
- The evictor's thresholds (95/90/110/10%) are constants, not settings. `arcadedb.freePageRAM` is honoured only by the
  legacy path; 02-retrieval-speed.md 4.1 C1's advice ("do not lower it") no longer applies after this change.
- The warm `lookupByRIDs` of 2,000 cached records measured 1 to 3 ms slower than 2,000 single lookups (2(c)); profile on a
  quiet machine before routing warm hydrates through it. Candidates: the `ImmutablePage` view per page from `tx.getPage`
  against the per-record view of the single path, and the `pages` list of `getRecords`.
- `GetValueFromIndexEntryStep`'s point-lookup path (`toResult` -> `lookupByRID` per entry, under `MAX_POINT_LOOKUP_ENTRIES`)
  still hydrates one record at a time; the range path is covered through the positions iterator's prefetch.
