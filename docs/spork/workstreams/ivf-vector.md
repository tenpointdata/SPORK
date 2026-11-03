# Workstream: `LSM_IVF_VECTOR` spike (2026-11-02)

Implements item 2 of `docs/spork/research/system-one-multimodal-index.md` section 5.6: a SPANN-style inverted-file dense
vector index whose per-vector state lives in LSM pages and replicates through the ordinary WAL/Raft page path, with only
the centroids resident on the heap. Status: **spike** - compiles, correctness tests pass in the `vector` lane, benchmark
numbers below. Nothing committed.

## What was built

| Piece | Where | Notes |
|---|---|---|
| Index type | `Schema.INDEX_TYPE.LSM_IVF_VECTOR`, registered in `LocalSchema` (index factory + `.ivfpost` component factory), `LocalDatabase.SUPPORTED_FILE_EXT` | wired into `CREATE INDEX ... LSM_IVF_VECTOR METADATA {...}`, `REBUILD INDEX`, Cypher `SHOW INDEXES`, the Postgres catalog |
| Metadata | `schema/LSMIVFVectorIndexMetadata` | `dimensions`, `similarity` (COSINE, DOT_PRODUCT, EUCLIDEAN, HAMMING), `nlist` or `targetPostingSize` (200), `scanCode` (BINARY, INT8; PRODUCT parsed, refused), `rerankCode` (INT8, NONE), `nprobe` (32), `rerankOversample` (10), `trainingSample` (100K), `kmeansIterations` (10); unknown keys refused like the other vector families |
| Builder | `schema/TypeLSMIVFVectorIndexBuilder`, `IndexBuilder.withIVFVectorType()`, `TypeIndexBuilder.withType()` | Java API mirror of the METADATA keys |
| Index | `index/ivfvector/LSMIVFVectorIndex` | `Index` + `IndexInternal` on an `LSMTreeIndex` shell (same pattern as `LSM_SPARSE_VECTOR`), postings in a sibling `IVFPostingsComponent` file `<index>_ivf` |
| Pages | `index/ivfvector/IVFPostingsComponent` | header page, thresholds, coarse + fine centroids, coarse ranges, directory (head/tail/count per list), posting pages (16 B header + fixed entries: live flag, position, scan code, rerank code) |
| Codes | `index/ivfvector/IVFCodec` | 1-bit codes in 64-bit words (Hamming = XOR + popcount per word), INT8 with per-vector scale (integer dot), all on primitive arrays and page `ByteBuffer`s, nothing allocated per entry. BINARY codes are SimHash: the sign of the dot with `dims` random Gaussian directions, rebuilt from a seed stored in the header page (HAMMING indexes use the raw bits) |
| Centroids | `index/ivfvector/IVFCentroids`, `IVFKMeans` | two-level k-means (`sqrt(k)` coarse groups, fine lists allocated per group in proportion to its sample share), assignment through the 2 nearest coarse groups, probe selection opens coarse groups until 16 x nprobe fine candidates are in view; the assignment pass of every Lloyd iteration fans out over the `QueryEngineManager` pool with the reclaiming `GraphAlgorithms.awaitFutures` (engine-concurrency skill: no new pool) |
| SQL | `vector.neighbors('Type[prop]', vec, k, {nprobe, rerank, filter, where, maxDistance})` | dispatched on the bucket index class in `SQLFunctionVectorNeighbors`; `groupBy` refused for IVF |
| Tests | `index/ivfvector/LSMIVFVectorIndexTest` (`@Tag("vector")`, 10 tests) | round trip + update, transaction visibility (same tx sees, other tx does not, rollback, commit), recall on 100K random 128-d and 100K clustered 512-d, allow-list 1% and 25%, reopen from disk, WAL replay after `kill()`, untrained index + `REBUILD INDEX`, SQL `CREATE INDEX` with METADATA, HAMMING over bit vectors |
| Benchmark | `index/ivfvector/LSMIVFVectorIndexBenchmark` (`@Tag("benchmark")`) | `-Divf.bench.rows=N`, run with `mvn -o -pl engine surefire:test -Dgroups=benchmark -DexcludedGroups= -Dsurefire.includes='**/ivfvector/*Benchmark.java' -Divf.bench.rows=5000000` |

