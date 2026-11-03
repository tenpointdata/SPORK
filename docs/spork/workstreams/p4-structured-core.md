# Workstream: P4 structured-search core

Status: implemented and tested on branch `claude/p4-structured-core`, 2026-11-03. Design: `../02-retrieval-speed.md` 2.4.
Consumer: the `spork-recon` search route (WS3 slice 2, `ws3-recon-plugin.md`), which owns the route, the console/ops
boundary, titles and the count; this is the walk underneath it.

## Why

Structured search was 98% of SPORK's traffic-weighted read time at 5M records (`retrieval-bench.md`, 5M fixed mode): 691 ms
median through SQL against the 5 ms P4 target. The SQL planner is not cost-based and prefers an index the predicate fully
matches over a prefix of a composite (`SelectExecutionPlanner.pickBestDescriptor`, #4600), so
`state = :s AND age BETWEEN :a AND :b AND services CONTAINS :svc ORDER BY tsPosted DESC LIMIT 100` opens `services BY ITEM`,
loads every record of a common service (a quarter of the corpus) and sorts them.

## What it is

`com.arcadedb.query.structured` in the engine: `StructuredSearch` (one per type and sort property, shared, thread-safe),
`StructuredQuery` (immutable page request) and `Facet` (property IN values; LIST properties match by item).

```java
StructuredSearch search = new StructuredSearch(database, "Record", "tsPosted");
StructuredSearch.Result page = search.search(StructuredQuery.newestFirst(50)
    .where(Facet.eq("state", "FL"))
    .where(Facet.intRange("age", 31, 35))
    .where(Facet.eq("services", "service0"))
    .window(from, to));                       // optional, on tsPosted
page.rows(); page.partial(); page.next();     // next page: query.after(page.next())
```

1. **Choose.** Every facet with a `(facet, tsPosted[, ...])` composite (plain or `BY ITEM`) is a candidate; the walk takes
   the one whose values hold the fewest entries, from a per-index table of entries per leading key value, built by one walk
   of the index and kept for an hour (`refreshCardinality()` drops it; the design refreshes at seal and hourly). No such
   facet: the index on `tsPosted` alone. Neither: `IllegalArgumentException` naming the index to create.
2. **Walk.** One range cursor per value of the chosen facet (an age band is five), bounded by the window and the keyset, merged
   newest first; ties on `tsPosted` ordered by RID in the walk's direction, so pages are stable. A `BY ITEM` composite walked
   on several values returns each record once.
3. **Test and stop.** The other facets are tested on the record; the walk stops at `limit` rows, or after
   `limit x overRead` index entries (default 40, as 2.4 specifies) with `partial = true` and a keyset that resumes after the
   last entry read.

Not in it (the route's job): the exact `total` (2.4 "Count"), the views-sort arm, substring arms (P4s), residuals tested on
the key bytes of a 3-column composite (saves the record load), titles from `txt_<p>`.

## Measured (5M records, fixed workload, 1 thread, 12:56)

| | median | mean | p90 | rows |
|---|---:|---:|---:|---:|
| `structured` (SQL) | 491 ms | 608 ms | 1,243 ms | 3,993 |
| `structuredP4` (this) | **2.5 ms** | **10.4 ms** | **22.4 ms** | 3,993 |

Same 50 parameter sets, same rows. OrientDB 3.2.24 on the SQL shape: 230 ms median. With this in the Recon mix, the
traffic-weighted mean read drops from about 150 ms to about 5 ms (structured is 15% of reads). The benchmark query runs with
no over-read cap so its rows equal SQL's; the 40x cap only bounds the tail further.

## Tests

- `engine/.../query/structured/StructuredSearchTest` (12): 150 random filter combinations in both directions against a
  full-scan oracle sorted in Java; index choice; keyset pages concatenate to the full order; the over-read cap answers
  partial and resumes without loss or repeat; ties across cursors; windows; the sort-only fallback; a `BY ITEM` composite
  with no duplicates; the cardinality table following the data; refusals.
- `benchmarks/.../ReconDatasetTest.theP4CoreAnswersStructuredSearchWithTheRowsSqlDoes`: 40 draws on the generated dataset,
  same records and the same `tsPosted` order as the SQL query.

Run: `benchmarks/bin/retrieval-bench.sh fixed -Darcadedb.retrievalBench.queries=structured,structuredP4`.
