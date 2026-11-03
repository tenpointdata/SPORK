# SPORK research findings (2026-11-02)

Input for the SPORK architecture. SPORK is the tenpointdata fork of ArcadeDB (26.11.1-SNAPSHOT, Java 21).
Goal: an identity-centric, targeting-first, truly multimodal database for counter-human-trafficking work, with
Redlight Recon (DeliverFund) as the primary consumer.

## Requirements (from the product owner)

- Full-text search, partial text search, geospatial search, vector search, ROC facial recognition, multimodal.
- Identity-centric and targeting-first at the core.
- Scale: 600 GB / 290M records today, expected ~3x (1.8 TB / ~900M records) within two years.
- Hardware: 4 nodes ("clusters"), each 126 GB RAM and 2 TB storage.
- Distribution: the owner clarified they are NOT looking to shard; they want REPLICATION (full copies across the
  nodes). This matches SPORK's native Raft HA. The binding constraint therefore becomes: the whole replicated
  footprint (data + indexes + vector graphs + WAL/raft logs + per-database resync headroom) must fit on ONE node's
  2 TB disk and its 126 GB RAM, today and at 3x.
- Disk is NOT a hard ceiling: the owner can expand disks as needed, but 2 TB is easiest short term. So the design
  must fit 2 TB per node for the near term, and state the concrete trigger (footprint threshold and expected date
  on the growth curve) at which disks should be expanded and to what size. Prefer designs that do not require
  re-architecture when disks grow. RAM (126 GB) is the firmer constraint.
- Schema details still to come.

## Owner directives added 2026-11-02 (later in the day)

1. **Optimize hard for retrieval speed.** p50/p99 read latency and read throughput outrank write throughput and
   storage efficiency. Denormalize, precompute adjacency, cache, and change the engine where it pays.
2. **Retrieval types should be reminiscent of Recon's compare feature** (`recon-next/src/compare.ts`,
   `server-compare.ts`): side-by-side comparison of 2 to 4 selections in four modes: records, entities
   (identifiers), sets (result sets) and faces (cross-group face clusters). Retrieval must therefore be
   SET-ORIENTED: given 2 to 4 arbitrary selections (record ids, identifier keys, saved result sets, face sets),
   return their shared and distinct identifiers, overlaps, timelines, places and face clusters fast. Every modality
   (exact, text, geo, vector, face) must be able to answer "over this set" as well as "over the corpus".
3. **A link-analysis / intelligence platform will be built on top of SPORK**, in the family of Kaseware PATH,
   Palantir Gotham/Foundry and Maltego. SPORK must therefore natively support: an object-property-link ontology
   with typed entities and typed relationships; graph expansion by hops with per-edge-type filters and limits
   (Maltego "transforms" = server-side procedures that take an entity and return linked entities with provenance);
   link-chart queries (shortest paths, common neighbours, k-hop neighbourhoods with counts, degree/centrality);
   timelines over any selection; geospatial layers over any selection; per-object provenance (source, collection
   time, analyst assertions, confidence); versioned entity resolution (merge/split with history); case workspaces
   with ACLs; saved searches and watchlists; export of subgraphs. These are read-heavy interactive workloads and
   fall under directive 1.

## Primary consumer: Recon (`~/Documents/Projects/recon/recon-next`, Bun + TypeScript)

### Upstream data shape
The upstream RedLight corpus is already an OrientDB graph. Records are addressed by OrientDB RIDs (`"168:8"`).
A Record vertex links to typed sub-nodes:
- Phone(area_code, carrier, country_code, phone_number, phone_number_original)
- Email(email_address)
- Social(social_handle, social_platform)
- File(hash, hash_unique, mime_type, path_local, path_url)
- Location(address, city, state, county, zipcode, country (+_original variants), general_location, geocode [lat, lon])
- Description(collected_age, ethnicity, eye_color, gender, hair_color, hair_type, height, nationality)
- Name(first, middle, last, aliases[], name_original)
- Business(business_name, business_erotic, business_opened)
- Review(review_body, review_date, reviewer_url)
- RedLight(article_body, article_title, hashes, article_type ad|encounter|profile|post, view count, category[],
  clientele[], payment_types[], rates[], services[], provider mentions, intra_ad_urls[], ts_date_removed)
- Record(aggregator, source, article_url, record_id UUID, ts_date_collected, ts_date_posted, website_name)
- Shanenet: about 20 boolean text-risk flags.

### Identity resolution rules used today
- Phones: last 10 digits; 7-digit numbers inherit the area code of the posting city.
- Emails and socials lowercased (`platform:handle`, leading `@` stripped); business names lowercased alnum-only;
  image hashes exact.
