# System 1 multimodal indexing for SPORK: research and proposal (2026-11-02)

Legend: **[V]** verified against a cited source; **[I]** inferred/estimated (arithmetic from SPORK's budget model
or engineering judgment). SPORK facts from `00-research-findings.md`, `01-architecture.md` and the engine source.
Companion docs: `system-one-decision-models.md` (decision layer), `embedding-models.md` (model choices).

## 1. State of the art in multimodal retrieval indexing (2024-2026)

| Technique | What it buys | Cost | Maturity | Verdict for SPORK |
|---|---|---|---|---|
| **Shared joint-embedding space** (ImageBind, SigLIP 2) | One index answers image->image, text->image, image->text [V arxiv 2305.05665] | **Modality gap**: image and text embeddings sit in separate cones, so one global threshold does not work [V arxiv 2203.02053] | Mature weights | Use ONE space for images + text queries over images; faces, style and text-semantic are different spaces |
| **Per-modality indexes + fusion** | Each leg keeps its best model, scores calibrated per leg | N indexes, N query encodes | SPORK has `vector.fuse` RRF/DBSF/LINEAR | Default architecture |
| **Late interaction multi-vector** (ColBERT, ColPali/ColQwen2) | Best quality for visually rich documents; ~1030 x 128-d per page [V arxiv 2407.01449] | ~264 KB/page fp16 [I]; Light-ColPali keeps >94% NDCG@5 with token merging [V arxiv 2506.04997] | Vespa, Weaviate | Too heavy for 1.35B images; case-scoped document-like images only |
| **MUVERA fixed-dimensional encodings** | Multi-vector retrieval as single-vector MIPS; ~10% better recall at 90% lower latency vs PLAID [V arxiv 2405.19504] | 10,240-d FDE (1,280 B after PQ) | Weaviate 1.31 | Nice-to-have for the case-scoped document leg |
| **Learned sparse (SPLADE) + dense** | Lexical precision + semantic recall | Posting lists ~ text index size | SPORK has `LSM_SPARSE_VECTOR`; OpenSearch neural-sparse is Apache-2.0 [V] | Optional hot-tier leg; BM25 stays the lexical leg |
| **Fusion** | Score fusion beat RRF by 3.86% NDCG@10 on 6 BEIR sets [V practitioner] | Learned fusion needs judged queries | SPORK has RRF/DBSF/LINEAR | RRF first; DBSF/LINEAR once calibrated; learned weights from analyst feedback (`RankerModel`) |
| **Matryoshka / adaptive retrieval** | Shortlist on a 16-64-d prefix, rerank at full width [V arxiv 2205.13147] | Model must be MRL-trained | Qwen3-Embedding-0.6B Apache-2.0, 32-1024 dims [V] | Use for text; PCA-256 + quantization for images |
| **Binary / PQ with rerank** | Elastic BBQ: 32x compression, often better ranking than float32 with 3x oversample rerank [V]; Cohere binary+int8 rescore matches float32 [V]; RaBitQ 4-7 bits Recall@1 0.90-0.99 [V] | Rerank reads full/int8 vectors | SPORK has INT8/BINARY/PRODUCT with `rerankOversample` (default 10) | Core of the design: 1-bit first pass, 4-bit or int8 rerank |
| **Filtered ANN** | ACORN 2-10x QPS over Filtered-DiskANN at low cardinality [V arxiv 2403.04871]; Qdrant: ACORN stalls at 67.7% recall on 1% filters [V]; Filtered-DiskANN supports only ~1,000 equality labels [V] | Graph methods degrade on selective filters; IVF scans fewer lists | SPORK: allow-list <= `VECTOR_INDEX_PREFILTER_MAX_SELECTIVITY` -> exact scoring, else bits-filtered walk | Arbitrary RID allow-lists (Recon sets) favor **posting-list (IVF) indexes** over graphs for the corpus tier |
| **Partition-aware / disk ANN** (DiskANN, SPANN, SPFresh) | DiskANN: 1B points, 64 GB RAM, >5,000 QPS, <3 ms [V]; SPANN: centroids in RAM, postings on SSD, ~1 ms with 32 GB at billion scale [V arxiv 2111.08566]; SPFresh in-place split/merge updates at 1% daily churn [V arxiv 2410.14452] | SSD random reads per query | jvector is the Java DiskANN-family engine SPORK embeds | SPANN-style posting-list index is the best fit for a page-based LSM engine with Raft page replication |
| **Multi-tenant ANN** (Curator) | Per-tenant indexes 37x faster than metadata filtering but 10x memory [V arxiv 2401.07119] | | Research | Case/watch galleries = small per-case jvector graphs; corpus = shared IVF with allow-lists |

