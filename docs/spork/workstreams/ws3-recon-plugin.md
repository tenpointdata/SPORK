# WS3: the `spork-recon` plugin

Status: slice 1 (plugin core) merged; slice 2 (structured search, P4) implemented and tested on branch
`claude/ws3-recon-plugin-551eca`. Owner of this doc: the
WS3 session. Architecture: `01-architecture.md` 4b, 5.1 to 5.4, 7.1, 7.2 and the WS3 row of 8; identity and audit:
`07-entry-identity-and-audit.md`, which supersedes the role claim and shared engine users of 5.1.

## Slice 1: what exists

New module `spork-recon` (`arcadedb-spork-recon`), `arcadedb-server` in `provided` scope like the wire-protocol modules,
package `com.arcadedb.spork.recon`. Opt-in: `SERVER_PLUGINS=spork-recon:com.arcadedb.spork.recon.SporkReconPlugin`
plus `<server>/config/server-spork-recon.json`.

| Piece | Class | Behaviour |
|---|---|---|
| Namespace | `SporkReconPlugin`, `SporkPath` | Claims `/api/spork/v1` as one prefix per served method (POST, GET); dispatches `{tenant}/{route}` inside it. The path grammar is the gateway's: tenant `[a-z0-9][a-z0-9_-]{0,63}`, up to 5 plain segments, no `.`, `%` or empty segment; anything else is 404. A known path with another method is 405 with `Allow`. An unconfigured tenant is 404 `unknownTenant`. |
| Person | `SporkReconHandler` | Whoever the server authenticated: an Entry account (`EntryServerSecurityUser`, the person's own bearer) or a local break-glass user. Never the header. |
| Operating Context | `OperatingContext` | `X-Spork-Context = base64url(JSON {caseRef?, purpose, analyst?})`. Absent: 428 `contextRequired`. Unreadable, unknown purpose, wrong types, control characters, more than 200 characters, or any other field (`role`, `markings`, ...): 428 `contextInvalid`. An `analyst` that is not the authenticated account: 403 `analystMismatch`. |
| Purpose gate | `SporkRoutes`, `SporkRoute`, `DataClass`, `Purpose` | A declarative table, one row per route: method, path, operationId, audit action, data class, allowed purposes. A route may narrow its data class's purposes, never widen them (checked at construction). Refusal: 403 `purposeDenied`. |
| Audit | `SporkAudit` | One entry per request on the chain in `auditDatabase` (default `identity`) through `AuditChain` with `AuditActor.of(user)`: actor = Entry account id (or `local:<name>`), caseRef, purpose, detail. Committed before the response is released; a chain that cannot take it is 503 `auditUnavailable` and nothing is released. The append runs as the system, so nobody needs a grant on `AuditEntry` to be audited. |
| Hydrate | `HydrateService`, `RecordRef`, `RecordProjection`, `HydratePool`, `MerkleRoot` | `POST /{tenant}/records:hydrate {ids[], projection?}`, at most 5,000 ids (400 `tooManyIds`). See below. |
| Context | `SporkRoutes.CONTEXT` | `POST /{tenant}/context`: open to every purpose, appends `context.declare`, echoes the authenticated analyst. |
| Contract | `SporkReconApiSpec` | OpenAPI 3.0.3 built from the route table, served at `GET /api/spork/v1/openapi.json` (authenticated, no tenant) and committed as `docs/spork/api/spork-recon-openapi.json`; a test keeps the copy equal to the code. Regenerate with `mvn -o -pl spork-recon -am test -Dtest=SporkReconApiSpecTest -Dsurefire.failIfNoSpecifiedTests=false -Dspork.openapi.write=true`. |

Every 200 answer carries `requestId` (also in the audit detail, for later completion appends), `auditSeq`, `servedBy`,
`partial` and `consistencyBookmark` (the commit index under HA, null otherwise), as 7.1 requires.

### Hydrate

- **Ids.** `"168:8"` (upstream RID, packed `cluster << 40 | position`, resolved through `recdir`), a `record_id` as an
  unsigned decimal string (period in the top 16 bits, so no directory), or `imp:<importedId>` (the
  `(tenantId, importedId)` index of each partition, scoped to the path tenant). Duplicates are answered once; malformed
  ids are listed in `invalid`. Non-string ids are a 400: a `record_id` does not fit a JavaScript number.
- **Reads per id.** One HASH probe on `RecordDir.upstreamRid` for an upstream RID, then one HASH probe and the Record
  page in `rec_<p>`. No edge, local vertex, Place or text read.
- **Engine user.** Each database is read with the person bound in `DatabaseContext` on the reading thread (pool workers
  included), so per-type grants apply; a database the person holds no grant on is 403 for the whole request before it is
  read. The engine refuses it too (an ungranted database binds the deny-all user), so the plugin's check is the earlier
  of two gates, not the only one: removing it leaves the 403 test green. Bindings are undone after each chunk, so nothing leaks onto a pooled thread or the request thread.
- **Visibility.** A record whose `markings` bits the person does not hold, or another tenant's import, is in `missing`
  exactly like an unknown id (absent, not redacted, 5.3). Held markings come from the person's Entry permissions through
  the `markings` map of the configuration (`{"spork.marking.minor": 0}`; a parent permission covers its children), never
  from the request. Local users hold `localMarkings` (default none).
- **Projection.** Allowlist (`RecordProjection`): phones, emails, socials, businessNames, imageHashes, lat, lon, city,
  state, country, postedAt, collectedAt, age, services, paymentTypes, clientele, source, viewCount, hasImages, style,
  styleVec, textHash, priorityScore, markings. Default: all but styleVec. `state`, `country` and `city` are the display
  values, not the normalized `stateCode`, `countryCode` and `cityNorm`. Anything else (title, text) is 400
  `projectionInvalid`.
- **Protocol tag.** Each chunk runs under the request's `ProtocolContext` (`HydratePool.runTagged`), restored in a
  finally, so pool work is the client's request to the HA snapshot gate, the query metrics and the traces, not the
  engine's (issue #10094; `ProtocolContextCensusTest` pins `spork-recon` at 2 tag calls).
- **Parallelism.** Above 64 ids the work splits into 64-id chunks on the `spork-hydrate` pool (`HydratePool`, a
  `DedicatedThreadPool`: 32 threads, 1,024-deep queue, caller-runs, gauges `arcadedb.executor.*{pool=spork_hydrate}`);
  the request thread runs chunk 0 and waits with `GraphAlgorithms.awaitFutures`, which reclaims unstarted chunks.
  Results land in per-index slots, so order is the request order with no synchronization between chunks.
- **Audit.** One `records.hydrate` entry: tenant, requestId, requested, records, missing, invalid and `keysRoot`, the
  Merkle root of the released `record_id`s (sorted distinct keys, leaves `SHA-256(0x00 || key)`, nodes
  `SHA-256(0x01 || l || r)`, odd node carried up, empty set `SHA-256("")`).
- **partial** is true when an id belongs to a partition this node does not hold.

### Configuration (`server-spork-recon.json`)

| Key | Default | Meaning |
|---|---|---|
| `tenants` | required | tenant path segments this installation serves |
| `auditDatabase` | `identity` | the analyst chain |
| `directoryDatabase` | `recdir` | upstreamRid to period |
| `partitionPrefix` | `rec_` | partition databases `rec_<period>` |
| `hydrateThreads`, `hydrateQueue` | 32, 1024 | the `spork-hydrate` pool |
| `markings` | `{}` | Entry permission to marking bit (0 to 63) |
| `localMarkings` | 0 | marking bits of local users |

A file that cannot be parsed stops the plugin at configure (fails closed). No file: only the contract is served and
every route answers 503 `notConfigured`.

## Slice 2: structured search (P4, P4b, P4v)

`POST /api/spork/v1/{tenant}/search` (01-architecture 7.2; 02-retrieval-speed.md 2.4; R36), served on direct index cursors by
the engine's structured-search core (`com.arcadedb.query.structured`, `p4-structured-core.md`), never on the SQL plan. By the
owner's decision there is one core: the walk, the count and the rank order live in the engine; the plugin adds only what is
Recon's.

| Piece | Where | Behaviour |
|---|---|---|
| Request | `SearchService.parse` | Recon's filters onto schema v1: `state` and `country` upper-cased onto `stateCode` and `countryCode` (ingest's normalization is not defined yet; two-letter codes assumed), `city` through `SporkKeys.cityNorm` onto `cityNorm`, `areaCode` and `service` by item, `hasImages`, `website`, `aggregator`, `ageMin`/`ageMax` (an integer band, 0 to 200), `postedFrom`/`postedTo` (ISO-8601 or epoch ms). `sort` posted_desc (default), posted_asc, views_desc; `limit` 1 to 500 (50); `projection` as hydrate's plus `title`. `phone`, `email`, `business`, `q` and `imageHash` are refused with 400 `filterUnsupported` (P4s and P7 are later arms); any other key is 400 `searchInvalid`. |
| Walk | engine `StructuredSearch` | The smallest indexed facet drives one cursor per value of its `(facet, tsPosted, views)` composite; the other facets are tested on the Record; at most 40 x limit entries for the page, `partial` beyond. `views_desc` walks `(facet, views, tsPosted)` with the window checked on the key and falls back to the time arm past 10,000 entries. |
| Count | engine `StructuredQuery.count` | Up to 10,000 (`totalCapped` beyond), counted on the index alone when one facet covers the search and visibility cannot hide the entry; at most 20,000 entries past the page, then a lower bound with `partial` and `totalEstimate`. |
| Visibility | `RecordVisibility` (a core `RecordFilter`) | Markings the person does not hold and other tenants' imports are neither rows nor counted. Per partition and request, the hidden RIDs are read from two sparse indexes added to schema v1's `rec.sql`, `(markings)` and `(tenantId)` with `NULL_STRATEGY SKIP`, so a count reads only the records it may have to hide. Unmarked records must leave `markings` null (0 is tolerated, skipped by range). Past 100,000 hidden entries, or without the indexes, every counted record is read: slower, never wrong. |
| Partitions | `SearchService.search` | Every `rec_<p>` the node holds, newest period first (oldest first for posted_asc), each answering its top `limit` with the person bound as engine user; pages merge in the core's order (time then RID, or views then time then RID), counts add up to the cap. |
| Titles | `SearchService.title` | `title` in the projection reads `TextBody.title` from `txt_<period>` through the record's `textHash` (one HASH probe), with the person bound on the text database: no grant there is 403 for the request, the console/ops text boundary of 5.4. |
| Audit | `records.search` | One entry per request: tenant, requestId, records, total, totalCapped, partial, sort, filter names, whether titles were read, and the Merkle root of the released `record_id`s. |

