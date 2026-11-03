# SPORK retrieval speed

Status: lead performance architecture, 2026-11-02, revision 2 (review log in appendix A; reconciliation with `01-architecture.md` round 6 in appendix B). Companion to `01-architecture.md`
(layout taken as given: 4 fully replicated nodes of 126 GB RAM and 2 TB NVMe, one Raft group, per-period `rec_<p>` /
`txt_<p>` / `media_<p>` databases capped at 120 GB, a small `identity` registry, `recdir`, reads served from followers n2/n3
with n1 light and n4 for analytics) and to `00-research-findings.md` (Recon access patterns). Inputs: six subsystem hotspot
audits of the working tree at commit 4bc577f11 (page cache and I/O, index lookups, graph traversal, serialization and wire,
full-text/vector/geo read paths, concurrency and configuration), the F1 selections design (`03-f1-selections-design.md`,
`RidBitmap` on RoaringBitmap), three engine workstreams already implemented in worktrees and independently re-measured in
`workstreams/verification-log.md`: parallel per-bucket vector search (`claude/vector-parallel-search-f7403e`), geo kNN
and metre radii (`claude/geo-knn-b2da7a`), n-gram/phonetic/reversed analyzers (`claude/partial-text-search-2551dc`), and
the review of revision 1 (appendix A). A retrieval benchmark module with a Recon-shaped generator and a regression gate
exists in `claude/read-bench-63f0a8` (`benchmarks/src/main/java/com/arcadedb/benchmarks/retrieval/`).

Ordering rule (owner directive 1): p50/p99 read latency and read throughput outrank write throughput and storage. Every
number below shows its arithmetic. Costs marked "estimate" come from reading the code and from the constants in 0.2;
P0a confirms them. Line numbers refer to the working tree at 4bc577f11.

---

## 0. Scope and cost model

### 0.1 What is being optimized

The Recon read patterns of `00-research-findings.md`, in the order Recon's traffic weights them (P0a records the real
mix; these weights come from the Recon code paths that call each one). Revision 2 adds the arms Recon's code actually
exercises: typed pivots, the `views_desc` and substring arms of search, `q` as a candidate set with an exact `total`,
the whole-board read, the dossier's 7,500-record hydrate, `/api/recent`, and the set-scoped `/geo/*` and `/graph/*` calls.

| # | Pattern | Recon caller | Share of interactive reads (assumed) |
|---|---|---|---|
| P1 | Intake: classify a token, return its footprint (300 newest records per token, max 2,000) | `Engine.resolveSource`, `/api/signal`, watch scans | 25% |
| P2 | Pivot 1-hop, typed: `pivot(seed, on)` for ONE identifier type, seed included, newest first, paged | `Engine.pivot` (`engine.ts:199-219`) | 10% |
| P3 | Pivot 2-hop, typed per hop | `Engine.pivot` multi-hop, RID sibling expansion | 5% |
| P4 | Structured search page: filters + posted or views sort over the full match set, 50 rows (up to 500), exact `total` up to a cap; substring arms for phone, email, business | `Engine.search` (`engine.ts:101-145`) | 15% |
| P5 | Feed window: 6 h / 24 h / 3 d / 7 d, optional state or city filter, hotspots per place with distinct phones, recurring phones with places, byState; `/api/recent` newest 1,000 located | `ops/feed.ts`, `server.ts:167-182` | 10% |
| P6 | Geo radius + time over the corpus (link-analysis platform; Recon has no corpus-wide caller) | `/geo/radius`, convergence seeds | 2% |
| P7 | Full-text `q`: candidate set ANDed with the structured filters, posted sort, exact `total` up to a cap; relevance order opt-in | `Engine.search` q, `bulk` tool (`tools.ts:412`) | 5% |
| P8 | kNN: style 24-d (SPORK), face 1:N (ROC tier) | `/similar/style`, `facial.ts` | 1% |
| P9 | Hydrate by id: 200 (watch rows), 2,000 (watch scans), up to 7,500 (dossier footprint of 25 tokens x 300) | `Engine.resolve`, `buildDossier` (`dossier.ts:383-437`) | 20% |
| P10 | Board (whole tenant board, server-sorted) and audit pages with filtered `total` and verification | `ops/board.ts`, `ops/audit.ts` | 2% |
| P11 | Set-scoped geo and graph analysis over 200 to 2,000 hydrated ids (n4) | `Engine.geoAnalyze(ids)`, `linkClusters`, `spaceTimeClusters` | 5% |

### 0.2 Constants used in every estimate

