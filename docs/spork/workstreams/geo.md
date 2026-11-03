# Geo workstream: nearest neighbours, metre radii, Cypher index use, convergence

Status: implemented and tested in the `geo-knn` worktree, not committed. Benchmark numbers are in
[Benchmark](#benchmark).

## What changed

| Gap (audit) | Now |
|---|---|
| No kNN | `geo.nearest(...)` SQL function and `CALL geo.nearest(...)` Cypher procedure: exact k nearest from the GEOSPATIAL index, never a type scan |
| `geo.dWithin` takes degrees, and was never index-backed | `geo.dWithinMeters(g, shape, metres)`; both radius predicates now answer from the index |
| Cypher point functions never use the index | `point.distance(n.p, c) < r` and `point.withinBBox(n.p, ll, ur)` in a MATCH ... WHERE plan a `NodeGeoIndexSeek` (visible in EXPLAIN) |
| `point.distance` refused WKT | `point.distance` / `point.withinBBox` read a WKT point string (what a GEOSPATIAL index holds) as a WGS-84 point |
| Geo + time window | `geo.geohash(point, p)` + `geo.geohashCover(shape, p, metres)` put a radius and a day window on one plain composite index |
| In-transaction reads | the nearest-neighbour walk and the radius/shape predicates see entries the current transaction has not committed |
| WKT parse cost | `POINT (x y)` is read by a hand parser; every other spelling still goes through the Spatial4j reader |

## Usage

All distances are great-circle metres on the 6,371 km mean-radius sphere, the same as `geo.distance()` and Cypher
`point.distance()`. Coordinates are WKT `POINT (lon lat)`.

### Nearest neighbours

```sql
-- the 10 sightings nearest to a point, nearest first; each row carries the record's properties, @rid, @type, distance (m)
SELECT expand(geo.nearest('Sighting[location]', 'POINT (-96.80 32.78)', 10))

-- bounded and filtered: at most 25 km, only one phone group; `where` is a map of equalities or a SQL condition with
-- named parameters, tested as the walk meets each candidate (k matching rows come back whenever they exist)
SELECT phone, distance
FROM (SELECT expand(geo.nearest('Sighting[location]', :p, 50,
        {maxDistanceMeters: 25000, where: 'groupId = :g AND ts >= :since'})))
```

```cypher
CALL geo.nearest('Sighting[location]', point({longitude: -96.80, latitude: 32.78}), 10, {maxDistanceMeters: 25000})
YIELD node, distance
RETURN node.phone AS phone, distance
```

The first argument is `'Type[property]'` or the index name; the point is WKT, `geo.point()`, or a Cypher point map. A
subtype searches only its own buckets. The origin must be a point.

### Radius in metres

```sql
SELECT FROM Sighting WHERE geo.dWithinMeters(location, 'POINT (-96.80 32.78)', 25000) = true
```

`EXPLAIN` shows `FETCH FROM INDEXED FUNCTION geo.dWithinMeters(...)`. The index is asked for the cells covering the
geodesic circle (or, for a non-point search shape, its bounding box widened by the radius), then the predicate checks
each candidate exactly, so polygons are measured from their nearest edge. `geo.dWithin` (degrees) uses the same path now.
A radius that can only be read off each record (`geo.dWithinMeters(location, :p, radiusField)`) is left to the scan.

### Cypher

```cypher
// anchored on NodeGeoIndexSeek(n:Sighting) [index=Sighting[location], point.distance(n.location, point($c)) < $r]
MATCH (n:Sighting)
WHERE point.distance(n.location, point($c)) < $r AND n.groupId = $g
RETURN n

MATCH (n:Sighting)
WHERE point.withinBBox(n.location, point({longitude: -97.5, latitude: 32.5}), point({longitude: -96.5, latitude: 33.2}))
RETURN n
```

Recognised: a top-level AND conjunct `point.distance(n.p, X) < | <= R` (either argument order, or `R > | >= ...`) and
`point.withinBBox(n.p, LL, UR)`, where `X`, `R`, `LL`, `UR` are literals, parameters, maps/lists of them, `point(...)`
calls or arithmetic over them. The conjunct stays in the WHERE as the exact check. Not recognised (falls back to the label
scan + filter, still correct): a centre read from another node (`point.distance(n.loc, a.loc)`), an OR, a label
disjunction.

### Convergence: two identifier groups within 200 km and 14 days

**Both groups known (the analysis as stated).** Read each group's sightings through an ordinary index on the group (or
on the identifiers), and pair them. No spatial index helps here: the pairs to check are those of two small sets.

