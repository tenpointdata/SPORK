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

## Current state (2026-11-04)

- `main` is at `6748abd4d` (merge of `spork/ivf-vector`) and matches `origin/main`.
- The architecture is at review round 10 ([01-architecture.md](docs/spork/01-architecture.md)). The plan is scheduled by
  gates (measurements, external answers, hardware, compute, data deadlines), never by engineering time.
- Every engine and train 1 worktree is merged into `main`. The only unmerged branch is
  `worktree-agent-abfed0420842cfa29`, an early `LSM_IVF_VECTOR` attempt superseded by the spike; it fails 4 of its 12
  tests and must not be merged.
- `docs/spork/README.md` still lists six worktrees "in review"; they are all merged now. That README belongs to the
  architecture session, so it has not been edited here.

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

### Waiting on the owner (gates)

- 4 TB replacement drives on every node by about 2027-01-15, ideally before the cluster is first formed.
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