### Write path (what makes it replicate)

`put()` runs on the writer's thread: it normalizes (COSINE), assigns the vector to its nearest fine centroid, codes it
(scan + rerank bytes) and queues an `IVFReplayKey(centroid, position, entry)` on the transaction's append-only index lane
(`isTransactionKeyOrderRequired() == false`, as the sparse index). At commit, `TransactionIndexContext` replays the lane
under the commit lock of the index files (`getFileIds()` returns the shell AND the postings file, so the lock covers both),
and `putReplay()` appends the entry to the tail page of that list through `tx.getPageToModify()` / `tx.addPage()`: the
pages are the committing transaction's, so they go into the WAL and onto the Raft page stream exactly like bucket pages.
A delete walks the vector's list (the old vector is handed to `remove()` by the indexer, so the list is recomputed, no
RID -> entry map) and clears the live flag. The header page keeps `totalEntries` / `liveEntries`, so `countEntries()` is
one page read.

The build is two bucket scans: pass 1 counts and reservoir-samples `trainingSample` vectors and trains the centroids,
whose pages (thresholds, coarse, fine, ranges, directory) are written in the build's transaction; pass 2 hands every
record to the indexer so it travels `put()` and is replayed at each batch commit. An index created on an empty type is
"untrained": one pseudo-centroid at the origin takes everything (searches degrade to a scan of that one list, still
correct); `REBUILD INDEX` trains it. In-transaction visibility reads the lane back (pending adds scored on their codes,
pending removes masked).

### Search path

1. `nprobe` lists from the resident centroids (`IVFCentroids.select`, two levels; brute force when nlist <= 256). An
   allow-list widens the probe by `min(16, live / allowed)`.
2. Scan: each list's pages, `getContent()` buffer, live flag, position, allow-list bitmap probe (`PositionFilter`:
   one bit per bucket position, falls back to a sorted array over 32 MB), Hamming or INT8 distance, bounded primitive
   max-heap of `k * rerankOversample`.
3. Rerank the heap on the INT8 code that sits in the same entry (the page is already hot). INT8 scan codes are their own
   rerank; BINARY + NONE answers Hamming / dims as a monotone proxy.
4. An allow-list at or below 1% of the live entries skips the lists altogether and scores the allowed records' stored
   vectors exactly (the `LSM_VECTOR` exact-prefilter crossover, see `VECTOR_INDEX_PREFILTER_MAX_SELECTIVITY`).
5. Distances match `LSM_VECTOR`'s shape: `1 - cos`, squared L2, `-dot`, bit count.

## Measured

Machine: Apple Silicon, 18 cores, 128 GB, default surefire heap (32 GB), page cache warm (every query repeated set, 200
queries after insert + build in the same JVM; no cold-NVMe numbers here). Corpus: 512-d, 5,000 Gaussian cluster centres,
spread 0.3, queries = stored vectors + N(0, 0.1) noise, k = 10, recall against brute-force cosine.

### Correctness-lane recall (100K vectors, `LSMIVFVectorIndexTest`)

| Corpus | Index | nprobe | recall@10 |
|---|---|---|---|
| clustered 512-d, 1,000 clusters | BINARY scan + INT8 rerank (10x), 500 lists | 32 | **0.928** (0.936 with per-dimension sign codes) |
| i.i.d. Gaussian 128-d | INT8 scan, 500 lists | 32 / 64 / 128 / 500 | 0.30 / 0.45 / 0.66 / 0.98 |
| i.i.d. Gaussian 128-d | BINARY scan + INT8 rerank 10x / 50x / 200x, all 500 lists | 500 | 0.23 / 0.48 / 0.76 |
| allow-list 1% of 20K (exact path) | BINARY + INT8 | 8 | 1.00 |
| allow-list 25% of 20K (scan path) | BINARY + INT8 | 8 | 0.97 |

The random corpus is the adversarial case for both halves of the design and is reported, not asserted, at nprobe 32:
with no cluster structure the nearest neighbours of a query sit in lists across the whole partition (recall at a fixed
nprobe is bounded by the share of lists probed), and sign bits carry little of the angle on i.i.d. coordinates. The
target of 0.9 at nprobe 32 is met on the clustered corpus (which is what embeddings look like) and is not attainable on
i.i.d. data by any IVF; the test pins the exact path instead (0.98 at an exhaustive probe).

