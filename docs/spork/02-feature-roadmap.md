# SPORK feature roadmap: parity with modern databases

Status: research draft for owner review, 2026-11-02.
Inputs: `00-research-findings.md`, `01-architecture.md`, six parallel research passes (graph databases, search engines,
vector/multimodal databases, geo/time/OLAP, security/governance/ops, entity resolution and intel platforms), each
checked against the SPORK tree on `main` (ArcadeDB 26.11.1-SNAPSHOT) with file evidence, plus the four in-flight
worktrees under `.claude/worktrees/`.

Purpose: `01-architecture.md` answers "how does Recon move onto SPORK with minimal engine change". This document
answers a different question: **what features does SPORK need to stand level with, or ahead of, the best modern
databases in each modality, judged by SPORK's own goals** (retrieval speed first, set-oriented retrieval, an
identity-centric intel platform on top, governance in the database). It is a ranked feature list, not a schedule;
section 7 shows how it slots into the existing plan.

Goal tags used throughout:

| Tag | Goal |
|---|---|
| **R** | Retrieval speed: p50/p99 read latency and throughput (owner directive 1) |
| **S** | Set-oriented retrieval: every modality answers "over this selection", compare 2 to 4 selections (directive 2) |
| **I** | Identity layer for the Gotham/Maltego/Kaseware-style platform: expansion, transforms, resolution, provenance (directive 3) |
| **M** | Multimodal recall: text, partial text, geo, vector, image, face |
| **G** | Governance: Operating Context, audit, ACLs, retention, deletion |
| **O** | Operations: replication, backup, observability |

Effort: **S** up to 2 engineer-weeks (ew), **M** 2 to 5, **L** 5 to 10, **XL** over 10.

---

## Summary

**SPORK is already broad.** It ships SQL, openCypher (Cypher 25 grammar), Gremlin, GraphQL and Mongo, Redis,
Postgres, Bolt and gRPC wire protocols. It has about 70 graph algorithms (Leiden, Betweenness, FastRP, GraphSAGE,
contraction hierarchies), LSM full-text, geo, dense and sparse vector indexes, time-series types, materialized views
and continuous aggregates. Breadth is not the problem.

**The gaps are depth and speed in the places SPORK's goals stress most:**

1. **No first-class selection.** Nothing persists a set of record ids, combines sets cheaply or compares 2 to 4 of
   them. The set functions (`SQLFunctionIntersect`, `SQLFunctionDifference`) work on materialized Java collections,
   and `RidSet` is an uncompressed bitmap sized to the highest offset. Recon's compare feature, the heart of
   directive 2, has no engine primitive.
2. **Expansion is not transform-grade.** `path.expand` has one global `limit`: no per-edge-type caps, no hub
   suppression, no `truncated` or `totalAtLeast`, no provenance. Every Maltego-style click depends on this.
3. **Text search cannot find substrings or rank top-K cheaply.** `LIKE '%x%'`, leading wildcards and regex are full
   scans. BM25 materializes every candidate before scoring. There are no positions, so phrase search is term
   intersection, and no highlighting. Analyzer chains cannot be configured, so there is no n-gram, phonetic or
   obfuscated-number normalization. The corpus is obfuscated ad text, so this costs both recall and p99.
4. **Governance lives outside the engine.** There is no row or label security, no audit subsystem and no CDC with
   replay. Scheduled backups are plaintext. The audit, ACL and outbox work the intel platform needs is designed in
   the plugin today. Label security must be settled **before** caches and the CSR read path harden around its
   absence.
5. **Image identity has no cheap modality.** Corpus-wide image vectors do not fit under replication
   (`01-architecture.md` 4). PDQ perceptual hashes do fit: 32 B per image, about 43 GB at 1.35B images. They are
   BSD-licensed, need no EULA, and link reposted and cropped photos across phones and sites.

**Where SPORK is already ahead of the field** (section 1): a transactionally synced CSR adjacency used by the
ordinary Cypher planner; contraction-hierarchy shortest paths; vector search inside Cypher; set-filtered full-text
(`RIDFilter`) and vector allow-list search; and a Block-Max MaxScore engine (built for sparse vectors) that full-text
can reuse.

**Recommendation.** Tier 1 (section 3.1) has 18 items, about 60 ew, of which about 14 ew are already budgeted in
`01-architecture.md` (E7, E8, E9) and about 5 ew are already written in worktrees. The highest-value new items are
**F1 selections and compare**, **F4 capped expansion**, **F7 trigram substring index**, **F11 PDQ image identity**
and **F13 label-security design**.

---

## 1. Where SPORK is already at or ahead of the field