| Symbol | Value | Basis |
|---|---|---|
| R_dev | 100 us per 16 KB random read from NVMe through dm-crypt at queue depth 1; 106 us per 32 KB page (100 + 32 KB / 5 GB/s); 113 us per 64 KB page | datacenter NVMe spec; `01-architecture.md` 1.4 uses the 100 us figure |
| R_q32 | about 120 us for a batch of 32 concurrent 16 KB reads, 128 us for 32 KB pages (queue depth 32) | NVMe latency rises only slightly to QD 32; 32 reads complete in about 1.2 x R_dev |
| B | 5 GB/s read bandwidth (5 to 6.5 GB/s through dm-crypt); 6.5 us per 32 KB page, 13 us per 64 KB page | `01-architecture.md` 1.4 |
| R_os | 6 us per 16 KB page served by the OS page cache through `PaginatedComponentFile.read` (pread syscall, kernel copy into the JDK's temporary direct buffer, copy into the heap `byte[]`, zeroed allocation) | page-cache audit, hotspot 6; `PaginatedComponentFile.java:258`, `CachedPage.java:75` |
| C_idx | 0.1 us per index page hit: `ConcurrentHashMap` get and `updateLastAccesses` on a shared view (`getPageForAbsoluteReads`, #10654), allocation-free | `PageManager.java:2812-2826` |
| C_pg | 0.2 to 0.3 us and 3 allocations per record page hit: `getPageNotHeld` -> `getImmutablePage` -> `useAsImmutable` allocates an `ImmutablePage`, a `Binary` and a `ByteBuffer` wrapper per touch, and `checkForPageDisposal` reads `System.currentTimeMillis` on every `getCachedPage` and a second time in `loadPage` | `PageManager.java:1667-1673, 2609, 2677-2679, 2815`; `CachedPage.java:92-94`; `LocalBucket.java:2634, 2676`; `LocalDatabase.java:1266` (`copyOfContent`) |
| C_rec | 2 us CPU per record once projected: about 1 us to locate and decode 18 to 20 properties in one header pass (`BinarySerializer.locateProperties`, `BinarySerializer.java:610-646`, #9496) plus about 1 us to write them as JSON bytes. Today a projected row costs one header walk per projected property (`ResultInternal.getProperty` -> `ImmutableDocument.get`, `ResultInternal.java:240-248`, `ImmutableDocument.java:82-100`): 20 fields x about 0.3 us = 6 us, plus 2 to 4 us of JSON | serialization audit, hotspots 2 to 4; 4.2 S4 |
| C_hash | 3 cached page touches for a HASH point lookup (page 0 for global depth and directory start, one directory page, the bucket page; overflow pages when the bucket chained), about 6 allocations (`ArrayList`, `IndexCursorEntry`, `RID`, `TempIndexCursor`, page views), about 1.5 to 2 us warm single-threaded; cold: 1 device read (the bucket page; page 0 and the directory are resident), 2 with an overflow page. Every `get` runs under the index's single-stripe FAIR `ReentrantReadWriteLock`, so under 32 concurrent threads the lock, not the page cache, bounds it until 4.2 S17 | `HashIndexBucket.java:364-366, 410-425, 455-478, 1659-1665`; `HashIndex.java:55, 240, 246, 252-258`; `RWLockContext.java:48-50, 60` |
| C_lsm | an LSM point lookup on a fully compacted index of S series costs page 0 + per series (tail page, then bloom probe) + root + leaf = 1 + 2S + 2 cached touches and two `Binary` allocations; S = 1 is 5 touches, about 6 us warm. A live index adds one binary search per mutable page M. A bloom "no" skips the root and leaf but never the tail read (4.3 M2 removes it) | `LSMTreeIndexCompacted.java:655, 703-706, 715-722, 727-745, 779-785`; `LSMTreeIndexBloomFilter.java:262-283`; `LSMTreeIndexMutable.java:410-434` |
| C_cur | an LSM range cursor open seeks every mutable page and every compacted series (page 0, series tail and root per series, a `getPage` + `new Binary(slice)` + `lookupInPage` per mutable page): about 30 us today on a live index, about 5 us after 4.3 M6. A `TypeIndex.range` opens one cursor PER BUCKET SUB-INDEX and merges them, so cursors = seeks x buckets | `TypeIndex.java:130-150`; `LSMTreeIndexCursor.java:140-215`; `LSMTreeIndexCompacted.java:438-520` |
| Sub-indexes per lookup | `TypeIndex.get` probes the sub-index of every bucket of the type until the key is found; pruning to one happens only when the type's bucket selection strategy maps the lookup key, which the default round-robin strategy never does and `PartitionedBucketSelectionStrategy` does only for the exact partition properties. Every type of section 2 therefore has ONE bucket, so sub-indexes per lookup = 1 and cursors per range = 1 | `TypeIndex.java:238-256, 868-905`; `LocalDocumentType.java:159`; `ThreadBucketSelectionStrategy.java:58-60`; `PartitionedBucketSelectionStrategy.java:205-211` |
| Bucket page | 32 KB on Record, TextBody, Target and AuditEntry buckets, 16 KB on `recdir` and identifier vertices (D39 revised, 2.0). A v0 bucket page always carries a 2,048-entry slot table of 8,194 B whatever the page size (`contentHeaderSize = PAGE_RECORD_TABLE_OFFSET + 2048 x 4`), plus an 8 B page header, so record space is 8,182 B at 16 KB (49.9%), 24,566 B at 32 KB (75%), 57,334 B at 64 KB (87.5%). The slot count is part of the on-disk format (RID position = page x 2048 + slot), so a smaller table is a format version (4.3 M20), as `EXTERNAL_BUCKET_VERSION` already is with 256 slots | `LocalBucket.java:90-97, 131, 662-664, 673-675, 2617-2618`; `BasePage.java:37`; `GlobalConfiguration.java:280-282` |
| Records per Record page | 609 B rows (`01-architecture.md` 0.4): 13 at 16 KB v0, 40 at 32 KB v0, 94 at 64 KB v0, 25 at 16 KB with a 256-slot table (M20) | arithmetic on the row above |
| Index page | HASH indexes at the engine defaults: 4 KB for fixed-width keys (LONG, UUID: `record_id`, `upstreamRid`, `recdir.upstreamRid`, `placeId`) and 16 KB for variable-width keys (STRING, BINARY: identifier `key`, `textHash`, `imageHashes BY ITEM`), the sizes the engine's own benchmark picked (`HashIndexBucket.DEF_PAGE_SIZE`, `DEF_VARIABLE_KEY_PAGE_SIZE`, issue #5712; cap 64 KB, `HashIndexBucket.java:110`; appendix B); 64 KB on LSM range composites, FULL_TEXT and GEOSPATIAL; 256 KB on any LSM index that serves point lookups (2.0, so a sealed index compacts to 2 series, not hundreds) | `HashIndexBucket.java:78-110, 1200-1205`; `HashIndex.java:106`; `GlobalConfiguration.java:284-286`; `TypeBuilder.java:542`; `IndexBuilder.java:317` |
| Index entries per leaf | about 400 at 16 KB, 1,600 at 64 KB, 6,400 at 256 KB, at 40 B per (key, RID) entry | LSM entry layout (varint key + compressed RID) |
| Series per sealed LSM index | a compaction starts a new series when the series reaches `DEFAULT_MAX_DATA_PAGES_PER_SERIES` = 1,024 leaves OR when the next root entry does not fit the root page; the root fills at about 540 entries at 16 KB, 2,160 at 64 KB, 8,600 at 256 KB, so root overflow is the binding rule below 256 KB: 77M entries = 192K leaves at 16 KB = about 360 series; 48K leaves at 64 KB = about 23 series; 12K leaves at 256 KB = 2 series | `LSMTreeIndexCompactedStreamWriter.java:39, 172-175` |

**Warm** means every page a query touches is in the heap page cache. **Cold** means every Record, TextBody and leaf
page comes from NVMe while HASH page 0 and directory pages, LSM page 0, series tails and root pages are resident (section 3
pins them; about 0.45 GB today, 1.3 GB at 3x). Cold is the steady state for anything older than about 30 days (3.1): the 23 GB cache covers
about 1% of the bytes, so the targets are set for cold rows served at 32-way concurrency, and warm is what repeated or
recent queries get.

I/O time for N cold random reads on a 32-way pool = ceil(N / 32) x R_q32, overlapped with N x page bytes / B; the larger
term wins. With 32 KB Record pages the bandwidth term wins from about N = 300 (300 x 6.5 us = 1.9 ms against 10 rounds x
128 us = 1.3 ms), which is why 2.0 keeps Record pages at 32 KB rather than the engine's 64 KB default and why M20 (16 KB
pages with a 256-slot table, 4x the bandwidth saving of today) is a medium item rather than a nice-to-have.

**Day-1 serving rule.** Until 4.4 L1 lands the engine has no batched or prefetched record load: `GetValueFromIndexEntryStep`
hydrates index hits one `lookupByRID` at a time in key order (`GetValueFromIndexEntryStep.java:990-997`), and
`count(*)` is served by an index only for a `FROM index:<name>` target (`SelectExecutionPlanner.java:636-648`). A SQL- or
Cypher-served pattern therefore costs N x R_dev serially on day 1: 50 cold rows are 5 ms, 500 are 53 ms, a filtered count
of 10,000 is 1.06 s. Every pattern of section 2 that loads more than about 8 records cold runs day 1 on the plugin path
(embedded index cursor or `get`, RIDs collected, then the plugin's 32-way `spork-hydrate` pool), which is what earns the
division by 32 in every cold cell of section 1. The SQL and Cypher shapes given per pattern are the admin and
link-analysis equivalents and carry the serial Day-1 cost until L1.

---

## 1. Latency targets

Targets are per request as seen by Recon at the plugin route on n2 or n3, after section 2's schema and the day-1
configuration (4.1) are in place, and before any code change in 4.2 to 4.4 (which are what take the "stretch" column).
p50 and p99 are over a 10-minute window at 2,000 mixed requests per second per node (1.1). Today's upstream baseline is
p50 1.08 s and p95 1.55 s for structured search under 150 simulated users.

| # | Pattern (rows) | Warm p50 | Warm p99 | Cold p50 | Cold p99 | Stretch cold p50 / p99 after 4.3 to 4.4 | Arithmetic (cold p50) |
|---|---|---|---|---|---|---|---|
| P1 | Intake, 1 token, 300 records | 3 ms | 10 ms | 6 ms | 20 ms | 3 / 10 ms (M20, L1) | registry HASH probe 3 resident touches; local key HASH: 1 bucket page; 1 edge-chunk page; 300 Record pages of 32 KB: 302 reads -> ceil(302/32) = 10 x 128 us = 1.3 ms, bytes 9.6 MB / 5 GB/s = 1.9 ms, overlapped about 2.2 ms; CPU 300 x C_rec = 0.6 ms (today 3 ms, 4.2 S4); request overhead 0.3 ms; plan cache hit. Total about 3.1 ms, 6 ms with contention margin |
| P2 | Typed pivot 1-hop, 500 records | 4 ms | 12 ms | 8 ms | 25 ms | 4 / 12 ms | 1 HASH bucket page + 1 chunk page + 500 Record pages = 503 reads: 16 rounds x 128 us = 2.0 ms, 16 MB = 3.2 ms, about 3.5 ms; CPU 500 x 2 us = 1 ms; one identifier type read from the seed, so no merge |
| P3 | Typed pivot 2-hop, 300 then 500 records | 10 ms | 35 ms | 25 ms | 80 ms | 12 / 40 ms (L1, L2, M20) | hop 1 as P1; about 500 distinct second-hop keys of the requested type: 500 HASH bucket pages + 500 edge-chunk pages (16 KB) + 500 Record pages (32 KB) + 302 = 1,803 reads: 57 rounds x 125 us = 7.1 ms, 41.6 MB = 8.3 ms, about 9 ms; CPU 800 x 2 us + 500 HASH gets x 2 us + set work 1 ms = 3.6 ms |
| P4 | Structured search, posted sort, first page 50 rows + count capped at 10,000 | 5 ms | 20 ms | 10 ms | 40 ms | 5 / 20 ms | one composite cursor (Record is 1 bucket): 1 root (resident) + 1 or 2 leaves of 64 KB; 50 Record pages (32 KB); titles: 50 `textHash` HASH bucket pages (16 KB) + 50 TextBody pages (32 KB); count: 7 leaves (10,000 / 1,600): 159 reads: 5 rounds x 125 us = 0.6 ms, 4.6 MB = 0.9 ms; cursor open 30 us; CPU 50 x 2 us + count 0.2 ms = 0.3 ms. Day 1 SQL-served instead: 150 serial reads = 16 ms, which is why it is a plugin path |
| P4b | Structured search, 500 rows | 12 ms | 40 ms | 25 ms | 80 ms | 12 / 40 ms | 500 Record + 500 TextBody (32 KB) + 500 textHash bucket pages (16 KB) + leaves = 1,520 reads: 48 rounds x 125 us = 6 ms, 41 MB = 8.3 ms, about 9 ms; CPU 500 x 2 us = 1 ms |
| P4v | Structured search, `sort=views_desc`, 50 rows | 5 ms | 20 ms | 10 ms | 40 ms | 5 / 20 ms | `(facet, views, tsPosted)` cursor DESC with the window checked on the key bytes: 1 to 7 leaves (stops at 50 accepted or 10,000 scanned); past 10,000 scanned, fall back to `(facet, tsPosted, views)` over the window up to the 10,000 count cap (7 leaves) sorted by views in the plugin (1 ms), `partial: true` beyond; + 50 Record + 50 titles: about 165 reads, 4 MB, about 1.2 ms I/O + 0.3 to 1.3 ms CPU |
| P4s | Structured search with a phone, email or business substring filter | 8 ms | 25 ms | 15 ms | 50 ms | 5 / 20 ms (M6) | `grams3 BY ITEM` or `digitSuffixes BY ITEM` range on the partition's local identifier vertices: 1 cursor + 2 leaves -> K <= 200 candidate keys (verified `contains`); K cursors on `(phones BY ITEM, tsPosted, views)` (or emails, businessNames) over the window: 200 x 30 us = 6 ms of cursor opens today (1 ms after M6) + 200 leaves (12.8 MB, 2.6 ms); k-way merge by tsPosted; 50 Record + 50 titles (2.4 MB): about 3 ms I/O + 6 ms + 0.5 ms CPU; `partial: true` when K is cut at 200 |
| P5 | Feed 6 h: summary + 300 newest rows, sampled slices | 4 ms | 15 ms | 8 ms | 25 ms | 4 / 12 ms | 1 `FeedWindowSummary` read (HASH 3 touches + 1 page) + `(located, tsPosted)` range 1 leaf + 300 Record pages, plus 20 keyset leaves and 200 Record pages when `sampled=true`; almost always warm (Raft applied them minutes ago); cold worst 522 reads: 17 rounds x 125 us = 2.1 ms, 17.3 MB = 3.5 ms; CPU 500 x 2 us = 1 ms. A `state` or `city` feed takes the same shape on `(stateCode, tsPosted, views)` or `(placeId, tsPosted, views)` with the aggregates computed over the 300 hydrated rows in the plugin (0.5 ms), as Recon does today |
| P5b | `/api/recent`: newest 1,000 located records | 4 ms | 15 ms | 12 ms | 40 ms | 6 / 20 ms | `(located, tsPosted)` range: 1 leaf + 1,000 Record pages in the live tail (warm: 1,000 x C_pg = 0.3 ms + CPU 2 ms); cold worst 32 rounds x 128 us = 4.1 ms, 32 MB = 6.4 ms, about 7 ms + CPU 2 ms. `geoAnalyze` and `spaceTimeClusters` over the 1,000 are P11 |
| P6 | Geo radius 200 km x 14 days, newest 500 + capped count | 12 ms | 40 ms | 25 ms | 80 ms | 10 / 30 ms (M6, M7, M20) | about 200 geohash-4 cells (2.6) x 1 bucket = 200 cursor opens x 30 us = 6 ms today (1 ms after M6); 200 leaves of 64 KB (12.8 MB) + 500 Record pages (16 MB) = 700 reads: 22 rounds x 125 us = 2.8 ms, 28.8 MB = 5.8 ms, about 6 ms; haversine on the packed lat/lon in the key, no record read for rejected candidates; CPU 500 x 2 us = 1 ms. Had Record 4 buckets this would be 800 cursor opens (24 ms) and 800 leaves |
| P7 | `q` (2 terms, median df 0.2% of a partition) ANDed with filters, posted sort, exact total up to the 2,000-hit cap | 40 ms | 150 ms | 60 ms | 250 ms | 15 / 60 ms after M9 + L3 + L2 | today: all postings of both terms materialized twice: 80K x 2 x 2 x 60 ns = 19 ms CPU per partition (190 ms at the 2% gate), partitions in parallel; resolve up to 2,000 textHash hits newest partition first through the non-unique `textHash` HASH on Record: 2,000 bucket pages = 63 rounds x 120 us = 7.5 ms cold (4 ms warm CPU); with filters AND the `RidBitmap` of the structured arm (7 leaves) and hydrate 50; without filters hydrate up to 4,000 refs through the P9b path (128 MB = 26 ms cold, 10 ms warm) and sort, filter and count in the plugin. About 55 ms cold at median df, 225 ms at the gate. `sort=relevance` keeps the old `ORDER BY $score` top-50 path at the same walk cost |
| P8a | Style kNN k = 20 over 16 month buckets of `StyleVec` | 5 ms | 20 ms | 15 ms | 60 ms | same (already parallel in the worktree) | measured 0.72 ms single client at 160K rows x 16 buckets parallel (verification log); at 8M per bucket a beam-100 walk is 1 to 3 ms warm per bucket, parallel so max not sum: 3 ms + merge; cold: about 10 graph hops x 100 us = 1 ms per bucket from NVMe through `OnDiskGraphIndex`; the 20 `textHash` resolves hit TextBody's single bucket |
| P8b | Face 1:N (ROC tier, 4-way fan-out) | 310 ms | 500 ms | 310 ms | 600 ms | not a SPORK path | 21.75M templates per node x 0.23 us / 16 cores = 0.31 s today; 0.74 s at the 205M cap (`01-architecture.md` 4). Compute-bound in ROC; SPORK only adds the hydrate of the matched records (P9) |
| P9 | Hydrate 200 records by id | 2 ms | 8 ms | 6 ms | 20 ms | 3 / 10 ms | upstream RID: `recdir` HASH bucket page (4 KB) + `recdir` doc page (16 KB) + partition `upstreamRid` HASH bucket page (4 KB) + Record page (32 KB) = 4 reads, 56 KB; 200 x 4 = 800 reads: 25 rounds x 125 us = 3.1 ms, 11 MB = 2.2 ms, about 3.5 ms; `record_id`s: 400 reads, 7.2 MB, 2 ms; CPU 200 x 3 us = 0.6 ms |
| P9b | Hydrate 2,000 records (watch scan) | 12 ms | 35 ms | 45 ms | 120 ms | 30 / 80 ms (M20, L1) | upstream RIDs: 8,000 reads: 250 rounds x 125 us = 31 ms, 112 MB = 22 ms, about 35 ms + CPU 6 ms = 41 ms; `record_id`s: 4,000 reads: 125 rounds = 16 ms, 72 MB = 14 ms, about 20 ms + 6 ms = 26 ms; 0.5% of the page cache per call (80 and 40 MB after M20) |
| P9c | Dossier: `/dossier:footprint` (25 tokens) + hydrate of up to 7,500 `record_id`s with the analysis projection (styleVec, socials, imageHashes, lat, lon, age) | 40 ms | 100 ms | 120 ms | 300 ms | 60 / 150 ms (M20, L1) | footprints: 25 x (HASH + bucket page + chunk page) = 75 reads, under 1 ms on the pool; hydrate 7,500 x (HASH bucket page 4 KB + Record 32 KB) = 15,000 reads: 469 rounds x 125 us = 59 ms, 270 MB = 54 ms, about 65 ms; CPU 7,500 x 3 us = 22 ms. Warm: 7,500 x (3 x C_pg + 3 us) = 28 ms. Served by n4 (`01-architecture.md` routes dossier footprints there) |
| P10a | Board: whole tenant board (up to 500 targets), server-sorted | 3 ms | 15 ms | 8 ms | 30 ms | same | `(tenantId, closed)` range: 1 leaf + up to 500 Target pages (600 B rows, 40 per 32 KB page, written over months so about one page per target cold): 16 rounds x 125 us = 2 ms, 16 MB = 3.2 ms; sort by (closed, priority, scoreRaw desc, updatedAt desc) in the plugin, 500 x log2 500 compares, under 0.1 ms; `read_your_writes` with the caller's bookmark |
| P10b | Audit page, 100 entries newest first with filters, `total` and `verification` | 4 ms | 15 ms | 10 ms | 40 ms | same | `(action, seq)` or `(caseRef, seq)` or `(subjectHmac, seq)` composite: 1 leaf; 100 AuditEntry pages (300 B each, 81 per 32 KB page, consecutive seq so about 2 pages unfiltered, 100 filtered, 3.3 MB); `total` from the per-filter counters in `ChainHead` (1 page) and `verification` from the checkpoint document (1 page); 4 rounds = 0.5 ms; `linearizable` adds a leader read-index round trip, 0.5 to 1 ms on 10 GbE (`RaftReplicatedDatabase.java:2616-2622`; `RaftHAServer.java:6936-6938`) |
| P11 | Set-scoped `/geo/*` or `/graph/*` over 2,000 hydrated ids (n4) | 60 ms | 150 ms | 100 ms | 250 ms | 60 / 150 ms | hydrate as P9b by `record_id` (26 ms cold, 10 ms warm) + CPU of the analysis itself: DBSCAN hotspots at 2,000 points = 2,000^2 x 20 ns = 80 ms, convergence and tracks under 20 ms, union-find link clusters under 5 ms. n4 only, so the n2/n3 budget is unaffected |

**Throughput per read node.** CPU per request at the weights of 0.1: 0.25 x 1.5 ms (P1) + 0.10 x 2 (P2) + 0.05 x 4.5 (P3)
+ 0.15 x 0.7 (P4, all arms) + 0.10 x 1.5 (P5) + 0.02 x 2 (P6) + 0.05 x 60 (P7 today, median df) + 0.01 x 3 (P8a) + 0.20 x 1
(P9) + 0.02 x 0.5 (P10) + 0 (P11 and P9c run on n4) = 0.375 + 0.2 + 0.225 + 0.105 + 0.15 + 0.04 + 3.0 + 0.03 + 0.2 + 0.01 =
4.3 ms of CPU per mixed request. 32 cores at 70% useful utilization give 32 x 0.7 x 1,000 / 4.3 = 5,200 requests/s;
**target 2,000 mixed requests/s per node with the p99 column holding**, 6,000 across n1 to n3 (n1 at weight 1), which
leaves a 2.6x margin for the ROC search cgroup taking up to 16 cores during face searches and for the estimates being
optimistic. P7 is 70% of the CPU budget until M9 lands (P7 at 20 ms: mixed cost 2.3 ms, ceiling 9,700/s); after L3 (P7 at
5 ms) the mixed cost is 1.6 ms and the ceiling 14,000/s.

---

## 2. Design per pattern

Common rules, applied to every pattern:

- **One bucket per type.** A type index over N buckets is N sub-indexes: `TypeIndex.get` probes them one after another
  until the unique key is found (`TypeIndex.java:238-256`), and `getIndexesByKeys` returns all of them unless
  `type.getBucketIndexByKeys` names one (`TypeIndex.java:868-905`), which the default `RoundRobinBucketSelectionStrategy`
  never does (`LocalDocumentType.java:159`; `ThreadBucketSelectionStrategy.java:58-60` returns -1) and
  `PartitionedBucketSelectionStrategy` does only for a lookup that covers exactly the partition properties
  (`PartitionedBucketSelectionStrategy.java:205-211`), so at most ONE key per type could ever be pruned. `TypeIndex.range`
  opens a cursor per sub-index and merges them (`TypeIndex.java:130-150`). Each sub-index `get` takes its own fair read
  lock (`HashIndex.java:55, 240, 246`; `LSMTreeIndex.java:84, 594, 707, 726`) and reads its own pages, so a 16-bucket
  Record would turn every `upstreamRid`, `textHash` or `imageHashes BY ITEM` lookup into 16 probes, 16 lock round trips
  and up to 16 cold bucket-page reads, and every P6 cell seek into 16 cursor opens. Parallel scans do not need buckets:
  `ParallelTypeScan.plan` cuts one large bucket into page ranges (`arcadedb.queryParallelScanPagesPerUnit`,
  `GlobalConfiguration.java:1106-1120`; `ParallelTypeScan.java:174-189` accepts a single bucket whose units are at least
  2). So Record, TextBody, `recdir`, every identifier vertex type, Target, AuditEntry and every `identity` type have one
  bucket (`TYPE_DEFAULT_BUCKETS` = 1, `GlobalConfiguration.java:277`), and no `PartitionedBucketSelectionStrategy` is
  needed. The only multi-bucket type is `StyleVec` (2.8), whose LSM_VECTOR search is per bucket and whose only other
  index is none: TextBody itself stays single-bucket so the 50 `textHash` title resolves of P4 are 50 probes, not 800.
- **Bucket page sizes** (D39 revised): a v0 bucket page spends 8,202 B on header and slot table whatever its size
  (`LocalBucket.java:131, 662-664`), so 16 KB Record pages would hold 13 records (50% waste) and a 77M-record partition
  would take 95 GB of Record pages. Record, TextBody, Target and AuditEntry buckets use 32 KB (40 Record rows per page,
  75% density, 62 GB per partition; a cold record read moves 32 KB = 6.5 us of bandwidth); `recdir` (24 B rows) and
  identifier vertices use 16 KB because their rows never fill 2,048 slots' worth of space anyway. 64 KB (94 rows,
  87.5%, 52 GB) is the engine default and the fallback if 32 KB shows a problem in P0a; it doubles every bandwidth term
  of section 1. 4.3 M20 adds a 256-slot bucket format version so 16 KB pages hold 25 rows and the per-record bandwidth
  halves again. Any document that must stay single-page (FeedWindowSummary's hot part, Target list rows) stays under the
  record space of its page, 24,566 B at 32 KB, or it is read as a serial chunk chain (`LocalBucket.java:937, 2666`).
  Created in SQL: `CREATE [VERTEX|DOCUMENT] TYPE ... PAGESIZE n` reaches `TypeBuilder.withPageSize`
  (`CreateVertexTypeStatement.java:41-53`, `CreateDocumentTypeStatement.java`; appendix B), with
  `arcadedb.bucketDefaultPageSize=32768` for the rest. `CREATE INDEX` has no PAGESIZE clause (`CreateIndexStatement.java:404`),
  so an index page size that differs from its engine default needs `IndexBuilder.withPageSize` (`IndexBuilder.java:317`):
  FULL_TEXT and GEOSPATIAL at 64 KB, because their builders ignore `arcadedb.indexDefaultPageSize` (`IndexBuilder.getPageSize()`
  defaults to 256 KB; only plain LSM uses `getLsmTreePageSize`), while the HASH indexes below take their defaults and need no
  Java. On disk, the 25% header share of a 32 KB v0 page is about +180 B per Record over `01-architecture.md` 0.4's 637 B
  line (+14 GB per partition) until M20; P0a measures it (`model/budget.py` prints the density table).
- **HASH for every exact key, at the engine default page sizes (4 KB fixed-width keys, 16 KB variable-width; appendix B).** `identity` (`Phone.key`, `Email.key`, `Handle.key`, `Business.key`,
  `ImageHash.key`, `Url.key`, `Name.key`, `Place.placeId`, `Target` id, `Watch` key), `recdir.upstreamRid`, and per
  `rec_<p>`: `record_id`, `upstreamRid`, `textHash` (non-unique), `imageHashes BY ITEM` (non-unique) and the local
  identifier `key`; `txt_<p>`: `TextBody.textHash` UNIQUE. A HASH lookup is C_hash: 3 cached touches, no mutable tail,
  no series walk, no compaction state (`HashIndexBucket.java:410-452`; `HashIndex.compact()` and `scheduleCompaction()` return
  false, `HashIndex.java:471, 650`, so a HASH index never ships a compaction through the Raft log; growth is a bucket split or
  a directory doubling, ordinary page writes replicated like bucket pages), and critically no series count: an LSM point index
  of 77M keys at 16 KB pages compacts to about 360 series (0.2), which is 1 + 2 x 360 cached touches per lookup until
  M2, and at 64 KB still 23 series. `rev`, `digits`, `grams3 BY ITEM`, `digitSuffixes BY ITEM` and `phonetic BY ITEM`
  are separate LSM range indexes on the same vertices (64 KB) and are unaffected. HASH is non-unique capable (RID lists,
  `HashIndex.java:252-258`); `BY ITEM` is validated as a property-type rule, not an index-type rule
  (`TypeIndexBuilder.java:423-425`), and P0a confirms it on HASH. Verified limits (appendix B): `supportsOrderedIterations()` is
  false (`HashIndex.java:406`), so ranges stay on the LSM composites; keys may be any scalar type including LONG, STRING,
  BINARY and UUID (`HashIndexBucket.isSupportedKeyType`); an entry must fit one page (`entryTooLarge`; SPORK keys are under
  100 B); the directory depth is bounded at 30. Fallback for any exact key that cannot be HASH: LSM at
  256 KB pages (2 series after the seal compaction, 1 + 2 x 2 + 2 = 7 touches, a cold leaf = 100 us + 51 us of transfer).
- **Range composites at 64 KB pages.** A newest-first range with LIMIT 50 reads one or two leaves: at 256 KB each leaf
  costs 100 us + 256 KB / 5 GB/s = 151 us and moves 16x the bytes needed; at 16 KB a 77M-entry index needs 192K leaves,
  about 360 series and a cursor open that seeks every series; 64 KB (113 us per leaf, 48K leaves and about 23 series
  per 77M entries) is the balance. FULL_TEXT and GEOSPATIAL indexes are created at 64 KB too (index audit, hotspot 8).
- **Compaction state is a read-side requirement, bounded by the Raft log (appendix B).** A sealed partition's composites sit
  at M = 0 and S = about 23 (root overflow at 64 KB, 0.2), which a range cursor tolerates (one seek per series, M6 makes them
  views) and a point lookup does not, hence HASH above. `01-architecture.md` 0.4 and E0 set the policy, and revision 2's C5
  variant was withdrawn in reconciliation: every compacted page the leader writes ships through the Raft log, and each
  incremental compaction round appends one series, so per database `arcadedb.indexCompactionFullSeriesThreshold=0` and
  `arcadedb.indexCompactionFullDeadEntriesRatio=0` (no automatic full compaction; `GlobalConfiguration.java:1283-1292`,
  defaults 10 and 1.0), `arcadedb.indexCompactionMinPagesSchedule=64` on `identity` and the current period (default 10; a
  sealed partition has no mutable pages after its seal compaction, so the value is moot there), and full compactions are
  plugin jobs under the `transient-slot` lease: every LSM index of a partition at seal and after admission (27 composites x
  77M x 40 B = 83 GB per seal, one 3 GB index at a time), weekly for the current FULL_TEXT (18.5 GB) and the `identity` LSM
  indexes (about 3 GB), about 28 GB/week and 4 GB/day at 3x pace. What that costs reads: a live composite on the current
  partition gains a series per round, 835K records x 40 B = 33 MB a day = 8 rounds at schedule 64, so up to 56 series at the
  end of a week at 3x pace (13 today) and up to 64 mutable pages: a cursor open there is about 56 x 2 + 64 = 175 cached
  touches, 50 to 100 us against 30 us today, 200 cursor opens in P6 up to 20 ms on the live partition only, which M2 (series
  directory and mutable-page fences) and M6 (cursor views) remove and P0a measures; the fallback is a twice-weekly full
  compaction of the current period's composites (+27 GB/week of log). Schedule 4 would have made it 127 rounds a day and 892
  series a week for a saving of 60 cached binary searches per open, and raised the incremental log traffic from about 8 to
  14 GB/day (each round also rewrites the metadata, tail and root pages, about 192 KB against 256 KB of merged entries);
  `indexCompactionFullSeriesThreshold=3` on `identity` is a no-op for the indexes that never reach 3 series and an unbounded
  automatic rewrite for the ones that grow (the AuditEntry composites, 2.7 GB a year of entries, every 3 rounds = every
  1.7 days, about 1.5 GB/day and rising), so it is 0 there too. Point lookups are HASH and have no compaction state.
  `arcadedb.indexBloomFilterRate` stays at its default 0.01 (`GlobalConfiguration.java:1309-1313`, "enabled"): it is not a
  lever, and the blooms matter more as series grow (3.6).
- **Denormalization on Record** (D33, kept): phones[], emails[], socials[], businessNames[], imageHashes[], city,
  stateCode, countryCode, areaCodes[], lat, lon, geoCell4, placeId, tsPosted, tsCollected, views, age, coded arrays,
  styleVec (96 B float32, inline because `fuzzy` link clusters read it for every record of a hydrated set and an extra
  page per record would cost 50% more reads on P9c). Ad text never sits on Record (`txt_<p>`, console role only).
- **EXTERNAL properties** keep hot rows small where a field is rarely read: `TextBody.text` (LZ4, external pages;
  already in 1.2), `Target.snapshot` and `Target.history`, `Mission.iterations` and `Mission.events`,
  `WikiRevision.body`, `Evidence.body`, `AuditEntry.detailCiphertext`. A `RETURN n` or `SELECT FROM` on these types must
  not inline them (4.3 M4 adds `inlineExternalProperties=false`; until then the plugin projects explicitly).
- **Routing and consistency.** Every interactive read goes to n2 or n3 (weight 3 each) or n1 (weight 1), through
  `/api/v1/query` or the plugin route (which calls the embedded `database.query`, never `command`), so no auto-commit
  transaction wraps it (`PostQueryHandler.java:55-63`; `DatabaseAbstractHandler.java:403-417` wraps `/command`), parallel
  scans are allowed (`ParallelTypeScan.java:231-240` refuses them inside a transaction) and the follower skips
  `analyze()` and `isLeader()` (`RaftReplicatedDatabase.java:1588-1611`). The default consistency `read_your_writes`
  (`arcadedb.ha.readConsistency`, `GlobalConfiguration.java:2507-2509`) is already free on a follower for a request
  without a bookmark: it waits only when `!isLeader() && ctx.readAfterIndex() >= 0` and the applied index is behind
  (`RaftReplicatedDatabase.java:2607-2615`); only `linearizable` issues the leader ReadIndex RPC
  (`RaftReplicatedDatabase.java:2616-2622`; `RaftHAServer.java:6936-6938`). The rule is therefore: send
  `X-ArcadeDB-Read-Consistency: read_your_writes` with the `X-ArcadeDB-Read-After` bookmark
  (`DatabaseAbstractHandler.java:231-256`) only on the request that follows the caller's own write, and `linearizable`
  only for audit pages and the board OCC read-before-write; `eventual` as the server default would change nothing
  measurable, so the key is left at its default (appendix B). n4 takes dossier footprints and hydrates, set-scoped graph and geo
  analysis (P11) and anything over the full-text df gate.
- **Wire.** Session token (`Bearer AU-...`) or API token on every request, never Basic (PBKDF2 at 65,536 iterations,
  `AbstractServerHttpHandler.java:579-590`, `GlobalConfiguration.java:2308`); `serializer: "record"` (the `json`, `studio`
  and `graph` serializers count edges per vertex, `JsonSerializer.java:541-548`, which walks a hub's whole adjacency
  chain); `Accept: application/x-ndjson` for anything over 100 rows (`NdJsonResultStream.java:75`); explicit projections;
  parameterized statements with `LIMIT $limit` (`PostCommandHandler` appends a literal limit otherwise and the statement
  cache keys on it). Binary wire (Bolt or gRPC streaming) is 4.4 L5.

### 2.1 P1 Intake

**Schema.** `identity`: `Phone` etc. with HASH UNIQUE `key`, properties recordCount, firstSeen, lastSeen, periodMask
(a bit per period), dayCells (LONG[] of (dayBucket << 16 | geohash4-ordinal) counters, newest 120 days), componentId,
hub, stop. `rec_<p>`: local `Phone` etc. (one bucket, 16 KB pages) with HASH UNIQUE `key`, the LSM range indexes of the
partial-search rows (`rev`, `digits`, `grams3 BY ITEM`, `digitSuffixes BY ITEM`, 64 KB), and the lightweight `HAS_PHONE`
etc. edges from Record (`LightEdgeRID`, no edge record). The adjacency the intake walks is the identifier's in-edge
list: it is a RID array in insertion order read newest first (`EdgeLinkedList` iterates from the newest chunk through
`previous` pointers, `ResettableIteratorBase.java:350-363`), and it yields Record RIDs directly, so each record is one
page read. `arcadedb.graph.edgeListInitialChunkSize=2048` on the identifier types (`GlobalConfiguration.java:716-718`;
the stock 64 B first chunk means a 500-record identifier sits in about 7 chunks per direction, 7 record lookups and 8 KB
of copies before the walk yields anything): a 2 KB first chunk holds about 200 entries at about 10 B per entry, which
covers over 95% of identifiers under the Zipf shape (the benchmark generator's biggest phone hub is 1% of records; the
median shared identifier has under 10). Hubs grow geometrically to 8 KB chunks (`LocalDatabase.java:206, 394-405`).
`arcadedb.graph.supernodeThreshold=1000000` (`GlobalConfiguration.java:712`) so a hub stays one chain: the 16-stripe
promotion exists for write contention and costs a read 16 head-chunk loads before the first entry
(`StripedEdgeList.java:630-660`). Space: 2,048 B x 0.6 local vertices per record = +1.2 KB per record on the first chunk
in the worst case of singletons; the arch counts 120 B for 64 B chunks, so this is +1.1 KB x 77M = 85 GB per partition if
every singleton gets a 2 KB chunk, which does not fit. So: local identifier vertices should get the 2 KB chunk only
once they hold 2 or more records (61% of local vertices by refs but about 20% by count), and singletons keep 64 B. **The
engine cannot express that today (appendix B):** `arcadedb.graph.edgeListInitialChunkSize` is declared `SCOPE.DATABASE` but
the static `LocalDatabase.getNewEdgeListSize(0)` reads the JVM-wide value (`LocalDatabase.java:394-399`) and
`GraphEngine.createInEdgeChunk` / `createOutEdgeChunk` take no size (`GraphEngine.java:1176, 1203`); there is no per-vertex,
per-type or per-database scope and no `GraphEngine` API for it. Day 1 therefore keeps 64 B everywhere (a 500-record
identifier costs 7 chunk hops of 3 to 8 us each, S7), and 4.2 S18 is the engine item (0.5 to 1 ew): a per-type first and
second chunk size in the vertex type metadata, read by `getNewEdgeListSize`, so a singleton keeps its 64 B chunk and any
identifier that needs a second chunk jumps to 2 KB (2 chunks for a 200-record identifier instead of 6), at about
2 KB x 20% x 46M = 18 GB per partition on the local vertices, or `identity` only at no partition cost.

**Shape.** The plugin, embedded API on the follower (no SQL parse):
1. `spork-keys` normalizes the token; `^\d+:\d+$` is a legacy RID (2.9 below under P9).
2. Registry: `identity` HASH `get(key)` -> (periodMask, recordCount, hub, stop). Absent means a singleton or unknown:
   probe each partition's local `key` HASH newest first until found (12 probes at 3x on the hydrate pool; a HASH miss
   has no bloom, so each probe is one bucket-page read, 12 x 100 us serially or about 0.2 ms on the pool; the identifier
   cache of 3.2 absorbs repeats).
3. For each period in periodMask, newest first: local `key` HASH lookup (1 bucket page) -> vertex RID -> in-edges of
   `HAS_*` through `Vertex.getConnectedVertexRIDs(IN, types)` (RIDs only, no edge objects) until 300 RIDs are collected
   or the period is exhausted; `truncated` and `totalAtLeast` from recordCount.
4. Records: `lookupByRIDs` (4.4 L1; until then the `spork-hydrate` 32-way pool, caller-runs on saturation, RIDs sorted by
   (bucket, page) so two records on one page cost one read) with the Recon projection (20 fields, one header pass after
   4.2 S4).
5. RID 1-hop siblings (Recon's intake on a RID): the record's own phones[] and imageHashes[] arrays on Record, then step
   3 per identifier with a per-identifier cap of 300 and the non-unique `Record.imageHashes BY ITEM` HASH for hashes (no
   local vertex): one probe each, because Record is one bucket.

Cypher equivalent for admins, same plan: `MATCH (i:Phone {key: $key})<-[:HAS_PHONE]-(r:Record) RETURN r.record_id,
r.tsPosted, r.phones, r.city ORDER BY r.tsPosted DESC LIMIT 300` with `NodeIndexSeek` and `asVertex(false)` (4.2 S5) so the
Record is loaded once for the projection, not once for the seek. Day 1 it hydrates serially (0.2): 300 x 106 us = 32 ms
cold, so it is the admin path, not Recon's.

**Page touches (cold, 1 period).** 1 HASH bucket page (local key, 16 KB) + 1 edge-chunk page (2 KB chunk inside a 16 KB
vertex page) + 300 Record pages (32 KB) = 302 reads = 10 rounds x 128 us = 1.3 ms against 9.6 MB / 5 GB/s = 1.9 ms,
about 2.2 ms; plus the registry HASH probe on 3 resident pages. Why it meets 6 / 20 ms cold: I/O 2.2 ms + CPU 0.6 ms +
request 0.3 ms = 3.1 ms; the p99 budget absorbs one eviction-free queueing delay and a 300-record token whose records
spread over two partitions (2 bucket pages, 2 chunks).

### 2.2 P2 Pivot 1-hop (typed)

Recon's pivot is typed: `pivot(seed, on)` expands ONE identifier type (phone, email, imageHash, businessName or
social), includes the seed record, and returns the seed's `values` for that type (`engine.ts:199-219`; `identifiersOf`
returns a single array per `on`, `engine-identifiers.ts:4-17`; dossier drills seed `pivot "<v>" on:<type>`,
`dossier.ts:342`; `tools.ts:138` passes `a.on`). `/pivot` therefore takes `on` (one type, or a list) and returns `values`,
the seed row and the per-identifier `hub` flag; `01-architecture.md` 1092's `/pivot` row gains the parameter. The plugin
reads only that array from the seed Record (1.4 phones on average instead of about 4 identifiers of all types), merges
only those identifiers' adjacency, and never displaces phone-linked records with image-hash siblings.

Same adjacency as P1, paged with a keyset cursor on the edge-chunk position (chunk index, entry offset) rather than on
tsPosted, so page N+1 resumes at the chunk where page N stopped: `EdgeLinkedList` supports positional resume through
`ResettableIteratorBase`. Hubs (recordCount > 2,000) return `hub: true` and the same cursor. 500 Record pages (32 KB) + 1
HASH bucket page + 1 chunk page (2 for hubs that cross a chunk) = 503 reads = 16 rounds x 128 us = 2.0 ms, bytes 16 MB /
5 GB/s = 3.2 ms, about 3.5 ms; CPU 500 x 2 us = 1 ms; total about 4.5 ms cold, so 8 / 25 ms holds with margin for a
second partition.

### 2.3 P3 Pivot 2-hop

**Shape.** Hop 1 as P2 (300 records, typed). The plugin dedups the second-hop identifier keys of the requested type(s)
into a sorted `String[]` (about 300 x 2.4 refs = 720 keys when all types are asked, about 500 distinct after dedup, 420
when `on=phone`; hub and stop keys excluded unless `includeHubs`), then:
- per partition in the union of periodMasks, `getMany(sortedKeys)` (4.4 L2; today a loop of HASH `get`, 3 touches and
  one lock round trip each) -> 500 vertex RIDs -> 500 edge-chunk pages -> candidate Record RIDs with a per-identifier
  cap (default 50) -> `RidBitmap` union (F1, `RidBitmap.java`) minus the hop-1 set -> newest 500 by a bounded heap on the
  tsPosted carried in the edge chunk order (newest first per identifier, so the merge is a k-way merge of 500 sorted
  runs) -> `lookupByRIDs`.
- Cypher shape for the identity layer (`expand(seed, 2, linkTypes, perTypeCap)`): `MATCH (i:Phone {key: $key})<-[:HAS_PHONE]-
  (r1:Record)-[:HAS_PHONE]->(j)<-[:HAS_PHONE]-(r2:Record) WHERE j <> i RETURN DISTINCT r2 ORDER BY r2.tsPosted DESC LIMIT
  500`. The optimizer path streams `ExpandAll` in batches; the legacy SQL `MATCH` materializes every hop
  (`MatchEdgeTraverser.java:417-463`, 4.3 M12), so the plugin uses the embedded walk until M12 lands.

**Page touches.** 302 (hop 1) + 500 HASH bucket pages (16 KB) + 500 chunk pages (16 KB) + 500 Record pages (32 KB) =
1,802 cold reads = 57 rounds x 125 us = 7.1 ms, 41.6 MB / 5 GB/s = 8.3 ms, overlapped about 9 ms; CPU 800 x 2 us + 500
HASH gets x 2 us + bitmap work (500 x 50 = 25K RIDs, under 1 ms) = 3.6 ms. 25 / 80 ms cold holds; the stretch 12 / 40 ms
needs L1, L2 (500 lookups as one sorted sweep instead of 500 lock round trips) and M20.

### 2.4 P4 Structured search page

Recon's search (`engine.ts:101-145`) ANDs every filter (city, state, country, areaCode, phone, email, businessName,
service, imageHash, hasImages, age, window, q), sorts the FULL match set by `posted_desc`, `posted_asc` or `views_desc`
(`engine.ts:135-140`), and returns an exact `total` (`engine.ts:142-144`); `phone`, `email` and `businessName` are
SUBSTRING matches (`engine.ts:115-118`). The design serves each of those as its own arm.