### 1M x 512-d, LSM_VECTOR (BINARY, 16 buckets) vs LSM_IVF_VECTOR (BINARY scan + INT8 rerank, 1 bucket, 5,000 lists)

Run of 2026-11-03 07:15 (load average 12 at start, ~25-48 by the end: a game and three other JVMs share the box, so
treat latencies as +-30%; an earlier run at lower load gave graph p50 15.2 / p95 39.1 ms and IVF 1.32 / 2.23 ms, same
recalls). `nprobe = 32`, `rerankOversample = 10` unless stated. Both builds on the same corpus in the same JVM; the IVF
build is a single-threaded pass through the indexer, the graph build uses every core.

| Metric | LSM_VECTOR BINARY, 16 buckets | LSM_IVF_VECTOR BINARY+INT8 |
|---|---|---|
| build time (s) | 285.6 (268.6 earlier run) | 291.9 (146.8 earlier run; 1,943 s under load 70) |
| build peak heap above baseline (MB, upper bound: garbage included) | 7,954 | 5,325 (the 2 GB corpus array and 1M RIDs of the harness are in both) |
| index bytes on disk (MB) | 212 | 696 (589 B per entry: 1 + 8 + 64 scan + 516 rerank) |
| resident heap after reopen + first search (MB) | 424 | 37 |
| of which centroids (exact, `IVFCentroids.residentBytes()`) | - | 10.9 (5,000 x 512 x 4 B + a 512 x 512 SimHash projection) |
| unfiltered p50 / p95 latency (ms) | 10.9 / 61.5 | **1.9 / 3.0** (0.81 / 0.90 in the sweep right after, cache hot) |
| unfiltered recall@10 | 1.000 | 0.763 (0.932 at rerankOversample 50, same latency band: see sweep) |
| filtered 1% p50 / p95 (ms) | 15.4 / 20.7 (exact prefilter) | 17.6 / 25.0 (exact path: 10K record reads) |
| filtered 1% recall@10 | 0.942 | **1.000** |
| filtered 20% p50 / p95 (ms) | 57.1 / 66.5 | **22.2 / 25.4** (bitmap allow-list, probe widened x5 to 160 lists) |
| filtered 20% recall@10 | 1.000 | 0.952 |

IVF sweep on the same 1M index, unfiltered (where the recall is lost):

| rerankOversample | nprobe 32 | nprobe 64 | nprobe 128 | nprobe 256 |
|---|---|---|---|---|
| 10 (default) | 0.763, p50 0.81 ms | 0.763, 1.71 ms | 0.763, 4.23 ms | 0.763, 10.1 ms |
| 50 | 0.932, 1.85 ms | 0.932, 3.32 ms | 0.932, 5.85 ms | 0.932, 9.78 ms |
| 200 | 0.932, 3.34 ms | 0.932, 4.95 ms | 0.932, 5.63 ms | 0.932, 7.16 ms |

Reading: recall does not move with `nprobe`, so the partition finds the right lists at 32 of 5,000 (0.64%); what bounds
it is the candidate pool of the one-bit scan and, past 0.93, the INT8 rerank itself. This synthetic corpus is the hardest
case for both: every cluster is an isotropic Gaussian ball of 200 points of the same radius, so a query's 10 nearest
neighbours are the 10 closest of 200 interchangeable points at nearly the same angle (the 10th-nearest and the 50th differ
by a few percent of angle), which 512 sign bits cannot order and INT8 at 1/127 resolution barely can. Real embeddings have
local structure the codes resolve; the 100K clustered test with 100-point clusters gets 0.93 at 10x for the same reason the
1M run gets 0.93 at 50x: the pool covers the cluster. Switching the scan to SimHash random projections (now the default
for non-HAMMING indexes) moved 10x from 0.747 to 0.763 only, confirming the ceiling is geometric, not the threshold.
What follows for the design: ship `rerankOversample` as a per-query knob (it is, `{rerank: 50}`) and expect real data to
sit between the two rows; a 4-bit rerank code would lift the 0.93 ceiling at +1/4 of the INT8 bytes.

