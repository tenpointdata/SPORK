# Workstream: dense vectors at 300M-900M per node

Status: 2026-11-02. Scope: `LSM_VECTOR` (jvector 4.0.2) on one SPORK node of the 4-node, fully replicated Raft
cluster (126 GB RAM, 2 TB disk). Primary payload: 512-d ArcFace face embeddings (COSINE, match threshold 0.45),
plus 24-d writing-style vectors and future text/image embeddings. ROC templates are opaque and out of scope here.

## What changed in this workstream

| Bottleneck (audit) | Change | Default |
|---|---|---|
| One graph per bucket, searched **sequentially** by `vector.neighbors`, `db.index.vector.queryNodes` and `select().nearestTo()` | New `VectorSearchPool` (dedicated, bounded, metered, never the common ForkJoinPool) searches the bucket sub-indexes in parallel; a primitive-heap k-way merge (`NeighborMerge`) replaces concatenate-and-sort and yields exactly the order the stable sort gave | on: `arcadedb.vectorIndex.parallelSearch=true` (per database) |
| One JVM-wide rebuild permit (`REBUILD_SEMAPHORE`), unsafe to widen because each build's heap check could not see the others | `VectorRebuildPermits`: every build reserves its estimated peak heap for its duration; a second build is admitted only while all reservations fit `rebuildMaxHeapPercent` of available heap. The first build is never held back (same as one permit) | unchanged: `arcadedb.vectorIndex.maxConcurrentRebuilds=1` |

### When the search fans out, and when it does not

The answer never depends on the fan-out: per-bucket answers come back in index order and are merged exactly as the
serial path merged them. A search stays on the caller's thread (exactly the old behaviour) when:

- the type has one bucket, or `arcadedb.vectorIndex.parallelSearch=false`;
- the caller holds **uncommitted changes** or a **REPEATABLE_READ** transaction: a worker binds its own context and
  reads committed pages only, so it could not see the caller's pending vectors or pinned page images;
- any sub-index has no graph resolved yet (the first search after open loads/builds it on the caller, under the
  rebuild permits, as before);
- it is itself running on a pool worker (no nested fan-out);
- multi-bucket searches already in flight could keep the pool busy (`inFlight * 2 > poolSize`);
- `select().nearestTo()` with a timeout set (its per-bucket deadline checkpoints are kept as they were).

Workers run with the caller's user (permissions on record reads unchanged). The wait reclaims queued tasks
(`GraphAlgorithms.awaitFutures`), so the fan-out cannot deadlock under saturation; a full queue degrades to
caller-runs. Pool: `arcadedb.vectorSearchPoolThreads` (0 = cores, min 2), `arcadedb.vectorSearchQueueSize`
(1024). Metrics row `pool=dense_vector` adds `queries.in_flight`, `queries.fan_out`, `queries.serial`.

## Benchmark (synthetic 512-d, 16 buckets)

`VectorParallelBucketSearchBenchmark` (`@Tag("benchmark")`): 160,000 clustered 512-d vectors (400 per centroid,
like many photos of one identity), COSINE, 16 buckets of 10,000, k=10, `vector.neighbors` through SQL, recall@10
against exact brute force. 18-core Apple Silicon laptop, JDK 21, default heap. Arms alternate per round.

Single client, quiet machine (run 2):

| Quantization | Arm | p50 | p95 | recall@10 |
|---|---|---|---|---|
| NONE | serial (before) | 4.90 ms | 6.02 ms | 1.000 |
| NONE | parallel (after) | 0.80 ms | 0.97 ms | 1.000 |
| BINARY (rerank 10) | serial (before) | 10.37 ms | 12.67 ms | 1.000 |
| BINARY (rerank 10) | parallel (after) | 1.30 ms | 1.65 ms | 1.000 |

Concurrent clients (run 3, same data; the machine was shared with other sessions' builds, so absolute numbers are
noisier and single-client p50 was 8.0 / 1.8 ms NONE, 12.6 / 3.0 ms BINARY in this run):

| Quantization | Clients | Serial q/s | Parallel q/s | Serial p50 / p95 | Parallel p50 / p95 |
|---|---|---|---|---|---|
| NONE | 4 | 316 | 603 | 10.7 / 23.4 ms | 4.9 / 16.0 ms |
| NONE | 8 | 549 | 650 | 11.7 / 29.0 ms | 7.1 / 38.1 ms |
| NONE | 16 | 600 | 629 | 21.4 / 62.2 ms | 21.5 / 58.5 ms |
| BINARY | 4 | 324 | 619 | 11.8 / 17.0 ms | 5.4 / 14.0 ms |
| BINARY | 8 | 522 | 657 | 13.1 / 26.2 ms | 7.6 / 35.0 ms |
| BINARY | 16 | 703 | 788 | 18.9 / 50.6 ms | 17.4 / 41.3 ms |

