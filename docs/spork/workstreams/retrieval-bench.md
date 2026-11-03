# Retrieval benchmark harness (workstream: read path)

Owner directive: optimize for retrieval speed above all. This harness is how every read-path change gets measured. It
lives in the `benchmarks/` Maven module (`arcadedb-benchmarks`, never deployed) and consists of:

- a synthetic **Recon-shaped dataset generator** at adjustable scale (default 5M records, `-Darcadedb.retrievalBench.records=50000000`
  for the large run), deterministic for a given seed;
- a **query mix** of nine retrieval shapes mirroring Recon (identifier lookup, 1-hop and 2-hop pivots, structured search,
  live feed, geo radius, full-text, vector kNN, hydration by RID);
- the **embedded leg** (Java API under JMH) and the **HTTP leg** (`POST /api/v1/query`, JSON vs NDJSON), each at 1, 8 and 32
  concurrent clients, warm and cold;
- **JFR profiling** of the hot path with flame-graph-derived tables (top frames by CPU and allocation, monitor contention,
  thread parks, synchronous I/O in query threads);
- a **regression gate** (`RetrievalRegressionGateBenchmark`, `@Tag("benchmark")`) asserting stall-discounted p95 against
  committed baselines.

## Why a module and not `engine/src/test`

The HTTP leg needs `arcadedb-server` on the classpath, which the engine test tree cannot have (the engine does not depend on
the server). A module also gives the JMH forks a clean classpath (`target/runtime-classpath.txt` is written at compile time
and the run script puts it after `target/classes`), keeps the generator reusable by other workstreams, and is picked up
automatically by `HardcodedTestServerPortsTest` (no hand-picked ports: the server binds the default 2480-2489 range and the
clients read the port back) and by the `benchmark-tests.yml` lane (`-Dgroups=benchmark -Dsurefire.includes=**/*Benchmark.java`).
Its ordinary unit tests (`*Test`, 18 of them, under 10 s) run in the default lane; they generate a 3,000-record dataset and
check the generator's shape, every query's result shape embedded, and HTTP parity between JSON and NDJSON.

## Layout

```
benchmarks/
  pom.xml                                   arcadedb-engine, arcadedb-server, JMH (GPLv2+CPE, already in ATTRIBUTIONS.md)
  bin/retrieval-bench.sh                    driver: generate | embedded | http | explain | jfr | all | full
  bin/logging.properties                    quiet JUL for runs
  src/main/java/com/arcadedb/benchmarks/retrieval/
    ReconDomain                             constants and pure functions: pools, keys, cities, vocabulary, style centroids
    ZipfSampler                             Zipf over ranks as a cumulative table + binary search (one double per rank)
    ReconSchema                             types, lightweight HAS_* edge types, the eleven indexes, vector warm-up
    ReconDataGenerator                      GraphBatch load + indexes + manifest (main)
    DatasetManifest                         <root>/manifest.json: scale, seed, time range, sample RIDs, timings
    QueryMix                                the nine queries and their parameter sampler
    EmbeddedRetrievalBenchmark              JMH: warm (SampleTime, 1/8/32 threads), cold (SingleShot forks), profile (JFR)
    HttpRetrievalBenchmark                  in-process server + closed-loop JDK HttpClient clients, JSON and NDJSON
    BenchServer, HttpQueryClient            server bootstrap on the dataset root; the client
    JfrTopFrames                            .jfr -> top-frame tables (main)
    ExplainPlans                            EXPLAIN of every query against a dataset (main)
    LatencyStats, BenchReport, BenchProps   primitives, Markdown/JSON tables, -D knobs
  src/test/java/.../retrieval/
    ZipfSamplerTest, LatencyStatsTest, JfrTopFramesTest, ReconDatasetTest      default lane
    RetrievalRegressionGateBenchmark                                           @Tag("benchmark") lane
  src/test/resources/retrieval-baselines.json                                  committed gate baselines
```

## The dataset

Shape (from `docs/spork/00-research-findings.md`):

| element | properties | skew |
|---|---|---|
| `Record` vertex | recordId, source, tsPosted, tsCollected (epoch ms, LONG), city, state, location (WKT `POINT (lon lat)`, 85% present), age, views, services (LIST of STRING, 1-4), text (20-60 words), styleVec (24 floats, unit) | sources Zipf 1.2; cities Zipf 1.0 over 300 synthetic cities (state Zipf 1.0 by population rank); recency `NOW - 730d * u^2` so the last day holds ~3.7% of two years (185K/day at 5M, matching the 150K-200K ads/day hint); age 18 + |N(0,1)| * 6; views lognormal; words Zipf 1.1 over 6,000 pronounceable tokens; style vectors around 200 centroids (Zipf 0.8) with Gaussian noise 0.35 |
| `Phone`, `Email`, `Handle`, `ImageHash` vertices | key (STRING, UNIQUE index) | pools of 35%, 10%, 15%, 60% of the record count; the pool index is the Zipf rank (rank 0 is the biggest hub) |
| `HAS_PHONE`, `HAS_EMAIL`, `HAS_HANDLE`, `HAS_IMAGE` | lightweight, bidirectional | per record: 1 phone (80%) or 2 (Zipf 0.8); email 30% (Zipf 0.9); handle 40% (Zipf 1.0); 0 (20%) or 1-4 image hashes (Zipf 0.7) |

Indexes (built after the load with the sorted build): `Phone[key]`, `Email[key]`, `Handle[key]`, `ImageHash[key]` UNIQUE;
`Record[tsPosted]`, `Record[state,tsPosted]`, `Record[age,tsPosted]` NOTUNIQUE; `Record[servicesbyitem]` (BY ITEM);
`Record[text]` FULL_TEXT (BM25); `Record[location]` GEOSPATIAL; `Record[styleVec]` LSM_VECTOR (24-d, COSINE).

Decisions worth knowing: timestamps are LONG epoch millis rather than DATETIME (cheapest comparable key, trivially bound over
HTTP); `Record` has one bucket by default (`-Darcadedb.retrievalBench.buckets=N` to change: one vector graph and one scan
unit per bucket, which is exactly what the vector-parallel workstream is about); identifier keys are pure functions of their
rank (`ReconDomain.phoneKey(rank)` etc.), so the query sampler never reads the database to find a key.

## The query mix

| id | shape | SQL |
|---|---|---|
| identLookup | identifier point lookup | `SELECT FROM Phone WHERE key = :key` |
| pivot1Hop | identifier -> records, newest first | `SELECT FROM (SELECT expand(in('HAS_PHONE')) FROM Phone WHERE key = :key) ORDER BY tsPosted DESC LIMIT 500` |
| pivot2Hop | identifier -> records -> identifiers -> records | `SELECT FROM (SELECT expand(in('HAS_PHONE').out('HAS_PHONE','HAS_IMAGE').in('HAS_PHONE','HAS_IMAGE')) FROM Phone WHERE key = :key) LIMIT 500` |
| structured | 3 filters, newest first | `SELECT FROM Record WHERE state = :state AND age >= :ageMin AND age <= :ageMax AND services CONTAINS :service ORDER BY tsPosted DESC LIMIT 100` |
| feed24h | last 24 h, located only | `SELECT FROM Record WHERE tsPosted >= :since AND location IS NOT NULL ORDER BY tsPosted DESC LIMIT 200` |
| geoRadius14d | 200 km + 14 days | `SELECT FROM Record WHERE geo.intersects(location, geo.geomFromText(:box)) = true AND geo.dWithin(location, geo.point(:lon, :lat), :radiusDeg) = true AND tsPosted >= :since LIMIT 500` |
| fulltextTop20 | two terms, BM25 top 20 | `SELECT FROM Record WHERE SEARCH_INDEX('Record[text]', :q) = true ORDER BY $score DESC LIMIT 20` |
| vectorKnn20 | 24-d kNN | ``SELECT expand(`vector.neighbors`('Record[styleVec]', :vector, 20))`` |
| hydrate200 | 200 records by RID | `SELECT FROM Record WHERE @rid IN :rids` |