Latency: the IVF scan is 6,400 entries x 589 B = 3.8 MB of hot pages per query at nprobe 32 and runs in 0.8-1.9 ms single
threaded; the 16-bucket graph search is 16 sequential jvector walks. Filtered 20% on the graph costs the bits-filtered walk
(57 ms); on IVF it is 160 lists through a 125 KB bitmap (22 ms, of which most is the 5x wider scan - a parallel page read
would divide it).

### 5M x 512-d

IVF only (`-Divf.bench.skipGraph=true`, 100 queries): the graph side was skipped because its 16-bucket build at 5M is
~8 GB of build heap per million plus a ~40-minute build on a shared box. Run of 2026-11-03 08:05-08:58, load average
38-47 throughout, so latencies and build time carry the same +-30% caveat. Same corpus generator with the same 5,000
cluster centres, which matters for the recall below: each cluster now holds 1,000 points instead of 200.

| Metric | LSM_IVF_VECTOR BINARY+INT8, 25,000 lists |
|---|---|
| build time (s) | 2,909 (1.7K vectors/s under load; 3.4K at 1M on a quieter box) |
| build peak heap above baseline (MB, upper bound incl. garbage) | 12,597 |
| index bytes on disk (MB) | 4,063 (= 5M x 589 B + 23% page headers/slack/centroid pages) |
| resident heap after reopen + first search (MB) | 123 |
| of which centroids (exact) | **50.2** (25,000 x 512 x 4 B + projection) |
| unfiltered p50 / p95 (ms), nprobe 32, rerank 10x | **3.8 / 5.4** (1.2 / 1.5 in the sweep, cache hot) |
| unfiltered recall@10, rerank 10x / 50x / 200x | 0.381 / 0.792 / **0.914** (flat in nprobe 32-256) |
| p50 at rerank 200x, nprobe 32 | **2.1 ms** (p95 2.5) |
| filtered 1% p50 / p95 (ms), recall | 109 / 165, **1.000** (exact path: 50K record reads) |
| filtered 20% p50 / p95 (ms), recall | 95 / 103, 0.929 |

What 5M adds to the 1M picture:
- **Latency is flat in the corpus size**, as the design intends: 25,000 lists of ~200, 32 probed, same 6,400 entries
  scanned: 1.2 ms hot at 5M against 0.8 ms at 1M (the difference is centroid selection over 5x the lists).
- **Recall at a fixed rerank budget falls with cluster density**, and nprobe does not help: with 1,000 near-identical
  points per synthetic cluster the 10 true neighbours are 10 of 1,000 at nearly the same angle, and 100 one-bit
  survivors (10x) catch 38% of them, 500 catch 79%, 2,000 catch 91%. This is the property of 1-bit-scan-then-rerank on
  tight clusters, not of the partition. For a production default it argues for the rerank budget being absolute and
  generous (2,000 candidates cost 2.1 ms here) and for a better scan code (4-bit, or RaBitQ-style corrected 1-bit) on
  corpora with near-duplicate density, which is exactly the S-IMG case before PDQ clustering.
- **The exact filter path needs an absolute bound**, not the 1% ratio: at 5M, 1% is 50K record loads (109 ms), slower
  than the 20% scan path (95 ms). The crossover should be a record count (~5-10K) so it stays in the 10-20 ms band.
- Filtered 20% at 5M is 1M allowed positions in a 625 KB bitmap and a 5x widened probe (160 lists): 95 ms, dominated by
  scanning 32K entries serially; the parallel list read is the fix.

The 20M run of the brief was not attempted (shared box; the 5M run alone took 53 minutes). An earlier 5M attempt failed
twice for reasons outside the index: once on a harness `int` overflow (`float[ROWS * DIMS]`, fixed), once because the
database directory was removed under the running JVM at load average 101 (WAL `txlog_105.wal` not found, database
fenced). Not root-caused; the IVF code deletes nothing outside its own `dropFile`, and the retry with the same code
completed.

## Extrapolation to 300M and 900M vectors on a 126 GB node

Per-entry bytes are fixed by the metadata: `1 (live) + 8 (position) + scan + rerank`.