Graph builds: 16 buckets x 10,000 x 512-d in 17-30 s (one permit, serial builds); every vector was in a graph and the
delta buffers were empty before timing (the benchmark asserts it). Recall is 1.000 for both arms because 10,000 nodes
per bucket at the default beam is an easy regime; the point here is that both arms return the same rows.

Reading it:

- Single-client latency drops 6-8x with 16 buckets on 18 cores, and recall is identical (the same rows come back; the
  benchmark asserts it).
- Under concurrency the gain shrinks as callers alone occupy the cores: about 2x throughput at 4 clients, 1.1-1.3x at
  8, even at 16, where the in-flight gate (`inFlight * 2 > poolSize`) keeps most searches serial. The p95 at 8 clients
  is the one cell that got worse (29 to 38 ms NONE): at that load the fan-out still runs while the cores are nearly
  busy. If that tail matters more than the p50 for Recon, lower `arcadedb.vectorSearchPoolThreads` (a smaller pool
  closes the gate earlier).
- 10k vectors per bucket is far below the target bucket size; per-bucket walk cost grows roughly with `log n` and with
  page-cache misses, so absolute numbers at 5M per bucket are higher, while the serial/parallel ratio is set by the
  bucket count and the core count.

Run it yourself:

```bash
mvn -pl engine test -Dtest=VectorParallelBucketSearchBenchmark -Dgroups=benchmark -DexcludedGroups=none -Dbench.rows=160000 -Dbench.buckets=16 -Dbench.quantizations=NONE,BINARY -Dbench.clients=1,4,8,16
```

## Sizing arithmetic for one 126 GB node

Figures are decimal GB unless they are JVM settings. All per-vector constants come from the code and are pinned by `VectorScaleLayoutTest`, so this section fails a test
when the engine changes under it.

### Per-vector heap (resident for as long as the index is open)

| Structure | Bytes / live vector | Source |
|---|---|---|
| Location index (offset, RID, presence bit, reverse index) | 32 | `VectorLocationIndex.APPROX_RETAINED_BYTES_PER_LOCATION` |
| Ordinal map of the published graph | 4 | `int[]` per node |
| BINARY one-bit code, 512-d (if fully cached) | 72 | `BinaryCodeCache.bytesPerCode(512)` = 8 longs + id + version |
| PRODUCT code, M subspaces | ~M (32 or 64) | jvector `PQVectors`, one byte per subspace |
| Graph topology | 0 on heap | `OnDiskGraphIndex`: read through the page cache (`maxPageRAM`) |

Totals:

| Layout | Per vector | 300M | 600M | 900M |
|---|---|---|---|---|
| BINARY, codes resident | 108 B | 32.4 GB | 64.8 GB | 97.2 GB |
| PRODUCT M=32 | 68 B | 20.4 GB | 40.8 GB | 61.2 GB |
| Location + ordinal only (codes not cached) | 36 B | 10.8 GB | 21.6 GB | 32.4 GB |

### Per-vector disk

| Item | Bytes / vector |
|---|---|
| Stored vector in the document, FLOAT32 | 2,048 |
| Stored vector in the document, `encoding: INT8` | 512 |
| Index entry (BINARY inline code + RID + id), estimate | ~100 |
| `.vecgraph` adjacency, `maxConnections=32` (32 x 4 B neighbour ids + header), estimate | ~136 |

At 900M, FLOAT32 storage alone is 1.84 TB, which cannot sit on a 2 TB node that also needs resync headroom
(full resync needs the database size free again). INT8 storage brings 900M to ~0.67 TB of vector data.

### Build heap per bucket

`VectorHeapBudget.estimateRebuildHeapBytes(n, 512, cache, false)` = `n x 1,104 B` (graph + ordinal map) +
`cache x 2,112 B` (build cache entry at 512-d). At 300M over 64 buckets, `n = 4.69M`:

- explicit `graphBuildCacheSize=1,000,000`: 5.2 GB + 2.1 GB = **7.3 GB** per build;
- default cache (25% of `-Xmx`, capped at the bucket): the whole bucket is cached, **14 GB+** per build.

## Recommended layout

### 300M vectors (fits today)

- JVM: `-Xmx80g` (G1 or generational ZGC). The remaining ~46 GB is the OS file cache that ArcadeDB's page reads fall
  through to, Raft logs, metaspace, direct buffers, and headroom for the full-text and geo workstreams.
- `arcadedb.maxPageRAM=24576` (24 GB on heap): hot document pages for reranking, index pages and `.vecgraph` pages.
- Index: `quantization: BINARY`, `encoding: INT8`, `rerank` 4-10 (the BINARY default reranks
  `DEFAULT_APPROXIMATE_RERANK_OVERSAMPLE = 10` candidates per neighbour on the stored vectors), `maxConnections: 32`.
  BINARY walks the graph on in-heap Hamming codes and reads stored vectors only for the rerank, which is what keeps
  a 64-bucket fan-out cheap. Validate recall at the 0.45 face threshold on real ArcFace data before committing;
  PRODUCT (M=64, rerank) is the fallback if BINARY recall on real embeddings is short.