| Capability | SPORK evidence | Field comparison |
|---|---|---|
| Read-optimized CSR adjacency, synced on commit and used by the normal Cypher planner | `engine/.../graph/olap/GraphAnalyticalView.java`; operators `GAVExpandAll`, `GAVExpandInto`, `GAVFusedChainOperator` | Neo4j GDS and Neptune Analytics use separate snapshot projections; the planner does not use them |
| Contraction hierarchies with bidirectional Dijkstra | `graph/olap/ContractionHierarchy.java`, `ShortestPathFinder.java`, `AlgoCCHShortestPath` | No surveyed graph vendor ships this |
| Vector search inside Cypher | `Cypher25Parser.g4` SEARCH clause, `VectorSearchClauseProcedure` | Neo4j `SEARCH` is still preview in 2026.01 |
| Set-filtered full-text | `RIDFilter` in `FullTextSearch.search(...)` (lines 172 to 208), applied before scoring | Equivalent to an ES filter context |
| Vector extras | MMR (`SQLFunctionVectorMmr`), grouping (`GroupedTopUpPlanner`), threshold search (`maxDistance`), exact rerank, `vector.recommend` and `vector.discover`, RRF/DBSF/LINEAR fusion, sparse vectors with Block-Max MaxScore | At par with Qdrant and Milvus feature lists |
| Algorithm breadth | About 70 classes in `query/opencypher/procedures/algo/`, callable from Cypher with no catalog step | Comparable to GDS and Memgraph MAGE |
| Quantified path patterns and path modes, plan cache | `ast/QuantifiedPathPattern.java`, `PathPattern.java`, `CypherPlanCache.java` | At par with Neo4j Cypher 25 |
| Time-series engine | Gorilla/delta-of-delta/Simple8b codecs, SIMD, PromQL, downsampling, retention (`engine/timeseries/*`) | At par with TimescaleDB for metrics; metric-shaped, not record-shaped |
| Tracing and metrics | `tracing/.../TracingPlugin.java`, `metrics/.../OtlpMetricsPlugin.java`, Micrometer | At par |
| Query timeouts and admission gate | `COMMAND_TIMEOUT`, `QUERY_QUEUE_*` in `GlobalConfiguration` | Partial parity; no per-tenant resource groups |

These are strengths to protect. Several Tier 1 items exist to make them usable at SPORK's scale (F3 puts the CSR on
followers; F5 points the algorithms at selections).

---

## 2. Work already in flight

Four worktrees under `.claude/worktrees/` already cover roadmap items. None is merged into `main`.

| Worktree | What it contains | Roadmap items |
|---|---|---|
| `geo-knn-b2da7a` | `SQLFunctionGeoDWithinMeters`, `SQLFunctionGeoNearest` + `GeoNearestSearch` (kNN over `LSMTreeGeoIndex`), `SQLFunctionGeoHash`/`GeoHashCover`, Cypher `NodeGeoIndexSeek` + `GeoSeekPredicate`, cost-model changes | F9 (= `01-architecture` E9) |
| `partial-text-search-2551dc` | `AutomatonTermSeeker` (automaton-based fuzzy/wildcard term seeking), `fulltext/analysis/` package, per-field analyzer tests, partial-match benchmark | Part of F6, part of N4 |
| `vector-parallel-search-f7403e` | `VectorSearchPool`, `NeighborMerge` (k-way merge), `VectorRebuildPermits`, parallel bucket search tests and benchmark | F10 (= E7) |
| `read-bench-63f0a8` | `benchmarks/` only | Measurement harness for every R item |

Finishing these four worktrees is the cheapest progress available.

---

## 3. The feature list

### 3.1 Tier 1: adopt now

Ordered by value to SPORK's goals. "Arch" names the matching `01-architecture.md` item, if any.

