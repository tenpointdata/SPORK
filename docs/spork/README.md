# SPORK design docs: start here

SPORK is the tenpointdata fork of ArcadeDB being turned into an identity-centric, targeting-first, truly multimodal
database for counter-human-trafficking work. Primary consumer: DeliverFund's Redlight Recon. A Palantir / Maltego /
Kaseware-class link-analysis platform will be built on top. Owner directives, in priority order: optimize for
retrieval speed; retrieval is set-oriented (Recon's compare feature); replicate, do not shard (for hot data; older
sealed partitions live on 2 of 4 nodes, plan C); disks can grow, RAM
(126 GB per node) is the firmer limit; round 10: the hardware will not change except replacing each 2 TB drive with a 4 TB one.

Status 2026-11-03: architecture at review round 10 (round 8: the System 1 multimodal index ships from the beginning;
round 9: the plan is scheduled by gates, not by engineering time; round 10: the hardware is fixed at 4 nodes x 126 GB
RAM, no GPU, with each 2 TB drive replaced by a 4 TB one, and the owner chose plan C for older data), and the engine work
consolidated onto local `main` on 2026-11-03 (six worktrees still in review, listed below). Everything below is for
owner review.

## Document map

| Doc | What it is |
|---|---|
| [00-research-findings.md](00-research-findings.md) | Verified inputs: owner directives, Recon's data model and access patterns, the SPORK engine audit, ROC facts |
| [01-architecture.md](01-architecture.md) | The architecture (about 1,650 lines, five adversarial review rounds and five directive rounds; Appendix A is the review log). Sections: 0 budget model, 1 topology, 2 data model and ontology, 3 replication, 4 modalities, 4b set-oriented retrieval and the intel-platform read contract (R1-R15), 4c System 1 multimodal index, 4d System 1 decision layer, 5 governance, 6 ingest, 7 Recon integration, 8 phased plan, 9 risks and open questions, 10 decisions |
| [02-retrieval-speed.md](02-retrieval-speed.md) | Per-pattern latency targets, index and cache design, ranked engine optimizations with file:line, JVM/OS tuning, benchmark plan |
| [model/budget.py](model/budget.py) | The one capacity model every number comes from; run with `python3` |
| [research/](research/) | Intel-platform data layer (Gotham, Maltego, Kaseware); System 1 decision models (TypeSafe Jev and the decider category); embedding model survey with licenses; System 1 multimodal index design (`LSM_IVF_VECTOR`) |
| [workstreams/](workstreams/) | Per-workstream docs with measured numbers; most live in their worktree until merged (see below) |
| [config/](config/) | Day-1 configuration bundle for the read, leader and OLAP nodes: validated `arcadedb.*` keys, JVM flags, OS tuning, schema DDL, with every value traced to a doc item |
| `02-feature-roadmap.md`, `03-f1-selections-design.md`, `04-identity-resolution-design.md`, `05-identity-model-mlops.md`, `06-stylometry-perceptual-hash.md`, `07-entry-identity-and-audit.md`, `08-native-versioning-design.md`, `09-f3-csr-on-followers.md` | Written by sibling sessions: feature parity roadmap; F1 selections API; identity-resolution procedures and their model lifecycle; stylometry port, perceptual hashes and Hamming band indexes; Entry as the identity and audit plane; native record versioning; F3 graph views on followers |

## The design in ten lines

1. One Raft cluster, four nodes, every node a full copy of every hot, interactively queried database; older sealed
   period partitions on 2 of 4 nodes outside the group (plan C, E26); reads served from followers with consistency
   headers.
2. The corpus is split into per-period databases (`rec_<p>`, `txt_<p>`, `media_<p>`) capped at 120 GB each, so a
   resync needs at most 120 GB of headroom; a small `identity` registry and a `recdir` directory tie them together by
   canonical keys, never by RIDs.
3. Image originals, crops, warm text and backups live in an S3-compatible object store, referenced by hash.
4. The data model is an ontology: typed objects, properties and two-sided links, markings on all three, provenance
   on asserted values, reversible merges with a winner key; version history is native to the engine, not modelled
   as extra nodes or edge validity stamps.
5. Retrieval is set-oriented: a batch hydrate, set-scoped analysis endpoints that reproduce Recon's rules, and an
   ObjectSet service (roaring bitmaps keyed by definition hash) that makes Recon compare and the 15 intel-platform
   read operations set algebra.
6. System 1 index: seven embedding spaces (scene, near-dup PDQ, face, text, style, tattoo, case docs) on Apache/MIT
   models, 1-bit or PQ codes, posting lists in LSM pages (new `LSM_IVF_VECTOR`) that replicate through Raft with
   only centroids on heap; time, geo, set and case constraints are allow-lists, never embedded. It ships from the
   beginning: near-duplicate and text spaces with Recon's Q1 move, then faces, scene, tattoo and case docs in Q2
   from one GPU image pass once ROC and the model licences have answered.
