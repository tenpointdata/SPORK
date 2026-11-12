# HTTP client guidance for a SPORK HA cluster

For anyone writing a client against the HTTP JSON API (`/api/v1/command/{db}`, `/api/v1/query/{db}`) of a Raft
cluster. It answers issues 6 and 7 of the sarah-clusters stress-test report (2026-11): reads queued on the leader's
admission gate because every request went to the leader, and graph-neighbourhood reads broke on the row cap. Every
header, status and setting named here was checked against the code; the read-your-writes round trip is pinned by
`ha-raft/src/test/java/com/arcadedb/server/ha/raft/RaftHttpFollowerReadYourWritesIT.java`, the row cap override by
`server/src/test/java/com/arcadedb/server/http/handler/HttpQueryElevatedMaxResultRowsIT.java`.

## 1. Spread reads to the followers, keep read-your-writes

Writes are decided by the leader. Reads are served locally by whichever node receives them, so a client that sends
everything to the leader puts every read in the same admission queue as the writes (section 4).

The contract, by header:

| Direction | Header | Meaning |
|---|---|---|
| response, every database request on an HA node | `X-ArcadeDB-Commit-Index: <n>` | the Raft index this node had applied when it answered. After a write answered by the leader, `n` covers that write |
| request, a read | `X-ArcadeDB-Read-After: <n>` | the bookmark: serve this read only once this node has applied entry `n` |
| request, optional | `X-ArcadeDB-Read-Consistency: eventual \| read_your_writes \| linearizable` | the level for this request; defaults to `arcadedb.ha.readConsistency`, which is `read_your_writes` by default and in `read-node.properties` |

So with the default configuration a client needs ONE header:

1. send the write to the leader; read `X-ArcadeDB-Commit-Index` from its answer and keep the highest value seen
   (per client, or per logical session if several workers must see each other's writes);
2. send reads to any follower with `X-ArcadeDB-Read-After: <that value>`.

The follower answers at once when it has applied the bookmark, and otherwise waits for it (normally milliseconds:
replicas stayed within ~50 records of the leader in the stress test). `X-ArcadeDB-Commit-Index` on the read's answer
is at or past the bookmark and can be kept as the next one. `X-ArcadeDB-Commit-Index` sent on a REQUEST is still
accepted as a legacy spelling of `X-ArcadeDB-Read-After`; use the latter.

Edge cases a client should handle:

- A non-numeric bookmark is refused with `400` before anything runs.
- A follower that cannot reach the bookmark within `arcadedb.ha.quorumTimeout` (10 s default) serves the read anyway,
  at `eventual`, and logs a warning. If a stale read is unacceptable, use `linearizable` for that read: it fails with
  `503` instead of degrading.
- A node removed from the Raft configuration refuses bookmarked and linearizable reads at once with a retryable `503`;
  send the read to another node.
- **A follower whose copy of the database is quarantined refuses every read of that database** (any consistency, with
  or without the bookmark) with `503`, `Retry-After: 10` and a body naming the database and the reason ("is quarantined
  on this server (...)"). Send the read to another node. A quarantine means the follower's copy is missing committed
  entries (a WAL version gap or an apply error) until a resync from the leader replaces it; before the fix of the
  2026-11-12 incident the bookmark did NOT protect against it - the follower's applied index moves past the entries a
  quarantine skips, so the bookmark wait returned at once and the read was served from the stale copy. While the
  database's copy is being replaced by a resync the same read is refused with `503` too ("is being replaced"). Writes
  sent to such a follower are still forwarded to the leader. Other databases on that follower are unaffected. Which
  follower holds which database quarantined is in the leader's `GET /api/v1/cluster`: the peer's `replicaStatus` is
  `QUARANTINED`, with `quarantinedDatabases` and `databaseResync` beside it. Pinned by
  `ha-raft/src/test/java/com/arcadedb/server/ha/raft/QuarantinedReplicaStatusAndReadsIT.java`.
- A bookmarked read that waits holds its admission slot on that follower while it waits (section 4).
- `eventual` (or no bookmark at all with `eventual` configured) is the cheapest read and can miss the client's own
  recent writes.
- The Java `RemoteDatabase` client does all of this itself (`setReadConsistency(ReadConsistency.READ_YOUR_WRITES)`).

Finding the nodes: `GET /api/v1/server?mode=cluster` names the leader and the replicas. A write sent to a follower
is forwarded to the leader by the server, at the cost of an extra hop; sending writes to the leader directly is
cheaper. (The bookmark a follower answers a forwarded write with was not separately verified for this note; carrying
the leader's bookmark is the verified path.)

## 2. The row cap and the per-caller override

Three settings, all `SCOPE.SERVER`:

| Setting | Default (`read-node.properties`) | Applies to |
|---|---|---|
| `arcadedb.server.httpQueryDefaultLimit` | 20000 (bounded by the ceiling, so 2000 there) | a request that states no limit: the response is cut there and says `"truncated": true` |
| `arcadedb.server.httpQueryMaxResultRows` | 1000000 (2000) | the hard ceiling: a request whose own `limit`, or whose query LIMIT, asks for more and actually gets more is refused with `413` naming the setting, never silently cut |
| `arcadedb.server.httpQueryElevatedMaxResultRows` | -1, follows the ceiling (commented out) | NEW. A higher ceiling for root and for users whose group grants `updateDatabaseSettings` on the database queried |

Every response carries `limit`, `returned` and `truncated`; test `truncated` on every read.

How a trusted client reads a neighbourhood of a few thousand rows in one query, once an operator has set the elevated
ceiling (say 50000):

```json
POST /api/v1/query/mydb
{ "language": "sql",
  "command": "TRAVERSE outE(), inV() FROM #12:0 WHILE $depth <= 6",
  "limit": 50000 }
```

Rules:

- The client has to ASK: a request without `limit` (and without a LIMIT in the query) still gets the default cap, for
  every caller. The elevation only lets an explicit request go past the ordinary ceiling.
- Callers without the grant keep the ordinary ceiling and get `413` exactly as before.
- Past the elevated ceiling the answer is `413` naming `arcadedb.server.httpQueryElevatedMaxResultRows`.
- Prefer `Accept: application/x-ndjson` for large reads. The buffered JSON answer builds the whole response on the heap
  before the first byte leaves, outside the `arcadedb.queryMaxHeapRAM` budget; the NDJSON encoding holds one row at a
  time and ends with a `{"stats": {...}}` trailer (a stream without the trailer is incomplete: read its `error` line).
  The ceiling applies to both encodings the same way.
- A cursor across requests does not exist on `/api/v1/query`; a traversal paged by `@rid > x LIMIT n` re-runs the
  traversal per page, which is why the override exists.

Why this design: it is the smallest change that keeps the protection. The ordinary ceiling stays the bound for every
tenant; only callers already trusted with the database's resource settings (`updateDatabaseSettings`, which can
change database-scope limits anyway) may go past it, and only up to a second bound the operator chooses. Nothing
changes until an operator sets the key.

## 3. Retry semantics

Every write is one auto-committed transaction per request. The server already retries engine conflicts
(`ConcurrentModificationException`) inside the request: the `retries` field of the body, default
`arcadedb.txRetries` (3). What reaches the client is classified as follows.

Safe to retry as is (nothing was applied):

| Status | When |
|---|---|
| `503` | a transient conflict that outlived the server-side retries (`NeedRetryException`, including `ConcurrentModificationException` and `QuorumNotReachedException`, which on the leader means the entry was NOT replicated); the admission gate refused it (`QueryAdmissionException`, section 4, with `Retry-After: 1` and the gate's reason in `detail`, in production mode too); the heap budget refused it (`QueryHeapBudgetExceededException`); a snapshot install or a database being reinstalled; a read of a database this node holds quarantined (section 1). Honour `Retry-After` when present, and back off with jitter otherwise |
| `400` naming a leader address | `ServerIsNotTheLeaderException`: send it to the named leader |
| `409` "request still in flight" with `Retry-After` | an identical request (same `X-Request-Id`, method, path, database and body) is still running on that server; retrying with the same `X-Request-Id` later returns its answer |

Do NOT retry blindly (the write may have been applied):

| Status / symptom | Meaning | What to do |
|---|---|---|
| `500` with exception `ReplicationDispatchedTimeoutException` (a `TransactionOutcomeUnknownException`) | the entry was handed to Raft but the outcome is unknown: it may still commit | read back whether the write landed before re-issuing it |
| `409` "Transaction committed cluster-wide - do not retry" (`TransactionCommittedRemotelyException`) | it committed | treat as success |
| `409` duplicate key | a UNIQUE index refused the write | if the key is the client's own idempotency key, the earlier attempt landed |
| `412` "The server restarted since the request was first sent" | see below | read back |
| connection reset, timeout, no response | unknown | retry with the same `X-Request-Id` (below), or read back |
| `500` "Error on transaction commit" | the transaction failed; `exceptionArgs` names the cause category (below) | act on the category |
| any other `500` | a server-side failure | do not loop on it |

The cause category of a `500` "Error on transaction commit" travels in `exceptionArgs`, which production mode keeps
(the free-form `detail` it conceals):

| `exceptionArgs` | Meaning | Retry? |
|---|---|---|
| `QUORUM_TIMEOUT` | the replication was handed to Raft and the quorum wait ended without an outcome | no: read back first |
| `LEADER_TIMEOUT` | a follower forwarded the command and the leader did not answer in time | no: read back first |
| `LEADER_UNREACHABLE` | a follower had no leader to forward to, or the exchange with it failed in transport | read back first unless nothing was sent |
| `LEADER_ERROR:<status>` | the leader's answer to the forward named no exception (a proxy between nodes, say), so the follower does not relay its status | read back first |
| `INTERNAL` | anything else; the server log holds it under the response's `requestId` | do not loop on it |

A write sent to a follower is answered with what the leader answered: a refusal the follower knows by type is rebuilt
(`QueryAdmissionException`, `DatabaseNotAvailableException`, parse and validation errors, ...), and any other typed
answer is relayed with the leader's status, label, `exception`, `exceptionArgs` and `Retry-After`. A `503` from the
leader therefore stays a `503` on the follower; before the 2026-11-12 incident fix it became a `500` "Error on
transaction commit" naming `TransactionException`.

Making retries safe after a lost response: send `X-Request-Id: <unique id per logical write>` on writes. Every
response carries `X-ArcadeDB-Replay-Protection: <process id>`. A retry of the same request (same id, same body) to the
SAME node within `arcadedb.ha.idempotencyCacheTtlMs` (60 s) is answered from the server's cache instead of running
again; send `X-ArcadeDB-Replay-Instance: <the process id seen earlier>` with it, and a node that is not that process
(it restarted) refuses with `412` instead of applying the write twice. The cache is in memory on one node: a retry
sent to a different node, or after the TTL, runs the write again, so a write that may not run twice should also carry
a natural UNIQUE key the client can read back.

## 4. Admission: `queryMaxConcurrent`, `queryQueueMaxSize`, `queryQueueTimeout`

One gate per JVM, across every database and every remote protocol (HTTP, Postgres, Bolt, gRPC, ...), reads and
writes alike:

- `arcadedb.queryMaxConcurrent` (default 2 x cores, at least 4; 48 in `read-node.properties`) requests run at once.
  A request is admitted before anything of it runs and holds its slot until its answer is ready, which for a write
  includes the commit and the Raft replication. That is the link to issue 4 of the report: writes that wait ~1-2 s
  for a replicated commit on a hot bucket hold their slots that long, and the reads behind them queue.
- A request that finds every slot taken waits in a FIFO queue of at most `arcadedb.queryQueueMaxSize` (default 256; 64
  in `read-node.properties`, which keeps it below `httpWorkerThreads` = 96 because each waiter parks an HTTP worker
  thread).
- It waits at most `arcadedb.queryQueueTimeout` ms (default 30000; 2000 in `read-node.properties`). A full queue or a
  wait past the timeout fails the request with `503` (`QueryAdmissionException`) without having run anything: always
  safe to retry, after a back-off.
- `arcadedb.queryAdmissionHeapWatermark` (80% of `queryMaxHeapRAM`) also holds new requests in the queue while the
  running ones hold most of the heap budget.

Observe it at `GET /api/v1/server` -> `metrics.profiler`: `queryAdmissionRunning`, `queryAdmissionQueued`,
`queryAdmissionAdmittedAfterWaiting`, `queryAdmissionRefused`, and `queryAdmissionWaitTime` (cumulative MILLISECONDS
spent waiting). In the report's 120 s windows, ~3 M ms over ~14,000 waiting requests is ~0.2 s of queueing each,
which matches the 0.25-1.7 s trivial `SELECT` on the leader.

What to do, in order: send reads to the followers with the bookmark (section 1) so they never meet the writers'
slots; keep the number of concurrent writers near `queryMaxConcurrent` of the leader rather than far above it (more
writers only lengthen the queue); retry `503` with back-off rather than at once. Raising `queryMaxConcurrent` on the
leader trades queueing for heap and CPU and does not make replicated commits on one bucket faster.