| ID | Feature | Gap today (evidence) | Benchmark products | Goals | Effort | Arch |
|---|---|---|---|---|---|---|
| **F1** | **Selections and compare.** A persisted, TTL'd selection handle backed by a roaring bitmap per bucket (RoaringBitmap, Apache 2.0; `Roaring64Bitmap` past 32-bit positions). Set operations `and`/`or`/`andNot`/`cardinality`. A `compare(handles[2..4], mode)` operator returning shared and distinct identifiers, overlaps and counts for the records, entities, sets and faces modes | Set functions work on in-memory collections (`function/sql/coll/SQLFunctionIntersect.java`); `RidSet` is uncompressed and sized to max offset (`query/sql/executor/RidSet.java`); materialized views are type-level only | Palantir Object Set Service; Druid and Pinot roaring indexes; TigerGraph SetAccum | S, R, I | L (6 ew) | New. Replaces part of the plugin's `{ids[]}` plumbing. Slice 1 done; design in `03-f1-selections-design.md` |
| **F2** | **Every modality takes and returns a selection.** Full-text already takes `RIDFilter`, vector takes an allow-list; add geo, exact-index and graph inputs and a uniform "emit as selection" output. Add set-vs-set vector primitives (cross-kNN, similarity matrix between 2 to 4 selections) on the existing exact allow-list path | Mixed: `FullTextSearch` and `vector.neighbors` accept filters, geo and algorithms do not | Qdrant filter + recommend; ES filter context | S, R | M (4 ew) | New |
| **F3** | **CSR view on Raft followers.** Prove `GraphAnalyticalView` stays fresh and is served on followers under `read_your_writes`, fix what fails, and pre-build views for identifier edge types | CSR persistence is leader-only by design (`GraphAnalyticalView.persistCsrIfPossible`, around line 4100, `isFollower()` early return); refresh is commit-listener driven, and listeners do not fire on followers | Memgraph, Neptune Analytics | R | S to M (2 ew) | New. Without it, reads routed to n2 to n4 lose SPORK's fastest traversal path. **Done** (steps 1 and 2, `09-f3-csr-on-followers.md`): it also fixed a stale-read bug, a restarted follower's view serving the graph as of its restart. Incremental follower updates via leader-shipped graph deltas remain |
| **F4** | **Transform-grade expansion.** `expand(seedSelection, hops, linkTypes, perTypeCap, perNodeCap, excludeHubs, asOf)` returning nodes, edges, per-hop counts, `truncated`/`totalAtLeast` and provenance columns; hub/stop-value suppression (agency phones, stock images) | `PathExpandConfig.java` (lines 91 to 97) has one global `limit`; `GRAPH_SUPERNODE_THRESHOLD` only stripes storage | Maltego transforms; Palantir Search Around limits; Memgraph BFS filter lambdas | I, R | M (4 ew) | Hub/stop flags are designed in arch 2.4; this moves them into the engine |
| **F5** | **Graph algorithms over a selection.** Let `algo.*` take a node selection or a Cypher projection, not only label and edge-type filters; move the hottest set-scoped ones (WCC under a `linkOn` profile, degree, articulation points, label propagation, betweenness with sampling) onto the CSR | `AbstractAlgoProcedure.loadGraph(db, nodeLabels, relTypes)` filters by type only; 7 of about 70 algorithms read the CSR | Neo4j GDS Cypher projections | S, I, R | M (3 ew) | Arch runs these over hydrated sets in the plugin; this makes them native |
| **F6** | **Analysis pipeline.** Honor per-field analyzers; declarative analyzer chains (Lucene `CustomAnalyzer`: tokenizer + filters by name); an obfuscated-number char filter ("five5five-l23" to 555123, homoglyphs, emoji digits); edge n-gram; phonetic filters (commons-codec: Double Metaphone, Beider-Morse, Daitch-Mokotoff); nori and smartcn for Korean and Chinese | `fieldAnalyzers` is stored but never read by index code (only `CypherIndexOptions.java:289`); `LSMTreeFullTextIndex.createAnalyzer` takes a no-arg class name; no phonetic code anywhere | Elasticsearch/OpenSearch analysis chains; no product ships obfuscated-number normalization (a differentiator) | M, R | M (3 ew; partly in `partial-text-search` worktree) | Pulls E11 (Year 2) forward and makes it cheap |
| **F7** | **Trigram index for substring, wildcard and regex.** Extract trigrams from the pattern, intersect postings, verify only the candidates (pg_trgm/Zoekt design); used by `LIKE '%x%'`, leading wildcards and regex | `collectWildcardMatches`/`collectRegexpMatches` scan the term dictionary; `LikeOperator` never uses an index | PostgreSQL pg_trgm; Zoekt; ES `wildcard` field | M, R | M (4 ew) | Generalizes the arch's hand-built `grams3[] BY ITEM` lists |
| **F8** | **BM25 top-K with Block-Max MaxScore**, by moving text postings onto the existing paginated block-max engine with term frequency or impact as the weight | `FullTextQueryExecutor` collects every candidate, then scores (`scoreCandidatesBM25`); `index/sparsevector/BmwScorer.java`, `PaginatedSegmentFormat.java` already implement block-max | Lucene, ES, Tantivy BlockWAND, Vespa weakAnd | R | M (4 ew) | = E8; reuse makes it cheaper and lowers risk |
| **F9** | **Geo in meters, kNN, Cypher geo index seek.** Finish and merge the worktree | Degrees-only `dWithin`, no kNN, Cypher point functions ignore the index | PostGIS geography and `<->`; Neo4j point index | M, R | S (1.5 ew remaining) | = E9 |
| **F10** | **Parallel vector bucket search** on a dedicated pool. Finish and merge the worktree | Sequential per-bucket loop in `SQLFunctionVectorNeighbors` | Milvus, ES, Vespa (segment-parallel) | R | S (1 ew remaining) | = E7 |
| **F11** | **PDQ image identity.** A native 256-bit property type, PDQ hashing in-process (ThreatExchange Java, BSD), a Hamming index by multi-index hashing (16 x 16-bit slots on LSM keys, exact at distance 31 or less), `SAME_IMAGE_AS` edges, and dedup before ROC passes | Hamming exists only over BINARY quantization codes (`BinaryCodeCache.java`); no bit-vector property | Meta ThreatExchange/HMA; FAISS binary indexes | M, I | M (4 ew) | New. Fills the image-identity hole left by "no corpus-wide image vectors" (arch 4) |
| **F12** | **Hash-chained audit and outbox in the engine.** A commit-time hook appends `{prevHash, actor, OC, touched ids}` in the same transaction; Raft replicates it in the same entry; periodic external digests; a verify procedure; the same log, with offsets, is the CDC stream with replay | No audit subsystem; the WebSocket feed drops events when its queue fills (`DatabaseEventWatcherThread.java:69`), has no offsets, and fires only where the tx ran | SQL Server Ledger, immudb; Neo4j CDC; CockroachDB changefeeds | G, I, O | L (5 ew) | Arch 5.2 builds this in the plugin; an engine hook makes it fail-closed and gives followers a feed (subsumes E15) |
| **F13** | **Label-security design (enforcement in N1).** A small integer label per record (header or hidden property); a per-session allowed-label bitset computed from clearance, case ACL and purpose and cached per `{user, caseRef, purpose}`; enforced in the executor, index lookups and CSR, never as a post-filter (that breaks LIMIT and COUNT and leaks through counts); fail closed on unknown labels | Per-type CRUD only (`SecurityDatabaseUser`); read hooks exist (`LocalDatabase.invokeAfterReadEvents`, `BucketIterator.java:221`) but no model | Oracle Label Security; Palantir markings and purpose-based access; Neo4j PBAC (whose DENY fails open) | G, I | S design now (1 ew) | Arch E13 (Year 2). The design must come first because F1, F3 and F4 bake in read paths |
| **F14** | **Encrypted scheduled backups and encryption at rest.** Reuse `BackupSettings.encryptionKey` in scheduled backups; a server setting for `DefaultDataEncryption` with a KMS-wrapped key | `AutoBackupConfig`/`BackupTask` have no encryption option (verified: no `encrypt` in `server/.../backup/`); `DefaultDataEncryption` is not wired to configuration | CockroachDB EAR and CMEK; Spanner CMEK | G, O | S (2 ew) | Arch works around it with an off-node encrypted mount; this removes the largest compliance exposure |
| **F15** | **Versioned, explainable resolution.** An append-only `ResolutionDecision` (rule, version, per-feature evidence, conflicts, actor); `why(a, b)` and `how(identity)`; merge/split/undo delete and recreate `MEMBER_OF` edges of a versioned type (N21) instead of ending them with validFrom/validTo, so nothing is lost; the as-of view is `history.edges(identity, t)` | `refactor.mergeNodes` is destructive (`RefactorMergeNodes.java`); arch 2.6 designs the model but nothing serves `why`/`how` | Senzing why/how; Palantir writeback | I, G | M (4 ew) | Implements arch 2.6 as engine procedures |
| **F16** | **Incremental aggregates.** Extend incremental materialized views to single-type `GROUP BY` with COUNT/SUM/MIN/MAX applied as deltas on commit (identifier counters: records per phone, first/last seen); let continuous aggregates take document types with a datetime column (day/week buckets, DOW/hour, 30d vs 90d baselines) | `MaterializedViewQueryClassifier.isSimple` rejects GROUP BY and aggregates; `ContinuousAggregateBuilder` refuses non-TimeSeries sources (verified) | ClickHouse MV + AggregatingMergeTree; pg_ivm; TimescaleDB continuous aggregates | R, S | L (6 ew) | Arch keeps these counters by hand in the plugin and `ops` |
| **F17** | **H3 cells as a first-class index.** `h3.cell(lat, lon, res)`, `gridDisk`, a composite key `(h3, dayBucket)` maintained on write; convergence, DBSCAN at 25 km, grid novelty and Markov locale states become cell lookups plus an exact haversine check | No H3 or S2; the index is Lucene `GeohashPrefixTree` (`LSMTreeGeoIndex.java:170`) | Uber H3; ES `geohex_grid`; BigQuery GIS | R, S, M | M (4 ew) | Arch uses geohash4 in `Presence`; H3 cells are equal-area and give O(1) neighbourhoods. h3-java is JNI (decision D2) |
| **F18** | **Operating Context on spans.** Tag tracing spans and metrics with caseRef and purpose; never ad text | Tracing exists without OC | OpenTelemetry DB semantic conventions | G, O | S (0.5 ew) | Complements arch 5.1 |

