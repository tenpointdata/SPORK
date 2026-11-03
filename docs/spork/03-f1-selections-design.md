# F1 design: selections and compare

Status: design note, slices 1 to 5 implemented, 2026-11-03. Roadmap item F1 in `02-feature-roadmap.md`.
Goals served: **S** (set-oriented retrieval), **R** (retrieval speed), **I** (identity layer).

## 1. Problem

Recon's compare feature (`recon-next/src/compare.ts`) puts 2 to 4 selections side by side in four modes (records,
entities, sets, faces) and asks: what do they share, what is distinct, and how big is each overlap. Today SPORK has no
engine primitive for this:

- The set functions (`function/sql/coll/SQLFunctionIntersect`, `SQLFunctionDifference`, ...) copy every input into a
  `HashSet<Object>`, about 100 bytes per RID, and run one pairwise pass per argument.
- `query/sql/executor/RidSet` is an uncompressed bitmap whose memory is proportional to the highest position seen in
  each bucket, not to the number of elements. Selections are sparse in a 290M-record bucket, which is its worst case.
- Nothing computes "which identifiers are reached by which selections" in one pass, so the plugin would have to pull
  every neighbourhood to the client and intersect there.

## 2. Design

### 2.1 `RidBitmap` (engine, `com.arcadedb.utility`)

A compressed set of RIDs built on RoaringBitmap (Apache 2.0, no transitive dependencies).

- **Layout.** A RID is `(bucketId: int, position: long)`, and `position = pageId * maxRecordsInPage + slot`
  (`LocalBucket.recordPosition`), which can pass 2^32 in one large bucket. So the set is a sorted map from a 64-bit key
  `(bucketId << 32) | (position >>> 32)` to one 32-bit `RoaringBitmap` of the low position bits. In practice there is
  one entry per bucket; huge buckets get a second entry rather than failing.
- **Operations.** `add`, `remove`, `contains`, `cardinality`, `isEmpty`, static `and`/`or`/`andNot`/`xor` that return
  new bitmaps, in-place `andWith`/`orWith`/`andNotWith`, `forEach(bucketId, position)` without allocation, and an
  `Iterator<RID>`. Iteration is ordered by bucket, then position, so results are deterministic.
- **Serialization.** `toBytes()`/`fromBytes()`: a version byte, the entry count, then per entry the 64-bit key and the
  bitmap in RoaringBitmap's portable format. The portable format is readable by the `roaring` npm package, so Recon
  can hold and combine selection handles client-side without a round trip (slice 3).
- **Cost.** Roaring stores a sparse 2,000-record selection in a few KB and a dense one at about 1 bit per position.
  AND/OR/ANDNOT run word-at-a-time over containers, about 1 ms per 10M members.

### 2.2 Cypher procedures (slice 1)

Both procedures take `selections`: a list of 2 to 8 lists, each holding nodes, RIDs or RID strings (`'#12:5'`).
Members that no longer exist are skipped, because a saved selection can go stale. The optional `options` map is checked
key by key (an unknown key is refused, as `extractConfig` does for `algo.*`).

| Option | Meaning | Default |
|---|---|---|
| `via` | Edge types to expand through: a name, a list, or `'*'` for every type. Absent means compare the members themselves (records mode) | absent |
| `direction` | `OUT`, `IN` or `BOTH` | `BOTH` |

**`selection.venn(selections, options?)`** yields one row per non-empty Venn region:
`mask` (bit i set means the region lies in selection i), `selections` (the indices in the mask) and `count`. Regions
are computed by bitmap algebra: region(m) = AND of the sets in m, ANDNOT the OR of the sets outside m. That is 2^k
regions of k operations each, never a per-element loop.

**`selection.compare(selections, options?)`** yields one row per element (member, or neighbour reached `via`) that
belongs to at least `minShared` selections:
`node`, `mask`, `selections`, `sharedBy`, and when `memberCap > 0`, `counts` (for each selection, how many of its
members reach the node) and `members` (up to `memberCap` member RIDs per selection, the evidence an analyst clicks).

