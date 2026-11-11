# SPORK day-1 configuration bundle (126 GB read-heavy node)

Generated from `docs/spork/02-retrieval-speed.md` sections 4.1 (C1 to C12) and 5 (JVM and OS tuning) and
`docs/spork/01-architecture.md` sections 1.4 (RAM budget), 3.6 E0 (day-1 settings), 0.4 (compaction policy) and 1.1.
Every `arcadedb.*` key was validated against `engine/src/main/java/com/arcadedb/GlobalConfiguration.java` (key string,
enum constant, scope, compiled-in default); every DDL clause against `engine/src/main/java/com/arcadedb/query/sql/parser`
and the `TypeBuilder` / `IndexBuilder` / `TypeIndexBuilder` Java APIs. Each value carries the doc item it comes from.

## Files

| File | What it is | Applies to |
|---|---|---|
| `read-node.properties` | Every `arcadedb.*` key the docs name, with value, enum constant, scope, default and doc item; keys the docs leave at default are present as comments | n2, n3 (base for every node) |
| `leader-node.properties` | Delta over the read node: `asyncWorkerThreads=2`; notes on backup.json, compaction running on the leader, Raft priority | n1 |
| `olap-node.properties` | Delta over the read node: `ha.serverRole=replica`, 4 GB page cache, parallel-scan defaults, `queryIndexMaxSelectivity=0.6`, build-slice JVM notes | n4 |
| `jvm.options` | `ARCADEDB_OPTS_MEMORY` and `ARCADEDB_OPTS_GC` for n1 to n3 and n4 with the heap arithmetic; why `ARCADEDB_OPTS_HEADERS` stays unset; what is deliberately not set | all |
| `os-tuning.sh` | THP, NVMe scheduler and read-ahead, LUKS/LVM/XFS layout and online expansion, sysctls, cgroups, queue-depth check, as documented commands (not to be run blind) | all |
| `schema-ddl.sql` | Schema v1 (WS2), **generated** from the `spork-keys` installer scripts (`spork-keys/src/main/resources/spork/schema/v1/`, installed by `SporkSchema.install`; `SporkSchemaTest` fails while the two differ). `ALTER DATABASE` page-size and compaction settings per database, `CREATE TYPE ... PAGESIZE` buckets, HASH point indexes (plain SQL at the engine default page sizes since round 6), LSM composites, FULL_TEXT / GEOSPATIAL; Java builder calls only where SQL cannot express the page size (FULL_TEXT, GEOSPATIAL) | per database |

## How settings reach the server

There is no `.properties` loader. `GlobalConfiguration.readConfiguration()` reads each key as a JVM system property and
then as an environment variable of the same name; `bin/server.sh` passes `$ARCADEDB_SETTINGS` verbatim to `java`, and
`ArcadeDBServer.loadConfiguration()` reads `config/server-configuration.json`. Turn a properties file into flags with:

```
export ARCADEDB_SETTINGS="$(grep -v '^#' read-node.properties | grep '=' | sed 's/^/-D/' | tr '\n' ' ')"
```

`SCOPE.DATABASE` keys set this way are the JVM-wide default for every database; per-database values are set with
`ALTER DATABASE \`arcadedb.key\` value` (SQL refuses SERVER/JVM-scoped keys there) and are persisted with the database.

## Keys validated (all exist in GlobalConfiguration)