### Measured (5M records, one schema v1 partition, embedded, 1 thread)

`StructuredSearchBenchmark` (`@Tag("benchmark")`): 5M records with the retrieval harness's distributions (state through a Zipf
1.0 over 300 cities, age 18 + |N(0,1)| x 6, 1 to 4 services Zipf 1.0 over 40, posted NOW - 730 d x u^2, lognormal views), the
P4 composites only, fully compacted as at seal; 400 timed queries per shape after 200 of warm-up, production visibility with
the hidden set. The machine was shared with other sessions' builds and benchmarks throughout: numbers are upper bounds.

Engine core, 2026-11-03 13:23 to 13:39, load average 12 rising to 24 (the SQL baseline ran 1.6x slower than in an earlier
pass at load 7.7, so read every number as an upper bound):

| shape | p50 ms | p95 ms | p99 ms | partial | mean entries read | mean records read |
|---|---:|---:|---:|---:|---:|---:|
| P4: state, newest 50 + count to 10,000 | 11.73 | 13.19 | 15.44 | 0 | 9,991 | 50 |
| P4: state + 7 d window, newest 50 + count | 8.46 | 9.27 | 9.78 | 0 | 8,845 | 50 |
| P4b: state, newest 500 + count | 9.60 | 11.33 | 13.75 | 0 | 10,001 | 500 |
| P4v: state + 30 d, views_desc 50 + count | 12.08 | 16.20 | 17.30 | 0 | 9,900 | 50 |
| P4 page only (no count) | 0.16 | 0.25 | 0.32 | 0 | 50 | 50 |
| harness STRUCTURED (state + age band + service), newest 100 + count | 26.31 | 382.06 | 502.05 | 322 of 400 | 10,353 | 10,353 |
| harness STRUCTURED, newest 50 + count | 6.47 | 370.21 | 609.92 | 321 of 400 | 8,309 | 8,308 |
| harness STRUCTURED, newest 50, page only | 3.44 | 26.15 | 27.60 | 186 of 400 | 1,418 | 1,418 |
| harness STRUCTURED on the SQL plan, newest 100 (40 queries) | 426.44 | 1,466.93 | 1,702.25 | | | |

