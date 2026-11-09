# SPORK handoff

The single entry point for whoever picks this repository up next (Claude, Codex, Gemini, Antigravity or a human). Read
this first, then [docs/spork/README.md](docs/spork/README.md) for the design.

## What SPORK is

SPORK is the `tenpointdata` fork of ArcadeDB being turned into an identity-centric, multimodal database for
counter-human-trafficking work. Its primary consumer is DeliverFund's Redlight Recon, which moves onto SPORK in train 1
(Q1). A Palantir / Maltego / Kaseware-class link-analysis platform will be built on top. Owner directives, in priority
order: optimize for retrieval speed; retrieval is set-oriented (Recon's compare feature); replicate, do not shard, for
hot data.

## Where the pieces live

| Piece | Where |
|---|---|
| Engine changes (indexes, search, versioning, geo, vector, page cache) | `engine/` (upstream ArcadeDB layout), e.g. `com.arcadedb.index.ivfvector`, `com.arcadedb.query.structured` |
| Replication | `ha-raft/` (E26 patch: `HA_LOCAL_DATABASES`) |
| Recon plugin (HTTP API, Operating Context, audit) | `spork-recon/`; contract in `docs/spork/api/spork-recon-openapi.json` |
| Canonical keys and schema v1 | `spork-keys/` |
| Encoder sidecar client (ONNX text encoding) | `spork-encoder/` |
| Retrieval benchmark harness | `benchmarks/`; OrientDB 3.2.24 baseline in `benchmarks-orientdb/` (outside the reactor) |
| Design docs | `docs/spork/` (start at its README) |
| Per-workstream docs with measured numbers | `docs/spork/workstreams/` |
| Capacity model (every capacity number comes from it) | `docs/spork/model/budget.py` (`python3 docs/spork/model/budget.py`) |
| Day-1 node configuration, JVM flags, OS tuning, schema DDL | `docs/spork/config/` |
| Remote | `https://github.com/tenpointdata/SPORK` (private). **GitHub issues are disabled on it**, so open work is tracked in this file (see below) |
| Deploy target | None yet. Production is a 4-node cluster (126 GB RAM each, no GPU, 4 TB drives); WS1 builds it |

## Current state (2026-11-09)

- `main` is at `b51665b57` and matches `origin/main`. No code changed since 2026-11-04; the change on this date is
  to the plan.
- The architecture is at review round 10 ([01-architecture.md](docs/spork/01-architecture.md)). The plan is scheduled by
  gates (measurements, external answers, hardware, compute, data deadlines), never by engineering time.
- Every engine and train 1 worktree is merged into `main`. The only unmerged branch is
  `worktree-agent-abfed0420842cfa29`, an early `LSM_IVF_VECTOR` attempt superseded by the spike; it fails 4 of its 12
  tests and must not be merged.
- `docs/spork/README.md` still lists six worktrees "in review"; they are all merged now. That README belongs to the
  architecture session, so it has not been edited here.
- A Recon analyst set out the four workflows the move has to serve (2026-11-05). The design answers most of them; the
  seven parts it does not are items 34 to 40 under Open work, and the ones that need a product or governance decision
  before they can be built are in the human lane of Triage. Nothing in the architecture was changed for them: the
  affected docs (01, 04) belong to other sessions.

### Done

Design:
- Architecture round 10, the capacity model, the day-1 config bundle and the Recon OpenAPI contract.
- Owner decisions: fixed hardware (4 nodes x 126 GB, no GPU, each 2 TB drive replaced by a 4 TB one); plan C then plan E
  for older data (sealed partitions on 2 of 4 nodes, warm text and the non-representative face gallery in the object
  store); scene and tattoo search on the hot window only; OCR on new images only.

Engine, on `main`:
- F1 saved selections (`RidBitmap`, `selection.*`, selection as a filter in the planner and in every search kind).
- Identity procedures (`identity.compare/cohesion/candidates/features`), the Senzing replacement.
- Stylometry port, perceptual hashes, Hamming band keys, `similarity.*`.
- F3 graph analytical views (CSR) on followers.
- E25 native record versioning (`ALTER TYPE ... VERSIONED`, `history.*`).
- Geo kNN and metre radii (kNN 3 s to 5-9 ms on 5M points).
- Parallel per-bucket vector search (single-client p50 6-8x faster).
- Partial-text and fuzzy search, BM25 top-K, block-max sidecar.
- E18 `LSM_IVF_VECTOR`, spike plus hardening (default recall@10 0.914 at 5M; a 1% filter 109 ms to 19 ms).
- Page cache prefetch, read coalescing and background eviction; graph degree counters and edge-list layout; index-lock and
  record-read work (shared page views, permission verdict cache); projection and lazy vertex load.
- E26 spike: warm partitions as node-local databases outside the Raft group (stock ha-raft cannot host them; patched it
  can).
- P4 structured search core, count, rank order and the cardinality-table estimated total (D57: estimates by default).
- Retrieval benchmark harness, JFR profiles, regression gate, OrientDB baseline.

Train 1 modules, on `main`:
- WS2 `spork-keys` (Java key library, golden vectors, schema v1).
- WS3 `spork-recon` slices 1 and 2 (plugin core with OC/428/403 and audit, structured search route).
- WS12 PDQ library, band scheme and hash-gate hook.
- WS13 encoder sidecar (text encoding end to end at about 194 ms per ad; image stages are stubs).
- Entry identity and audit slice 1 (session proof, lifecycle receiver, hash-chained audit with actor = Entry account).

## Triage

What to pick up next, ranked. Numbers are the items under [Open work](#open-work); take the top unclaimed one in the
agents' lane unless you were given a task, and claim it (`fork_handoff` with `claim=#N`). Items 29 to 31 are claimed by
the retrieval-speed worktrees named on their lines. Re-rank when you finish: drop what you closed, add what you found,
move items between lanes when that changed.

**For an agent**

| Item | Size | Done looks like |
|---|---|---|
| 34 evidence rows name their supporting records and dates | small | `identity.compare` / `cohesion` / `candidates` evidence rows carry the supporting member RIDs per side and each side's first and last sighting, under a cap, with no extra record reads |
| 37 `(website, urlHash)` index on `Record` | small | The index is in schema v1 and a URL's capture set is one lookup |
| 35 period window on the association question | small | `from` / `to` on compare, scored on the window, with in-window and out-of-window sighting counts per identifier |
| 36 rejected candidates stay rejected | medium | `identity.candidates` excludes or flags groups with a rejected link or unlink decision, with an option to include them |
| 4 WS2 follow-ups (schema still to add, ETL, offline GraphBatch build) | medium | Each sub-item of the WS2 line, in that doc's order |
| 5 WS3 next slices, in order from intake and `GET /records/{id}` | large | Each slice green with its tests, in the order the line gives |

**For a human (owner or product decision first)**

| Item | Why it needs a person |
|---|---|
| 39 analyst assessment state | The shape is a Recon product decision (what a subject is, what states exist, who sees whose assessments) before it can be schema |
| 38 record-group route | Depends on 39, and on how Recon wants a group presented |
| 40 basis on a place assertion | Someone has to define what counts as corroborated presence, and governance has to agree the label is not an assertion of fact |
| 27 enable GitHub issues, then move Open work into issues | Owner-only on the remote |
| 28 commit-policy conflict between `CLAUDE.md` and `docs/spork/README.md` | Owner call |
| 25 refresh `docs/spork/README.md` "Engine work" | Owned by the architecture session |

**Blocked**

| Item | On what |
|---|---|
| 32 latency A/B on a cluster node | One idle 126 GB node, about 2 hours |
| 2 IVF recall gate | Phase 0 data and the 4 TB drives |
| 11 E19 and WS15a first spaces | The 4 TB drives and the backfill window |
| 33 HA hazard with `arcadedb.backup.enabled=false` | Needs a reproduction on a cluster, not the shared Mac |

## Setup, run, test

Java and Maven as for upstream ArcadeDB; see [CLAUDE.md](CLAUDE.md) "Build and Development Commands" for the traps,
which matter here:

- Full build: `mvn verify` (not `mvn test`: gremlin's shaded jar is consumed by other modules).
- A subset: always with `-am`, e.g. `mvn -o -pl spork-recon -am verify -Pintegration`. Without `-am` the run silently
  uses stale artifacts from `~/.m2`.
- Skip slow lanes with `-DexcludedGroups=benchmark,slow,vector`, never `-Dtest='!Foo'`.
- After any merge, run the full engine and server unit lanes (about 25 + 15 minutes), not just the feature's tests:
  whole-repo guard tests catch what targeted runs miss.
- Per-workstream commands are in each doc under `docs/spork/workstreams/`, e.g. `mvn -o -pl spork-encoder test`,
  `mvn -o -pl engine test -Dtest='LSMIVFVectorIndexTest,LSMIVFVectorIndexReviewTest'`, and the benchmark gate in
  `retrieval-bench.md`.

Deploy: there is no deployment yet. The node configuration to start from is `docs/spork/config/`.

## Open work

GitHub issues are disabled on `tenpointdata/SPORK`. Until they are enabled this list is the record of open work; when
they are, file each item as an issue and replace its line with a link. Sizes and gates are in
[01-architecture.md section 8](docs/spork/01-architecture.md).

### Train 1, Q1 (Recon onto SPORK, until about 2027-01-29)

1. **Phase 0 measurements and drills.** P0a 10% rehearsal (29M records, bytes per record, Raft log bytes per day); P0b 1M
   image face sample (selects low, mid or high); P0c chaos drills (leader kill, empty-node replacement, formation at full
   size); P0d external answers. Done when each gate in section 8 has its number.
2. **IVF recall gate** on `LSM_IVF_VECTOR`: 90% recall at 100 with nprobe <= 128 and oversample <= 50, at no fewer than 66
   items per posting at mid. Due by the end of Phase 0 week 4. Fail path: re-scope E18 (4-bit or PQ codes) and ship the
   first spaces on the existing indexes. Notes: `docs/spork/workstreams/ivf-vector.md`.
3. **WS1 infrastructure.** LVM on LUKS, XFS, TLS/mTLS, 4-node Raft group with the E0 settings, Prometheus with Raft log
   alerts.
4. **WS2 follow-ups** (`docs/spork/workstreams/ws2-keys-schema.md`): TS port of `SporkKeys` in Recon with the golden
   files; the ETL from upstream NDJSON; offline GraphBatch build on n4 (10M-record gate); initial formation by
   leader-shipped snapshots; the schema still to add (`ops` and `audit` databases, df table, `ChainHead`, Outbox types,
   `Review`, `Image.pdq` and `pdqBands`); open questions on the `Url` key, `textHash` width, `ImageHash` packing,
   `stateCode`.
5. **WS3 `spork-recon`, remaining slices** (`docs/spork/workstreams/ws3-recon-plugin.md`, "Next slices"), in order:
   intake and `GET /records/{id}`; the rest of search (substring arms, `q`, `imageHash`, keyset pages, count); pivot and
   partial; group-by and timeline; feed and recent; board with OCC and deconfliction; audit API; image grants;
   read-consistency headers and the leader append route for audited follower reads; set-scoped routes; compaction
   scheduler. Also: per-tenant authorization, marking permission names, purpose gates, and adding the plugin to the
   `package` distribution.
6. **WS4** Recon `SporkSource` as a materializing cache, generated TS client, gateway routing, and the shadow-diff
   harness that accepts every train.
7. **WS5** continuous ingest saga (NDJSON route, Outbox, SSE change feed).
8. **WS6** full-text search v1 (derived property, RRF merge, df table, gram and phonetic lists, degree counters).
9. **WS12 follow-ups** (`docs/spork/workstreams/ws12-pdq.md`): the ingest hook in the enrichment worker, the `Image`
   PDQ properties, a real hash gate (PhotoDNA and known-hash lists are owner and legal decisions), S-PDQ on IVF.
10. **WS13 follow-ups** (`docs/spork/workstreams/ws13-encoder.md`): image stages (libvips, YuNet, AuraFace) once P0d
    answers; re-measure on the nodes in P0a; probe-cache warm-up; the sparse leg.
11. **E19 and WS15a, the System 1 first spaces**: `HAMMING` similarity for binary codes, S-PDQ and S-TEXT built over every
    partition, `/similar/pdq` and `/similar/text` behind `SPORK_S1_*` flags. Needs the 4 TB drives and the backfill
    (13.4 days of CPU, starting by about 2027-01-16).

### Train 2, Q2

12. E1 cold install (before 2027-02-15) with E0.5 Raft log preservation.
13. WS7 retention: DeletionOrder saga, aging protocol (before 2027-03-22).
14. WS11 ObjectSet service and graph procedures; WS11b intel-platform writes; E20 allow-list bitmaps in the IVF scan.
15. W8 ROC tier and the CPU face and scene pass; WS15b S-FACE; S-IMG, S-TATTOO, S-DOC; S-STYLE onto IVF; WS8 style
    vectors; E22 per-leg calibration.
16. E8a and E8 block-max full-text postings (a changed format: never on a Recon path before it).
17. WS14 decision layer; WS9 Outbox detectors; WS10 watch and mission state.
18. Replication robustness: E2, E14, E5, E3; E25b and E25c versioning follow-ups.
19. W3d backups streamed to the object store; CSV subgraph export; registry-only GAV on n4.
20. Recon cutover behind `SPORK_*` flags, paced by the shadow-diff harness; Recon S1 panels.

### Train 3, Q3

21. E26 in production by about 2027-07 (warm partitions on 2 of 4 nodes; patch already on `main`; open points in
    `docs/spork/workstreams/e26-spike.md`).
22. E17 plugin-keyed FULL_TEXT, then valves 4 and 2 (warm text and the non-representative gallery to the object store).
23. E4 listener role, E16 forced installs off the apply thread, W3e restore-then-join, E15 replicated commit events.
24. E13 engine row and property filters (markings); E23 per-edge-type adjacency chains.

### Housekeeping

25. Refresh `docs/spork/README.md` "Engine work" now that every worktree is merged (owner: architecture session).
26. Delete the superseded branch `worktree-agent-abfed0420842cfa29` and prune merged worktrees under `.claude/worktrees/`
    once the owner agrees.
27. Enable GitHub issues on `tenpointdata/SPORK` (owner) and move this list into issues.
28. Resolve the commit-policy conflict: `CLAUDE.md` lets sessions commit and push (explicit paths only), while the
    `docs/spork/README.md` "Conventions" line still says no commits from Claude sessions.

### Retrieval speed (read path), as of 2026-11-04

Owner goal: optimize retrieval speed. The CPU-cost A/B (upstream `4bc577f11` engine vs main `6748abd4d`, 5M
Recon-shaped records, three interleaved rounds per leg) is in
`.claude/worktrees/bench-main-combined/target/cpucost/comparison.md`. Its harness changes (the `cpucost` stage and the
opt-in IVF variant) are uncommitted in the `bench-main-combined` and `read-bench-63f0a8` worktrees.

29. **Ordered index scan with early LIMIT** (worktree `wf_e48018eb-d84-1`, in progress): the `structured` query
    (equality on `state`, ranges, `ORDER BY tsPosted DESC LIMIT 100`) costs about 755 ms CPU at 5M, 96% of the mix.
    Done when the planner walks `Record[state,tsPosted]` newest-first and stops at the limit, with result-equivalence
    tests and no regression on `feed24h`.
30. **Filtered vector kNN allow-lists** (worktree `wf_e48018eb-d84-2`, in progress): about 280 ms CPU and 220 MB
    allocated per query because the `filter` subquery becomes a `HashSet<RID>` per call. Done when the allow-list is
    index-only and primitive, about 10x cheaper, same neighbors.
31. **IVF defaults on low-dimensional vectors** (worktree `wf_e48018eb-d84-3`, in progress): on 24-d style vectors the
    defaults give recall@20 0.47 against 0.91 for `LSM_VECTOR`, at 1.7x the CPU. Done at recall@20 >= 0.9 with CPU no
    worse than `LSM_VECTOR` and the filtered advantage (41% less CPU) kept. Related to item 2.
32. **Latency A/B on a cluster node**: latency (p50/p99 at 1, 8, 32 clients) cannot be measured on the shared Mac (it never
    had a quiet window in 18 hours). Needs one idle 126 GB node for about 2 hours, Java 21, about 20 GB disk. Blocked on
    node provisioning. The interleaved, contention-gated runner is
    `.claude/worktrees/bench-main-combined/target/gated/gated-ab.sh`.
33. **HA hazard with `arcadedb.backup.enabled=false`** (any chunked index build, not IVF-specific): a leader write during a
    session build longer than `HA_QUORUM_TIMEOUT` ships a `TX_ENTRY` for a file only the leader's uncommitted session
    has, so followers can diverge. Details in `docs/spork/workstreams/ivf-vector.md`, round 6/7.

### Analyst workflow fit (Recon, from the 2026-11-05 analyst requirements)

A Recon analyst (Alisha Willemssen) set out the four workflows the move onto SPORK has to serve and what each one needs:
discovery against assessment for identity, grouping without collapsing for duplicates, connections whose basis is
visible, and historical comparison that does not read as verified movement. Most of it the design already answers
(`04-identity-resolution-design.md` for the time-aware compare model with its honest interval and its DISJOINT and
CONFLICT terms, `01-architecture.md` 2.6 for soft edges that are never automatic and whose rejections are kept, 2.7 for
`_prov`, markings and the ontology, E25 for history). These seven are the parts it does not, each traced to the workflow
it blocks.

34. **Evidence rows name no supporting records and no sighting dates.** `identity.compare`, `identity.cohesion` and
    `identity.candidates` return evidence as `{type, kind, identifier, countA, countB, frequency, gapDays, weight,
    stdDev}` (`IdentityMatchModel.Evidence`, rendered in `AbstractIdentityProcedure.java:380-400`). The analyst sees
    which phone and how many ads on each side, then has to go and find those ads and their dates. That is precisely the
    "unexplained connections still require me to reconstruct the work" cost the move is meant to remove, and the
    requirement is "supporting and conflicting information and sources and relevant dates". Done when each evidence row
    carries, under a cap, the supporting member RIDs per side and each side's first and last sighting of that
    identifier, with no extra record reads on the unshared path (`ClusterProfile` already holds the per-member sorted
    time arrays, and a shared identifier is already read once for its frequency).
35. **The association question cannot be scoped to a period.** The analyst's phone question is not "who owns this
    number" but "what supports the association of this person to this number during the relevant time period". Today
    the caller has to pre-filter both clusters to the window, and the evidence rows do not say which sightings fell
    inside it, so "this number ties to her through 2023 and not after" is not one call. Done when compare takes a
    `from` / `to` window (procedure option, and the plugin route over it) and reports in-window and out-of-window
    sighting counts per identifier, scored on the window while the out-of-window sightings stay visible.
36. **A rejected candidate comes back on the next call.** 2.6 keeps rejected soft edges "so recomputation never
    re-creates them", but `identity.candidates` blocks and scores straight off the graph with no filter on rejected
    links or prior `ResolutionDecision`s, so an identity the analyst has already ruled out is re-surfaced every time.
    The requirement is retaining reviewed and rejected matches to prevent repeated work "while allowing reassessment
    when new info appears". Done when candidates excludes, or returns flagged with the decision id and date, any group
    with a rejected link or an unlink decision against the cluster; an option includes them; and reassessment is a new
    decision that supersedes rather than an overwrite.
37. **Re-ingest of the same source is not an indexed question.** `Record.urlHash` is a property with no index (2.1
    schema table; the `Url` key is still an open question in `workstreams/ws2-keys-schema.md`), and `upstreamRid` only
    makes ingest idempotent when upstream minted the same rid. So the first of the three cases the analyst needs
    separated, "the same source being ingested more than once", cannot be answered without a scan. Done when
    `(website, urlHash)` is a NOTUNIQUE HASH index on `Record` and a capture set for a URL is one lookup.
38. **Record grouping for review is not a route.** Storage dedup (exact-byte `textHash` on `TextBody`, SHA-256 on
    `Image`, PDQ clusters) never collapses records, which is the right half of "grouped, not necessarily collapsed in a
    way that removes their individual value"; what is missing is the other half, a group an analyst can review once.
    The three cases have to be told apart and labelled: same source captured twice (37), separate ads sharing
    attributes, and records possibly referring to the same person. The example to hold onto is different ad sites,
    same number, different photos including tattoos, where the extra photos were the positive ID: the group must
    surface what each member adds, not hide it. Done when a WS3 record-group route returns, for a record, its capture
    siblings, its attribute-sharing siblings with the shared identifiers named, and its identity siblings, each with
    its basis and its review state (39), and search rows carry the group id.
39. **There is no analyst assessment state.** `Review` in the schema is the upstream RedLight ad review vertex, not
    analyst triage. `ResolutionDecision` covers identity merges, splits, links and unlinks, and `BoardLabel` covers
    board work, but nothing records "I have looked at this record, or this candidate pair, and it is supported,
    rejected or unresolved", which is where the analyst's workflow actually ends and the only thing that stops repeat
    review. Done when an assessment exists per (subject, counterpart, Operating Context) with state supported,
    rejected or unresolved, the evidence snapshot it was made against, the actor Entry account and its audit entry, a
    versioned type so a later reassessment supersedes rather than overwrites, and it is returned on every search row
    and every candidate row. 36 and 38 both depend on it.
40. **Listed location is not distinguished from corroborated presence.** `Record.placeId`, `city`, `lat` and `lon` are
    what the ad listed, and nothing marks a place as independently corroborated, so the timeline and geo routes present
    listed cities as a trajectory. Analysts need the patterns "without automatically treating it as verified
    movements": many post the cities they are travelling to next, or several cities at once, and the distinctions that
    matter are when an ad was reportedly posted, when it was collected, what location it listed, and whether the
    presence is corroborated. The first two are already separate indexed properties (`tsPosted`, `tsCollected`). Done
    when a place assertion carries its basis (listed in the ad, analyst corroborated, independently sourced), the
    timeline and geo routes return the basis per point and can filter on it, and no route labels a listed sequence as
    movement.

### Waiting on the owner (gates)

- 4 TB replacement drives on every node by about 2027-01-15, ideally before the cluster is first formed.
- One idle cluster node for the retrieval latency benchmark (item 32), once the nodes are provisioned.
- Object store: S3-compatible or not, free capacity, Object Lock, who holds the encryption keys (gates plan E and
  backups).
- Faces: image counts per record, existing crops or embeddings, permitted purposes, rules for minor-flagged faces.
- Governance: marking model, link markings, 28 CFR Part 23, retention per data class, legal-hold authority, tenancy.
- Licences and partners: ROC EULA (Q-ROC1 to 5), DINOv3, TraffickCam / Hotels-50K.
- Whether Recon's move waits for the index if it is not ready at the Q1 exit.
- Warm-data deletion rewrite cadence (E26).
- The full list is section 9.2 of the architecture (25 questions) and 9.3 for ROC.

## Known issues and gotchas

- **Shared checkout.** Several Claude sessions work in this checkout and its worktrees at once. Stage by explicit path,
  never `git add -A`; check `git status` and recent mtimes before editing; run one Maven build at a time in the main
  checkout; never `mvn install` from a trial tree (it overwrites `~/.m2` for everyone).
- **Doc ownership.** Each file in `docs/spork` has one owning session: the architecture session owns 00, 01,
  02-retrieval-speed, `model/`, `research/`, `config/` (the S1 re-plan session edits 01, README and `budget.py` for owner
  decisions); feature roadmap, 03 and 09 belong to the Goal session; 04 and 05 to identity; 06 to stylometry; 07 to
  Entry; 08 to native versioning.
- **Upstream flakes** that also fail on pre-SPORK `main`: `PostBatchHandlerForwardRequestTest`,
  `Issue7603LeaderForwardHopTest`, `HaLeadershipTransferIT`, `Issue10915VectorIndexSizedOnFirstVectorTest`,
  `SelectParallelIteratorTest.nonThrowingTimeoutTruncatesWhileStreaming`.
- **Two green branches can fail to compile together** (seen in the consolidation); compile and run the full lanes after
  merging.
- **Benchmarks need a quiet machine.** Check `uptime` (load under about 4), run A and B interleaved in one window, and
  never compare legs measured hours apart. The 2026-11-03 5M A/B was ruined by contention and is not citable.
- **Commit an architecture worktree only when merging it**: its review loops keep editing after it is first reported
  clean.
- No em dash character in any doc or response (project `CLAUDE.md`).

## Recent decisions

- 2026-11-03: hardware fixed at 4 nodes x 126 GB, no GPU, 2 TB drives replaced by 4 TB (round 10).
- 2026-11-03: plan C (E26 warm partitions on 2 of 4 nodes) then plan E (C plus object-store valves 2 and 4, E17
  committed); "replicate, do not shard" now holds for hot data only.
- 2026-11-03: scene and tattoo search on the hot window only; OCR on new images only.
- 2026-11-03: the plan is scheduled by gates, not engineering time (round 9); the System 1 index ships from the
  beginning (round 8).
- 2026-11-03: one structured-search engine core with the plugin layered on top; search totals are estimates by default,
  exact count opt-in (D57).
- 2026-11-03: version history is native to the engine (E25), not modelled with assertion nodes or edge validity stamps.
- 2026-11-03: all work consolidated onto `main`; sessions may commit and push, staging explicit paths only.
- 2026-11-04: GitHub issues are disabled on the remote, so open work lives in this file until they are enabled.
- 2026-11-09: the 2026-11-05 analyst requirements are recorded as open work (34 to 40), not as architecture changes. The
  design already answers the bulk of them, the seven gaps are each traceable to a named route, index or procedure field,
  and 01 and 04 belong to other sessions. Assessment state (39), the record-group route (38) and the basis on a place
  assertion (40) are in the human lane because each needs a Recon product or governance decision before it is schema.

## Handoff policy

This policy applies to every project on this account. Keep it, in full, in every `HANDOFF.md` and mirror it in
`AGENTS.md`.

### Handoff policy: keep every project handoff-ready

Any repository you work in must stay in a state where another agent (Claude, Codex, Gemini, Antigravity, or a human) can
pick up the work cold, with no access to this conversation. Two things make that true, and you maintain both:

1. **`HANDOFF.md` at the repository root.** The single entry point for whoever comes next. It holds: what the project is
   and where its pieces live (code, data, services, deploy targets); the current state, under a dated stamp; how to set
   up, run, test and deploy; the open work, each item linked to its GitHub issue; known issues and gotchas; recent
   decisions and why; and this handoff policy itself, in full, so the instruction survives the handoff.
2. **GitHub issues for every open piece of work.** Anything unfinished, deferred, newly discovered, or decided against
   for now gets an issue with enough context to act on without this conversation: what, why, where in the code, and
   what done looks like. Close issues when the work lands and reference them from the commit or PR. The open-work list
   in `HANDOFF.md` points at issues; it never carries work that has no issue.

**When to update:** at the end of any session or turn that changed code, state, configuration, or plans, before the
final commit or push. Refresh the current-state stamp, add or close issues, and record decisions made. If a repository
has no `HANDOFF.md`, create it and the issues as your first act there. If it has no GitHub remote, say so in
`HANDOFF.md` and keep an `## Open work` list there until one exists.

**Propagation:** every `HANDOFF.md` you create or update contains this policy, so an agent that reads only the
repository learns to keep maintaining it. Also mirror it in the repository's `AGENTS.md` (read by Codex, Gemini CLI and
others) and have the repository's `CLAUDE.md` import that file with `@AGENTS.md`.

### Repository hosting and permissions

- **Where new repositories go:** work repositories live under the `tenpointdata` GitHub organization; `cybersicilian` is
  the owner's personal account. When a project needs a remote, propose the organization first. Private by default.
- **Creating a repository may be denied** by an agent's permission classifier even for a private repo. Do not work
  around it: prepare the exact commands (a `scripts/bootstrap-github.sh` that creates the repo, pushes, adds labels and
  files the drafted issues), record in `HANDOFF.md` that the remote is pending, and hand the command to the owner.
- **Repository-specific conventions win where stricter.** This policy is the floor, not the ceiling.
- **Shared folders:** when two sessions work in one checkout, stage by explicit path, never `git add -A`, and do not edit
  files the other session owns.
- **This repository:** the remote exists but has issues disabled; the open-work list above stands in for issues until
  they are enabled.