```cypher
MATCH (a:Sighting {groupId: $A}), (b:Sighting {groupId: $B})
WHERE point.distance(a.location, b.location) < 200000 AND abs(a.ts - b.ts) <= 14 * 86400000
RETURN a, b
```

Cost is |A| x |B| distance checks (about 10^7 per second on one core). Past that, pre-bucket both sides by day in the
application and only pair buckets within 14 days.

**One side open ("who was within 200 km of this group's sightings within 14 days").** Here a radius alone reads every
sighting of the area across all time (a 200 km disc around a metro holds a large share of the data), and the time filter
throws almost all of it away. Put both dimensions on one composite LSM index instead:

```sql
CREATE PROPERTY Sighting.cell3 STRING;   -- GeoHash precision 3: ~156 x 156 km cells (~128 km wide at 35 N)
CREATE PROPERTY Sighting.day LONG;       -- epoch day
CREATE INDEX ON Sighting (cell3, day) NOTUNIQUE;
-- maintain at write time (or backfill): cell3 = geo.geohash(location, 3), day = ts / 86400000

SELECT FROM Sighting
WHERE cell3 IN geo.geohashCover(:p, 3, 200000)       -- the ~9-25 cells the 200 km disc touches
  AND day BETWEEN :day - 14 AND :day + 14
  AND geo.distance(location, :p) <= 200000             -- exact check
```

`EXPLAIN` shows `FETCH FROM INDEX Sighting[cell3,day]`: one index range per (cell, window) instead of the whole disc.
Write the exact check with `geo.distance`, not `geo.dWithinMeters`: the SQL planner prefers an index-backed function
over a composite index when both apply, so with `geo.dWithinMeters` it would read the whole disc from the GEOSPATIAL
index and filter the days afterwards (pinned by `GeoConvergencePatternTest`).
Pick the precision so that a radius covers a few dozen cells at most: 3 for 100-300 km, 4 (39 x 19.5 km) for 10-50 km.
Run it once per sighting of the known group (or per distinct (cell, day) of it), and group the answers by identifier.

## Benchmark

`engine/src/test/java/performance/GeoIndexBenchmark.java` (`@Tag("benchmark")`, skipped in CI):

```bash
mvn -o -pl engine test -Dtest=GeoIndexBenchmark -Dgroups=benchmark -DexcludedGroups= -Darcadedb.geoBenchmark.points=5000000 -Dsurefire.failIfNoSpecifiedTests=false
```

5,000,000 points: 60% around 30 US metro areas (Gaussian, sigma 0.3 degrees, ~33 km), 40% uniform over the contiguous
US; one bucket (the default); 40 query points, half at metro centres, half rural. "After" = all 40 queries, single
thread. "Before" = the plan that existed before this work, on the first 3 query points: a full scan, which the SQL
engine runs **in parallel** over the type (18 cores here), so the gap is the gap against a parallel scan. Median /
p95 / max in ms, macOS laptop (18 cores), engine built from this worktree.

Caveat: the machine was shared with other build sessions during both runs (load average 19-66 on 18 cores), so absolute
numbers are noisy by a factor of up to ~2; the ratios are the point.

**Run 1: compacted index** (as a production index is):