Parameter sampling (`QueryMix.ParamSampler`): pivot and lookup keys follow the data's Zipf truncated to the ranks expected to
have at least one record (a user pivots on an identifier taken from a record), so hubs are hit as often as they are
referenced and the p99 is the hub cost; windows are relative to the newest record in the manifest; geo centres are the cities
records cluster in (the bounding box is the index-backed coarse filter, `geo.dWithin` the exact one, since on `main`
`geo.dWithin` alone is evaluated inline over a full scan); full-text terms are two mid-frequency words (ranks 30-600);
hydration picks 200 distinct RIDs from a 20,000-RID sample written by the generator.

## How to run

Build and run the standard set at the default 5M scale (one command, writes `target/retrieval-results/`):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) benchmarks/bin/retrieval-bench.sh all
```

`all` is the run that fits the 20-minute budget at 5M: generation (skipped when a dataset with the same `records` and
`seed` is already at the root; `-Darcadedb.retrievalBench.regenerate=true` forces it), embedded warm at 1 and 8 threads with
one 3 s measurement after a 2 s warm-up, embedded cold, and HTTP JSON and NDJSON at 1 and 8 clients with 2 s windows. `full`
is the matrix the numbers below come from: 1, 8 and 32 clients, the default windows (3 x 4 s embedded, 4 s HTTP), the JFR
profiles of the top four queries and the EXPLAIN plans. Flags on the command line override the stage's own.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) benchmarks/bin/retrieval-bench.sh full
```

Stages, each accepting `-Darcadedb.retrievalBench.*` knobs (and any `arcadedb.*` engine setting, forwarded to JMH forks):

```bash
benchmarks/bin/retrieval-bench.sh generate -Darcadedb.retrievalBench.records=5000000 -Darcadedb.retrievalBench.seed=42
```

```bash
benchmarks/bin/retrieval-bench.sh embedded -Darcadedb.retrievalBench.mode=warm -Darcadedb.retrievalBench.threads=1,8,32
```

```bash
benchmarks/bin/retrieval-bench.sh embedded -Darcadedb.retrievalBench.mode=cold
```

```bash
benchmarks/bin/retrieval-bench.sh http -Darcadedb.retrievalBench.formats=json,ndjson -Darcadedb.retrievalBench.measureSeconds=4
```

```bash
benchmarks/bin/retrieval-bench.sh embedded -Darcadedb.retrievalBench.mode=profile -Darcadedb.retrievalBench.profileQueries=pivot1Hop,structured,fulltextTop20,vectorKnn20
```

```bash
benchmarks/bin/retrieval-bench.sh explain
```

Knobs: `root` (dataset root, default `target/retrieval-bench/<records>`), `records`, `seed`, `buckets`, `txSize`,
`results` (output dir), `threads`, `queries` (comma-separated ids), `warmupIterations`/`warmupSeconds`/`measureIterations`/
`measureSeconds`, `formats`, `forkHeap` (JMH forks, default 12g), `profileQueries`/`profileThreads`/`profileSeconds`/`profileTop`,
`mode`. The driver JVM heap is `RETRIEVAL_BENCH_HEAP` (default 16g, ZGC); `RETRIEVAL_BENCH_REBUILD=0` skips the Maven build.
The build is always `-pl benchmarks -am`: without `-am` the classpath file points at `~/.m2` jars that may predate your edits.

Cold runs: a JMH fork is a fresh JVM with an empty ArcadeDB page cache and an unloaded vector graph. The OS file cache is
not dropped by the harness (macOS needs `sudo purge`, Linux `echo 3 > /proc/sys/vm/drop_caches`); run that first for a
truly cold disk. HTTP cold is the first request of each query after the server started.

The regression gate (benchmark lane only):

```bash
mvn -o -pl benchmarks -am verify -Dgroups=benchmark -DexcludedGroups= -Dsurefire.includes='**/RetrievalRegressionGateBenchmark.java' -Dsurefire.failIfNoSpecifiedTests=false
```

The include pattern has to name the class: `-am` puts the engine in the reactor, and `**/*Benchmark.java` runs the engine's
whole `benchmark` lane first (two hours on this machine, and one of those classes fails on its own) before the gate is reached.

It generates (once, reused on later runs) a 300K-record dataset under `target/retrieval-gate`, measures 300 warm single-thread
samples per query with the JVM-wide stall discounted from each (`StallAwareStopwatch.jvmStallNanos()` differenced around the
query, per the repository rule), and asserts p95 under baseline x headroom (3.0). It writes
`target/retrieval-results/gate-measured.json`; copy it over `src/test/resources/retrieval-baselines.json` to re-baseline after
an accepted change.

## Baseline: this machine