**Schema (per `rec_<p>`, Record, one bucket, LSM_TREE composites at 64 KB).** Posted-sort arm, all with tsPosted as the
trailing sort key and views after it so the entry carries both without a record load (`SelectExecutionPlanner.fullySorted`,
`SelectExecutionPlanner.java:5294-5368`): (stateCode, tsPosted, views), (countryCode, tsPosted, views), (placeId,
tsPosted, views), (cityNorm, tsPosted, views) for city-only filters (city is denormalized, so no Place resolution on the
hot path), (areaCodes BY ITEM, tsPosted, views), (services BY ITEM, tsPosted, views), (age, tsPosted, views), (hasImages,
tsPosted, views), (website, tsPosted, views), (aggregator, tsPosted, views), (located, tsPosted) for P5, (tsPosted, views)
for an unfiltered window, (tsCollected). Views-sort arm: (stateCode, views, tsPosted), (placeId, views, tsPosted),
(cityNorm, views, tsPosted), (areaCodes BY ITEM, views, tsPosted), (services BY ITEM, views, tsPosted), (hasImages,
views, tsPosted), (age, views, tsPosted), (views, tsPosted). Identifier arm: (phones BY ITEM, tsPosted, views), (emails
BY ITEM, tsPosted, views), (businessNames BY ITEM, tsPosted, views), which serve the exact phone, email and business
filters and the substring arm below; `imageHash` uses the non-unique `imageHashes BY ITEM` HASH and intersects by
`RidBitmap`. Each composite entry is about 48 B; 27 composites x 77M x 48 B = 100 GB of index per partition at 3x density
is the storage price of serving every sort self-answering, which the ordering rule accepts.

**Shape (posted sort).** The plugin opens the embedded range cursor on the composite it picks (one cursor: Record is one
bucket) per partition newest first, reads keys until `limit` entries pass the residual predicates evaluated on the key
bytes where the key carries them and on the Record row otherwise, keyset `(tsPosted, record_id)` for the next page, then
hydrates on the 32-way pool. The planner is not cost-based (`IndexSearchDescriptor.java:76-95`,
`SelectExecutionPlanner.java:5718-5776`: every descriptor costs `Integer.MAX_VALUE` and ties resolve to `getAllIndexes`
order), so the plugin picks the composite from a per-partition cardinality table it maintains (distinct count and entry
count per filter value, refreshed at seal and hourly on the live partition). Residual over-read is bounded at 40 x limit
entries for the posted arm; beyond it the page returns `partial: true`. The admin SQL shape is `SELECT record_id,
tsPosted, phones, emails, socials, businessNames, city, stateCode, countryCode, age, services, hasImages, imageHashes,
views, source, website, textHash FROM Record WHERE stateCode = :st AND tsPosted BETWEEN :from AND :to [AND age BETWEEN
:a1 AND :a2 ...] ORDER BY tsPosted DESC LIMIT :limit` with the index hint; day 1 it hydrates serially (0.2). The 50 titles
come from `txt_<p>`: `TextBody.textHash` UNIQUE HASH (one bucket, 1 probe) + TextBody page (32 KB), title inline (text is
EXTERNAL and never read on the ops role).

**Shape (`sort=views_desc`).** A `(views, tsPosted)` composite alone orders only an unfiltered scan; with a filter the
plugin walks `(facet, views, tsPosted)` DESC and checks the window on the key's tsPosted, stopping at 50 accepted or
10,000 scanned (7 leaves at 1,600 entries). A narrow window on a busy facet (a 6 h window is 0.07% of a year-long
partition, about 70K scanned entries for 50 rows) trips the cap, and the plugin then materializes the window from
`(facet, tsPosted, views)` up to the 10,000 count cap (7 leaves), sorts the entries by views in memory (10,000 entries,
about 1 ms) and marks `partial: true` when the window exceeds the cap. The 40 x limit rule does not apply to this arm.

**Shape (substring filters).** `phone`, `email` and `businessName` are substring matches: the plugin ranges the
partition's local identifier vertices on `grams3 BY ITEM` (email, business, handle) or `digitSuffixes BY ITEM` and `rev`
(phone) for the fragment (`01-architecture.md` 665-666), verifies `contains` on the candidate keys, bounds them at K = 200
(`partial: true` when cut), then opens K cursors on the identifier composite `(phones BY ITEM, tsPosted, views)` over the
window and k-way merges them by tsPosted with the other residuals. Cost: 1 cursor + 2 leaves for the gram range, K cursor
opens (200 x 30 us = 6 ms today, 1 ms after M6), K leaves (12.8 MB, 2.6 ms), then the usual 50 rows and titles.

**Count.** `SELECT count(*) FROM Record WHERE ...` has no covering path for a type target
(`SelectExecutionPlanner.java:636-648` chains `CountFromIndexStep` only for `FROM index:` targets), so day 1 it would load
one record per entry: 10,000 x 106 us = 1.06 s cold. The plugin counts index entries through the embedded range cursor
with `LIMIT 10001` and returns "at least 10,000" past the cap: 7 leaves at 64 KB. 4.3 M3 turns this into `count(*)`
served by the index.

**Page touches (cold, first page, posted sort).** 1 root (resident) + 1 or 2 leaves + 50 Record (32 KB) + 50 textHash
bucket pages (16 KB) + 50 TextBody (32 KB) + 7 count leaves = 159 reads = 5 rounds x 125 us = 0.6 ms; bytes 100 x 32 KB
+ 50 x 16 KB + 9 x 64 KB = 4.6 MB = 0.9 ms; one cursor open 30 us; CPU 50 x 2 us + count 0.2 ms = 0.3 ms. 10 / 40 ms cold
holds by a factor of 6, which is deliberate: this is where a wrong index choice (hotspot 9) costs 1,000x, and the
plugin's forced choice is what keeps the p99 flat.

### 2.5 P5 Feed window

Recon's feed (`ops/feed.ts`) accepts `state` and `city` filters (`feed.ts:75-85`, `matchesPlace` at :161-165), keys
hotspots by place ("City, ST", `placeOf` at :135-139) each with count, DISTINCT phone count and newest (`hotspotsOf`,
:265-280), returns byState counts (:282-287), recurring phones with count, up to 6 places and newest (`recurringOf`,
:289-315), and assembles rows (cap 300), tempo and sampling (`assembleFeed`, :342-364; `FeedQuery`, `FeedHotspot`,
`FeedRecurring` at `contracts.ts:459-485`). A geo-cell rollup cannot produce those fields, so the summary is keyed the
way Recon reads it.

**Schema.** Per `rec_<p>`: (located BOOLEAN, tsPosted) and (located, tsCollected) composites at 64 KB (a record is in a
window if either time falls in it, so the two scans are unioned by `RidBitmap`, bounded at now + 2 h for misdated rows).
In `ops`: `FeedWindowSummary` documents, one per (window, tenant), rewritten every 60 s by a leased job that consumes the
Outbox, hot part under 24 KB (one 32 KB page): `total`, `located`, `byState` (stateCode, count)[], `placeTop` (placeId,
lat, lon, count, distinctPhones as an HLL sketch of 256 B or an exact set under 64, newestTs)[] for the top 64 places,
`recurring` (phoneKey, spelling, count, top 6 placeIds, newestTs)[] for phones with 2 or more records in the window,
tempo buckets, and the 20 sampling cut points on tsPosted. The job keeps the window's records in memory incrementally
(7 d x 600K/day at 3x = 4.2M (recordId, lat, lon, placeId, stateCode, phoneKeys) tuples x 64 B = 270 MB on n4).
`feed.view` is audited per request as Recon does (leader append through the internal route, 3.1 of the architecture).

**Shape.** `GET /feed/live?window=6h`: 1 `FeedWindowSummary` read (HASH 3 touches + 1 page) + the newest 300 rows from
`(located, tsPosted)` on the live partition (1 leaf) + 300 Record pages, plus the sampled slices: 20 keyset probes (20
leaves) x 10 rows = 200 more Record pages when `sampled=true`. `state=TX` or `city=Houston`: the rows come from
`(stateCode, tsPosted, views)` or `(placeId, tsPosted, views)` over the window (the P4 path, 1 to 2 leaves) and the plugin
computes hotspots per place with distinct phones, recurring phones with places and byState over the 300 hydrated rows,
exactly as Recon does over whatever it hydrates today; this is explicitly a sample-based aggregate and the response says
so (`sampling.saturated`). The newest pages are warm on followers because Raft just applied them (follower page deltas
land in the page cache).

**`/api/recent`** (`server.ts:167-182`): the newest 1,000 LOCATED records, not per-cell samples: `(located, tsPosted)`
range, 1 leaf + 1,000 Record pages, all within the last two minutes of ingest at 3x and so in the live tail; `geoAnalyze`
and `spaceTimeClusters` over them are P11 (Recon computes them over the hydrated rows, or `/geo/*` on n4 does).

**Page touches.** Cold worst case (6 h sampled) 1 + 1 + 1 + 300 + 20 + 200 = 523 reads = 17 rounds x 125 us = 2.1 ms,
17.3 MB = 3.5 ms; warm 523 x 0.25 us = 0.1 ms; CPU 500 x 2 us = 1 ms. 8 / 25 ms cold holds; the hotspot and
recurring-phone work is precomputed for the unfiltered windows, which is what removes the 50K-record (6 h at 3x: 150K)
scan Recon does today. `/recent` cold worst: 1,001 reads = 32 rounds x 128 us = 4.1 ms, 32 MB = 6.4 ms, about 7 ms + 2 ms
CPU; warm 2.5 ms.

### 2.6 P6 Geo radius + time

**Schema.** Per `rec_<p>`: composite LSM_TREE (geoCell4 STRING, tsPosted LONG, geoPacked LONG) at 64 KB on the
single-bucket Record, where geoCell4 is the geohash-4 cell (39 x 19.5 km) and geoPacked holds lat and lon quantized to
1e-5 degrees in two 32-bit halves. The third component makes the entry self-answering: the haversine runs on the key
bytes and only accepted candidates load a Record. Corpus-wide arbitrary shapes keep the GEOSPATIAL index on the 8M `Place`
vertices (D9) and use `geo.dWithinMeters` / `geo.nearest` from the geo-knn worktree (kNN k = 10 measured 0.98 ms median
against 116 ms by scan, verification log). Recon has no corpus-wide geo caller (every `geoAnalyze` takes ids,
`engine.ts:287`); P6 is a link-analysis platform pattern and weighs 2% in the mix.

**Shape.** `/geo/radius {lat, lon, radiusM, from, to, limit}`: the plugin computes the covering geohash-4 cells of the
circle (a 200 km radius spans pi x 200^2 = 125,664 km^2 / 760 km^2 per cell = 165 interior cells plus a boundary ring,
about 200), then per cell a newest-first range `range((cell, to, MAX), (cell, from, MIN))` (prefix on cell, so the
composite bloom of 4.3 M7 skips series that lack the cell), k-way merged by tsPosted into a bounded heap of `limit`.
Cursors = seeks x buckets = 200 x 1. Each cursor's first leaf holds up to 1,600 entries, so a 14-day window of a busy cell
(420K records at 3x in 200 km / 200 cells = 2,100 per cell) is 2 leaves per cell at most and the merge stops after about
500 entries in total. Exact haversine on geoPacked; survivors (about 95% inside the covering) load Records. Count: the
same cursors summed with a cap of 10,000 entries (7 leaves at 64 KB when they come from one cell, up to 200 when spread).