Tier 1 total: about 60 ew (14 ew of it already budgeted as E7, E8, E9; about 5 ew already written in worktrees).

### 3.2 Tier 2: next

| ID | Feature | Gap today | Benchmark | Goals | Effort | Arch |
|---|---|---|---|---|---|---|
| N1 | Label-security enforcement (F13 built), plus property-level redaction for ad text and titles | Missing | Oracle VPD, Neo4j PBAC | G, I | L (8 ew) | Replaces E13 |
| N2 | Watchlists by reverse search: embed Lucene `monitor` + `memory` (Apache 2.0) so new commits are matched against stored queries; alerts on the F12 stream | Missing; raw hooks only (`RecordEventsRegistry`, `MaterializedViewChangeListener`) | ES/OpenSearch percolator, Linkurious alerts | I, M | M (4 ew) | Arch runs watch scans in Recon over leases |
| N3 | Positions and offsets in postings: phrase, proximity and intervals queries, highlighting and snippets. Or adopt the Lucene sidecar (decision D1) | `FullTextQueryExecutor.java:633`: order cannot be checked without positions | Lucene intervals, UnifiedHighlighter, ParadeDB `snippet` | M, I | L (7 ew) | New |
| N4 | Fuzzy via Levenshtein automata; finish `AutomatonTermSeeker` | Edit-distance DP over every term after the prefix (`collectFuzzyMatches`) | Lucene, Tantivy, Typesense | M, R | M (2 ew remaining; in worktree) | New |
| N5 | Hot-reloadable synonym and slang lexicon (emoji codes, euphemisms) | Only possible as a hard-coded analyzer | ES Synonyms API | M | M (3 ew) | New |
| N6 | Facet counts from columnar or bitmap facets (city, phone, site, service) | GROUP BY after materialization | ES aggregations, Typesense facets | S, R, I | M (4 ew) | Builds on F1 bitmaps |
| N7 | Transform SPI: a declared contract (input/output object types, provenance stamping, rate limits, version) over existing procedures and plugins | Plumbing exists (`ProcedureRegistry`, `CypherProcedureRegistry`, `ServerPlugin`), no contract | Maltego TDS | I | M (3 ew) | New |
| N8 | Action types: one governed write path (validation, OCC revision, audit, side effects) for dispositions, link accept/reject and merges | BEFORE/AFTER triggers and constraints only | Palantir action types | I, G | M (3 ew) | Arch board OCC in plugin |
| N9 | Incremental union-find on ingest and dynamic clustering profiles (`linkOn`) | `AlgoWCC` is batch only | Senzing, Quantexa | I, R | L (5 ew) | Arch WS6 "incremental components" in plugin |
| N10 | Probabilistic match scoring (Fellegi-Sunter m/u weights, EM-trained; algorithm ported from Splink, MIT) plus a blocking-key API | Missing | Splink, Senzing | I | M (4 ew) | New |
| N11 | Trajectory functions over sorted per-identifier sequences: `geo.legs`, `geo.track`, `geo.impossibleTravel`; DBSCAN on H3 neighbourhoods | Window functions exist (`SQLFunctionLag`, `Lead`); no DBSCAN | MobilityDB, PostGIS `ST_ClusterDBSCAN` | S, M | M (4 ew) | Arch reproduces Recon's `engine.ts` in the plugin |
| N12 | Mergeable sketches (Apache DataSketches theta and HLL) inside aggregates; theta intersection and A-not-B for instant corpus-scale overlap estimates before exact F1 bitmaps | Missing | DataSketches, ClickHouse `uniqCombined` | R, S | M (3 ew) | New |
| N13 | Vector index scale: partitioned builds merged by jvector N:1 compaction, mutable inserts instead of full rebuilds, off-heap location map, NVQ (all already in jvector 4.0.2 but unwired) | Full per-bucket rebuild; about 1.1 KB heap per node to build; location map on heap | Qdrant HNSW healing, pgvectorscale StreamingDiskANN, Milvus | R, M | L (8 ew) | Needed only if corpus-wide image or text vectors return (arch 4 dropped them) |
| N14 | ACORN-style filtered traversal for the 1 to 20% selectivity band | Bits filter rejects after a pop; the exact fallback explodes at 1B | Qdrant 1.16 `acorn`, Weaviate | R, S | M (4 ew) | New |
| N15 | Crypto-shredding: per-subject or per-case keys in a Raft-replicated key store, wrapped by KMS, so deletion reaches every replica, snapshot and backup | One database-wide key (`BinarySerializer.java:1961`) | KMS-backed pattern (Spanner, CockroachDB) | G | M (5 ew) | Arch WS7 has monthly biometric keys in the plugin; engine support generalizes it |
| N16 | Per-edge-type adjacency for dense nodes (implement the proposed design in `docs/2026-10-07-8868-per-edge-type-adjacency-design.md`) | Type-filtered hop is O(total degree) | Neo4j relationship groups | R | L (6 ew) | New |
| N17 | GQL path selectors (`ANY SHORTEST`, `SHORTEST k [GROUPS]`): the grammar parses them, the AST builder refuses them | `CypherASTBuilder.java:2077-2080` | Neo4j Cypher 25 | I | M (3 ew) | New |
| N18 | Retention and legal holds as engine policy (TTL per type or label, holds that block delete) | TimeSeries retention only | Foundry, Oracle | G | M (3 ew) | Arch WS7 DeletionOrder saga |
| N19 | OIDC/SAML/LDAP single sign-on | API tokens and lockout only (`ApiTokenConfiguration`, `ServerSecurity`) | PostgreSQL 18 OAuth, Neo4j | G, O | M (3 ew) | Needed for hosted mode |
| N20 | Point-in-time recovery (WAL archiving to the object store) | Full backups only | CockroachDB, Spanner PITR | O | L (6 ew) | Arch W3d covers full backups |
| N21 | **Native system versioning (transaction time), superseding the `Assertion`-node pattern** per the owner's 2026-11-02 goal of versions "managed natively rather than as an exploded mess of nodes". `ALTER TYPE X VERSIONED true`: the current record stays in place with an engine-owned `_txFrom` stamp; superseded and deleted versions go to an internal `_VersionHistory` store in the same transaction, which clients cannot write. SQL/Cypher `history.of(rid)`, `history.asOf(rid, t)`, `history.edges(vertex, t, dir, type)`, `history.purge(rid)`; Java `RecordVersioning.of(db)`. Follow-ups: `history.scan(type, t)` for type-level as-of; seed the stamp counter from the store on open (failover clock skew); optional delta images if the storage budget needs them; markings (F13/N1) applied to the history store | **Implemented, uncommitted**: worktree `.claude/worktrees/native-versioning`, branch `worktree-native-versioning`, design in its `docs/spork/08-native-versioning-design.md` (284 tests green as reported by that session, not re-run here) | XTDB 2, Datomic, SQL:2011 system-versioned tables | I, G | done; follow-ups S to M | Pairs with F15 |
| N22 | Subgraph export (GraphML, JSON, evidence package) over a selection | `PathSubgraphAll`; GraphML/GraphSON live in the gremlin module | Linkurious, Neo4j | I | S (1.5 ew) | New |
| N23 | Image embeddings for case-linked images only: SigLIP 2 (Apache 2.0) computed outside the DB, a small per-case vector index | N/A | Milvus, Weaviate multimodal | M | S (1 ew in DB) | Arch allows "case-linked images only" |