- Link clusters: union-find over records sharing any phone, email, image hash, business name, social.
- Fuzzy edges: writing-style cosine >= 0.95 (24-d style vector), face cosine >= 0.45 (512-d ArcFace).
- Targets: `tgt_<sha256(type:normalized)[:16]>`, with priority, assessment, disposition, revision (OCC), scores,
  history, notes.

### Access patterns (all are in-memory full scans today; must be pushed down)
- Structured search: q, city/state/country, areaCode, phone, email, business, service, hasImages, imageHash, age
  range, posted window; sort by posted or views; limit up to 500, production paginates to 1000+.
- Intake: classify token (phone/email/social/RID) and return footprint; RID pulls 1-hop siblings via phones and
  image hashes.
- Pivot (record -> identifier -> records), multi-hop.
- Partial: social handle substring, area-code prefix, board search by digits/name/tag.
- Group-by on multi-valued fields (phone, areaCode, email, imageHash, business, city, state, service, age).
- Graph algorithms: connected components, degree, label-propagation communities, articulation points,
  clustering coefficient, frequent pairs, sequential number blocks.
- Time series: day/week buckets, tempo, periodicity, spikes (30d vs 90d baseline), DOW/hour histograms.
- Geo: places, convergence (>= 2 phone groups within 200 km and 14 days), movement legs, per-identifier tracks,
  Markov next-locale, DBSCAN hotspots, grid novelty, impossible travel.
- Live feed: located records over 6h/24h/3d/7d windows, sliced sampling, hotspots, recurring phones.
- Face linkage: 512-d embeddings, cosine >= 0.45; authorship: 24-d style vector cosine.
- Dossier: re-resolve up to 25 tokens, take the component, run detectors; caps 200 records / 120 nodes / 360 edges.
- Board: KV + filters + OCC (409 on stale revision).
- Audit: newest-first pages with seq cursor, filter by action/case/subject, chain verification.
- Evidence: full-text (SQLite FTS5 today), wiki revisions, bindings.
- Watches: scheduled re-scan + diff + alert; wants change feeds / subscriptions and in-DB scheduled detectors.

### Governance that must reach the DB layer
- Operating Context {analyst, caseRef, purpose} on every sensitive call; purposes victim_identification,
  network_disruption, investigative_lead, standing_monitoring, training. HTTP 428 without it.
- Hash-chained audit log: `hash = sha256(prevHash + canonicalJSON(entry))`, appended in the SAME transaction as
  the write it describes; fails closed. Unkeyed today (a local admin could rewrite it).
- Redaction: ad titles/text never cross ops APIs; packages carry no ad text or images.
- Retention, minimization, legal holds and deletion must propagate through graph, search and vector projections
  (biometrics especially). No retention code exists yet.
- Hosted, tenant/case-scoped access is planned; event outbox with replay, monotonic sequences.

### Volume and latency hints
- Comparable systems ingest 150K-200K ads/day.
- Current upstream search p50 ~1.08 s, p95 ~1.55 s under 150 simulated users; target "first signal in ms".

## SPORK engine audit

### Distribution
- No horizontal sharding. HA = Apache Ratis, ONE Raft group per cluster, every node holds a full physical copy of
  every database (`RaftHAServer`, `HA_AUTO_ACQUIRE_DATABASES`). No scatter-gather; queries never span databases.
- `PartitionedBucketSelectionStrategy` partitions into local buckets only (needs UNIQUE index on partition key).
- Full resync (`SnapshotInstaller`) needs the database's full size free on the volume (2x total).
  => a single 1.8 TB database cannot live on a 2 TB node; even 600 GB needs ~1.2 TB during resync.
- 4 voting nodes tolerate only 1 failure (same as 3).
- Followers serve reads with `X-ArcadeDB-Read-Consistency` (eventual / read_your_writes / linearizable).
- Followers apply raw page deltas; record listeners and triggers fire only where the tx executes.
- LSM_VECTOR graph files (`.vecgraph`) are NOT replicated; every node rebuilds them.
- `HA_BOOTSTRAP_FROM_LOCAL_DATABASE` allows pre-staging files for initial load.

### Memory / storage
- Page cache lives on heap (`arcadedb.maxPageRAM`, default heap/4); no mmap. Use ZGC.
- No page compression; LZ4 only for EXTERNAL properties (which can sit on another volume).
- `TYPE_DEFAULT_BUCKETS` defaults to 1.
- Bucket pages reserve ~12.5% for slots.