**Page touches.** 200 leaves (64 KB: 12.8 MB) + 500 Record pages (32 KB: 16 MB) = 700 reads = 22 rounds x 125 us = 2.8
ms, bandwidth 28.8 MB / 5 GB/s = 5.8 ms, about 6 ms; cursor opens 200 x about 30 us = 6 ms today
(`LSMTreeIndexCursor.java:140-215` opens one page cursor per mutable page and per series and binary-searches each,
`LSMTreeIndexCompacted.java:438-520` reads page 0 and every series' tail and root; 4.3 M6 cuts it to about 5 us) + CPU 500
x 2 us. About 13 ms; 25 / 80 ms cold holds; the stretch 10 / 30 ms needs M6, M7 and M20. For set-scoped convergence,
tracks and hotspots (Recon's `geoAnalyze`), the inputs are a hydrated set (P9) and lat/lon on Record (P11).

### 2.7 P7 Full-text `q`

Recon never orders by relevance: `q` is one more AND filter (`engine.ts:126-131`), the result is sorted by posted or
views over the full match set, `total` is exact, and the `bulk` tool depends on `total` for a bare term
(`tools.ts:412`: `search({ q: term, limit: 1 }).total`). P7 is therefore a candidate-set producer, not a ranker.

**Schema.** FULL_TEXT (BM25) on `TextBody.text` per `txt_<p>` at 64 KB pages (TextBody one bucket); the non-unique
`textHash` HASH on Record (2.0) maps a text to its records; the plugin's term document-frequency table in `identity`.
Evidence FULL_TEXT in `identity`. The n-gram, phonetic, reversed and phone analyzers from the partial-text worktree serve
substring and obfuscation recall on identifier fields, so `LIKE '%x%'` never runs on the hot path.

**Shape (candidate set).** Per partition newest first, in parallel while the summed df over all MUST and SHOULD terms
stays under the gate (2% of the partition's unique texts, 0.8M candidates): `SELECT textHash FROM TextBody WHERE
SEARCH_INDEX('TextBody[text]', :q) = true` with no ORDER BY, hits taken in TextBody RID order descending (insertion order,
which is newest-first within a partition) up to R = 2,000 textHash hits; each hit resolves through the `textHash` HASH on
Record (1 probe, 1 cold bucket page). With structured filters the resolved refs are ANDed with the structured arm's
`RidBitmap` over the window (whose `(facet, tsPosted, views)` entries carry tsPosted and views) and the first 50 by the
requested sort are hydrated; without filters the plugin hydrates all resolved refs (up to about 4,000 at 50% text
dedup) through the P9b path and sorts, filters and counts them in memory, which is what Recon does today. `total` is
exact while the hits fit under R and `partial: true` with "at least" beyond it, the same contract as the 10,000 count
cap. The `bulk` tool gets the same `total`. `sort=relevance` (console opt-in) keeps `ORDER BY $score DESC LIMIT 50` per
partition with the RRF merge (D28).

**Cost today.** The executor materializes every posting of every term twice (`FullTextQueryExecutor.java:604-629`,
`LSMTreeFullTextIndex.java:538-573`) into boxed maps before any cursor is built (`LSMTreeFullTextIndex.java:678-720`): at
the median df (0.2% = 80K postings) 80K x 2 terms x 2 passes x about 60 ns = 19 ms CPU per partition, 190 ms and about
240 MB of garbage at the gate; the gate on the sum of df is what bounds it. Resolve: 2,000 HASH gets = 4 ms warm,
2,000 bucket pages = 63 rounds x 120 us = 7.5 ms cold. Hydrate without filters: 4,000 Record pages (the RIDs come from the
HASH, so no `record_id` probe), 128 MB = 26 ms cold, 10 ms warm. Total cold about 55 ms at the median, 225 ms at the gate.

**After M9 and L3 (E8).** M9 folds scoring into one posting pass (50 ms at the gate); L3's Block-Max MaxScore keeps
per-term posting cursors in RID order (`BmwScorer.java:496-636`, `PaginatedSparseVectorEngine.java:583-700`), so for the
candidate set the walk is an RID-ordered intersection that stops after the first R hits: about 100K postings for a 2%
term, 6 ms; with L2's `getMany` the 2,000 resolves sweep the HASH once. Targets: 60 / 250 ms cold today (gated), 40 / 150
after M9, 15 / 60 after L3 + L2. A q term over all history above the gate is refused on n1 to n3 or routed to n4 (risk
7.6).

### 2.8 P8 kNN

**Style (SPORK).** LSM_VECTOR INT8 on `StyleVec.style[24]`, a dedicated per-`txt_<p>` document type `StyleVec {textHash,
style}` in month buckets of at most 8M (the only multi-bucket type: its one index is the vector index, searched per
bucket, and `textHash` lookups stay on the single-bucket TextBody), degree 16, `storeVectorsInGraph=true`,
`searchCacheSize=500000` explicit (`GlobalConfiguration.java:1395`). Shape: `SELECT textHash, $distance FROM StyleVec
WHERE vector.neighbors('StyleVec[style]', :vec, 20) ...` or `db.index.vector.queryNodes`; the worktree's parallel
per-bucket search fans the 16 bucket walks out on the query pool with a k-way heap merge
(`SQLFunctionVectorNeighbors.java:343`, `DbIndexVectorQueryNodes.java:228`), measured 7.3x to 8.4x single-client p50 at 16
buckets; without it the walks run sequentially and the Day-1 figure is the sum of 16 walks. The searcher pool is not a
lever: `arcadedb.vectorIndex.searcherPoolSize` defaults to 0, which sizes the pool to 2x the cores, and -1 is what
disables pooling (`GlobalConfiguration.java:1439-1447`; `GraphSearcherPool.java:93-120` borrows a pooled searcher). Set-scoped
kNN ("over this set") passes a `RidBitmap` allow-list (F1 slice 3); below 20% selectivity the index already takes the
exact pre-filter plan. Cold: an `OnDiskGraphIndex` walk is about 10 hops, each a 100 us read, per bucket in parallel =
about 1 ms, plus 20 resolve reads. 15 / 60 ms cold holds; delta-buffer scans on the ingest-target bucket (4.3 M15) are
the p99 risk. Recon's authorship clustering (`analysis/style.ts:28`) runs over a hydrated set, so corpus kNN weighs 1%.

**Face (ROC).** Not a SPORK index. The SPORK side is the hydrate of the matched Face and Record rows (P9) and the
case/watch gallery first; see `01-architecture.md` 4.1. Targets are the ROC tier's: 0.31 s today at 16 cores per node.

### 2.9 P9 Hydrate (watch rows, watch scans, dossier)

**Schema.** `recdir`: `{upstreamRid LONG, period SHORT}` one bucket at 16 KB with HASH UNIQUE `upstreamRid` (LONG key, 4 KB
HASH pages); per partition `record_id` HASH UNIQUE and `upstreamRid` HASH UNIQUE on the single-bucket Record. `record_id` carries the
period in its high bits, so it needs no `recdir` probe.

**Shape.** `POST /records:hydrate {ids[], projection}`: the plugin groups ids by kind, resolves upstream RIDs through
`recdir` (sorted keys, one `getMany` after L2; today 200 HASH `get` calls on the 32-way pool), groups the result by
partition, looks the Record RIDs up (sorted keys per partition), sorts RIDs by (bucket, page) and reads the pages 32 at a
time (`lookupByRIDs` after L1), then projects the 20 Recon fields in one header pass. No SQL on this path: a
`WHERE record_id IN :ids` with 200 keys is 200 independent lookups in `FetchFromIndexStep.processInCondition`
(`FetchFromIndexStep.java:581-638`) plus a `ResultInternal` per entry and a serial `lookupByRID` per hit
(`GetValueFromIndexEntryStep.java:990-997`), and the embedded API avoids the parse, the admission gate and the per-row
key re-check `holdsKey` runs on a committing node (`GetValueFromIndexEntryStep.java:523-561`).

**Dossier.** `buildDossier` prefetches up to 25 tokens x 300 records (`dossier.ts:54, 386`), then runs `linkClusters`
with fuzzy clustering, `geoAnalyze` and every detector over the WHOLE resolved footprint (`dossier.ts:393-410`) and caps
only the `records` output at 200 (`dossier.ts:48, 437`); `authorshipClusters` needs `styleVec` per row
(`analysis/style.ts:28-30`). The dossier is therefore `/dossier:footprint` (25 x P1 without the record step) plus a
hydrate of up to 7,500 `record_id`s with the analysis projection, P9c, on n4; the dossier caveats say "footprint capped
at 7,500 records" when a token's 300-cap truncates.

**Page touches.** Upstream RIDs: 200 x (recdir bucket page + recdir doc page + partition bucket page + Record page) =
800 reads = 25 rounds x 125 us = 3.1 ms, 11 MB / 5 GB/s = 2.2 ms; `record_id`s: 400 reads, 7.2 MB, 2 ms. CPU 200 x 3 us =
0.6 ms. 6 / 20 ms cold holds. 2,000 ids: 8,000 reads, 31 ms on latency and 22 ms on bandwidth (112 MB), about 35 ms I/O +
6 ms CPU = 41 ms p50 by upstream RID, 26 ms by `record_id` (72 MB); 7,500 `record_id`s: 15,000 reads, 59 ms on latency, 54 ms
on bandwidth (270 MB), about 90 ms with CPU. The stretch comes from M20 (16 KB Record pages halve the bandwidth term), L1
(no per-record lock, stats and permission overhead, and 32 reads in flight per call without 32 threads) and the hot-record
cache (3.2) answering the recently hydrated share.

### 2.10 P10 Board and audit

**Board.** Recon's board is never paged: `list()` filters the whole board and sorts it open-first, then priority P1..P4,
then absolute score desc, then updatedAt desc (`board.ts:244-250, 300-319`), and both the board route and the overview
consume the full list (`routes.ts:215`; `overview.ts:97` with `open: false`). `Target` in `identity`: HASH UNIQUE id,
composite (tenantId, closed) at 64 KB, `identifierKeys BY ITEM` HASH and board gram lists for `q`; `snapshot` and
`history` EXTERNAL so a list row is about 600 B (40 per 32 KB page). `GET /board`: one range on `(tenantId, closed)`
(hundreds of documents), sorted server-side by (closed, priority, scoreRaw desc, updatedAt desc), returned whole;
`read_your_writes` with the bookmark Recon received from its last PATCH. If paging is ever required, a `(tenantId,
closed, priority, scoreRaw, updatedAt)` composite serves the first page without a sort. PATCH: leader, `If-Match`
revision, `linearizable` read-before-write inside the transaction, audit append in the same transaction (5.2 of the
architecture). Cold 8 / 30 ms for a 500-target board (16 MB of Target pages at 32-way).

**Audit.** Recon's page returns `total` = the count of ALL entries matching the action, caseRef and subject filters,
independent of the `before` cursor, and a `verification` object on every page (`audit.ts:337-353`, `total++` at :348
before the cursor check at :349; `AuditPage` at `contracts.ts:94-98`). `AuditEntry` per database with `seq`, composites
(action, seq), (caseRef, seq), (subjectHmac, seq) at 64 KB; `ChainHead` carries per-database counters for (action),
(caseRef) and (subject), incremented in the same transaction as the append (the audit write path already exists), and
a `verification` checkpoint document written by the background verifier. `GET /audit?cursor=&action=` is one leaf + 100
entry pages (2 when unfiltered: consecutive seq) + the `ChainHead` page + the checkpoint page, `linearizable` (a leader
read-index round trip, 0.5 to 1 ms). Ad hoc filter combinations fall back to the capped cursor count (as P4c) until 4.3
M3. 10 / 40 ms cold.

### 2.11 Set-oriented retrieval (compare) and set-scoped analysis

Every modality returns and accepts a `RidBitmap` (F1): a 2,000-record selection is a few KB, AND/OR/ANDNOT run at
about 1 ms per 10M members, `selection.venn` computes 2^k regions with k operations each, and `selection.compare` with
`via` reaches shared identifiers through `getConnectedVertexRIDs` without loading neighbours
(`03-f1-selections-design.md`). Compare of 4 selections of 500 records in entities mode: 2,000 Record reads (arrays on
Record, so no edge walk: 63 rounds x 128 us = 8 ms, 64 MB = 12.8 ms cold) + bitmap algebra under 1 ms. Target 20 / 60 ms
cold. P11 (`/geo/*`, `/graph/*` with `{ids[]}`, `01-architecture.md` 1099) is the same hydrate followed by the analysis
CPU of section 1, on n4.

---

## 3. Caching layers

### 3.1 Page cache sizing and what it must hold

Heap 56 GB on n1 to n3: page cache 23 GB (`arcadedb.maxPageRAM=23552`, under the 80%-of-heap clamp at
`GlobalConfiguration.java:178-200`), vector residency 8, rebuild transient 7, query working memory 8, GC headroom 10
(`01-architecture.md` 1.4). 23 GB of 32 KB Record pages and 4 to 16 KB index pages is about 800K to 1M page slots;
`arcadedb.initialPageCacheSize=2000000` (`GlobalConfiguration.java:204`, default 65,535) so the `ConcurrentHashMap` never
resizes under load.

**The pinned set (index tops), today and at 3x.** With HASH on every exact key the tops are small: a HASH index keeps
page 0 and a directory of 4 B per entry, 2^globalDepth entries, about one to two per bucket page; a 16 KB bucket page
holds about 250 STRING keys at about 65% load (key, 10 B RID, 3 B tagged slot, `HashIndexBucket.java:156-177`) and a 4 KB
page about 125 LONG keys, so a 77M-key STRING index has about 310K bucket pages and a 2^19 x 4 B = 2 MB directory and a
77M-key LONG index 620K pages and a 2^20 x 4 B = 4 MB directory (appendix B). LSM composites keep page 0, one tail and one
root per series.

| Component | Keys or entries | Resident pages |
|---|---|---|
| `identity` identifier keys (HASH) | 177M shared (0.61 x 290M) | 710K bucket pages -> 2^20 x 4 B = 4 MB of directory |
| `recdir.upstreamRid` (HASH, LONG key, 4 KB pages) | 290M | 2.3M pages -> 2^22 x 4 B = 17 MB (7.2M pages, 34 MB at 3x) |
| per `rec_<p>` HASH: `record_id`, `upstreamRid` (77M each, 4 KB pages), `textHash` (77M), `imageHashes BY ITEM` (115M), local `key` (46M) (16 KB pages) | 392M | 4 + 4 + 2 + 2 + 1 = 14 MB |
| per `rec_<p>` LSM composites (27 at 64 KB, 2.4 and 2.6): root 48K leaves x 40 B = 1.9 MB + 23 series tails x 64 KB = 1.5 MB each | 27 x 77M | 27 x 3.4 MB = 92 MB |
| 4 partitions today | | 4 x 106 MB = 0.43 GB |
| **Today** | | **about 0.45 GB** |
| **3x** (12 partitions, 531M shared, 870M `recdir`) | | 17 + 34 + 12 x 106 MB = **1.3 GB** |

The composites' bloom pages (9.6 bits per entry, 92 MB per composite, 2.5 GB per partition) are not pinned day 1: a range
cursor never probes them; they become worth pinning per composite only when 4.3 M7 adds the prefix bloom for P6. The
pinned class (4.3 M1) covers `identity`, `recdir`, the live and the previous partition unconditionally and sealed
partitions' tops at high priority, evicted before Record pages only when the cache is over 95%.

**What the rest holds.** 23 - 0.45 = 22.5 GB = 740K pages of 32 KB; at 40 records per Record page that is 30M records'
worth of pages, 10% of today's corpus. Recon's access is recency-skewed (the generator models it as `NOW - SPAN x u^2`):
the newest 30 days are 200K x 30 = 6M records = 150K pages = 4.8 GB, so the newest month stays fully resident with 17 GB
left for older leaves and records that intake and dossiers touch. At 3x (600K/day) the newest 30 days are 14.4 GB; the
cache still holds them. Eviction keeps them: `EVICTION_ORDER` sorts bulk-scan pages first, then by last access
(`PageManager.java:2695-2710`), so a 50% run evicts the least recently touched half, not the hot month.

**Eviction behaviour today and the day-1 setting.** `checkForPageDisposal` (`PageManager.java:2677-2689`) runs on every
page get, miss and put, gated to once per 100 ms by `lastCheckForRAM`; when `totalReadCacheRAM >= maxRAM` it calls
`evictOldestPages(totalRAM x freePageRAM / 100)` (`PageManager.java:2714-2759`), which is `synchronized` on the
process-wide `PageManager`, copies the WHOLE cache into an `ArrayList<EvictionCandidate>` (800K entries x 56 B = 45 MB of
garbage) and sorts it (800K x log2(800K) = 16M comparisons, about 0.2 to 0.5 s) however little it then frees; it never
re-checks `totalReadCacheRAM` against `maxRAM` after taking the monitor, and `lastCheckForRAM` is stamped only at the end
(`PageManager.java:2758`), so every reader that arrived during the run performs its own full copy-and-sort afterwards.
The run's frequency is set by the refill rate, not by the sort: period = maxRAM x freePageRAM / miss bandwidth. At
1 GB/s of misses (31K Record pages/s, the 2,000 request/s mix at a 20% cold share) the stock `freePageRAM=50` frees
11.5 GB and recurs every 11.5 s (a 0.2 to 0.5 s stall is 2 to 4% of wall time); `freePageRAM=5` would free 1.15 GB and
recur every 1.15 s, five to ten times the stall for the same sort. Lowering `freePageRAM` therefore makes the p99 cliff
MORE frequent, so the day-1 setting keeps the default 50 (4.1 C1), 4.2 S16 (re-check under the monitor, stamp on entry,
0.5 ew) ships as the first code change so arrivals during a run skip it, and M1 (sampled or CLOCK eviction on an
evictor thread) is the first medium item. Until S16 and M1 the p99 column of section 1 holds only between runs (risk
7.2).

**OS page cache, 9 GB on n1 to n3, 6 GB on n4.** 590K pages of 16 KB at R_os = 6 us (`01-architecture.md` 1.4 round 4 moved 3 GB of the former 12 to the encoder sidecar; appendix B): a hit there is 16x cheaper than the device.
It is an L2 for cold leaves, EXTERNAL text pages, `.vecgraph` files (read by jvector's own I/O, not the page cache) and
the Ratis log. Every GB moved from the ROC or direct-memory budgets into the OS cache is 65K more pages answered in 6 us
instead of 100 us; the heap cache is not grown past 23 GB because ZGC needs 15 to 20% of the heap free and the page cache
is what fills it. `read_ahead_kb=32` on the data volume (5.3) so a random 32 KB pread does not pull 128 KB. L4 keeps the
OS cache for EXTERNAL buckets because it does not use O_DIRECT (4.4).

### 3.2 Hot identifier and record caches in the plugin

Both live in the plugin (isolated classloader) inside the 8 GB query working-memory slice, with Caffeine (Apache 2.0,
no transitive dependencies; added to `ATTRIBUTIONS.md`; it is not on the engine classpath today, `grep caffeine
*/pom.xml` finds nothing) for the on-heap maps and direct `ByteBuffer` slabs for the record bytes so the GC never scans
them.

| Cache | Key | Value | Entries | Bytes | Why |
|---|---|---|---|---|---|
| Identifier footprint | canonical key (24 B) | registry fields (recordCount, periodMask, hub, componentId: 24 B) + the newest 300 Record RIDs as `long[]` packed (bucketId << 48 \| position), 2.4 KB max, median 80 B | 2M (the Zipf head: the top 2M of 177M shared identifiers receive most intake traffic) | 2M x (24 + 24 + 300 avg x 8 x 0.1 fill + 64 overhead) = about 2M x 350 B = 0.7 GB | an intake on a cached identifier skips the registry probe, the partition key lookups and the edge-chunk reads: 3 to 5 page reads and about 50 us of CPU per token; it also absorbs the 12-partition HASH miss probe of a repeated singleton token |
| Hot record projection | `record_id` (16 B) | the 20-field Recon projection already serialized as the NDJSON row (about 300 B) | 3M (the newest 15 days at today's rate, 5 days at 3x) | 3M x (16 + 300 + 32 slab header) = 1.0 GB, in 64 MB direct slabs with an open-addressing `long[]` index | a hydrate or footprint row served from here costs about 1 us and no page read or deserialization; watch scans re-hydrate the same footprints every `RECON_SCAN_INTERVAL` |
| Place | placeId (8 B) | city, state, country, lat, lon (about 80 B) | 8M (all Places) | 8M x 120 B = 0.96 GB (or 2M hottest, 0.24 GB) | records without lat/lon resolve through Place; the architecture already assumes an in-heap Place cache |
| Query plan and cardinality | statement text + partition | plan, forced index, per-value cardinality | 2,000 statements (`arcadedb.sqlStatementCache=2000`, `GlobalConfiguration.java:791`; `opencypher.statementCache` and `planCache` 2,000, `GlobalConfiguration.java:830-834`) | under 100 MB | a parse + plan is 50 to 500 us; Recon's query family varies predicates, not shapes |

Total about 2.5 GB of the 8 GB slice (1.7 GB with the smaller Place cache). Invalidation: the identifier cache is
keyed on the key and carries the `Outbox` seq it was built at; an Outbox event whose `subjectKeys` contain the key
evicts it (the plugin consumes its own node's applied Outbox, 3.4). The record cache is append-mostly: a Record changes
only through the deletion saga and `tsRemoved`, both Outbox events on the record's key. Hit rates to verify in P0a:
intake tokens repeat across analysts and watches (expected over 60% on the identifier cache); dossier and watch
re-hydration repeat within hours (expected over 50% on the record cache).

### 3.3 Result caches

Keyed by the canonical request hash (tenant, route, normalized parameters) and the set of partitions read with each
partition's version. A sealed partition's version never changes, so a cached structured-search page over sealed
partitions lives for its TTL (10 minutes); the live partition's version is bumped by the Outbox consumer on every
applied batch (every 30 s or 500 records, 6.2 of the architecture), so entries that read it live at most one batch.
Candidates: structured-search first pages and counts (the same dashboard queries are repeated by many analysts), `q`
totals for the `bulk` tool, feed summaries (already materialized, 2.5), group-by facets, timeline buckets over sealed
partitions (immutable), board lists keyed with the OCC revision. Budget 512 MB in the working-memory slice; everything
else is served from the page cache. Not cached: anything audited per request (feed.view is audited but the summary
document is data, not a response), anything under `linearizable`, anything with `partial: true`.

### 3.4 Invalidation through the change feed

Every governed write adds `OutboxEvent(seq, db, type, subjectKeys, ctxHash, auditSeq)` in the same transaction (7.3 of
the architecture), replicated by Raft. Each node's plugin tails its own local Outbox (a `seq` range scan every second,
one leaf when idle) and: evicts identifier-cache entries by `subjectKeys`; evicts record-cache entries by `record_id`;
bumps the live partition's version; triggers the `FeedWindowSummary` refresh when a located record arrived. Because the
feed is replicated data, a follower's caches are invalidated by the same events the leader produced, within the
follower's apply lag (bounded by `SERVER_READINESS_HA_MAX_LAG`, past which the node stops advertising ready).

### 3.5 Warmup after restart or schema reload

A node that opens cold serves every first query at R_dev per page, and the thundering herd on page 0, directory and root
pages (page-cache audit hotspot 4: N threads missing one page produce N reads and N allocations,
`PageManager.java:2651-2675`) makes the first minute's p99 tens of ms. The plugin's warmup, run before the node
advertises ready (`SERVER_READINESS_REQUIRES_HA=true` already gates readiness on HA state):
1. Index tops: touch page 0 and the directory pages of every HASH index, and page 0, every series tail and every root
   page of every LSM index on `identity`, `recdir` and the newest two partitions, in file order on 4 threads: about
   0.25 GB at about 1 GB/s sequential, under 1 s.
2. The newest 7 days of Record pages: 1.4M records / 40 per page = 35K pages = 1.1 GB in bucket order, about 1 s.
3. Hot key replay: the plugin persists its identifier cache's top 100K keys by hit count every 10 minutes to the local
   disk (keys only, no data); on start it replays them as intakes without the record step: 100K x 2 reads / 32-way x
   120 us = 0.75 s, which also fills the bucket and chunk pages intake touches.
4. The vector search caches: `IndexWarmUpScheduler` already warms vector indexes after a schema reload
   (`arcadedb.vectorIndex.warmUpAfterSchemaReloadDelayMs`); the plugin waits for it.
Total under 10 s before ready. A schema reload on a running node (replicated DDL) drops nothing from the page cache,
so only step 4 applies.

### 3.6 Bloom filters

The engine keeps a blocked bloom per compacted series (`LSMTreeIndexBloomFilter.java:262-283`, 8 KB blocks, rate 0.01
by default, `GlobalConfiguration.java:1309-1313`), consulted AFTER reading the series' tail page for its fingerprint
(`LSMTreeIndexCompacted.java:703-722`): a negative probe costs two cached touches per series (tail, bloom block) and no
device read when the tops are resident, and a positive one adds the root and the leaf. The filter is worth most when
there are many series to skip: a 23-series composite lookup is 23 x 2 touches + 2 instead of 23 x (root + leaf), which
is why 2.0 keeps the default rate and why the statement "the bloom's value depends on the series count staying at 1" in
revision 1 was inverted. What the bloom does not save is the tail read, so a point lookup on a 23-series index is still
49 cached touches; 4.3 M2's in-memory series directory (root page number, leaf count, fingerprint, key range per series,
rebuilt when `setCompactedTotalPages` publishes) is what removes it, and it is the first medium item after M1 for that
reason. With HASH on every exact key (2.0) the blooms matter on the hot path only for the prefix seeks of P6 and P4s
(4.3 M7) and for any LSM point index kept at 256 KB (S = 2). The plugin adds its own bloom only for the `recdir` miss
path (an upstream RID Recon asks for that SPORK never imported, which otherwise costs a bucket-page read per miss): 290M
keys x 9.6 bits = 348 MB, rebuilt from the index at seal. 4.3 M2 also adds per-mutable-page fences and a small bloom per
mutable page so the live partition's range indexes stop paying one binary search per mutable page at cursor open.

---

## 4. Engine optimizations ranked by (latency gain for Recon) / effort

Score = the ms the item removes from the cold p50 of the patterns it touches, weighted by the pattern shares of 0.1,
divided by effort in engineer-weeks. Gains are estimates from the audits; P0a's JFR and page-stat captures replace them.
Items marked "done in worktree" are implemented and re-measured; their cost is a merge.

### 4.1 Config-only (apply day 1, zero code)

| ID | Item | Where | Patterns | Gain | Notes |
|---|---|---|---|---|---|
| C1 | `arcadedb.freePageRAM` stays at the default 50; do NOT lower it | `GlobalConfiguration.java:270-271`; `PageManager.java:2677-2689, 2714-2759` | all, p99 | none from configuration: every eviction run copies and sorts the whole cache whatever it frees, and the run recurs every maxRAM x freePageRAM / miss bandwidth, so a lower value makes the stall more frequent, not rarer (3.1). The stall itself is removed by S16 (cheap) and M1 | SCOPE.DATABASE but read JVM-wide by `PageManager.configure()`; the 6.4 eviction gate is derived from the refill rate |
| C2 | Follower routing: reads through `/api/v1/query` or the plugin's embedded `query`, never `/command`; `read_your_writes` with a bookmark only after the caller's own write; `linearizable` only where 2.0 says | `DatabaseAbstractHandler.java:231-256, 403-417`; `PostQueryHandler.java:55-63`; `RaftReplicatedDatabase.java:1588-1611, 2607-2622`; `RaftHAServer.java:6936-6938` | all | avoids the auto-commit wrapper (5 to 15 us), `analyze()` + `isLeader()` per request and the leader ReadIndex RPC of `linearizable` (0.5 to 1 ms) where it is not needed; keeps parallel scans enabled. The server default `read_your_writes` is already free for requests without a bookmark, so `arcadedb.ha.readConsistency` stays at its default and `eventual` would save nothing per request | the one hard rule for Recon's data layer |
| C3 | Token auth, never Basic | `AbstractServerHttpHandler.java:579-590, 785-820, 828-855`; `GlobalConfiguration.java:2305-2310` | all HTTP | 30 to 60 ms of PBKDF2 per request avoided when the 64-entry salt cache misses; `securitySaltCacheSize=1024` if Basic must stay | |
| C4 | `serializer: record`, NDJSON over 100 rows, explicit projections, `LIMIT $limit`, `arcadedb.queryLiteralParameterization=true` (already the default), statement and plan caches 2,000, `httpQueryMaxResultRows=2000` | `JsonSerializer.java:541-548`; `AbstractQueryHandler.java:603-614`; `PostCommandHandler.java:193, 285-300`; `GlobalConfiguration.java:791-795, 830-834, 2138` | P1 to P4, P9 | no edge counting per vertex (100 us to 10 ms per hub), first byte after the first row, no parse + plan (50 to 500 us) per distinct limit | |
| C5 | Compaction policy (`01-architecture.md` 0.4, E0; appendix B): per database `indexCompactionFullSeriesThreshold=0` and `indexCompactionFullDeadEntriesRatio=0` (no automatic full compaction), `indexCompactionMinPagesSchedule=64` on `identity` and the current period; full compactions as leased plugin jobs: every LSM index of a partition at seal and after admission, weekly for the current FULL_TEXT and the `identity` LSM indexes; `indexBloomFilterRate` left at its default 0.01. Revision 2's schedule 4 and `fullSeriesThreshold=3` on `identity` are withdrawn (2.0) | `GlobalConfiguration.java:1283-1292, 1309-1313`; `LSMTreeIndexMutable.java:159-160, 202-219`; `LSMTreeIndexCompactedStreamWriter.java:172-175` | P4 to P6 cursors, P4s | a cursor open on a sealed partition seeks 23 resident series and no mutable page; on the live partition up to 64 mutable pages and 56 series at week's end (2.0), which M2 and M6 remove; no full-rewrite loop through the Raft log (risk 7.4). Point lookups are HASH (C8) and have no compaction state | the schedule and the lease are the arch's E0 and 0.4 |
| C6 | Read-node thread budget: `httpWorkerThreads=96`, `httpIoThreads=8`, `queryMaxConcurrent=48`, `queryQueueMaxSize=64`, `queryQueueTimeout=2000`, `queryParallelismPoolThreads=12`, `parallelScanProducerPoolThreads=8`, `queryParallelScanPagesPerUnit=256`, `queryScanReadAheadMaxRAM=512`, `command.timeout=30000`, `httpTerminateOnClientDisconnect=false`, `polyglotEngineEnabled=false`; `asyncWorkerThreads=2` on the leader only (the async pool is created lazily by `LocalDatabase.async()`, so a read-only follower never has one); n4 keeps the parallel defaults | `GlobalConfiguration.java:341-360, 910, 974, 1026-1062, 1113-1146, 2015-2024, 2066`; `LocalDatabase.java:282, 673-678`; `DatabaseAsyncExecutorImpl.java:1065`; `HttpServer.java:639-640` | all, p99 | 500 blocking workers on 32 cores is 15:1 oversubscription; a bounded run queue keeps p99 flat under burst; one analyst's unindexed scan cannot take every core and the NVMe queue | do not use `profile=high-performance` (sets vector caches to 50% of heap) |
| C7 | Graph: `graph.ghostEdgeProbe=false` on sealed partitions after `CHECK DATABASE ... FIX`; `graph.supernodeThreshold=1000000` before load; `graph.edgeListInitialChunkSize` stays 64 (2 KB on shared identifier vertices needs 4.2 S18; appendix B) | `GlobalConfiguration.java:708-718`; `GraphTraverser.java:148-149, 180, 281`; `StripedEdgeList.java:630-660`; `EdgeLinkedList.java:381-384` | P1 to P3 | 25 to 33% off variable-length hops (the config's own measurement, #10658); hub reads 16 head-chunk loads to 1; the 500-record identifier's 7 chunk hops to 1 or 2 wait for S18 | |
| C8 | HASH on every exact key at the engine default page sizes (2.0): `identity` keys, `recdir.upstreamRid`, per-partition `record_id`, `upstreamRid`, `textHash`, `imageHashes BY ITEM`, local `key`, `TextBody.textHash`; adopted by `01-architecture.md` 2.1, 0.4 and D39 in round 6 | `HashIndexBucket.java:410-452`; `HashIndex.java:252-258`; `LSMTreeIndexCompactedStreamWriter.java:172-175` | P1 to P4, P7, P9 | C_hash = 3 cached touches and 1 cold read against 1 + 2S + 2 touches for an LSM index of S series (about 360 series for 77M keys at 16 KB, 23 at 64 KB), flat p99 under ingest and no compaction state; the warm saving over a 2-series 256 KB LSM index is a few us, the cold saving is one 16 KB read against a 256 KB leaf | P0a verifies HA behaviour and `BY ITEM`; the per-index fair lock is S17 |
| C9 | Page sizes and buckets: one bucket per type (StyleVec excepted); Record, TextBody, Target, AuditEntry buckets 32 KB; `recdir` and identifier vertices 16 KB; HASH indexes at their engine defaults (4 KB LONG and UUID keys, 16 KB STRING and BINARY); LSM range composites, FULL_TEXT, GEOSPATIAL 64 KB; any LSM point index 256 KB | `GlobalConfiguration.java:277-290, 1106-1120`; `TypeBuilder.java:498, 542`; `IndexBuilder.java:317`; `TypeIndex.java:130-150, 238-256`; `ParallelTypeScan.java:174-189`; `LocalBucket.java:131, 662-664` | all | sub-indexes probed per lookup and cursors per seek = 1 (16 at 16 buckets); a 2,000-id hydrate moves 112 MB, not 1.3 GB on stock pages (80 MB after M20); a range leaf moves 64 KB, not 256 KB; parallel scans split the single bucket by page ranges | fixed at creation; buckets through SQL `CREATE TYPE ... PAGESIZE`, FULL_TEXT and GEOSPATIAL through `IndexBuilder.withPageSize` (2.0) |
| C10 | JVM and OS flags of section 5 | `package/src/main/docker/Dockerfile:20, 29-31, 37`; `package/src/main/scripts/server.sh:90-101` | all, p99 | no warm-up page faults, no ZGC uncommit refaults, 2 MB pages, bounded allocation stalls | |
| C11 | Vector: `vectorIndex.searchCacheSize=500000`, `VECTOR_INDEX_MAX_CONCURRENT_REBUILDS=1` (already the default), INT8 inline, a lower delta cap on the ingest-target bucket; sparse `rescoreOversample 0` if sparse is ever enabled. `searcherPoolSize` left at its default 0 (auto-sized to 2x the cores) | `GlobalConfiguration.java:1395-1409, 1439-1447`; `LSMVectorIndex.java:5713-5745, 14216-14240`; `LSMSparseVectorIndexMetadata.java:55` | P8a | keeps the hot bucket's delta scan (up to four fifths of a query at the cap, the file's own note at `LSMVectorIndex.java:14233`) short; the P8 gain itself comes from the parallel per-bucket search (M13), not from this row | |
| C12 | `arcadedb.queryIndexMaxSelectivity=0` on `rec_<p>` (never abandon an index search for a scan); n4 keeps the default 0.6 | `GlobalConfiguration.java:1086-1098`; `SelectExecutionPlanner.java:5427-5445`; `ParallelTypeScan.java:246-262` | P4c, group-by | the setting is the share of a type's records ABOVE which an index search gives way to a full scan, divided by (1 + W) / 2 for W parallel-scan workers (W = 8 producers here: 0.6 -> 13%, 0.9 -> 20%, 0.2 -> 4.4%), and it applies only to aggregations and to an ORDER BY the index does not serve, never to the P4 row pages; a count over a 77M partition that matched over 13% would become a 0.5 to 5 s scan, which the 10,000 cap exists to avoid | |

### 4.2 Small (1 to 2 weeks each), ranked

| Rank | ID | Item | Where | Patterns | Gain (cold p50) | Effort |
|---|---|---|---|---|---|---|
| 1 | S16 | Eviction run hygiene: stamp `lastCheckForRAM` on entry to `evictOldestPages`, re-check `totalReadCacheRAM < maxRAM` after taking the monitor and return if another run already freed, free in a bounded sample instead of after a full sort when the overshoot is under 2% | `PageManager.java:2677-2689, 2714-2758` | all, p99 | every reader that arrives during a run stops repeating the whole-cache copy-and-sort; with the default `freePageRAM=50` the stall is one 0.2 to 0.5 s run per refill period instead of one per waiting thread | 0.5 ew (day-1 patch) |
| 2 | S1 | Per-page read future: after acquiring the slot in `concurrentPageAccess`, re-check `readCache`; better, a `ConcurrentHashMap<PageId, CompletableFuture<CachedPage>>` so N threads missing one page share one read and park instead of `Thread.yield` | `PageManager.java:2594-2634, 2651-2675` | all, p99 | 32 threads missing one hot page: 32 reads and 32 x 32 KB allocations become 1; p99 after restart or eviction from several ms to one device read | 1 ew |
| 3 | S17 | Index locks: `HashIndex` and `LSMTreeIndex` take `RWLockContext(defaultStripeCount())` non-fair (or a `StampedLock` optimistic read on the lookup path) instead of the single-stripe fair `ReentrantReadWriteLock`; `HashIndexBucket` reads through `getPageForAbsoluteReads` instead of `tx.getPage` so a touch is a shared view, not a page-view allocation | `HashIndex.java:55, 240, 246`; `LSMTreeIndex.java:84, 594, 707, 726`; `RWLockContext.java:48-50, 60`; `HashIndexBucket.java:376, 472` | P1, P3, P7, P9 hydrate paths | 32 hydrate threads on one HASH index share one fair lock word today: the lock, not the page cache, bounds the warm lookup rate; the database lock is already striped (`LocalDatabase.java:355`) so this is the remaining convoy. Measured by the 6.3 JMH case before and after | 1 ew |
| 4 | S2 | Per-miss CPU tax: one `checkForPageDisposal` per `getCachedPage` (drop the duplicate in `loadPage`) on a `CoarseClock` instead of two `System.currentTimeMillis` reads; `DatabaseStats.readRecord` sampled; read through a pooled per-thread direct `ByteBuffer` and copy once. `totalPagesRead` / `totalPagesReadSize` stay `AtomicLong`: they are bumped only on the miss path under a 100 us I/O and are exported as counters | `PageManager.java:2609, 2627-2629, 2677-2679, 2792, 2815`; `LocalDatabase.java:1234`; `DatabaseStats.java:37-45`; `PaginatedComponentFile.java:258, 568-593`; `CachedPage.java:75` | all | 40 to 60 ns of clock reads per page touch (C_pg) and 3 to 5 us per 32 KB miss (25 to 40 ms of CPU per 8,000-miss hydrate) | 1 ew |
| 5 | S4 | One header pass per projected row: wrap element rows in `PropertyCachingResult` (or call `locateProperties` once) in `ProjectionCalculationStep` and the Cypher projection operator, then in the serializers for element rows; `toMap()` once in Bolt `toProperties`, gRPC `convertToGrpcRecord`, Postgres `columnValue`. The engine primitive exists (`locateProperties` + `getPropertyAt`, #9496) but its only consumer is `AggregateRowEvaluator` | `PropertyCachingResult.java`; `AggregateRowEvaluator.java:213`; `ProjectionCalculationStep.java`; `ResultInternal.java:240-248`; `ImmutableDocument.java:82-100, 141-157`; `BinarySerializer.java:610-646`; `JsonSerializer.java:149-235`; `BoltStructureMapper.java:274-282` | P1 to P5, P9 | a projected 20-field row from about 6 us of header walks to about 1 us: 2.5 ms per 500-record response, 37 ms per 7,500-record dossier hydrate; section 2 forbids element rows, so a serializer-only change would not reach Recon's rows | 1 ew |
| 6 | S3 | `getRecordInternal` reads through the shared immutable view under READ_COMMITTED (as the index path does, #10654) and copies straight into a right-sized `byte[]` | `LocalBucket.java:2612-2683`; `CachedPage.java:92-102`; `LocalDatabase.java:1266` | all | about 70% fewer allocations per record read (10 objects, 500 B), 0.2 to 0.3 us each; lower ZGC pressure on the 56 GB heap | 1 ew |
| 7 | S5 | Lazy record load on index hits: `NodeIndexSeek` `asVertex(false)`, `GetValueFromIndexEntryStep` `lookupByRID(rid, false)`, projection drives the load | `NodeIndexSeek.java:332`; `GetValueFromIndexEntryStep.java:997`; `FetchFromIndexStep.java:188-202` | P1 to P4, P7, P8 | 2 to 3x fewer bytes copied on top-K and seek paths; 20 to 40% CPU on index-driven queries that project a few fields | 1 ew |
| 8 | S6 | Allocation-free adjacency entries: `MutableEdgeSegment.nextEntry(pos, long[] out)`, skip the edge RID in `VertexIterator`, plain `int` position | `MutableEdgeSegment.java:608-614`; `VertexIterator.java:65-67`; `ResettableIteratorBase.java:38`; `BinarySerializer.java:1133-1136, 1214-1216` | P1 to P3 | about 40% of the per-entry CPU of a neighbour walk (150 to 300 ns and 2 allocations per entry; 500 entries = 100 to 150 us) | 1 ew |
| 9 | S7 | Edge chunks read as page views, previous pointer decoded to primitives, per-statement (headRID to segments) cache | `EdgeLinkedList.java:381-384`; `MutableEdgeSegment.java:557-574`; `ResettableIteratorBase.java:350-363` | P1 to P3 | 3 to 8 us and up to 8 KB of copy per chunk hop | 1 ew |
| 10 | S8 | `QueryAdmissionGate` lock-free fast path (`Semaphore.tryAcquire` when nobody queues and heap is under the watermark) | `QueryAdmissionGate.java:82-130` | all, throughput | removes a JVM-wide `ReentrantLock` taken twice per remote request | 0.5 ew |
| 11 | S9 | Read-only requests skip the auto-commit wrapper and the two-pass body parse; cache per-begin config values | `DatabaseAbstractHandler.java:125-300, 403-417`; `PostCommandHandler.java:214`; `TransactionContext.begin` | all, throughput | 5 to 15 us and about 40 allocations per request; 10 to 20% more read throughput per core at saturation | 1 ew |
| 12 | S14 | Permission verdict cached per (statement, fileId); `stats.readRecord` sampled | `LocalDatabase.java:1140-1152, 1239`; `LocalBucket.java:704, 2559-2571` | all | 0.2 to 0.4 us per record of fixed overhead that does no I/O | 0.5 ew |
| 13 | S10 | Vector search: `LongAdder` metrics, `StampedLock` optimistic read over the (graph, ordinal map, locations) triple, primitive allow-list `isLocationIn(vectorId, RidHashSet)` | `LSMVectorIndex.java:9412-9413, 9487-9489`; `LSMVectorIndexMetrics.java:36`; `LiveVectorBitsFilter.java:232` | P8a, p99 | removes the only shared-write cache lines on the search path; set-scoped kNN loses one allocation and one boxed hash per visited node (20 to 30% of a filtered walk) | 1 ew |
| 14 | S12 | Geo: coalesce adjacent frontier cells into one range; `RidHashSet` for `seen`; cache the parsed search shape per statement | `GeoIndexCursor.java:67, 111-144`; `LSMTreeGeoIndex.java:299-319`; `SQLFunctionGeoPredicate.java:70-77, 268` | P6 | 3 to 5x on cursor setup for wide radii; no WKT parse per row | 1 ew |
| 15 | S15 | O(1) vertex degree kept in the head edge segment | `EdgeLinkedList.java:416-435`; `ImmutableVertex.java:186` | degree detectors, `json` serializer | `countEdges` from a chain walk (100 us to 10 ms on hubs) to a field read | 1.5 ew |
| 16 | S11 | Sparse IDF from `PaginatedDimMetadata.df()` instead of `countDim` posting walks | `LSMSparseVectorIndex.java:974-1016`; `PaginatedSparseVectorEngine.java:853-870` | sparse (deferred) | removes an O(total postings of the query dims) pre-pass from every IDF-modifier query | 0.5 ew |
| 17 | S13 | Bolt: one `PackStreamWriter` per PULL; gRPC: drop `logical_type` on scalars | `BoltNetworkExecutor.java:2077-2096`; `ArcadeDbGrpcService.java` toGrpcValue | L5 | about 1 KB of garbage per Bolt record | 0.5 ew |
| 18 | S18 | Per-type edge-chunk sizes: `edgeListInitialChunkSize` and a second-chunk size in the vertex type metadata, read by `LocalDatabase.getNewEdgeListSize` in place of the JVM-wide static, so shared identifier vertices get a 2 KB chunk and singletons keep 64 B (2.1, C7; `01-architecture.md` E24; appendix B) | `LocalDatabase.java:394-405`; `GraphEngine.java:1117, 1176, 1203`; `EdgeLinkedList.java:760, 796`; `StripedEdgeList.java:340, 357`; `LocalVertexType` | P1 to P3 | a 500-record identifier read in 1 or 2 chunk hops instead of 7 (3 to 8 us and up to 8 KB of copy each, S7): 20 to 40 us per footprint token, 0.5 to 1 ms per 25-token dossier footprint; about 18 GB per partition if applied to the local vertices with 2 or more records, 0 if `identity` only | 0.5 to 1 ew |

### 4.3 Medium (3 to 6 weeks each), ranked

| Rank | ID | Item | Where | Patterns | Gain | Effort |
|---|---|---|---|---|---|---|
| 1 | M1 | Eviction without sort-the-world: CLOCK ring (`CachedPage[]` in insertion order, a reference bit set in `updateLastAccesses`, a sweep hand) or sampled LRU (32 random entries, evict the oldest, repeat); on a dedicated evictor thread woken at 95% with readers parking only above 110%; a `priority` byte on `CachedPage` set by the loader (HASH page 0 and directory pages, LSM page 0, series tails and root pages, Dictionary pages pinned; bucket pages normal; bulk-scan low) with pinned RAM bounded at 10% of `maxRAM`; drop the larger-first tie-break for pinned pages | `PageManager.java:2677-2689, 2695-2711, 2714-2759`; `CachedPage.java:169-175`; `LSMTreeIndexBloomFilter.java:262-283`; `LSMTreeIndexCompacted.java:655, 715` | all, p99 | the single largest p99 artifact: a 0.2 to 0.5 s global stall every refill period (11.5 s at 1 GB/s of misses, 3.1), plus the refill storm; afterwards p99 of identifier lookups and hydrates is the NVMe bound; index tops stop being evicted by hydrate streams | 4 ew |
| 2 | M20 | Bucket format v2: a 256-slot table (1,026 B header, as `EXTERNAL_BUCKET_VERSION` already has) selectable for primary buckets, so 16 KB Record pages hold 25 rows instead of 13 and a cold record read moves 16 KB instead of 32 KB; a `CHECK DATABASE`-style migration for existing buckets is out of scope (new partitions only) | `LocalBucket.java:90-97, 131, 655-665, 2617-2618`; `LocalSchema.java:1918-1926`; `TypeBuilder.java:542` | P1 to P5, P9, P11 | halves every record bandwidth term of section 1: P9b from 32 to 16 ms of transfer, P9c from 72 to 36 ms, P2 from 3.2 to 1.6 ms; a 77M partition's Record pages from 62 GB to 49 GB | 3 ew (format version; group M because it is an on-disk format change) |
| 3 | M2 | LSM lookup and cursor-open walk: an in-memory series directory per compacted index (root page number, leaf count, fingerprint, key range) rebuilt when `setCompactedTotalPages` publishes, so neither a point lookup nor a cursor open reads a tail page per series and both probe every series' bloom or key range first; in-memory (min, max) fence per mutable page plus a small per-page bloom built at `createNewPage` and extended on `internalPut`; `PageIdCache` in the compacted path; a shared not-found `LookupResult`; a `TYPE_LONG` fast path in `compareKey` without boxing | `LSMTreeIndexMutable.java:380, 410-434`; `LSMTreeIndexCompacted.java:438-520, 653-824, 1207`; `LSMTreeIndexAbstract.java:744-790` | P4 to P6 cursors, P4s, any LSM point index | a lookup on a 23-series index from 1 + 2 x 23 + 2 = 49 cached touches to 23 in-memory probes + root + leaf; a cursor open stops reading 23 tail pages; p99 stops growing with unflushed pages during ingest bursts | 4 ew |
| 4 | M4 | Projection pushdown and EXTERNAL hygiene: `Projection.calculateSingle` through `locateProperties` beyond S4's wrapper (one header pass for N fields, types cached per `DocumentType`); `inlineExternalProperties=false` so a whole-record `RETURN` leaves EXTERNAL values out unless named | `PropertyCachingResult.java:150-200`; `ImmutableDocument.java:142-158`; `BinarySerializer.java:435-470, 610-646`; `JsonSerializer.java:149-256, 575-585`; `FinalProjectionStep.java:132` | P1 to P5, P9 | per-record decode to about 1 us on every path S4 does not cover (Cypher `RETURN n`, Gremlin), and no accidental EXTERNAL reads | 3 ew |
| 5 | M5 | Byte-direct response writer: a `JsonWriter` (or hand-rolled) over a pooled buffer or the exchange stream, values emitted by `BinaryTypes` byte without boxing, declared types resolved once per type per response, `Content-Length` set; NDJSON uses the same writer per row | `JsonSerializer.java:149-256, 797-840, 940-1000`; `JSONObject.java:138-200, 543-544`; `ExecutionResponse.java:163`; `NdJsonResultStream` | P1 to P5, P9 | the response is built three times today (Gson tree, String, UTF-8 bytes): 2 to 4 ms and 3 to 6 MB of garbage per 500-record response; about 70% less allocation, flatter p99 under 150 users | 3 ew |
| 6 | M3 | Covering-index projection: `ProjectFromIndexEntryStep` when every projected expression references only the index key or `@rid`; `count(*)` and `count(DISTINCT key)` over an indexed equality served from the index; the same primitive as an engine API (keys to RID lists, RIDs to key lists) for the set-compare layer | `SelectExecutionPlanner.java:636-648, 5208-5273`; `GetValueFromIndexEntryStep.java:523-542, 878-908, 986-997`; `FetchFromIndexStep.java:188-202` | P4 count, P10b totals, P3, compare | identifier-only queries (footprint overlap, pivot, compare entities) drop the per-hit record load: 2 to 5x lower latency and no bucket pages pulled into the cache for queries that read no property; SQL counts stop costing a record per entry | 4 ew |
| 7 | M6 | Range cursor: one shared page view per cursor (`getPageForAbsoluteReads` handed to `newPageIterator`), a binary heap of cursors when more than 4, run detection on raw key bytes, RIDs as packed longs until `next()`, DESC runs through `seekRunBoundary` | `LSMTreeIndexCursor.java:140-230, 467-491, 541-581`; `LSMTreeIndexMutable.java:291-296`; `LSMTreeIndexUnderlyingPageCursor.java:60-161`; `LSMTreeIndexAbstract.java:861-899` | P4, P4s, P5, P6 | cursor open from about 30 us to 5 us (200 seeks x 1 bucket: 6 ms to 1 ms on P6 and P4s); 2 to 4x fewer allocations per emitted row on time-ordered scans; O(log C) merge instead of O(C) | 3 ew |
| 8 | M8 | Primitive RID lists from `get`: `RidList` of packed longs returned by the HASH, mutable and compacted lookups, `IndexCursorEntry`/`RID` built only at the `IndexCursor` boundary; keys converted once; lazy tombstone sets; a direct entry for full-text and geo | `HashIndex.java:252-258`; `LSMTreeIndexMutable.java:302-315`; `LSMTreeIndexAbstract.java:647-653, 1034-1089`; `TypeIndex.java:236-257` | all index paths, P7 | 50 to 80% less garbage per lookup (6 to 15 objects for a single hit; 120 B per posting for a hub key); the allocation floor under P7 and L3 | 3 ew |
| 9 | M9 | Full-text stage 1: fold BM25 scoring into the matching walk (one posting pass per term, partial scores in a primitive `double[T]`, a `RidToIntMap` beside `RidHashSet`), delete the second `underlyingIndex.get` per token; the candidate-set path (P7) returns hits in RID order without scoring | `FullTextQueryExecutor.java:604-629`; `LSMTreeFullTextIndex.java:538-573, 585-598`; `FullTextSearch.java:214-258` | P7 | about 2x less I/O and 3 to 5x fewer allocations per posting: a 1M-posting term from about 200 ms + GC to 60 to 80 ms; the Q2 interim before L3 | 3 ew |
| 10 | M10 | Geo index self-answering for points: lat/lon stored beside the RID in the posting (same mechanism as `storeTermFrequency`, `LSMTreeIndexAbstract.java:852-898`), the cursor applies the haversine and marks itself exact, so rejected candidates cost a 16 B decode, not a page read and a WKT parse; `distErrPct` exposed per query | `LSMTreeGeoIndex.java:280-319`; `GeoIndexCursor.java:111-144`; `SQLFunctionGeoPredicate.java:268`; `GeoUtils.java:150-154` | P6 (arbitrary shapes on Place) | 50K candidates from 0.4 to 0.6 s to 10 to 20 ms of index decode | 4 ew |
| 11 | M7 | Composite prefix bloom per series (a `prefixLength` in the bloom directory), consulted in `newIterators` when `fromKeys` and `toKeys` share a prefix | `LSMTreeIndexCompacted.java:435-541, 1022, 1175-1177`; `LSMTreeIndexBloomFilter` | P6, P4s, (identifier, ts) composites | a prefix seek stops reading every series' root: 2 to 3x on p50 for prefix ranges over 23-series composites | 3 ew |
| 12 | M11 | Cost-based index choice: entries and distinct keys counted in the compaction pass, persisted in the index metadata; `pickBestDescriptor` ranks by estimated rows, ties toward unique and HASH | `IndexSearchDescriptor.java:76-95`; `QueryStats.java:62-71`; `SelectExecutionPlanner.java:5185, 5718-5776`; `LSMTreeIndexCompacted.java:127` | P4 | removes 10 to 1,000x pathological plans on mixed-selectivity filters; the plugin's forced index becomes a safety net instead of the mechanism | 4 ew |
| 13 | M12 | Streaming `MATCH`: `traversePatternEdge` returns the iterator instead of collecting; child rows as parent-pointer results; `ExpandAll` stops re-reading the source per edge | `MatchEdgeTraverser.java:60-84, 417-463`; `ExpandAll.java:126, 201, 233, 268`; `ResultInternal.java:161-173` | P3 (Cypher/MATCH path) | first-row latency from "after the whole hop" to "after the first neighbour"; half the heap churn per emitted row | 3 ew |
| 14 | M15 | Vector delta buffer as a contiguous INT8 flat block scored with the SIMD kernel, not a `List<DeltaVectorEntry>`; later, incremental `addGraphNode` on an on-heap graph for the ingest-target bucket | `LSMVectorIndex.java:8537-8640, 9597`; `FlatVectorCache` | P8a | the hot bucket's delta scan (hundreds of us to ms, up to four fifths of a query at the cap) 5 to 10x faster | 4 ew |
| 15 | M16 | Multi-bucket full-text passes on the query pool, merged by a bounded heap | `FullTextSearch.java:175-258, 455-466` | P7 on multi-bucket types | latency from sum to max of the buckets; low priority because TextBody is single-bucket | 2 ew |
| 16 | M19 | Raft apply notifier: per-waiter parking keyed by target index (`ConcurrentSkipListMap<Long, waiters>` + `LockSupport.unpark`), cached Ratis division reference | `RaftHAServer.java:537, 6469-6470, 6579-6625, 6726-6736, 3698-3710` | bookmarked reads (P10) | removes the notifyAll convoy and two Ratis lookups per bookmarked read | 2 ew |
| 17 | M18 | Pooled direct read buffers; longer term pages in pooled direct memory with a `byte[]`-free `Binary` (FFM is preview on Java 21, so `ByteBuffer.allocateDirect` pools) | `CachedPage.java:75`; `PaginatedComponentFile.java:256-264, 568-593` | all cold reads | the kernel-to-direct and direct-to-heap double copy (2 us per 32 KB, 15 us per 256 KB) and the zeroed allocation per miss | 4 ew |
| done | M13 | Parallel per-bucket vector search (was E7): per-bucket `findNeighbors` on the query pool, caller runs bucket 0, `GraphAlgorithms.awaitFutures`, k-way heap merge; heap-budgeted rebuild permits | `SQLFunctionVectorNeighbors.java:343-358`; `DbIndexVectorQueryNodes.java:228-235`; worktree `claude/vector-parallel-search-f7403e` | P8a | measured 7.3x (NONE) and 8.4x (BINARY) single-client p50, identical rows, 1.5 to 1.9x throughput at 4 clients; the whole P8a gain over the sequential 16-bucket walk | merge |
| done | M14 | Geo kNN (`geo.nearest`) by ring expansion and metre radii (`geo.dWithinMeters`) with Cypher geo seeks (was E9) | `LSMTreeGeoIndex`, `GeoCoveringCellWalk`; worktree `claude/geo-knn-b2da7a` | P6 shapes on Place | kNN k = 10 0.98 ms median against 116 ms by scan; radius candidate-bound at about 2 us per candidate | merge |
| done | M17 | n-gram, phonetic, reversed and phone analyzers with automaton fuzzy seek (was E11) | `LSMTreeFullTextIndex`, `FullTextIndexMetadata`; worktree `claude/partial-text-search-2551dc` | partial search, P4s | substring and obfuscation recall as exact-term lookups instead of posting scans | merge |

### 4.4 Large (6 weeks or more), ranked

| Rank | ID | Item | Where | Patterns | Gain | Effort |
|---|---|---|---|---|---|---|
| 1 | L1 | Batched record hydration: `LocalDatabase.lookupByRIDs(RID[], loadContent)` takes the read lock and resolves the transaction once, groups RIDs by (bucket, page), issues the misses in parallel on a bounded page-read pool (`PageManager.prefetch(Collection<PageId>)`, engine-concurrency rules, caller-runs saturation), reads each page once; positions-mode iteration already exists as `LocalBucket.iterator(long[] positions, from, to)` on `BucketIterator`; wired into the query engine's RID-list fetch step and the plugin hydrate | `LocalDatabase.java:1233-1282`; `PageManager.java:2594-2634`; `LocalBucket.java:704, 1397-1399, 2612-2683`; `BucketIterator.java:109-110`; `GetValueFromIndexEntryStep.java:842-908, 990-997` | P1 to P5, P9 | a cold 500-record hydrate from 53 ms serial to 2 to 4 ms inside one call without the plugin spawning threads, which also puts SQL and Cypher shapes on the section 1 numbers; 0.3 to 0.5 ms less per-record overhead per 500 hot records; the queue depth NVMe needs (5.3) comes from here rather than from 32 blocked threads | 6 ew |
| 2 | L3 | E8: Block-Max MaxScore for BM25 top-K over the sparse segment format (term = dimension id, payload (tf, dl), block bound = termScore(maxTf, minDl), query weight idf x boost), `MemtableSourceCursor` as the memtable, RID-range partitions on `SparseVectorScoringPool`; MUST/SHOULD/term queries take the DAAT path, phrase and wildcard keep the candidate path; df per segment is metadata (E8a); the RID-ordered cursors also serve P7's candidate set with early stop at R hits | `FullTextSearch.java:207-257`; `FullTextQueryExecutor.java:289-334`; `BmwScorer.java:496-638`; `PaginatedSparseVectorEngine.java:583-700`; `LSMTreeIndexCompactedStreamWriter.appendBounded` | P7 | top-K over common terms touches 1 to 5% of postings and a 2,000-hit candidate set about 100K postings: "seconds" becomes tens of ms, selective queries under 15 ms, the plugin's df gate and RRF merge go away | 8 ew |
| 3 | L2 | Batched multi-key index lookup: `IndexInternal.getMany(Object[][] sortedKeys, limitPerKey, RidSink)`: for HASH one lock acquisition and one directory walk per batch; per LSM mutable page one descent then a gallop through the remaining keys; per series one bloom pass for all keys, root once, each data page once; one tombstone pair across the batch; used by `FetchFromIndexStep` above about 8 point keys, by `recdir`, `record_id` and `textHash` hydrates and by geo cell batches | `FetchFromIndexStep.java:581-638, 802-898, 1158-1223, 1475-1502`; `TypeIndex.java:205-258, 349-357`; `HashIndex.java:240-258`; `LSMTreeIndexMutable`, `LSMTreeIndexCompacted` | P3, P7, P9, P6 | a 500-key lookup from 500 lock round trips and 1,500 touches to one pass: 10 to 50x less index CPU, sub-millisecond index time for 500-key lists | 6 ew |
| 4 | L5 | Binary wire for Recon: Bolt (openCypher, `neo4j-driver`, PackStream, one auth per connection, records coalesced, HA routing) for pivots and set queries, gRPC `streamQuery` for bulk pulls (today `executeQuery` holds the whole result in heap before the first byte), HTTP/NDJSON with tokens for the UI's ad-hoc path; HTTP/2 only over TLS with ALPN (h2c upgrade is never attempted by Bun or Node fetch), `arcadedb.network.socketTimeout` above the client pool's idle timeout so keep-alive connections are not closed at 30 s | `BoltNetworkExecutor.java:2077-2096`; `BoltStructureMapper.java:162-174`; `ArcadeDbGrpcService.java:1736-1812`; `arcadedb-server.proto:56-85`; `HttpServer.java:632-638`; `GlobalConfiguration.java:1692` | all wire | 30 to 50% fewer bytes than JSON and 1 to 2 ms less CPU per 500 records; first-row latency on gRPC from whole-result time to query time; no TCP + TLS handshake (1 to 5 ms) after an idle period | 6 ew (plugin gRPC service + Recon client) |
| 5 | L6 | Zero-copy record reads: records produced inside a statement are page views (pages are immutable; `modify()` copies), copied only when they escape the result set (`toMap`, `modify`, `detach`) | `LocalDatabase.java:1266`; `Binary.java:104-108`; `ImmutablePage.java:47-55`; `LocalBucket.java:925-940` (the scan path already hands out views) | all | 50 to 500 ns and the record's size in garbage per point lookup and hop target; 0.1 to 0.3 ms per 500 records | 6 ew |
| 6 | L4 | Off-heap page cache: pages in pooled direct memory behind a `byte[]`-free page view; the heap keeps only the index. `Binary` is `byte[]`-backed everywhere: its `ByteBuffer` constructor calls `buffer.array()` (throws on a direct buffer), `CachedPage.readByte`, `useAsMutable`, `SharedImmutablePage.privateContent` and `ImmutablePage.modify` go through `getContent()` or `.array()`, and `getContent()` has 56 call sites in 27 engine files, so the item is a second `Binary` implementation or a page-view abstraction over all of them. Scoped WITHOUT O_DIRECT: the file stays on `RandomAccessFile`, so the OS cache keeps serving EXTERNAL buckets and `.vecgraph` (3.1); a per-file-type DIRECT toggle (`FileChannel.open(..., ExtendedOpenOption.DIRECT)`) is a later item | `Binary.java:88-97`; `CachedPage.java:75, 92-94, 106, 125-127`; `SharedImmutablePage.java:50`; `ImmutablePage.java:53-55`; `PaginatedComponentFile.java:45, 568-594`; `PageManager` (no mmap exists today: grep finds none) | all | removes 23 GB of `byte[]` from the heap (ZGC region management, allocation stalls on misses), allows a 40 GB+ cache in the same RAM budget; on NVMe the device, not the copy, dominates a cold read, so this ranks after L1 to L3 | 14 ew, format-neutral but touches every page reader |
| 7 | L7 | Parallel scan tuning: allow `ParallelTypeScan` inside a read-only transaction (today refused when any transaction is active, including `/command` SELECT), per-query producer budget so one scan cannot take all 8 producers | `ParallelTypeScan.java:174-240`; `FetchFromTypeExecutionStep.syncPullParallel`; `GlobalConfiguration.java:1100-1120` | n4 analytics, group-by | 4 to 10x on filtered scans and aggregations that run inside sessions; nothing on point lookups (C2 already routes interactive reads outside transactions) | 6 ew |

**Order of execution** (Q2 onward, after the day-1 configuration, S16 and the three worktree merges): S1, S17, S2, S4,
S3, S5 (one engineer, 6 weeks, all on the per-read hot path and all measurable with the gate of section 6) in parallel
with M1 (the p99 fix) and M20 (the bandwidth fix, new partitions only); then M2 and M4; then L1 and L2 together (they
share the sorted-key, page-grouped design); M5, M6, M8 and M9 next; L3 (E8) once M8's primitive posting cursor exists;
M3, M7, M10, M11 after the Q3 measurements say which of P4, P4s and P6 is further from its target; L5 when Recon's client
is ready for a second protocol; L4 and L6 are year-2 items unless P0a shows the heap page cache itself in the GC profile.

---

## 5. JVM and OS tuning for a 126 GB read-heavy node

### 5.1 Memory split

The RAM budget is fixed by the architecture (1.4): OS 6 + JVM heap 56 + JVM native 3 + direct 4 + ROC 45 + encoder
sidecar 3 + OS file cache 9 = 126 GB (n4: 6 + 43 + 3 + 4 + 45 + 3 + 16 build slice + 6 = 126; appendix B). The heap/OS-cache question is which of the two should hold the next GB of pages:
- a heap index page hit costs C_idx = 0.1 us and a record page hit C_pg = 0.2 to 0.3 us; an OS-cache hit costs R_os =
  6 us; a device read R_dev = 100 to 106 us. Per GB (32,768 pages of 32 KB) moved from the device into either cache the
  saving per hit is about 100 us or 94 us: nearly equal. The difference is what the GB costs the JVM: 32,768
  `byte[32768]` objects in old regions that ZGC relocates and that every allocation stall competes with, against zero
  JVM cost in the OS cache.
- ZGC needs about 15 to 20% of the heap free to avoid allocation stalls; 10 GB of 56 GB is 18%. Growing the page
  cache from 23 to 33 GB inside the same heap would leave the headroom at 0, so the heap stays at 56 GB with a 23 GB
  page cache until L4 moves pages off heap, after which the same 23 GB sits in direct memory and the heap can shrink to
  about 30 GB, giving 26 GB more to the OS cache.
- The 9 GB OS cache is therefore the lever: every GB added there answers 33K more 32 KB pages in 6 us instead of 106 us.
  If P0a shows the ROC steady state at 26 GB of templates rather than its 45 GB cap, the gap is OS cache, not heap.

### 5.2 JVM flags (n1 to n3; n4 with `-Xmx43g`, `-Xms43g`, `SoftMaxHeapSize=38g`)

**Runtime pinned to the Dockerfile's: Eclipse Temurin 25.** `package/src/main/docker/Dockerfile:20` is
`FROM eclipse-temurin:25-alpine`; the jars are compiled for release 21 (`Dockerfile:17-19`, `00-research-findings.md`:
"Java 21" is the build target, not the runtime). The two differ in flags: ZGC is generational-only from Java 24, so
`-XX:+ZGenerational` is obsolete on 25 and only prints a warning at every start (`Dockerfile:29-30`), and
`-XX:+UseCompactObjectHeaders` is a product option only from 25 (Java 21 refuses to start with it), which is why
`server.sh:90-101` probes the JVM for it and sets `ARCADEDB_OPTS_HEADERS` only when the probe passes. The flags below
assume 25; a node that must run 21 adds `-XX:+ZGenerational` and gets no headers flag from the probe, and the object
sizes of section 4 grow by 4 to 8 B each.

`Dockerfile:31` sets only `-XX:+UseZGC` and `:37` sets `-XX:MaxRAMPercentage=75`, which on a 126 GB node is a 94 GB
heap, breaks the budget and triggers the `maxPageRAM` clamp (`GlobalConfiguration.java:178-200`). Override both and
leave `ARCADEDB_OPTS_HEADERS` to the probe:

```
ARCADEDB_OPTS_MEMORY="-Xms56g -Xmx56g -XX:SoftMaxHeapSize=50g -XX:MaxDirectMemorySize=4g"
ARCADEDB_OPTS_GC="-XX:+UseZGC -XX:+AlwaysPreTouch -XX:-ZUncommit
  -XX:ZAllocationSpikeTolerance=5 -XX:ConcGCThreads=6 -XX:ParallelGCThreads=16
  -XX:+UseTransparentHugePages -XX:+UseNUMA
  -Xlog:gc*,gc+phases=debug,safepoint:file=/home/arcadedb/log/gc.log:time,uptime:filecount=5,filesize=50m"
# ARCADEDB_OPTS_HEADERS is NOT set: server.sh:95-101 probes -XX:+UseCompactObjectHeaders and adds it on 25
```

n4: `ARCADEDB_OPTS_MEMORY="-Xms43g -Xmx43g -XX:SoftMaxHeapSize=38g -XX:MaxDirectMemorySize=4g"` (1.4: page cache 4 + vector 8 +
rebuild 7 + OLAP working memory 16 + GC headroom 8 = 43; 38 keeps the same 89% soft target and 8 / 43 = 19% headroom;
revision 2's 46g summed to 129 GB; appendix B).

- `-Xms = -Xmx` and `AlwaysPreTouch`: the 23 GB page cache is otherwise faulted in 4 KB at a time (6M page faults,
  seconds of warm-up latency spread over the first minutes of traffic). Pre-touching 56 GB at about 10 GB/s costs 6 s of
  startup, paid once, before the node is ready.
- `-ZUncommit`: ZGC would otherwise return idle heap to the OS and re-fault it on the next miss storm.
- `ZAllocationSpikeTolerance=5`: cache misses allocate one zeroed `byte[32768]` each; at 30,000 misses/s that is 1 GB/s,
  and a 2,000 request/s mix at about 500 KB of transient garbage per request is another 1 GB/s. Generational ZGC
  sustains this; the tolerance factor starts the young cycle early enough that a burst of 3x does not stall.
- `ConcGCThreads=6`: 20% of 32 cores for concurrent marking and relocation; `ParallelGCThreads=16` for the short
  pause phases.
- Compact object headers (probe-selected on 25) save 4 to 8 B per object, which matters on the 10 to 15 objects a
  lookup allocates today (M8). Compressed oops are off above 32 GB of heap, so object sizes in section 4 assume 8-byte
  references.
- `UseTransparentHugePages`: the JVM `madvise`s its heap for 2 MB pages; the 23 GB of page-cache arrays then need
  11,776 TLB entries instead of 6M, which is typically 5 to 15% of CPU on pointer-chasing code. Requires 5.3.
- `UseNUMA`: ZGC's NUMA-aware allocation keeps a thread's young allocations on its socket.
- Not set: `-XX:+UseLargePages` with hugetlbfs (needs `vm.nr_hugepages=29000` reserved at boot and makes the ROC
  budget rigid; THP gives most of the benefit without the reservation).

### 5.3 OS

- **Transparent huge pages:** `transparent_hugepage/enabled=madvise`, `defrag=defer+madvise`, so only the JVM's
  `madvise`d heap gets huge pages and the OS page cache, ROC and Ratis keep 4 KB pages (huge pages in the file cache
  would make 32 KB random reads pull 2 MB).
- **Block device:** NVMe scheduler `none`; `read_ahead_kb=32` (`blockdev --setra 64`, sectors) because the engine
  reads whole 32 KB Record pages at random and `BucketIterator` already batches scans; `nr_requests` at the NVMe default
  (1023); dm-crypt opened with `--perf-no_read_workqueue --perf-no_write_workqueue` so reads complete inline instead of
  being queued to a kernel worker (saves 10 to 30 us per read); XFS with `noatime`, `inode64`, `logbsize=256k`.
- **io_uring is not available from Java**, so NVMe queue depth comes only from concurrent synchronous preads. The
  device needs about QD 32 to deliver 300K IOPS at 100 us (Little's law: 32 / 100 us = 320K/s). Sources of concurrency
  on a read node: the 32-way `spork-hydrate` pool, up to 96 HTTP workers, 8 parallel-scan producers, and after L1 the
  page-read pool inside `lookupByRIDs` (32 reads in flight per call). The async worker pool is not one of them on a
  follower (C6). Keep the sum of blocking readers near 128 so the device queue stays full without 500 runnable threads.
- **NUMA:** on a 2-socket node, `roc-search` (16 cores) and `roc-work` get one socket's cores by `cpuset`, the JVM the
  other socket plus whatever ROC leaves idle through `cpu.weight` rather than a hard quota on the JVM, so an idle ROC
  core serves database reads; `numactl --membind` is not used for the JVM (it needs memory from both sockets for 56 GB
  on a 64 GB-per-socket board), `UseNUMA` handles locality.
- **VM:** `vm.swappiness=1`, `vm.dirty_background_ratio=5`, `vm.dirty_ratio=20` (followers write applied page deltas
  and the Ratis log), `vm.max_map_count=1048576` (jvector maps its graph files), `fs.file-max` and the service's
  `LimitNOFILE` at 1,048,576 (one `FileChannel` per component file, about 12 databases x hundreds of files).
- **cgroups:** the JVM, `roc-search`, `roc-work` and the enrichment workers in separate cgroups with `memory.max` at
  their budgets (JVM 56 + 3 + 4 = 63 GB, ROC 45 GB, encoder sidecar 3 GB), so a ROC gallery growth cannot evict the OS file cache the JVM
  relies on, and `memory.high` on the JVM cgroup 2 GB below `max` so pressure shows up as a metric before an OOM kill.

### 5.4 Server settings that pair with the JVM

The full day-1 setting list for n1 to n3 (the server has no properties-file loader: keys are passed as `-D` system properties through `ARCADEDB_SETTINGS` or in `config/server-configuration.json`, see `config/README.md`), consolidating 4.1 (n4 keeps the parallel defaults, a 4 GB page
cache, `queryIndexMaxSelectivity` at its default and `HA_SERVER_ROLE=replica`):

```
arcadedb.maxPageRAM=23552            arcadedb.initialPageCacheSize=2000000
# arcadedb.freePageRAM is left at its default 50 on purpose (C1, 3.1)
arcadedb.indexDefaultPageSize=65536  arcadedb.bucketDefaultPageSize=32768
arcadedb.indexCompactionMinPagesSchedule=64  (identity and the current period; moot on sealed partitions, 2.0)
arcadedb.indexCompactionFullSeriesThreshold=0   arcadedb.indexCompactionFullDeadEntriesRatio=0   (every database, 2.0)
# arcadedb.ha.readConsistency=read_your_writes  (already the default; eventual changes nothing for bookmark-less requests)
arcadedb.server.httpWorkerThreads=96 arcadedb.server.httpIoThreads=8
arcadedb.queryMaxConcurrent=48 arcadedb.queryQueueMaxSize=64 arcadedb.queryQueueTimeout=2000
arcadedb.queryParallelismPoolThreads=12 arcadedb.parallelScanProducerPoolThreads=8
arcadedb.queryParallelScanPagesPerUnit=256 arcadedb.queryScanReadAheadMaxRAM=512
arcadedb.queryIndexMaxSelectivity=0   (rec_<p>: never abandon an index for a scan; n4 keeps 0.6)
arcadedb.command.timeout=30000
arcadedb.server.httpTerminateOnClientDisconnect=false  arcadedb.server.httpQueryMaxResultRows=2000
arcadedb.sqlStatementCache=2000 arcadedb.opencypher.statementCache=2000 arcadedb.opencypher.planCache=2000
# arcadedb.queryLiteralParameterization=true    (already the default)
arcadedb.vectorIndex.searchCacheSize=500000  arcadedb.polyglotEngineEnabled=false
arcadedb.network.socketTimeout=600000
arcadedb.graph.supernodeThreshold=1000000
arcadedb.graph.ghostEdgeProbe=false   (sealed partitions only, after CHECK DATABASE ... FIX)
arcadedb.asyncWorkerThreads=2         (leader only; a follower never creates the pool)
```

`queryIndexMaxSelectivity=0` (`GlobalConfiguration.java:1086-1098`): the option is the share of the type above which an
index search is abandoned for a scan, divided by (1 + W) / 2 for W parallel-scan workers (`ParallelTypeScan.java:246-262`
plans W from the producer pool, 8 here), so the stock 0.6 flips at 13% and the 0.9 of revision 1 at 20%; it applies only
to aggregations and to an ORDER BY the index does not serve (`SelectExecutionPlanner.java:5427-5445`), so it never touched
the P4 row pages and would have turned any count over 10M matching rows into a scan. 0 disables the rule, which is what a
count capped at 10,000 entries wants. n4, where wide aggregations should use all cores, keeps the default.

---

## 6. Benchmark plan

### 6.1 Datasets

The generator exists: `ReconDataGenerator` in `claude/read-bench-63f0a8`
(`benchmarks/src/main/java/com/arcadedb/benchmarks/retrieval/ReconDataGenerator.java`, `ReconDomain.java`,
`ZipfSampler.java`), writing Record vertices with `HAS_*` edges to Phone/Email/Handle identifier vertices drawn from
Zipf pools (the largest phone hub is about 1% of the records), states by population rank, posting time `NOW - SPAN x
u^2` over two years, 1 phone (80%) or 2, email 30%, handle 40%, 0 to 4 image hashes. Three extensions bring it to the
section 2 schema and to 290M scale:
- **Schema v1 shape:** the denormalized Record fields and arrays (views included), `record_id` with the period in the
  high bits, `upstreamRid` + `recdir`, per-period databases (one `rec_<p>` per 77M records), `txt_<p>` with deduplicated
  TextBody (50% unique, 600 B bodies drawn from a Zipf vocabulary so df follows a power law: the top term in 30% of
  texts, rank 1,000 in 0.1%) and `StyleVec` in month buckets, 24-d style vectors in clusters so 2% of pairs pass cosine
  0.95, 8M Places with lat/lon, the geo composite, `located` 60%, the `identity` registry with the 2-or-more rule (0.61
  shared identifiers per record), hubs (recordCount > 2,000 across > 25 cities) at 0.01% of identifiers, one bucket per
  type, 32 KB record pages, HASH indexes at 4 and 16 KB, 64 KB composites.
- **Scales:** 300K (CI gate, already the default of `RetrievalRegressionGateBenchmark`), 5M (laptop), 29M (P0a, 10%
  of today), 290M (today), 900M (3x; on n4's build slice through `GraphBatch` at the measured rate: at 150K elements/s,
  2B elements is 3.7 h plus index builds). The 290M and 900M sets are generated once and kept as database directories
  on the benchmark host.
- **Parameter sampling** shares the pools (`ReconDomain`), so a query asks for identifiers the generator wrote; the
  sampler draws tokens with the same Zipf skew as the data (hubs are hit proportionally) plus a 10% uniform tail so
  singletons and misses are exercised.

### 6.2 Query mix

`QueryMix` has identLookup, pivot1Hop, pivot2Hop, structured, feed24h, geoRadius14d, fulltextTop20, vectorKnn20,
hydrate200. Add intake300 (registry probe + footprint), pivot1HopTyped (`on=phone`), structuredViews (`sort=views_desc`
on a 30-day window of a top-3 state), structuredSubstring (7-digit phone fragment, email fragment), structuredCount,
qWithFilters (median df term AND state AND 30-day window, posted sort, total), qBare (the `bulk` tool's `limit: 1`
total), feed6hSampled, feedStateFiltered, recent1000, hydrate2000, dossierHydrate7500 (n4 lane), boardWhole500,
auditPage100WithTotal, compareEntities4x500 (`selection.compare` with `via`), geoAnalyze2000 (set-scoped, n4 lane), and
the P7 variants (selective: df under 0.1%; common: df 5% with a posted window; gated: df 30%, expected to be refused or
routed to n4). Weights from 0.1 for the mixed run on n2 (P6 at 2, P8 at 1, P9c and P11 on the n4 lane); single-query
runs for the per-pattern targets. Every query in the mix is the exact statement or plugin call of section 2, so the
benchmark measures the design, not a proxy.

### 6.3 Harness

- **Engine (JMH):** `EmbeddedRetrievalBenchmark` (`@State(Scope.Benchmark)` dataset, `@State(Scope.Thread)` sampler, one
  `@Benchmark` per query) over the embedded API; `JfrTopFrames` attaches JFR and prints the top allocation and CPU frames
  per query, which is how the per-stage split of the audits (index walk, record load, decode, serialize) is confirmed
  and how a regression is attributed. Tagged `@Tag("benchmark")` so the regular lanes skip it; the root pom defaults
  `excludedGroups` to `benchmark`, so runs need `-DexcludedGroups=none` (the verification log records that a run without
  it is silently green with zero tests). Two added cases: `hashGet32Threads` (32 threads, the same HASH index, distinct
  keys, warm) and `lsmGet32Threads`, run before and after S17 to measure the per-index lock convoy.
- **Page-touch counters:** every engine benchmark also records `PageManager.getStats()` deltas (`cacheHits`, `cacheMiss`,
  `totalPagesRead`, `totalPagesReadSize`, `evictionRuns`, `pagesEvicted`) per query and asserts the expected counts
  exactly: identLookup on a sealed partition <= 3 page gets (HASH), hydrate200 by `record_id` <= 400 misses cold,
  pivot1Hop 500 <= 503 misses cold, structured first page <= 170, geoRadius14d <= 700 misses cold. Two structural
  assertions guard the bucket rule: `TypeIndex.getIndexesByKeys(keys).size() == 1` for every Record, TextBody and
  identifier-vertex index, and cursors opened per range (`LSMTreeIndexCursor` constructions, counted through the index
  statistics) == seeks x 1 on P4, P4s, P5 and P6. These are deterministic, so they are the strongest regression gates
  and never flake on a slow machine.
- **HTTP (in-process):** `HttpRetrievalBenchmark` starts the server over the dataset and drives closed-loop clients at 1,
  8 and 32 threads in `json` and `ndjson`, reporting p50/p95/p99 and throughput per (query, format, threads).
- **HTTP (plugin routes, open-loop):** k6 from a separate load host over 10 GbE against n2 with the real
  `spork-recon` routes, token auth and the section 2 headers, at fixed arrival rates of 500, 1,000 and 2,000 requests/s
  with the 0.1 mix for 10 minutes each, plus a 30-minute soak at 1,000/s with ingest running on n1 at 3x pace (a
  closed-loop client under-reports p99 because a slow response delays the next request; open-loop does not).
  Bolt and gRPC legs are added with L5 using the same mix.
- **Cold runs:** `echo 3 > /proc/sys/vm/drop_caches` and a server restart before each cold measurement; warm runs
  after the warmup of 3.5 and one pass of the mix.
- **Latency measurement:** `StallAwareStopwatch` (engine test-jar) in the gate so a JVM-wide stall inside the window
  is discounted, with `assertStayedUnder` because the bound is the claim (the gate's own message says so:
  `RetrievalRegressionGateBenchmark.java:174-177`); open-loop p99 from k6 is reported raw because a production p99 is
  raw.

### 6.4 Acceptance thresholds

| Gate | Where it runs | Threshold | Fails when |
|---|---|---|---|
| CI regression gate | every PR, 300K records, single thread, warm | p95 within 3.0x of `retrieval-baselines.json` per query (the existing gate), page-touch counts exact, sub-indexes per lookup and cursors per seek exact | a query leaves its index, adds a page read, or a type gains a bucket |
| Engine nightly | n4, 29M set, 1 and 32 threads, warm and cold | per-pattern cold p50 and p99 within the section 1 targets scaled to 29M (same per-row cost; the index tops are smaller, so the targets apply unchanged), p50 drift over 15% or p99 drift over 25% against the trailing 7-day median; eviction: `evictionRuns` x measured run time under 1% of wall during the cold hydrate storm, which at the stock `freePageRAM=50` means a run time under 115 ms per 11.5 s refill period (3.1) | any pattern regresses, or the eviction stall share exceeds 1% (S16 and M1 are what bring it there) |
| Scale run | before each quarterly cutover, 290M then 900M set, open-loop at 2,000/s | the section 1 cold p99 column at 290M; at 900M the same with P9b allowed 1.5x (12 partitions in `periodMask` probes); throughput 2,000/s with no `QueryAdmissionException` and `caller_run_fallbacks` at 0 on the query pools | targets missed or admission refusals |
| Soak | monthly, 30 minutes at 1,000/s with ingest at 3x pace | p99 of the last 10 minutes within 1.2x of the first 10 (no drift from compaction lag or delta buffers); heap after the run within 10% of the start; `evictionRuns` growth linear, not bursty | drift or leak |
| Shadow-diff parity | WS4 harness | rows, signals, totals and dossier `contentHash` match within the listed differences (q tokenization versus substring, the 2,000-hit `total` cap, the 7,500-record dossier cap) | any unlisted difference |

### 6.5 How regressions are gated

The CI gate runs on every engine PR in the `benchmark` lane; a red run names the query and prints the JFR top frames,
so the author sees whether the regression is an added page read (counter), an allocation (JFR) or a plan change
(`EXPLAIN` captured per query in `BenchReport`). Re-baselining is a reviewed change to `retrieval-baselines.json` with
the `gate-measured.json` of the accepted run attached, never a loosened headroom. The nightly posts its report
(`BenchReport`, JSON plus a Markdown table) to the team channel with the 7-day sparkline; a quarterly scale run is the
exit criterion for each engine item in section 4 (an item ships when the pattern it targets moves by at least the
estimated gain's lower bound on the 290M set, and nothing else regresses).

---

## 7. Open risks

1. **The estimates are from code, not measurements.** Every us and allocation figure in sections 1 to 4 is an
   engineering estimate; the first P0a run with JFR and page counters on the 29M set replaces them, and the ranking
   in section 4 is re-sorted on measured gains. The biggest uncertainty is the per-record CPU (C_rec): if the one-pass
   projection (S4) does not reach about 1 us for 20 fields, P1 to P4 and P9 are CPU-bound sooner than the throughput
   arithmetic of section 1 assumes.
2. **Eviction stalls until S16 and M1 ship.** The whole-cache copy-and-sort runs once per refill period (maxRAM x
   freePageRAM / miss bandwidth: 11.5 s at 1 GB/s of misses with the default 50) for 0.2 to 0.5 s under the
   process-wide monitor, and until S16 every reader that arrived during the run repeats it. Until M1 the p99 column of
   section 1 holds only between runs; the nightly's eviction gate (6.4) says how much wall time it costs, and if it is
   over 1% M1 moves ahead of everything else. Lowering `freePageRAM` is not a mitigation (3.1).
3. **HASH indexes under Raft.** C8 puts every exact key on `HashIndex`, which is newer than the LSM path; its page
   layout, overflow handling, `BY ITEM` support and behaviour after a follower snapshot install are verified in P0a
   before schema v1 is final. Fallback: LSM_TREE at 256 KB pages (2 series after the seal compaction, 7 cached touches, a
   cold leaf of 256 KB); never 16 KB, where the same index is about 360 series.
4. **Compaction against the Raft log.** Reads want M <= 4 on live range indexes; the architecture caps compaction
   because every compacted page ships through the log (320 GB/day for the stock policy on `recdir` alone, now moot for
   `recdir` as a HASH). The policy of 2.0 (schedule 64, no automatic full compaction, leased full compactions) trades cursor-open cost on
   the live partition (up to 64 mutable pages and 56 series at week's end at 3x pace) for a bounded log; P0a measures both,
   and M2's fences and series directory are what keep the cursor open cheap under it. The full-series threshold must stay 0 on indexes whose sealed series count (root
   overflow, 0.2) exceeds it, or every compaction becomes a full rewrite.
5. **Planner choice on structured search.** Until M11 the plugin forces indexes from its own cardinality table; a
   filter combination the table does not cover falls back to the planner's `getAllIndexes` order and can take a
   1,000x plan. The P4 gate asserts `EXPLAIN` output per query to catch it.
6. **`q` semantics are capped until L3.** Recon's `q` is served as a candidate set with an exact `total` only up to
   2,000 hits per request and a df gate at 2% of a partition; common terms over all history are refused on n1 to n3 or
   run on n4, and tokenized matching differs from upstream substring search. The shadow-diff harness lists both
   differences (6.4); the `bulk` tool's counts carry `partial` past the cap.
7. **Follower caches and apply lag.** The identifier and record caches (3.2) are invalidated by each node's own Outbox
   tail, so a follower that lags shows stale footprints for the lag; `read_your_writes` with a bookmark after an
   analyst's own write is the only guard, and `SERVER_READINESS_HA_MAX_LAG` bounds the window. Set-scoped compare over
   stale selections is acceptable by design (F1 skips members that no longer exist).
8. **Thread budget against ROC's cores.** Section 1's throughput assumes 32 cores at 70% for the JVM; the ROC search
   cgroup takes up to 16 during face searches. If face traffic is sustained rather than bursty, the read-node throughput
   target halves (about 1,000 mixed requests/s per node) and the pool sizes of C6 are re-derived at 16 cores; P7's CPU
   share (70% until M9) is what makes this bite first.
9. **Edge-chunk growth on shared identifiers.** The 2 KB first chunk (C7) cannot be set per vertex or per type today:
   `LocalDatabase.getNewEdgeListSize` reads the JVM-wide value, and a global 2 KB chunk on 46M local singletons per
   partition would cost about 85 GB, so day 1 keeps 64 B (7 chunk hops on a 500-record identifier) until S18 ships
   (appendix B); P0a measures the actual bytes per record and chunk hops against the 0.4 budget.
10. **Protocol change in Recon.** L5 assumes Recon adopts a second driver (Bolt or gRPC); `recon-next` has no database
    driver dependency today, so the choice is open, but a two-protocol client doubles the shadow-diff surface.
11. **Geo covering at precision 4.** The P6 design puts about 200 range cursors behind a 200 km radius; smaller
    radii (25 km) resolve to about 6 cells at precision 4 and over-fetch the ring, so the plugin picks precision 4 for
    R > 60 km and precision 5 (about 300 cells at 25 km) below it, and both are in the mix of 6.2. Arbitrary shapes
    stay on the Place GEOSPATIAL index.
12. **Off-heap pages (L4) are a long pole.** Every page reader in the engine assumes a heap `byte[]` behind `Binary`
    (56 `getContent()` call sites, a `ByteBuffer` constructor that calls `array()`); the item is ranked last on purpose
    and is only pulled forward if P0a's GC log shows allocation stalls attributable to the page cache rather than to
    query garbage (which M4, M5, M8 and S3 remove first).
13. **Record page bandwidth until M20.** With v0's 2,048-slot table the Record page is 32 KB, so every hydrate over a
    few hundred records is bandwidth-bound (P9b 112 MB, P9c 270 MB); M20's 256-slot format halves it but applies to new
    partitions only, so the sealed partitions of year 1 keep the 32 KB cost until they are rebuilt.
14. **Bucket count is a one-way door.** Every lookup and cursor estimate assumes one bucket per type (0.2). A type that
    gains buckets later (for write parallelism, for instance) multiplies its sub-index probes and cursor opens by the
    bucket count; the 6.3 structural assertions exist to make that change a red build rather than a silent p99 regression.

---

## Appendix A. Review log (revision 1 -> 2)

Issues raised against revision 1 and their disposition. Several referenced items or section numbers that revision 1
had already changed before the review landed (its C1 was `freePageRAM=5`, its Record was 4 buckets, its C_hash already
3 touches); the disposition records what revision 2 did about the substance.

| # | Issue | Disposition |
|---|---|---|
| 1 | TypeIndex probes every bucket sub-index; point lookups priced as one probe | Applied. One bucket per type (2.0, C9), `StyleVec` the only exception; "sub-indexes per lookup" is a 0.2 constant and a 6.3 assertion; P1, P2, P3, P4, P9 re-derived. Revision 1's 4-bucket Record and `PartitionedBucketSelectionStrategy` are gone: parallel scans split a single bucket (`GlobalConfiguration.java:1106-1120`) |
| 2 | Range cursors are one per bucket sub-index | Applied. C_cur in 0.2 states cursors = seeks x buckets; P4, P4s, P5, P6 computed with 1 bucket; the 4-bucket alternative is priced in the P6 row (800 opens) for contrast; 6.3 asserts cursors per seek |
| 3 | 16 KB bucket pages carry a fixed 8,194 B slot table | Applied. D39 revised to 32 KB Record pages (40 rows), with the 13 / 40 / 94 / 25 per-page table in 0.2, every cold bandwidth term re-derived, the 3.1 hot set and 3.5 warmup recomputed, FeedWindowSummary's hot part bounded by the page's record space, and M20 (256-slot format version) added to group M. 64 KB was not chosen because it doubles every bandwidth term that 32 KB already made the binding one |
| 4, 13 | `searcherPoolSize` claim and C6 gain | Not present in revision 1 as reviewed here (its C6 was the thread budget and C11 did not mention the pool). Revision 2 states the correct semantics in 2.8 and C11 so the claim cannot return, and attributes the P8 gain to M13 only |
| 5 | C_hash 3 touches; per-index single-stripe fair lock | C_hash was already 3 touches in revision 1; the lock, allocation and cold-read details were added to C_hash and S17 (index locks, `getPageForAbsoluteReads` in `HashIndexBucket`) was added with the 6.3 JMH case |
| 6 | Day-1 SQL cold cells divided by 32 without a prefetcher | Applied. 0.2 "Day-1 serving rule"; P4 moved to the plugin path with the serial figure shown (16 ms); P4c's Day-1 SQL count (1.06 s) stated; Cypher admin shapes marked serial until L1 |
| 7 | C2 `read_your_writes` saving is false | Applied. 2.0 routing and C2 reworded: the default is free without a bookmark, only `linearizable` pays the RPC; 5.4 keeps the default |
| 8 | C_lsm reads the series tail before the bloom | Applied. C_lsm = 1 + 2S + 2; 3.6 rewritten; M2's series directory moved to rank 3 and named as the item that removes the tail read |
| 9 | 1,024 versus about 500 leaves per series | Revision 1 did not cite the constant, but its "S = 1 after seal" rested on the same misunderstanding. Applied as the "Series per sealed LSM index" row of 0.2 (1,024 cap, root overflow binding below 256 KB) and in 2.0 |
| 10 | C_hit must split index touch and record-page touch | Applied. C_idx (0.1 us) and C_pg (0.2 to 0.3 us, 3 allocations, two clock reads) in 0.2; S2 carries the `CoarseClock` and duplicate-call fix with the per-touch saving |
| 11 | Lowering `freePageRAM` makes the stall more frequent | Applied, and revision 1's `freePageRAM=5` was worse than the reviewed 10. C1 keeps the default 50; S16 added as the day-1 patch; 3.1, 5.4, risk 2 and the 6.4 eviction gate re-derived from the refill rate; the "hot month thrown away" claim removed (eviction is by last access) |
| 12 | `queryIndexMaxSelectivity` pushed the wrong way | Applied. Revision 1 had 0.9 with the wrong rationale; C12 and 5.4 now set 0 on `rec_<p>` with the (1 + W) / 2 arithmetic at W = 8 and the aggregation-only scope |
| 14 | LSM bloom skip ignored; `textHash` and `imageHashes BY ITEM` LSM at 16 KB would compact to hundreds of series; blanket full-series threshold | Applied. Every exact key is HASH (C8), the LSM fallback is 256 KB (2 series), the full-series threshold is 0 on `rec_<p>` composites (C5), 3.6 says blooms matter more as series grow, `indexBloomFilterRate` is stated as not a lever |
| 15 | Serializer-only one-pass decode misses projected rows | Applied to revision 1's S4 (same substance): rescoped to `PropertyCachingResult` / `locateProperties` in `ProjectionCalculationStep` and the Cypher projection operator, C_rec re-derived for a 20-field projected row (6 us -> 1 us), M4 narrowed to what S4 leaves |
| 16 | Database-lock convoy overstated; LongAdder on miss-path counters | Revision 1 never claimed a database-lock convoy; the LongAdder sub-item of S2 is dropped with the reason (miss path, counters). The real convoy is the per-index lock (S17) |
| 17 | `asyncWorkerThreads` saving on followers is zero | Applied in C6 and 5.3: the pool is lazy (`LocalDatabase.java:673-678`), leader only |
| 18 | `-XX:+ZGenerational` on the Java 25 image; headers flag hardcoded | Applied. 5.2 pins Temurin 25, drops `ZGenerational`, routes headers through the `server.sh` probe |
| 19 | Off-heap cache effort thin; O_DIRECT breaks the EXTERNAL OS-cache assumption | Applied to L4 (revision 1's name for it): `Binary` constraints and the 56 call sites stated, effort 14 ew, scoped without O_DIRECT so 3.1's OS-cache role for EXTERNAL buckets holds |
| 20 | `LocalBucket.getRecords(long[])` does not exist; HASH touch count; `tx.getPage` in `HashIndexBucket` | Revision 1's L1 did not cite `getRecords`; L1 now cites `LocalBucket.iterator(long[], int, int)`; C_hash was already 3; the `getPageForAbsoluteReads` line item is in S17 |
| 21 | P7 ranked by score while Recon sorts by posted with an exact total | Applied. 2.7 rewritten as a candidate-set producer with the 2,000-hit cap, AND with the structured bitmap, posted or views sort, `total` with `partial`; relevance opt-in; P7 row and risk 6 re-budgeted; the P7 CPU share dominates the throughput arithmetic until M9 |
| 22 | `views_desc` sorts the whole filtered set | Applied. `(facet, views, tsPosted)` composites and the fallback materialization in 2.4; P4v row added; the 40 x limit rule excluded for this arm |
| 23 | Pivot is typed | Applied. `/pivot` takes `on`, 2.2 and 2.3 rewritten, P2 and P3 rows re-derived |
| 24 | FeedHour cells cannot produce Recon's feed fields; `/recent` missing | Applied to revision 1's `FeedWindowSummary` (same substance): keyed by place with distinct-phone sketches, byState, recurring with places; state and city feeds served from the P4 composites with aggregates over the sample; P5b `/recent` row added |
| 25 | Board is not paged by updatedAt | Applied. 2.10 returns the whole tenant board server-sorted on `(tenantId, closed)`, with the paging composite only if ever needed |
| 26 | Audit `total` and `verification` per page | Applied. Counters in `ChainHead` written with the append, checkpoint document read per page, M3 fallback for ad hoc combinations |
| 27 | Dossier hydrates up to 7,500 records | Applied. P9c row, 2.9 dossier paragraph, mix row on the n4 lane, dossier caveat |
| 28 | Phone, email and business filters are substring matches | Applied. P4s arm with the gram and suffix ranges on local identifiers and the `(phones BY ITEM, tsPosted, views)` family of composites; P4s row |
| 29 | Mix weight on corpus geo and kNN that Recon never sends | Applied. P6 to 2%, P8 to 1%, P11 (set-scoped `/geo/*` and `/graph/*`, n4) added at 5% with its own row |

## Appendix B. Reconciliation log (with `01-architecture.md` round 6)

The config-bundle review (`config/README.md`, "doc errors") found ten places where this document and `01-architecture.md`
disagreed. Each was settled by read latency first and the verified engine facts second, in both documents, in
`model/budget.py` and in `config/`; the full arithmetic is in `01-architecture.md` Appendix A, round 6.

| # | Conflict | Settled as | Changed here |
|---|---|---|---|
| 1 | n4 heap: 5.2 said 46g / 42g, 1.4 says 43g | 43g / 38g: only 43 sums to 126 (6 + 43 + 3 + 4 + 45 + 3 + 16 + 6; 46 gives 129); 38 = 43 x 50 / 56 keeps the 89% soft target | 5.2 header and n4 line |
| 2 | OS file cache: 3.1 and 5.1 said 12 GB | 9 GB on n1 to n3, 6 on n4 (3 GB funded the encoder sidecar in 1.4 round 4); 590K pages of 16 KB | 3.1, 5.1, 5.3 cgroups |
| 3 | Compaction: C5 schedule 4, `fullSeriesThreshold=3` on `identity`; 1.4 / E0 0 everywhere, schedule 64, leased full compactions | The architecture's policy: each round appends a series, so schedule 4 leaves a live composite 892 series a week against 56 at 64 for 60 fewer binary searches per open and +6 GB/day of log; threshold 3 is a no-op on tiny `identity` indexes and an unbounded rewrite on growing ones (AuditEntry: 1.5 GB/day and rising); kept from C5: full compaction of every index at seal, bloom rate default | 2.0, C5, 5.4, risk 4 |
| 4 | Point lookups: C8 HASH; the architecture assumed LSM_TREE | HASH everywhere: 3 cached touches + 1 cold read against 1 + 2S + 2 (S = 519 to 1,609 for `recdir` at 16 KB), under 0.25 GB of resident directories against 3.8 GB of blooms, `compact()` false so no Raft traffic; limits verified (no ordered iteration, entry fits a page, depth 30, non-unique RID lists, `BY ITEM` by property type) | 0.2 Index page, 2.0 (limits), C8; the architecture's D52 reversed so n4 takes P9c and P11 as 2.0 says |
| 5 | Bucket pages: the architecture had Record, `recdir`, TextBody at 16 KB (131 MB per 2,000-id hydrate); C9 had 32 / 16 KB (160 MB) | C9's buckets (v0: 13 / 40 / 94 rows per 16 / 32 / 64 KB page, 97 / 63 / 54 GB per partition; 32 KB until M20, 16 KB v2 after) and HASH at the engine defaults instead of a uniform 16 KB (4 KB on LONG and UUID keys, #5712): upstream RID 4 + 16 + 4 + 32 = 56 KB, 2,000 ids 112 MB (72 by `record_id`, 80 and 40 after M20), search row 80 KB | 0.2, section 1 P9 to P9c, 2.0, 2.9, 3.1, C9, 6.1, risk 13 |
| 6 | 2.1 claimed a per-vertex `GraphEngine` chunk-size API | None exists: `LocalDatabase.getNewEdgeListSize` is a JVM-wide static and `createInEdgeChunk` / `createOutEdgeChunk` take no size; 64 B on day 1, engine item S18 / E24 (0.5 to 1 ew, per-type first and second chunk sizes; 2048 everywhere is +85 GB per partition) | 2.1, C7, 4.2 S18, risk 9 |
| 7 | Both documents said SQL has no page-size clause and routed buckets through Java | `CREATE [VERTEX|DOCUMENT] TYPE ... PAGESIZE n` exists (`CreateVertexTypeStatement.java:41-53`); only `CREATE INDEX` lacks it (`CreateIndexStatement.java:404`), so FULL_TEXT and GEOSPATIAL need `IndexBuilder.withPageSize`, HASH takes `HashIndexBucket.defaultPageSize` | 2.0, C9 |
| 8 | `arcadedb.fullText.topK` | Exists only in the partial-text worktree until the Q2 merge; marked so in the architecture (1.4, D28). This document never named the key | none |
| 9 | Already-default settings presented as changes | `queryLiteralParameterization=true`, `vectorIndex.maxConcurrentRebuilds=1`, `ha.readConsistency=read_your_writes`, `ha.autoAcquireDatabases=true`, `typeDefaultBuckets=1` marked "already the default"; the first and third are comments in 5.4 | C2, C4, C11, 5.4 |
| 10 | Hamming band indexes (`06-stylometry-perceptual-hash.md` 3.1, open question 4) | No corpus-wide band index: PDQ 8 bands cost 144 B per distinct image, 194 GB at mid 3x next to S-PDQ's 54 GB, pHash 2 bands 49 GB. PDQ bands only on the hot-window `media_<p>` and case databases (165M images = 24 GB today, 300M = 43 GB at the cap), radius-31 near-dup through the S-PDQ IVF space, corpus-wide pHash not stored. This document does not discuss near-duplicate lookup; the footprint change (mid 3x 4,142 GB, 92% of 4,520; second-drive trigger month 19.1) is in `01-architecture.md` 0.5 and 0.6 | none |
