# WS13: the System 1 encoder sidecar (CPU only)

Status: implemented and tested on branch `claude/ws13-encoder-6f2eea`, module `spork-encoder/`. S-TEXT works end to
end; image decode and the MediaJob stages are interfaces and stubs. Not merged.

Train 1 item WS13 of `01-architecture.md` section 8, building 4c.4: a separate process per node, in the `encode`
cgroup (4 cores, 2 on n1; 0.8 and 1.4), that turns text (and later images) into the vectors and codes the
`LSM_IVF_VECTOR` spaces store. No GPU path (round 10: the hardware is fixed, no GPU).

## What was built

| Requirement | Where | Notes |
|---|---|---|
| Separate process, not in the ArcadeDB JVM | `EncoderMain` | `java -cp $(cat spork-encoder/target/classpath.txt):spork-encoder/target/spork-encoder-*.jar com.arcadedb.spork.encoder.EncoderMain [--config encoder.json]`; heap about 1 GB, weights live in ONNX Runtime's native memory |
| ONNX Runtime CPU, int8 models | `OnnxTextEncoder` | `onnxruntime` 1.31.0 (MIT), one shared `OrtSession` per model, per-thread direct input buffers, spinning off so idle threads leave the cgroup to the database |
| Model registry keyed `algo@ver` | `ModelRegistry`, `ModelId`, `ModelManifest` | `models/<algo>/<ver>/model.json`; SHA-256 of every model and tokenizer file checked at load; `spaces.json` names the active model when a space has several; every response carries the id |
| S-TEXT end to end | `OnnxTextEncoder`, `Pooling`, `VectorCodes` | tokenizer (Hugging Face tokenizers via DJL, natives in the jar, offline), padding to the batch's longest text, CLS pooling (in the graph), Matryoshka truncation 768 to 256, L2 normalization, codes |
| Probe cache | `ProbeCache` | 16-way striped LRU, 50,000 entries by default (about 55 MB), keyed by 128 bits of SHA-256 over model id and text; probe lane only |
| HTTP API | `EncoderHttpServer` | JDK `HttpServer` on virtual threads (no new dependency), JSON with base64 codes; see [API](#api) |
| Thread and batch sizing | `EncodeScheduler`, `EncoderConfig` | see [Thread and batch sizing](#thread-and-batch-sizing) |
| Golden test | `GteGoldenTest` + `src/test/resources/golden/gte-multilingual-base@1.json` | see [Golden test](#golden-test) |
| Throughput | `TextEncoderThroughputBenchmark` (`@Tag("benchmark")`) | see [Throughput](#throughput) |
| Image decode, MediaJob stages | `image/` | `ImageDecoder`, `DecodedImage`, `MediaJobStage`, `MediaEncodeStage`; `LibvipsImageDecoder` is a stub; `/v1/encode/image` answers 501 |

Tests: 43 in the module (`mvn -o -pl spork-encoder test`), all passing; the 5 golden tests skip with a message when the
model has not been exported, so CI without the 325 MB model stays green and says why.

## The S-TEXT model

| | |
|---|---|
| Model | `Alibaba-NLP/gte-multilingual-base`, revision `9bbca17d9273fd0d03d5725c7a4b0f6b45142062`, Apache-2.0 (checked on the model card and repository metadata) |
| Modeling code | `Alibaba-NLP/new-impl`, revision `40ced75c3017eb27626c9d4ea981bde21a2662f4`, Apache-2.0; downloaded at that revision and imported from the snapshot, never through `trust_remote_code` |
| Weights file | `model.safetensors` SHA-256 `f5a35a10faa54da7717870af1517c9b41e9bd8e3880bc5a8e9363d4c3c63e9b0` |
| Registry id | `gte-multilingual-base@1`, space `S-TEXT`, 256-d, max 512 tokens |
| Exported file | `model_int8.onnx`, 325 MB, SHA-256 `40930e64fa203bd1b57d6617f6b0e7190fc1eb606721349f6102f1f07cb05267` (this export, Python 3.12, torch 2.5.1, onnx 1.17.0, onnxruntime 1.23.2) |
| Tokenizer | the repository's `tokenizer.json`, unchanged, SHA-256 `f59925fcb90c92b894cb93e51bb9b4a6105c5c249fe54ce1c704420ac39b81af` |

Rebuild it with the pinned toolchain:

```bash
uv venv -p 3.12 .venv-export && uv pip install -p .venv-export/bin/python torch==2.5.1 transformers==4.46.3 onnx==1.17.0 onnxruntime==1.23.2 huggingface_hub==0.26.5 "numpy<2.2"
```

```bash
.venv-export/bin/python spork-encoder/scripts/export_gte.py
```

It writes `spork-encoder/models/gte-multilingual-base/1/` (git-ignored) and the golden file. The export pins both
revisions, so it never downloads from anywhere but the two official repositories. The ONNX graph is traced at fp32
(max difference to PyTorch 7.2e-7), then quantized.

Two export details: the graph slices the CLS token itself (`cls_embedding`, `[batch, 768]`), so 768 floats per text
leave ONNX Runtime instead of `tokens x 768`; and the NTK-scaled rotary tables are cut from 65,536 to the model's
8,192 positions (33 MB of constants; rows below 8,192 are unchanged).

### Quantization

"int8" is not one thing, and the first choice was wrong. Measured on 58 texts against the fp32 PyTorch embeddings
(256-d, normalized):

| Variant | Size | Cosine to fp32, min / mean | Same text batched vs alone, min cosine |
|---|---|---|---|
| `quantize_dynamic` QInt8 (ORT default) | 310 MB | 0.942 / 0.961 | 0.912 |
| same, per-channel weights | 311 MB | 0.947 / 0.970 | 0.935 |
| per-channel, FFN `down_proj` left fp32 | 396 MB | 0.987 / 0.991 | 0.984 |
| **MatMulNBits 8-bit, block 32, accuracy level 4 + int8 embedding table (chosen)** | **325 MB** | **0.9993 / 0.9995** | **0.9999** |
| MatMulNBits 8-bit, fp32 embedding table | 900 MB | 0.9995 / 0.9997 | 0.9999 |
| MatMulNBits 8-bit + 4-bit embedding table | 252 MB | 0.9966 / 0.9986 | 0.9999 |

Dynamic quantization picks one activation scale per tensor, and the tensor is the whole padded batch: a text's vector
depended on its batch-mates and their padding, so a probe would not reproduce the vector stored for the same text at
ingest. The FFN down projections' activation outliers caused most of the accuracy loss. MatMulNBits quantizes
activations per token row in blocks of 32, so rows never see each other, and keeps the int8 compute (3.7x faster than
fp32 in a single-thread check during the selection). The batched-vs-alone floor of 0.9999 is now kernel accumulation
order, not quantization.

## Code format (4c.2)

Every vector is 256 floats, unit length, tagged `gte-multilingual-base@1`. The response can carry, per text:

| Output | Bytes at 256-d | Layout |
|---|---|---|
| `float32` | 1,024 | little-endian floats: the canonical vector. The IVF codebook trains on it, and any centroid-relative code (RaBitQ-style residual) is computed from it by the index, which alone knows the centroids |
| `binary` + `binaryMedian` | 32 + 4 | the engine's `BINARY` layout: bit `i` of byte `i / 8` set when component `i` is at or above the vector's median, with `LSMVectorIndex.quantizeToBinary`'s constant-vector rule. The 1-bit scan code of 4c.1 |
| `int4` + `int4Min` + `int4Max` | 128 + 8 | min-max to 0..15, two components per byte, even component in the low nibble. The 4-bit rerank code of 4c.1 |
| `int8` + `int8Min` + `int8Max` | 256 + 8 | the engine's `INT8` layout (min-max to -128..127) |

The two centroid-free codes match the sizes 4c.1 budgets (32 B scan, 128 B rerank). E18 is not in code yet; if it
settles on residual codes it derives them from `float32` and the sidecar does not change. `VectorCodes` is the single
place a different absolute layout would go.

## API

`EncoderHttpServer`, bound to `127.0.0.1:2495` by default (the callers, the SPORK plugin and the ingest saga, run on the
same node; ArcadeDB's HTTP range is 2480 to 2489). Binding any other address requires `authToken`, checked as
`Authorization: Bearer` in constant time.

```
POST /v1/encode/text
{"space": "S-TEXT", "texts": ["..."], "lane": "probe", "outputs": ["float32", "binary", "int4"]}
-> {"model": "gte-multilingual-base@1", "space": "S-TEXT", "dimensions": 256,
    "items": [{"float32": "<base64>", "binary": "<base64>", "binaryMedian": 0.0012, "int4": "<base64>",
               "int4Min": -0.21, "int4Max": 0.19, "cached": false}]}
```

- `"model": "algo@ver"` instead of (or with) `"space"` pins a version: a backfill pins the id its codebook was trained
  for, so it never drifts onto a newer model mid-run.
- `lane`: `probe` (default; query time, cached, served first) or `bulk` (ingest and backfill).
- Limits: 256 texts and 8 MB per request; longer texts are truncated to 512 tokens by the tokenizer.
- Status: 400 malformed, 401 token, 404 unknown space or model, 405 method, 413 too large, 429 queues full (with
  `Retry-After: 1`; the saga backs off), 500 model failure, 504 timeout.
- `GET /v1/models` (manifests without file paths, `active` per space), `GET /v1/health`, `GET /v1/stats` (queue depths,
  rejections, texts encoded, busy time, cache hits and misses).
- `POST /v1/encode/image`: 501 until the image stages land.

Settings (`--config encoder.json`, each key overridable by `-Dspork.encoder.<key>`): `modelsDirectory`, `host`, `port`,
`authToken`, `cores`, `workers`, `intraOpThreads`, `probeMaxBatch` (16), `bulkMaxBatch` (32), `probeQueueCapacity`
(256), `bulkQueueCapacity` (64 micro-batches), `maxTextsPerRequest`, `maxRequestBytes`, `probeTimeoutMs` (10 s),
`bulkTimeoutMs` (300 s), `probeCacheEntries` (50,000).

## Thread and batch sizing

`EncodeScheduler` is the sidecar's only compute pool (the engine-concurrency rules, applied to a process of its own):
a fixed set of platform worker threads, each running one model call at a time; HTTP requests run on virtual threads
that only parse, wait and serialize, so the request count never changes the CPU used.

- **Probe lane first.** A free worker takes waiting probes before any bulk work and coalesces up to 16 of them into one
  call, so a burst of searches costs one model call. The worst a probe waits behind bulk work is one micro-batch.
- **Bulk micro-batches.** A bulk request is sorted by text length and cut into micro-batches of 32, so a batch pads to
  texts of similar length. Admission is all or nothing; a full queue answers 429 instead of queueing hours of backfill in
  front of the probes.
- **Tokenizer threads.** Texts are tokenized one by one on the worker: the tokenizer's batch call fans out over its own
  Rayon pool, which would escape the cgroup sizing.
- **Default split.** `workers = cores / intraOpThreads`, `intraOpThreads = 1`, from `Runtime.availableProcessors()`,
  which honours the cgroup's CPU quota: 4 workers x 1 thread on n2 to n4, 2 x 1 on n1. The measurements below are the
  basis; `intraOpThreads = cores` (one call using every core) is the better setting only for a node that serves probes
  and no bulk work, where single-probe latency is all that matters.

## Throughput

Measured 2026-11-03 with `TextEncoderThroughputBenchmark` on this development machine: Apple M5 Max (aarch64, 18
CPUs, mixed performance and efficiency cores), OpenJDK 26.0.1, ONNX Runtime 1.31.0, the `gte-multilingual-base@1`
int8 file above, a budget of 4 cores (the `encode` cgroup of n2 to n4), 8 s per point after a warm-up, synthetic
multilingual ad text cut to an exact token count (special tokens included).

```bash
mvn -o -pl spork-encoder test -Dtest=TextEncoderThroughputBenchmark -DexcludedGroups= -Dgroups=benchmark -Dspork.bench.cores=4 -Dspork.bench.seconds=8
```

**Read these as upper bounds on cost.** The machine was shared throughout: the load average rose from 26 to 88 on 18
CPUs during the run (other sessions' builds and an unrelated desktop application), so the four benchmark threads
competed for cores and could land on efficiency cores. The production nodes are different CPUs. P0a re-runs the same
command on a node, inside the real cgroup.

Thread split and micro-batch, 128 tokens:

| workers x intra-op | micro-batch | texts/s | core-ms per text |
|---|---|---|---|
| 4 x 1 | 1 | 13.3 | 300.9 |
| 4 x 1 | 8 | 15.9 | 251.0 |
| 4 x 1 | 32 | 16.6 | 240.3 |
| 2 x 2 | 1 | 8.0 | 502.7 |
| 2 x 2 | 8 | 5.7 | 701.7 |
| 2 x 2 | 32 | 16.4 | 243.5 |
| 1 x 4 | 1 | 14.8 | 270.2 |
| 1 x 4 | 8 | 19.4 | 205.7 |
| 1 x 4 | 32 | 16.2 | 247.4 |

Token length, 4 workers x 1 thread, micro-batch 32 (the default), with what it would mean for the 145M unique texts of
the S-TEXT backfill (0.8) on the 16 encode cores:

| tokens | texts/s on 4 cores | core-ms per text | S-TEXT backfill, 145M texts on 16 cores |
|---|---|---|---|
| 16 | 121.1 | 33.0 | 3.5 days |
| 32 | 52.9 | 75.7 | 7.9 days |
| 64 | 29.7 | 134.5 | 14.1 days |
| 128 | 15.6 | 257.1 | 25.2 days (240.3 core-ms in the first table: 2 runs of the same point differ by 7%) |
| 256 | 9.7 | 411.8 | 43.2 days |
| 512 | 2.5 | 1,573.7 | not meaningful: attention is quadratic and the point ran at the peak of the contention |

One probe on an otherwise idle sidecar (100 probes after 20 warm-up, one worker):

| intra-op threads | tokens | p50 ms | p95 ms |
|---|---|---|---|
| 1 | 16 | 22.1 | 27.6 |
| 1 | 64 | 79.7 | 93.4 |
| 1 | 128 | 259.3 | 555.8 |
| 4 | 16 | 50.5 | 179.5 |
| 4 | 64 | 184.7 | 519.9 |
| 4 | 128 | 158.4 | 282.0 |

What the numbers say:

- **Micro-batching helps a little, not a lot**: 4 x 1 goes from 13.3 to 16.6 texts/s between batch 1 and batch 32 at
  128 tokens. On CPU a single text already gives the matmuls 128 rows. Length sorting matters more than batch size,
  because padding is paid in full.
- **Splitting a call across threads did not pay under contention.** 2 x 2 and 1 x 4 land within noise of 4 x 1 at
  batch 32, and are worse at small batches, where a sleeping intra-op helper has to be woken for every operator.
  4 x 1 stays the default: same throughput, and four independent calls keep serving probes while one long bulk batch
  runs. Single-probe latency is where intra-op threads should help, and on a loaded machine they did not; re-measure
  on a node before using them on n2 and n3.
- **Cost scales with token length**, close to linearly up to 256. The 60 ms per unique text that 0.8 uses for the
  S-TEXT backfill (6.3 days on 16 cores) matches about 30 tokens here. If unique ad texts average around 100 tokens
  and a node core is no faster than this contended one, the S-TEXT backfill is closer to 20 days than 6.3, which
  does not fit the 9.4-day day-one window that starts by 2027-01-20. This is an upper bound from a shared laptop, not a
  node measurement, but it is the most important open number from this workstream: P0a should measure the
  token-length distribution of the unique texts (A5) and re-run this benchmark on a node. The levers if it holds are
  a lower `maxTokens` for the backfill (ad text past 128 or 256 tokens is mostly boilerplate, but truncation is a
  relevance decision), more cores for the `encode` cgroup during the backfill window, or a later S-TEXT completion
  than S-PDQ.

## Golden test

`GteGoldenTest` (runs whenever the model is exported) checks the Java pipeline against the reference
`export_gte.py` computed once from the pinned revision, on 10 texts: English, Spanish, French, Chinese, Russian and Thai
ad-like texts, a leetspeak and emoji one, a short scene query, a one-letter text and a 5,400-character text that
truncates at 512 tokens.

| Check | Bar | Measured |
|---|---|---|
| Token ids equal the Hugging Face tokenizer's, truncation included | exact | exact |
| Each text alone vs the fp32 PyTorch embedding | cosine > 0.999 | >= 0.9993 |
| Each text alone vs Python ONNX Runtime 1.23 on the same int8 file | cosine > 0.9995 | >= 0.99984 (Java runs ORT 1.31; the kernels differ) |
| All 10 in one padded batch vs each alone | cosine > 0.9999 | passes |
| Over HTTP, probe lane vs bulk lane for the same text | cosine > 0.9999 | passes; binary 32 B, int4 128 B |

## libvips

libvips is **LGPL-2.1-or-later** (its `LICENSE`, checked 2026-11-03; latest release v8.18.7, 2026-09-26). CLAUDE.md
allows LGPL 2.1+ for libraries only, unmodified. Decision:

- Load it dynamically (JNA, already on the classpath through the tokenizer, or the FFM API once the build is past Java
  21) from an **unmodified upstream release** installed on the node; never vendored, patched or shipped inside a SPORK
  artifact, so it stays replaceable as the LGPL requires.
- **Not the distribution package.** Debian's and Ubuntu's libvips link GPL code into the process: poppler-glib (PDF
  load, GPL-2.0) and libimagequant (palette PNG save, GPL-3.0). The node build is configured from the release tarball
  with only permissive or LGPL loaders (libjpeg-turbo, libspng, libwebp, libheif, libtiff, cgif, libexif, lcms2,
  glib/gobject) and without poppler, ImageMagick, libimagequant and cfitsio. Configuring a build is not modifying the
  library.
- `LibvipsImageDecoder.available()` will refuse to load a libvips whose module list shows any of those GPL loaders.
- Decoding runs on the scheduler's workers with `VIPS_CONCURRENCY=1` and shrink-on-load (`vips_thumbnail_buffer`),
  and rejects images whose header declares more than a pixel budget before decoding (decompression bombs).

Until then `image/` holds the interfaces: `ImageDecoder`, `DecodedImage`, `MediaJobStage` (DECODE, CSAM_GATE, PDQ,
FACE_DETECT, FACE_EMBED, SCENE_EMBED, TEXT_PRESENCE, OCR in dependency order; a test checks that no model stage can run
before the CSAM gate) and `MediaEncodeStage` (one stage implemented by one model, batched like texts, results tagged
`algo@ver`).

## Dependencies and licenses

| Dependency | Version | License |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime` (CPU) | 1.31.0 (2026-10-09) | MIT |
| `ai.djl.huggingface:tokenizers` | 0.38.0 (2026-09-09) | Apache-2.0 |
| `ai.djl:api`, transitive | 0.38.0 | Apache-2.0 |
| `net.java.dev.jna:jna`, transitive | 5.17.0 | Apache-2.0 / LGPL-2.1+ |
| gte-multilingual-base weights and tokenizer | revision 9bbca17 | Apache-2.0 |

Newer releases existed (onnxruntime 1.31.1, DJL 0.39 and 0.40) but were under two weeks old. `license-maven-plugin`
plus `.github/scripts/check-license-allowlist.py` on the module: all 66 dependency licenses are on the allow-list.
`ATTRIBUTIONS.md` lists the libraries and the model. The tokenizers jar also carries Windows natives with libgcc and
libstdc++ (GPL-3.0 with the GCC runtime library exception); Linux nodes never load them.

## Deferred / follow-ups

- **Image stages**: the libvips binding and its build on the nodes, then AuraFace-v1 + YuNet (S-FACE) and the S-IMG
  model once P0d settles them; PDQ hashing is WS12's.
- **Re-measure on the nodes in P0a** with the benchmark command above, and replace 0.8's 60 ms per text with a figure
  weighted by the measured token-length distribution of unique texts; this decides whether the S-TEXT backfill fits
  its window (see [Throughput](#throughput)).
- **Probe cache warm-up** across restarts is not done (the cache is in memory only).
- **The sparse leg** (OpenSearch neural-sparse multilingual v1 doc encoder) is not in this workstream.
- **gRPC**: HTTP/JSON was chosen for train 1 (no new dependency, the plugin already speaks it); a binary endpoint is the
  cheap upgrade if base64 JSON shows up in the saga's profile.
