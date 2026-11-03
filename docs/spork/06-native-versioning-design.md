# 06 - Native record versioning (system-versioned types)

Status: design note and slice 1 implemented, 2026-11-02. Supersedes the "engine-level history only if usage demands it"
caveat of roadmap item N21: the owner asked for versions to be managed natively by the engine, not modelled as an
exploded mess of `Assertion` vertices and never-deleted `MEMBER_OF` edges.

## Problem

The architecture (2.6, 2.7) and roadmap (F15, N21) expressed history as graph data:

- every asserted value as an `Assertion` vertex the read path overlays;
- every membership change as an *ended* `MEMBER_OF` edge (`validTo` set) that is never deleted, so the edge lists of
  every Identity grow with every merge and split and every traversal filters `validTo IS NULL`;
- `graph.history(id, asOf)` reassembled from those pieces.

That puts history on the hot read path (dead edges in every adjacency walk), multiplies vertex and edge counts, and
makes every query author responsible for the as-of filter.

## Model: SQL:2011 system-versioned types

A type can be marked **versioned**. The engine then keeps the transaction-time history of every record of that type
(and of its subtypes) by itself:

| Piece | What it is |
| --- | --- |
| Current version | The record, where it always was, same RID. Reads that do not ask for history are untouched: no extra page, no filter. |
| `_txFrom` | One engine-stamped `LONG` (epoch microseconds) on each record of a versioned type: when the current version became current. Engine-owned: an update that changes it is refused, a value given on create is overwritten. |
| History store | Internal document type `_VersionHistory`: one entry per superseded version, `{rid, txFrom, txTo, out, in, data}`. `data` is the record's committed serialized image, copied byte for byte (no re-serialization on the write path). `out`/`in` are set for edges only. |
| Indexes | `rid` (NOTUNIQUE), `out` and `in` (NOTUNIQUE, nulls skipped) on the history store. |

A version is valid on `[txFrom, txTo)`. The current version is valid on `[_txFrom, +inf)`. A deleted record has no
current version; its last history entry ends at the delete.

### Write path

- **Create**: stamp `_txFrom`. Nothing else.
- **Update** (only the user-visible path, the one that fires record events; the engine's own edge-list bookkeeping on a
  vertex never creates a version): on the first update of the record in the transaction, copy the committed image to
  the history store with `txTo = stamp`, then stamp `_txFrom = stamp`. Later updates of the same record in the same
  transaction add nothing: the intermediate states were never committed. A record created in the same transaction has
  no committed image and gets no history entry.
- **Delete**: copy the committed image with `txTo = stamp`, unless an update in the same transaction already did.
  Edges deleted by a vertex delete go through the same path, so the edge's history survives its endpoint's removal.
- **Stamp**: one per transaction, taken at its first versioned write: epoch microseconds, strictly increasing per
  database (`max(now, last + 1)`), so every version of a record has a distinct, ordered stamp.
- **Rollback** discards the history entries with the rest of the transaction; **HA** replicates them with the rest of
  the transaction's pages, so every replica holds the same history.

The history store refuses `UPDATE` and `DELETE` from clients. Only `history.purge` (retention, right-to-erasure)
removes entries.

### Read path

| Call | Returns |
| --- | --- |
| `history.of(rid)` | Every version of the record, oldest first, the current one last (`@current = true`). Each is a map of the record's properties plus `@rid`, `@type`, `@version` (1-based), `@txFrom`, `@txTo` (dates; `@txTo` null for the current version), `@out`/`@in` for edges. |
| `history.asOf(rid, time)` | The version valid at `time` (a date, or epoch milliseconds), or null when the record did not exist then. |
| `history.edges(vertex, time [, direction [, edgeType]])` | The edges of `vertex` as they were at `time`: current edges that already existed, plus superseded or deleted edges valid at `time`. |
| `history.purge(rid)` | Removes the record's history entries, returns how many. |

