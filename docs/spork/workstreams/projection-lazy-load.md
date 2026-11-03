# Workstream: one header pass per projected row, lazy index-hit vertices

Status: implemented in worktree `worktree-agent-a92e8710383089f2f` (branch of the same name), 2026-11-02; tests and
benchmarks re-run 2026-11-03. Items S4, S5 (openCypher half) and the first slice of M4 of
`docs/spork/02-retrieval-speed.md` section 4. Measured numbers are in section 3; what was deliberately not done, and why,
is in section 4. S9 (read-only HTTP commands skipping the auto-commit wrapper) was implemented, reviewed three times and
then removed in round 4: see section 7.

## 1. What changed

### S4: one header pass per projected row

Reading a property of a record off its bytes walks the record header from the start until it meets the property
(`BinarySerializer.deserializePropertyOf`). A row that projects N properties paid N walks (`ResultInternal.getProperty`
-> `ImmutableDocument.get`), and a serializer that iterated `getPropertyNames()` then `get(name)` paid N + 1. The
engine primitive for one walk (`locateProperties` + `getPropertyAt`, #9496) had one consumer, the GROUP BY evaluator.
It now has these:

| Path | File | Change |
|---|---|---|
| SQL projection step | `engine/.../query/sql/executor/ProjectionCalculationStep.java` | wraps each row in the existing `PropertyCachingResult` when no expression of the projection can hand the row out (the `isSafeOnView` contract moved to `PropertyCachingResult` and is shared with `AggregateRowEvaluator`): no function call, nested projection, sub-query, `*` or `@this`-alone. A `*` item (`ProjectionItem.all`, no expression, so no node the AST check sees) is refused explicitly (`hasStarItem`, round 4): its branch reads the row whole and already reads a plain row in one pass, see the next line. One view per step (one per execution, one thread). `$current` stays the scan's row, never the view |
| SQL `SELECT *` | `engine/.../query/sql/parser/Projection.java` | the `*` branch reads a plain `ResultInternal` row through `toPropertyMap(false)` (one `deserializeProperties` pass, the reading `INSERT ... FROM SELECT` takes since #10098) instead of `getPropertyNames()` + `getProperty()` per name |
| openCypher `RETURN` / `WITH` | `engine/.../query/opencypher/executor/steps/BoundPropertyReads.java` (new), `ProjectReturnStep.java`, `WithStep.java` | the plain `n.a, n.b, n.c` items that read one bound `ImmutableDocument` are located in one `locateProperties` walk per row and read with `getPropertyAt`; the value goes through the new three-argument `TemporalUtil.convertFromStorage(document, name, value)`, the same reading a single `n.a` gets. Any other item, and any row whose variable is bound to something else (a mutable record, a RID, a map), is evaluated exactly as before |
| JSON serializer, element rows | `engine/.../serializer/JsonSerializer.java` | `serializeResult` reads a plain element row (`ResultInternal`, element set, no projected content) through `toPropertyMap(false)`; type hints, declared-type temporal precision, record attributes and tombstones are unchanged |
| Bolt | `bolt/.../BoltStructureMapper.toProperties` | `document.toMap(false)` instead of names then `get`, for a record-backed document (`BaseDocument`: immutable, mutable, detached, embedded, edges and light edges) only; any other `Document` (a `GAVVertex`) keeps names then `get` (round 5) |
| gRPC | `grpcw/.../ArcadeDbGrpcService.convertToGrpcRecord` | same, with the same `BaseDocument` guard |
| Postgres | `postgresw/.../PostgresNetworkExecutor.columnValue` | the DataRow writer and `COPY ... TO STDOUT` read a plain element row once per row (`storedProperties`) and answer every column off that map; a null from the map (a name the row lacks, or a property removed from the row, a tombstone) still takes the element fallback the per-column lookup had (round 4); any other row keeps the per-column lookup |

Kill switch: `arcadedb.queryProjectionPropertyCache` (new `GlobalConfiguration`, SCOPE.DATABASE, default `true`) turns the
SQL view, the SQL `SELECT *` one-pass branch (`Projection.calculateSingle`) and the Cypher one-pass reads off; the rows are identical either way, which the regression tests assert by
running every shape under both settings. The serializer changes are not behind the flag: they are a different reading of
the same record with no row-shape decision in them.

### M4, first slice

`Projection.calculateSingle` now reaches `locateProperties` for N fields through the view above; the dictionary ids of the
projected names are cached once per execution in the view (the dictionary is per database, so there is nothing per
`DocumentType` left to cache: a name has one id). `inlineExternalProperties=false` does not exist in this tree
(`grep inlineExternalProperties` finds nothing) and is NOT added here: a whole-record `RETURN n` or `SELECT FROM` on a
type with EXTERNAL properties still reads them inline. Deferred, see section 4.

### S5: lazy record load on index hits (openCypher half)

`NodeIndexSeek` binds `identifiable.asVertex(false)`: the vertex is handed out with its content unloaded, and whatever the
statement reads of it (a property, the pushed-down filter, the adjacency of an expansion, a serializer) loads it on first
use. `FetchFromIndexStep` lines 188-202 were verified: the step emits `key` and `rid` only and loads nothing.

What the load did besides copying the content is still done at the seek (`NodeIndexSeek.mayHandOutUnloaded`, added after
the re-review, see section 6): the READ_RECORD permission on the vertex's bucket is asked, and a database or type with a
before-read or after-read listener or trigger keeps the eager load. The answer is cached for the last bucket seen, so a
seek that stays in one bucket pays two volatile reads and one int compare per row.

Two things bound what this buys today, both pinned by `NodeIndexSeekLazyVertexLoadTest`:

- a whole-key seek of up to 256 entries is read by `ConsistentKeyLookup` with its records loaded against one committed
  state (#9369, #9397). That is a correctness contract (the entry and the record belong together) and is left alone;
  the lazy path serves prefix seeks on composite keys, ranges, and whole keys of more than 256 entries;
- the planner pushes the seek's own equality back down as a filter on the vertex (`n.group = 1` re-evaluated per row,
  #8537: the index key conversion is not Cypher's `=`), so a planned `MATCH (n:T {k: $v}) RETURN count(n)` still loads
  each vertex to re-check the key. The operator is lazy; the planned statement is not yet. Evaluating that one conjunct
  on the index key instead of the record is the follow-up that would make the saving real (section 4).

## 2. Tests

Regression tests, all green in this worktree:

- `engine`: `ProjectionOneHeaderPassTest` (values, mutable values not shared between rows, every shape equal under both
  settings, `SELECT *` attributes and exclusions, `RETURN AFTER` rows), `CypherReturnOneHeaderPassTest` (values,
  temporals, every shape equal under both settings, RID- and map-bound variables), `NodeIndexSeekLazyVertexLoadTest`
  (a prefix seek loads nothing until a property is read, read off the vertex's buffer; a sub-type seek rejects the other
  buckets without loading; a whole-key seek is still read consistently; a planned seek loads what its filter needs; a
  database or type read listener sees every vertex the seek hands out), `NodeIndexSeekReadPermissionTest` (a user
  without READ_RECORD is refused by a prefix seek), `JsonSerializerElementRowOnePassTest` (names, order, precision, attributes, tombstones, reshaped rows).
- `postgresw`: `PostgresElementRowColumnValueTest` (round 4: a plain element row answers the record's values and
  attributes; a property removed from the row falls back to the record as the per-column reading did).
- The S9 tests (`HttpReadOnlyCommandNoAutoCommitIT`, `SqlAstInspectorCallsOnlyBuiltInsTest`) were removed with S9 in
  round 4 (section 7). The runs listed below for `server` predate that removal; the round 4 runs are in section 7.
- Existing suites, all run in this worktree on 2026-11-03 (`mvn -o`):
  - `engine`: every test under `query/sql/executor`, `query/sql/parser`, `query/opencypher` and `serializer`:
    11,790 run, 0 failures, 0 errors, 18 skipped.
  - `server` (`-pl server -am`): every `*Test` under `server/http` plus `HttpReadOnlyCommandNoAutoCommitIT`,
    `Issue7306HttpStreamingQueryIT`, `Issue7571GetQueryStreamingReadOnlyGateIT`, `Issue5026TransactionSessionLifecycleIT`,
    `Issue7575ExplainContractIsTheSameOnEveryQueryOperationIT`, `Issue8235QueryNdJsonErrorStatusIT`: 1,403 run, 2 failures,
    neither from this change. `PostBatchHandlerForwardRequestTest.theBodyCanOnlyBeHandedOverOnce` fails on every run: it
    exercises `PostBatchHandler.buildForwardRequest` and the JDK's `BodyPublishers.ofInputStream`, neither touched or
    reached by this diff (pre-existing). `Issue7603LeaderForwardHopTest.aStreamThatStallsMidwayIsGivenUpOn...` is a 1 s
    deadline test that failed once under load and passed on both re-runs (flaky, `LeaderCommandForwarder`, not touched).
  - `grpcw` (`-pl grpcw -am`): every `*Test` under `server/grpc` plus `ArcadeDbGrpcServiceCoverageIT` and
    `Issue4197GrpcExecuteQueryResultSetLeakIT`: 453 run, 0 failures.
  - `bolt`: the module's test dependencies (`neo4j-java-driver`) are not in the offline repository, so Maven cannot run it.
    `BoltStructureTest` and `BoltDateTimeStructureTest` (the `toProperties` path) were compiled and run with the JUnit
    launcher against the module's classes: 63 run, 0 failures. The driver-based ITs were not run.
  - `postgresw`: depends on the shaded `arcadedb-gremlin` artifact, not in the offline repository, so Maven cannot build
    it. Its main sources were compiled with `javac` against the server classpath (clean); no Postgres test was run, the
    JDBC driver is not in the offline repository either. The `columnValue` change is NOT covered by a run test.

Benchmark (`@Tag("benchmark")`): `engine/src/test/java/performance/ProjectionHeaderPassBenchmark.java`. The S9 benchmark
(`HttpReadRequestOverheadBenchmark`) was removed with S9 in round 4.

## 3. Numbers

One development machine (macOS, Java 21, warm page cache: every page in the heap cache, so this is the CPU side of the
design document's cost model, not the cold I/O side). Other worktrees were building on the same machine, so only the
pairs are comparable, never two rows of different runs: every "before / after" below is measured as ALTERNATING pairs
in one run (each measured iteration runs one of each arm, the arm that goes first alternating too), 3 warm-up pairs and
the median of 7 measured. 100,000 vertices of 30 properties (10 STRING, 10 INTEGER, 10 DOUBLE) read through a unique LSM
index on `key` (`ProjectionHeaderPassBenchmark`). "before" is `arcadedb.queryProjectionPropertyCache=false` (the
per-property reading), "after" is the default. CPU is the thread's CPU time, alloc the bytes the thread allocated (the
record copy and the decoded values: the header walks allocate nothing, which is why the column does not move on the
projection rows). Two runs, both shown, because the machine was loaded:

| Shape | run 1 cpu ms before / after | run 2 cpu ms before / after | CPU change | alloc MB (both arms) |
|---|---|---|---|---|
| SQL 3 of 30: `SELECT f03, f11, f27 ... WHERE key range` | 112 / 106 | 74 / 73 | -1 to -5% | 168 to 180, unchanged |
| SQL 3 of 30, repeated at the end of the run | 74 / 73 | 76 / 74 | -1 to -3% | unchanged |
| SQL 10 of 30 | 172 / 144 | 129 / 110 | -15 to -16% | 243 to 255, unchanged |
| SQL expression: `f04 + f07, f01.toUpperCase(), f29` | 101 / 99 | 95 / 90 | -2 to -5% | unchanged |
| SQL 30 of 30: `SELECT *` | 412 / 404 | 397 / 396 | 0 to -2% | unchanged |
| SQL `SELECT *`, repeated at the end of the run | 285 / 285 | 482 / 479 | 0 to -1% | unchanged |
| openCypher 3 of 30: `RETURN n.f03, n.f11, n.f27` | 72 / 68 | 99 / 96 | -3 to -6% | 243 to 255, unchanged |
| openCypher `WITH n.f03 AS a, n.f11 AS b, n.f27 AS c RETURN a, b, c` | 87 / 81 | 101 / 95 | -6 to -7% | unchanged |
| Element row read as the serializers do it (JSON, Bolt, gRPC, Postgres): names then `get` per name (before) vs one `toPropertyMap(false)` pass (after) | 345 / 166 | 282 / 132 | -52 to -53% | 750 / 564 and 762 / 576: -25% |

Reference rows, one arm only: the full `JsonSerializer.serializeResult` of the same 100K element rows costs 306 and 509
ms CPU in the two runs (the JSON object building dominates, the property read above is now about a third of it), and the
SQL `count(*)` over the same index hits costs 8 to 11 ms (about 130 ns per hit, 3 to 5 MB): the planner already answers
that shape without loading records.

What the numbers say against the design document's estimate ("a projected 20-field row from about 6 us of header walks
to about 1 us"): the header walk of a 30-property record costs 100 to 200 ns per property here, not 300, and the record
copy (`copyOfContent`), the `ResultInternal` and its map and the decoded values are most of the 700 to 1100 ns a 3-field
row costs. So the projection-side walk is a small slice at 3 fields (1 to 6%, inside the noise band of this machine) and
grows with the number of fields read (10 of 30: 15%). The serializer-side change is the big one: reading an element row
the way every wire serializer did (names, then a header walk per name) costs twice what one pass costs, and allocates a
quarter more. `SELECT *` does not move, because its row was already built by one pass upstream and the per-name reads it
replaced were reads of the row's map, not of the record. The openCypher rows gain 3 to 7%: their per-row cost is the
seek's pushed-down filter and the two row copies (`ProjectReturnStep` copies every input binding, `FinalProjectionStep`
copies again), not the property reads. The `count(*)` row shows the deferred SQL half of S5 has a smaller prize than the
document's P4 count arithmetic assumes; its remaining prize is `@rid`-only and DISTINCT-by-RID projections.

The S9 HTTP numbers (wrapper on and off, `/command` against `/query`) were measured and showed no end-to-end gain: the
wrapper skip was inside the run-to-run band, and in process the wrapper was never the cost. They went with S9 in round 4
(section 7).

## 4. Deferred, and why

| Item | Why not here | What it would take |
|---|---|---|
| S5 in SQL (`GetValueFromIndexEntryStep` `lookupByRID(rid, false)`) | the eager load is the mechanism of #9369 and #9902: the step loads every record between two samples of the page manager's publication sequence and reconciles an entry whose record is gone under the publication lock. A lazy load would emit the row first and read the content later, so a slot freed and reused between the two would serve another record's content under the entry's key, exactly the race those issues closed. Deferring the load is safe only when the plan provably never reads the record | the planner telling the step that nothing downstream reads record content (`@rid`-only projections, DISTINCT by RID, a `count(*)` with a residual predicate on indexed columns), which is the covering-index projection of M3. The benchmark's `count(*)` row shows the plain indexed count is already served without record loads, so the prize is the other shapes |
| S5 in openCypher for planned equality seeks | the pushed-down equality re-reads the key property off the vertex (#8537) | evaluate the conjuncts that read only indexed properties against the cursor's key (`IndexCursor.getKeys()`), with Cypher `=` semantics on the typed key, and leave the record untouched when they are the whole filter |
| `inlineExternalProperties=false` | the flag does not exist; a read of an EXTERNAL property is one more bucket read (`readExternalValue`) and `SELECT FROM` / `RETURN n` inline every property | a per-database setting read by `BinarySerializer.deserializeProperties` (and `toMap`, `toJSON`) that answers a marker for an EXTERNAL property not named by the projection; the projection view already reads only the named ones, so `SELECT a, b FROM T` never touches an EXTERNAL `c` today |
| Functions in the SQL projection view | a function receives the row and may keep or answer it; the contract of `PropertyCachingResult` refuses every `FunctionCall` | an allow-list of built-in scalar functions that read their arguments only (the same shape `AggregateRowEvaluator.isBuiltInAggregate` has for aggregates) |

## 5. Risks

- `SELECT *` on a record with a corrupted property: `deserializeProperties` skips the property with a warning, where the
  per-name `get` answered `null` for it. The column is absent instead of null on such a record. Every other row is
  byte-for-byte what it was (asserted under both settings).
- A `Result` implementation other than `ResultInternal` (a subclass, a GAV row) keeps the name-by-name reading on every
  path: the one-pass branches check the exact class. The Bolt and gRPC record converters take the one-pass read only for
  a `BaseDocument`, so a `GAVVertex` (whose `get` reads the view's snapshot and whose `toMap` reads the live record) is
  read name by name there too (round 5).
- The lazy vertex of a Cypher seek widens the window between the index entry and the record read from nanoseconds
  (eager load) to the life of the row in the operator's batch. The whole-key path is unaffected (consistent lookup);
  the prefix and range paths had no consistency guarantee before either. Concretely, under READ_COMMITTED: a vertex
  deleted by a concurrent commit after its entry was read used to fail the statement at the seek
  (`RecordNotFoundException` out of `asVertex()`); it is now counted, and fails the statement with the same exception
  when a property of it is first read, possibly after ORDER BY or `collect()` buffered the result. A slot freed and
  reused by a new record of the same bucket inside that window serves the new record's content. See section 6.
- Lifecycle: a vertex an embedded caller keeps from a seek's result set and reads after the transaction or the database
  closed loads on that first read: it raises `DatabaseIsClosedException`, or reads the state of that later moment. This
  is the contract every vertex a Cypher expansion already hands out has (`GraphTraverser.vertex`, `VertexIterator`,
  `ImmutableEdge.getIn/getOut` all use `lookupByRID(rid, false)`), so code that keeps vertices past their statement
  already had to read them first; it is a change only for vertices reached through a prefix, range or large whole-key
  seek.
- The Postgres `columnValue` change is covered by `PostgresElementRowColumnValueTest` and the module's other unit tests
  (run with the JUnit launcher in round 4, section 7), not by a wire test: Maven cannot build the module offline. It
  answers `stored.get(name)` for a plain element row and takes the old element fallback whenever that is null, so it
  differs from the per-column reading only in how a non-null value is read, but `PostgresProtocolIT`, `PostgresWJdbcIT` and
  the `COPY ... TO STDOUT` ITs (`Issue7188CopyToStdoutIT`, `Issue7470CopyToStdoutSparseTypeIT`) must run green on a machine
  with the dependencies before this lands. The same holds for the Bolt driver ITs (`BoltProtocolIT`,
  `Bolt4907TemporalOutputIT`).

## 6. Review findings and fixes

Rows and paragraphs below about `PostCommandHandler`, `requiresTransaction(database, payload)`, `hasPureAnalyze`,
`analyzeExecutesNothing`, `isReadOnlyStatement`, `callsOnlyBuiltIns` and `HttpReadOnlyCommandNoAutoCommitIT` describe S9
code that no longer exists: it was removed in round 4 (section 7). They are kept as the record of why.

An independent re-review (2026-11-03) found one blocker and one major; both are fixed, each with a regression test that
was run red against the unfixed line and green against the fix.

| Severity | Finding | Fix | Regression test |
|---|---|---|---|
| blocker | `PostCommandHandler.requiresTransaction(database, payload)` denied the verdict only to `sqlscript` and `gremlin`, so every other language was analyzed in `determineAtomicTransaction`. `PolyglotQueryEngine.analyze()` analyzes by RUNNING the script (`engine.eval`), so a `js` command ran twice: once outside any transaction, before the admission gate, unregistered as a running query (not terminable) and before the HA read-consistency context, then again for real. An insert in the script inserted two records, the first committed by its own implicit transaction | an allow-list: the verdict is asked only of `sql`, `opencypher` and `cypher` (`hasPureAnalyze`), every other language keeps the wrapper without being analyzed | `HttpReadOnlyCommandNoAutoCommitIT.aJsCommandRunsExactlyOnce` (a `js` command inserting a record leaves exactly one; with the deny-list it leaves two, measured), `onlyTheLanguagesWithAPureAnalyzeAreAsked` |
| major (a) | `NodeIndexSeek` handing out `asVertex(false)` skipped what `LocalBucket.getRecord` did on the eager load: the READ_RECORD check and the before/after-read events. A user without READ_RECORD on a type got counts and RIDs from a seek whose rows are only counted or identified, where the statement used to fail with `SecurityException`; a read listener that hides or replaces records was bypassed the same way | `NodeIndexSeek.mayHandOutUnloaded`: `checkPermissionsOnFile(bucket.getFileId(), READ_RECORD)` before the first unloaded vertex of each bucket (cached for the last bucket), and the eager load whenever the database or the vertex's type has a before-read or after-read listener or trigger, the same guard `SQLFunctionVectorNeighbors.deferredRecordReads` uses | `NodeIndexSeekReadPermissionTest` (a prefix seek by a user granted everything but READ_RECORD throws `SecurityException`; a user with it gets the rows), `NodeIndexSeekLazyVertexLoadTest.aDatabaseReadListenerSeesEveryVertexTheSeekHandsOut`, `aTypeReadListenerSeesEveryVertexTheSeekHandsOut`, `aSubTypeSeekRejectsTheOtherTypesWithoutLoadingThem` (four tests red without the fix) |
| major (b) | a vertex deleted by a concurrent READ_COMMITTED commit after its entry was read is not failed at the seek, and a reused slot can serve another record's content | documented, not changed (section 5). The eager path did not skip such a vertex either: it failed the statement at the seek, where the lazy path fails it at the first property read. Skipping it at the seek would need an existence probe of the slot (`LocalBucket.existsRecord`), which reads the record's page and costs most of what the lazy path saves; catching the exception where the content is first loaded would mean every Cypher property read, filter and serializer. The same window already exists for every vertex an expansion hands out | none (no behaviour change) |
| major (c) | embedded callers that keep returned vertices past their transaction or database now load them later | documented (section 5): the same lifecycle every expansion's vertices already have | none (no behaviour change) |

Because a registered after-read listener now turns the eager load on, `NodeIndexSeekLazyVertexLoadTest` no longer counts
loads with a listener to prove the lazy path: `aPrefixSeekHandsItsVerticesOutUnloaded` reads the vertex's buffer
(`BaseRecord.getBuffer()`, null until the content is read) instead. The tests that still register the counter now
assert what a listener sees.

Runs after the fixes (`mvn -o`, this worktree, 2026-11-03):

- `engine`, every test under `query/opencypher`: 8,273 run, 0 failures, 11 skipped. Under `event`, `security`, `index` and
  `database/Issue*`: 2,917 run, 1 failure, `LSMVectorIndexConcurrentRebuildVisibilityTest` (a `@Tag("vector")` class that
  convoys on the JVM-wide rebuild semaphore, reads no Cypher and no HTTP); it passed on both isolated re-runs.
- `server` (`-pl server -am`), every `*Test` under `server/http`: 1,403 run, 1 failure, the pre-existing
  `PostBatchHandlerForwardRequestTest.theBodyCanOnlyBeHandedOverOnce` (section 2). ITs `HttpReadOnlyCommandNoAutoCommitIT`,
  `Issue7306HttpStreamingQueryIT`, `Issue7571GetQueryStreamingReadOnlyGateIT`, `PolyglotScriptingAuthorizationIT`,
  `AsyncScriptingAuthorizationIT`, `Issue5026TransactionSessionLifecycleIT`, `Issue8235QueryNdJsonErrorStatusIT`: 51 run,
  0 failures.

Not done, and why:

- The streaming encoding analyzing a `js` command before refusing it: fixed after the second review, see below.
- No benchmark re-run for the NodeIndexSeek guard. It adds two volatile reads and one int compare per row on a seek that
  stays in one bucket (plus a type lookup, a bucket lookup and the permission check on the first row of each bucket),
  against the 700 to 1,100 ns per row measured in section 3; there is no switch to run it as an alternating A/B pair
  in one JVM, and two separate runs are not comparable on this shared machine.

### Second re-review (2026-11-03)

A second independent re-review found two majors; both are fixed, each with a regression test run red against the
unfixed code (3 of the 13 tests of `HttpReadOnlyCommandNoAutoCommitIT` failed: `expected 0 but was 1` twice and the
wrapper reported off where it must be on) and green after.

| Severity | Finding | Fix | Regression test |
|---|---|---|---|
| major | SQL's read-only verdict never looks at what a called function does (`SelectStatement.isIdempotentItself()` is always true). With `DEFINE FUNCTION audit.log "INSERT ..." LANGUAGE sql`, `POST /command` of ``SELECT `audit.log`() FROM Item`` was judged read-only and ran without the wrapper: each call's `INSERT` committed its own implicit transaction, so a failure on row k left the k-1 earlier writes committed, and a conflict was not retried | `PostCommandHandler.requiresTransaction(database, payload)` keeps the wrapper for a SQL statement that calls any function or method the engine does not ship: `Statement.callsOnlyBuiltIns()` answers it off the cached statement (the same statement-cache entry the verdict just looked up): the function and method calls of the tree are collected once per statement with the existing `allNodesMatch` walk (`SqlAstInspector.callsOf`, kept like the statement's `placeholders`) and only re-resolved per request, reusing the built-in test of `isParallelSafe` (`DefaultSQLFunctionFactory.isBuiltIn`, `DefaultSQLMethodFactory.isBuiltIn`). `expand(...)` counts as built-in (the planner's projection, `FunctionCall.isExpand`); a namespaced function of the unified `FunctionRegistry` does not (applications can register there, and a function library of the same namespace wins over it in `SQLQueryEngine.getFunction`), so such a read keeps the wrapper as it did before S9. openCypher needed nothing: `SimpleCypherStatement.anyUnresolvedFunctionCall` already refuses the read-only verdict | `HttpReadOnlyCommandNoAutoCommitIT.aSelectWhoseFunctionWritesLeavesNoWriteBehindWhenALaterCallFails` (five rows each calling a `DEFINE FUNCTION` that inserts the same key into a unique index: 1 record left behind without the fix, 0 with it), `aReadCallingAFunctionTheEngineDoesNotShipKeepsTheWrapper`, `SqlAstInspectorCallsOnlyBuiltInsTest` |
| minor (found while fixing the above) | `PostQueryHandler` inherits `requiresTransaction(database, payload)`, so a `POST /query` statement the verdict did not clear would have been WRAPPED, against #8775. Before the fix above only a write could take that branch, and `database.query` refuses a write anyway; with the fix, any `/query` read calling a user function would have been | the per-request verdict only ever takes the wrapper off: it returns the handler's default when that default is no wrapper | `aQueryIsNeverWrappedWhateverItCalls` |
| major | a streamed request (`Accept: application/x-ndjson`) in `js` (or any polyglot language) reached `requireStreamableStatement` -> `isReadOnlyStatement` -> `PolyglotQueryEngine.analyze()`, which runs the script (`engine.eval`) outside any transaction before refusing the stream: the client was told "refused" about a write that happened (pre-existing since #7306) | `isReadOnlyStatement` asks `analyze()` only of a language on the allow-list `AbstractQueryHandler.analyzeExecutesNothing` (`sql`, `sqlscript`, `opencypher`, `cypher`, `gremlin`, `mongo`, `graphql`, `redis`: each parses or classifies the text, none executes it) and answers false for every other one, so the stream is refused before anything runs, for `POST /command`, `POST /query` and `GET /query` alike. `hasPureAnalyze` moved to `AbstractQueryHandler` beside it. The refusal message is unchanged: a polyglot or `java` analysis always answered "not idempotent", so the same requests are refused as before, only without running first | `aStreamedJsCommandIsRefusedWithoutRunning` (refused with 400 and the record counted: 1 without the fix, 0 with it), `onlyTheLanguagesWhoseAnalyzeRunsNothingAreAnalyzedAtAll` |

Two deliberate deviations from the fixes the review proposed:

- Streaming is limited by `analyzeExecutesNothing`, not by `hasPureAnalyze` as proposed. `hasPureAnalyze` is about cost (a
  statement-cache lookup the execution repeats), not safety, and refusing every stream outside `sql`/`opencypher` would
  refuse the streamed `sqlscript`, `gremlin`, `mongo`, `graphql` and `redis` reads that work today
  (`Issue7576SqlScriptStreamingGateTest.stillAllowsAScriptOfPlainReadsThroughSqlScript` pins one).
- The built-in call check guards the auto-commit wrapper only, not the streaming gate. Putting it in the shared verdict
  would refuse every streamed SQL read that calls a user function, which is a supported use: the core streaming test of
  #7306 (`Issue7306HttpStreamingQueryIT.postQueryDeliversTheFirstRowBeforeTheLastIsProduced`) streams a `SELECT` calling a
  function library. A streamed SQL read whose user function WRITES therefore still writes outside any transaction (and
  reports failures in band), as it has since #7306 and as `GET /query` and `POST /query` do for the buffered encoding:
  left to its own issue, since closing it means refusing a feature in use.

Still open, same class, not introduced here: a record read listener or a read trigger that writes runs on any read,
wrapped or not; and on HA a follower serves a SQL read calling a writing user function locally (the engine's
`isIdempotent()` says read), which is the engine-level form of the first finding. Fixing that one in
`Statement.isIdempotent()` would make `database.query("sql", ...)` refuse every user function call, so it needs its own
decision.

Runs after these fixes (`mvn -o`, this worktree, 2026-11-03):

- `engine`, every test under `query/sql` and `function/sql` (after the final change): 5,747 run, 0 failures, 8 skipped.
  Under `query/sql/executor` and `function/sql` with the uncached first version: 3,908 run, 0 failures.
- `server` (`-pl server -am`), every `*Test` under `server/http`: 1,400 run, 1 failure, the pre-existing
  `PostBatchHandlerForwardRequestTest.theBodyCanOnlyBeHandedOverOnce` (section 2, not reached by this diff).
- ITs after the final change: `HttpReadOnlyCommandNoAutoCommitIT` (13), `Issue7306HttpStreamingQueryIT` (18),
  `Issue7571GetQueryStreamingReadOnlyGateIT` (5): 36 run, 0 failures; with the first version also
  `PolyglotScriptingAuthorizationIT`, `AsyncScriptingAuthorizationIT`, `Issue8235QueryNdJsonErrorStatusIT`,
  `Issue5026TransactionSessionLifecycleIT`, `Issue7575ExplainContractIsTheSameOnEveryQueryOperationIT`: 62 run, 0 failures.
  Unit: `Issue7576SqlScriptStreamingGateTest`, `Issue9628*Test`, `AutoCommitParameterTest`, `Issue9784*Test`: green.

Cost of the new check, `HttpReadRequestOverheadBenchmark` (in process, alternating pairs in one run, 3 warm-up pairs,
median of 7, 200,000 verdicts per arm per iteration; "wider" is `SELECT name, id, out('E1').size() AS degree,
name.toUpperCase() AS upper FROM V1 WHERE name = 'x' AND id > 3 ORDER BY name LIMIT 10`):

| Shape | verdict alone, us | verdict + call check, us |
|---|---|---|
| `SELECT FROM V1 LIMIT 1`, first version (tree walked per request) | 0.005 | 0.60 |
| wider SELECT, first version | (lost to a log line) | 8.2 |
| `SELECT FROM V1 LIMIT 1`, calls kept with the statement, runs 1 / 2 / 3 | 0.005 / 0.005 / 0.005 | 0.020 / 0.015 / 0.015 |
| wider SELECT, calls kept with the statement, run 3 | 0.040 | 0.195 |

The first version walked the tree reflectively on every request, up to 8 us for a statement of a dozen nodes, which is why
the calls are now kept with the cached statement: what is left is a lowercase and two map lookups per call, 0.01 to 0.15
us. The HTTP arms of the same runs (`/command` wrapped, `/command` read-only, `/query`): 1395 / 1430 / 1401, 1387 / 1399 /
1329 and 1631 / 1806 / 1731 us per request. The spread between runs (the third run is 15 to 30% slower in every arm, other
worktrees were building) is far above the 0.2 us the check costs, so the end-to-end rows say nothing about it either way.

## 7. Descoped in round 4: S9 removed, why

S9 let `POST /command` run a read-only statement outside the auto-commit wrapper, on a read-only verdict computed per
request from `analyze()`, `isIdempotent()` and `SqlAstInspector`. It is removed entirely. `DatabaseAbstractHandler`,
`PostCommandHandler`, `AbstractQueryHandler`, `SqlAstInspector` and `Statement` are back to the base commit (`4bc577f11`)
byte for byte; `PostQueryHandler` was never edited. Removed with it: `HttpReadOnlyCommandNoAutoCommitIT`,
`SqlAstInspectorCallsOnlyBuiltInsTest` and the `HttpReadRequestOverheadBenchmark` arms (the whole class was S9's).

Why. Three review rounds each found a new way the verdict was wrong or unsafe, and each fix opened the next one:

- the verdict does not look inside called code: a `DEFINE FUNCTION` body, a `js` function, a function named by a string
  can write, and outside the wrapper each write committed its own implicit transaction (no atomicity, no retry);
- a polyglot `analyze()` runs the script, so asking the verdict ran it once more, outside any transaction;
- `PostQueryHandler` inherited the new two-argument `requiresTransaction` and could have been wrapped, against #8775;
- dropping the wrapper changes what a REPEATABLE_READ read sees (one snapshot per request against one per statement);
- the statement was parsed for the verdict before the admission gate, so a refused or queued request paid the parse.

The gain it bought is 5 to 15 us per request against an HTTP envelope of about 1.5 ms (section 3), inside the run-to-run
band. The risk is not worth that. The single body pass (`json.toMap` skipped) was S9's too and went with it, being in the
same handler and worth less still. Also reverted, since it lived in `AbstractQueryHandler` with the S9 verdict: the round 2
guard that kept a streamed `js` (polyglot) request from being analyzed, and so run, before it is refused. That bug is
pre-existing (since #7306) and is left to its own issue: a streamed polyglot request is again analyzed by
`requireStreamableStatement` as on the base commit. If the wrapper's cost ever matters, the safer shape is an engine-level read-only
flag on the statement the execution parses (one parse, after admission), not a second verdict in the handler.

Also fixed in round 4 (the remaining projection minors):

- `SELECT *`: a `*` item (`ProjectionItem.all`, no expression) passed the AST check, so `SELECT *` rows were handed the
  `PropertyCachingResult` view, which made `Projection.calculateSingle` skip its one-pass `toPropertyMap` branch (the
  exact-class check fails on the view) and fall back to the per-name reads. The view is now refused for any projection
  with a `*` item (`ProjectionCalculationStep.hasStarItem`); `SELECT *` gets the scan's row, as on the base commit, plus
  the one-pass read. Test: `ProjectionOneHeaderPassTest.selectStarIsNotReadThroughTheView` (red before: the step of
  `SELECT * FROM Wide` read through the view).
- Postgres tombstone: for a property removed from a plain element row, the base `columnValue` answered null from
  `row.getProperty` and then the record's value from `row.getElement().get(name)`; the one-pass map answered null and
  stopped. A null from the map now takes the same element fallback. Test:
  `PostgresElementRowColumnValueTest.aRemovedPropertyFallsBackToTheRecordAsBefore` (red before: `expected "first" but was
  null`). `columnValue(Result, String)` is package-private for the test.

The `SELECT *` rows of section 3 were measured before the `*` fix, so with the view and without the one-pass read on
both arms; that is why they did not move. The benchmark was not re-run in round 4 (shared machine, see section 3).

Runs after round 4 (this worktree, 2026-11-03):

- `postgresw`: Maven still cannot resolve the module offline (`arcadedb-gremlin` dependencies). Its main sources and
  every `*Test` but one (`Issue10591NoRowCatalogRelationsTest` needs the pgjdbc driver, not in the offline repository)
  were compiled with `javac` against the server test classpath and run with the JUnit Platform launcher: 942 run,
  0 failures.
- `engine` (`mvn -o -pl engine`), every test under `query/sql/executor`, `query/sql/parser`, `query/opencypher` and
  `serializer`: 11,795 run, 0 failures, 0 errors, 18 skipped.
- `server` (`mvn -o -pl server -am`), every `*Test` under `server/http`: 1,403 run, 1 failure, the pre-existing
  `PostBatchHandlerForwardRequestTest.theBodyCanOnlyBeHandedOverOnce` (section 2, not reached by this diff; the server
  handlers are now identical to the base commit). ITs `Issue7306HttpStreamingQueryIT`,
  `Issue7571GetQueryStreamingReadOnlyGateIT`, `Issue5026TransactionSessionLifecycleIT`,
  `PolyglotScriptingAuthorizationIT`: 34 run, 0 failures.

## 8. Round 5: GAV-backed vertices in the Bolt and gRPC converters

An independent review found one minor. `BoltStructureMapper.toProperties` and `ArcadeDbGrpcService.convertToGrpcRecord`
read every node through `document.toMap(false)`. A node bound by a Cypher expansion a Graph Analytical View serves
(`MatchRelationshipStep`, `GAVExpandAll`) is a `GAVVertex`: its `get(name)` answers the view's column-store snapshot,
its `toMap(false)` delegates to `resolve().toMap(false)`, the live OLTP record. With `arcadedb.gavUseWhenStale=true`, or a
commit landing between the snapshot pin and the serialization, `MATCH (a)-[:KNOWS]->(b) RETURN b.age, b` over Bolt could
answer two different ages for `b` in one row.

Fix: both converters take the one-pass `toMap(false)` only for a `BaseDocument`, the base of every record-backed
document (`ImmutableDocument`, `MutableDocument`, `DetachedDocument`, the embedded documents, `ImmutableVertex`,
`MutableVertex`, `ImmutableEdge`, `MutableEdge` and both light edges, whose `get` and `toMap` already agree). Any other
`Document` implementation (`GAVVertex`, `SynchronizedVertex`, a caller's own) is read as on the base commit:
`getPropertyNames()`, then `get(name)` per name. `convertToGrpcRecord` is package-private for the test.

Regression tests (each a real `GAVVertex` over a record whose `age` is 31, with a view proxy whose column store answers 30;
both were run red against the unfixed line, `expected 30 but was 31`, and green after):

- `bolt/src/test/java/com/arcadedb/bolt/BoltGavVertexPropertiesTest.java`: `nodePropertiesAgreeWithTheViewTheProjectionRead`
  (`toProperties` and `toNode`), `aRecordBackedVertexStillReadsEveryProperty`.
- `grpcw/src/test/java/com/arcadedb/server/grpc/GrpcRecordGavVertexPropertiesTest.java`:
  `recordPropertiesAgreeWithTheViewTheProjectionRead`, `aRecordBackedVertexStillReadsEveryProperty`.

Runs after round 5 (`mvn -o -pl bolt,grpcw -am`, this worktree, 2026-11-03). The offline repository has
`neo4j-java-driver` 5.28.14 but not the 6.3.0 the build names, so the bolt module was run with
`-Dneo4j-driver.version=5.28.14` (test scope only, nothing shipped changes):

- Unit: `BoltGavVertexPropertiesTest` (2), `BoltStructureTest` (56), `Bolt4998PathMappingTest` (5),
  `BoltTypeRoundTripTest` (11): 74 run, 0 failures. `GrpcRecordGavVertexPropertiesTest` (2),
  `ArcadeDbGrpcServiceExtendedTest` (27), `Issue10091GrpcStoredStringTypesAreTaggedTest` (3): 32 run, 0 failures.
- ITs (`verify -DskipITs=false`): `BoltProtocolIT` 82 run, `Issue6404LookupByRidMissingCatIT` 1 run,
  `Issue11067GrpcTraversalProjectionIT` 9 run: 92 run, 0 failures. `BoltProtocolIT` is the first Bolt driver IT run on
  this change (section 5 listed it as not run).

No benchmark: the guard is one `instanceof` per record, and the record-backed path is unchanged.
