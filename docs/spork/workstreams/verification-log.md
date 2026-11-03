# Verification log: independent re-runs of workstream benchmarks

Each entry is a re-run by the coordinating session, not the implementing session, on the same laptop (Apple
Silicon, 18 cores, JDK 21, offline Maven). Numbers are for relative comparison only; production sizing uses the
126 GB / NVMe nodes.

## 2026-11-02 Parallel per-bucket vector search (worktree `claude/vector-parallel-search-f7403e`)

Command:
`mvn -o -pl engine test -Dgroups=benchmark -DexcludedGroups=none -Dsurefire.includes='**/VectorParallelBucketSearchBenchmark.java' -Dbench.rows=160000 -Dbench.buckets=16 -Dbench.quantizations=NONE,BINARY -Dbench.clients=1,4`

Note: the root pom defaults `excludedGroups` to `benchmark`, so `-DexcludedGroups=none` is required or the run is
silently green with zero tests.

| Quantization | Arm | Clients | p50 | p95 | recall@10 | q/s |
|---|---|---|---|---|---|---|
| NONE | serial (before) | 1 | 5.25 ms | 6.47 ms | 1.000 | |
| NONE | parallel (after) | 1 | 0.72 ms | 0.97 ms | 1.000 | |
| NONE | serial | 4 | 5.71 ms | 7.75 ms | | 675 |
| NONE | parallel | 4 | 3.56 ms | 7.29 ms | | 1030 |
| BINARY | serial (before) | 1 | 6.57 ms | 8.12 ms | 1.000 | |
| BINARY | parallel (after) | 1 | 0.78 ms | 1.24 ms | 1.000 | |
| BINARY | serial | 4 | 7.13 ms | 10.49 ms | | 534 |
| BINARY | parallel | 4 | 3.40 ms | 7.55 ms | | 1024 |

Result: 7.3x (NONE) and 8.4x (BINARY) single-client p50 improvement, identical rows returned, 1.5-1.9x throughput
at 4 clients. Matches the implementing session's report (6-8x).

## 2026-11-02 Geo kNN and metre radius (worktree `claude/geo-knn-b2da7a`)

Command:
`mvn -o -pl engine test -Dgroups=benchmark -DexcludedGroups=none -Dsurefire.includes='**/GeoIndexBenchmark.java' -Darcadedb.geoBenchmark.points=1000000`

Whole run took 8.3 s, so the point count honoured may be smaller than 1M; treat the "before" scan numbers as a
small-corpus baseline (the implementing session measured 3 s scans at 5M). Ratios shrink as the corpus shrinks
because the scan is what gets cheaper, not the index path.

| query | plan | median | p95 | max | rows |
|---|---|---|---|---|---|
| kNN k=10 | after: `geo.nearest` | 0.98 ms | 1.62 ms | 2.18 ms | 10 |
| kNN k=10 | before: scan + ORDER BY geo.distance | 116.15 ms | 243.23 ms | 243.23 ms | 10 |
| kNN k=100 | after: `geo.nearest` | 3.76 ms | 5.05 ms | 6.77 ms | 100 |
| kNN k=100 | before: scan + ORDER BY geo.distance | 58.98 ms | 78.68 ms | 78.68 ms | 100 |
| radius 25 km | after: `geo.dWithinMeters` (index) | 41.53 ms | 63.47 ms | 120.21 ms | 4,701 |
| radius 25 km | before: scan + geo.distance | 108.18 ms | 119.53 ms | 119.53 ms | 5,825 |
| radius 200 km | after: `geo.dWithinMeters` (index) | 37.51 ms | 71.69 ms | 81.11 ms | 23,456 |
| radius 200 km | before: scan + geo.distance | 45.62 ms | 46.95 ms | 46.95 ms | 23,813 |

Result: kNN is index-bound and flat in corpus size (118x at k=10 here, 300-800x at 5M in the implementing
session's run). Radius is candidate-bound (about 2 us per candidate) and only beats a parallel scan when the corpus
is large relative to the radius; at 290M records it always is. The 25 km "after" returns fewer rows (4,701 vs
5,825) because the index path uses an exact great-circle check in metres while the scan baseline used degrees;
the implementing session's doc notes the same.