| query | plan | median | p95 | max | rows (median) |
|---|---|---|---|---|---|
| kNN k=10 | after: `geo.nearest` | 9.4 | 18.3 | 30.5 | 10 |
| kNN k=10 | before: scan + `ORDER BY geo.distance` | 3,046 | 6,680 | 6,680 | 10 |
| kNN k=100 | after: `geo.nearest` | 7.3 | 13.5 | 16.2 | 100 |
| kNN k=100 | before: scan + `ORDER BY geo.distance` | 3,119 | 3,527 | 3,527 | 100 |
| radius 25 km | after: `geo.dWithinMeters` (index) | 163 | 367 | 633 | 23,994 |
| radius 25 km | before: scan + `geo.distance` | 3,450 | 3,973 | 3,973 | 28,307 |
| radius 200 km | after: `geo.dWithinMeters` (index) | 413 | 1,047 | 1,379 | 117,301 |
| radius 200 km | before: scan + `geo.distance` | 1,397 | 2,006 | 2,006 | 118,830 |

**Run 2: right after the bulk load** (uncompacted LSM, lighter machine load):

| query | plan | median | p95 | max | rows (median) |
|---|---|---|---|---|---|
| kNN k=10 | after | 5.3 | 8.2 | 9.1 | 10 |
| kNN k=10 | before | 2,296 | 2,452 | 2,452 | 10 |
| kNN k=100 | after | 3.8 | 12.4 | 43.7 | 100 |
| kNN k=100 | before | 1,566 | 1,773 | 1,773 | 100 |
| radius 25 km | after | 180 | 371 | 469 | 23,994 |
| radius 25 km | before | 901 | 906 | 906 | 28,307 |
| radius 200 km | after | 982 | 2,662 | 7,854 | 117,301 |
| radius 200 km | before | 2,972 | 4,072 | 4,072 | 118,830 |

Load: 5M points with the index in 31-68 s (73-160k points/s, single writer thread).

Reading the numbers:

- **kNN** is 300-800x faster and, unlike the scan, does not grow with the data: it reads a few cells around the point and
  loads ~k records. At 290M records the scan would take minutes; the walk stays in milliseconds.
- **Radius** is 3-20x faster than the *parallel* scan. Its cost is (number of candidates) x ~2 us (cell walk, record
  load, exact check, single-threaded) plus (number of covering cells) x a fixed per-cell cost. The 200 km disc around a
  metro holds ~117k of the 5M points here (2.3%); at 290M that is ~7M rows, and any radius query that returns millions
  of rows is bounded by returning them, index or not. For analytics over such discs use the `(cell, day)` composite
  pattern or aggregate in the query.
- **Covering cells**: Lucene sizes the query cells from the shape (2.5% error), so a 25 km circle decomposes into 5,688
  cells (level 7) while a 200 km one needs 1,427 (level 5). That fixed walk is the floor of a small radius in a sparse
  area (tens of ms here). Coarser query cells (10% error) did not measurably help on this machine; see follow-ups.

## Design notes

### `geo.nearest`: best-first walk over the geohash cells

`GeoNearestSearch` (engine `index/geospatial`) keeps one priority queue of three kinds of item, each keyed by a
great-circle distance that never exceeds the distance of anything it can still produce:

- a **cell**, keyed by the distance to its rectangle: popping it runs one prefix range scan per bucket index over the
  entries at or below it. If the cell holds more than the scan budget (max(256, 4k) entries) the scan stops and the cell
  splits into its 32 children instead, so sparse areas are read in one coarse scan and dense ones narrowed down, with no
  density assumption;
- an index **entry** (a RID under a token), keyed by the distance to its token's cell. At the default precision 11 that
  cell is ~15 cm, so the key is the exact distance to within centimetres, and only records that can still be among the
  nearest are ever loaded;
- a **hit**, a loaded record keyed by its exact distance (haversine for points, nearest edge for shapes).

A hit at the head of the queue is nearer than anything still queued can be, so it is the next nearest: the walk stops
after k hits, at `maxDistanceMeters`, or when the queue empties. This is the "stop when the k-th distance is below the
next ring's minimum" rule, with the rings being whatever cells are queued. Polygons and lines indexed under coarse cells
are kept when their cell splits; the legacy FULL layout works (its ancestor tokens are skipped). The lower bound
(point-to-rectangle on the sphere) is property-tested against 20,000 random cell/point pairs, including the case where
the nearest meridian edge faces away from the point.

