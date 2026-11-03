# Embedding / perception model research for SPORK multimodal indexes (2026-11-02)

Scope: open, commercially usable "System 1" models for recall, paired with precise "System 2" verifiers (ROC face,
ROC tattoo, OCR normalization, exact identifiers). Legend: **[V]** verified on a primary page; **[I]** inferred,
secondary, or estimate.

## 0. License findings first

| Model / asset | License | Verdict |
|---|---|---|
| InsightFace buffalo_l (SCRFD det_10g + ArcFace w600k_r50), what Recon ships today | Code MIT; **weights non-commercial research only** [V] | Must replace detector and recognizer |
| AuraFace-v1 (fal) ResNet100 ArcFace-loss | **Apache-2.0**; LFW 0.9965, CFP-FP 0.952, AgeDB 0.961 [V] | Use (recall only) |
| YuNet (opencv_zoo) face detector | **MIT** [V] | Use |
| SFace (opencv_zoo) | **Apache-2.0**, LFW 0.994 [V] | CPU fallback |
| EdgeFace (Idiap) | CC BY-NC-SA 4.0 [V] | No |
| AdaFace official weights | Code MIT, training sets research-only [I] | Avoid |
| facenet-pytorch (VGGFace2) | VGGFace2 terms non-commercial [I] | Avoid |
| SigLIP 2 (google/siglip2-*) | **Apache-2.0** [V] | Use |
| Perception Encoder PE-Core (Meta) | **Apache-2.0** [V] | Use |
| DINOv3 | custom Meta license: royalty-free, no explicit commercial prohibition, bans military/ITAR/espionage, Meta may amend; gated [V] | Usable after legal review (the "espionage" clause needs a lawyer's read for an OSINT product) |
| DINOv2 | Apache-2.0 [I]; ONNX exports exist [V] | Use if DINOv3 rejected |
| V-JEPA 2 | MIT on HF card [V] | Video-first; not for stills |
| ImageBind | CC BY-NC 4.0 [I] | No |
| PaddleOCR PP-OCRv5 | Apache-2.0 [I] | Use |
| docTR (Mindee) | Apache-2.0 [I]; Florence-2 MIT; TrOCR MIT; GOT-OCR2 research-only | docTR usable |
| bge-m3 (BAAI) | MIT, 1024-d dense + sparse + colbert [I] | Use |
| gte-multilingual-base (Alibaba) | **Apache-2.0**, 305M, 768-d (Matryoshka), 8192 tok, 70+ langs, dense + sparse [V] | Use (preferred) |
| nomic-embed-text-v2-moe | **Apache-2.0**, 768-d (MRL to 256) [V] | Alternative |
| multilingual-e5-large | MIT [I] | Alternative |
| jina-embeddings-v3 | CC BY-NC 4.0 [I] | No |
| SPLADE-v3 (naver) | CC BY-NC-SA 4.0 [I] | No |
| opensearch-neural-sparse-encoding-multilingual-v1 | **Apache-2.0**, ~160M, 15 langs, doc-side encoder, query = tokenizer + IDF ("inference-free"); MIRACL nDCG@10 0.629 vs BM25 0.305 [V] | Use for learned sparse |
| LUAR (LLNL) authorship | **Apache-2.0**, 512-d [V] | Use (validate on ad text) |
| Wegmann Style-Embedding | no license stated [V] | Avoid |
| MiVOLO age | code Apache; weights license not stated [V] | Triage only |
| Hotels-50K code + ResNet-50 | **No license file** [V]; dataset ~1.03M images / 50K hotels | Benchmark only; contact TraffickCam (Stylianou, GWU) |

## 1. Per-signal survey

### (a) Face embedding, recall only (ROC verifies)
- **AuraFace-v1** (Apache-2.0): ResNet100, ~65M params [I], 512-d L2-normalized [I], 112x112 aligned input,
  native ONNX. A few points below buffalo_l on CFP-FP/CPLFW; fine for a shortlist. Pair with **YuNet** (MIT, WIDER
  Face AP 0.88/0.87/0.75, 10-300 px faces, emits 5 landmarks) and do alignment yourself.
- **SFace** (Apache-2.0): 128-d [I], tiny, LFW 0.994. CPU-only fallback.
- Throughput [I]: ResNet100@112 FP16 batched ~3,000-5,000 faces/s on A100/H100; CPU ONNX INT8 ~30-60 faces/s per
  8 cores. YuNet@320 >100 fps on one CPU core.
- ROC remains the verifier (NIST roc_021 1:1 FNMR 0.0013).

### (b) General image / scene embedding
- **PE-Core** (Apache-2.0): B/16 0.09B, **1024-d**, 224 px; L/14 0.32B, 1024-d, 336 px, IN-1k zero-shot 83.5%;
  G/14 1.88B, 1280-d, 448 px [V]. Image+text in one space.
- **SigLIP 2** (Apache-2.0): B 86M (768-d), L 303M (1024-d), So400m 400M (**1152-d**), g 1B (1536-d); NaFlex
  variants handle aspect ratio; multilingual text tower. Standard checkpoints convert to ONNX; NaFlex currently
  fails in the onnx-community converter [V].
- **DINOv3** (custom license): ViT-S/16 384-d, B/16 768-d, L/16 1024-d, H+/16 1280-d, 7B 4096-d. Best dense/patch
  features for instance matching (same room, same bedspread). Gated; no published ONNX [I].
- Throughput [I]: ViT-B/16@224 ~3,300 img/s on RTX 4090 (AMP+compile) [V timm]; ViT-L/16 ~1,040 img/s; So400m@512
  ~150-300 img/s on H100. CPU: ViT-B/16 INT8 ONNX ~40-80 img/s on 32 cores [I].

### (c) Tattoo / body-mark
- No production-grade open tattoo embedding exists [V by absence]. NIST Tatt-E had two participants. ROC markets
  tattoo 1:N (vendor claim). Open research: GAT synthetic retrieval, TattTRN (arXiv 2405.07571), forensic
  retrieval (arXiv 2606.12294); none with licensed weights.
- Practical path: detector = Apache/MIT YOLO-family (YOLOX / RT-DETR, **not** Ultralytics AGPL) trained on CC-BY
  tattoo data (Roboflow TatooDetection ~1.9K imgs CC BY 4.0 [V]) plus internal crops; embedding = PE-Core/DINOv3 on
  the crop; verifier = ROC Tattoo.

### (d) OCR for text in images
- **PP-OCRv5** (Apache-2.0): detection avg 0.770 (mobile) / 0.827 (server); recognition 0.80 / 0.84. Official
  per-image Python timings: GPU 0.62 s mobile; CPU 1.75 s [V]. Batched ONNX on GPU far higher ([I] 50-150
  full-OCR img/s on A100). Multilingual rec models for Latin/Cyrillic ads, or docTR.
- **docTR** (Apache-2.0): DBNet/LinkNet + CRNN/PARSeq, ONNX export. **Florence-2** (MIT): strong OCR-with-region
  but ~10x slower; second pass only.
- Verifier is deterministic: regex + libphonenumber + Recon normalizers (last-10-digits, lowercase handle), then
  exact-index lookup. Store OCR confidence and bbox for audit.

### (e) Ad text embedding and learned sparse
- **gte-multilingual-base** (Apache): 768-d dense (truncatable) + sparse lexical head; 8192 tokens; 70+ languages
  [V]. ~305M params. Throughput [I]: ~1,500-3,000 short ads/s on A100 FP16; CPU ~30-80/s per 16 cores.
- **bge-m3** (MIT): 1024-d + sparse + ColBERT; 568M; heavier, stronger on long text.
- Emoji/obfuscation ("c@ll", leetspeak, emoji digits): pre-normalize (NFKC, emoji-digit map, de-leet) before both
  embedding and the sparse encoder. Template-rewrite clustering (Recon ML-5): dense cosine >= ~0.85-0.9 as recall,
  MinHash shingles / shared identifiers as verifier.
- Learned sparse: **opensearch multilingual v1** (Apache) fits `LSM_SPARSE_VECTOR` directly (doc vectors ~138
  non-zeros avg; query = tokenizer + IDF table, no model at query time) [V].

### (f) Writing-style / authorship
- **LUAR-MUD / LUAR-CRUD** (Apache-2.0, 512-d) [V]; newer multi-vector LUAR-MUD-MV-Qwen improves MRR 0.57 -> 0.66.
  Domain shift to escort-ad text is real; validate against the current 24-d `styleVector` (cos >= 0.95) on labeled
  operator clusters. Keep the 24-d vector as verifier/corroborator plus identifier overlap.

### (g) Hotel-room / background matching
- Hotels-50K (AAAI'19; 1.03M train images, 50K hotels, 17,954 TraffickCam test images with occlusion masks),
  Hotel-ID 2021/2022 Kaggle, "Revisiting Hotels-50K and Hotel-ID" (arXiv 2207.10200). Repo has **no license**; a
  partnership conversation with TraffickCam (Stylianou, GWU) rather than a download.
- Recommended: DINOv3 (or PE-Core) global embedding for coarse recall; fine-tune a light projection head on
  Hotels-50K under agreement; verify with dense patch matching (DINOv3 patch tokens + mutual-NN / LightGlue-style)
  and analyst review. Mask people/faces before embedding (TraffickCam protocol) [I].

## 2. Age estimation / minor indicators
- Open: MiVOLO MAE ~4.2 (IMDB-clean), 5.55 cross-dataset; no open model publishes 13-16 MAE; academic minor-age
  work generalizes poorly (~68% for 16-17) [I].
- Commercial: NIST FATE AEV Child Online Safety (13-16) track; ROC claims #1 MAE on COS and Mugshot (ROC-002, Feb
  2026) [V vendor]; Yoti claims MAE 1.3 for 13-17 [V vendor].
- Verdict: open models are roughly 2-3x worse and unvalidated on minors; do not ship an open age model as a
  signal. For a minor-likelihood triage flag use ROC's age module (donated to DeliverFund), threshold
  conservatively, store only a band + confidence, never a scalar age, human review only.
- Legal/ethics: Illinois BIPA (consent, retention, private right of action), Texas CUBI, Washington; GDPR Art 9 /
  Law Enforcement Directive; EU AI Act Art 5 bans untargeted facial scraping and restricts real-time remote
  biometric ID with a trafficking-victim carve-out; post-remote ID is high-risk. Images of possible minors in
  commercial-sex ads may be CSAM: hash (PhotoDNA/pHash), report (18 U.S.C. 2258A), and **never embed, store or
  template**; gate the vision pipeline behind a CSAM classifier/hash check. Face templates of possible minors:
  minimum retention, legal-hold-aware deletion through every projection, purpose binding to victim_identification,
  no training use.

## 3. Multimodal (image+text) joint space
- **PE-Core** (Apache, 1024-d) and **SigLIP 2** (Apache, 768-1536-d, multilingual) are the two clean choices.
  ImageBind is CC BY-NC. Pick PE-Core B/16 for cheap 1024-d at 224 px, or SigLIP 2 So400m/16-512 if text-to-image
  quality matters more than cost (4-6x compute). Use cases: image-image ("looks like this room / tattoo /
  watermark"), analyst text-to-image ("mirror selfie, hotel bathroom, red neon"), zero-shot brand/watermark
  classification.

## 4. Compute plan
Volumes: 290M records x 3 images = **870M images** backfill (pHash dedupe plausibly cuts unique images 3-10x [I]);
daily 200K-800K ads = **0.6-2.4M images/day** plus text.

Per-image budget on one H100/A100, FP16, batched ONNX [I]:
- JPEG decode+resize is usually the real bottleneck: ~300-600 img/s per 8 cores; use nvJPEG/DALI or 32+ cores.
- YuNet ~0.3 ms; AuraFace ~0.3 ms/face; PE-Core B/16 or DINOv3-B ~0.4 ms; PP-OCRv5 det ~2-3 ms; rec ~8-15 ms per
  text-bearing image (~30-40% of ad images [I]).
- Tier A (detect+face+scene+text-presence) ~3-4 ms/img -> **~750-950 GPU-hours** for 870M.
- Tier B (OCR recognition on ~300M text-bearing images) -> **~850 GPU-hours**.
- Optional So400m@512 or DINOv3-L: **+1,000-1,400 GPU-hours**. Text (gte + sparse, 290M ads): **~100 GPU-hours**.
- **Total backfill ~1,700-3,300 H100-hours**: cloud at $2-4/GPU-h -> **$3.5K-13K**; on-prem one L40S-class box
  (~$10-15K) finishes in 3-6 weeks. Spot cloud is cheaper for the one-time backfill; on-prem wins for the daily
  stream and for keeping images inside the enclave.
- Daily: 2.4M images x ~15 ms ~ **10 GPU-hours/day** -> one mid GPU (L4/L40S/4090) with headroom.
- CPU-only: 4 SPORK nodes ~200-300 img/s -> daily feasible in 2-4 hours (steals DB cores); 870M backfill ~40-50
  days for scene+face only; OCR on CPU **infeasible** at backfill scale. GPU for backfill; small INT8 models as the
  degraded CPU path.
- Java in-process: ONNX Runtime Java (`onnxruntime` CPU / `onnxruntime_gpu` CUDA 12.8 + cuDNN 9) [V]; DJL
  `onnxruntime-engine` 0.36 [V]. `OrtSession.run` is thread-safe; export with dynamic batch axis, micro-batch
  16-64 in a dedicated pool (engine-concurrency skill). Keep image decode off the DB heap (libvips via JNI or a
  sidecar). For GPU nodes, a sidecar gRPC embedder (Triton or small ORT server) is safer than loading CUDA into the
  DB JVM; the ServerPlugin owns batching, caching and write-back.

## 5. Recommendation: minimal model set and System 1 / System 2 pairings

| # | Signal | System 1 (recall, open) | System 2 (verify) | Store per item |
|---|---|---|---|---|
| 1 | Face | YuNet (MIT) -> AuraFace-v1 (Apache) 512-d, loose cos >= ~0.35 (recalibrate) | ROC 1:1 on the shortlist (opaque ~448 B templates, BINARY with version tag) | per face: 512-d INT8 = 512 B in `LSM_VECTOR` (or PQ 64-128 B + rerank), bbox 16 B, aligned crop in object storage |
| 2 | Scene / tattoo crop / brand / cross-modal | PE-Core B/16 (Apache) 1024-d, or DINOv3 ViT-B/16 768-d if license accepted | Rooms: DINOv3 patch matching + Hotels-50K head + analyst; tattoos: ROC Tattoo 1:N; brands: zero-shot prompt + pHash exact | per image: 1024-d -> PQ 128 B + pHash 8 B + tattoo crops as extra vectors |
| 3 | Text in image | PP-OCRv5 mobile (Apache), docTR for Latin | Deterministic: libphonenumber/handle normalizers -> exact identifier index -> link graph | OCR strings + bbox + confidence (~100-300 B/img); identifiers as Phone/Social vertices with provenance=ocr |
| 4 | Ad text | gte-multilingual-base (Apache) 768-d dense (MRL 256-512) + opensearch multilingual sparse (Apache) in `LSM_SPARSE_VECTOR`; `vector.fuse` RRF with BM25 | MinHash/shingle near-dup + shared identifiers; human for risk flags | per ad: 512-d INT8 = 512 B (or 256 B) + sparse ~0.8 KB |
| 5 | Authorship | LUAR-MUD (Apache) 512-d | existing 24-d styleVector cos >= 0.95 + identifier corroboration | 512-d INT8 = 512 B (+ 96 B) |
| (6) | Minor-likelihood | none open worth shipping | ROC age band, human review only | band + confidence, no scalar age; retention-minimized |

Storage sanity (2 TB/node, 126 GB RAM): 870M images x (128 B PQ + 8 B pHash + ~130 B adjacency) ~ **230 GB**
scene; faces ~1/record x ~200 B ~ 60 GB; ads 290M x ~1.3 KB ~ 380 GB; total ~ **0.7 TB before dedupe**. Fits
alongside the corpus only with pHash dedupe, PQ, and INT8/fp16 originals EXTERNAL/off-volume. jvector's ~1.1 KB
heap per node during build and the ~10M-per-index comfort zone mean the 870M-image index must be split into many
buckets (per month or source) with staged builds, or offloaded to a dedicated ANN tier: this is the main
engineering risk, not model choice.

Open items: (1) AuraFace embedding dim and ONNX input spec from the weights; (2) DINOv3 license counsel review;
(3) TraffickCam/GWU data and model terms; (4) PP-OCRv5 multilingual rec models for Spanish/Russian/Portuguese;
(5) run timm/ORT benchmarks on the actual GPU; (6) ROC EULA position on storing shortlist results and templates in
SPORK.
