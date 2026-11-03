# Workstream: graph adjacency (S6, S7) and geo cursor (S12)

Status: implemented and measured in worktree `.claude/worktrees/agent-adafdfb8b2f5f8705`, branch
`worktree-agent-adafdfb8b2f5f8705`, on top of 4bc577f11. Nothing is committed; the review decides. Items from
`../02-retrieval-speed.md` section 4.2: S6 (allocation-free adjacency entries), S7 (edge chunks read as page views, previous
pointer as primitives) and S12 (geo cursor: coalesced frontier ranges, primitive `seen` set, search shape parsed once per
statement), plus the F4 safety fix found by the review. S15 (O(1) vertex degree in the head chunk) was built, reviewed five times
and **removed** on 2026-11-03: see section 4.

**No on-disk format change.** Edge chunks are written exactly as at 4bc577f11 (record type 3, 17-byte header: type byte, used
bytes, previous chunk RID), so any release reads a database this code wrote and the other way round. `git diff 4bc577f11` touches
no writer of a chunk's bytes, no record type, no setting and no `CHECK DATABASE` result key.

The Recon pattern this serves is the 1 to 2 hop pivot from an identifier vertex to its records (P1 to P3 in the doc), where the
identifier is often a hub with thousands of entries.

## 1. What is kept

### S6: allocation-free adjacency entries

`EdgeSegment.nextEntry(int position, long[] entry)` decodes the four numbers of an entry (edge bucket, edge position, vertex
bucket, vertex position) off the buffer's bytes in one pass, without moving the buffer position and without allocating; it
returns the position of the next entry. Every iterator (`VertexIterator`, `EdgeIterator`, `RIDIterator`, `RIDIteratorFilter`,
`IteratorFilterBase` and its vertex/edge/entry filters, `EdgeVertexIterator`) steps on it and builds a `RID` only for what it
hands out: the vertex walks never build the edge RID, and the neighbour filter and the deleted-self-loop check decide on the
primitives before any RID exists. `ResettableIteratorBase.currentPosition` is a plain `int` (it was an `AtomicInteger` updated by
CAS on every entry), and the entry the last `next()` returned is remembered by position and decoded only if `remove()` asks for it
(eagerly when the entry sits in the transaction's live copy of the chunk, which the transaction itself can shift: after an unlink
moved the walk onto it, a flag `countEntries()` saves and restores across its own chunk hops, or whenever the walked instance IS
the transaction's written record, asked of the transaction for any container that is not a page view, so the flag is not the only
guard).
`getRID(AtomicInteger)` and `skipRID(AtomicInteger)` stay for `LightEdgeRID` and compatibility, implemented on absolute reads.
`containsEdge`, `getFirstEdgeConnectedToVertex`, `count`, `countInto`, `nextEntryInBuckets`, `forEachEntry`, `toJSON` and the
light-edge probes read at absolute positions too, so a page view shared by several readers is never moved under them. The writers
(`add`, `removeEdge`, ...) are the base code.

### S7: chunks read as page views, previous pointer as primitives