`maxPageRAM` (MAX_PAGE_RAM, DATABASE, 4096), `initialPageCacheSize` (65535), `freePageRAM` (50, left),
`indexDefaultPageSize` (262144), `bucketDefaultPageSize` (65536), `externalPropertyBucketDefaultPageSize` (262144),
`typeDefaultBuckets` (1, left), `indexCompactionMinPagesSchedule` (10), `indexCompactionFullSeriesThreshold`
(constant INDEX_COMPACTION_FULL_SERIES, 10), `indexCompactionFullDeadEntriesRatio` (1.0), `indexBloomFilterRate`
(0.01, left), `queryLiteralParameterization` (true), `sqlStatementCache` (300), `opencypher.statementCache` (300),
`opencypher.planCache` (300), `server.httpQueryMaxResultRows` (SERVER, 1000000), `server.httpQueryElevatedMaxResultRows` (SERVER, -1 = follow
`httpQueryMaxResultRows`; commented out in `read-node.properties`), `queryIndexMaxSelectivity`
(DATABASE, 0.6), `server.httpWorkerThreads` (500), `server.httpIoThreads` (cores), `queryMaxConcurrent` (JVM, 2 x
cores), `queryQueueMaxSize` (256), `queryQueueTimeout` (30000), `queryParallelismPoolThreads` (JVM, cores),
`parallelScanProducerPoolThreads` (JVM, cores), `queryParallelScanPagesPerUnit` (DATABASE, 32),
`queryScanReadAheadMaxRAM` (JVM, 200), `command.timeout` (DATABASE, 0), `server.httpTerminateOnClientDisconnect`
(true), `polyglotEngineEnabled` (JVM, true), `asyncWorkerThreads` (DATABASE, cores - 1), `profile` (JVM, default),
`graph.ghostEdgeProbe` (true), `graph.supernodeThreshold` (4096), `graph.edgeListInitialChunkSize` (64),
`vectorIndex.searchCacheSize` (DATABASE, 0), `vectorIndex.maxConcurrentRebuilds` (JVM, 1), `vectorIndex.searcherPoolSize`
(0, left), `ha.readConsistency` (SERVER, read_your_writes), `ha.autoAcquireDatabases` (true), `ha.snapshotMaxConcurrent`
(2), `ha.snapshotInstallRetries` (3), `ha.snapshotInstallRetryBaseMs` (5000), `ha.bootstrapTimeoutMs` (120000),
`ha.snapshotCompressionLevel` (1), `ha.logPurgeUptoSnapshot` (true), `ha.staleFollowerRecoveryDurationMs` (60000),
`ha.serverRole` (any; allowed any|replica), `server.readinessRequiresHA` (false), `network.socketTimeout` (SERVER,
30000), `server.securitySaltCacheSize` (64, commented), `server.backupDirectory` (JVM, `${rootPath}/backups`).

## Named by the docs but not a GlobalConfiguration key (dropped or flagged)

| Doc text | Finding | Where handled |
|---|---|---|
| `arcadedb.fullText.topK` (01-architecture 1.4, "database scope, default true") | No such key in GlobalConfiguration; it exists only in the partial-text worktree | Not in any file |
| `rescoreOversample 0` (C11, sparse) | Sparse vector index metadata (`LSMSparseVectorIndexMetadata`), not a config key; sparse is "if ever enabled" | Not in any file |
| `inlineExternalProperties=false` (2.0, M4) | Future M4 item, no key today | Not in any file |
| `VECTOR_INDEX_MAX_CONCURRENT_REBUILDS=1` (E0, C11) | Exists (`arcadedb.vectorIndex.maxConcurrentRebuilds`) and 1 is already the default | read-node.properties, explicit |
| C6 shorthand `httpWorkerThreads`, `httpIoThreads`, `httpTerminateOnClientDisconnect` | Real keys carry the `arcadedb.server.` prefix (5.4 has them right) | read-node.properties |
| `arcadedb-server.properties` (5.4) | No such file format is read by the server; see "How settings reach the server" | README |
| Raft peer priority 10 / 5 / 0 (1.1) | Not a GlobalConfiguration key; `ha.serverRole=replica` is the only priority lever (sets 0) | olap-node.properties |
| Auto-backup schedule, directory, retention (E0) | `config/backup.json` via `server/backup/AutoBackupConfig` (fields: enabled, backupDirectory, defaults{schedule{type, frequencyMinutes}, retention{maxFiles}}, databases{}) | leader-node.properties notes |
| `storeVectorsInGraph=true`, INT8 inline, delta cap (E0, C11, 2.8) | Per-index vector metadata, not config; not validated in this pass | schema-ddl.sql note |

## Items that could not be expressed, and why