7. System 2 verifies every shortlist: ROC face and tattoo 1:1 (templates opaque, version-tagged), ROC age band,
   deterministic OCR normalizers, a cross-encoder on at most 50 texts. Recon's ArcFace (non-commercial) is retired.
8. System 1 decision layer: a pluggable decider (Jev hosted, Perplexity Decider self-hosted, rules fallback) routes
   intent, prunes partitions, scores ingest priority, runs the entity-resolution fast pass with an analyst middle
   band; every decision logged with outcome; self-hosting required before any hosted provider sees CHT data.
9. Governance in the database: Operating Context on every sensitive call, hash-chained audit appended in the same
   transaction, redaction by data class and role, retention and deletion propagated through graph, text, vector and
   ROC projections, envelope encryption in the plugin.
10. Delivery: planned by gates (measurements, external answers, hardware, CPU core-days, model deadlines, integration
    risk), not engineering time. Train 1 (Q1): Recon moves onto SPORK with zero engine changes, and the System 1
    first spaces ship beside it (E18 `LSM_IVF_VECTOR`, gated on a Phase 0 recall test with a fallback onto the
    existing indexes). Train 2 (Q2 releases): faces, the remaining spaces, set-oriented retrieval, governance and the
    first replication items. Train 3 (Q3): deeper ha-raft and edge-layout changes. Within a train, retrieval speed
    first, then Recon impact. Additive formats
    (`LSM_IVF_VECTOR`) may ride the Q1 milestone; changes to existing formats (block-max full-text postings) stay
    off every Recon path, and Recon's cutover never waits on the index.

## Capacity headlines (mid face scenario; arithmetic in 01-architecture.md section 0, round 10 in 0.8)

- Today: 1,417 GB per node, 91% of the 1,560 GB planning ceiling on 2 TB drives (1,239 GB face-free). **The 4 TB
  replacement drives are due now**: before any index or face backfill and before the face-free crossing (2027-02-21);
  for the index to ship with Recon's Q1 move, on every node by about 2027-01-15, ideally before the cluster is first
  formed. The 4 TB ceiling is 3,000 GB.
- Fixed hardware (round 10): no GPU, no 256 GB RAM, no second drive. With full replicas mid crosses 3,000 GB at about
  2027-12 and would need 140% of it at 3x. Combination A (valves 3, 1, 5; scene and tattoo codes on the hot window)
  holds to about 2028-01. **The owner chose plan C**: A plus E26 (warm partitions on 2 of 4 nodes, in production by
  about 2027-07), which holds to about 2029-01 at 95% at 3x (scene and tattoo on the hot window and OCR on new images
  only, both approved); **the owner then adopted plan E** (C plus the non-representative gallery and warm text in the
  existing object store), which holds to about 2029-07 at 86% at 3x. "Replicate, do not shard" now holds for hot data
  only. High face counts do not fit this hardware without a retention window and a face-scope cut.
- History is the engine's: Identity and MEMBER_OF are versioned types (native record versioning, built in a
  worktree), so the current view is the plain edge list with no validity filter and the as-of view is
  `history.edges(identity, t)`. Reads that do not ask for history pay nothing; history costs about 36 B per record
  per year (43 GB at 3x).
- Point lookups are HASH indexes (3 cached touches + 1 cold read, no compaction traffic); range and composite
  indexes stay LSM. Record pages are 32 KB today, 16 KB once the 256-slot bucket format (M20) lands.
- RAM: JVM 56 GB (23 GB page cache, 10 GB GC headroom, 8 GB vector residency), ROC 45 GB cap (about 205M interactive
  face templates cluster-wide; crossed about 2028-02 at mid, then ROC indexed search or restricted representatives), 3
  GB encoder sidecar. Encoding is CPU-only: the day-one index spaces backfill in 13.4 days on the 16 encode cores plus
  the 12 ROC worker cores that are idle in Q1 (WS13 measured text encoding at about 194 ms per ad at 100 tokens); it
  starts by 2027-01-16.
- LSM compaction must be scheduled, not automatic: the stock policy would ship about 320 GB/day of compacted pages
  through the Raft log for one index alone.

## Engine work

### On `main` (consolidated 2026-11-03, local commits, connected tests green)

