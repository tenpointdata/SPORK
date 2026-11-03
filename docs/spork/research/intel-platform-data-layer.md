# Intel-platform data-layer requirements for SPORK (Gotham/Foundry, Maltego, Kaseware)

Research date 2026-11-02. Legend: **[V]** verified against a cited primary source; **[V2]** verified against a
secondary/archived source; **[I]** inference. Context: `docs/spork/00-research-findings.md` (owner directives 1-3,
Recon access patterns) and `docs/spork/01-architecture.md` (2.1-2.6 data model, 5.3 case scoping, 7.2 endpoint map).

## 1. Palantir Gotham / Foundry

### 1.1 Ontology model
- **[V]** Foundry Ontology = object types, properties, link types, action types, functions, interfaces (polymorphic
  "shape" contracts), with granular security and governance for all changes
  (https://www.palantir.com/docs/foundry/ontology/overview/).
- **[V]** A link type is the schema of a relationship between two object types; each link type has **two sides, each
  with its own display and API name**, traversable from either side; self-links allowed; one link type per real-world
  relationship even between the same two types; many-to-many link types are backed by their own datasource; no
  cross-ontology links (https://www.palantir.com/docs/foundry/object-link-types/link-types-overview). Functions docs
  add 1:1, 1:N, N:M cardinalities (https://palantir.com/docs/foundry/functions/api-objects-links/).
- **[V]** Link properties are **not documented** on Foundry link-type pages or the Gotham create-link API. [I]
  Palantir models link attributes as intermediary object types. SPORK should keep real edge properties (confidence,
  provenance), a strict superset.
- **[V]** Gotham's backend is "RevDB, an object database backed by a dynamic ontology"; API groups: objects
  (get/create/search, property add/update/delete, list/create links), media, resolution, federated sources, maps,
  Target Workbench, tracks/observations
  (https://www.palantir.com/docs/gotham/api/v1/general/overview/introduction/).
- **[V2]** Gotham product sheet: Search Around over entities/events/documents, results reusable as the seed of new
  searches; helper apps Timeline, Timewheel, Heatmap, Histogram; Graph for relationships and flows; Map with layers
  and polygon search. Third-party: access controls down to individual properties
  (https://www.bdemerson.com/article/palantir-gotham).

### 1.2 Entity resolution ("object resolution")
- **[V]** Gotham resolution metadata: `canonicalObjectPrimaryKey`, `winnerObjectPrimaryKey` (minted on resolve; new
  writes attach to it so they can be split back out), `otherObjectPrimaryKeys` (flattened on recursive resolution).
  Unresolve yields separate objects each with its own metadata; each object's independent history is preserved
  (https://www.palantir.com/docs/gotham/api/v1/revdb-resources/resolution/resolution-basics). This matches SPORK's
  2.6 "nothing is destroyed" design; the winner-object trick (a fresh key that owns post-merge writes) is worth
  adopting.

### 1.3 Security
- **[V]** Markings are **mandatory** controls, roles **discretionary**; a user needs every marking on a resource;
  markings inherit through folders and data lineage; removal needs "Expand Access"; markings can hide existence
  (https://www.palantir.com/docs/foundry/security/markings/).
- **[V]** Row-level: restricted views filter which objects a user sees; object types inherit RV policies
  (https://www.palantir.com/docs/foundry/security/restricted-views/).
- **[V]** Property-level: a property can carry a marking different from its object
  (https://www.palantir.com/docs/foundry/security/property-security-markings/).
- **[V]** Gotham: creating a link requires a `security` block with `portionMarkings` and/or `permissions`, which may
  differ from the linked objects (https://www.palantir.com/docs/gotham/api/revdb-resources/objects/create-object-link).
  So **links are independently classified**.

### 1.4 Query/API shapes the front end issues
- **[V]** Object set algebra in Functions: `.filter()` (exactMatch, phrase/prefix/fuzzy, range, geo
  withinDistanceOf/withinPolygon/withinBoundingBox, link `.isPresent()`, array `.contains()`), `.union()
  .intersect() .subtract()`, per-link-type search-around (`searchAround<Link>()`, `pivotTo()`),
  `.nearestNeighbors()` (K<=100, dim<=2048), `.orderBy()/.orderByRelevance()`, `.take()`,
  `.groupBy().segmentBy()` with count/average/max/min/sum/cardinality; **10,000 bucket cap, topValues approximate
  beyond 1,000 distinct** (https://palantir.com/docs/foundry/functions/api-object-sets).
- **[V]** REST aggregate: `aggregation[] {type: count|max|min|sum|avg|approximateDistinct}`, `groupBy[] {type:
  exact|fixedWidth|ranges|duration, maxGroupCount default 10,000}`, `excludedItems`
  (https://www.palantir.com/docs/foundry/api/ontology-resources/objects/aggregate). Default speed-over-accuracy;
  `PREFER_ACCURACY` mode; response carries `AggregateResultAccuracy`
  (https://www.palantir.com/docs/foundry/object-backend/aggregation-considerations).
- **[V]** Load object set: `objectSet`, `orderBy`, `select`, `pageSize` (a page may be short on memory pressure),
  `pageToken`, `snapshot` for consistent paging; OSv1 caps at 10,000 objects
  (https://www.palantir.com/docs/foundry/api/ontologies-v2-resources/ontology-object-sets/load-object-set).
- **[V]** Limits: one search-around result <= 10M objects (OSv2) / 100,000 (OSv1); at most **3 chained
  search-arounds**; in-memory execution up to 100,000 objects, beyond that Spark
  (https://www.palantir.com/docs/foundry/ontologies/oss-limitations).
- **[V]** Gotham REST: `GET /objects/{pk}/links/{linkType}` pages at **max 1,000**; `POST
  /objects/types/{type}/search` with query tree `empty|eq|and|or|not|keyword|lt|gt|lte|gte|geoPointWithin`,
  **pageSize default and max 100**.
- **[V]** Actions: a single transaction changing properties/links of one or more objects, with parameters,
  submission criteria, side effects; user edits land in a writeback dataset
  (https://www.palantir.com/docs/foundry/action-types/overview/).
- **[I]** Takeaway: the front end never asks for "the graph"; it asks for *typed, bounded, set-valued* operations
  (a set, one link type, a page) and composes them client side (<= 3 hops). Interactive budgets are enforced by caps
  (100/1,000 per page, 10k buckets) rather than by timeouts.

## 2. Maltego

- **[V]** Transform protocol is XML. Request `MaltegoTransformRequestMessage` -> `Entities/Entity{Type, Genealogy,
  AdditionalFields/Field{Name}, Value, Weight}` plus `Limits{SoftLimit, HardLimit}` (sample 256). Response
  `Entities/Entity{Type, Value, Weight}` + `UIMessages`
  (https://docs.maltego.com/support/solutions/articles/15000015758-writing-transforms). Fields carry
  `MatchingRule=strict|loose`.
- **[V]** Link attributes via `link#`-prefixed fields: `maltego.link.direction` (`input-to-output` default,
  `output-to-input`, `bidirectional`), `.label`, `.color`, `.style`, `.thickness`; SDK `graph.add_link(is_reversed,
  thickness, style, color, label)`. Notes and bookmarks (6 colours) are per entity.
- **[V]** Result limit slider: presets Small/Balanced/Large; limits vary by transform; purpose is "prevent browser
  overload" (https://docs.maltego.com/support/solutions/articles/15000061694-setting-the-transform-result-limit-slider).
- **[V]** Weight: a per-entity integer in request and response; TRX default 100. [I] Treat as relevance/confidence
  0-100 used for ordering and pruning.
- **[V]** Collections: same-type unpinned entities collapse into collection nodes once they reach a minimum (default
  **25**) (https://docs.maltego.com/support/solutions/articles/15000010775-collections-tab). No official node cap
  found. [I] Design for a few thousand visible nodes, server-side collapsing of same-type fans.
- **[V]** Machines: `run(transformID, [slider], [ignoreSeenEntities], settings...)`, filters `type`, `value`,
  `property`, `degree`, `incoming`, `outgoing`, `age`, `bookmarked`, actions `bookmark`, `delete`, `deleteBranch`,
  `userFilter`, `setLayout`; perpetual machines re-run on a timer
  (https://docs.maltego.com/en/support/solutions/articles/15000019254-machine-syntax-reference).
- **[V]** Seeds bundle transforms + settings; iTDS is the self-hosted distribution server.
- **[I]** Data-layer translation: a transform = `f(entityType, value, fields, softLimit, hardLimit, settings) -> [
  {type, value, fields, weight, linkLabel, linkDirection} ]` running in well under a second; `ignoreSeenEntities`
  and `degree/incoming/outgoing` filters imply cheap **degree lookups** and **set difference against already-seen
  nodes**; perpetual machines imply **watch/diff** support.

## 3. Kaseware

Public information is thin: marketing pages only. **No product called "PATH" was found** on kaseware.com or in
third-party listings; confirm with the owner.
- **[V]** Link charts auto-generated from case/report/intel data, updated in real time, drill-through from any
  node/link to source; geospatial overlays via ESRI; Timeline View; one secure database for case management, intel
  and analytics; role-based access on every chart; export of charts (https://www.kaseware.com/link-analysis).
- **[V]** Modules: case, incident, evidence, document, records, task management, OSINT, dispatch, Search & Query, AI
  services, reports/dashboards, API; **chain of custody**; records management with audits; claims CJIS, SOC 2, ISO
  27001/27701 (https://www.kaseware.com/platform).
- **[V]** Intelligence page: adheres to **28 CFR Part 23** and FBI CJIS Security Policy; logs every system action in
  real time (https://www.kaseware.com/intelligence-agencies). Third-party: GraphQL-based API [V2].
- **[I]** 28 CFR Part 23 implies per-record criminal predicate / reasonable suspicion fields, **5-year review/purge
  dates**, dissemination logging and need-to-know/right-to-know checks; CJIS implies full audit of every access,
  MFA, encryption at rest.

## 4. Common-denominator "intel platform data-layer contract"

### 4.1 Ontology model
- **ObjectType** {apiName, title, primaryKeyProperty, titleProperty, interfaces[], prominent/hidden,
  defaultMarkings[], retentionClass}. SPORK vertex types in 2.1 fit; the abstract `Identifier` type is the
  equivalent of an interface.
- **PropertyType** {apiName, baseType (string, int, long, double, bool, date, timestamp, geopoint, geoshape,
  vector, binary/media ref, array, struct), searchable (exact/text/prefix/range/geo/knn), markings[], pii flag,
  display mask}. Property **values** carry optional provenance `{sourceId, collectedAt, assertedBy, confidence}`;
  cheapest is a parallel `_prov` struct for the few properties analysts can assert.
- **LinkType** {apiName, sideA{objectType, apiName, display}, sideB{...}, cardinality, directed, properties
  (confidence, weight, firstSeen, lastSeen, method, evidence[], state proposed|accepted|rejected, decisionId,
  markings[]), transformId}. Two-sided naming is worth copying so the UI can label both directions.
- **ObjectSet** = `base(type) | static(ids[]) | ref(savedId) | filter(set, where) | union | intersect | subtract |
  searchAround(set, linkType, side, limit) | knn(set, vector, k)`; immutable; cacheable by definition hash; nesting
  depth <= 3 search-arounds.
- **Workspace/Case** {tenantId, caseId, members, markings, saved sets, watchlists, notes}; **Decision**
  (merge/split/link/unlink with supersedes, 2.6); **AuditEntry** (hash-chained).

### 4.2 Top 15 read operations and interactive budgets (p95 at 290M records; [I] budgets derived from Palantir caps
and Maltego's sub-second transform expectation)

| # | Operation | Shape | Budget |
|---|---|---|---|
| R1 | Get objects by id (hydrate) | ids[] <= 2,000, projection | <= 50 ms / 100 ids |
| R2 | Expand 1 hop, per-link-type caps | object(s), linkTypes[], side, perTypeLimit (default 100), order by weight/recency, with `truncated` + `totalAtLeast` | <= 100 ms, independent of degree |
| R3 | Degree counts per link type | object(s) -> {linkType: count} | <= 10 ms |
| R4 | k-hop neighbourhood counts (k <= 3) | seed set, linkTypes, k, nodeCap | <= 300 ms for <= 10k reached nodes |
| R5 | Shortest path(s) | a, b, linkTypes, maxHops <= 4, k <= 5 | <= 200 ms |
| R6 | Common neighbours of 2-4 objects | ids[2..4], linkTypes | <= 50 ms |
| R7 | Object-set algebra | union/intersect/subtract of 2-4 sets up to 100k members each | <= 100 ms counts, <= 200 ms page |
| R8 | Faceted search with counts | filter tree, facets[] topN <= 1,000, total | <= 300 ms |
| R9 | Aggregation over a set | groupBy exact/ranges/fixedWidth/duration, metrics, maxGroups 10k | <= 300 ms |
| R10 | Timeline buckets over a set | set, timeProperty, bucket, optional segmentBy | <= 200 ms |
| R11 | Geo over a set | heatmap grid / geohash cells, bbox or polygon, within-distance | <= 300 ms |
| R12 | Subgraph induced by a set | set (<= 2,000 nodes), linkTypes, edge cap | <= 300 ms |
| R13 | Provenance / history lookup | object or link -> sources, assertions, decisions, as-of view | <= 50 ms |
| R14 | Saved set / watch re-evaluate + diff | setDef -> added/removed since cursor | <= 1 s (background OK) |
| R15 | Similarity / kNN over a set or corpus | vector or face template, k <= 100, filter set | <= 200 ms corpus, <= 100 ms set |

### 4.3 Write operations
Assert link (typed, with provenance, confidence, markings; idempotent on (a,b,type,source)); retract link (soft:
state=rejected); merge/split entities via Decision (winner key owns post-merge writes; validFrom/validTo on
memberships); annotate (note, bookmark colour, tag); mark (add/remove markings; audited, "expand access"
privilege); save set / watchlist; action = multi-object transaction with submission criteria + side effect (outbox
event). Every write appends an audit entry in the same transaction.

### 4.4 Security
Mandatory markings on object, property value and link (reader must hold all); discretionary case/tenant roles;
need-to-know purpose on every read (Recon's Operating Context, 428 without); hidden existence for unheld markings;
per-access audit (who, what ids, purpose, case) with hash chain; retention/purge review dates (28 CFR Part 23) and
legal hold flags that the query layer must respect.

### 4.5 Export
Subgraph (nodes + edges + provenance, markings filtered to the requester) to JSON, GraphML, GraphSON, CSV
(nodes.csv/edges.csv); object set to CSV/JSONL; redaction rules applied (no ad text/images in packages).

## 5. Mapping to SPORK capabilities

Verified in the tree (`engine/src/main/java/com/arcadedb/...`):
- **Edge storage**: `EdgeLinkedList`, super-node `StripedEdgeList` (threshold 4,096, 16 stripes), `EdgeBucketMask`;
  per-edge-type chains for supernodes are **designed, not implemented**
  (`docs/2026-10-07-8868-per-edge-type-adjacency-design.md`).
- **GAV/CSR** (`graph/olap/GraphAnalyticalView`, `CSRAdjacencyIndex`): one CSR per edge type, both directions,
  dense ids, column store, overlay for commits, persisted CSR, `countEdges` O(1), `countCommonNeighbors`,
  `isConnectedTo`, `getNeighborView`; `BoundedBreadthFirstSearch`; `ContractionHierarchy` for weighted shortest
  paths (`ShortestPathFinder`). Cypher operators `GAVExpandAll/GAVExpandInto/GAVFusedChainOperator`.
- **Procedures** (`query/opencypher/procedures`): `path.expandConfig/subgraphAll/subgraphNodes/spanningTree`;
  `algo.*` ~70 procs incl. `bfs`, `degree`, `commonNeighbors`, `jaccard`, `wcc`, `louvain`, `leiden`,
  `labelPropagation`, `articulationPoints`, `kShortestPaths`, `dijkstra`, `cchShortestPath`, `personalizedPageRank`,
  `kCore`, `knn`, `fastRP/node2vec`; `refactor.mergeNodes`; `db.index.fulltext.queryNodes`,
  `db.index.vector.queryNodes`.
- **SQL**: `MATCH`, `TRAVERSE`, `out/in/both`, `shortestPath`, `dijkstra`, `astar`, `cchShortestPath`; set
  functions `intersect`, `unionAll`, `difference`, `symmetricDifference`; `time_bucket`; geo functions on a geohash
  LSM index; `CountFromIndexStep`, `StreamingAggregationStep`, continuous aggregates.
- **Export**: `integration` exporter supports `jsonl`, `graphml`, `graphson`. CSV export absent.
- **Security**: per-database users/groups, per-type CRUD only; no row/property-level filtering, no markings, no
  audit subsystem.

| Op | Today | Gap / recommendation |
|---|---|---|
| R1 hydrate | `SELECT ... WHERE record_id IN [...]` on UNIQUE index; denormalised Record | fine; add server-side projection masks |
| R2 expand with per-type caps | `out('T')`/`path.expandConfig(limit)` is O(total degree) on supernodes; GAV `getNeighborIdsOfSlice` is O(k) but not recency-ordered | **New procedure** `graph.expandTyped(ids, {type: cap}, side, order)` reading the GAV slice when ready, else OLTP; **engine**: ship per-edge-type chains so OLTP walks are O(edges of type); store `lastSeen` on `CO_OCCURS` for recency order |
| R3 degree per type | `Vertex.countEdges(dir, types)`; GAV `countEdges` O(1) | **Precompute** per-type degree property; expose `graph.degrees(ids)` |
| R4 k-hop counts | `algo.bfs(maxDepth)` on GAV/CSR; `path.subgraphNodes` | **New procedure** returning per-depth and per-type counts with nodeCap and `truncated`; hub/stop exclusion |
| R5 shortest path | `shortestPath()`, `algo.kShortestPaths`, `algo.cchShortestPath` | Hop-bounded bidirectional BFS over GAV suffices; needs **type-filtered** variant and hard `maxHops` |
| R6 common neighbours of 2-4 | GAV `countCommonNeighbors(a,b)`; `algo.commonNeighbors` is 1-vs-all | **New procedure** `graph.commonNeighbors(ids[2..4], types)` as merge-join over sorted CSR slices |
| R7 set algebra | SQL set functions on in-memory collections | **New**: server-side `ObjectSet` service (plugin) materialising sets as sorted RID `long[]`/roaring bitmaps keyed by definition hash, with union/intersect/subtract and paged hydrate, LRU of recent sets. This is Recon compare: records mode = set algebra on record RIDs; entities mode = project each set to identifier keys then set algebra; sets mode = algebra on saved result sets; faces mode = `/face/cluster` over the union, then per-cluster intersect. All reduce to `project(set) -> sortedKeys`, `shared = intersection`, `distinct_i = set_i minus union(others)` |
| R8 faceted search | indexes + `StreamingAggregationStep` | Facet counts cheap only when index-resolvable; add `CountFromIndexStep` paths for facets; cap topN at 1,000 with `approximate` flag; BY ITEM indexes for multi-valued facets |
| R9 aggregation | SQL GROUP BY, continuous aggregates, GAV column store | Set-scoped aggregations over GAV column store when dense; cap groups 10k with `excludedItems` |
| R10 timeline buckets | `time_bucket()`, `(placeId, tsPosted)` composite | **Precompute** per-identifier day/week histograms (`dayCells`); set-scoped via `time_bucket` over hydrated `tsPosted` |
| R11 geo over set | geohash LSM, `geo.dWithin` | **Precompute** `geoCell` (geohash-7) per record and `topCells` per identifier; heatmap = GROUP BY geoCell; metre radius and geo kNN now exist in the geo workstream |
| R12 induced subgraph | `path.subgraphAll`; GAV `isConnectedTo` | Add set-input variant: edges among a given id set via merge-join on CSR slices; apply edge cap |
| R13 provenance | `ResolutionDecision`, `MEMBER_OF validFrom/validTo`, soft-edge `evidence[]` | Add `graph.history(id, asOf)`; per-asserted-property provenance struct |
| R14 watch/diff | WebSocket change feed, after-commit callbacks, `OutboxEvent` | **New**: persisted set snapshots so diff = symmetric difference; schedule in plugin |
| R15 kNN | `LSM_VECTOR`, `vector.fuse`, allow-list prefilter <= 20% | Set-scoped kNN maps onto allow-list prefilter; faces via ROC tier |

Cross-cutting engine gaps: (1) per-object/property markings and need-to-know filtering: none in engine; do it in
plugin query builders now and as row/property filters in `SelectExecutor`/`FetchFromTypeExecutionStep` later; (2)
audit: plugin, hash-chained, same transaction; (3) CSV export and a markings-aware subgraph exporter; (4) every
read must return `truncated`/`totalAtLeast` rather than time out; (5) GAV memory: a database-wide GAV at 3x is too
large (26.6 GB), so R2-R6/R12 should run on set-scoped GAVs built on the serving node, or on OLTP edge lists once
per-type chains land. Worth measuring: a persistent GAV covering only registry edge types (`CO_OCCURS`,
`ACCEPTED_LINK`, `MEMBER_OF`), which is far smaller than the full record graph.

## 6. Open questions for the owner
1. Is "PATH" a Kaseware product/module name seen in a demo or contract? Nothing public uses it.
2. Marking model: flat labels with intersection semantics (Foundry/Gotham portion markings) or classification +
   compartments?
3. Should links carry their own markings independently of endpoints (Gotham does)?
4. Hard caps to adopt: per-type expand 100, page 1,000, facet topN 1,000, groups 10k, chained hops 3.
5. 28 CFR Part 23 applicability (purge/review dates) for the shared ad corpus vs case-scoped intel.