## 2. "System 1 then System 2" pipelines
- Cascade retrieval is the industry default: recall-oriented first stage (ANN/BM25) measured by Recall@k, precise
  second stage (cross-encoder/rerank) measured by nDCG. Cross-encoder costs: 100-200 ms for 50-100 docs on CPU,
  30-50 ms on a T4 [V practitioner]. A 2026 paper pairs learned-sparse first stage with multi-vector rerank for
  >24x speedup with early exit on score drop / stable top-k [V arxiv 2601.05200].
- Quantization cascades (1-bit scan -> int8/float rerank) are the same pattern inside one index; Matryoshka Funnel
  is the dimension analog.
- JEPA prediction error as an index feature: evidence thin (V-JEPA 2 residual as review-value signal, arxiv
  2608.01336; MTS-JEPA anomaly prediction with collapse as the main failure). **Recommendation [I]:** do not make
  prediction error an index key; store a cheap per-item `novelty` scalar (distance to nearest centroid at ingest)
  as a structured property that detectors can filter and sort on.
- Shape to plan for [I]: System 1 at 90-95% recall@100 in 2-5 ms of engine time; System 2 spends 100-500 ms on
  <= 500 candidates. Recall lost in System 1 is unrecoverable, so the recall budget (oversample, nprobe) is the knob
  the analysts' "exhaustive" mode turns up.

## 3. Near-duplicate and perceptual hashing at 100M+
- **PDQ** (Meta, open, BSD): 256-bit DCT hash, Hamming threshold 31; Meta's `PDQIndex` uses multi-index hashing;
  FAISS `IndexBinaryMultiHash` [V]. **PhotoDNA**: 1152-bit, cloud service for qualified customers, "not intended
  for law enforcement" via the cloud [V]; treat as a CSAM-reporting interface, not a general near-dup index [I].
  Both lack robustness to targeted evasion [V arxiv 2406.00918].
- **Learned copy detection**: SSCD 512-d, +48% over SimCLR on DISC2021 [V arxiv 2202.10261]; self-supervised
  encoders (DINOv2) beat text-aligned ones on near-dup/instance retrieval [V vendor].
- **Coexistence design [I]:** three layers per distinct image: (1) exact SHA-256 (already an identifier), (2) PDQ
  256-bit for crop/recompress/resize near-dups, (3) a semantic embedding for "same room, same tattoo". PDQ is a 32 B
  binary vector; SPORK's BINARY path already computes cosine as `1 - 2*hamming/dimensions` (`LSMVectorIndex` ~line
  7264), so PDQ can ride the same ANN machinery with a `HAMMING` metric and exact Hamming verify. Reserve
  multi-index hashing for ingest-time dedup batches; use ANN-Hamming interactively.

## 4. Temporal and geo-aware embeddings: fold in or filter?
- Mainstream vector DBs treat time as filtered metadata. Research folding time/location into vectors (SpatCode WWW
  2026; CubeGraph) exists but is unbenchmarked here; GeoCLIP solves a different task (where was this photo taken),
  useful as System 2 enrichment.
- **Verdict [I, strongly supported]:** keep time and place as **structured filters**: investigators need hard,
  auditable windows ("14 days, 200 km"); SPORK's period partitions make time a free prefilter; folding time into the
  vector breaks cross-partition score comparability; the geo index already resolves radius -> `placeId` set ->
  allow-list. Only `novelty`-style scalars are stored alongside, never mixed into the metric.

## 5. Proposed "System 1 multimodal index" for SPORK

### 5.1 Principle
One **IVF/posting-list ANN index per embedding space** (not per modality), each with 1-bit codes for the scan and
4-bit/int8 codes for rerank, centroids in RAM, postings in LSM pages on NVMe. A query from any modality is encoded
into every space it can legally enter, each leg returns `(key, score, leg)`, legs are fused with RRF, and the fused
shortlist goes to System 2 verifiers. All structured constraints (time, geo, set membership, case ACL) are
**allow-lists**, never embedded.

### 5.2 Spaces, models, sizes (mid scenario; today -> 3x)