### 3.3 Tier 3: later

| Feature | Why later |
|---|---|
| Worst-case optimal and factorized joins (Kuzu, now LadybugDB) | Cyclic patterns are rare in Recon's reads; F1 bitmaps cover common-neighbour counts |
| Parallel expand and aggregate runtime (Neo4j parallel runtime) | Point reads gain more from F3 than from parallelism |
| Multi-vector per record (MUVERA, MaxSim) | One Face vertex per face fits the identity graph better |
| GPU index build (CAGRA, cuVS) | Needs NVIDIA hardware and a different graph format; only with N13 |
| IVF disk indexes (ES DiskBBQ, SPFresh) | Only if N13 proves insufficient |
| General columnar storage and vectorized execution (DuckDB, ClickHouse) | XL; F16 and N12 cover the fixed analytics |
| Star-tree pre-aggregation (Pinot) | Pinot itself says ad-hoc queries gain nothing |
| Cross-encoder reranking | Off the latency path; Recon can rerank |
| `search_after` with point-in-time paging | Keyset cursors in the plugin suffice for now |
| SQL/PGQ `GRAPH_TABLE` | Postgres-wire users can use Cypher |
| Per-tenant resource groups | Matters only once hosting is multi-tenant |
| Lineage (Foundry-style) | Falls out of F12 (who did what) plus N21's `history.*` (what each record was, when) |