- **64 buckets** (`CREATE DOCUMENT TYPE Face BUCKETS 64`), about 4.7M vectors each. Rationale: inside the ~10M per
  index the engine has been tested to; a mutation-triggered full rebuild of one bucket costs ~7 GB and minutes, not
  ~30 GB and an hour; and the fan-out has enough pieces to use the cores. More buckets cost more CPU per query (one
  graph walk each), fewer cost bigger rebuilds.
- Resident heap: 32.4 GB (BINARY codes resident) + 24 GB page cache + ~6 GB other = ~62 GB of 80 GB.
- Builds: `graphBuildCacheSize=1000000`, `maxConcurrentRebuilds=2`, `graphBuildParallelism=8` (two CPU-bound builds
  on 18 cores). With ~18 GB of available heap and the default `rebuildMaxHeapPercent=90`, the ledger admits two
  7.3 GB builds side by side and holds the third (pinned by `twoBucketBuildsRunSideBySideAtThreeHundredMillionAndAThirdWaits`).

### 600M-900M vectors (does not fit as is)

- The location index alone is 19-29 GB at 600-900M, and no quantization shrinks it. BINARY with codes resident is
  65-97 GB; PRODUCT M=32 is 41-61 GB. With a 24 GB page cache neither fits an 80 GB heap at 900M, and raising
  `-Xmx` past ~90 GB starves the OS cache the stored-vector reads depend on.
- Options, in order of preference:
  1. **Move the location index and codes off heap** (memory-mapped or `Arena`-allocated, same primitive layout). This
     removes 36-108 B/vector from the heap and is the structural fix. Deferred: see below.
  2. Cap the BINARY code cache (`searchCacheSize`) and accept page reads for cold codes; resident cost drops toward 36
     B/vector (32.4 GB at 900M). Search latency then depends on page-cache hit rate.
  3. Keep only embeddings that are actually searched in the ANN index (e.g. one representative per identity cluster,
     the rest linked by edge), which is a data-model decision for Recon.
- Bucket count: 128 at 900M (~7M each). Buckets can be added later (`CREATE BUCKET Face_64`, then
  `ALTER TYPE Face BUCKET +Face_64`); existing rows do not move, new rows round-robin over all buckets, so growing 64 to
  128 halves the fill rate of the old ones.

## Tuning

- `arcadedb.vectorIndex.parallelSearch=false` turns the fan-out off for a database (for an A/B or a regression).
- `arcadedb.vectorSearchPoolThreads`: lower it on a node that also runs heavy Gremlin/graph-algorithm work.
- Watch `pool=dense_vector`: `queries.serial` growing alone means the gate keeps searches on callers (transactions
  with writes, REPEATABLE_READ, cold graphs, or load); `tasks.caller_run_fallbacks` means the queue is full.
- `arcadedb.vectorIndex.maxConcurrentRebuilds` above 1 is safe for heap now, not for CPU: lower
  `graphBuildParallelism` to `cores / maxConcurrentRebuilds`.

## Risks

- **HA: `.vecgraph` is rebuilt per node, not replicated.** Each of the 4 nodes pays every build (CPU, heap) itself, and a
  node that restarts or resyncs rebuilds every bucket graph whose persisted file it lacks. With 64 buckets at ~7 GB
  and a few minutes each, a cold node needs on the order of an hour of build time at `maxConcurrentRebuilds=2` before
  its first ANN query is fast; until then searches build on demand under the permits (slow, correct). Stagger node
  restarts and warm with `warmUp` before routing reads to a rebuilt node.
- Heap estimates are order-of-magnitude (`APPROX_GRAPH_BYTES_PER_NODE` was measured at 128-d). The ledger fails safe
  (it double counts a build whose memory a GC has already observed), so the cost of an error is a build that waits.
- The ledger is not fair: a large waiting build can be overtaken by smaller ones; its wait is bounded by
  `arcadedb.vectorIndex.rebuildPermitTimeoutMs`.
- Last-bit distance differences: jvector's Vector API kernels reduce in an order that depends on JIT state, so the
  same query can return distances that differ in the last float bit before and after warm-up (observed serially as
  well as across threads). Rows and order were identical in every test and benchmark run here.
- CPU per query grows with the bucket count (one walk per bucket). The fan-out turns that into latency headroom when
  cores are idle; at saturation it is the same CPU as before, and the in-flight gate keeps searches serial.

## Deferred

- Off-heap location index and BINARY/PQ codes (the 900M blocker).
- Parallel grouped top-up (the `groupBy` second phase, issue #8002), which runs only when a bucket ranked a winning group
  outside its own top k. The first phase of grouped searches, and multi-label logical indexes, do fan out.
- Loading or building the graphs of cold buckets in parallel on first search after open (kept on the caller on purpose).
- Benchmark at real scale (millions per bucket) on the target hardware with real ArcFace embeddings.
- Replicating or shipping `.vecgraph` files between nodes to avoid per-node rebuilds.