Hardware: Apple M5 Max, 18 cores, 128 GB RAM, Apple NVMe (APFS), macOS 27.0; OpenJDK 21.0.11 (Homebrew), ZGC, driver heap
24 GB, `arcadedb.maxPageRAM=12288` MB for the warm runs (the whole 6.5 GB dataset fits the page cache, which is the "hot
set fits" case; a 600 GB corpus on a 126 GB node is the other case and needs the cold numbers). Numbers from 2026-11-02 on
branch `claude/read-bench-63f0a8` at `4bc577f11` (main), no engine changes.

### Generation at 5M (seed 42, one bucket, WAL on)

5,000,000 Record vertices, 6,000,000 identifier vertices, 19,494,466 lightweight edges; 6.5 GB on disk.

| phase | seconds |
|---|---:|
| identifier vertices (6M) | 6.6 |
| records + outgoing edges (5M, GraphBatch) | 62.3 |
| incoming-edge connection (close) | 46.1 |
| load total | 115.0 |
| UNIQUE indexes on the four identifier keys (sorted build) | 11.4 |
| `Record[tsPosted]`, `Record[state,tsPosted]`, `Record[age,tsPosted]` (sorted build) | 36.2 |
| `Record[servicesbyitem]` | 3.7 |
| `Record[text]` FULL_TEXT (about 200M tokens) | 135.7 |
| `Record[location]` GEOSPATIAL | 21.9 |
| `Record[styleVec]` LSM_VECTOR, graph built synchronously | 412.9 |
| indexes total | 621.8 |
| **generation total** | **737.0 (12.3 min)** |

The vector graph build is 56% of the generation and the full-text index another 18%; both scale a little worse than
linearly (500K: 32.5 s and 11.5 s; 1M: 75.5 s and 20.6 s). At 50M expect roughly 1.5 to 2 hours, dominated by the vector
graph; generate once and keep the root.

Largest files: `Record_0` bucket 3,345 MB (about 700 bytes per record), the full-text index 1,042 MB, the vector graph
649 MB, out-edges 259 MB, `ImageHash` in-edges 179 MB.

### A load-path finding on the way: `GraphBatch.withWAL(false)` retains heap until it is exhausted

The first 5M attempt used `withWAL(false)`, as the GraphBatch Javadoc recommends for imports, with a 24 GB heap. It ran out
of heap at `close()`. A 2M run with a 12 GB heap and `-Xlog:gc` shows live heap after GC growing with the records written,
about 8 KB per record (844 MB at 7 s, 7.7 GB at 22 s, 12 GB at 28 s), until every collection is an allocation stall. The
same 1M load with WAL on and an 8 GB heap peaks at 7 GB and collects back to about 2 GB every cycle. The generator now
defaults to WAL on (`-Darcadedb.retrievalBench.wal=false` reproduces the growth). Not investigated further here (engine
code is out of scope for this workstream); the repro is one command and the suspect is the page publication path when the
commit has no WAL ticket (`TransactionContext.commit2ndPhase` -> `PageManager.publishPages`).

### Stage timings of the `full` run (5M, this machine)

| stage | seconds | what ran |
|---|---:|---|
| generate | 737 | load + 11 indexes (table above) |
| embedded warm | 699 | 9 queries x 3 thread levels, 1 x 3 s warm-up + 2 x 4 s measured, one JVM |
| embedded cold | 10 | 9 forked JVMs, one query each |
| http | 385 | 9 queries x 2 encodings x 3 client counts, 2 s warm-up + 4 s measured, in-process server |
| profile | 126 | 4 forked JVMs with JFR, 8 threads, 5 s warm-up + 20 s recorded |
| explain | 3 | |
| **total** | **1,960 (32.7 min)** | |

The measurement half is 20 minutes on its own because a JMH iteration cannot end before every thread's in-flight query
does: `structured` takes 10 to 57 s per query at 32 threads, so its two nominal 4 s iterations take two minutes. That is the
bottleneck, not the harness. The `all` stage (1 and 8 clients, shorter windows) is the run that fits the budget:
measured at 246 s for the benchmark half on this machine (one Maven compile, embedded warm at 1 and 8 threads, cold,
HTTP JSON and NDJSON at 1 and 8 clients), so with the 737 s generation `all` is 983 s, **16.4 minutes end to end at 5M**.
Its numbers are a smoke test and a regression check, not the baseline: a 3 s window holds three samples of a one-second
query (geoRadius14d at one thread came out at 1,258 ms p50 from 3 samples on a page cache that was still filling, against
22 ms over 144 samples in `full`). Read the slow queries from `full`; the fast ones agree within noise between the two
(identLookup 5 us in both, hydrate200 0.14 against 0.12 ms, HTTP hydrate200 1.6 against 2.0 ms).

### Embedded, warm (JMH SampleTime, one JVM, page cache warm, vector graph loaded; ms)

| query | t=1 p50 | p95 | p99 | q/s | t=8 p50 | p95 | p99 | q/s | t=32 p50 | p95 | p99 | q/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| identLookup | 0.005 | 0.008 | 0.016 | 172,557 | 0.008 | 0.025 | 0.140 | 515,920 | 0.007 | 0.221 | 0.944 | 737,199 |
| pivot1Hop | 0.131 | 12.9 | 52.3 | 423 | 0.072 | 17.5 | 80.7 | 2,388 | 0.068 | 44.0 | 180.6 | 4,319 |
| pivot2Hop | 1.15 | 121.0 | 477.1 | 44 | 3.50 | 650.6 | 2,060 | 73 | 11.6 | 2,093 | 5,968 | 87 |
| structured | 372.8 | 1,877 | 1,877 | 1.3 | 1,009 | 5,270 | 5,469 | 4.0 | 10,335 | 51,003 | 57,579 | 1.7 |
| feed24h | 0.096 | 0.162 | 0.253 | 9,386 | 0.246 | 0.541 | 1.77 | 24,211 | 0.291 | 0.709 | 12.9 | 48,811 |
| geoRadius14d | 22.2 | 260.8 | 741.0 | 17 | 36.2 | 103.9 | 197.9 | 168 | 144.4 | 433.7 | 800.1 | 171 |
| fulltextTop20 | 112.7 | 470.3 | 710.9 | 6.2 | 229.0 | 939.3 | 1,868 | 22 | 750.8 | 4,015 | 5,928 | 24 |
| vectorKnn20 | 1.86 | 2.82 | 3.51 | 515 | 0.692 | 1.00 | 1.06 | 10,838 | 0.869 | 6.69 | 26.5 | 17,031 |
| hydrate200 | 0.122 | 0.171 | 0.198 | 8,110 | 0.190 | 0.284 | 0.324 | 40,363 | 0.256 | 0.469 | 12.8 | 57,174 |

`rows/q` is not reported by the JMH leg; the HTTP table has it. Each query returns what its LIMIT allows: pivot1Hop averages
115 to 120 of the 500 allowed because most identifiers have fewer records than that, structured averages 54 to 100 because
some (state, age band, service) combinations have fewer than 100 matches.

Reading it:

- identLookup, hydrate200 and feed24h are where the engine should be: 5, 122 and 96 us at one thread, scaling to 737K,
  57K and 49K q/s at 32 threads. feed24h is the shape that works: the index on `tsPosted` is walked in order, no sort step.
- structured is the outlier of the mix: 373 ms p50 at one thread for a query that returns 100 rows, 1 s at 8 threads,
  10 s at 32, and it is the same three-predicate-plus-ORDER-BY shape as feed24h. Finding 1 below.
- pivot1Hop has a bimodal distribution (p50 0.13 ms, p95 13 ms, p99 52 ms): the median identifier has a handful of records,
  the Zipf tail has thousands, and the cost per record of the ORDER BY is what the p95 measures. Finding 3.
- pivot2Hop at p99 is 0.5 s at one thread and 6 s at 32, with 10 s maxima, even with hubs excluded from its parameters
  (with hubs it exhausts a 16 GB heap). Finding 2.
- fulltextTop20 is 113 ms p50 for two terms with document frequencies of 34K and 66K: the whole posting lists are
  materialised and scored. Finding 5.
- geoRadius14d is 22 ms p50 with a 741 ms p99 (the p99 is a dense city). Finding 6.
- vectorKnn20 is 1.9 ms at one thread and 0.7 ms at 8: the one-thread number is JIT warm-up order (vector runs last in its
  JMH group), the 8-thread one is the steady state.
- At 32 threads on 18 cores, feed24h and hydrate200 show 12.9 ms p99 and 125 ms maxima that are absent at 8 threads: those
  are ZGC allocation stalls. structured allocates 12 GB/s at 8 threads (profile below); at 32 the run is GC-bound, which is
  why its throughput falls from 4.0 to 1.7 q/s while the thread count quadruples. The scaling problem of this engine on a
  read-heavy box is allocation, not locks (see "Locks and synchronous I/O").

### Cold (fresh JVM, empty page cache, vector graph not loaded) against warm

| query | embedded, fresh JVM (ms) | HTTP first request (ms) | embedded warm p50 at t=1 (ms) | HTTP JSON warm p50 at 1 client (ms) |
|---|---:|---:|---:|---:|
| identLookup | 153.1 | 96.9 | 0.005 | 0.093 |
| pivot1Hop | 190.6 | 78.2 | 0.131 | 0.334 |
| pivot2Hop | 185.4 | 58.0 | 1.15 | 6.95 |
| structured | 2,155 | 746.3 | 372.8 | 581.1 |
| feed24h | 400.3 | 13.2 | 0.096 | 1.95 |
| geoRadius14d | 438.0 | 113.3 | 22.2 | 24.4 |
| fulltextTop20 | 660.6 | 349.3 | 112.7 | 68.6 |
| vectorKnn20 | 1,044 | 833.6 | 1.86 | 3.30 |
| hydrate200 | 195.6 | 9.20 | 0.122 | 2.01 |

The embedded cold column includes opening the database (schema, dictionary, index metadata, about 150 ms of the identLookup
number) and, for vectorKnn20, loading the 649 MB graph. The HTTP cold column is the first request to an already-open server,
so it isolates the page cache misses: 9 ms for 200 random RIDs, 13 ms for the feed, 113 ms for the geo query, 746 ms for
structured, 834 ms for the first vector search (graph load). These are the numbers that matter for a corpus that does not
fit RAM; the warm numbers are the ones for a hot set that does.

### HTTP (`POST /api/v1/query`, in-process server, closed-loop clients, JDK HttpClient; ms)

| query | format | 1 client p50 | p95 | q/s | 8 clients p50 | p95 | q/s | 32 clients p50 | p95 | q/s | rows/q |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| identLookup | json | 0.093 | 0.201 | 8,887 | 0.285 | 0.383 | 26,986 | 1.07 | 1.88 | 25,623 | 1 |
| identLookup | ndjson | 0.122 | 0.223 | 7,023 | 0.447 | 0.683 | 16,836 | 1.32 | 2.28 | 22,617 | 1 |
| pivot1Hop | json | 0.334 | 12.0 | 395 | 0.432 | 18.9 | 2,069 | 1.29 | 52.3 | 3,063 | 120 |
| pivot1Hop | ndjson | 0.473 | 15.3 | 324 | 0.756 | 26.7 | 1,498 | 2.80 | 74.4 | 2,116 | 122 |
| pivot2Hop | json | 6.95 | 132.0 | 36 | 10.2 | 904.9 | 68 | 28.8 | 2,021 | 90 | 394 |
| pivot2Hop | ndjson | 3.86 | 126.3 | 48 | 9.76 | 639.5 | 72 | 26.2 | 2,488 | 52 | 415 |
| structured | json | 581.1 | 1,131 | 1.8 | 620.2 | 4,632 | 4.5 | 8,364 | 13,686 | 0.8 | 65 |
| structured | ndjson | 370.2 | 1,285 | 2.2 | 772.3 | 3,348 | 3.5 | 13,108 | 23,975 | 1.2 | 92 |
| feed24h | json | 1.95 | 2.72 | 489 | 3.15 | 4.08 | 2,458 | 8.09 | 13.4 | 3,752 | 200 |
| feed24h | ndjson | 2.27 | 2.73 | 430 | 3.00 | 4.70 | 2,459 | 8.87 | 14.0 | 3,445 | 200 |
| geoRadius14d | json | 24.4 | 64.7 | 33 | 36.9 | 102.9 | 170 | 101.5 | 299.6 | 252 | 499 |
| geoRadius14d | ndjson | 22.9 | 55.2 | 36 | 40.1 | 91.0 | 160 | 135.8 | 320.7 | 204 | 499 |
| fulltextTop20 | json | 68.6 | 208.3 | 10 | 121.4 | 479.1 | 48 | 932.6 | 3,556 | 26 | 20 |
| fulltextTop20 | ndjson | 143.0 | 460.4 | 5.0 | 155.5 | 739.9 | 32 | 490.6 | 2,283 | 45 | 20 |
| vectorKnn20 | json | 3.30 | 4.26 | 288 | 1.37 | 1.72 | 5,654 | 3.24 | 7.15 | 8,266 | 20 |
| vectorKnn20 | ndjson | 1.39 | 1.97 | 688 | 1.51 | 1.92 | 5,094 | 4.03 | 9.27 | 6,605 | 20 |
| hydrate200 | json | 2.01 | 2.53 | 474 | 3.09 | 3.77 | 2,499 | 6.74 | 10.6 | 4,447 | 200 |
| hydrate200 | ndjson | 1.43 | 2.03 | 648 | 3.19 | 5.74 | 2,164 | 10.8 | 19.7 | 2,731 | 200 |

Embedded against HTTP at one client:

| query | embedded p50 (ms) | HTTP JSON p50 (ms) | ratio | HTTP NDJSON p50 (ms) |
|---|---:|---:|---:|---:|
| identLookup | 0.005 | 0.093 | 18.6x | 0.122 |
| pivot1Hop | 0.131 | 0.334 | 2.6x | 0.473 |
| pivot2Hop | 1.15 | 6.95 | 6.0x | 3.86 |
| structured | 372.8 | 581.1 | 1.6x | 370.2 |
| feed24h | 0.096 | 1.95 | 20.4x | 2.27 |
| geoRadius14d | 22.2 | 24.4 | 1.1x | 22.9 |
| fulltextTop20 | 112.7 | 68.6 | 0.6x | 143.0 |
| vectorKnn20 | 1.86 | 3.30 | 1.8x | 1.39 |
| hydrate200 | 0.122 | 2.01 | 16.5x | 1.43 |

- The HTTP layer costs about 2 ms per 200-row answer regardless of what the query did: hydrate200 goes from 0.12 to 2.0 ms,
  feed24h from 0.10 to 1.95 ms. For the queries Recon issues most (pivot, hydrate, feed) the serialization is the query.
  Finding 7.
- NDJSON is not cheaper than JSON for these answers: it wins at one client on hydrate200 and vectorKnn20 (where the JSON run
  went first and paid the JIT), loses at 8 and 32 clients everywhere (hydrate200 at 32 clients: 10.8 ms against 6.7 ms). It
  streams the same per-row `JSONObject` through a keep-alive timer and a bounded writer per row; its value is memory, not
  latency.
- identLookup over HTTP is 93 us against 5 us embedded: that is the floor of the stack (parse, auth, transaction, JSON) and it
  caps a single client at 8.9K q/s and 32 clients at 26K q/s. The embedded engine does 737K.
- fulltextTop20 is faster over HTTP than embedded at one client (69 against 113 ms) because the HTTP run came after the
  JMH warm run had already paged the full-text index in; the two runs are not interleaved.

## Profiles (JFR, 8 threads, 20 s recorded after a 5 s warm-up, forked JVM with a 12 GB heap)

The flame-graph-derived tables for the four most expensive queries. The full tables (top 25 of every category, inclusive
frames, allocated classes, monitors, parks, I/O) are in `target/retrieval-results/profile-<query>-5000000.md`, the recordings
next to them for `jfr print` or JMC. The fork's `arcadedb.maxPageRAM` was clamped to 6 GB (80% of its heap) against a 6.5 GB
dataset, so these profiles include a page cache that does not quite hold the data, which is the realistic case.

### pivot1Hop

CPU samples: 7,937. Allocation samples weight: 100,314 MB. GC: 41 collections, 520 ms total.

62,944 queries in the 20 s (2.5 ms mean, 51 us median). 70% of the CPU is under `OrderBy.compare` (inclusive table), reached from `OrderByStep.initTopK` (query/sql/executor/OrderByStep.java:213 and the comparator at :347): every comparison re-reads the sort key from the record bytes, `ResultInternal.getProperty:248` -> `ImmutableDocument.get:92` -> `BinarySerializer.deserializePropertyOf:586`, which walks the record header and boxes a `Long` (serializer/BinarySerializer.java:1173 is 20% of all allocation). The other half is the per-record load path: the striped read lock (`RWLockContext.readLock:76`, 11%), the thread-local database context (`DatabaseContext.getContextIfExists:134`, 9%), and `LocalSchema.hasTriggerListeners:6219` (6%: a `ConcurrentHashMap.isEmpty()` per record, which is `sumCount()` over the cells).

Top ArcadeDB frames by self CPU (first `com.arcadedb` frame on the stack; paths relative to `engine/src/main/java/com/arcadedb/`):

| frame | samples | share |
|---|---:|---:|
| serializer/BinarySerializer.java:1128 BinarySerializer.deserializeValue | 1040 | 13.1% |
| database/Binary.java:623 Binary.getUnsignedNumber | 947 | 11.9% |
| utility/RWLockContext.java:76 RWLockContext.readLock | 899 | 11.3% |
| database/DatabaseContext.java:134 DatabaseContext.getContextIfExists | 730 | 9.2% |
| serializer/BinarySerializer.java:586 BinarySerializer.deserializePropertyOf | 623 | 7.8% |
| database/Binary.java:860 Binary.slice | 556 | 7.0% |
| schema/LocalSchema.java:6219 LocalSchema.hasTriggerListeners | 490 | 6.2% |
| utility/RWLockContext.java:82 RWLockContext.readUnlock | 448 | 5.6% |
| serializer/BinaryComparator.java:791 BinaryComparator.compareTo | 255 | 3.2% |
| database/Binary.java:557 Binary.getUnsignedNumberAt | 241 | 3.0% |

Top allocation sites (first `com.arcadedb` frame, then the class allocated):

| site | samples | MB | share |
|---|---:|---:|---:|
| serializer/BinarySerializer.java:1173 BinarySerializer.deserializeValue <- Long | 5323 | 19811.7 | 19.7% |
| database/BasicDatabase.java:204 BasicDatabase.newRID <- DatabaseRID | 2577 | 9628.5 | 9.6% |
| database/Binary.java:860 Binary.slice <- HeapByteBuffer | 2298 | 8687.0 | 8.7% |
| query/sql/executor/ExpandStep.java:106 ExpandStep.fetchNext <- ResultInternal | 2282 | 8627.5 | 8.6% |
| database/Binary.java:90 Binary.<init> <- HeapByteBuffer | 2266 | 8466.9 | 8.4% |
| database/RecordFactory.java:41 RecordFactory.newImmutableRecord <- ImmutableVertex | 2190 | 8193.8 | 8.2% |
| engine/BasePage.java:48 BasePage.<init> <- Binary | 1576 | 5914.0 | 5.9% |
| database/Binary.java:863 Binary.slice <- Binary | 1541 | 5709.8 | 5.7% |

### structured

CPU samples: 6,512. Allocation samples weight: 242,795 MB. GC: 127 collections, 20,374 ms total.

85 queries in the 20 s (2.0 s mean). 1 GB allocated per query: `Binary.copyOfContent` (database/Binary.java:105) alone is 86 GB of the 243 GB, one copy of every record the `servicesbyitem` index returned (`LocalDatabase.lookupByRID:1266`), before the `state` and `age` filters drop 99% of them. The index side is `LSMTreeIndexAbstract.lookupInPageAndAddInResultset:1081` (11% CPU, one `IndexCursorEntry` plus a `HashMap$Node` per entry) feeding `TypeIndex.get:252`'s `HashSet`, then `FetchFromIndexStep$1.next:196` and `GetValueFromIndexEntryStep.toResult:996` build two `ResultInternal`s (each a `LinkedHashMap`) per entry to carry one RID to the next step. GC ran 20 s of concurrent cycles in the 20 s window.

Top ArcadeDB frames by self CPU (first `com.arcadedb` frame on the stack; paths relative to `engine/src/main/java/com/arcadedb/`):

| frame | samples | share |
|---|---:|---:|
| utility/RWLockContext.java:76 RWLockContext.readLock | 779 | 12.0% |
| index/lsm/LSMTreeIndexAbstract.java:1081 LSMTreeIndexAbstract.lookupInPageAndAddInResultset | 708 | 10.9% |
| query/sql/executor/ResultInternal.java:221 ResultInternal.setProperty | 507 | 7.8% |
| index/TypeIndex.java:252 TypeIndex.get | 436 | 6.7% |
| engine/LocalBucket.java:2603 LocalBucket.getRecordInternal | 378 | 5.8% |
| database/ImmutableDocument.java:113 ImmutableDocument.getIfPresent | 314 | 4.8% |
| database/TransactionContext.java:1881 TransactionContext.getPageNotHeld | 286 | 4.4% |
| query/sql/executor/BasicCommandContext.java:299 BasicCommandContext.setVariable | 230 | 3.5% |
| database/IndexCursorCollection.java:97 IndexCursorCollection.next | 225 | 3.5% |
| database/DatabaseContext.java:134 DatabaseContext.getContextIfExists | 218 | 3.3% |

Top allocation sites (first `com.arcadedb` frame, then the class allocated):

| site | samples | MB | share |
|---|---:|---:|---:|
| database/Binary.java:105 Binary.copyOfContent <- [B | 21148 | 86158.3 | 35.5% |
| database/Binary.java:90 Binary.<init> <- HeapByteBuffer | 3816 | 15826.7 | 6.5% |
| query/sql/executor/ResultInternal.java:221 ResultInternal.setProperty <- LinkedHashMap$Entry | 2764 | 11839.0 | 4.9% |
| query/sql/executor/ResultInternal.java:85 ResultInternal.<init> <- LinkedHashMap | 2128 | 8623.1 | 3.6% |
| query/sql/executor/FetchFromIndexStep.java:196 FetchFromIndexStep$1.next <- ResultInternal | 1930 | 7939.9 | 3.3% |
| database/Binary.java:860 Binary.slice <- HeapByteBuffer | 1871 | 7872.2 | 3.2% |
| database/RecordFactory.java:55 RecordFactory.newImmutableRecord <- ImmutableVertex | 1910 | 7701.0 | 3.2% |
| query/sql/executor/GetValueFromIndexEntryStep.java:996 GetValueFromIndexEntryStep.toResult <- ResultInternal | 1868 | 7694.4 | 3.2% |

### fulltextTop20

CPU samples: 6,770. Allocation samples weight: 91,590 MB. GC: 71 collections, 6,964 ms total.

633 queries in the 20 s (252 ms mean), 145 MB allocated per query. Half the CPU is `HashMap` work: `FullTextQueryExecutor.collectTermMatches:625` puts every posting of every term into a `HashMap<RID, AtomicInteger>` (`computeIfAbsent`), `LSMTreeFullTextIndex.computeBM25Scores:532` builds a second `HashMap<RID, double[]>` over the same RIDs, `canonicalRID:669` allocates a third RID object per candidate, and `buildScoredCursor:701` finally keeps 20. `RID.equals:181` alone is 7%. Underneath, `lookupInPageAndAddInResultset:1081` (16%) materialises each posting as an `IndexCursorEntry` plus `FullTextPostingRID` (`readEntryValue:898`). No posting is skipped: the two terms have 34K and 66K postings and all 100K are visited and hashed three times for a top 20.

Top ArcadeDB frames by self CPU (first `com.arcadedb` frame on the stack; paths relative to `engine/src/main/java/com/arcadedb/`):

| frame | samples | share |
|---|---:|---:|
| index/lsm/LSMTreeIndexAbstract.java:1081 LSMTreeIndexAbstract.lookupInPageAndAddInResultset | 1087 | 16.1% |
| index/fulltext/FullTextQueryExecutor.java:625 FullTextQueryExecutor.collectTermMatches | 955 | 14.1% |
| index/TempIndexCursor.java:64 TempIndexCursor.next | 680 | 10.0% |
| database/RID.java:181 RID.equals | 478 | 7.1% |
| database/Binary.java:507 Binary.mismatch | 445 | 6.6% |
| index/fulltext/FullTextQueryExecutor.java:411 FullTextQueryExecutor.collectMatches | 414 | 6.1% |
| index/fulltext/LSMTreeFullTextIndex.java:532 LSMTreeFullTextIndex.computeBM25Scores | 374 | 5.5% |
| index/fulltext/FullTextQueryExecutor.java:409 FullTextQueryExecutor.collectMatches | 333 | 4.9% |
| index/lsm/LSMTreeIndexAbstract.java:1070 LSMTreeIndexAbstract.lookupInPageAndAddInResultset | 309 | 4.6% |
| index/fulltext/LSMTreeFullTextIndex.java:559 LSMTreeFullTextIndex.computeBM25Scores | 289 | 4.3% |

Top allocation sites (first `com.arcadedb` frame, then the class allocated):

| site | samples | MB | share |
|---|---:|---:|---:|
| index/lsm/LSMTreeIndexAbstract.java:898 LSMTreeIndexAbstract.readEntryValue <- FullTextPostingRID | 3907 | 12487.5 | 13.6% |
| index/lsm/LSMTreeIndexAbstract.java:1081 LSMTreeIndexAbstract.lookupInPageAndAddInResultset <- IndexCursorEntry | 3185 | 10346.4 | 11.3% |
| database/BasicDatabase.java:204 BasicDatabase.newRID <- DatabaseRID | 3101 | 10114.5 | 11.0% |
| index/lsm/LSMTreeIndexAbstract.java:1081 LSMTreeIndexAbstract.lookupInPageAndAddInResultset <- HashMap$Node | 3070 | 9909.4 | 10.8% |
| index/fulltext/FullTextQueryExecutor.java:411 FullTextQueryExecutor.collectMatches <- HashMap$Node | 1750 | 5923.1 | 6.5% |
| index/fulltext/FullTextQueryExecutor.java:625 FullTextQueryExecutor.collectTermMatches <- HashMap$Node | 1677 | 5464.1 | 6.0% |
| index/fulltext/LSMTreeFullTextIndex.java:532 LSMTreeFullTextIndex.computeBM25Scores <- HashMap$Node | 1537 | 5102.2 | 5.6% |
| index/fulltext/LSMTreeFullTextIndex.java:669 LSMTreeFullTextIndex.canonicalRID <- DatabaseRID | 1430 | 4753.5 | 5.2% |

### vectorKnn20

CPU samples: 8,369. Allocation samples weight: 16,846 MB. GC: 30 collections, 456 ms total.

220,888 queries in the 20 s (724 us mean). 53% of the CPU is inside jvector under `LSMVectorIndex.walkGraph:9172`: the visited set (`IntHashSet.add`, 35% of all samples) and the candidate heap (`AbstractLongHeap.upHeap`, 14%) outweigh the cosine kernel (9%). The engine-side cost is `FlatVectorCache.count:343` (6%: a striped VarHandle counter bumped on every similarity call), `DatabaseContext.getContextIfExists` (9%, the record load of the 20 results) and `ContiguousPageReader.read:197`. Allocation is low (17 GB in 20 s, mostly the result records' `Binary` copies at database/Binary.java:74). The lock on `FlatVectorCache.chunkFor:328` shows 21 waits for 108 ms in total: cold chunks allocated under a monitor in the first seconds, gone in steady state.

Top ArcadeDB frames by self CPU (first `com.arcadedb` frame on the stack; paths relative to `engine/src/main/java/com/arcadedb/`):

| frame | samples | share |
|---|---:|---:|
| index/vector/LSMVectorIndex.java:9172 LSMVectorIndex.walkGraph | 4454 | 53.2% |
| index/vector/FlatVectorCache.java:314 FlatVectorCache.similarity | 760 | 9.1% |
| database/DatabaseContext.java:135 DatabaseContext.getContextIfExists | 728 | 8.7% |
| index/vector/ArcadePageVectorValues.java:257 ArcadePageVectorValues.similarity | 677 | 8.1% |
| index/vector/FlatVectorCache.java:343 FlatVectorCache.count | 529 | 6.3% |
| index/vector/ContiguousPageReader.java:197 ContiguousPageReader.read | 232 | 2.8% |
| database/DatabaseContext.java:134 DatabaseContext.getContextIfExists | 92 | 1.1% |
| index/vector/ContiguousPageReader.java:200 ContiguousPageReader.read | 89 | 1.1% |
| index/vector/FlatVectorCache.java:156 FlatVectorCache.similarity | 79 | 0.9% |
| index/vector/FlatVectorCache.java:158 FlatVectorCache.similarity | 60 | 0.7% |

Top allocation sites (first `com.arcadedb` frame, then the class allocated):

| site | samples | MB | share |
|---|---:|---:|---:|
| database/Binary.java:74 Binary.<init> <- [B | 1032 | 4008.4 | 23.8% |
| index/vector/LSMVectorIndex.java:9172 LSMVectorIndex.walkGraph <- [I | 324 | 1235.7 | 7.3% |
| index/vector/LSMVectorIndex.java:9172 LSMVectorIndex.walkGraph <- Object; | 297 | 1086.7 | 6.5% |
| index/vector/ContiguousPageReader.java:295 ContiguousPageReader.ensurePageHolds <- PageId | 228 | 852.6 | 5.1% |
| index/vector/LSMVectorIndex.java:9172 LSMVectorIndex.walkGraph <- Float | 223 | 831.1 | 4.9% |
| index/vector/LSMVectorIndex.java:9172 LSMVectorIndex.walkGraph <- 0x000000d8012fd350 | 205 | 710.9 | 4.2% |
| database/RecordFactory.java:41 RecordFactory.newImmutableRecord <- ImmutableVertex | 232 | 574.2 | 3.4% |
| database/Binary.java:310 Binary.getFloatArray <- [F | 303 | 534.8 | 3.2% |


### HTTP serialization profile (hydrate200 and feed24h, JSON, 8 clients, 10 s)

Recorded with the same JFR settings during an HTTP run of hydrate200 and feed24h at 8 JSON clients (3 s warm-up, 10 s
measured, 24 GB heap). The recording's own overhead (allocation sampling, file and socket events at a 0 ms threshold) raised
hydrate200 from 3.1 to 8.7 ms p50, so what it is good for is the shares, not the latencies. Full tables in
`target/retrieval-results-full-matrix/profile-http-5000000.md`. 64% of the CPU samples are on the eight `XNIO-1 task`
threads (the server), the rest on the benchmark's own client threads, which parse every response with the engine's
`JSONObject(String)` (Gson) in the same JVM: `JSONObject.<init>:91` is 30% of the JVM's CPU and 25% of its allocation,
and that is the harness, not the server. It also means the HTTP table above was measured with the client competing for the
same 18 cores at 8 and 32 clients; a client that counted rows without a full parse would move the 32-client numbers a
little. Server side, by inclusive share of all samples:

| share | where | what |
|---:|---|---|
| 66.9% | `server/.../AbstractServerHttpHandler.java:1013 handleRequest` | the whole request on the XNIO thread |
| 32.2% | `AbstractQueryHandler.java:273 serializeResultSetBounded` -> `:579 serializeResultSet` | building and writing the answer |
| 30.9% | `engine/.../serializer/json/JSONObject.java:544 toString` | `Gson.toJson` of the finished response tree into a `String` |
| 23.6% | `AbstractQueryHandler.java:608 serializeRows` -> `JsonSerializer.java:149 serializeResult` | one `JSONObject` per row: `getPropertyNames` (a header walk, `BinarySerializer.java:366`), one `getProperty` per property (another walk each, `ImmutableDocument.get:92`) |
| 10.2% | `LimitExecutionStep.java:64` and below | the query itself |
| 20.2% self | `AbstractStringBuilder.ensureCapacityInternal` | the `StringBuilder` inside Gson's writer growing from its default size for a 100 to 300 KB answer |
| 11% self | `FloatToDecimal.*` | the 24 `styleVec` floats of every row formatted as shortest decimal: 4,800 conversions per hydrate200 answer |
| 7% self | `LinkedTreeMap.*` | Gson's `JsonObject` map, one node per property per row |

I/O on the XNIO threads: `ExecutionResponse.java:164 send` hands the `String` to Undertow, which encodes it to bytes (6.8 GB
of `byte[]` in 10 s) and writes it in 73,238 socket writes (3.5 s of the 80 thread-seconds); `PaginatedComponentFile.readFully:258`
shows 20,164 page reads (2.9 s) in request threads, the page cache still filling behind a fresh JVM. Allocation: 197 GB in
10 s, 15% of it the `byte[]` inside `JSONObject.toString`, 8% the per-row property strings (`Binary.getString:719`).

So of the 2 ms the HTTP layer adds to a 200-row answer, roughly half is turning a finished `JsonObject` tree into text (with
the float formatting and the buffer growth inside it), a quarter is building that tree one `getProperty` header walk at a
time, and the rest is the request parse, the transaction and the socket. None of it is the query.

### Locks and synchronous I/O in query threads

Monitors (`jdk.JavaMonitorEnter` over 1 ms) in all four recordings: the only engine monitors that waited are
`FlatVectorCache.chunkFor:327-328` (21 + 8 waits, 148 ms, cold-chunk allocation in the first seconds of the vector run),
`LSMVectorIndex.getSearchVectorCache:13943` (7 waits, 41 ms, same start-up), `LSMTreeIndexBloomFilter.mightContain:283`
(6 waits, 15 ms) and `LocalDatabase.query:2984` (4 waits, 23 ms). Everything else is start-up: the JMH state init, and
`SQLAntlrParser.parseWithTree:111` class initialisation (7 threads, 640 ms once). There is no lock convoy on the read path
at 8 threads. What the read path pays for locking is uncontended: a `ReentrantReadWriteLock` lock/unlock pair per record in
`LocalDatabase.lookupByRID` through `RWLockContext.readLock:76` and `readUnlock:82` (16% of pivot1Hop CPU, 12% of structured).

Thread parks: `PageManagerFlushThread.flushPagesFromQueueToDisk:754` is the flusher idling (one park per second); the JMH
worker parks are iteration boundaries. No query thread parked on engine state.

Synchronous file I/O in query threads (`jdk.FileRead`, all from `PaginatedComponentFile.readFully:258`, reached from
`PageManager.loadPage:2616` on a page cache miss, through `LocalBucket.getRecordInternal:2628` or an index page read):

| recording | file reads in 20 s | total ms | queries | reads per query |
|---|---:|---:|---:|---:|
| pivot1Hop | 59,097 | 1,242 | 62,944 | 0.9 |
| structured | 55,969 | 695 | 85 | 658 |
| fulltextTop20 | 21,001 | 370 | 633 | 33 |
| vectorKnn20 | 56,477 | 992 | 220,888 | 0.3 |

Each is one `FileChannel.read` at a position, issued by the thread that needs the page, one page at a time, with no read-ahead
and no batching: a hydrate of 200 RIDs on a cold cache is 200 sequential round trips to the device. At 20 us a read on this
NVMe that is hidden in the warm numbers; on a 600 GB corpus on a 126 GB node every record-heavy query takes this path.
No socket or file write appears in any query thread.

## Findings, ranked by expected gain for Recon's read mix

1. **structured: the planner picks the least selective index and sorts afterwards.** Each of the three indexes covers one
   predicate (`state =` on `(state, tsPosted)`, `age >=` on `(age, tsPosted)`, `services CONTAINS` on `servicesbyitem`), so
   the "most conditions covered" rule ties; the "fully matched key" rule
   (query/sql/executor/SelectExecutionPlanner.java:5751-5755) then prefers the single-property BY ITEM index over the
   composite prefixes, and the cost step (:5760, `IndexSearchDescriptor.cost:76`) answers `Integer.MAX_VALUE` for all because
   `QueryStats.getIndexStats:62` only knows what a previous profiled run pushed. The plan fetches the 10% of records with the
   service (600K at 5M), loads each, filters 99% away, then top-100-sorts. `fullySorted` (:5294) already recognises that
   `(state, tsPosted)` with `state =` yields the ORDER BY order, but only for the index already chosen. Expected gain: from
   373 ms to a few ms, which is what feed24h costs for the same shape. Fix order: a selectivity estimate for the tie-break
   (distinct-key count per index is one `countEntries`/dictionary size away), then ORDER-BY-aware index choice, then an
   index intersection for the two remaining predicates.
2. **pivot2Hop: the whole 2-hop neighbourhood is loaded before the LIMIT sees a row.** `in().out().in()` is evaluated by
   `SQLQueryEngine.foreachRecord:395-411`, which iterates its entire input to register one iterator per element before
   returning, and `SQLFunctionMove.v2v:73` loads every intermediate vertex (`iRecord.getRecord()`) to read its edge list; the
   projection is finished before `ExpandStep.fetchNext:106` hands the first row to `LimitExecutionStep`. On a hub phone
   (2,000 records x 3 identifiers each) that is tens of thousands of record loads for 500 rows, and a 16 GB heap is not
   enough at 8 threads. Expected gain: bounded memory and p99 from seconds to tens of ms. Fix: lazy iterator chaining in
   `foreachRecord` (register iterators on demand) and letting EXPAND stop pulling once the downstream LIMIT is satisfied.
3. **ORDER BY ... LIMIT re-deserialises the sort key on every comparison.** `OrderByStep.initTopK:213` keeps `Kept(row,
   arrival)` and compares through `OrderBy.compare` -> `OrderByItem.compare:118` -> `ResultInternal.getProperty:248` ->
   `ImmutableDocument.get:92` -> `BinarySerializer.deserializePropertyOf:586`: a header walk and a boxed `Long`
   (BinarySerializer.java:1173, 20% of pivot1Hop allocation) per comparison, log2(500) comparisons per row. 70% of pivot1Hop
   CPU. Expected gain: 2 to 3x on pivot1Hop p95, and on every `ORDER BY tsPosted DESC LIMIT n` (structured after finding 1,
   anything Recon sorts). Fix: extract the key once into `Kept`, compare primitives.
4. **Per-record load overhead on every path.** `LocalDatabase.lookupByRID:1239-1266` does, for each record: a striped
   `ReentrantReadWriteLock` lock/unlock (`RWLockContext.readLock:76`), a `ThreadLocal` map lookup
   (`DatabaseContext.getContextIfExists:134`), the before-read event check
   (`LocalBucket.fireBeforeReadEvents:2559` -> `LocalDocumentType.hasBeforeReadListeners:330` -> `hasTriggers:374` ->
   `LocalSchema.hasTriggerListeners:6219`, a `ConcurrentHashMap.isEmpty()` which sums the counter cells), a `PageId`
   allocation (`LocalBucket.getRecordInternal:2628`), a `DatabaseRID` (`BasicDatabase.newRID:204`), two `HeapByteBuffer`
   slices (`Binary.slice:860`, `Binary.<init>:90`) and a copy of the record bytes out of the page
   (`Binary.copyOfContent`, database/Binary.java:105, at :1266). Together 40 to 50% of CPU and most of the allocation in
   pivot1Hop and structured. Expected gain: 20 to 30% on every record-heavy query and the 32-thread GC stalls. Fix: take
   the read lock once per statement, cache the context on the `CommandContext`, keep `hasTriggerListeners` as a volatile
   boolean, read immutable records as a view over the cached page, reuse RID instances from the index cursor.
5. **Full-text top-k visits and hashes every posting three times.** `FullTextQueryExecutor.collectTermMatches:625` (one
   `HashMap<RID, AtomicInteger>` entry per posting), `LSMTreeFullTextIndex.computeBM25Scores:532` (`HashMap<RID,
   double[]>`), `canonicalRID:669` (a third RID object), `buildScoredCursor:701` (the heap, last); each posting is first an
   `IndexCursorEntry` + `FullTextPostingRID` from `LSMTreeIndexAbstract.readEntryValue:898`. 145 MB and 113 ms for a top 20
   on two common terms. Expected gain: 10x and more with RID-ordered postings and block-max skipping; the partial-text
   workstream has the design (`docs/spork/workstreams/fulltext-topk.md`) and a first top-k planner. Short of that,
   primitive long-keyed maps (RID packed into a long) remove the boxing.
6. **geoRadius14d: candidates re-filtered inline, geometry parsed from WKT per row and per predicate.** The plan fetches
   the geohash cells intersecting the box through the index and then re-evaluates `geo.intersects` AND `geo.dWithin` AND
   `tsPosted >=` on every candidate; `SQLFunctionGeoPredicate.execute:75-76` calls `GeoUtils.parseGeometry:130` on both
   arguments per row, so each candidate's `location` WKT string is parsed twice (once per predicate) and the constant box
   and point are parsed per row as well. The `tsPosted` index is not consulted although 14 days is 14% of the records.
   22 ms p50, 741 ms p99 for 500 rows. Expected gain: 3 to 5x. Fix: parse constants once per statement, store `location`
   as the compressed geometry binary type rather than a STRING so the record side is a decode, evaluate both predicates on
   one parsed shape, and intersect with the `tsPosted` range. The geo-knn workstream has made `dWithin` index-backed, which
   removes the second predicate.
7. **HTTP: about 2 ms of serialization per 200-row answer, 16 to 20x the engine time for hydrate and feed.** The HTTP
   profile puts half of it in `JSONObject.toString` (serializer/json/JSONObject.java:544, `Gson.toJson` of the whole
   response into a `String`, with `StringBuilder` growth as 20% of all server CPU and the shortest-decimal formatting of the
   24 `styleVec` floats per row as 11%), a quarter in building that tree: `AbstractQueryHandler.serializeRows:605-612` ->
   `JsonSerializer.serializeResult` (serializer/JsonSerializer.java:149), which for an element row calls
   `getPropertyNames()` (one header walk, BinarySerializer.java:366) and then `getProperty()` per property (another walk
   each, 13 properties, 14 walks per row) and puts each into Gson's `LinkedTreeMap`. Then `ExecutionResponse.send:164`
   encodes the `String` to bytes. NDJSON (`:363`) does the same per row. Expected gain: 3 to 5x on the HTTP latency of
   hydrate200, feed24h and pivot1Hop, which is what Recon measures. Fix: serialize an element row straight from its binary
   through `locateProperties` (one header walk, BinarySerializer.java:604) into a pre-sized UTF-8 buffer, no Map, no
   `JsonObject`, no intermediate `String`; and do not ship a 24-float vector in every row unless it is projected (a
   schema-level "not returned by default" for ARRAY_OF_FLOATS, or a binary encoding for it).
8. **Page cache miss = one synchronous positional read per page in the query thread.** `PageManager.loadPage:2616` ->
   `PaginatedComponentFile.read:568` -> `readFully:258`, no read-ahead, no batching across the RIDs of one statement. Cold
   hydrate200 is 200 serial device round trips; the table above shows 658 reads per structured query. Expected gain: the
   whole cold column, which is the steady state of a corpus larger than RAM. Fix: batch the page reads of a RID list
   (`FETCH FROM RIDs`) and of an index scan into one vectored or asynchronous submission, and read ahead on sequential
   bucket scans.
9. **Index-to-record pipeline allocates three row objects per index entry.** `FetchFromIndexStep$1.next:196` builds a
   `ResultInternal` with a `LinkedHashMap` holding `key` and `rid` for every entry, `GetValueFromIndexEntryStep.toResult:996`
   builds a second one around the loaded document, and `TypeIndex.get:252` first collected the RIDs into a `HashSet` (a
   `HashMap$Node` per RID) so that `DistinctExecutionStep` can drop the duplicates a multi-value index produces. In
   structured that is 16% of the allocation before a single record is read. Expected gain: 10 to 20% on every indexed
   query that returns many entries. Fix: a primitive RID cursor between the two steps, DISTINCT on packed longs.
10. **vectorKnn20: the walk, not the arithmetic.** `LSMVectorIndex.walkGraph:9172` spends 35% of its time in jvector's
    visited set and 14% in its candidate heap against 9% in the cosine kernel, which says the beam visits far more nodes
    than the 24-dimensional, 200-cluster data needs; `FlatVectorCache.count:343` adds 6% for a striped counter bumped per
    similarity. Expected gain: 1.5 to 2x on a query that is already 0.7 ms; lowest priority of the ten. Fix: beam and
    overquery tuned per index from a recall probe at build time, counters per query rather than per call.

## Top 10 read-path bottlenecks (file:line)

| # | query | where | what |
|---|---|---|---|
| 1 | structured | `engine/src/main/java/com/arcadedb/query/sql/executor/SelectExecutionPlanner.java:5751` (full-key tie-break), `:5760` (cost), `engine/src/main/java/com/arcadedb/query/sql/executor/IndexSearchDescriptor.java:76`, `engine/src/main/java/com/arcadedb/query/sql/executor/QueryStats.java:62` | least selective index chosen, no selectivity, ORDER BY not pushed into `(state, tsPosted)` |
| 2 | pivot2Hop | `engine/src/main/java/com/arcadedb/query/sql/SQLQueryEngine.java:395-411`, `engine/src/main/java/com/arcadedb/function/sql/graph/SQLFunctionMove.java:73`, `engine/src/main/java/com/arcadedb/query/sql/executor/ExpandStep.java:106` | whole traversal materialised and every intermediate vertex loaded before LIMIT |
| 3 | pivot1Hop, any ORDER BY LIMIT | `engine/src/main/java/com/arcadedb/query/sql/executor/OrderByStep.java:213`, `:347`, `engine/src/main/java/com/arcadedb/query/sql/parser/OrderByItem.java:118`, `engine/src/main/java/com/arcadedb/serializer/BinarySerializer.java:586`, `:1173` | sort key deserialised and boxed on every comparison |
| 4 | every record load | `engine/src/main/java/com/arcadedb/database/LocalDatabase.java:1239-1266`, `engine/src/main/java/com/arcadedb/utility/RWLockContext.java:76`, `engine/src/main/java/com/arcadedb/database/DatabaseContext.java:134`, `engine/src/main/java/com/arcadedb/engine/LocalBucket.java:2559`, `engine/src/main/java/com/arcadedb/schema/LocalSchema.java:6219`, `engine/src/main/java/com/arcadedb/database/Binary.java:105` | lock, thread-local, trigger check, PageId, RID, two buffer slices and a byte copy per record |
| 5 | fulltextTop20 | `engine/src/main/java/com/arcadedb/index/fulltext/FullTextQueryExecutor.java:625`, `engine/src/main/java/com/arcadedb/index/fulltext/LSMTreeFullTextIndex.java:532`, `:669`, `:701`, `engine/src/main/java/com/arcadedb/index/lsm/LSMTreeIndexAbstract.java:898`, `:1081` | all postings materialised and hashed three times for a top 20 |
| 6 | geoRadius14d | `engine/src/main/java/com/arcadedb/function/sql/geo/SQLFunctionGeoPredicate.java:75-76`, `engine/src/main/java/com/arcadedb/function/sql/geo/GeoUtils.java:130`, `engine/src/main/java/com/arcadedb/function/sql/geo/SQLFunctionGeoDWithin.java:62` | WKT parsed per row per predicate, constants re-parsed, `dWithin` and `tsPosted` not indexed |
| 7 | HTTP hydrate200, feed24h, pivot1Hop | `engine/src/main/java/com/arcadedb/serializer/json/JSONObject.java:544`, `server/src/main/java/com/arcadedb/server/http/handler/AbstractQueryHandler.java:605-612`, `:363`, `engine/src/main/java/com/arcadedb/serializer/JsonSerializer.java:149`, `engine/src/main/java/com/arcadedb/serializer/BinarySerializer.java:366`, `server/src/main/java/com/arcadedb/server/http/handler/ExecutionResponse.java:164` | Gson tree to `String` to bytes, StringBuilder growth, 24 floats formatted per row, one header walk per property |
| 8 | every cold read | `engine/src/main/java/com/arcadedb/engine/PageManager.java:2616`, `engine/src/main/java/com/arcadedb/engine/PaginatedComponentFile.java:568`, `:258` | synchronous single-page positional read in the query thread, no batching or read-ahead |
| 9 | every indexed query | `engine/src/main/java/com/arcadedb/query/sql/executor/FetchFromIndexStep.java:196`, `engine/src/main/java/com/arcadedb/query/sql/executor/GetValueFromIndexEntryStep.java:996`, `engine/src/main/java/com/arcadedb/index/TypeIndex.java:252` | three row objects and a HashSet node per index entry |
| 10 | vectorKnn20 | `engine/src/main/java/com/arcadedb/index/vector/LSMVectorIndex.java:9172`, `engine/src/main/java/com/arcadedb/index/vector/FlatVectorCache.java:343` | beam visits far more nodes than needed; per-call striped counter |

Not in the ten but recorded: the `GraphBatch.withWAL(false)` heap retention above (load path, not read path), and the
`arcadedb.maxPageRAM` clamp to 80% of the heap, which silently turns a "fits in RAM" benchmark into a cache-missing one
when the fork heap is sized for the data rather than for the data plus a quarter.

## Regression gate baselines

`benchmarks/src/test/resources/retrieval-baselines.json` holds the warm single-thread p95 of each query on the 300K-record
gate dataset (seed 42), measured on this machine (file header), with a 3.0 headroom. The gate run that produced them took
58 s for the nine tests (generation of the gate dataset included, 79 s with the Maven build):

| query | p50 us | p95 us (baseline) | bound at 3.0 (us) | rows |
|---|---:|---:|---:|---:|
| identLookup | 7 | 16 | 48 | 1 |
| pivot1Hop | 373 | 7,017 | 21,051 | 115 |
| pivot2Hop | 588 | 137,666 | 412,998 | 378 |
| structured | 20,651 | 99,670 | 299,010 | 54 |
| feed24h | 442 | 3,412 | 10,236 | 200 |
| geoRadius14d | 18,011 | 27,783 | 83,349 | 410 |
| fulltextTop20 | 3,457 | 13,791 | 41,373 | 20 |
| vectorKnn20 | 700 | 1,483 | 4,449 | 20 |
| hydrate200 | 100 | 165 | 495 | 200 |

The 300 samples per query are each discounted by the JVM-wide stall observed inside them (`StallAwareStopwatch.jvmStallNanos()`
differenced around the call), so a stop-the-world pause in a long CI run does not fail the gate; the assertion message says
the bound IS the assertion and loosening it deletes the test. With these baselines in place and the 3.0 headroom the gate passes 9 of 9 in 34.5 s (72 s with the Maven build, the gate
dataset reused), and the placeholder run before it (headroom 1,000,000, used to capture the numbers) passed in 58 s with the
gate dataset generated.
