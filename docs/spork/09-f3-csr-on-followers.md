# F3: graph analytical views on Raft followers

Status: design note, steps 1 and 2 implemented, 2026-11-03. Roadmap item F3 in `02-feature-roadmap.md`.
Goals served: **R** (read latency on the nodes reads are routed to), and correctness of every read on a follower.

## 1. What a follower did before this change

Measured with `GraphViewOnFollowerHAIT` (ha-raft, 3 nodes) on `main`:

1. **A view created after a follower started never reached it.** View definitions live in the schema extension
   `graphAnalyticalViews`, which replicates, but `GraphAnalyticalViewPersistence.restoreAll` runs only when a database
   opens. The follower had no view (slow, but correct) until it restarted.
2. **After a follower restarted, its view went stale and kept serving: a correctness bug.** The restart restores the
   view and builds it from the follower's own files, READY. A view stays current through record listeners
   (`AfterRecordCreate/Update/Delete`) and after-commit callbacks, and a follower applies replicated commits as page
   deltas (`TransactionManager.applyChanges`), where neither fires. So the view stayed READY over the graph as it stood
   at the restart. After an edge `a -> c` replicated, Cypher on the follower answered `count = 1` where the follower's
   own records held 2, and still did ten seconds later.

The leader is unaffected: its own Raft entries go through `publishLocalCommit`, never `applyChanges`, and its
listeners fire.

## 2. The fix, in two independent steps

**Step 1, correctness (engine).** At the end of a successful `TransactionManager.applyChanges`, the engine calls
`GraphAnalyticalViewRegistry.onReplicatedChanges(database, tx)`. It resolves each page's file to the type it serves
(`getInvolvedTypeByBucketId`, which maps a vertex bucket's internal edge-list buckets to the vertex type too). A view
is told when the transaction touched a vertex type or an edge type it covers, or a bucket the schema cannot attribute
(conservative). Index files and other document types are ignored. A view told takes its existing
`onRelevantCommitCallback()`: it publishes STALE (or flags a build in flight to publish STALE, #8378), so
`findProvider` stops handing it out and every reader falls back to the records; an incremental view (SYNCHRONOUS,
ASYNCHRONOUS) then rebuilds in the background, coalescing commits that arrive meanwhile into one follow-up build; an
OFF view stays STALE until rebuilt, as on the leader. WAL recovery at open reaches the same method before any view is
registered, where it does nothing.

**Step 2, availability (ha-raft).** After a follower applies a schema entry that changed the schema, it calls
`GraphAnalyticalViewPersistence.reconcileReplicated(db)`: a definition with no live view is restored exactly as at
open (`restoreFromDiskOrBuildAsync`, with persistence skipped); a live view whose definition is gone is shut down. It
never writes the schema, which a follower cannot. A failure is logged and does not fail the entry: the view is then
missing, which is slow but correct, because step 1 alone keeps any live view from serving stale data.

## 3. What this does not do

- **Incremental follower updates.** A follower learns THAT a covered type changed, not WHAT changed, so it rebuilds the
  whole view. Under continuous ingest a follower's view therefore rebuilds often and is not served while it does: always
  correct, but the fast path is only as available as the write rate allows. The real fix is for the leader to ship the
  logical edge changes of each transaction in its Raft entry, as it already ships full-text corpus statistics
  (`corpusDeltas`), for followers to merge into their overlay like a leader commit. That changes the Raft entry, so it
  needs an extension section and a peer capability (ha-raft/CLAUDE.md, #7138, #7219). Next step, own design.
- **A view whose definition changed in place** under the same name: reconcile compares names only. Today a redefinition
  is a drop and a create, which it handles.
- **Persisting a follower's CSR**: still leader-only (`persistCsrIfPossible`), because a follower's transaction counter
  does not advance on replicated commits (#11021). A follower rebuilds from its records after a restart.

## 4. Verification (green, 2026-11-03)

Results: `GraphViewOnFollowerHAIT` 3/3 (the views there are `UPDATE MODE SYNCHRONOUS`: an OFF view, the default, stays
STALE after a change until rebuilt by hand, on the leader as on a follower); `GraphViewReplicatedChangesTest` 5/5; the
engine `graph.olap` and `engine` packages plus `SelectionCsrPathTest`, 3,092 tests in 425 classes, with the `slow`,
`benchmark` and `vector` lanes excluded. Not run: the full ha-raft IT suite.

- `GraphViewOnFollowerHAIT`: a view created after the followers started is restored on a follower and answers there;
  after a replicated edge, a follower never answers the older graph (Cypher, and through the view while it is offered)
  and its view is current again once rebuilt; the same after a follower restart; the follower's record walk is the
  reference (`SELECT out('FvE')`).
- Engine unit test of the relevance rule (`GraphViewReplicatedChangesTest`): a transaction touching a covered vertex
  type, a covered edge type, or a vertex type's edge-list bucket marks a view stale; one touching only an uncovered
  document type or an index does not.
- The existing view, HA apply and schema-apply suites.