`EdgeLinkedList.readChunkForWalk` / `readRecordForWalk` read a chunk for a walk as a read-only view of its cached page
(`MutableEdgeSegment.view`; every mutator refuses on it with an `IllegalStateException`) instead of the copy `lookupByRID` makes,
skipping the up to 8 KB copy, the after-read event dispatch and the record statistics of a lookup; the bucket permission check and
the before-read events still run. The transaction's own written copy of a chunk wins when it has one (its pending appends live only
there), and anything that is not an edge segment (a stripe directory, a damaged slot) is built as `lookupByRID` builds it (the
transaction's cached instance first, then the record factory, the after-read events and one `readRecord`), from the content that
same bucket read returned, so it raises what it always raised and a super-node list open reads its stripe directory once, as at
the base (it used to read the bucket, see the type, then read it again through `lookupByRID`: two before-read events). Used by the chunk hops of every iterator (`EdgeSegment.previousForWalk()`), by the counting walks of
`EdgeLinkedList` (`count`, `countInto`, so `countEdges`) and by `GraphEngine.buildEdgeList`, the read side of every
`getEdgeHeadChunk`. The write side (`loadChunkForWrite`, `removeEdge`, `deleteAll`, the head flip) reads anchored copies as before.
`Iterator.remove()` and `CHECK DATABASE`'s `removeEntryKeepingEdge()` never write the view they walked: they take the
transaction's copy of the chunk (`lookupByRID`, as the base `remove()` already did) and unlink by position only when that copy
still holds the entry there, otherwise by identity.

`getPreviousRID()` reads the two fixed-width numbers of the pointer at their offsets instead of going through the serializer's
`deserializeValue` dispatch; `hasPrevious()` answers without building the RID.

Not every read-only walk uses views: `containsEdge`, `containsLightEdge`, `containsVertex`, `getFirstEdgeConnectedToVertex` and
`toJSON` of `EdgeLinkedList` still hop through `previousOf` (a `lookupByRID` copy per hop). Moving them is a one-line change each,
left for a measured follow-up because they are not on the pivot path.

Observable: a database-level `AfterRecordReadListener` no longer hears the chunk hops of a walk (the before-read events still fire,
in the bucket), and `DatabaseStats.readRecord` no longer counts them. A listener on edge segments was never a supported shape.

Not done from S7: the per-statement (head RID to segments) cache in the traversal context (section 6).

### S12: geo cursor

- `GeoIndexCursor` answers a run of adjacent frontier cells with ONE prefix range scan (`CellCursorFactory.openFrontierRun`,
  `LSMTreeGeoIndex.openFrontierRunCursor`): two frontier siblings whose last characters are consecutive in the GeoHash alphabet
  have no cell between them in key order, so the range from the first token to the last token's upper bound covers exactly the run
  (`GeoIndexCursor.adjacentSiblings`). The walk is a pre-order over the cell tree, so such siblings arrive one after the other.
  Off for the FULL layout (exact lookups) and for the cursor's three-argument constructor, which the existing unit tests drive one
  cell at a time;
- the `seen` set is a `RidHashSet` (two primitive arrays) instead of a `HashSet<RID>`;
- `SQLFunctionGeoPredicate.parseShape` keeps the last SEARCH shape parsed (the second argument), keyed by an immutable snapshot of
  the value it came from (the WKT `String`, or `Map.copyOf` of a `point()` map; other values are not cached), and answers it for an
  equal value: the search shape is parsed once per statement instead of once per row. The first argument, the record's own
  geometry, is parsed without the cache (no per-row slot). One function instance serves every execution of a cached statement from
  several threads, so the slot is a volatile reference to an immutable pair (`engine-concurrency` skill, lock-free snapshot
  pattern); two threads racing on the same value compute the same shape twice at worst.

### F4: a new chunk never drops the entry it was created for

- `EdgeLinkedList.addToNewChunk` throws `IllegalStateException` when a chunk just created refuses the entry it was created for,
  instead of ignoring `add()`'s `false` and losing the edge silently. Used by `EdgeLinkedList.add`, `addAll` and
  `StripedEdgeList.add` (a stripe's first chunk and its head flip).
- `LocalDatabase.MIN_EDGE_LIST_CHUNK_SIZE` is 64 (was 32). Still needed with the base 17-byte header: the widest entry is 30 bytes
  (two RIDs of a 5-byte bucket and a 10-byte position) and a 32-byte first chunk has 15 bytes of room, so an entry with wide bucket
  ids or large positions did not fit. The default first chunk is already 64, so only a configured
  `arcadedb.graph.edgeListInitialChunkSize` below 64 changes (it is floored to 64).

### Files

`EdgeSegment` (`nextEntry`, `previousForWalk`, `hasPrevious`), `MutableEdgeSegment` (`view`, `isReadOnlyView`, read-only guard on every mutator,
absolute reads, `nextEntry`), `EdgeLinkedList` (`readChunkForWalk`, `readRecordForWalk`, `recordReadForWalk`, `previousViewOf`, `addToNewChunk`),
`ResettableIteratorBase` and the seven iterators, `GraphEngine.buildEdgeList` (head as a view), `StripedEdgeList` (`addToNewChunk`
only), `LocalDatabase` (chunk floor), `GeoIndexCursor`, `LSMTreeGeoIndex`, `SQLFunctionGeoPredicate`. Unchanged from the base:
`RecordFactory`, `EdgeListScanner`, `GraphBatch`, `LightEdgeRID`, `GraphDatabaseChecker`, `DatabaseChecker`,
`GlobalConfiguration`, `TransactionContext`, and the add/remove/flip logic of the edge lists.

## 2. Measured

Laptop (Apple Silicon, JDK 21), default test profile, warm page cache, single thread. Numbers are for relative comparison only;
wall time is discounted of JVM stalls observed inside the window (`StallAwareStopwatch`), allocation is
`ThreadMXBean.getThreadAllocatedBytes` on the measuring thread. Each benchmark runs its "before" and "after" on the same data in
the same JVM. Commands:

```
mvn -o -pl engine test -Dtest=AdjacencyPivotBenchmark -DexcludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl engine test -Dtest=GeoRadiusCursorSetupBenchmark -DexcludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false
```

Measured on the final code (S15 removed), two runs each, 2026-11-03. No JVM stall was observed inside any window.

### `AdjacencyPivotBenchmark` (S6, S7)

1-hop pivot: the 500 newest neighbours of a 10,000-degree vertex, 200 runs.

| walk | run 1 ns/entry | run 2 ns/entry | bytes/entry |
|---|---:|---:|---:|
| before: `getRID` x2 + `lookupByRID` copy per chunk | 247 | 237 | 181 |
| after: `getVertices()` (`nextEntry`, vertex RID only, page views) | 151 | 126 | 103 / 96 |
| after: `getConnectedVertexRIDs()` | 61 | 73 | 35 |
| before: full walk, `getRID` x2 + copies | 53 | 53 | 111 / 117 |
| after: full walk, `getVertices()` | 54 | 41 | 89 |

The pivot shape is 1.6x to 1.9x faster with `getVertices()` and 3.2x to 4x faster when only RIDs are needed, and allocates about
half (vertex walk) to a fifth (RID walk) per entry. On a full walk the gain is smaller (equal to 1.3x) because the per-entry cost is
dominated by the vertex lookup both sides share; allocation still drops by about a quarter.

`countEdges` on a 100,000-degree hub, 50 runs (the count is a walk, as at the base):

| count | run 1 us/call | run 2 us/call | bytes/call |
|---|---:|---:|---:|
| before: chain walk over chunk copies | 820.5 | 891.2 | 851,529 |
| after: chain walk over page views | 835.1 | 874.6 | 39,164 / 39,209 |

Time is a wash (within run-to-run noise); allocation drops 22x, which is what the page views buy here: no 8 KB copy per chunk.
For comparison, S15's stored degree answered the same call in about 2 us; that is the number the data-model degree (section 4)
replaces.

### `GeoRadiusCursorSetupBenchmark` (S12)

200,000 points, 200 km radius, 19,675 candidates, 30 runs.

| cursor | scans | coalesced | run 1 first row us | run 2 first row us | run 1 drain ms | run 2 drain ms |
|---|---:|---:|---:|---:|---:|---:|
| before: one scan per cell | 1,413 | 0 | 393.7 | 270.7 | 49.38 | 28.98 |
| after: one scan per run | 261 | 1,152 | 444.6 | 353.1 | 23.64 | 18.36 |

Coalescing cuts the range scans 5.4x and the full drain 1.6x to 2.1x. Time to the first row is 50 to 80 us slower (measured, not yet
attributed; the first coalesced range covers several cells before it yields a row). That is the trade: the radius query that drains its candidates
(the common case: a filter and a sort follow it) is faster, a `LIMIT 1` on it is slightly slower.

### Correctness runs

`mvn -o -pl engine test -DexcludedGroups=benchmark,vector` over every graph, edge, supernode, lightweight-edge, `CHECK DATABASE`
and geo test class of the engine (`com/arcadedb/graph/**/*Test` plus `*Edge*Test`, `*Edges*Test`, `*SuperNode*Test`,
`*Supernode*Test`, `*Lightweight*Test`, `*LightEdge*Test`, `*CheckDatabase*Test`, `*DatabaseChecker*Test`, `*Geo*Test`):
**308 classes, 2,672 tests, 0 failures, 0 errors, 0 skipped** (the `com.arcadedb.graph` package alone: 202 classes, 1,649
tests). After the two review minors of 2026-11-03 (section 3), the same set plus `*Iterator*Test`: **318 classes, 2,762 tests, 0
failures, 0 errors, 0 skipped** (`com.arcadedb.graph`: 202 classes, 1,651 tests). Among them `EdgeListWalkTest` 8,
`Issue9779EdgeIteratorRemoveTest` 57, `Issue10375IteratorRemoveEdgeAppendMergeTest` 10, `GeoIndexCursorCoalescingTest` 4, `SQLFunctionGeoPredicateShapeCacheTest` 4,
`Issue8417TypeFilteredScanTest` 10, `Issue5155EdgeRemovalAnchorTest` 2, `EdgeListChunkSizeTest` 2.

## 3. Tests

New: `EdgeListWalkTest` (a page view refuses every write and its copy does not; `nextEntry` decodes what `getRID` decodes,
including multi-byte and negative numbers, and the absolute-read probes agree; the previous pointer read without the serializer;
F4: the floor holds the widest entry after the 17-byte header and a too-small new chunk throws instead of dropping the entry; a walk
inside a transaction reads its own written chunk; `Iterator.remove()` of an entry in a chunk the walk read as a view;
`aCountBetweenNextAndRemoveOnTheLiveCopyDeletesTheEdgeReturned`: `next()`, `remove()`, `countEntries()`, `next()` x2, the same
transaction deletes the second edge, `remove()` deletes the third and not the fourth that shifted onto its position;
`openingASuperNodeListReadsItsStripeDirectoryOnce`: one before-read event on the stripe directory per list open),
`GeoIndexCursorCoalescingTest`, `SQLFunctionGeoPredicateShapeCacheTest`, and the two `@Tag("benchmark")` classes
`AdjacencyPivotBenchmark` (its count rows now compare the chain walk over copies with the walk over views; the degree-field row is
gone) and `GeoRadiusCursorSetupBenchmark`.

Updated: `Issue8417TypeFilteredScanTest` (counts `nextEntry` decodes too and follows `previousForWalk`; the expectation becomes one
decode per match instead of two).

Back to the base: `Issue5155EdgeRemovalAnchorTest` (a removal from the deepest chunk anchors that chunk only) and
`EdgeAppendMergeMultiPageChunkTest` (reads each chunk from `CONTENT_START_POSITION`).

Deleted with S15: `EdgeListDegreeTest` (28 tests) and `EdgeListDegreeRaceTest` (10 tests). Their three tests that were about S6, S7
and F4 rather than the degree (`aPageViewOfAChunkRefusesEveryWrite`, `nextEntryDecodesWhatTheRidReadersDecode`,
`theSmallestFirstChunkHoldsTheWidestEntryAndANewChunkNeverDropsOne`) were rewritten without the degree into `EdgeListWalkTest`.

## 4. S15 removed after five review rounds: why, and where O(1) degree lives instead

S15 kept the number of entries of the whole chain in the head chunk of each edge list (a second chunk layout, record type 8, with
an 8-byte degree after the 17-byte header), so `countEdges` was one field read: measured 2 us against 619 us for the walk on a
100,000-degree hub. It was removed, whole, for three reasons.

1. **Every review round found a new race between removals and concurrent head flips.** The field lives in one chunk while the
   entries it counts live in every chunk of the chain, and the engine's concurrency unit is the page: an append to the head commutes
   with everything (the edge-append merge replays it), a removal behind the head does not, and a head flip moves the field to a new
   chunk. Keeping the count exact needed a seal on every retired head, a trim of every chunk a removal opened room in, an anchored
   rewrite of the head for every removal behind it, a re-read of the vertex's head pointer, a resolution of the head an iterator
   started on when its own transaction had flipped it since, `DEGREE_SEALED`/`DEGREE_UNKNOWN` states, an underflow counter, and a
   `CHECK DATABASE` verify and FIX that themselves raced live writers. Rounds 1 to 3 fixed F1 to F6 and R1 to R3; round 4 descoped
   migration and the online rewrite; round 5 made the layout opt-in after finding that an older release opens such a database and
   then fails on every list holding a type-8 chunk (and its `CHECK DATABASE FIX` drops the lightweight edges of those lists). Each
   fix added state that the next round found another interleaving against. A wrong degree is silent, so "almost always exact" is
   not an acceptable bar for it.
2. **It was a forward-incompatible on-disk format change** for a retrieval-speed item, with an HA rolling-upgrade and rollback
   hazard that only a setting kept closed.
3. **It did not reach the data that needs it.** After round 4 only chains born with the setting on kept a degree: every hub of an
   upgraded database, Recon's identifiers included, kept the walk until a rebuild path that was never built.

**Where O(1) degree lives instead: the data model** (`../01-architecture.md` section 4b.2, read operation R3). `graph.degrees(ids)`
answers from `recordCount` and the precomputed `degree_<type>` properties that ingest maintains on the registry identifier
vertices (CO_OCCURS, MEMBER_OF, ACCEPTED_LINK counts), with `Vertex.countEdges` as the fallback. Those are ordinary properties,
written in the same transaction as the edge they count by the one writer (ingest) that knows the edge's type and the identifier's
role, so they need no new storage format and no engine-level concurrency protocol: two transactions changing the same identifier
conflict on its record, as any update does. It is also the shape R3 asks for (a count per link type, which a single per-list degree
could not answer without a walk anyway).

What the engine keeps for counting: `countEdges`, `EdgeLinkedList.count()` and `countInto()` walk the chain, as at the base, but
over page views (S7) and absolute reads (S6) instead of copies: see the count rows of section 2.

## 5. Risks and what they trade

1. **Page views keep the whole page's `byte[]` reachable** while a walk holds a chunk (64 KB per chunk held, bounded by the chain
   length a cursor is parked on). The views are never written (every mutator checks), and a walk inside a transaction that wrote the
   chunk reads the written copy. They depend on three page-cache invariants, which a change to `PageManager` eviction or a page
   prefetcher must keep (another worktree changes eviction and adds prefetch): (1) the `byte[]` of a published
   `ImmutablePage`/`SharedImmutablePage` is never written or recycled after publication; (2) a page replaced by a commit is a new
   object with a new array, never the old array rewritten in place; (3) a prefetch fills its buffer completely before publishing
   it. Any of the three broken would let a walk read bytes that change under it.
2. **Listeners and statistics**: database-level `AfterRecordReadListener`s and `DatabaseStats.readRecord` no longer see chunk hops
   (section 1, S7).
3. **Chunk floor**: a configured `arcadedb.graph.edgeListInitialChunkSize` below 64 is floored to 64 (was 32); a chunk that still
   refuses its first entry now throws instead of losing the edge.
4. **Geo coalescing** relies on the walk visiting siblings in GeoHash order and on no indexed token using a character outside the
   alphabet, both properties of Lucene's `GeohashPrefixTree`; a violation costs coalescing (the adjacency test fails), never rows.

## 6. Deferred

1. S7's per-statement (head RID to segments) cache in the traversal context. `GraphTraverser` instances are created per start
   vertex by the variable-length operators, where the visited set already prevents re-expansion, so a cache there rarely hits; the
   right home is the Cypher `ExpandAll` operator or `CommandContext`. The hooks it needs are in place
   (`EdgeLinkedList.readChunkForWalk`, `EdgeSegment.previousForWalk`).
2. `EdgeEntryRIDIterator.nextInto(long[])`: a RID-free neighbour walk for the plugin's hydrate path (today one `RID` per entry).
3. The probes of `EdgeLinkedList` that still hop through copies (section 1, S7).
4. The sibling geo worktree (`claude/geo-knn-b2da7a`: `GeoNearestSearch`, `geo.dWithinMeters`) opens cells through
   `LSMTreeGeoIndex` too; its ring walk can use `openFrontierRunCursor` once merged.
5. Found during S15's round 4 and independent of it (it reproduces on the base layout): an online `CHECK DATABASE FIX` races
   concurrent deletes in its adjacency repairs. An entry whose edge a concurrent transaction just deleted is pruned as dangling,
   the iterator removal then fails ("The edge list was modified during remove()"), the list is rebuilt from the surviving edge
   records of a scan that misses the edges appended meanwhile, and afterwards `CHECK DATABASE` reports edges missing from their
   vertex's list. Related: the connectivity check's `catch (Throwable)` takes a retryable conflict for a record that cannot be
   loaded, flags it as corrupt, and under FIX deletes it. The adjacency walks need to handle retry signals, or FIX should refuse to
   run online.