| Commit | What | Measured |
|---|---|---|
| F1 selections | `RidBitmap` (RoaringBitmap), `selection.*` procedures, `selection.rids()`, saved selection as a RID filter in the planner and in vector, sparse, full-text and hybrid search, `/selection` HTTP, gRPC and MCP | see `03-f1-selections-design.md` |
| Identity procedures | `identity.compare`, `cohesion`, `candidates`, `features` (the Senzing replacement) | see `04-identity-resolution-design.md` |
| Similarity | stylometry port with golden vectors, perceptual hashes, Hamming band keys, `similarity.*` procedures | see `06-stylometry-perceptual-hash.md` |
| F3 | graph analytical views (CSR) built on followers and kept current by replicated commits | see `09-f3-csr-on-followers.md` |
| Native versioning (E25) | `ALTER TYPE ... VERSIONED`, `_VersionHistory`, `history.of/asOf/edges/purge` | see `08-native-versioning-design.md` |
| Geo (`claude/geo-knn-b2da7a`) | `geo.nearest` kNN, `geo.dWithinMeters`, geohash cover, Cypher geo index seek, convergence pattern | kNN 300-800x faster (3 s to 5-9 ms on 5M points); radius 3-20x |
| Vector scale (`claude/vector-parallel-search-f7403e`) | parallel per-bucket vector search on a dedicated pool; heap-budgeted concurrent rebuilds | single-client p50 6-8x faster, identical results |
| Entry identity and audit, slice 1 (`claude/entry-identity-audit`) | Entry session proof, lifecycle receiver, hash-chained audit with actor = Entry account | see `07-entry-identity-and-audit.md` |
| Retrieval bench (`claude/read-bench-63f0a8`) | Recon-shaped benchmark harness, JFR profiles, regression gate; OrientDB 3.2.24 baseline module outside the reactor | timed ArcadeDB vs OrientDB runs start after this merge |

### Still in worktrees (fix and re-review loops; merge when the architecture session reports them clean)

| Worktree | What | Open issue |
|---|---|---|
| `claude/partial-text-search-2551dc` | analyzers, automaton fuzzy seek, BM25 top-K, block-max sidecar (index format 2) | block-max under adversarial review |
| `worktree-agent-a38ee13437f37dbff` | `LSM_IVF_VECTOR` spike (E18 continues here); 1M x 512-d p50 1.9 ms vs 10.9 ms, 37 MB resident vs 424 MB | spike quality; recall@10 0.763 at default rerank |
| `worktree-agent-a6b83e7aba0748e90` | page cache: prefetch, read coalescing, background eviction | stale-page reuse across drop/recreate, HA snapshot guard, possible per-page hang |
| `worktree-agent-adafdfb8b2f5f8705` | graph adjacency: degree counters, edge-list layout | degree drift under concurrent delete; new on-disk edge chunk type |
| `worktree-agent-af71298f8778713e2` | index locks and record read: shared page views, permission verdict cache | review fixes running |
| `worktree-agent-a92e8710383089f2f` | projection and lazy load: one header pass, lazy vertex load in index seek | being completed |

`worktree-agent-abfed0420842cfa29` is an earlier IVF attempt superseded by the spike, preserved as a commit on its
branch; it fails 4 of its 12 tests and is not to be merged.

Run Maven in one checkout at a time: the main checkout's `target/` directories are shared with the sibling sessions.

## What needs the owner

Section 9.2 of the architecture lists 25 questions; the ones that gate work soonest:

1. Hardware: the 4 TB replacement drive model, on every node by about 2027-01-15 (ideally before the cluster is
   first formed). Decided by the owner: plan C (E26), scene and tattoo search on the hot window only, and OCR on the
   daily stream only. An object store or off-node backup mount exists, and plan E (C plus valves 2 and 4 on it, to
   about 2029-07, E17 committed) is adopted; confirming the store is S3-compatible is now a gate on E.
2. Object store with Object Lock for originals, crops, warm text, segments, backups (about 200 TB of originals at 3x).
3. Backup mount: off-node, encrypted, about 1.9 TB from Q1 (2.0 TB with every index space) and 4.5 TB at 3x.
4. Object store and backup mount: one exists (answered). Still needed: is it S3-compatible, how much free capacity,
   Object Lock, and who holds its encryption keys (backups stay off until that is confirmed).
5. Faces: image counts per record (settles low/mid/high); existing crops or embeddings; which purposes may run face,
   tattoo and ARC search; rules for minor-flagged faces.
6. Governance: marking model (flat labels vs classification + compartments), whether links carry their own markings,
   28 CFR Part 23 applicability, retention per data class and legal-hold authority, tenancy model.
7. Caps to adopt for the intel platform (typed expand 100, page 1,000, facets 1,000, hops 3, hydrate 5,000 ids).
8. Licenses and partners: DINOv3 custom license review (DINOv2 is the Apache fallback); TraffickCam / Hotels-50K
   terms; ROC EULA position on storing templates and shortlist results in SPORK (9.3 lists the ROC questions).
9. "Kaseware PATH": nothing public uses that name; confirm it is the intended product.
10. If the index is not ready at the Q1 exit, does Recon's move wait for it or cut over first?
11. Warm-data deletion cadence (E26, the plan C mechanism the Phase 0 spike validated): deletions on older data are
    hidden at once but physically removed only at the next rewrite. How often must that rewrite run?

## Conventions

- No commits from Claude sessions; the owner reviews and commits.
- No em dash character in any doc.
- Every capacity figure shows its arithmetic and comes from `model/budget.py`.
- Engine changes go to worktrees; docs in `docs/spork` have a single owning session each (see memory notes).