| Space | Entry | Entries / 64 KB page |
|---|---|---|
| 512-d, BINARY + INT8 | 9 + 64 + 516 = **589 B** | 111 (99.8% of the page) |
| 256-d (PCA, the research design), BINARY + INT8 | 9 + 32 + 260 = **301 B** | 217 |
| 256-d, BINARY + 4-bit rerank (not built) | 9 + 32 + 132 = 173 B | 378 |
| 256-bit PDQ, HAMMING, no rerank | 9 + 32 = 41 B | 1,598 |

Disk, postings only (centroid and directory pages are < 0.1% of it); the research doc's 30% boundary replication is
not implemented, so add it if SPANN-style replication is adopted:

| Vectors | 512-d B+INT8 | 256-d B+INT8 | 256-d B+4bit | PDQ |
|---|---|---|---|---|
| 300M | 177 GB | 90 GB | 52 GB | 12 GB |
| 900M | 530 GB | 271 GB | 156 GB | 37 GB |

Resident heap is the centroids alone (`IVFCentroids.residentBytes()`: fine + coarse + thresholds + the SimHash
projection, 4 B per float; the projection is `dims^2`, 1 MB at 512-d, constant). Checked against the measurement: 1M
vectors at 200 per list = 5,000 lists x 512 x 4 B = 9.8 MB + 1 MB projection = the 10.9 MB the benchmark reported.

| Vectors | lists at 200 / list | 512-d | 256-d | at 500 / list, 256-d |
|---|---|---|---|---|
| 1M (measured) | 5K | 10.9 MB | - | - |
| 300M | 1.5M | 3.1 GB | 1.5 GB | 0.6 GB |
| 900M | 4.5M | 9.2 GB | 4.6 GB | 1.8 GB |

For comparison, the measured `LSM_VECTOR` residency, 424 MB per 1M 512-d BINARY vectors after a reopen, scales
linearly with the vectors (location index + codes + graph): ~127 GB at 300M and ~380 GB at 900M, i.e. it does not fit a
126 GB node at either size, before counting its ~8 GB-per-million build peak. The IVF index keeps nothing per vector on
the heap, so 300M at 256-d fits in 1.5 GB and the 126 GB stay available to the page cache, which is what the latency
depends on. On a 126 GB node with `maxPageRAM` at, say, 64 GB, the 300M 256-d postings (90 GB) are ~70% cached and the
900M ones (271 GB) ~24%: the 300M case is mostly hot-cache latency, the 900M case is the cold-read row below.

Per query at nprobe 32 and 200 entries per list: 6,400 entries scanned, 58 pages of 589 B entries (30 pages at 256-d).
Hot cache, measured: 0.8-1.9 ms p50 at 1M; the scan cost depends on the list size and nprobe, not on the corpus size,
so the same figure holds at 300M and 900M once centroid selection is cheap. Cold NVMe at ~100 us a page read: ~6 ms
sequentially and ~1 ms if the 32 lists are read in parallel (not done in this spike). Centroid selection with the
two-level scheme at 1.5M lists is ~1,225 coarse + ~16 x 32 fine distance computations at 256-d (~0.5M flops, well under
1 ms); at 4.5M lists ~2,100 coarse. This is where the research doc's in-heap jvector graph over the centroids would go
(sub-ms at any size, and it would replace the two-nearest-coarse-groups assignment heuristic).

| Per leg at k = 10, nprobe 32 | 300M (256-d B+INT8) | 900M (256-d B+INT8) |
|---|---|---|
| postings on disk | 90 GB | 271 GB |
| heap | 1.5 GB | 4.6 GB |
| p50, postings in page cache | ~1-2 ms (measured at 1M) | same |
| p50, postings cold, serial reads | ~3-4 ms (30 pages) | same |
| p50, postings cold, 32-way parallel reads (to build) | ~0.5-1 ms | same |
| recall@10 on clustered data | depends on near-duplicate density, not on N: 0.91-0.93 with a ~2,000-candidate rerank on 200-1,000-point synthetic clusters (measured 1M, 5M); 10x is not enough as a default | same |

Build time: measured 292 s per million at 512-d (3.4K vectors/s), almost all of it the single-threaded indexer pass that
assigns and codes each vector; k-means on the 200K sample is parallel and a small share. At that rate 300M is ~24 h and
900M ~73 h, which is the first thing to fix before corpus scale: parallelising pass 2 on the query pool per batch (as the
k-means assignment already does) should give ~10-15x on 18 cores, i.e. ~2 h / ~6 h. Build heap is O(sample): the 200K x
512 sample is 400 MB, independent of the corpus, against `LSM_VECTOR`'s per-node build heap.