An earlier pass of the same shapes on the plugin's own walk (since folded into the core), at load 7.7, measured P4 + count
5.90 / 7.12 ms (p50 / p95), P4b 6.12 / 7.05, P4v 6.07 / 7.01, the STRUCTURED page 2.31 / 13.44, and SQL 263 / 744.

Targets (2.4, warm): P4 and P4v 5 ms p50 / 20 ms p99, P4b 12 / 40 ms.

What the numbers say, and what this design cannot reach:

- **The page is far inside the target.** A covered first page reads 50 entries and 50 records: 0.16 ms. The P99 targets hold for
  P4, P4b and P4v with the count included, even at load 24.
- **The capped count is the whole of the P4 and P4v medians, and the 5 ms p50 is not met with it.** A single-facet count reads
  up to 10,001 index entries and no record, which is what 2.4 designed ("count: 7 leaves"), but the LSM cursor costs 0.5 to 1 us
  per entry here (5 to 12 ms), against the 0.2 ms 2.4 budgeted from the leaf bytes. Not tuned around, per the brief. The
  options are design changes: (a) answer a single-facet count with no window from the cardinality table the core already
  keeps (O(1), exact as of its refresh, so stale by up to its TTL); (b) engine item 4.3 M3, `count(*)` served from the index
  by leaf (entries per leaf in the page header), which also serves windows; (c) make the count asynchronous or opt-in for
  Recon's first page.