### 3.4 Skip

| Feature | Reason |
|---|---|
| Composite databases / Fabric (`USE`) | Full replication puts everything on one node |
| Distributed MPP query (TigerGraph, Spanner) | The owner chose replication over sharding |
| Object-storage-first vector tiers (Turbopuffer) | Conflicts with local-NVMe full replication |
| Dolt-style data branches | XL; case sandboxes can be labels |
| `semantic_text` auto-embedding on write | Recon embeds upstream; keeps model code out of the engine |
| Online schema change beyond today's | Small cluster with rare schema changes |
| S2 cells | Only if H3's JNI packaging fails (D2) |

---

## 4. Area notes

### 4.1 Graph and link analysis
SPORK leads on the read path (CSR inside the planner, contraction hierarchies) but leads only on the leader: F3 is
the precondition for routing link-chart reads to followers. F4 and F5 turn the algorithm library into Maltego-style
gestures. Kuzu was archived in October 2025 (continued as LadybugDB), so it is a source of ideas, not a benchmark.
Sources: Neo4j Cypher 25 shortest paths (neo4j.com/docs/cypher-manual/25/patterns/reference/shortest-paths/), GDS
Cypher projection (neo4j.com/docs/graph-data-science/current/management-ops/projections/graph-project-cypher-projection/),
TigerGraph accumulators (docs.tigergraph.com/gsql-ref/current/querying/accumulators), Neptune Analytics algorithms
(docs.aws.amazon.com/neptune-analytics/latest/userguide/algorithms.html), Kuzu CIDR'23 paper.

