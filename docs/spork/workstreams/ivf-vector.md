# Workstream: `LSM_IVF_VECTOR` spike (2026-11-02)

Implements item 2 of `docs/spork/research/system-one-multimodal-index.md` section 5.6: a SPANN-style inverted-file dense
vector index whose per-vector state lives in LSM pages and replicates through the ordinary WAL/Raft page path, with only
the centroids resident on the heap. Status: **spike + hardening pass** (2026-11-03, see [Hardening](#hardening) at the end:
scale-correct defaults, parallel posting reads, INT4 rerank code and an exact rerank tier, parallel build, centroid graph,
automatic training, position locator, Micrometer metrics, Raft test) - compiles, IVF tests green, benchmark numbers
below. Nothing committed.

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
| Tests | `index/ivfvector/LSMIVFVectorIndexTest` (10 tests at the spike, 22 now; untagged since round 7, its two 100K recall cases `@Tag("slow")`) | round trip + update, transaction visibility (same tx sees, other tx does not, rollback, commit), recall on 100K random 128-d and 100K clustered 512-d, allow-list 1% and 25%, reopen from disk, WAL replay after `kill()`, untrained index + `REBUILD INDEX`, SQL `CREATE INDEX` with METADATA, HAMMING over bit vectors |
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
| 256-d, BINARY + INT4 rerank (built in the hardening pass: residual + scale + correction) | 9 + 32 + 136 = 177 B | 370 |
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

**Correction (review finding 8).** Every figure below assumes the trained lists hold ~200 entries, i.e. 1.5M lists at 300M
and 4.5M at 900M. The spike did not deliver that: k-means cannot make more lists than it has sample points, and the sample
was capped at `trainingSample` (100K by default), so a 300M build made 100K lists of ~3,000 entries. A probe then scans
~15x what the tables assume (nprobe 32: ~96K entries per query instead of 6,400, 27+ pages per list at 512-d instead of 2),
and the latency rows below would be ~15x worse. The sample now grows to 30 vectors per list wanted within an eighth of the
heap, and a training that still makes fewer lists than wanted logs a WARNING. At 300M x 256-d the 45M-vector sample that
1.5M lists want is ~46 GB, more than an eighth of any realistic heap, so the tables stay a target, not a prediction: reaching
them needs a sample streamed from disk (or a hierarchical k-means trained group by group), which is not built. Until then a
300M build with a 32 GB heap gets ~4M sample points and ~130K lists (30 per list) of ~2,300 entries.

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

Spike-quality (known gaps, in priority order for retrieval speed; items 0, 1, 2, 4, 5, 6, 7 and 8 are addressed by the
[Hardening](#hardening) pass, 3 and 9 remain):
0. **Default rerank budget and exact-filter crossover are wrong for scale** (measured at 5M): `rerankOversample` 10 gives
   0.38 recall on dense clusters where 200 gives 0.91 for +0.9 ms; the exact-filter crossover is a 1% ratio that becomes
   50K record reads at 5M. Both should be absolute counts (~2,000 rerank candidates, ~5-10K exact records).
1. **Centroid selection and assignment are two-level brute force**, not the planned jvector graph over centroids. Fine
   up to a few hundred thousand lists; at 1.5M-4.5M lists it costs ~1 ms per query and makes the graph worth building.
2. **Posting lists are scanned serially**; a 32-way parallel page read (the vector workstream's `VectorSearchPool`
   pattern, under the engine-concurrency rules) is the single biggest cold-cache win.
3. **No list split/merge/reassign** (SPFresh). Lists only grow; a skewed insert stream makes some lists long and slow.
   `REBUILD INDEX` retrains; an in-place retrain writes new centroid/directory sections and leaves the old pages dead.
   Since review round 2 an index whose `nlist` is sized automatically also trains itself again each time it grows 4x
   (see "Review findings and fixes, round 2"), which keeps the average list near `targetPostingSize` but not a skewed one.
4. **Build pass 2 is single-threaded** through the indexer; the `LSM_VECTOR` comparison build uses every core.
5. **Deletes walk the list** to find the entry (O(list length) page reads per delete); fine at 200 entries, needs a
   per-list position index or a RID -> (page, slot) side map at 10x that.
6. **No PRODUCT scan code, no 4-bit rerank**; `rerankOversample` on BINARY + NONE answers a Hamming proxy, not a
   distance comparable across indexes.
7. **Untrained index**: one list until `REBUILD INDEX`; no automatic first training once enough vectors arrive.
8. Metrics are counters on the index (`getStats()`: searches, scannedEntries, centroids, residentBytes, postingPages);
   not wired into Micrometer.
9. Concurrent writers to the same list in two transactions take the ordinary page-version conflict and retry; nothing
   merges appends (the `GRAPH_EDGE_APPEND_MERGE` shape would apply). (Superseded: the appends run in the commit replay,
   under the commit lock, on the pages as committed, so under READ_COMMITTED two writers never conflict on the index. Under
   REPEATABLE_READ this understated the problem until review round 5: every pair of overlapping writers to the index
   conflicted, whatever list they touched, because a write pinned header page 0 in its body. See Round 5.)

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
- **Verified under Raft by the hardening pass** (`ha-raft` `LSMIVFVectorIndexHATest`, see [Hardening](#hardening)); before it:
  the shell + sibling-file pattern is the one
  `LSM_SPARSE_VECTOR` already replicates with, and the sparse engine's "follower sees the file before its first pages
  arrive" case is handled the same way here (`readHeader()` answers null on an empty file and the index treats it as
  untrained until pages land).
- A follower never assigns for the leader's writes: those arrive as pages, and the page says which list. A follower's OWN
  write does run `put()` and its commit replay on the follower (`TransactionContext.commit1stPhase`, #5503/#6964), and ships
  the pages that replay wrote; the leader applies them as pages (`TransactionManager.applyChangesInternal`), with no replay
  of its own. What that means for a build that runs on the leader meanwhile is in [Round 6](#round-6-2026-11-03).

## Hardening

Pass of 2026-11-03 on the nine-item list of the owner (retrieval speed first; 300M-900M vectors per 126 GB node, fully
replicated). Items 1-6, 8 and 9 are implemented and tested; item 7 (posting-list split) is designed below, not built. Still
nothing committed.

### What changed

| # | Item | Where | Tests |
|---|---|---|---|
| 1 | **Scale-correct defaults.** The default rerank budget is absolute: `max(k * rerankOversample, rerankCandidates)`, `rerankCandidates` 2,000 (metadata key); a query that names `{rerank: n}` still gets exactly `k * n`. The exact-filter crossover is an absolute count, `exactFilterThreshold` 5,000 allowed records (was 1% of the live entries). Under an allow-list the budget also covers the allowed entries the widened probe is expected to meet (`2 * probes * allowed / nlist`), replacing the old "every allowed record" rule that sized a 100K-slot heap at 20% of 5M | `LSMIVFVectorIndexMetadata`, `LSMIVFVectorIndex.candidateBudget` | `defaultRerankBudgetIsAnAbsoluteCandidateCount`, `exactFilterCrossoverIsAnAbsoluteCount` |
| 2 | **Parallel posting reads.** The `nprobe` lists of one query are pulled off a shared counter by `min(pool width, lists / 2)` tasks on the `QueryEngineManager` pool (engine-concurrency skill: no new pool), the caller running one share; each task fills its own growable primitive max-heap and the heaps are merged into the largest; the wait is the reclaiming `GraphAlgorithms.awaitFutures`. Workers read the page cache's shared page view (`PageManager.getSharedImmutablePage`, absolute reads only), which is exactly what the caller reads under READ_COMMITTED while its transaction holds no page it wrote, so the parallel path runs only then (REPEATABLE_READ or a transaction with modified pages stays serial). A rerank of 512+ survivors is split the same way. Up to two lists stay on the caller. The serial path also moved to `getPageForAbsoluteReads` (no page copy per read) | `scanParallel`, `rerankAll`, `canReadOffThread` | `parallelPostingReadsAnswerTheSerialAnswer` (same answer entry for entry, INT8 and INT4, filtered and not; single list and dirty transaction stay serial) |
| 2b | **Blocked-bitmap allow-list.** Record positions are `bucketPage * 2048 + slot`, so a corpus of large records numbers them sparsely (5M x 2 KB reaches position ~340M) and the spike's flat bitmap (capped at 32 MB) fell back to sorting the allow-list and binary-searching it per scanned entry. Now one int per block of 512 positions points to a 64-byte bit block holding them (only blocks with an allowed position exist): no sort, two array reads per probe, ~13 MB for 1M allowed positions at 5M instead of 42 MB flat. The sorted array is left for positions past 32G | `PositionFilter` | `blockedAndSortedAllowListsAgree` (same answers as the sorted array, far sparse positions included) |
| 3 | **INT4 rerank code + exact rerank tier.** `rerankCode: 'INT4'`: 4-bit residual against the entry's list centroid, `ceil(d/2) + 8` bytes (scale + EUCLIDEAN correction), scored asymmetrically (float query, per-list constant computed once per probed list). `exactRerank` (metadata, and a `findNeighbors` argument): the best `max(k, n)` reranked candidates are scored again on their stored vectors, one record read each. INT8 scan + INT4 rerank is refused (the INT8 scan is its own rerank) | `IVFCodec.int4*`, `EntryLayout`, `findNeighbors` | `int4RerankCodeAndExactRerankTier`, `int4RerankUnderEverySimilarity` (distances within quantization of the exact ones for COSINE, DOT_PRODUCT, EUCLIDEAN) |
| 4 | **Parallel build pass 2.** The bucket scan fills batches of 8,192 (position, vector); assignment and coding of a batch fan out over the query pool (`forRange`, reclaiming wait), the appends run on the build's thread in scan order so the batch commits and the WAL are unchanged. The build no longer goes through the indexer (review round 4 put the appends back on the transaction lane, staged by the build's thread, coding still parallel: see Round 4) | `trainAndPopulate`, `populate`, `appendBatch` | `parallelBuildMatchesTheSerialBuild` (same entries, same answers as `setParallelBuild(false)`) |
| 5 | **Centroid graph.** From `IVFCentroids.graphMinLists` (20,000) lists a jvector graph over the fine centroids (M 16, beam 100, EUCLIDEAN, search beam `max(64, 4 * nprobe)`) replaces the two levels for `select` and `assign`. Built synchronously by a build before pass 2, in the background (one daemon thread driving a transient `ForkJoinPool` of half the cores, shut down after; jvector requires a FJP, never the common pool) after a reopen or a replicated training, the two levels serving until it is published. Searchers come from a `GraphSearcherPool`. Not persisted | `IVFCentroidGraph`, `IVFCentroids` | `centroidGraphSelectsTheProbeSet` (built by the build, rebuilt after reopen, recall 0.96 at nprobe 16); `IVFCentroidSelectorBenchmark` |
| 6 | **Automatic training.** An untrained index registers an after-commit callback on every write; once `autoTrainThreshold` (default 10,000; 0 = never) live vectors are committed, the committing thread runs one transaction of its own that samples, trains and re-populates every list from the records, publishing the centroid, directory, posting and locator pages together. A concurrent writer's commit conflicts with it on the header page (every append bumps the counters), so the training retries (3 attempts, then the next write re-arms it). Replay keys now carry the centroid version and the prepared vector: a key coded against the untrained centroid that commits after the training is assigned and coded again at replay (`replayRecodes`) | `scheduleAutoTrain`, `maybeAutoTrain`, `applyAdd`, `IVFReplayKey` | `untrainedIndexTrainsItselfAtTheThreshold` (threshold crossed by another thread while a transaction holds an untrained-coded write; recode counted; reopen), HA test below |
| 8 | **Position locator + metrics.** A linear-hash table of record position to (posting page, entry index) in pages of the postings file (`IVFLocator`): 16-byte entries, 0.75 load, one bucket split at a time, overflow chains, directory as a chain of pages. Positions are `bucketPage * 2048 + slot`, sparse for large records, hence a hash and not an array. Every append maps its position (clearing a live entry the position already had: a position has at most one live entry), a delete clears through it in a few page reads and falls back to the list walk only for a position it does not know. Micrometer: the engine counts JVM-wide in `IVFVectorIndexMetrics` (no Micrometer dependency in the engine), the server exports `arcadedb.vector.ivf.*` FunctionCounters (`IVFVectorIndexMetricsBinder`, bound next to `EngineMetricsBinder`): searches, scanned entries, reranked candidates, parallel searches, exact-filter searches, exact reranks, locator/walked deletes, trainings, auto trainings, auto retrainings (round 4), replay recodes, built vectors. Per index the same in `getStats()` plus `centroidGraph`, `trained` | `IVFLocator`, `IVFPostingsComponent.append/markDeleted`, `IVFVectorIndexMetrics`, server `IVFVectorIndexMetricsBinder` | `deletesUseTheLocator` (1,100 deletes/updates, 0 walks), `locatorAgreesWithAMap` (40K positions, many splits, 10K removes, replace), `IVFVectorIndexMetricsBinderTest` |
| 9 | **Raft test.** `ha-raft` `LSMIVFVectorIndexHATest` (`BaseRaftHATest`, 3 nodes, fixture ports): index built on the leader, every node trained with the same 10 lists, 2,000 entries, finds each sampled record by its own vector through `vector.neighbors`; a delete on the leader leaves every follower's count and answer; an index created empty trains itself on the leader at 500 vectors and every follower reads the training (6 lists, 600 entries) and searches it | | 2 tests, green |

Also: `hardeningSettingsRoundTripThroughSqlAndReopen` (the new METADATA keys `rerankCandidates`, `exactFilterThreshold`,
`autoTrainThreshold`, `exactRerank`, `rerankCode: 'INT4'` through `CREATE INDEX` and a reopen). All 22 `LSMIVFVectorIndexTest`
cases are green (the 10 of the spike unchanged), as are `Issue5639IndexMetadataKeysTest`, `Issue10751TypeRenameRekeysIndexMapTest`,
`EngineMetricsBinderTest`. Run with `mvn -o -pl engine test -Dtest='LSMIVFVectorIndexTest,LSMIVFVectorIndexReviewTest'`,
`mvn -o -pl ha-raft -am verify -Dtest=LSMIVFVectorIndexHATest -Dsurefire.failIfNoSpecifiedTests=false -DskipITs` (untagged since
round 7, so CI runs it in the `modules` shard of `unit-tests`).

### Measured: 1M x 512-d, A/B in one run

`LSMIVFVectorIndexBenchmark#hardeningAB` (`-Divf.bench.hardening=true`), same corpus generator and machine as above, 200
queries, `nprobe` 32, k 10. Load average 20-35 during the run (other worktrees building), which is why every latency pair is
interleaved query by query (serial and parallel alternate which goes first) and every build is an A/B/A on the same records.
The benchmark ran from a copy of the compiled classes so the worktree could keep compiling.

| Build (1M x 512, BINARY + INT8, 5,000 lists) | time (s) | vectors/s |
|---|---|---|
| parallel pass 2 (create) | 109.2 | 9,156 |
| serial pass 2 (rebuild) | 364.7 | 2,742 |
| parallel pass 2 (rebuild) | 72.2 | **13,851** |

Parallel pass 2 is 3.4-5x the serial one, not the 10-15x hoped for: what is left serial is the bucket scan and record
deserialization and the page appends (with their locator update) on the build's thread. At 13.9K vectors/s a 300M build is
~6 h and 900M ~18 h (was ~24 h / ~73 h); the next step is per-list append batching (one page modification per page, not per
entry) and a parallel record decode.

| Search (alternating per query) | serial p50 / p95 ms | parallel p50 / p95 ms | recall@10 |
|---|---|---|---|
| unfiltered, default budget (2,000 candidates) | 3.12 / 3.95 | **1.78 / 2.97** | **0.932** |
| unfiltered, rerank 10 x k (the spike's default) | 1.23 / 1.52 | 0.75 / 0.82 | 0.763 |
| unfiltered, default budget + exactRerank 20 | 2.79 / 3.18 | **1.57 / 1.76** | **1.000** |
| unfiltered, default budget + exactRerank 50 | 2.61 / 2.93 | 1.52 / 1.67 | 1.000 |
| filtered, 4,000 records (exact path) | 8.18 / 10.46 | 8.17 / 10.29 (no lists read) | 1.000 |
| filtered 1% (10,000 records, now the scan path) | 16.40 / 24.33 | **5.87 / 14.69** | 0.439 |
| filtered 20% | 21.42 / 25.64 | **11.24 / 13.01** | 0.952 |

| Rerank code, same 1M corpus | entry bytes | index MB | p50 ms | recall@10 | + exactRerank 50: p50 ms / recall |
|---|---|---|---|---|---|
| BINARY + INT8 | 589 | 725 | 3.05 | 0.932 | 3.51 / 1.000 |
| BINARY + INT4 | **337** (-43%) | 563 | 2.98 | 0.630 | 3.74 / **0.963** |

(The rerank-code rows are not an A/B pair, they ran one after the other at a higher load than the rows above: compare their
recalls and bytes, not their latencies.)

Reading:
- **The default budget moves recall from 0.76 to 0.93 at 1M** for +1 ms parallel; the parallel read pays for most of it
  (1.78 ms parallel against 3.12 serial). The 0.93 is the INT8 ceiling the spike measured.
- **The exact tier is the real lift**: rescoring the best 20 reranked candidates on their stored float vectors gives 1.000,
  and on this hot cache costs nothing measurable (the p50 is lower than without it because it ran after the first row warmed
  the pages; 20 record reads are ~0.1 ms hot). On cold storage it is 20-50 random record reads per query (~0.2-0.5 ms with
  parallel NVMe reads, ~2-5 ms serially): worth turning on (`exactRerank: 20`) where the vectors are stored with the records,
  which is why it stays opt-in (default 0) rather than default: S-IMG and S-TEXT store no vector (section 5.2 of the research
  doc).
- **INT4 does not lift the INT8 ceiling, it trades recall for bytes.** Sixteen levels of the residual are coarser than 255
  levels of the vector on these clusters (residual ~29% of the vector norm, so the INT4 step is ~5x the INT8 one): 0.63 alone.
  With the exact tier it reaches 0.96 at 43% fewer bytes per entry (177 B instead of 301 B at 256-d). The brief's "lift the
  ceiling at a quarter of the bytes" is met by INT4 + `exactRerank`, not by INT4 alone.
- **Parallel reads cut the filtered paths 2-3x** (20%: 21 ms to 11 ms), where the widened probe reads 5x the lists.
- **Filtered 1% recall 0.44 is the synthetic corpus, not the scan.** With 10,000 allowed records an allow-list now takes the
  scan path (the old ratio sent it to the exact path: 1.000 at 17.6 ms). A random 1% filter leaves ~2 allowed points in the
  query's own cluster, and the other 8 true neighbours are the nearest of 9,998 allowed points in 5,000 clusters whose centres
  are i.i.d. Gaussian in 512-d, i.e. all at the same distance: the "true" 3rd-10th neighbours are ties no partition can
  localize (20% holds 40 allowed points per cluster and gets 0.95). On data with neighbourhood structure the widened probe
  finds them; on data like this, raise `exactFilterThreshold` per index (10,000 records cost ~20 ms on the exact path here,
  against 5.9 ms on the scan).

### Measured: 5M x 512-d, IVF only, A/B in one run

Same benchmark, `-Divf.bench.rows=5000000 -Divf.bench.queries=100 -Divf.bench.skipBuildAB=true -Divf.bench.skipInt4=true`,
25,000 lists, clusters of 1,000 points as in the spike's 5M run. **Load average 60-85 throughout** (several other worktrees
building on the 18 cores), so the absolute latencies are inflated well beyond the 1M run's and the parallel gain is
compressed (there were few idle cores to fan out to); the serial/parallel pairs are still interleaved query by query.

| Search (alternating per query) | serial p50 / p95 ms | parallel p50 / p95 ms | recall@10 |
|---|---|---|---|
| unfiltered, default budget (2,000 candidates) | 5.93 / 8.83 | 4.42 / 6.26 | **0.914** |
| unfiltered, rerank 10 x k (the spike's default) | 3.67 / 4.94 | 3.31 / 4.06 | 0.385 |
| unfiltered, default budget + exactRerank 20 | 5.83 / 7.91 | 5.19 / 7.55 | **0.999** |
| unfiltered, default budget + exactRerank 50 | 6.32 / 8.56 | 5.79 / 7.97 | 1.000 |
| filtered, 4,000 records (exact path) | 8.45 / 12.24 | 8.55 / 13.31 | 1.000 |
| filtered 1% (50,000 records, scan path; the spike sent it to the exact path: 109 ms) | 45.2 / 62.6 | **19.3 / 26.6** | 0.989 |
| filtered 20% | 131 / 207 | 110 / 148 | 0.929 |

- **Default recall at 5M is 0.914** (the spike's default gave 0.38 on the same corpus): the target of ~0.9 by default is met,
  and `exactRerank: 20` takes it to 0.999.
- **The exact-filter crossover fix is visible**: a 1% filter at 5M went from 109 ms (50K record reads) to 19 ms with recall
  0.989 (each 1,000-point cluster holds ~10 allowed points, so the widened probe finds them; compare the 1M 1% row).
- The build of the 5M index took 1,433 s in this run (3,490 vectors/s, timed from the postings file's creation and last
  write: the run logged no build time, the benchmark now does) at load 60-85, and **829 s (6,030 vectors/s)** in the run
  below, against the spike's 2,909 s serial build at load 38-47.
- The postings file is 4,227 MB against the spike's 4,063 MB: the 164 MB difference is the locator (~21 B per entry plus
  bucket slack: ~34 B per entry measured).
- **Filtered 20% stayed at ~110 ms**, and the profile says why: at 5M the record positions reach ~340M (30 records of 2 KB
  in each 2,048-position bucket page), past the spike's 32 MB flat-bitmap limit, so the allow-list fell back to a sorted
  array: every query sorted its 1M positions and binary-searched them once per scanned entry. That is what the blocked
  bitmap below replaces.

Second 5M run, the blocked bitmap against the sorted array it replaces, interleaved query by query on the same index (parallel
scan, `-Divf.bench.filterOnly=true`; build 829 s, index 4,228 MB):

| Allow-list probe (5M) | p50 / p95 ms | recall@10 |
|---|---|---|
| filtered 1% (50,000 records), sorted array (spike) | 19.8 / 54.9 | 0.991 |
| filtered 1%, **blocked bitmap** | **13.3 / 37.1** | 0.991 |
| filtered 20% (1M records), sorted array (spike) | 126.1 / 154.6 | 0.929 |
| filtered 20%, **blocked bitmap** | **64.0 / 83.5** | 0.929 |

What is left of the 20% case is building the filter itself from a `Set<RID>` of 1M boxed RIDs per query (an iteration of the
set, plus the 5x widened scan); the research doc's Roaring bitmap of ordinals handed in by the caller would remove it.

### Measured: centroid selection at 50K and 200K lists (item 5)

`IVFCentroidSelectorBenchmark`, 256-d centroids drawn around 2,000 regions, 500 queries, two-level and graph alternating per
query, "overlap" = share of the exact `nprobe` nearest centroids the selector returned:

| lists | nprobe | two-level p50 / p95 ms, overlap | graph, beam 2 x nprobe | graph, beam 4 x nprobe (default) | full brute force p50 |
|---|---|---|---|---|---|
| 50,000 | 32 | 0.56 / 0.74, 0.849 | 0.12 / 0.17, 0.948 | **0.21 / 0.27, 0.981** | 8.8 ms |
| 50,000 | 64 | 0.89 / 1.29, 0.552 | 0.38 / 0.51, 0.927 | **0.79 / 0.95, 0.964** | 14.7 ms |
| 200,000 | 32 | 1.63-2.11 / 2.3-3.2, 0.999 | 0.12 / 0.27, 0.877 | **0.25 / 0.45, 0.960** | 68 ms |
| 200,000 | 64 | 1.96-2.41 / 3.3-3.9, 0.999 | 0.30 / 0.58, 0.960 | **0.68 / 1.00, 0.986** | 66 ms |

Graph build (in-heap, 18-core FJP in the benchmark, half that in the index): 2.5 s at 50K, 7.2 s at 200K lists; two-level
training of the same sets 1.1 s / 12.7 s. The graph wins on latency everywhere at 50K+ (3-8x), and on overlap at 50K; at
200K the two levels open so many groups that their overlap is near-exact, at 2 ms per query, which is the cost the graph
removes. So the graph is the default from 20,000 lists; below that the two levels stay (a 5,000-list index selects in
~0.1 ms either way). Extrapolated to 1.5M lists (300M vectors): two-level selection would be ~5-15 ms per query and the
graph stays ~0.3 ms; the in-heap graph costs a second copy of the fine centroids plus ~150 B per list (1.5M x 256-d:
~1.8 GB on top of the 1.5 GB of centroids) and a build of minutes on every node after a training or a restart.

### What remains

1. **Posting-list split (item 7), designed, not built.** It is more than a third of what was left of this pass, because the
   expensive part is not the split itself but making a one-centroid change cheap for every node to absorb. Design:
   - *Trigger*: `IVFPostingsComponent.append` sees the directory count of list L pass `splitThreshold` (metadata, default
     `4 * targetPostingSize`) and the replay registers an after-commit callback (the auto-training shape), leader only.
   - *Split transaction* (its own, retried like the training): read L's live positions (through its pages), load their stored
     vectors (records), 2-means them into c1, c2. c1 overwrites L's row in the fine-centroid section in place (one page
     write); c2 is appended as list `nlist` in a new **centroid extension** section (a chain of pages: row + coarse group id)
     and gets a directory slot in an extension directory. L's entries are rewritten into two fresh page chains (scan codes
     copied, INT4 residuals recoded against the new centroids, INT8 copied), the locator remapped for every moved entry, the
     old pages left dead. SPFresh's LIRE reassignment (move entries of the few lists nearest L that are now closer to c1/c2)
     is a second step on the same machinery.
   - *Readers*: the header gains a `layoutEpoch` separate from the centroid `version`, and a small changed-rows log (list
     ids rewritten since version V). A node whose resident centroids are at (V, epoch e) reloads only the logged rows and the
     extension rows past its count, instead of the whole set (1.5 GB at 300M; a full reload per split is not an option). The
     two-level selector scans extension rows brute force (bounded: a periodic re-training folds them in); the centroid graph
     takes them with `GraphIndexBuilder.addGraphNode` on a kept-mutable builder.
   - *Writers*: a directory entry gains a 4-byte per-list split epoch; a replay key coded against an older epoch of its list
     is recoded from its vector (the auto-training mechanism, per list instead of global).
   - *Merge*: a list under `targetPostingSize / 4` live entries is folded into its nearest neighbour by the reverse path.
   - Estimated 600-800 lines plus tests (split under concurrent writers, follower catch-up by delta, recode on epoch).
2. **Persist the centroid graph** as pages (`OnDiskGraphIndex` over the postings file) so a 1.5M-list graph is not rebuilt on
   every node after every restart, and so it replicates instead of being recomputed.
3. **Build throughput past 14K vectors/s**: per-list batched appends and parallel record decode (see the build table).
4. **Exact rerank under cold storage**: the tier reads records serially on the caller; reading them on the query pool (they
   need the caller's transaction view, so through shared pages like the posting reads) is the remaining cold-cache lever.
5. A deleted
   entry's bytes stay in its posting page until a rebuild (no compaction of dead entries; the locator makes the dead entry
   unreachable but not reclaimed).
6. The auto-training now runs on the query pool (review finding 3), and still re-populates in one transaction: at the 10,000
   default that is ~10K entries of posting and locator pages in one commit, but a threshold in the millions, or a first
   commit that crosses the threshold with a bulk transaction, still builds one large transaction (see the review section).
7. **Allow-lists as ordinals**: `findNeighbors` still takes a `Set<RID>` and builds its bitmap per query (most of the 64 ms of
   a 20% filter at 5M); taking a prebuilt bitmap from the caller (`preFilterPlan`, section 5.6 item 3 of the research doc) is
   the remaining filtered-latency lever.
8. Diagnostic switches added for the A/B runs (`setParallelScan` per index, `setParallelBuild`, `setSortedFilter` and,
   since review round 4, `setStagedBuild` JVM-wide) are not configuration; they could become `GlobalConfiguration` entries or be removed once the numbers are
   accepted.

## Review findings and fixes

An independent adversarial review of the index (2026-11-03) reported one blocker, three majors and four minors. Every
blocker and major is fixed with a regression test that fails without the fix; the minors are fixed too. The tests are in
`index/ivfvector/LSMIVFVectorIndexReviewTest` (11 tests in round 1, 27 now; untagged since round 7, the two live-ingest cases added in rounds 4 and 5 `@Tag("slow")`). Each one was checked against a mutation of the
fix it pins (the fix reverted in place, the test run, the fix restored): all eleven go red without their fix.

Readers overlapping a publication are staged with a test-only page-read observer on `IVFPostingsComponent` (it answers, once,
the page a reader would meet in the window: a page past the file's end, which reads as zeros, or the header as read just before
a training) and `engine.PublicationTestSupport`, which moves `PageManager.getPublicationSequence()` the way a commit does.

| # | Severity | Finding | Fix | Regression test |
|---|---|---|---|---|
| 1 | blocker | (the publication-sequence window and the locked read of this fix were replaced in round 2, below) A commit publishes its modified pages (header, directory, the tail page now linking a new page) before its new pages, and a READ_COMMITTED reader asking for a page past the file gets a zeroed page. A scan followed `P_NEXT` into it, then to page 0 (the header) read as a posting page: an endless loop, an exception, or foreign entries reranked with the wrong list constant. A centroid load in the same window read zero rows and cached them as current until the next training. `findNeighbors` also read the centroids and the header apart, so a training between them made the old list ids index the new directory | `findNeighbors` and the committed centroid load run inside a window no publication overlapped: the sequence sampled even before the first read and found unchanged after the last, read again otherwise, under the publication lock after 8 attempts (the #9369 / #10941 pattern of `ConsistentKeyLookup`). Cheap guards turn a torn read into a retry instead of a walk: a link to page 0, to a negative page or to a page at or past the file's page count is never followed, a page whose `P_CENTROID` is not the list being scanned or whose count does not fit a page stops the list, a list longer than the file stops, a header whose version differs from the centroids' retries, and a centroid load whose sections reach past the file or whose coarse starts do not frame the fine rows retries. A REPEATABLE_READ retry releases the postings pages it pinned (`unpinFiles`) | `aScanThatMeetsAPageStillBeingPublishedReadsTheSearchAgain`, `aTrainingBetweenTheCentroidsAndTheHeaderIsReadAgain`, `aCentroidLoadThatOverlappedAPublicationIsReadAgain` |
| 2 | major | An update inside a transaction leaves REMOVE(p) then ADD(p) on the lane; the overlay unmasked p, so the committed entry (old vector) was scored and the pending entry (new vector) merged too: the record came back twice, first at distance ~0 for its old vector. Same on the exact-filter path | The scan, and the exact-filter path, skip every position the transaction removed or wrote again (`Overlay.masks`); the overlay merge alone scores the pending entry | `anUpdateInsideTheTransactionAnswersTheRecordOnceForItsNewVector` |
| 3 | major | The automatic training re-populated in one transaction that every concurrent commit beat on header page 0; under a steady stream of writers it never won, re-armed on every commit, and each failed round cost the writer that ran it up to three O(N) re-populations | The training runs on the query pool (engine-concurrency skill: an existing pool, caller-runs on saturation, which degrades to the old writer-thread behaviour), trains once outside any transaction (pass 1 and k-means), then re-populates up to three times: two optimistic attempts with a doubling pause (50 ms, 100 ms), then one that takes an explicit lock on the bucket and its indexes before any read, so writers wait at their commit for the re-population of `autoTrainThreshold` entries and it cannot lose. A training that still fails is not re-armed until the untrained list has doubled. The attempts begin and commit through `getWrappedDatabaseInstance()`, as `database.transaction()` does, so under HA the layout still ships to the replicas from a pool thread | `theAutomaticTrainingWinsAgainstASteadyStreamOfWriters` (4 writers committing one record each, without pause); `untrainedIndexTrainsItselfAtTheThreshold` and the HA test now wait for the background training |
| 4 | major | `centroids()` was an unsynchronized check, load, publish: after a training or a reopen every concurrent search loaded its own copy and started its own graph build (K x 1.5 GB at the design's scale), and a REPEATABLE_READ transaction pinned at the older version wrote it back into the shared field, so it and READ_COMMITTED callers flipped the field on every search | One loader at a time (`centroidLoadLock`), version re-checked under it; the field never goes back to an older version; the set it replaces is kept as `previousCentroids`, which is what a REPEATABLE_READ transaction pinned before the training reads; a set only the caller's transaction sees is read privately and never published; only the published set starts a graph build. Centroid pages are now read once per page, not once per row | `concurrentSearchesLoadTheCentroidsOnce`, `aRepeatableReadSearchOnAnOlderLayoutDoesNotDowngradeTheResidentCentroids` |
| 5 | minor | `trainAndPopulate` put the trained set in the shared field before its transaction committed, numbered `centroids().version + 1`: a retried training reused the number with a different set, and a reader could pair one set with the other's header | The version is the committed header's plus one, read in the training's transaction (so committed versions are unique: a concurrent training conflicts on the header); the set is attached to the transaction (`TransactionContext.setAttachment`) for its own later writes and published by an after-commit callback | `aRolledBackTrainingLeavesTheResidentCentroidsAlone` |
| 6 | minor | With `{rerank: 0}` on BINARY + INT8 (or INT4), pending entries were scored on the exact distance and committed ones on the Hamming proxy, then sorted together | The overlay merge scores a pending entry on the scale of this query's committed entries: exact when the query reranks, rescores exactly or scans INT8, the Hamming proxy of its scan code otherwise | `aPendingEntryIsScoredOnTheScaleOfTheCommittedOnes` |
| 7 | minor | In a logical (multi-type) index, the first `LSM_IVF_VECTOR` member skipped the vector-type check of every later member and dropped the `LSM_VECTOR` members collected so far | Every member is checked; a logical index that mixes `LSM_VECTOR` and `LSM_IVF_VECTOR` members is refused with a message (their distances are on different scales, so no merge) | `aLogicalIndexMixingIndexKindsIsRefused` |
| 8 | minor | The lists were capped at the training sample (`min(k, sample)`): 100K lists of ~3,000 entries at 300M instead of 1.5M of 200 | The reservoir is `max(trainingSample, min(30 x lists wanted, heap / 8 / row bytes, records))`, the lists wanted estimated from the bucket's record count; a training with fewer lists than wanted logs a WARNING; the extrapolation above is corrected | `theTrainingSampleGrowsWithTheListsWanted` |

What the fixes cost and what they leave:
- Per search: two volatile reads of the publication sequence, one page-count read, and per posting page two int compares;
  in a transaction with pending writes, one boxed map probe per scanned entry (the overlay only exists then). Not
  benchmarked: no A/B switch exists for the check, and the added work is below the noise of the scan it guards. A torn read
  costs one re-read of the search (`retriedSearches` in `getStats()`).
- The locked fallback (`PageManager.executeInLock`) does not exclude a replicated apply, which publishes without that lock
  (#10941); on a follower the retries rely on the sequence alone, as `ConsistentKeyLookup` does.
- A REPEATABLE_READ search that overlapped a publication releases its pinned postings pages, so its snapshot of the index
  moves forward (the same trade `ConsistentKeyLookup` makes).
- The automatic training's re-population is still one transaction. Its locked attempt makes writers to the bucket wait for
  it, bounded by the re-population of `autoTrainThreshold` entries plus whatever arrived since: a writer whose wait passes
  `COMMIT_LOCK_TIMEOUT` (5 s) gets a `LockTimeoutException`, a `NeedRetryException` its retry absorbs. Publishing the layout
  first and re-populating in batches (the finding's preferred shape) needs readers to search the untrained list and the new
  lists together until the re-population ends; not built. A first commit that is itself a bulk transaction crossing the
  threshold still makes one large training transaction.
- Training attached to a transaction marks an enclosing SQL statement unrestorable (`setAttachment`), so a statement that
  builds an IVF index cannot be rolled back on its own, only with its transaction.
- No benchmark was run for these fixes; the hot path changes are the ones listed above.

### Round 2 (2026-11-03)

A second independent review of the index, after the fixes above, reported four majors and no blocker. Each is fixed with a
regression test in `LSMIVFVectorIndexReviewTest` (now 16 tests), and each test was checked against a mutation of its fix
(the fix reverted in place, the test run red, the fix restored). The round-1 test
`aCentroidLoadThatOverlappedAPublicationIsReadAgain` staged a zeroed centroid page BELOW the committed page count, a state
no publication produces (see row 1 below); it is replaced by a test of the state a publication does produce.

| # | Severity | Finding | Fix | Regression test |
|---|---|---|---|---|
| 1 | major | On an HA follower a search overlapping a replicated apply got read errors. The apply (`TransactionManager.applyChangesInternal`) moves the publication sequence without the page-manager lock, so the round-1 wait (`executeInLock(() -> null)`) returned at once: the 8 attempts of `findNeighbors`, and the 8 of the centroid load, were spent in microseconds while one apply was in flight, and the "locked" fallback excluded nothing. A scan that met a link to a page not applied yet threw `ConcurrentModificationException` at a read-only query; a centroid load that met a header whose sections were not there yet threw `IndexException` for the whole apply of a training | No read looks at the publication sequence any more. A read that meets a publication half done (a link to page 0 or past the committed page count, a page of another list, a header of a newer training, a header whose sections reach past the committed page count) is read again after a pause: four yields, then parks doubling from 20 us to 5 ms, bounded by TIME (30 s, `CONSISTENT_READ_TIMEOUT_NANOS`) rather than by an attempt count a no-op wait burns through; only then does the search throw `ConcurrentModificationException` (a `NeedRetryException`) or the load an `IndexException`. The centroid load treats a header whose sections are not published as "publication in flight" and waits for them | `aSearchWaitsOutAPublicationThatHoldsNoLock` (a zeroed second posting page served while a publication is held open WITHOUT the lock for 300 ms, through the new `PublicationTestSupport.beginPublication`/`endPublication`), `aCentroidLoadWaitsForTheSectionsOfAHeaderStillBeingPublished` (the committed header names fine centroids past the file for 300 ms, same staging) |
| 2 | major | The publication sequence and the lock the round-1 fix validated against belong to the one `PageManager` of the JVM: every commit of every database moved the sequence, so a 5 ms search under a 2 ms ingest anywhere in the process was read again most of the time, and after 8 runs it ran under `PageManager.executeInLock`, which held the `publishPages` of every committer of the process for the length of a scan, its cold-page reads and its record reads. A centroid load of seconds at 1.5M lists practically always ended there | Option (b) of the finding: the check is scoped to the postings file by construction, because it is structural and not a counter. A scan follows a link only to a page in `(0, pageLimit)`, where `pageLimit` is the page count read when the scan starts, and stops a list at a page whose `P_CENTROID` is not the list or whose count does not fit; the page count of a file is raised only once its pages are in the read cache (after the whole publication on a commit, page by page in allocation order on a replicated apply, where a transaction's new pages precede its modified ones), so every page below it is published. Any other overlap is a read of two adjacent committed states of one list: an entry missed (committed during the search) or met twice (an update's old entry read live before its publication, its new one after), never a foreign entry, so the answer is deduplicated by position (an O(k) primitive open-addressing set, the best distance kept). The centroid load: every section page below the committed page count read after the header, plus the coarse-starts frame, proves a complete read of immutable new pages; no sequence check. Nothing runs under the page-manager lock any more | `aSearchIsNotReadAgainForCommitsElsewhereAndHoldsNoGlobalLock` (a commit to another type on another thread during every run of the search: no retry, and no commit waits; under the round-1 code the 9th commit waited 5 s on the lock held by the locked search), `aPositionMetTwiceByATornReadIsAnsweredOnce` (the dead old entry of an updated record served live, as before the update's publication: the record is answered once) |
| 3 | major | The automatic training sized the partition once, at the threshold, and nothing trained it again: the usual flow (create the index on an empty type, then load) trained at 10K vectors into 50 lists, and after 1M more each list held ~20K entries, `nprobe` 32 scanned ~640K entries per query against the 6,400 the latency model assumes, and nothing warned | A trained index with an automatically sized `nlist` (`nlist` 0) and `autoTrainThreshold` > 0 trains again, through the same machinery as the first training (pass 1 and k-means on the query pool, one re-populating transaction, two optimistic attempts then one under an explicit bucket lock), once its live entries reach 4x what its last training saw and a training would make more lists. The count a training saw is persisted in the header (`H_TRAINED_AT`, offset 92 of page 0; 0 in an older file, read as `nlist x targetPostingSize`), so a reopen keeps the trigger; `REBUILD INDEX` resets it. The check costs one atomic increment per write and, every 1,024th write, one header read after its commit. A re-population is kept to one transaction of at most 128 MB of entries (`RETRAIN_MAX_TRANSACTION_BYTES`); past that, with an explicit `nlist`, or with `autoTrainThreshold` 0, the index logs a WARNING once per doubling of the corpus when its lists average more than 4x `targetPostingSize`, asking for a `REBUILD INDEX` (which commits in batches). `getStats()` gains `autoRetrainings`, `liveEntries` and `trainedAtEntries` | `theIndexTrainsAgainAsItGrows` (threshold 500, target 50: 10 to 12 lists at 600 vectors, at least 4x that after 3,400, every entry indexed, a self-query found, the trained-at count unchanged by a reopen) |
| 4 | major | The allow-list bitmap's block index was a dense `int[(maxPosition >>> 9) + 1]`, sized by the largest allowed position, and was built before the exact-path decision: one record at position ~20G (300M records of ~2 KB) allocated 160 MB per query for a filter the exact path then probed only for pending entries | `PositionFilter` costs what it holds. An allow-list of at most `exactFilterThreshold` positions (the exact path) is a sorted array, never a bitmap. A wider one keeps the dense block index (two array reads per probe) only while the positions average at most 16 blocks apart, so the index never costs more than the 64-byte bit block each position takes anyway; past that the allowed blocks are found through an open-addressing table of the allowed blocks alone (block + 1 to bit-block index, at most half full). The `MAX_BLOCKS` cap and its sorted-array fallback are gone | `anAllowListCostsWhatItHoldsNotHowFarItReaches` (one position at 20G: under 1 KB; 10,000 positions spread to 20G: under 4 MB, about 1 MB; dense positions: the block index, under 64 KB; membership exact for every member and its neighbour) |

What round 2 costs and what it leaves:
- Per search: the two volatile reads of the publication sequence are gone; added is one `long[]` of 4 x the next power of
  two of `k` and one hash probe per answered candidate. A torn read now costs a pause of microseconds (a yield) before
  the re-read instead of an immediate re-read.
- The automatic retraining is one transaction, like the first training, so its locked attempt makes writers to the bucket
  wait for the re-population (up to 128 MB of entries plus what arrived since); a writer whose wait passes
  `COMMIT_LOCK_TIMEOUT` (5 s) gets a `LockTimeoutException`, a `NeedRetryException` its retry absorbs. Each retraining
  leaves the pages of the previous layout dead in the file: across the 4x steps that is at most a third of the final size
  on top. Retraining is global (every list re-assigned); per-list split and merge remain designed and not built (see
  "What remains", item 1), and a skewed stream still makes some lists long between trainings.
- An index past the retraining cap, or with an explicit `nlist`, still needs `REBUILD INDEX` after bulk growth; the
  WARNING and `getStats()` say when.
- An approximate answer can miss an entry whose publication overlapped the search, or, during the publication of an
  update, the record itself (its old entry read already cleared, its new one not yet linked); a re-run after the commit
  finds it. The round-1 sequence check did not close that window either for a scan that ended before the publication.
- No benchmark was run for round 2: the filter path the 1M and 5M runs exercise (dense positions) is the unchanged dense
  block index, the exact path's sorted array replaces a bitmap of the same size or larger, and the search loop lost work
  rather than gained it. The position-dedupe probe is O(k) per query.
- The HA test still searches only after the training has landed; the follower's in-flight apply is staged in the engine
  test above (the publication held open without the lock) rather than through a live cluster.

### Round 3 (2026-11-03)

A third review reported one major. It is fixed with two regression tests in `LSMIVFVectorIndexReviewTest` (now 18 tests),
each checked against a mutation of its fix (the training compaction, the `assign` skip and the `select` count reverted one at
a time: each test goes red).

| # | Severity | Finding | Fix | Regression test |
|---|---|---|---|---|
| 1 | major | A two-level training (more than 256 lists, fewer than the 20,000 that switch to the centroid graph) could lay out a coarse group with no fine centroid: Lloyd's last update can leave a coarse centroid with no sample point (duplicates make it certain), the re-seed only runs inside the iterations, and a group with 0 points got share 0, so `coarseStart[c] == coarseStart[c+1]`. Its coarse centroid still attracted vectors, and `assign` searched the empty range and answered its start: for the last group that is `nlist`, so `put()` threw `ArrayIndexOutOfBoundsException` reading fine centroid `nlist` (second group non-empty) or appended to list `nlist` (second group empty or absent), writing the directory slot after the last list and corrupting the header page or the locator's directory page. A middle empty group sent the vector to the first list of the next group. `REBUILD INDEX` trained the same layout again (`new Random(42)`) | `IVFKMeans.train` drops every coarse group the final assignment left empty, compacting `coarse` and `coarseStart`, so every group it lays out owns at least one list. `IVFCentroids.assign` skips groups with an empty fine range when it picks the two nearest, and `select` counts only groups holding lists towards its minimum of four opened groups, so a layout read back from a file trained before the fix assigns to a valid list and probes as many real groups as a clean one | `aCoarseGroupWithNoListsIsNeverAssigned` (empty last group nearest the vector: list 3 of 4 instead of the AIOOBE; two empty last groups: list 3 instead of `nlist`; two empty middle groups: the true nearest list, not the first of the next group; four empty groups nearest a query no longer fill the probe minimum and hide the nearest list), `aTrainingLaysOutNoEmptyCoarseGroup` (600 copies of 3 distinct vectors, 400 lists wanted: every coarse range non-empty, every vector assigned below `nlist`) |

What round 3 costs and what it leaves:
- `assign` adds one int compare per coarse group, `select` one per opened group; the training loses the empty groups'
  layout. No benchmark: the per-group compare is below the noise of the coarse distance it sits next to.
- An index already trained with an empty group keeps that group's coarse row in its file; it is skipped, not removed, until
  the next training.

### Round 4 (2026-11-03)

A fourth review reported one major and one minor. Both are fixed with regression tests in `LSMIVFVectorIndexReviewTest`
(now 22 tests); the build fix is pinned by two mutations (the staging turned off, the explicit lock removed: each makes its
test red with a `ConcurrentModificationException`), the counter fix by its test, red before the fix.

| # | Severity | Finding | Fix | Regression test |
|---|---|---|---|---|
| 1 | major | Hardening item 4 moved build pass 2 off the lane: `appendBatch` appended every entry onto the posting pages, header page 0 included (every append rewrites its counters and locator), inside each 5,000-record chunk transaction and outside the commit lock, and the first chunk also wrote the layout. A writer that committed to the index while a chunk was open (it queues an `IVFReplayKey` from the index's registration on, and its replay moves page 0) failed the chunk's commit on the page version, and the `NeedRetryException` made `LocalSchema.createBucketIndex` drop the whole index | The coding stays on the query pool; the entries are staged on the commit lane (`tx.addIndexOperation(this, ADD, IVFReplayKey(list, version, position, entry, vector), rid)`), so `putReplay` appends them at the chunk's commit, under the commit lock, on the pages as they are then, like a live write. The layout is written in a short transaction of its own whose first act is an explicit lock of the bucket and its indexes (the transaction the build was handed, which holds at most the index's creation, is committed first, as the first batch commit would have): a writer that commits meanwhile waits for it at its commit, and it cannot lose. Applies to builds that commit in chunks (an owned build with a batch size); the automatic training and a build inside the caller's transaction write in their one transaction, as before, and are retried as a whole | `aBuildChunkCommitsOverAWriterThatCommittedInsideIt` (a direct build of 3,000 records, chunks of 500: at record 750 another thread deletes one record and updates another of the committed first chunk; the build completes, 2,999 entries, the updated record found by its new vector and the deleted one not found, every 7th record found by an exhaustive search), `aWriterCommittingWhileTheLayoutIsWrittenWaitsForIt` (a writer started at the build's read of the header, before it writes the layout, under REPEATABLE_READ so the window is as wide as the test makes it: both commit, 2,001 entries, the writer's record found), `createAndRebuildIndexCompleteUnderLiveIngest` (CREATE INDEX then REBUILD INDEX ... WITH batchSize = 500 on 4,000 records while another thread commits inserts, updates and deletes without pause: both complete, the writer never fails, and afterwards the index holds exactly one entry per live record and every live record is found by a search probing all 8 lists) |
| 2 | minor | `populateTrained` counted `trainings` (per index and the JVM-wide meter) before its transaction committed, so an automatic training attempt that lost to a writer, or a build that rolled back, still counted; the JVM-wide `autoTrainings` meter, described as first trainings of indexes created empty, was incremented for retrainings too | A training is counted in the after-commit callback that publishes its centroids. `autoTrainings` counts first trainings only; retrainings have their own JVM-wide meter, `IVFVectorIndexMetrics.autoRetrainings`, exported as `arcadedb.vector.ivf.auto.retrainings`; the meter descriptions say "committed" | `aTrainingThatRollsBackIsNotCounted` (a build rolled back leaves `trainings` unchanged, a committed one adds one), `theIndexTrainsAgainAsItGrows` (now also: one first training, the retraining meter moved), `IVFVectorIndexMetricsBinderTest` (the new meter registered, the trainings meter moved by the build) |

How reachable finding 1 was: through `CREATE INDEX` and `REBUILD INDEX` it was not. Every builder runs the build inside
`recordFileChanges`, which holds the database write lock, and every commit takes the read lock, so another session's writes
wait for the whole build instead of committing into it: `createAndRebuildIndexCompleteUnderLiveIngest` passes on the code
before the fix as well, and only the two direct-build tests (`IndexInternal.build` in a transaction, with no schema frame
around it) reproduce the failure. The fix makes the build safe on its own rather than through that lock. The lock also means
live ingest stalls, not fails, for the length of a build (about 53 s at 1M x 512 below); that is the engine's behaviour for
every index kind and is not changed here.

A build that runs outside a schema frame while writers commit is now safe from conflicts, but it is not a snapshot: an
update committed to a record of the CURRENT, still open chunk is replaced by the build's older entry when the chunk commits
(the locator keeps one live entry per position, the last appended). The direct-build test therefore writes only to records
of a chunk already committed. Inside `CREATE INDEX`/`REBUILD INDEX` this cannot happen, for the reason above.
(Corrected in round 5: "the reason above" holds only while the database lock is on. With `arcadedb.backup.enabled=false`
`recordFileChanges` takes no lock, writers commit into `CREATE INDEX`, and the race was reachable from SQL. Fixed there.)

Measured: `LSMIVFVectorIndexBenchmark#stagedBuildAB` (`-Divf.bench.stagedBuild=true -Divf.bench.rows=1000000`), 1M x 512-d,
BINARY + INT8, 5,000 lists, the same records built four times in one JVM, staged and direct alternating
(`LSMIVFVectorIndex.setStagedBuild`), load average about 7 on the machine.

| Build (1M x 512) | pass 2 | time (s) | vectors/s |
|---|---|---|---|
| create | staged on the lane | 52.8 | 18,938 |
| rebuild | appended in the chunk (before) | 51.7 | 19,324 |
| rebuild | staged on the lane | 52.9 | 18,900 |
| rebuild | appended in the chunk (before) | 54.2 | 18,438 |

Staged 52.85 s on average against 52.95 s direct: no measurable cost (the spread of each side, 0.1 s and 2.5 s, is larger
than the difference). The appends are the same work moved to the chunk's commit; what is added is one `IVFReplayKey`,
`Object[]` and lane entry per record, and one header read per append in `applyAdd` instead of one per batch of 8,192, all
below the noise of the bucket scan and the coding. Memory: a chunk keeps its 5,000 prepared vectors alive until its commit
(10 MB at 512-d), where the direct path released them per batch of 8,192.

What round 4 leaves:
- The explicit lock of the layout transaction makes writers to the bucket wait for the layout write (centroid, directory and
  locator pages: milliseconds at 5,000 lists, longer at 1.5M); a wait past `COMMIT_LOCK_TIMEOUT` is a `NeedRetryException`.
- `setStagedBuild` is one more diagnostic switch (What remains, item 8).


### Round 5 (2026-11-03)

A fifth review reported two majors. Both are fixed with regression tests in `LSMIVFVectorIndexReviewTest` (now 27 tests), and
the live-ingest test that pins the second exposed two more defects on the same path, fixed with it (rows 3 and 4). Every fix
is pinned by a mutation: with it reverted, the tests named in the last column go red and every other test of the table stays
green.

| # | Severity | Finding | Fix | Regression test (red with the fix reverted) |
|---|---|---|---|---|
| 1 | major | Under REPEATABLE_READ a transaction pins every page it reads. `put()` and `remove()` read header page 0 through the transaction (`centroids()` -> `readCentroidVersion`) to learn the centroid version, and a search pins the header and the pages of the lists it scanned. The commit replay (`applyAdd`/`applyRemove`) then appended on the pinned copies, so a commit of ANY other writer to the index in between (every append moves page 0: counters, locator) failed `checkPageVersion` with a `ConcurrentModificationException`, whatever list or record the two touched. Under steady concurrent ingest in REPEATABLE_READ the retries ran out. Spike gap 9 described this as "concurrent appends to one list": it was every pair of overlapping writers | Two parts. (a) A write takes its centroids from the committed state (`centroidsForWrite`: the transaction's own uncommitted training when it has one, else the resident set when its version is the committed one, else the committed set loaded under `centroidLoadLock`), never from page 0 read through the transaction; the assignment was only ever a hint, the replay recodes on a version mismatch. (b) The first replay of a commit (`releaseReadPinsBeforeReplay`, once per commit through a transaction attachment, REPEATABLE_READ only, never in the body) releases the transaction's pins of the postings file (`tx.unpinFiles`, as `releasePinnedPages` already did for a torn search) unless the body wrote pages of that file itself (`tx.hasChangesInFiles`: a training or a build in the caller's transaction, whose pages the version check must still judge). The replay then reads the pages as committed, under the commit lock, exactly as under READ_COMMITTED. Nothing after the replay reads through the released pins, so the snapshot weakens only where it no longer serves anything | (b): `aRepeatableReadWriterCommitsOverAnotherWritersCommit` (a REPEATABLE_READ transaction inserts and updates, a second writer commits to the index, the first commits with no retry; a second round searches first), `overlappingRepeatableReadWritersAllCommit` (4 threads x 40 REPEATABLE_READ transactions of 5 inserts and an update, every other one searching first, default retries: all 160 commit, `countEntries` equals the record count; before the fix the first commit that overlapped another failed), `aRepeatableReadWriteIsCodedAgainstTheCommittedCentroids`. (a): `aRepeatableReadWriteIsCodedAgainstTheCommittedCentroids` (a REPEATABLE_READ transaction pins the header, a training commits on another thread, the transaction inserts 20 vectors and commits: no replay recode, where reading the pinned header coded all 20 against the replaced layout) |
| 2 | major | A build is not a snapshot. A chunk stages ADD(vector as scanned) for every record and replays it at the chunk's commit; `append` clears whatever live entry the locator holds for the position. A writer that updated a record the open chunk had scanned, and committed first, lost its new entry to the stale one (the record found only by its old vector); a writer that deleted it, or cleared its vector, left a live ghost (`countEntries` above the record count, `findNeighbors` answering a deleted RID). Round 4 called this unreachable from `CREATE INDEX`/`REBUILD INDEX` because `recordFileChanges` holds the database write lock, but that lock is off with `arcadedb.backup.enabled=false` (`LocalDatabase.openInternal`: `setLockingEnabled(BACKUP_ENABLED)`, documented as a performance option): writers then commit into `CREATE INDEX`. A direct `Index.build` reaches it with the lock on | A staged entry is flagged (`IVFReplayKey.build`). From the layout of a chunked build until its last chunk commits, every removal a writer's replay applies to the index is recorded (`BuildOverlap`), and the replay drops a flagged entry whose position a removal reached since the layout. That covers every write that can make the staged entry stale, because each one removes the record's entry first: an update (its own ADD is the live entry the stale one would have replaced), a delete, a vector cleared, a delete and a re-insert into the same slot. A write that only adds (an insert committed after the layout, before the scan reached it) leaves an entry with the vector the scan then reads, so the staged copy replaces it with the same vector: one dead entry, no wrong answer. The review also proposed skipping a flagged entry whose position the locator maps, or whose record slot is gone. Both are subsumed by the removal record (with the locator check reverted no test goes red), and the locator check would add a locator walk per staged entry to every build. The record is installed while the layout transaction still holds its explicit lock: every writer that replayed before it has published (a writer holds its locks from replay to publication), so the scan reads what it wrote; none replays again until the layout commits, so no removal of the previous layout is recorded. Kept small: the build stages positions in ascending order (the bucket scan's) and chunks commit in that order, so a removal at or below the highest replayed staged position is not kept (the entry is on the pages and the removal found it). Only removals ahead of the build stay, in a primitive `LongHashSet` (about 11 bytes each), until the build ends. The last chunk is committed by the build itself, inside the window, instead of by its caller | `aBuildChunkDoesNotOverwriteWritesToRecordsItHasScanned` (a direct build of 3,000 records in chunks of 500: at record 750 another thread updates record 600, deletes 610 and clears the vector of 620, all scanned by the open chunk, and commits. Afterwards: 2,998 entries, 600 found by its new vector, 610 and 620 not found, every 7th record of the chunk found), `createAndRebuildIndexCompleteUnderLiveIngestWithoutTheDatabaseLock` (the round 4 SQL test with `BACKUP_ENABLED=false`: CREATE INDEX then REBUILD INDEX ... WITH batchSize = 500 while a writer commits inserts, updates and deletes; afterwards exactly one entry per live record and every live record found by an exhaustive search; red on the code before the fix in 3 runs out of 3) |
| 3 | major | Found by the test of row 2: with the database lock off, a writer can commit between the registration of a new index and the commit of its creation (the postings file has no committed page yet). `applyAdd` threw `IndexException("postings file has no header page")`, which the commit wraps in a plain `TransactionException`, so no retry absorbed it and the writer failed | Such an ADD is left to the build: the creation always runs a build after it, which scans the record this writer commits now (a chunked build commits the creation before its scan). Logged at FINE. A REMOVE already returned there | `createAndRebuildIndexCompleteUnderLiveIngestWithoutTheDatabaseLock` (the writer failed on its first commit after `CREATE INDEX`) |
| 4 | minor (engine) | Also found by the test of row 2: `REBUILD INDEX` holds the old index files' locks for the whole rebuild (`executeLockingFiles`) and drops them. A writer whose commit waited on one timed out after `COMMIT_LOCK_TIMEOUT`, and `TransactionManager.tryLockFiles` built the `LockTimeoutException` message with `FileManager.getFile`, which throws `IllegalArgumentException` for a dropped file: the retryable timeout became a plain `TransactionException`. Not specific to IVF (any index rebuilt under ingest with the lock off) | Both `tryLockFiles` overloads name the file through `getFileIfExists` (`describeFile`), "dropped" when it is gone, so the timeout stays a `LockTimeoutException` and the writer's retry runs against the new index | `LockTimeoutHolderDiagnosticsTest.lockTimeoutOnAFileDroppedMeanwhileStaysALockTimeout` (a lock held on a file id no file is registered under: both overloads answer a `LockTimeoutException` naming the holder) |

What the mutation runs showed, per part: reverting (a) turns `aRepeatableReadWriteIsCodedAgainstTheCommittedCentroids`
red; reverting (b) turns that test, `aRepeatableReadWriterCommitsOverAnotherWritersCommit` and
`overlappingRepeatableReadWritersAllCommit` red; reverting the removal record turns
`aBuildChunkDoesNotOverwriteWritesToRecordsItHasScanned` and `createAndRebuildIndexCompleteUnderLiveIngestWithoutTheDatabaseLock`
red; restoring the throw of row 3 turns the latter red. `aBuildChunkCommitsOverAWriterThatCommittedInsideIt` and
`createAndRebuildIndexCompleteUnderLiveIngest` stay green under every mutation, as they should: they write only to committed
chunks, or with the lock on.

Measured: `LSMIVFVectorIndexBenchmark#stagedBuildAB` as in round 4 (1M x 512-d, BINARY + INT8, 5,000 lists, four builds in
one JVM alternating staged and direct). The direct path is unchanged by this round, so it is the baseline. The machine was
shared with other builds: the load average ran from 10 to 30 during the run (about 7 in round 4), which is why every number is
about 40% slower than round 4.

| Build (1M x 512) | pass 2 | time (s) | vectors/s |
|---|---|---|---|
| create | staged on the lane (with the removal record) | 90.0 | 11,116 |
| rebuild | appended in the chunk | 80.7 | 12,386 |
| rebuild | staged on the lane (with the removal record) | 69.6 | 14,377 |
| rebuild | appended in the chunk | 71.5 | 13,987 |

The two adjacent rebuilds, staged 69.6 s against direct 71.5 s, show no cost. The first build is also the cold one (JIT, page
cache), and the 21 s spread between the two direct runs is larger than any difference between staged and direct. What the
round adds per staged entry is one flag and, at replay, one synchronized primitive-set probe. Per write it adds one
committed-version read of page 0 (the page cache's shared view, no copy) instead of one through the transaction. Under
REPEATABLE_READ it adds one pass over the transaction's modified pages per commit that touches the index.

What round 5 leaves:
- A build's removal record grows with the removals committed AHEAD of the build's scan while it runs: about 11 bytes per
  distinct position, freed when the build ends. A REBUILD of a large index under heavy update traffic to records the scan
  has not reached holds that much more heap for its length.
- Releasing the pins at the replay means a REPEATABLE_READ transaction's commit no longer fails when its earlier search read
  index pages a concurrent commit has since changed. That is the same answer READ_COMMITTED gives, and the index has no
  read-validation semantics to lose. Its record pages keep their pins and their version checks.
- With the database lock off, every index kind's build stays non-snapshot in other ways (a record committed by a writer
  whose transaction began before the index was registered, into a slot the scan has already passed, is indexed by neither).
  `LSM_TREE` has the same gap. Only the IVF-specific overwrite of a newer entry is closed here.


### Round 6 (2026-11-03)

A sixth review reported three minors. All three are fixed; the HA one came with a reachability question, answered below with
code references and pinned by two new tests in `LSMIVFVectorIndexHATest` (now 4 tests).

| # | Severity | Finding | Fix | Test |
|---|---|---|---|---|
| 1 | minor | `aWriterCommittingWhileTheLayoutIsWrittenWaitsForIt` ran its writer with 3 retries, and a comment said the REPEATABLE_READ writer conflicts on the header page it read before the layout. Since round 5 (`centroidsForWrite` takes no page through the transaction, `releaseReadPinsBeforeReplay` unpins at replay) that is false, and the retries would hide a regression of either fix | The writer runs with 1 attempt and counts it; the test asserts exactly one attempt, no failure, and the writer's record found. The stale comment is gone | the test itself, green |
| 2 | minor | `LSMIVFVectorIndex.hasLiveVector` claimed to check for a live entry on the pages or in the transaction, but compared bucket ids only, so it answered true for a deleted or vector-less RID. Nothing called it: `SQLFunctionVectorNeighbors` defers record reads only for `LSMVectorIndex` (`deferredRecordReads`, `neighbourRecord`), and its IVF path (`executeWithIVFIndexes`) reads every record it answers, so a deleted one is skipped by the read | Removed. A correct one (locator lookup plus the transaction overlay) has no caller until the IVF path defers its record reads, which is a feature of its own (#10973 for IVF), not this fix | n/a (no caller; `grep hasLiveVector` finds only `LSMVectorIndex` and its test) |
| 3 | minor | `BuildOverlap` (round 5) is a field of the index instance in the leader's JVM. A follower's write is replayed on the follower and reaches the leader as page bytes, so a follower's update or delete of a record an open build chunk has scanned would never reach the leader's removal record, and the chunk's stale entry would replace the newer one or bring the deleted record back | Not reachable through any shipped entry point (below), so the removal record stays in memory. A guard makes the assumption fail loudly: `LSMIVFVectorIndex.build` refuses a build that commits in chunks on a replicated database (`isReplicated()` on the wrapper) unless a recording of file changes is open on the build's thread, which is the leader's schema session (`checkChunkedBuildIsExclusive`). It costs one check per build | `aFollowerWriteDuringABuildOnTheLeaderIsRefusedUntilTheBuildEnds`, `aChunkedBuildOutsideTheLeadersSchemaSessionIsRefused` |

**Why finding 3 is not reachable through CREATE INDEX or REBUILD INDEX.**

1. Every build that commits in chunks runs inside the leader's schema session. `CREATE INDEX` (`TypeIndexBuilder.create`,
   `schema.recordFileChanges` at line 519), `REBUILD INDEX` (`RebuildIndexStatement` -> `BucketIndexBuilder.create`,
   `schema.recordFileChanges` at line 193) and the Java schema builder all go through `LocalSchema.recordFileChanges`, which
   calls `database.getWrappedDatabaseInstance().recordFileChanges` (line 5759): under Raft, `RaftReplicatedDatabase.recordFileChanges`.
   That method refuses on a follower (`schemaChangesNeedTheLeader`, line 2743), and a follower forwards DDL to the leader
   (line 1633). So the build always runs on the leader, on the thread that opened the session.
2. The session makes the leader exclusive on the database for its whole length. `RaftReplicatedDatabase.recordFileChanges`
   calls `beginLeaderExclusive()` (line 2833) before the callback and ends it in its `finally`, after the final entry is
   applied. `ArcadeStateMachine.beginLeaderExclusive` registers the database first and then waits for every entry already
   given page versions to be applied, so nothing a follower committed before the session is still in flight when the build
   starts. From then on, a transaction entry of another node for the database is refused with a retryable
   `NeedRetryException` (`refusesEntriesOfOtherNodes`, checked at lines 1545 and 1616 of `ArcadeStateMachine`, the second
   check closing the race with an entry that passed the first). A follower's write therefore cannot commit anywhere while a
   chunk is open: the follower retries and commits after the build, when the new index is there and its own replay writes the
   entry.
3. The leader's own writers replay on the leader, in the same JVM and against the same index instance, so their removals do
   reach `BuildOverlap`. They do not even overlap the build when the database lock is on (`recordFileChanges` holds the write
   lock); with it off (`arcadedb.backup.enabled=false`) a leader commit waits for the recording session
   (`waitForActiveRecordingSession`, `RaftReplicatedDatabase` line 661) and, past `HA_QUORUM_TIMEOUT`, commits with the
   replay, which `BuildOverlap` sees, as in round 5. (Corrected in round 7: that keeps the leader's index right and no more.
   The commit that gave up waiting ships an ordinary `TX_ENTRY`, and the followers can diverge; see the open issue in
   Round 7.)
4. Followers do not replay build keys at all: they apply the build's pages from the session's `SCHEMA_ENTRY` instalments.

**Where it was reachable, and what closes it.** A chunked build asked of the index directly (`IndexInternal.build(batch, false,
callback)` from embedded code on an HA server, or on a follower) runs outside the session: no exclusive window, so a follower's
delete of a record in the open chunk commits on the leader as pages, and the chunk's replay appends the stale entry over it. No
shipped path does this (every engine call site of `build` is inside a schema frame: `IndexBuilder.buildCreatedIndex`,
`LocalSchema.createBucketIndex`, `TypeIndex.build` reached from them). Making the stale check derivable from the pages was
considered and rejected: a follower's delete of a record whose staged entry is not on the pages yet finds no entry and writes
nothing to the postings file, so there is no page state to derive "removed after the layout" from, short of re-reading the
record from its bucket for every staged entry at the chunk's commit (5,000 record reads under the commit lock per chunk). The
guard refuses such a build instead, before the status flip or any write, with an `IndexException` that names `REBUILD INDEX`.
Two shapes stay outside the guard on purpose: a build that shares the caller's transaction (one transaction, no chunks and no
`BuildOverlap`; its non-snapshot gap is the general one round 5 lists for every index kind), and a recording session that is a
compaction (`runWithCompactionReplication`), which never runs an IVF build. (Changed in round 7: the first shape, and a build
in one transaction, are refused too.)

Tests (`ha-raft`, 3 nodes):
- `aFollowerWriteDuringABuildOnTheLeaderIsRefusedUntilTheBuildEnds`: the index is created on the leader through the schema
  builder with a build callback that pauses at record 750 (in the second chunk, after the scan passed records 600 and 610).
  A follower then deletes 600 and moves 610 to a new vector in one transaction with one attempt: the commit is answered
  while the build is paused (not held until it ends), with a `NeedRetryException` in its cause chain, and the record is still
  on the leader. After the build the same write commits on the follower, and on every node the index holds 1,999 entries,
  finds 610 by its new vector and does not answer 600.
- `aChunkedBuildOutsideTheLeadersSchemaSessionIsRefused`: `build(100, false, null)` asked directly of the index on each of the
  three nodes throws `IndexException`; each index keeps its 500 entries and answers a search.

### Round 7 (2026-11-03)

A seventh review reported one major and four minors, and a follow-up review one more minor (row 6). All are fixed; finding 3 is a correction of the record plus an open issue
that is not IVF's to close.

| # | Severity | Finding | Fix | Test |
|---|---|---|---|---|
| 1 | major | `LSMIVFVectorIndexHATest` was tagged `vector`, but the only `vector` lane in `mvn-test.yml` runs `-pl engine`, the `modules` shard of `unit-tests` (which holds `ha-raft`) excludes `vector`, and `ha-integration-tests` runs only `*IT`. The four HA tests, round 6's guard pins included, ran in no CI lane. The tag was also wrong on its own terms: IVF does not use `LSMVectorIndex.REBUILD_SEMAPHORE` | Tag removed, so the class runs in the `modules` shard (not `slow` either: that lane is engine-only too). No other IVF test outside `engine` carries a tag. The run command under Hardening is updated | the class, selected by `-DexcludedGroups=slow,benchmark,vector` |
| 2 | minor | The round 6 guard exempted a build sharing the caller's transaction and a build in one transaction (`commitEvery <= 0`), but either, asked directly on an HA node outside a schema session, can still leave a ghost for a record a follower deletes after the scan passed it | `checkBuildIsExclusive` (was `checkChunkedBuildIsExclusive`) refuses every build on a replicated database unless a recording of file changes is open on the build's thread; the message still names `REBUILD INDEX`. Checked by grep that no shipped path is affected: the only engine call sites of `build` are `LocalSchema.createBucketIndex` (build = true), `IndexBuilder.buildCreatedIndex` (passes `sharesCallerTransaction = false` to the index in both branches) and `TypeIndex.build`; their callers (`TypeIndexBuilder.create`, `BucketIndexBuilder`, `LocalDocumentType.addBucketInternal`, `propagateSuperTypeIndexes`, `createSubIndexesFor`, REBUILD INDEX, TRUNCATE TYPE, CHECK DATABASE FIX) run inside `recordFileChanges`, and the automatic training does not call `build` | `aBuildOutsideTheLeadersSchemaSessionIsRefused` (renamed from `aChunkedBuildOutsideTheLeadersSchemaSessionIsRefused`): `build(100, false, null)`, `build(0, false, null)` and `build(100, true, null)` inside a transaction, each refused on all three nodes |
| 3 | minor | Round 6 point 3 said that with `arcadedb.backup.enabled=false` a leader writer waits for the session and is seen by `BuildOverlap`. True only inside the leader's JVM | Doc and the `isStaleBuildEntry` javadoc now say it precisely (below). No `ha-raft` change in this round | n/a |
| 4 | minor | The follower-write HA test bounded the writer with `writer.join(60_000)` and then asserted it ended: a raw wall-clock tripwire, which CLAUDE.md forbids | Measured with `StallAwareStopwatch` and `assertGaveUpWithin(60 s, "refused during the build rather than held until it ends")`; the join stays only as a 120 s hang detector (a commit held until the build ends never returns, since the build waits on the writer) | the test itself |
| 5 | minor | `aWriterCommittingWhileTheLayoutIsWrittenWaitsForIt` counted attempts and asserted one, but `database.transaction(..., false, 1)` cannot run its body twice | Counter dropped. A retry is impossible by construction, so `failure == null` is the check, and the comment says so. (Corrects round 6 row 1, which described the counter as part of the check.) | the test itself |
| 6 | minor | Follow-up to row 1: `LSMIVFVectorIndexTest` and `LSMIVFVectorIndexReviewTest` still carried a class-level `@Tag("vector")`, which CLAUDE.md reserves for LSM vector-index tests waiting on `LSMVectorIndex.REBUILD_SEMAPHORE`. Row 1's reasoning applies to them word for word. They did run (the `vector-unit-tests` lane is `-pl engine -Dgroups=vector`), so this was a misused partition, not a coverage gap | Class tags removed, so both classes run in the `engine` shard of `unit-tests`. The four multi-second methods carry a method-level `@Tag("slow")` and run in the `rest` shard of `slow-unit-tests`: `recallOnClustered512d` (about 18 s), `recallOnRandom128d` (about 5 s), `createAndRebuildIndexCompleteUnderLiveIngest` and its `WithoutTheDatabaseLock` twin (about 9 s each). Every other method is under 3 s. No IVF test carries `vector` now. The run command under Hardening and the tag notes in the summary table and in Review findings are updated | both classes, 22 + 27 green with `-Dtest` (no group filter) |

**Open general issue: an index build in a schema session on HA with `arcadedb.backup.enabled=false`.** With locking off,
`RWLockContext.writeLock()` returns null, so nothing on the leader keeps a writer out of a CREATE INDEX or REBUILD INDEX session.
`RaftReplicatedDatabase.commit` waits for the recording session (`waitForActiveRecordingSession`, around lines 653-662 and
4253-4264) only up to `HA_QUORUM_TIMEOUT` (10 s by default), then gives up and ships an ordinary `TX_ENTRY`. If that
transaction wrote to the index being built (it is registered, so it does), its pages name a file that exists only in the
leader's uncommitted schema session. A follower that applies the `TX_ENTRY` before the session's `SCHEMA_ENTRY` creates the
file skips those pages (`TransactionManager.applyChanges` drops pages of an unknown file id, the #4083 symptom), so the
followers can diverge from the leader. `BuildOverlap` keeps the leader's own index right, and cannot help the followers. This
is a property of any build of any index kind that outlasts the timeout in a session on HA with the database lock off, not of
IVF. Left open for an `ha-raft` change of its own (for example refusing, retryably, a leader commit that touches a file of an
open session instead of shipping it); `arcadedb.backup.enabled=false` on an HA cluster should be treated as unsafe for
online index builds until then.