- **A multi-filter count cannot be exact and fast on a single-cursor design.** For the harness STRUCTURED shape the other two
  filters are residuals, so each counted entry is a record read: 10,000 reads, 370 ms p95, and the 20,000-entry budget runs out
  on 80% of queries, which then answer `partial` with `totalEstimate`. The page alone is 3.4 ms p50 but 26 ms p95, because the
  40 x limit over-read also runs out (186 of 400 partial) when the joint selectivity is low. Exact counts for this shape need
  set intersection rather than a cursor: per-value roaring bitmaps per partition (the ObjectSet service of 01-architecture 4b),
  where three facets intersect in well under a millisecond. Owner decision whether Recon shows "about N" (the estimate) until
  then.
- Against SQL on the same data: the STRUCTURED page is 124x faster at the median (3.44 against 426 ms) with the same rows.

## Decisions taken in slice 1

- **Read audit is committed after the read and before the release.** A hydrate reads `recdir` and `rec_<p>`, and a
  transaction cannot span databases, so "same transaction" (5.2) is met as "nothing leaves that the chain does not
  already hold". Write routes will append inside their own write transaction.
- **No request-scoped ThreadLocal for the context** (5.1 "Binding"): the context is passed explicitly, because hydrate
  hands work to pool threads where a ThreadLocal would have to be copied anyway. The engine user binding is the only
  thread-bound state, and it is set and undone per chunk.
- **Refusals are not audited.** A 428 or 403 releases nothing; auditing them would let anyone with a credential grow the
  chain at will. Revisit if the owner wants attempted access on the chain.
- **Errors** are `{"error": <for people>, "code": <stable>}`; Recon's `ApiError` switches on `code`.

## Schema v1 (WS2)

Confirmed by WS2 on 2026-11-03 (branch `claude/ws2-keys-schema-ee9519`, commit 5516a8d43, not merged when this slice
was written; handover note `ws2-schema-v1-handover.md` in the train-1 notes folder):

1. `record_id` is a LONG, `RecordId.of(period, seq) = period << 48 | seq`, so `period = record_id >>> 48` (0 to 32767),
   partitions `rec_<period>`. `RecordRef.periodOf` is the one place here that knows it.
2. Record property names are the ones `RecordProjection` reads: `socials`, `lat`, `lon`, display `city`, `stateCode`,
   `countryCode`, `paymentTypes`, `clientele`, `source`, `style`, `styleVec` (ARRAY_OF_FLOATS), `priorityScore`
   (FLOAT), `markings` (LONG bitmask), `tenantId`. `state` and `country` are the display values v1 keeps beside the
   normalized `stateCode` and `countryCode`, and hydrate returns the display ones, as Recon shows them. `importedId` is indexed only as `(tenantId, importedId)` UNIQUE_HASH
   NULL_STRATEGY SKIP, which is exactly how an `imp:` id is looked up; `upstreamRid` UNIQUE_HASH skips nulls.
3. `textHash` is BINARY 32 bytes (full SHA-256 over the 4-byte title length, title and body), returned as lowercase hex.
4. `RecordDir {upstreamRid LONG, period SHORT}`, UNIQUE_HASH on `upstreamRid`.

