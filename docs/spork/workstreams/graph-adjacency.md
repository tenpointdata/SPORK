# Workstream: graph adjacency (S6, S7, S15) and geo cursor (S12)

Status: implemented and measured on 2026-11-02 in worktree `.claude/worktrees/agent-adafdfb8b2f5f8705`, branch
`worktree-agent-adafdfb8b2f5f8705`, on top of 4bc577f11. Items from `../02-retrieval-speed.md` section 4.2: S6 (allocation-free
adjacency entries), S7 (edge chunks read as page views, previous pointer as primitives), S15 (O(1) vertex degree in the head
chunk) and S12 (geo cursor: coalesced frontier ranges, primitive `seen` set, search shape parsed once per statement). Nothing
is committed; the review decides.

The Recon pattern this serves is the 1 to 2 hop pivot from an identifier vertex to its records (P1 to P3 in the doc), where the
identifier is often a hub with thousands of entries, and the degree detectors and the `json` serializer that call `countEdges`
on such hubs (C4 in the doc).

## 1. What changed

### S15: the degree lives in the head chunk

An edge list is a chain of chunks, newest first. The head chunk now carries the number of entries of the whole chain, so
`Vertex.countEdges(direction)` (and `EdgeLinkedList.count()` / `countInto()` with no type and no neighbour filter) is one field
read on the head instead of a walk over every chunk. Filtered counts (`countEdges(OUT, "KNOWS")`) still walk, now over page views.

On-disk format, versioned by the record type byte (`MutableEdgeSegment`):

| layout | byte 0 | header | entries start | written by |
|---|---|---|---|---|
| legacy | `3` (`EdgeSegment.RECORD_TYPE`) | type, used (int), previous RID (int bucket + long position) | 17 | every release before this one; this one with `arcadedb.graph.edgeListDegree=false` |
| with degree | `8` (`MutableEdgeSegment.RECORD_TYPE_WITH_DEGREE`) | the same 17 bytes, then the degree (long) | 25 | this release, default |