## Production-ready vs spike-quality

Production-shaped (would survive a review with small changes):
- Metadata, builders, SQL/Cypher/Postgres wiring, `IF NOT EXISTS` comparison, persisted definition round trip (the
  `Issue10751TypeRenameRekeysIndexMapTest` census covers rename + reopen of the new kind).
- Transactional write path: lane -> replay under the commit lock -> pages of the committing transaction; WAL replay
  after `kill()` tested; rollback tested; commit lock covers both files.
- Page layout and codecs: fixed-size entries, no per-entry allocation, bitmap allow-list.
- Exact path for selective filters, in-transaction visibility overlay.

Spike-quality (known gaps, in priority order for retrieval speed):
0. **Default rerank budget and exact-filter crossover are wrong for scale** (measured at 5M): `rerankOversample` 10 gives
   0.38 recall on dense clusters where 200 gives 0.91 for +0.9 ms; the exact-filter crossover is a 1% ratio that becomes
   50K record reads at 5M. Both should be absolute counts (~2,000 rerank candidates, ~5-10K exact records).
1. **Centroid selection and assignment are two-level brute force**, not the planned jvector graph over centroids. Fine
   up to a few hundred thousand lists; at 1.5M-4.5M lists it costs ~1 ms per query and makes the graph worth building.
2. **Posting lists are scanned serially**; a 32-way parallel page read (the vector workstream's `VectorSearchPool`
   pattern, under the engine-concurrency rules) is the single biggest cold-cache win.
3. **No list split/merge/reassign** (SPFresh). Lists only grow; a skewed insert stream makes some lists long and slow.
   `REBUILD INDEX` retrains; an in-place retrain writes new centroid/directory sections and leaves the old pages dead.
4. **Build pass 2 is single-threaded** through the indexer; the `LSM_VECTOR` comparison build uses every core.
5. **Deletes walk the list** to find the entry (O(list length) page reads per delete); fine at 200 entries, needs a
   per-list position index or a RID -> (page, slot) side map at 10x that.
6. **No PRODUCT scan code, no 4-bit rerank**; `rerankOversample` on BINARY + NONE answers a Hamming proxy, not a
   distance comparable across indexes.
7. **Untrained index**: one list until `REBUILD INDEX`; no automatic first training once enough vectors arrive.
8. Metrics are counters on the index (`getStats()`: searches, scannedEntries, centroids, residentBytes, postingPages);
   not wired into Micrometer.
9. Concurrent writers to the same list in two transactions take the ordinary page-version conflict and retry; nothing
   merges appends (the `GRAPH_EDGE_APPEND_MERGE` shape would apply).

## HA replication story

- **Postings replicate as pages.** Every entry is appended in the committing transaction's pages (postings component
  file registered through `LocalSchema.registerFile`, extension in `SUPPORTED_FILE_EXT`), so the WAL and the Raft page
  stream carry it with no index-specific code - the `.vecgraph`-not-replicated gap of `LSM_VECTOR` does not exist here.
  Followers apply raw page deltas and their `LSMIVFVectorIndex` instance reads the same pages.
- **Centroid training happens once, on the node that runs the build, and ships as pages.** The thresholds, coarse and
  fine centroids, coarse ranges and directory are written by `IVFPostingsComponent.writeLayout()` inside the build's
  transaction and committed with its first batch. A follower never trains: `LSMIVFVectorIndex.centroids()` compares the
  header page's `centroidVersion` with its resident copy on every search and write and reloads from the pages when a
  training it has not seen has landed, so a follower that opened the index before the leader built it picks the
  centroids up on its next search. The header is one cached page read per operation.
- **Not verified under Raft in this spike** (no `ha-raft` test written): the shell + sibling-file pattern is the one
  `LSM_SPARSE_VECTOR` already replicates with, and the sparse engine's "follower sees the file before its first pages
  arrive" case is handled the same way here (`readHeader()` answers null on an empty file and the index treats it as
  untrained until pages land).
- Assignment on a follower is never needed: followers do not run `put()`; the leader assigns, codes and the page says
  which list.