| Extra option | Meaning | Default |
|---|---|---|
| `minShared` | Minimum number of selections an element must be in; 1 also returns the distinct elements | 2 |
| `limit` | Maximum rows | 1000 |
| `memberCap` | Members returned per selection per row; 0 skips the evidence pass | 10 |

Rows come out by `sharedBy` descending (regions with more bits first), then in RID order, so `limit` keeps the most
shared elements first without sorting. The evidence pass walks the selections' neighbourhoods a second time, only for
the rows being returned; `memberCap: 0` skips it when only the identifiers are needed.

Mapping to Recon's compare modes:

| Recon mode | Call |
|---|---|
| records (which records overlap) | `selection.venn(sels)` / `selection.compare(sels, {minShared: 2})` |
| entities (identifiers shared across selections of records) | `selection.compare(sels, {via: ['HAS_PHONE', 'HAS_EMAIL', ...], direction: 'OUT'})` |
| sets (saved result sets) | Same as records, once slice 2 handles exist |
| faces (cross-group face clusters) | Through `via: 'HAS_FACE'` to Face vertices for shared faces; similarity clustering stays in the ROC tier |

### 2.3 Saved selections (slice 2)

A saved selection (a handle) is an ordinary document of the type `SelectionHandle`, so Raft replicates it, backups carry
it, and the normal record security governs it. One document per name:

| Property | Type | Notes |
|---|---|---|
| `name` | STRING | Mandatory; UNIQUE LSM index; 1 to 256 characters |
| `members` | BINARY | `RidBitmap.toBytes()`, after `runOptimize()` |
| `cardinality` | LONG | Members when saved (stale members are not subtracted later) |
| `createdAt` | LONG | Epoch milliseconds |
| `expiresAt` | LONG | Epoch milliseconds; null means it never expires; NOTUNIQUE LSM index for the purge |
| `source`, `language`, `params` | STRING, STRING, MAP | Provenance: the query text, its language and its parameters; or `members` / `combine:<op>(...)` |
| `createdBy` | STRING | The database user who saved it |

**Type creation.** The first save creates the type, its declared properties and both indexes inside one
`schema.bulkChange`, with the async executor quiesced first, as `Labels.ensureCompositeType` does. One outermost schema
frame means one Raft schema entry. A type of that name that lacks the `name` unique index is refused rather than
reused.

**Expiry is lazy.** An expired handle reads as expired everywhere (resolving it fails, naming the expiry time). Every
write procedure deletes up to 100 expired handles as it runs, through the `expiresAt` index, and `selection.purge()`
deletes them all. No background thread: deletes happen only on the node a write already runs on (the leader under HA),
so no new leader-only scheduler is needed.

**Procedures.**

| Procedure | Writes | Yields |
|---|---|---|
| `selection.save(name, source, options?)`: `source` is a list of members, or a query string run read-only under the caller's user (`language`, default `opencypher`, and `params` options); every record identity in a row, or in a list inside a row, becomes a member | yes | `name, cardinality, createdAt, expiresAt` |
| `selection.combine(op, inputs, name, options?)`: `op` is `and`, `or`, `andNot` (the first input minus all the others) or `xor` (members in an odd number of inputs); `inputs` are 2 to 8 handle names or member lists | yes | as save |
| `selection.get(name)` | no | `name, cardinality, createdAt, expiresAt, source, language, params, createdBy` |
| `selection.members(name, options?)`: `skip`, `limit` (default 1000), in RID order, stale members skipped | no | `node` |
| `selection.drop(name)` | yes | `name, dropped` |
| `selection.purge()` | yes | `purged` |

Save and combine options: `ttl` in seconds (default 86,400; 0 never expires) and `replace` (default false). Saving
over a live handle without `replace` is refused; an expired handle is overwritten silently.

