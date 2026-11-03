# WS3: the `spork-recon` plugin

Status: slice 1 (plugin core) implemented and tested on branch `claude/ws3-recon-plugin-551eca`. Owner of this doc: the
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
  styleVec, textHash, priorityScore, markings. Default: all but styleVec. Anything else (title, text) is 400
  `projectionInvalid`.
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

## Decisions taken in this slice

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
   (FLOAT), `markings` (LONG bitmask), `tenantId`. `importedId` is indexed only as `(tenantId, importedId)` UNIQUE_HASH
   NULL_STRATEGY SKIP, which is exactly how an `imp:` id is looked up; `upstreamRid` UNIQUE_HASH skips nulls.
3. `textHash` is BINARY 32 bytes (full SHA-256 over the 4-byte title length, title and body), returned as lowercase hex.
4. `RecordDir {upstreamRid LONG, period SHORT}`, UNIQUE_HASH on `upstreamRid`.

When `spork-keys` merges: replace `RecordRef.periodOf` and `formatUpstreamRid` with `RecordId` and `UpstreamRid`, and
build the IT corpus with `SporkSchema.install(db)` instead of the hand-written DDL (which mirrors v1 today). The chain
columns and `ChainHead` stay this side's (`AuditChain.ensureSchema`, IF NOT EXISTS over WS2's `AuditEntry`).

## Next slices

In the order Recon's move needs them (WS4 builds `SporkSource` against the OpenAPI contract):

1. **Intake** (`POST /intake`, at most 25 tokens, per-token caps on every token kind, `expand=false`, `requestId` for
   the completion append) and **`GET /records/{id}`**.
2. **Search and count** (`POST /search`, `POST /search:count`) with the console/ops text boundary as Entry permissions,
   title and text projections from `txt_<p>` behind their grant, and the three denial tests of 5.4.
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

`mvn -o verify -Pintegration -pl spork-recon -am`. Unit: `OperatingContextTest`, `SporkRoutesTest`, `RecordRefTest`,
`MerkleRootTest`, `SporkReconConfigurationTest`, `SporkReconApiSpecTest` (the document parses with swagger-parser,
documents every route with its purposes and refusals, carries the 5,000 cap, matches the committed copy, and the plugin
registers only its two prefixes). Integration (`BaseGraphServerTest`, ports read back from the server, a stub Entry on a
fixture-allocated port): `SporkReconPluginIT` (428 both ways, 403 purposeDenied, 403 analystMismatch, 401, 404 tenant and
route, 405 with Allow, every id form, missing and invalid, partial, markings absent for a person without them, 403 for a
person with no grant on the partition, projection allowlist, the 5,000 cap, a 2,000-id fan-out in request order with one
entry, `context.declare`, a local user audited as `local:root`, the served contract) and `SporkReconAuditFailsClosedIT`
(503 and no records when the chain is unavailable).