`Record.getRecordType()` answers `3` for both: the storage layer (`LocalBucket`'s edge-segment special cases) keys on the kind of
record, the layout is byte 0 of the content. `RecordFactory` maps both bytes to `MutableEdgeSegment`; `EdgeListScanner` (the
in-place scan of #11012) and `GraphEngine`'s committed-chain reads accept both markers. A release without this change refuses byte
8 ("Cannot find record type '8'"), which is the versioning the owner directive asks for; `arcadedb.graph.edgeListDegree=false`
keeps writing the legacy layout, for a rolling upgrade.

Maintenance, all inside `MutableEdgeSegment` and `EdgeLinkedList` (`StripedEdgeList` for the promoted super-nodes):

- every append (`add`, `addAtEnd`, `addAtEndDirect`, `addManyAtEndDirect`) moves the chunk's own field by the entries it took;
  every removal (`removeEdge`, `removeVertex`, `removeLightEdge`, `removeEntry`) by the entries it dropped. On the head that is
  the chain's degree. The commit-time edge-append merge replays `add()` on the committed head, so a rebased append counts itself;
- a head flip (`EdgeLinkedList.add`/`addAll`, `StripedEdgeList.add`) writes `old chain degree + new entries` into the new head and
  **seals** the old head (`EdgeSegment.seal()`: the record is trimmed to its used bytes). The seal closes the one race the field
  cannot otherwise survive: transaction A appends in place to the old head while transaction B flips it. Without the seal, if B
  commits first, A's rebase replays the append onto the old head behind the new head's back and the degree is short by one.
  Sealed, the rebase finds no room, A retries and lands on the new head, which counts it; if A commits first, B's rewrite of the
  old head conflicts and B retries. `EdgeListDegreeTest.concurrentAppendsAcrossHeadFlipsKeepTheDegreeExact` races four appenders
  across many flips and compares the field with the walk;
- a removal from a chunk BEHIND the head also rewrites the head (`degreeChangedBehindHead`), through `updateRewrittenSegment`
  so the head's page leaves the append merge: a concurrent append to the head conflicts and retries instead of being rebased onto
  a head that does not count the removal. `Iterator.remove()` and `CHECK DATABASE`'s `removeEntryKeepingEdge()` go through the same
  path (`ResettableIteratorBase.unlinkReturnedEntry`);
- migration: a chain of legacy chunks, or one assembled by `GraphBatch` (which links chunks with `setPrevious(RID)` and so marks the
  degree unknown), answers `DEGREE_UNKNOWN` (-1) and is counted by the walk as before. Its first head flip counts the chain once
  (`EdgeLinkedList.chainDegree`) and keeps the count from then on. `EdgeListDegreeTest.aLegacyChainIsCountedByAWalkUntilItsFirstFlipMigratesIt`.

### S6: allocation-free adjacency entries

`EdgeSegment.nextEntry(int position, long[] entry)` decodes the four numbers of an entry (edge bucket, edge position, vertex
bucket, vertex position) off the buffer's bytes in one pass, without moving the buffer position and without allocating; it
returns the position of the next entry. Every iterator (`VertexIterator`, `EdgeIterator`, `RIDIterator`, `RIDIteratorFilter`,
`IteratorFilterBase` and its vertex/edge/entry filters, `EdgeVertexIterator`) steps on it and builds a `RID` only for what it
hands out: the vertex walks never build the edge RID any more, and the neighbour filter and the deleted-self-loop check decide
on the primitives before any RID exists. `ResettableIteratorBase.currentPosition` is a plain `int` (it was an `AtomicInteger`
updated by CAS on every entry), and the entry the last `next()` returned is remembered by position and decoded only if `remove()`
asks for it (eagerly again once an unlink moved the walk onto the transaction's live copy, which the transaction itself can shift).
`getRID(AtomicInteger)` and `skipRID(AtomicInteger)` stay for `LightEdgeRID` and compatibility, implemented on absolute reads.
`containsEdge`, `getFirstEdgeConnectedToVertex`, `count`, `countInto`, `nextEntryInBuckets` and the light-edge probes read at
absolute positions too, so a page view shared by several readers is never moved under them.

### S7: chunks read as page views, previous pointer as primitives

`EdgeLinkedList.readChunkForWalk` / `readRecordForWalk` read a chunk for a walk as a read-only view of its cached page
(`MutableEdgeSegment.view`, every mutator refuses on it) instead of the copy `lookupByRID` makes, skipping the up to 8 KB copy, the
after-read event dispatch and the record statistics of a lookup; the bucket permission check and before-read events still run. The
transaction's own written copy of a chunk wins when it has one (its pending appends live only there), and anything that is not an
edge segment (a stripe directory, a damaged slot) is read the old way so it raises what it always raised. Used by the chunk hops of
every iterator (`EdgeSegment.previousForWalk()`), by the read-only chain walks of `EdgeLinkedList` (counts, containment probes,
`toJSON`) and by `GraphEngine.buildEdgeList`, the read side of every `getEdgeHeadChunk`. The write side (`loadChunkForWrite`,
`removeEdge`, `deleteAll`, the flip) still reads anchored copies.

`getPreviousRID()` reads the two fixed-width numbers of the pointer at their offsets instead of going through the serializer's
`deserializeValue` dispatch; `hasPrevious()` answers without building the RID.

Not done from S7: the per-statement (head RID to segments) cache in the traversal context. See deferred items.

### S12: geo cursor

- `GeoIndexCursor` answers a run of adjacent frontier cells with ONE prefix range scan (`CellCursorFactory.openFrontierRun`,
  `LSMTreeGeoIndex.openFrontierRunCursor`): two frontier siblings whose last characters are consecutive in the GeoHash alphabet
  have no cell between them in key order, so the range from the first token to the last token's upper bound covers exactly the run
  (`GeoIndexCursor.adjacentSiblings`). The walk is a pre-order over the cell tree, so such siblings arrive one after the other.
  Off for the FULL layout (exact lookups) and for the cursor's three-argument constructor, which the existing unit tests drive one
  cell at a time. `GeoIndexCursorCoalescingTest` checks the rows against a per-cell cursor over a sorted fake index that also holds
  deeper descendants and a foreign cell;
- the `seen` set is a `RidHashSet` (two primitive arrays) instead of a `HashSet<RID>`;
- `SQLFunctionGeoPredicate.parseShape` keeps the last shape parsed from each argument, keyed by the value it came from, and answers
  it for an equal value: the search shape (a WKT literal, a `point()` map) is parsed once per statement instead of once per row,
  for the index seek and the per-row re-check alike. One function instance serves every execution of a cached statement from
  several threads, so each slot is a volatile reference to an immutable pair (`engine-concurrency` skill, lock-free snapshot
  pattern); two threads racing on the same value compute the same shape twice at worst.

### Other files touched

`RecordFactory` (one case), `GlobalConfiguration` (`GRAPH_EDGE_LIST_DEGREE`), `GraphBatch` (sizes its exact-fit chunks with
`MutableEdgeSegment.contentStartOfNewSegments(database)`, the header of the layout being written), `LightEdgeRID` (content start
of the chunk, not the constant), `EdgeListScanner`, `GraphEngine` (marker checks, `buildEdgeList`). Nothing in `PageManager`,
`LocalBucket`, `LocalDatabase`, the index locks or the projection and serializer paths.

## 2. Measured

Laptop (Apple Silicon, JDK 21), `low-ram`/default test profile, warm page cache, single thread. Numbers are for relative
comparison only; wall time is discounted of JVM stalls observed inside the window (`StallAwareStopwatch`), allocation is
`ThreadMXBean.getThreadAllocatedBytes` on the measuring thread. Commands:

```
mvn -o -pl engine test -Dsurefire.includes='**/AdjacencyPivotBenchmark.java' -DexcludedGroups=none
mvn -o -pl engine test -Dsurefire.includes='**/GeoRadiusCursorSetupBenchmark.java' -DexcludedGroups=none
```

### 1-hop pivot: 500 newest neighbours of a 10,000-degree vertex (S6, S7)

Classic single-chain layout (super-node promotion off, as C7 runs Recon's identifiers), one `Identifier` vertex with 10,000
incoming `HAS` edges from `Record` vertices, 200 runs per row; the figures are per entry read. "Before" reproduces the walk the
iterators did until S6 on the same chain (both RIDs of every entry through `getRID(AtomicInteger)`, the vertex looked up lazily,
every chunk hopped to as a `lookupByRID` copy); "after" is the production path.

| walk | ns / entry | bytes allocated / entry |
|---|---|---|
| before: `getRID` x2 + `lookupByRID(rid, false)` per entry, chunk copies | 152 | 181 |
| after: `getVertices()` (`nextEntry`, vertex RID only, chunk views) | 97 | 93 |
| after: `getConnectedVertexRIDs()` (RID per entry, no vertex handle) | 27 | 35 |
| before, full 10,000-entry walk | 34 | 115 |
| after, full 10,000-entry walk `getVertices()` | 31 | 89 |

On the 500-newest page the per-entry cost drops 36% (152 to 97 ns) and the allocation 49% (181 to 93 bytes): what remains is the
one `RID` and the lazy `ImmutableVertex` handle `lookupByRID` returns per entry, which S6 does not touch (L6 in the doc is the
item that would). The RID-only walk, the shape a plugin hydrate uses, is 27 ns and 35 bytes per entry. The full walk moves less
(34 to 31 ns, 115 to 89 bytes) because on a 10,000-entry walk the per-entry record lookup dominates and the head and chunk reads
saved by S7 are amortised over more entries; the 500-page is where the head copy and the two decodes per entry weighed. The doc's
estimate for S6 was "about 40% of the per-entry CPU of a neighbour walk".

### `countEdges` on a 100,000-degree hub (S15)

Same layout, 100,000 incoming edges (about 75 chunks of 8 KB after the geometric growth), 50 runs.

| count | us / call | bytes allocated / call |
|---|---|---|
| before: chain walk, `count(null)` per chunk, chunk copies | 618.9 | 860,185 |
| after: degree field in the head chunk | 2.0 | 945 |

310x faster and 900x less garbage: the remaining 2 us and 945 bytes are the vertex read and the head chunk's page view. The doc's
estimate was "from a chain walk (100 us to 10 ms on hubs) to a field read".

### Geo radius 200 km, cursor set-up (S12)

200,000 points spread over a 12 x 12 degree box around Rome, GEOSPATIAL index at the default precision, a 64-point polygon of
200 km radius, 19,675 candidates, 30 runs each. The machine was shared with other builds during the run (the stall column), so
the absolute times carry noise; the scan count does not.

| cursor | underlying scans opened | cells coalesced | first row (us) | drain (ms) | stall discounted (ms) |
|---|---|---|---|---|---|
| before: one scan per cell | 1,413 | 0 | 895 | 137.3 | 3,292 |
| after: one scan per run of adjacent frontier cells | 261 | 1,152 | 1,107 | 57.8 | 1,340 |

5.4x fewer cursor opens (1,413 to 261: the 200 km circle at this precision is about 1,400 covering cells, of which 1,152 are
answered by the run they belong to), and the full candidate drain 2.4x faster (137 to 58 ms), which is the cursor-open cost
removed: each open on a live LSM index seeks every mutable page and every compacted series (C_cur in the doc, about 30 us each).
The first-row time did not move within the noise: the first cell of the walk is one scan either way, and the stall column says
the two runs were not measured under the same load. The doc's estimate for S12 was "3 to 5x on cursor setup for wide radii".

The same rows come back either way (`GeoRadiusCursorSetupBenchmark` asserts the two candidate sets are equal before timing).

### Correctness runs

`mvn -o -pl engine test` over `**/graph/**/*Test.java`, the query-side graph, edge, degree, expand, traversal and vertex tests,
the super-node and lightweight-edge tests, `**/geospatial/*Test.java` and `**/sql/geo/*Test.java` (the default lanes, `slow`
included): 2,956 tests, 0 failures, 0 errors, after the two expectation updates listed in section 3. `CHECK DATABASE` is
covered by the `CheckDatabase*Test`, `GraphDatabaseChecker*Test` and `Issue10463GhostEntriesAreRepairedNotProbedTest` classes
in that set; `StripedEdgeList` by `SuperNode*Test`, `Issue10314StripedLightEdgeDeleteTest`, `Issue10377SuperNodePruneAndLightEdgeRebuildTest`
and `Issue5147SuperNodeChunkRaceTest`. `Issue8378GAVBuildRacesCommitsTest.asynchronousBlockingBuildRacedByACommitConverges`
failed once in the first run (an async view racing commits, type-filtered counts on both sides, so not the degree field) and
passed in the two runs after, including the full one.

## 3. Tests

New: `EdgeListDegreeTest` (degree across flips and restart, removals from the head and older chunks, iterator removal, vertex
deletion, lightweight edges, legacy chain migration, super-node stripes, concurrent appends across flips, read-only views,
`nextEntry` against `getRID`, sealing), `GeoIndexCursorCoalescingTest`, `SQLFunctionGeoPredicateShapeCacheTest`,
`AdjacencyPivotBenchmark` and `GeoRadiusCursorSetupBenchmark` (`@Tag("benchmark")`).

Updated: `Issue8417TypeFilteredScanTest` (counts `nextEntry` decodes too and follows `previousForWalk`; the expectation becomes one
decode per match instead of two), `Issue5155EdgeRemovalAnchorTest` (a removal from the deepest chunk anchors that chunk AND the
head, whose degree it moves: two pages, still fewer than the chain spans).

Suites run: see `verification-log.md` style summary at the end of this file.

## 4. Risks and what they trade

1. **Format**: a database that has written one degree-carrying chunk cannot be opened by a release without this change. Mitigation:
   `arcadedb.graph.edgeListDegree=false` during a rolling upgrade; the legacy layout stays readable forever and migrates lazily.
2. **A head flip now rewrites the old head** (seal, 1 slot write on its page, poisoned for the append merge). On a hub with
   concurrent appenders a flip can now conflict with an in-flight append on the old head where before both committed: one retry
   per flip at worst, once per ~1,000 appends on cap-size chunks. This is what keeps the degree exact; read speed outranks write
   throughput per the owner's ordering rule.
3. **A removal from an older chunk rewrites the head**: one more anchored page per such removal (`Issue5155EdgeRemovalAnchorTest`
   now pins two), and a concurrent append to the head conflicts with it. Removals are rare in Recon's append-mostly lists.
4. **A wrong degree would be silent.** The invariant is pinned by the tests above, including the concurrent race, but `CHECK
   DATABASE` does not yet verify or repair the field (deferred item 1). A chain whose degree is suspected wrong can be reset by
   setting it unknown; there is no operator command for that yet.
5. **Page views** keep the whole page's `byte[]` reachable while a walk holds a chunk (64 KB per chunk held, bounded by the chain
   length a cursor is parked on). The views are never written (every mutator checks), and a walk inside a transaction that wrote
   the chunk reads the written copy, as before. After-read events do not fire for chunk hops any more (a listener on edge segments
   was never a supported shape); `DatabaseStats.readRecord` no longer counts chunk hops.
6. **Minimum chunk size**: `LocalDatabase.MIN_EDGE_LIST_CHUNK_SIZE` is 32 bytes; with the 25-byte header a 32-byte first chunk
   holds about one entry and flips at once to 64. The default initial size (64) holds about five. `LocalDatabase` is not mine to
   change; raise `arcadedb.graph.edgeListInitialChunkSize` if 32 is in use anywhere.
7. **Geo coalescing** relies on the walk visiting siblings in GeoHash order and on no indexed token using a character outside the
   alphabet, both properties of Lucene's `GeohashPrefixTree`; a violation costs coalescing (the adjacency test fails) never rows.

## 5. Deferred

1. `CHECK DATABASE` verification and `FIX` of the degree field (recount the chain, rewrite the head); a `degreeMismatches` counter.
2. S7's per-statement (head RID to segments) cache in the traversal context. `GraphTraverser` instances are created per start
   vertex by the variable-length operators, where the visited set already prevents re-expansion, so a cache there rarely hits; the
   right home is the Cypher `ExpandAll` operator or `CommandContext`, outside the files of this workstream. The hooks it needs are
   in place (`EdgeLinkedList.readChunkForWalk`, `EdgeSegment.previousForWalk`).
3. `EdgeEntryRIDIterator.nextInto(long[])`: a RID-free neighbour walk for the plugin's hydrate path (today one `RID` per entry).
4. Using the degree in `GraphBatch`-built chains without waiting for the first flip (the batch knows how many entries it wrote).
5. The sibling geo worktree (`claude/geo-knn-b2da7a`: `GeoNearestSearch`, `geo.dWithinMeters`) opens cells through
   `LSMTreeGeoIndex` too; its ring walk can use `openFrontierRunCursor` once merged.