`selection.venn` and `selection.compare` accept a handle name anywhere a selection list goes: at the selection level a
string is a handle name, while a string inside a list is still a member RID.

**Not in slice 2:** case scoping by Operating Context (needs F13 and the plugin's OC binding), handles readable only by
their creator, and a size cap on query-sourced handles beyond the command timeout.

**HA caveat, untested.** A follower running a CLIENT-managed transaction can create a missing type only through the
leader, and that route forwards the type alone (`TypeBuilder.toSQL`), not its properties and indexes (ha-raft/CLAUDE.md,
#10067). So the very first save of a cluster should run outside a client transaction (any plain HTTP command, which the
follower forwards whole to the leader) or on the leader. After that the type exists everywhere and the caveat is moot.
An HA integration test belongs to slice 3, with the HTTP routes.

### 2.4 HTTP routes (slice 3)

Eight `POST /api/v1/selection/{database}/<operation>` routes, all JSON in and out. They follow the vector routes'
shape: each handler is thin and delegates to one engine service, `com.arcadedb.query.selection.SelectionService`, which
MCP or gRPC can call later without re-deriving anything.

| Operation | Body | Answer |
|---|---|---|
| `save` | `name`, exactly one of `members` (RID strings), `query` (with `language`, `params`) or `bitmap` (base64 of `RidBitmap.toBytes()`), `ttl`, `replace` | `name, cardinality, createdAt, expiresAt` |
| `combine` | `op`, `inputs` (names or RID lists), `name`, `ttl`, `replace` | as save |
| `compare` | `selections`, `via`, `direction`, `minShared`, `limit`, `memberCap` | `count`, `rows[]` of `rid, properties, mask, selections, sharedBy, counts, members` |
| `venn` | `selections`, `via`, `direction` | `regions[]` of `mask, selections, count` |
| `get` | `name`, `bitmap` (boolean) | the handle's metadata and provenance, plus `bitmap` (base64) when asked |
| `members` | `name`, `skip`, `limit`, `properties` (default true) | `count`, `members[]` of `rid` and, unless turned off, `properties` |
| `drop` | `name` | `name, dropped` |
| `purge` | none | `purged` |

**Cluster routing comes from the engine, not the handler.** The service runs each operation as a Cypher `CALL
selection.*`. On a follower, `RaftReplicatedDatabase.command` forwards a non-idempotent statement (a write-procedure
call) to the leader through Raft, and reads run locally under the request's read-consistency header. So the routes need
no forwarding code of their own. The write routes open the usual auto-commit transaction and do NOT join a session
transaction a client opened with `/begin` (`participatesInSessionTransaction` false), which keeps them on the forwarded
path and makes the slice 2 HA caveat unreachable from HTTP.

**Errors.** A refusal from a procedure (`IllegalArgumentException` anywhere in the cause chain) is answered 400 with
its message; the service unwraps the chain itself, because the HTTP error mapping looks only one cause deep.

**Bitmap exchange.** `get` with `bitmap: true` returns the members in the `RidBitmap` envelope (a version byte, a key
count, then per key a 64-bit `(bucketId << 32) | (position >>> 32)` and a RoaringBitmap in its portable format), and
`save` accepts the same bytes back. A client can therefore intersect or subtract saved selections locally with any
RoaringBitmap port, then save the result, without shipping RID lists either way.

### 2.5 Modalities accept a selection (slice 4)

Every modality could already EMIT a selection (`selection.save` runs any query). Slice 4 lets them ACCEPT one as the set
to search within, inside the index scan, not as a filter after it:

| Modality | How it takes a selection | Mechanism |
|---|---|---|
| Full-text in SQL (`SEARCH_INDEX`) and the geo predicates (`geo.within`, `geo.intersects`, ...) | `... AND @rid IN selection.rids('g0')` (or `NOT IN`) | The planner already pushes `@rid IN <expression>` into an indexed function as a `RIDFilter` allow-list (#9909, #11069), so the index tests each candidate's RID before loading a record |
| Vector in SQL (`vector.neighbors`) and Cypher (`db.index.vector.queryNodes`) | option `{selection: 'g0'}` | The allow-list the index tests candidates against (`SQLFunctionVectorAbstract.parsePreFilter`), ANDed with `filter` and `where` |
| Full-text in Cypher (`db.index.fulltext.queryNodes`) | option `{selection: 'g0'}` | The same allow-list, through `FullTextQueryOptions` |

**Two planner changes in `SelectExecutionPlanner`, both narrow.**
- `isKnownBeforeFetch` accepts `selection.rids(<literal or parameter>)` as known before the fetch, like a literal list, so
  the `@rid IN` condition is pushed into the indexed function (`RID FILTER` in the plan).
- The RID-address rewrite (#5824, `@rid IN <list>` fetched directly) steps aside when the list is a saved selection AND
  the same AND block holds an index-backed function. The direct fetch would load every member and test the search on
  each; the indexed fetch loads at most the matches inside the selection. Alone, `@rid IN selection.rids('g0')` still
  fetches the members directly.

**No copy of the selection.** `selection.rids(name)` answers a `RidBitmapSet`: a read-only `Set<RID>` view over the
saved bitmap (`contains` is a bitmap probe, `size` its cardinality). `RIDFilter` keeps such a view as its allow-list
instead of copying it into a `HashSet` (about 100 bytes per member), and reads the buckets it can skip from the bitmap's
keys instead of walking every member. The function caches the decoded bitmap per command context, so a full scan that
evaluates `@rid IN selection.rids('g0')` per record decodes it once.

### 2.6 Search services and MCP (slice 4b)

**Search services.** `VectorSearch`, `FullTextQuery` and `HybridSearch` (`com.arcadedb.query.search`, shared by the
`/api/v1/vector/*` routes, the MCP search tools and the gRPC vector RPCs) take a `selection` argument, checked before any
I/O (`SearchSelection`). A vector leg passes it to `vector.neighbors` or `vector.sparseNeighbors` as the option of the
same name; a full-text leg turns it into the `RIDFilter` its index applies before scoring. A hybrid search applies it to
both legs, so the fused list holds only members.

**MCP.** Two tools, split along the MCP permission line:

| Tool | Operations | Gate |
|---|---|---|
| `selection_query` | `get`, `members`, `compare`, `venn` | read access |
| `selection_update` | `save`, `combine` | insert and update, plus schema change while the `SelectionHandle` type does not exist yet (the first save creates it) |
| | `drop`, `purge` | delete |

Both are admitted through the query admission gate, listed by `list queries`, and in the `rag` profile beside the
upsert tools. The search tools' schemas advertise `selection` (`MCPToolUtils.withSelectionArgument`).

### 2.7 gRPC (slice 4c)

Eight RPCs on `ArcadeDbService` (`grpc/src/main/proto/arcadedb-server.proto`): `SaveSelection`, `CombineSelections`,
`CompareSelections`, `SelectionVenn`, `GetSelection`, `SelectionMembers`, `DropSelection`, `PurgeSelections`. They are
typed messages, not JSON in a string: a selection is a `SelectionRef` (a saved name or a `RidList`), a bitmap travels as
raw `bytes`, records as `GrpcRecord`, and compare rows and members as `SharedElement`. `GrpcSelections` translates
them to and from `SelectionService` with no validation of its own, as `GrpcVectorSearch` does for search, so the three
protocols accept and refuse the same requests. A refusal is `INVALID_ARGUMENT`, through the search RPCs' status
mapping. Like the HTTP routes, no selection RPC joins a client transaction. `VectorSearchRequest` and
`HybridSearchRequest` gain `string selection = 16`, `FullTextSearchRequest` `string selection = 9`, all optional and
backward compatible.

### 2.8 CSR fast path (slice 5)

When a `GraphAnalyticalView` can answer the hop, `selection.venn` and `selection.compare` walk its CSR adjacency instead
of the records: `GraphTraversalProviderRegistry.findProvider(context, via)` hands back a ready view covering the `via`
edge types and every vertex type, pinned to one snapshot for the statement. Each member maps to a dense node id
(`getNodeId`), its neighbours come from `getNeighborIds(node, direction, via)`, and nothing is loaded: not a member,
not an edge segment. Only the rows `compare` returns are loaded, to answer `node`. The evidence pass (counts and
members) uses the same view.

**Fallback is per call, never wrong.** No view, one that lacks a `via` edge type or a vertex type, one still building or
restoring, or a transaction holding uncommitted changes (`findProvider` answers null for it, so the walk sees the
transaction's own writes): the record walk of 2.5. A member the view does not map (a document, a vertex created after
the view's snapshot) is decided by the record walk for that member alone.

**One deliberate difference.** The record walk skips a ghost entry (an adjacency entry whose edge record is gone,
probed once per vertex reached); the view, like every analytical view and count push-down, follows entries by contract
(engine/CLAUDE.md, #10463). The two agree on any database `CHECK DATABASE ... FIX` has repaired.

**Followers.** A view's persisted CSR is leader-only today (F3), so on a follower the first statement may fall back
until the view is rebuilt there; F3 makes the fast path available on every node.

**Not done: parallel expansion.** Through the view, expanding a Recon-sized selection (up to about 2,000 members) is
microseconds of array reads, below the cost of forking per selection; on the record walk, a worker thread would need
the caller's database and transaction context (see the `engine-concurrency` skill). It is left for when the read
benchmark (`benchmarks/`, `read-bench` worktree) shows an expansion-bound workload.

### 2.9 Expansion

Slice 1 reads neighbours with `Vertex.getConnectedVertexRIDs(direction, types)`, which yields RIDs without loading the
neighbour records, and loads only the member vertices. A `WorkGuard` makes the loops abortable under
`arcadedb.command.timeout`. The CSR fast path (`GraphAnalyticalView`) is slice 4, after F3 shows it works on followers.

## 3. Slices

| Slice | Content | Status |
|---|---|---|
| 1 | `RidBitmap`; `selection.venn` and `selection.compare` in Cypher; tests | **This change** |
| 2 | Saved handles (2.3): `SelectionHandle` documents, `selection.save`, `combine`, `get`, `members`, `drop`, `purge`; venn and compare accept handle names; lazy expiry | **Done** |
| 3 | HTTP routes (2.4) through `SelectionService`, bitmap exchange, OpenAPI documentation, and an HA test of saving and comparing through followers | **Done** |
| 4 | Modalities ACCEPT selections (2.5): `selection.rids` in SQL for full-text and geo, a `selection` option for vector (SQL and Cypher) and Cypher full-text | **Done** |
| 4b | A `selection` argument on the shared search services (so on `/api/v1/vector/{database}/*` and the MCP search tools, dense, sparse, full-text and both hybrid legs); MCP tools `selection_query` and `selection_update` over `SelectionService` | **Done** |
| 4c | gRPC (2.7): eight selection RPCs over `SelectionService`, and a `selection` field on `VectorSearch`, `HybridSearch` and `FullTextSearch` | **Done** |
| 5 | CSR fast path for expansion (2.8), with per-call fallback to the record walk | **Done** |
| 5b | Parallel expansion per selection (measured first), hub flags from F4, the fast path on followers (F3) | Later |

## 4. Governance hooks

Selections hold RIDs, not data, but a handle can still reveal that a record exists. Once F13 labels exist, every
procedure here must drop members and neighbours the session cannot see **before** computing regions, so counts never
leak hidden rows. Slice 2 handles are created with the creator's Operating Context and are readable only under the
same case.

## 5. Verification

- `RidBitmapTest`: every operation against a `HashSet<RID>` reference on random data across several buckets, including
  positions above 2^32; in-place against static operations; serialization round trip; iteration order.
- `SelectionProceduresTest`: Venn regions and counts in records and `via` modes; compare with `minShared` 1 and 2,
  `limit`, `memberCap` 0 and n, counts and members; RID strings and stale RIDs as input; refused inputs (one selection,
  more than 8, unknown option key, unknown edge type).
- `SelectionHandlesTest` (slice 2): save from members, a Cypher query and a SQL query (rows and lists in rows); query
  sources run read-only; compare and venn by handle name, mixed with lists; members with skip, limit and stale members;
  replace rules; drop; expiry refused everywhere, overwritten without replace, purged by `selection.purge` and by
  writes; combine for all four operators; survival across a reopen; the type created once in one schema frame (schema
  version +1) with both indexes; a foreign type of the same name refused; a blank name refused.
- `SelectionServiceTest` (slice 3, engine): the JSON contract of every operation, the bitmap round trip, and refusals
  surfacing as `IllegalArgumentException` with the procedure's message.
- `SelectionHttpIT` (server): the eight routes end to end, 400 for refusals, an empty body refused except by purge, 401
  without credentials, and the write routes committing.
- `SelectionRoutesHAIT` (ha-raft, 3 nodes): every write sent to a follower, the first save included; get and compare
  answer the same on every node; drop through a follower reaches every node; cluster consistency holds.
- `OpenApiSpecGenerationIT` and `Issue9849ServerRoutesIoThreadDispatchTest` list the eight routes.
- `SelectionModalitiesTest` (slice 4): full-text and geo in SQL push the selection into the index (`RID FILTER`, no
  `FETCH FROM RIDs`) and load only the matches inside it, measured net of the handle's own reads against an empty
  selection; `NOT IN`; vector in SQL and Cypher, alone and ANDed with `filter`; full-text in Cypher; a selection alone is
  still fetched by RID; an unknown selection is refused. `RidBitmapTest` covers `bucketIds` and the read-only
  `RidBitmapSet` view. The existing `Issue9909RidConditionPushdownTest`, `Issue11069FilteredFullTextSearchTest`,
  `Issue10685VectorQueryNodesPreFilterTest`, `Issue10921WhereIndexedPrefilterPlanTest` and `RidInScanOptimizationTest`
  pass unchanged.
- `SearchSelectionTest` (slice 4b, engine): dense, sparse, full-text and hybrid searches stay inside a selection; a
  selection that is unknown, blank or not a string is refused. `SelectionHttpIT.theSearchRoutesTakeASelection` covers
  `/api/v1/vector/{database}/fulltext`. `SelectionMcpIT` (mcp): both tools end to end, the full-text tool with a
  selection, the schema, insert and delete permission refusals, an unknown operation, and the tools' registration,
  admission, listing and `rag` profile.
- `SelectionCsrPathTest` (slice 5): with a view, venn and compare answer exactly what the record walk answered (OUT,
  BOTH, members themselves); venn loads zero records through the view where the record walk loads the members, and
  compare loads only the rows it returns; a view lacking a `via` edge type is not used; a transaction with uncommitted
  edges sees them (record walk); a stale member is skipped and a document member refused, as on the record walk.
- `SelectionGrpcIT` (slice 4c, grpcw): all eight RPCs end to end, the bitmap round trip as bytes, `FullTextSearch` with a
  selection, and refusals as `INVALID_ARGUMENT`.
- Compile the engine and run the neighbouring procedure tests (`AlgoCommonNeighborsTest`, the procedure-registry tests).
- Dependency: RoaringBitmap 1.3.0 added to `engine/pom.xml` and `ATTRIBUTIONS.md`. The jar ships no NOTICE file, and
  its "Apache 2" licence spelling is already in `ALLOWED_CLAUSES`.