### 4.2 Full-text and partial text
The order is F6 (cheap, unlocks n-gram, phonetic, multilingual and obfuscation recall at once), then F7 (largest p99
win for substring hunting), then F8 (top-K at 290M to 900M documents), then N2 (watchlists) and N3 (phrase and
highlighting). ICU4J may now be under the Unicode-3.0 license, which is not on the allow-list; use `CJKBigramFilter`
(already on the classpath), nori and smartcn (Apache 2.0) instead. Sources: Lucene 10 highlights
(elastic.co/search-labs/blog/apache-lucene-10-release-highlights), pg_trgm (postgresql.org/docs/current/pgtrgm.html),
ES percolator and synonyms API docs.

### 4.3 Vector, image and face
jvector 4.0.2 is still the latest release, and SPORK uses only part of it (FusedPQ, the sequential writer, PQ). NVQ,
the parallel writer and N:1 compaction are already in the jar. At 1.35B 512-d vectors, float32 is 2.7 TB and a
single-graph build needs about 1.5 TB of heap, so corpus-wide image vectors need N13 first, which is why arch 4
dropped them and why F11 (PDQ) is the image modality now. No permissively licensed face-recognition model matches
ArcFace-class accuracy (InsightFace weights are non-commercial, and AdaFace weights were trained on research-only data),
so ROC stays the face engine of record. Sources: Qdrant 1.15/1.16 release notes (HNSW healing, ACORN), Milvus
IVF_RABITQ (milvus.io/docs/ivf-rabitq.md), ThreatExchange PDQ (github.com/facebook/ThreatExchange/tree/main/pdq),
SigLIP 2 model card.

### 4.4 Geo, time and analytics
The time-series engine is strong but metric-shaped (TAG plus numeric fields, five pushdown aggregates), so it can hold
a derived identifier event stream but not multi-valued records. Continuous aggregates and incremental views exist and
need only the F16 widening. Sources: PostGIS KNN and `ST_ClusterDBSCAN`, h3-java (github.com/uber/h3-java),
RoaringBitmap (github.com/RoaringBitmap/RoaringBitmap), DataSketches theta set operations, ClickHouse materialized
views, TimescaleDB continuous aggregates.

### 4.5 Identity layer (what the intel platform calls)
A minimal native API, exposed as plugin HTTP routes and as Cypher procedures. Every call is tenant- and case-scoped,
audited (F12), label-checked (F13/N1), and takes `limit`, `perTypeCap` and `asOf` (an `asOf` read goes through N21's
`history.asOf` and `history.edges`):

| Group | Operations | Built from |
|---|---|---|
| Resolution | `resolve(keys[] or recordIds[])`, `why(a, b)`, `how(identityId)` | F15, N9, N10 |
| Expansion | `expand(seedSelection, hops, linkTypes, perTypeCap, excludeHubs)` | F4, F3 |
| Selections | `selection.create(query or ids)` (TTL handle), `selection.op(and/or/andNot, handles)`, `compare(handles[2..4], mode)` | F1, F2 |
| Analysis | `paths(a, b, k, maxHops)`, `commonNeighbors(sel)`, `connectingNetwork(sel)` (Steiner), `timeline(sel, bucket)`, `geo(sel, h3Res)`, `algo.*(sel)` | F5, F16, F17, N11, N17 |
| Decisions | `decide(merge/split/link/unlink/undo, inputs, rationale, revision)` with OCC | F15, N8 |
| Transforms | `transform.run(name, inputSelection)` with provenance per result | N7 |
| Watch and export | watchlist CRUD with an alert stream; `export(sel, graphml/json)` | N2, F12, N22 |

Sources: Senzing v4 why/how (senzing.com/senzing-ai-sdk-v4-release/), Palantir object sets
(palantir.com/docs/foundry/api/ontologies-v2-resources/ontology-object-sets), Search Around limits
(palantir.com/docs/foundry/ontologies/oss-limitations), Maltego TDS, i2 Find Path.

### 4.6 Governance and operations
F13 first as a design, because every read-path feature in Tier 1 must check labels without post-filtering. F12 and
F14 close the fail-closed audit and plaintext-backup gaps. Note that AWS retired QLDB on 2025-07-31; SQL Server
Ledger and immudb are the live references for verifiable history. Sources: PostgreSQL RLS, Palantir purpose-based
access (blog.palantir.com/purpose-based-access-controls-at-palantir-f419faa400b3), Neo4j PBAC, SQL Server Ledger
verification, CockroachDB 25.2 RLS and encryption docs, Datomic excision, XTDB 2 data model.

---

## 5. Decisions for the owner

| ID | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Full-text engine direction | (a) Extend the LSM postings natively (F6, F7, F8, N3); (b) a per-node Lucene index as a derived sidecar, built deterministically on each replica from replicated records, with about 1 s near-real-time lag and a small in-transaction overlay for read-your-writes | (a) for Tier 1, since F6 to F8 are cheap natively and F8 reuses existing code. Revisit (b) before N3, because Lucene brings positions, highlighting, FST dictionaries, automata, facets and Monitor in one move |
| D2 | Cell system for F17 | H3 (JNI binding with bundled native libraries) or S2 (pure Java) | H3 if its native libraries package cleanly for the target Linux nodes; otherwise S2 |
| D3 | Capacity for Tier 1 | Q1 Java is already 28 of 30 ew (arch 8). Tier 1 adds about 46 ew beyond what is already budgeted | Finish the four worktrees and do F3, F13 design, F14 and F18 (about 8 ew) inside Q1 slack and the third Java engineer; schedule the rest across Q2 and Q3 ahead of lower-value arch items (E4 listener role, E3 `.vecgraph` shipping) |
| D4 | Where the identity layer lives | Plugin (arch today) or engine procedures | Engine for primitives that touch the read path (F1, F4, F5, F13); plugin for Recon-specific rules and routing |