They are SQL functions, so Cypher calls them too (`RETURN history.asOf(n, $t)`). Java: `RecordVersioning.of(db)`.

Schema: `ALTER TYPE Identity VERSIONED true`, `DocumentType.setVersioned(true)`. The flag is persisted in
`schema.json` (`"versioned": true`) and inherited by subtypes. Lightweight edge types cannot be versioned (they have
no record to version).

## What it replaces in the SPORK design

| Before | With native versioning |
| --- | --- |
| Ended `MEMBER_OF` edges kept forever with `validFrom`/`validTo`; every traversal filters `validTo IS NULL` | Delete the edge. The current graph holds only live memberships; `history.edges(identity, t)` answers the as-of view. |
| `Assertion` vertices overlaid on ingested values for history | Update the property; `history.of` replays every value with its transaction time. `_prov` still says *who* asserted it. |
| `graph.history(id, asOf)` reassembled from decisions, assertions and ended edges | `history.of` / `history.asOf` / `history.edges` plus the `ResolutionDecision` chain for *why*. |

`ResolutionDecision` stays: it is the audit record of a decision (rule, evidence, actor), not a version store.

## Limits of slice 1 and next slices

- Transaction time only. Application (valid) time, bitemporal corrections, remain modelled as properties.
- Type-level as-of scans (`SELECT FROM T` as of a time) are not in the SQL grammar yet; slice 2 adds
  `history.scan(type, time)` over the history store with an index on `txTo`.
- Versions are full copies of the record image (read speed first). Slice 3 can add delta or compressed images if the
  storage budget needs it; the read API does not change.
- Records written before versioning was enabled have no `_txFrom`; they are treated as valid since the beginning.
- History is not checked against the reader's markings (E13 applies to the history store when it lands).
- EXTERNAL property values are not copied into the history: the image keeps their pointer, and a version read after the
  value was replaced does not show it.
- `DROP TYPE` and `TRUNCATE TYPE` on the history store are not blocked; they need `UPDATE_SCHEMA`, as any schema change.
- The stamp is wall-clock based: a leader whose clock is behind its predecessor's still issues increasing stamps within
  one process (`max(now, last + 1)`), but not across a failover to a node with a slower clock. Slice 2 seeds the counter
  from the newest stamp in the history store on open.

## Cost

- Reads that do not ask for history: unchanged. No extra page, no filter, no index.
- Writes to non-versioned types: one `isVersioned()` check (a field read plus a walk over the super types).
- Create of a versioned record: 9 bytes for `_txFrom`.
- First update or delete of a versioned record in a transaction: one copy of its committed image into the history store,
  plus one `rid` index entry (and `out`/`in` entries for an edge). Further updates in the same transaction: nothing.
- `history.of(rid)` / `history.asOf`: one index lookup plus one read per stored version; only the version returned is
  decoded.
- `history.edges(v, t)`: the vertex's current edge list plus one index lookup per direction.

## Code

| Piece | Where |
| --- | --- |
| Service, write hooks, read API | `engine/.../versioning/RecordVersioning.java`, `RecordVersion.java` |
| Hooks | `LocalDatabase.createRecordNoLock`, `applyUpdate` (both branches, user-visible updates only), `deleteRecordNoLock`; `GraphEngine.moveEdge` |
| Per-transaction stamp and handled records | `TransactionContext.versionStamp`, `versionedRecords` |
| Schema flag | `DocumentType.isVersioned/setVersioned`, `LocalDocumentType` (persisted as `"versioned": true`), `LocalSchema` load, `RemoteDocumentType` |
| SQL | `ALTER TYPE x VERSIONED true|false` (`SQLParser.g4`, `SQLLexer.g4`, `SQLASTBuilder`, `AlterTypeStatement`) |
| Functions | `engine/.../function/sql/history/` registered in `DefaultSQLFunctionFactory` |
| Tests | `engine/src/test/java/com/arcadedb/versioning/RecordVersioningTest.java` |