| Space | Members | Model (license) | Stored vector | Scan code | Rerank code | Items today -> 3x | Disk at 3x [I] |
|---|---|---|---|---|---|---|---|
| **S-IMG** scene/object | PDQ-cluster representative images; text queries via the text tower | SigLIP 2 so400m 1152-d, PCA->256-d (Apache-2.0) | none (regenerable) | 1-bit 256 = 32 B + 8 B | 4-bit 128 B | ~130M -> ~405M (30% survive PDQ clustering [I]) | 23 GB postings + 52 GB rerank |
| **S-PDQ** near-dup | every distinct image | PDQ (BSD) | 32 B on `Image` | the hash (Hamming) | exact Hamming | 435M -> 1.35B | 54 GB |
| **S-FACE** recall shortlist | representative faces (1 per 2.5) | open 512-d model (AuraFace-v1 Apache per `embedding-models.md`; AdaFace weights carry research-only data terms) | int8 512 B (envelope-encrypted) | PQ 64 B (binary risky for faces [I]) | int8 | 87M -> 270M | 19 GB + 138 GB rerank (69 GB with 4-bit) |
| **S-TEXT** ad text + OCR text | unique `TextBody` | Qwen3-Embedding-0.6B MRL 256-d (Apache-2.0), or gte-multilingual-base | none | 1-bit 32 B + 8 B | 4-bit 128 B | ~150M -> ~450M | 25 GB + 58 GB |
| **S-STYLE** authorship | hot unique texts | existing 24-d Recon vector (exact, threshold 0.95); optional LUAR 512-d | as today | INT8 jvector | | 200M cap | unchanged |
| **S-TATTOO** | detected tattoo crops | DINOv2-B / PE-Core crop embedding 768->256-d (Apache) for recall; ROC tattoo 1:N verify | none | 1-bit 32 B | 4-bit | 8.7M -> 27M | 2 GB |
| **S-DOC** (case-scoped) | screenshots/posters in open cases | ColQwen2 via MUVERA or Light-ColPali | | PQ | MaxSim | <= 1M | < 2 GB |

Total new disk at mid 3x ~ **330-380 GB** including ~30% SPANN boundary replication, lifting the architecture's
3x footprint to ~3,950-4,000 GB, 88% of the +1-drive ceiling (4,520 GB) [I]. RAM: centroids only. At ~200 items
per posting, S-IMG has ~2M centroids, S-TEXT ~2.25M, S-FACE ~1.35M; at ~180 B each that is **~1.1 GB heap**, inside
the existing vector residency slice [I]. This is what makes corpus-wide image and face recall fit on a 126 GB node
where the architecture had to reject a 346 GB ArcFace index: nothing per item is resident.

### 5.3 How a query from any modality hits the index [I]
1. **Classify the probe**: image / face crop / tattoo crop / text / token / RID / saved set.
2. **Encode** in the sidecar (`algo@ver` namespaces): image -> S-IMG (+PDQ, + face detect -> S-FACE, + tattoo
   detect -> S-TATTOO); text -> S-TEXT + S-IMG text tower + FULL_TEXT/BM25 (+ sparse); RID -> reuse stored codes,
   zero encode cost.
3. **Constrain**: time window -> partitions; geo radius -> `placeId` set -> `(placeId, tsPosted)` scan -> RID
   allow-list; case ACL -> allow-list; Recon set -> allow-list. Allow-lists are Roaring bitmaps of ordinals.
4. **Scan**: nprobe centroids (default 64, adaptive to 512 when an allow-list is selective) -> posting pages ->
   Hamming/ADC on codes -> top 10xk -> rerank on 4-bit/int8 -> top k per leg.
5. **Fuse**: RRF (k=60) across legs; DBSF per leg once calibration tables exist (modality gap means text->image and
   image->image get separate calibrations).
6. **Verify (System 2)**: faces -> ROC 1:1 on the shortlist (nothing ROC-derived enters an ANN index); near-dups ->
   exact Hamming + SHA; text -> cross-encoder rerank on <= 50; images needing text -> OCR on candidates only; analyst.

### 5.4 Set-oriented retrieval (Recon compare, 2-4 selections) [I]
- **Set <= ~20K items**: skip the index. Codes are stored inline on `Image`/`Face`/`TextBody` vertices (+40-72 B),
  so set-scoped endpoints that already hydrate the set score it directly: 6,000 x 6,000 32-byte Hamming ops ~ 10 ms
  single core. Pairwise "shared faces/images/style across groups" becomes an in-memory kNN join tagged by group.