`spork-recon` depends on `spork-keys` (engine excluded: the server provides it): `RecordRef` parses RIDs with
`UpstreamRid` (so `#168:8` is accepted too), hydrate takes the period from `RecordId.period`, and the IT corpus is built
with `SporkSchema.install`. The chain columns and `ChainHead` stay this side's (`AuditChain.ensureSchema`, IF NOT EXISTS
over WS2's `AuditEntry`).

## Next slices

In the order Recon's move needs them (WS4 builds `SporkSource` against the OpenAPI contract):

1. **Intake** (`POST /intake`, at most 25 tokens, per-token caps on every token kind, `expand=false`, `requestId` for
   the completion append) and **`GET /records/{id}`**.
2. **Search, the rest of it**: substring arms P4s (`phone`, `email`, `business`, after M6), `q` (P7), `imageHash`,
   keyset pages through the route (the core has them), `POST /search:count`, and titles on hydrate as on search.
3. **Pivot** and **partial** (`POST /pivot`, `POST /partial`).
4. **Group-by** and **timeline** (`POST /groupby`, `POST /timeseries`, query form and `{ids[]}`).
5. **Feed** (`GET /feed/live`, `feed.view` audited per request including cache hits) and `GET /recent`.
6. **Board** with OCC (`/board`, `/targets/{key}`, If-Match revision, 409 when stale) and **deconfliction**
   (`POST /deconflict`, the full `TargetOverlap`).
7. **Audit API**: `GET /audit` (cursor, filters, count pushdown), `POST /audit/append` completions with the detail
   allowlist and `requestId` check, `POST /audit/verify`, the `AuditAccess` side table with `GET /audit?record=` and
   `?analyst=`, v2 commitment body and MAC (needs `K_subject` and `K_epoch` from KMS).
8. **Image grants** on hydrate and `GET /image`.
9. **Read consistency plumbing** (`X-ArcadeDB-Read-Consistency`, `X-ArcadeDB-Read-After`) and the internal leader append
   route for audited reads served on followers (today an append on a follower is not routed).
10. **Set-scoped routes** on F1 selections (`/sets/*`, `/compare`, `/graph/*`, `/geo/*`) with `{ids[]}` or a selection.
11. **Compaction scheduler** (leased full compactions, seal and post-admission compactions, suspension while a peer is
    down) and the ops-grant step of period creation.

Also open: per-tenant authorization (which Entry permission admits a person to a tenant; today any authenticated person
with database grants may use any configured tenant), the permission names for markings, and the purpose gates
themselves (owner question 10). The plugin is not yet in the `package` distribution.

## Verification

`mvn -o verify -Pintegration -pl spork-recon -am`. Search: `SearchEquivalenceTest` (the core under the plugin's visibility filter
against SQL on a v1 partition with hidden records: 200 random filter, sort and window combinations, the benchmark shape, every
sort per state: same rows, order and count; the smallest facet drives; a covered count reads only the page and the hidden
records), `SearchRequestTest`, `RecordVisibilityTest`; in the engine, `StructuredSearchCountAndRankTest` (10) next to the core's
`StructuredSearchTest` (12). Unit: `OperatingContextTest`, `SporkRoutesTest`, `RecordRefTest`, `HydratePoolTest` (a chunk carries the request's protocol tag and restores the thread's own),
`MerkleRootTest`, `SporkReconConfigurationTest`, `SporkReconApiSpecTest` (the document parses with swagger-parser,
documents every route with its purposes and refusals, carries the 5,000 cap, matches the committed copy, and the plugin
registers only its two prefixes). Integration (`BaseGraphServerTest`, ports read back from the server, a stub Entry on a
fixture-allocated port): `SporkReconPluginIT` (428 both ways, 403 purposeDenied, 403 analystMismatch, 401, 404 tenant and
route, 405 with Allow, every id form, missing and invalid, partial, markings absent for a person without them, 403 for a
person with no grant on the partition, projection allowlist, the 5,000 cap, a 2,000-id fan-out in request order with one
entry, `context.declare`, a local user audited as `local:root`, the served contract; search: page and count of what the person
may see, unheld markings left out of the count (oldest first, so the count meets them), views order with a window, the purpose
gate, 428, `filterUnsupported`, no partition grant, titles behind the text grant) and `SporkReconAuditFailsClosedIT`
(503 and no records when the chain is unavailable).