1. **`arcadedb.graph.edgeListInitialChunkSize=2048` on shared identifier vertices only (C7; 2.1 says "per vertex at
   creation through the GraphEngine API").** The setting is declared `SCOPE.DATABASE` but
   `LocalDatabase.getNewEdgeListSize` reads `GlobalConfiguration.GRAPH_EDGE_LIST_INITIAL_CHUNK_SIZE.getValueAsInteger()`,
   the JVM-wide static value, and `GraphEngine.createInEdgeChunk` / `createOutEdgeChunk` take no size. There is no
   per-vertex, per-type or per-database scope today, and 2048 everywhere costs +85 GB per partition by 2.1's own
   arithmetic. Left at 64 and flagged as an engine gap (a per-vertex first-chunk size API is needed before C7's value
   can ship). Round 6: both docs now say so; the engine item is 02 4.2 S18 / 01 3.6 E24 (per-type first and second chunk
   sizes, 0.5 to 1 ew).
2. **HASH page size from SQL.** `CREATE INDEX ... UNIQUE_HASH|NOTUNIQUE_HASH` has no PAGESIZE clause and the HASH default
   is 16 KB only for STRING/BINARY/DECIMAL keys and 4 KB for LONG keys (`HashIndexBucket.defaultPageSize`). The LONG-keyed
   point indexes (`Record.record_id`, `Record.upstreamRid`, `RecordDir.upstreamRid`, `Place.placeId`) were therefore
   written as `IndexBuilder.withPageSize(16384)` Java calls. Round 6 kept the engine defaults instead (the engine's own
   #5712 benchmark picked 4 KB, and a cold LONG-key probe moves 4 KB instead of 16), so those indexes are now plain SQL in
   schema-ddl.sql and no HASH index needs Java.
3. **FULL_TEXT and GEOSPATIAL at 64 KB.** `arcadedb.indexDefaultPageSize` applies only to plain LSM_TREE indexes
   (`IndexBuilder.getLsmTreePageSize`); full-text and geo builders use `getPageSize()` whose default is 256 KB. Java
   builder calls with `withPageSize(65536)` are given.
4. **`indexCompactionFullSeriesThreshold=3` "only on indexes that can reach 1 series" (C5).** The key is per database,
   not per index; set to 3 on `identity` and 0 everywhere else was the closest the engine could express. Round 6 rejected
   the value itself (doc errors 3): 0 on every database, schedule 64.
5. **`graph.ghostEdgeProbe=false` on sealed partitions after `CHECK DATABASE ... FIX` (C7).** Per database and
   time-dependent; given as an `ALTER DATABASE` to run at seal, not in the JVM-wide file.
6. **n4 `initialPageCacheSize`.** Not stated by either doc; derived as 2,000,000 x 4 / 23 = 348K, rounded to 400,000.
7. **The `recdir` type name.** The docs name the database and the index, not the document type; `RecordDir` is a
   placeholder.
8. **Lightweight `HAS_*` edge types, StyleVec `LSM_VECTOR` metadata, the ontology registry types.** Out of this bundle's
   scope (not point-lookup indexes or bucket page sizes); noted in schema-ddl.sql.

## Doc errors and conflicts found

Every item below was resolved in round 6 (01-architecture.md Appendix A "Round 6", 02-retrieval-speed.md Appendix B); the
decision is appended to each item and the files in this directory carry it.

1. **n4 heap.** 02-retrieval-speed 5.2 header: "n4 with -Xmx46g, -Xms46g, SoftMaxHeapSize=42g". 01-architecture 1.4:
   n4 heap 43 GB (`-Xmx43g`), and only 43 sums to 126 (6 + 43 + 3 + 4 + 45 + 3 + 16 + 6; with 46 it is 129). The bundle
   uses 43g / 38g. **Resolved:** 02 5.2 now says 43g / 38g.
2. **OS file cache.** 5.1 ("OS file cache 12 = 126") and 3.1 ("OS page cache, 12 GB on n1 to n3") predate 1.4's round 4,
   which moved 3 GB to the encoder sidecar (OS cache 9 on n1 to n3, 6 on n4). 5.1's sum omits the sidecar. **Resolved:**
   02 3.1, 5.1 and 5.3 now say 9 GB (6 on n4) with the sidecar in the sum.
3. **Compaction policy conflict.** 02-retrieval-speed C5 and 2.0: `indexCompactionMinPagesSchedule=4`,
   `indexCompactionFullSeriesThreshold=3` on `identity`, 0 on `rec_<p>`. 01-architecture 0.4, 1.1 and E0:
   `indexCompactionFullSeriesThreshold=0` and `indexCompactionFullDeadEntriesRatio=0` everywhere,
   `indexCompactionMinPagesSchedule=64` on `recdir`, `identity` and the current period, sealed partitions keep 10. The
   bundle follows the retrieval doc (4, 3 on identity, 0 elsewhere) and adds the architecture's dead-entries ratio 0
   (without it threshold 0 does not stop automatic full compaction). The architecture's Raft-bytes argument for 64 and
   for 0 on `identity` still applies to the LSM indexes left in `identity` after C8; the owner should pick one.
   **Resolved:** the architecture's policy everywhere (threshold 0, dead-ratio 0, schedule 64 on `identity` and the
   current period, leased full compactions: every LSM index at seal, weekly current FULL_TEXT and `identity`). Each
   incremental round appends one series, so schedule 4 would leave a live composite 892 series a week against 56 at 64 for
   60 fewer binary searches per cursor open and +6 GB/day of Raft log; threshold 3 is a no-op on tiny `identity` indexes
   and an unbounded rewrite on the growing ones (AuditEntry composites: 1.5 GB/day and rising). read-node.properties and
   schema-ddl.sql updated (64, 0 everywhere).
4. **Point-lookup index type.** 01-architecture 2.1 (table header "Key (UNIQUE LSM_TREE)"), 0.4 (series walk, bloom
   pages, "point-lookup indexes created at 16 KB through the Java builders") and 1.4 ("resident bloom and series-root set
   of the point-lookup indexes") describe LSM point indexes; 02-retrieval-speed C8 and 2.0 make every exact key HASH,
   which has no series, bloom or compaction state. The bundle follows C8; the 0.4 weekly full compaction of the
   `identity` key indexes and `recdir` (27 GB each) no longer applies to HASH indexes. **Resolved:** HASH adopted in the
   architecture (2.1, 0.4, 1.4, 2.5, section 4, D39, D41, D52): 3 cached touches + 1 cold read against 1 + 2S + 2 (S = 519
   to 1,609 for `recdir`), under 0.25 GB of resident directories against 3.8 GB of blooms, no compaction through Raft.
5. **Bucket page size.** 01-architecture 0.4 and 2.1: Record, recdir and TextBody buckets at 16 KB (hydrate 131 MB in
   1.4). 02-retrieval-speed C9 and 2.0 (D39 revised): Record and TextBody 32 KB (a v0 page spends 8,202 B on header and
   slots, so 16 KB holds 13 rows), recdir and identifier vertices 16 KB (hydrate 160 MB). The bundle follows C9.
   **Resolved:** C9's buckets (32 KB v0 until M20, 16 KB v2 after) in both docs, and HASH at the engine defaults instead of
   a uniform 16 KB: an upstream RID is 4 + 16 + 4 + 32 = 56 KB, a 2,000-id hydrate 112 MB (72 by `record_id`, 80 and 40
   after M20).
6. **SQL page-size clause.** Both docs say "SQL CREATE INDEX has no page-size clause" (true) and route bucket sizes
   through `TypeBuilder.withPageSize` and `bucketDefaultPageSize`; they omit that `CREATE [VERTEX|DOCUMENT] TYPE ...
   PAGESIZE n` exists and reaches `withPageSize` (CreateVertexTypeStatement.java:42-53). Buckets need no Java.
   **Resolved:** stated in 01 0.4 / D39 and 02 2.0 / C9, with `IndexBuilder.withPageSize` only for FULL_TEXT and GEOSPATIAL.
7. **HASH default page size.** Neither doc states it; it is 4 KB for fixed-width keys and 16 KB for variable-width keys,
   maximum 64 KB (`HashIndexBucket.DEF_PAGE_SIZE`, `DEF_VARIABLE_KEY_PAGE_SIZE`, `MAX_PAGE_SIZE`). C9's uniform 16 KB
   needs the builder for LONG keys. **Resolved:** both docs state the defaults and keep them (see item 5).
8. **`edgeListInitialChunkSize` per vertex (2.1).** No such API exists; see item 1 above. **Resolved:** 02 2.1, C7 and
   risk 9 corrected (64 B on day 1); engine item S18 / E24 added with a 0.5 to 1 ew estimate.
9. **`arcadedb.fullText.topK` (1.4).** Not a key in GlobalConfiguration (worktree only). **Resolved:** marked
   worktree-only in 01 (1.4, executive summary, section 4, D28); 02 never named it.
10. **Already-default values presented as settings.** `queryLiteralParameterization=true`, `vectorIndex.maxConcurrentRebuilds=1`,
    `ha.readConsistency=read_your_writes`, `ha.autoAcquireDatabases=true`, `typeDefaultBuckets=1` are the compiled-in
    defaults (the docs acknowledge the first three); kept explicit in the files. **Resolved:** every one is marked
    "already the default" in both docs (01 E0, 1.1, 3.4; 02 C2, C4, C11, 5.4); the files keep them explicit.
11. **Line references.** `GlobalConfiguration.java` line numbers cited in the docs are close but drift (e.g. C6 cites
    341-360 and 910 for the thread keys and command timeout, which match; C1 cites 270-271, matches; 2.0 cites
    2507-2509 for `ha.readConsistency`, matches). No cited key was missing. **Resolved:** no change needed.
12. **Hamming band indexes (added in round 6 from `06-stylometry-perceptual-hash.md` 3.1).** No corpus-wide band index:
    PDQ 8 bands cost 144 B per distinct image (194 GB at mid 3x next to S-PDQ's 54 GB), pHash 2 bands 49 GB. PDQ bands only
    on the hot-window `media_<p>` and case databases (24 GB today, 43 GB at 3x, in the model as `HOT_BAND_B`); the recipe is
    WS12's (round 10: schema v1 leaves `pdq`, `pdqBands` and their hot-window index to the WS12 installer, see `media.sql`).