### Ingest
- `GraphBatch` (embedded), HTTP `/api/v1/batch` (JSONL/CSV), gRPC `GraphBatchLoad` / `InsertStream`,
  OrientDB importer, WebSocket insert sessions. No published throughput numbers.

### Extension points
- `ServerPlugin` (isolated classloader, custom HTTP routes), record event listeners, after-commit callbacks,
  triggers (SQL/JS/Java), custom SQL functions, WebSocket change feed.
- Index types are a closed enum (`Schema.INDEX_TYPE`); adding one means changing the engine.

### Security
- Users/groups per database, per-type CRUD. No row-level security, no audit subsystem, no OIDC/SAML/LDAP.
- `DefaultDataEncryption` (AES-GCM per property) exists but is not wired to the server.
- TLS for HTTP/Postgres/Bolt/gRPC and Raft mTLS available. MCP plugin exposes execute-command: lock it down.

### APIs for TypeScript
- HTTP/JSON (OpenAPI, NDJSON streaming, consistency headers), Postgres wire (`pg`), Bolt (`neo4j-driver`, HA
  routing), gRPC (`@grpc/grpc-js`). No official JS client.

### Full-text
- Own engine on LSM keys; Lucene supplies only Analyzer + QueryParser. BM25 default. No positions (phrase =
  term intersection), no proximity, no highlighting. Candidates fully materialized (no WAND/MaxScore).
- One analyzer per index (per-field analyzer config parsed but ignored). No n-gram, no phonetic.
- Prefix fast; wildcard/regex/fuzzy scan postings; `LIKE '%x%'` = full scan.
- SQL `SEARCH_INDEX`, Cypher `db.index.fulltext.queryNodes`.

### Geo
- Geohash prefix tree in LSM; WKT stored as STRING; predicates within/intersects/contains/dWithin (degrees!).
- No kNN; Cypher point functions never use the index.

### Dense vector (`LSM_VECTOR`, jvector 4.0.2 Vamana/DiskANN-style)
- One graph per bucket, searched SEQUENTIALLY; ~1.1 KB heap per node to build; one JVM-wide rebuild permit;
  full rebuild per bucket; location index + PQ codes resident on heap. Tested to ~10M per index.
- Quantization NONE / INT8 / BINARY (rerank) / PRODUCT (rerank, recall 0.987 on SIFT-1M).
- Filtered search: allow-list prefilter (<= 20% selectivity -> exact), else bits filter.

### Sparse vector
- `LSM_SPARSE_VECTOR`, Block-Max MaxScore, SPLADE / IDF modifier; `vector.sparseNeighbors`.

### Hybrid
- `vector.fuse` (RRF/DBSF/LINEAR) over vector, sparse, full-text or ranked SELECTs; `HybridSearch`
  (vector + text + bounded BFS) via `/api/v1/vector/{db}/hybrid`.

### BLOBs
- BINARY properties; records chunked across pages; EXTERNAL properties with LZ4 on a separate bucket/volume.
  No streaming API: originals belong in object storage.

## ROC (Rank One Computing, now roc.ai)

- SDK 3.17 (2026-09-28). Bindings: C++, Java, Python, C#, Go, Node.js, Rust. CLI, self-hosted `roc-serve` Web API
  (protobuf over HTTP; Represent / Search; one gallery per process). CPU and GPU. Licensing per device/stream/
  transaction. ROC donated its SDK to DeliverFund (May 2025).
- Templates are OPAQUE: ~448 B (roc-021). Dimensions, element type and metric are not public. Scores are ROC
  calibrated similarities. Templates are almost certainly version-locked: keep source crops and re-templatize on
  upgrade.
- EULA: no reverse engineering, no "merging" with other software, outputs may not train models, no published
  benchmarks. Decoding templates into a generic ANN index needs ROC's written permission.
- NIST 1:N roc_021: search time linear in N (about 0.23 us/template/process; 12M in 2.7 s). Vendor claims
  sub-second at 100M+ and 500M deployments (method undisclosed).
- Accuracy: top-tier (1:1 FNMR 0.0013; 1:N rank-1 FNIR 0.0007 at 12M); weaker on >= 10-year age gaps.
- Also: age estimation (minor flagging), tattoo recognition 1:N (trafficking victim ID use case), ABIS
  (face+fingerprint+iris+tattoo), deduplication, video (ROC Watch).
- Recommended: store template as opaque binary with algorithm/version tag on a Face vertex; do 1:N in a
  ROC-managed, sharded gallery tier; optional two-stage (license-clean open embedding ANN shortlist, ROC rescore).
- Open questions for ROC: cross-version compatibility, float embedding export, indexed/GPU 100M+ search, Docker
  image, EULA interpretation for storing templates in a third-party DB.