### Index-backed radius

`SQLFunctionGeoPredicate` gained a hook for the shape the index is asked for; `geo.dWithin` and `geo.dWithinMeters`
widen the search shape by the radius (`GeoUtils.searchShapeWithin`: a geodesic circle around a point, otherwise the
bounding box widened by `asin(sin r / cos lat)` of longitude, the full longitude range near a pole or past 180 degrees).

### Cypher planner

`AnchorSelector` now also costs a geo seek (`GeoSeekPredicate`, selectivity 1%) against the other candidate access paths;
`StatisticsProvider` collects the usable GEOSPATIAL indexes apart from the key indexes (they answer no key lookup);
`IndexSelectionRule` builds a `NodeGeoIndexSeek`, which evaluates the circle/box once per execution (parameters
included) and streams the candidates of the covering cells, restricted to the label's buckets.

### Transaction visibility

`LSMTreeIndex.range()` reads committed pages only, while `get()` merges the transaction's pending entries. The geo cell
scans are range scans, so a point written earlier in the same transaction was invisible to `geo.within` & co. Both the
nearest walk and the predicate cursor now merge the pending entries of the scanned prefix.

## Native point type: assessment (not done)

The indexed property is WKT in a STRING. What that costs, per record touched by a geo query: a WKT parse (Spatial4j
reader, a few microseconds and a handful of objects), and ~30-40 bytes of text instead of 16 bytes of two doubles. It
does **not** cost index size or seek speed: the index stores one geohash token per point either way.

Where it shows: radius queries that load many candidates (the 200 km row of the benchmark is dominated by record loads
plus WKT parses), and DBSCAN/track analytics that read millions of points. A native type would be a new `Type`
(serializer id, binary layout, JSON/HTTP/Bolt/Postgres wire mapping, Studio display, import/export, schema migration of
existing STRING columns) touching most modules: not small. Recommended instead, in order:

1. done in this workstream: a fast path for `POINT (x y)` in `GeoUtils.parseGeometry` (a hand parser that defers to the
   Spatial4j reader for every other spelling, pinned by `GeoUtilsPlainPointTest`);
2. for bulk analytics, store `lon`/`lat` DOUBLE columns next to the WKT and read those;
3. revisit a native type only if profiles of the real workload still show WKT handling on top.

## Deferred / follow-ups

- **Correlated centre in Cypher**: `MATCH (a ...) MATCH (n) WHERE point.distance(n.loc, a.loc) < r` is not index-backed
  (the seek needs the centre before the MATCH runs). Would need a per-row (apply/nested-loop) geo seek operator.
- **Composite geo+time index type**: the `(cell, day)` pattern above needs the derived columns maintained by the writer.
  A first-class index (time-bucket-prefixed geohash tokens) would remove that, at the cost of a new index type.
- **Native point type**: see above.
- **Radius candidates are not distance-ordered**: `geo.dWithinMeters ... ORDER BY geo.distance(...) LIMIT n` still loads
  every candidate of the disc; use `geo.nearest` with `maxDistanceMeters` for "nearest n within r".
- **Query cell sizing**: cap or tune the covering-cell count of a search shape (25 km = 5.7k cells today, more than
  200 km); needs a controlled benchmark on an idle machine before changing the shared `get()` path.
- **Parallel radius**: the index path is single-threaded while the scan it replaces is parallel; with several buckets per
  type the per-bucket cell walks could fan out (see the `engine-concurrency` skill for the pool rules).
- **Studio function reference**: `studio/.../function-reference.json` is generated (`FunctionReferenceGenerator`) and not
  regenerated here, to avoid colliding with the other workstreams' additions; regenerate once after merging.
- **Cost model**: the geo seek is costed at a fixed 1% of the label. A radius-aware estimate (disc area against the
  data's extent) would let a very wide radius lose to a selective equality index.