---

## 6. Dependencies and licenses

Every new dependency needs an `ATTRIBUTIONS.md` entry, and NOTICE text where Apache-licensed with a NOTICE file.

| Library | Use | License | Status |
|---|---|---|---|
| RoaringBitmap | F1, N6 | Apache 2.0 | Allowed |
| Apache DataSketches | N12 | Apache 2.0 | Allowed |
| Apache Commons Codec | F6 phonetic | Apache 2.0 | Allowed |
| Lucene `analysis-phonetic`, `analysis-nori`, `analysis-smartcn`, `monitor`, `memory`, `highlighter` | F6, N2, N3 | Apache 2.0 | Allowed (Lucene already a dependency at 10.5.2) |
| Lucene `analysis-icu` (ICU4J) | CJK | Possibly Unicode-3.0 | **Not on the allow-list**; avoid or get sign-off |
| h3-java | F17 | Apache 2.0 (JNI) | Allowed; check native packaging |
| ThreatExchange PDQ (Java) | F11 | BSD | Allowed |
| jvector | N13 | Apache 2.0 | Already a dependency |
| SigLIP 2 weights, OpenCLIP, DINOv2, YuNet | N23 (outside the DB) | Apache 2.0 / MIT | Allowed |
| Splink, dedupe, Python recordlinkage | N10 | MIT / BSD | Port algorithms; do not embed Python |
| Duke, JedAI | N10 blocking | Apache 2.0 (unverified) | Read license files before use |
| Zingg | ER | AGPL-3.0 | **Forbidden** |
| Ultralytics YOLO-face | Detection | AGPL | **Forbidden** |
| Elastic-licensed ES code | Any | Elastic-2.0/SSPL | **Forbidden**; read docs only |
| pgvectorscale | Ideas only | PostgreSQL License | Not on the allow-list; no code |
| Senzing, Quantexa, Palantir, Maltego, i2 | Benchmarks only | Proprietary | Not embeddable |

---

## 7. How this changes `01-architecture.md`

| Arch item | Change |
|---|---|
| E7 parallel vector search | Already written (`vector-parallel-search` worktree); move from Q3 to Q1 merge |
| E8 FTS top-K | Same goal as F8, now cheaper by reusing `sparsevector/BmwScorer`; keep in Q3 or pull forward |
| E9 geo meters and kNN | Already written (`geo-knn` worktree); move from Q3 to Q1/Q2 merge |
| E11 n-gram and phonetic (Year 2, optional) | Becomes F6, Tier 1, mostly configuration on top of `CustomAnalyzer` |
| E13 engine RLS (Year 2) | Design moves to now (F13); enforcement (N1) moves to Q3/Q4, before hosted mode |
| E15 replicated commit events (Year 2) | Subsumed by F12's offset-addressed log, which is replicated in the same Raft entry |
| Plugin `grams3[]` lists (arch 4) | Replaced over time by F7, which serves every property instead of hand-maintained lists |
| Plugin counters and `ops` aggregates (arch 4) | Replaced over time by F16 |
| Plugin set-scoped endpoints taking `{ids[]}` (arch 7) | Keep the HTTP shape; back it with F1 selection handles so repeated compares do not re-ship ids |
| Image identity (arch 4: none corpus-wide) | F11 PDQ adds a corpus-wide, replicable image modality at about 43 GB at 1.35B images; add it to the disk model in arch 0.5 |
| Backups on an off-node encrypted mount (arch 5.6) | Still valid; F14 makes the archives themselves encrypted |

---

## 8. Open questions and unverified claims

1. Do WebSocket change events and the CSR view refresh fire on Raft followers at all? (F3 and F12 depend on it.)
   The research found commit listeners run only where the transaction executes, but did not test it.
2. Is ICU4J now Unicode-3.0? Check the jar before any ICU use.
3. Does h3-java ship native libraries for the production Linux and CPU architecture? (D2.)
4. Duke and JedAI license files have not been read.
5. PDQ multi-index hashing index size at 1.35B hashes (about 16 index entries per hash) must be modeled against the
   disk budget in arch 0.5 before F11 is scheduled.
6. Vendor capability claims (Senzing, Quantexa, Palantir) come from documentation and marketing pages, not from
   independent tests.
7. All effort figures are first estimates from the research passes; each Tier 1 item needs a design note with a
   test plan before it starts, per the project's TDD rule.