- **Set between 20K and ~20% of a partition**: IVF scan with bitmap intersection; cost grows ~1/selectivity (nprobe
  up to 512 -> ~20-40 ms at 1%). Posting-list indexes avoid the graph stall ACORN shows at 1%.
- **Corpus-wide**: plain nprobe=64.
- Intersection of legs across sets: each leg returns `(key, score, leg, setId)`; shared vs distinct are set
  operations on keys in the plugin.

### 5.5 Expected latency (per node, NVMe ~100 us random read, 32-way pool) [I]

| Stage | 300M records | 900M records |
|---|---|---|
| Probe encode (CPU int8 ONNX / GPU) | text 0.6B: 30-60 ms / 5 ms; SigLIP 2 image: 150-300 ms / 10 ms; RID probe: 0 | same |
| IVF scan: 64 postings x 1 page, 12.8K codes | 0.3-0.5 ms I/O + 0.2 ms compute | +0.1 ms |
| Rerank 200 candidates | 0.7-1 ms | same |
| Per leg total | **2-3 ms p50, 8-12 ms p95** | **2-4 ms p50, 10-15 ms p95** |
| 4 legs in parallel + RRF | 4-6 ms p50 | 5-8 ms p50 |
| Set-scoped (2,000-record set, hydrated) | +1-2 ms over the hydrate | same |
| System 2: ROC rescore 500 faces | ~2 ms I/O + compute + probe templatize ~100 ms | same |
| System 2: cross-encoder on 50 texts | 100-200 ms CPU / 30-50 ms GPU | same |

Anchors: SPANN ~1 ms at 90% recall on 1B with 32 GB [V]; DiskANN <3 ms at 1B with 64 GB [V]. Engine-side "first
signal" is ~5-10 ms; **the probe encoder dominates**, so a small GPU per node (or encoder caching and RID-probe
reuse) is the single biggest lever on perceived System 1 latency.

### 5.6 Engine changes (ordered)
1. **Parallel bucket search**: done in the vector workstream (`VectorSearchPool`, `NeighborMerge`).
2. **New index type `LSM_IVF_VECTOR`** (SPANN-style; `Schema.INDEX_TYPE` is a closed enum, so engine work): in-RAM
   centroid graph reusing jvector `OnDiskGraphIndex`/`GraphSearcherPool`; posting lists as `PaginatedComponent`
   pages (borrow `SegmentFormat`/`SkipEntry`/`RidScoreMinHeap` from `index/sparsevector`); codebooks via
   `LSMVectorIndexPQFile` and `BinaryCodeCache`; liveness/allow-list via `LiveVectorBitsFilter`; SPFresh-style
   split/merge/reassign instead of full rebuilds. Build is streaming k-means on a sample plus one assignment pass,
   so heap is O(centroids), removing the ~1.1 KB/node build cost and 10M-per-bucket ceiling. Because postings are
   LSM pages they **replicate through Raft automatically**, fixing the "`.vecgraph` not replicated" gap.
3. **Allow-list representation**: `preFilterPlan` and the IVF scan accept a Roaring bitmap of ordinals; keep
   `VECTOR_INDEX_PREFILTER_MAX_SELECTIVITY` as the exact/approximate crossover.
4. **`HAMMING` similarity** for BINARY-encoded properties (math exists in `LSMVectorIndex` ~7264; expose it) so PDQ
   uses the same index path.
5. **`vector.fuse` calibration**: per-source z-score/Platt tables and a `leg` tag; learned linear weights from the
   `RankerModel` vertex.
6. **Globally trained, versioned codebooks/centroids** per space so scores compare across partitions.
7. **Encoder sidecar** (plugin): model registry, int8 ONNX CPU path and optional GPU, probe-embedding cache,
   `algo@ver` on every code.
8. **Reuse jvector graphs** for small, hot, mutable tiers (case/watch galleries, S-DOC, S-STYLE).

### 5.7 Open risks
- **Face model licensing**: use AuraFace-v1 (Apache) per `embedding-models.md`, or negotiate ROC float export.
- **PDQ-cluster representative ratio (30%)** and **text dedup (50%)** are assumptions to measure in P0; at 100%
  S-IMG postings grow to 76 GB and rerank to 173 GB (disk only, RAM unchanged).
- **Binary codes for faces** untested; PQ-64 B is the safer default.
- **Disk**: relies on the planned +1 drive per node; adds no RAM pressure.
