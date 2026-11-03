# Workstream: P4 structured-search core

Status: in main (core, count, rank order); the estimated total on branch `claude/p4-count-estimate`, 2026-11-03. Design: `../02-retrieval-speed.md` 2.4.
Consumer: the `spork-recon` search route (WS3 slice 2, `ws3-recon-plugin.md`), which owns the route, visibility, the
console/ops boundary and titles; this is the walk underneath it, with the count and the rank order.

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

Added by WS3 (2026-11-03, the owner's decision of one core with the plugin on top; tests in
`StructuredSearchCountAndRankTest`, 10, against the same kind of full-scan oracle):

4. **Count** (`count(cap)`, `countBudget(entries)`, default 20,000). After the page the same cursors are drained, in no
   order, up to the cap: `total`, `totalCapped`. An entry is counted on the index alone when no facet is left to test and the
   record filter does not ask for the record, so a single-facet count reads no record (2.4 "Count"). Past the budget the count
   is a lower bound with `totalPartial` and a `totalEstimate` scaled from the walked facet's size. With `after`, the count covers
   this page and what follows it.
5. **Record filter** (`filter(RecordFilter)`): a per-record condition no index answers (the plugin's visibility rule), applied to
   rows and counts. `RecordFilter.mustRead(rid)` says which entries it might reject; the others are counted unread. The default
   reads every counted record.
6. **Rank order** (`rankBy("views")`, 2.4 P4v): the `(facet, rank, sort)` composite descending (`(rank, sort)` with no
   indexed facet), the window checked on each key's sort value, until the page or `rankScanCap` entries (default 10,000);
   past that the window's matches come from the time arm up to `rankFallbackCap` (10,000), sorted by rank in memory, `partial`
   when there are more. Ties: rank, then sort value, then RID, all descending. No keyset; newest-first only.
7. The unfiltered walk takes the shortest index led by the sort property, `(tsPosted)` or `(tsPosted, views)` (schema v1 has
   only the latter).

8. **Estimated total** (`estimateTotal()`, the owner's decision of 2026-11-03: Recon shows the page with an estimated total by
   default, the exact count capped at 10,000 is opt-in). When the search has at most one facet (a single value when its index
   is `BY ITEM`, since a record listing two values is one entry per value), no window, and no record filter or one whose
   `RecordFilter.hidesNothing()` is true, the total comes from the cardinality table the walk already keeps: the facet's
   entries summed over its values, or the type's record count with no facet. No count is run, even when `count(cap)` was also
   asked: `total = -1`, `totalPartial`, `totalEstimate` = the sum. It is as old as the table, up to the cardinality TTL (an
   hour), and `refreshCardinality()` renews it. `hidesNothing()` defaults to false because a statistic counts every entry, and
   a total that included records the caller may not see would disclose how many there are. Any other search ignores the
   request, so the route can always ask for it and keep its budgeted count as the fallback. Tests:
   `StructuredSearchEstimateTest` (10, against a full-scan count).

Still not in it: substring arms (P4s), residuals tested on the key bytes of a 3-column composite (saves the record load),
titles from `txt_<p>` (the route's).

## Measured (5M records, fixed workload, 1 thread, 12:56)

| | median | mean | p90 | rows |
|---|---:|---:|---:|---:|
| `structured` (SQL) | 491 ms | 608 ms | 1,243 ms | 3,993 |
| `structuredP4` (this) | **2.5 ms** | **10.4 ms** | **22.4 ms** | 3,993 |

Same 50 parameter sets, same rows. OrientDB 3.2.24 on the SQL shape: 230 ms median. With this in the Recon mix, the
traffic-weighted mean read drops from about 150 ms to about 5 ms (structured is 15% of reads). The benchmark query runs with
no over-read cap so its rows equal SQL's; the 40x cap only bounds the tail further.

### With the total (5M records, fixed workload, 1 thread, 14:14, branch `claude/p4-count-estimate`)

Same 50 parameter sets; the route's default is `count(10_000).countBudget(2_000).estimateTotal()` (`SearchService`), its
`exactTotal` option `count(10_000)` with the core's default budget.

| query | total | median | mean | p90 |
|---|---|---:|---:|---:|
| `structured` (SQL) | none | 532 ms | 659 ms | 1,439 ms |
| `structuredP4` | none | 2.65 ms | 10.0 ms | 23.2 ms |
| `structuredP4Estimate` | route default: three facets, so the budgeted count | **5.03 ms** | 12.3 ms | 25.5 ms |
| `structuredP4Count` | exact, capped at 10,000 | 23.9 ms | 24.5 ms | 34.9 ms |
| `stateP4Estimate` | route default: one facet, so the table | **0.068 ms** | 0.075 ms | 0.099 ms |
| `stateP4Count` | exact, capped at 10,000 | 3.11 ms | 3.10 ms | 3.14 ms |

Recon's default search (page and estimated total) meets the 5 ms p50 target at 5M, and the exact count is opt-in at about
24 ms. The table estimate makes a single-facet total free; a multi-facet search pays the 2,000-entry budgeted count, about
2.4 ms on top of its page.

## Tests

- `engine/.../query/structured/StructuredSearchTest` (12): 150 random filter combinations in both directions against a
  full-scan oracle sorted in Java; index choice; keyset pages concatenate to the full order; the over-read cap answers
  partial and resumes without loss or repeat; ties across cursors; windows; the sort-only fallback; a `BY ITEM` composite
  with no duplicates; the cardinality table following the data; refusals.
- `benchmarks/.../ReconDatasetTest.theP4CoreAnswersStructuredSearchWithTheRowsSqlDoes`: 40 draws on the generated dataset,
  same records and the same `tsPosted` order as the SQL query.

Run: `benchmarks/bin/retrieval-bench.sh fixed -Darcadedb.retrievalBench.queries=structured,structuredP4`.
