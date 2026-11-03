# WS12: PDQ perceptual hashing and near-duplicate lookup (engine part)

Status: library, SQL function, band scheme and hash-gate hook implemented and tested on branch `claude/ws12-pdq-00bd6b`,
2026-11-03. The ingest hook is a follow-up (it needs the WS2 schema and WS5 ingest). Architecture references:
`01-architecture.md` 4c.1 (S-PDQ), 5.7 (the hash gate) and the WS12 row of 8; `06-stylometry-perceptual-hash.md` 3.1
(band keys).

## What is built

| Piece | Where | What |
|---|---|---|
| `PdqHasher` | `engine/.../similarity/` | Meta's PDQ (256 bits + quality 0 to 100), ported from the BSD Java reference in facebook/ThreatExchange; bit-identical output; the 8 dihedral hashes from one DCT; reusable per-thread instance, static helpers |
| `PerceptualHash` (changed) | same | `Method.PDQ` and `quality()` now delegate to `PdqHasher` (full-resolution decode), so the codebase has one PDQ; the 64-bit methods are unchanged; the decoder is shared (`decodeImage`) |
| `BitHash.toBytes` | same | a hash as big-endian bytes (32 for PDQ), the compact BINARY form; `BitHash.parse` already read it back |
| `PdqBands` | same | the `pdqBands` scheme: `hamming.bandKeys(pdq, 8)` keys, sorted and de-duplicated probes for one hash or the 8 dihedral hashes, probe counts |
| `ImageHashGate`, `HashGateDecision`, `ImageDigest` | same | the 5.7 hook: decide on hashes (SHA-256, MD5, PDQ with dihedral and quality) before any vision model; CLEAR / MATCH / UNAVAILABLE (fail closed); `checkThen` runs the vision stage only on CLEAR; `all(...)` composes lists |
| `image.pdq(image [, dihedral])` | `function/sql/similarity/` | `{hash, quality}` (and `hashes` with `dihedral = true`), callable from SQL and Cypher; classified repeatable for the correlated subquery cache |

No dependency was added: ImageIO decodes, as the reference does. Meta's notice is in `ATTRIBUTIONS.md` and in the two
derived files.

## Parity with the reference

The port keeps the reference's arithmetic operation for operation in `float`: the Rec. 601 luminance, two X-then-Y
passes of the Jarosz box filter (window `ceil(side / 128)`, the four-phase running sum, division by the current window),
decimation at the pixel centres, the image-domain quality, the 16x16 DCT block after DC summed in the same order, the
Torben median and the bit packing. What changed is the data flow, not the arithmetic:

- **Streaming.** The reference holds two `float` copies of the whole image (96 MB for a 4000 x 3000 photo). The port
  converts and X-filters each row as it reads it, runs each column pass on a ring of `window + 1` rows with one running
  sum per column (every column still sees its additions and subtractions in the reference's order), and keeps only the 64
  sampled columns for the second Y pass. Working set: about `(ceil(height / 128) + 1) x (width + 64)` floats, bounded by
  the pixel budget for any shape, reused across calls.
- **Raster reads.** `TYPE_3BYTE_BGR`, `TYPE_4BYTE_ABGR`, `TYPE_INT_RGB` and `TYPE_INT_ARGB` are read from the raster,
  where `getRGB` returns the channels unchanged; every other layout (grey, 16-bit, indexed, premultiplied) goes through
  `getRGB` as in the reference.

Verification (`PdqHasherTest`):

- the reference's regression output (`pdq/java/reg_test/expected/out`) on four of its test photos
  (`engine/src/test/resources/similarity/pdq/`), hash and quality, exact;
- the 8 `--pdqdih` dihedral hashes of the original, exact;
- the reference's own unit test: rotate-90 at distance 120, the grey-scale conversion at 0, the half-resolution redraw at 4;
- bit-for-bit agreement with `PdqReferenceTranscription` (the reference's structure written out literally) on 88
  synthetic images: 8 pixel layouts x 11 shapes from 1 x 1 to 1100 x 260, including 3 x 2000 and 300 x 7, with a fresh
  and a reused hasher.

Decoders matter: the C++ reference (CImg) differs from the Java one by a few bits on these JPEGs, so parity is with the
Java reference and ImageIO. The reference ignores EXIF orientation, and so does the port; the dihedral hashes cover
rotated re-posts.

## Usage

```sql
-- hash and quality in one call; 64 hex digits as hash lists print them
SELECT image.pdq(bytes) AS p FROM Image WHERE @rid = :rid
-- {"hash": "f8f8f0ce...", "quality": 100}

-- mirrored and rotated re-posts: compare against the 8 dihedral hashes (hamming.distance takes the smallest)
SELECT hamming.distance(image.pdq(:probe, true).hashes, pdq) AS d FROM Image WHERE ...

-- store 32 bytes and the band keys (hot-window and case databases only, 4c.1)
UPDATE Image SET pdq = :pdqBytes, pdqQuality = :q, pdqBands = hamming.bandKeys(:pdqBytes, 8) WHERE @rid = :rid

-- near duplicates through the band index: radius 15 for re-encodes and ingest dedup, 31 for the reference cut
SELECT FROM Image WHERE pdqBands CONTAINSANY hamming.probeKeys(:q, 8, 15) AND hamming.distance(pdq, :q) <= 15
```

```cypher
CALL similarity.hash.neighbors($pdq, {type: 'Image', property: 'pdq', bandsProperty: 'pdqBands', bands: 8,
                                      maxDistance: 31, qualityProperty: 'pdqQuality'})
YIELD node, distance
```

From Java, the enrichment worker keeps one `PdqHasher` per thread (`hasher.dihedral(bytes)`) and probes with
`PdqBands.probeKeys(dihedral.hashes(), radius)`, which are sorted, so the LSM index is read in key order and the eight
queries share their lookups.

## `pdqBands` and radius 31

`pdqBands` is the existing multi-index hashing keys with PDQ's parameters (8 bands of 32 bits), not a new mechanism.
Recall is exact at any radius by the pigeonhole principle. `PdqBandsTest` checks it on an LSM `BY ITEM` index: 4,000
random hashes plus 24 query centres with planted neighbours at 0 to 40 bits, both random and spread evenly over the 8
bands (at 31 bits only one band is within 3 bits, the worst case); the index lookup, `hamming.probeKeys` from SQL and
`similarity.hash.neighbors` all return exactly the brute-force set at radius 31.

Measured cost (200,000 stored hashes, one thread, this machine):

| Radius | Probe keys | Per query | Random candidates |
|---|---|---|---|
| 15 | 264 | 0.82 ms | 0.01 |
| 31 | 43,912 | 138 ms | 1.7 |

The brief asked for corpus-wide radius-31 lookup on the band index. It works and is exact, but 138 ms per query (about
3 us per point lookup, before real hashes cluster) and 194 GB of band entries at 3x are why the architecture keeps the band
index on the hot-window `media_<p>` and case databases and puts corpus-wide radius 31 on the S-PDQ IVF space (4c.1,
06 3.1, round 6). Nothing here changes that decision: radius 31 on the band index is fine for a case database or an
analyst's one-off probe, and ingest dedup uses radius 15.

## The hash gate (5.7)

`ImageHashGate.check(ImageDigest)` answers CLEAR, MATCH (list, algorithm, distance) or UNAVAILABLE (reason).
`checkThen(digest, visionStage)` is the one place the order is fixed: the vision stage runs only on CLEAR, never on a
match or an unanswered check, and a gate that answers null is an error. `ImageHashGate.all(...)` composes lists: the
first MATCH wins, a list that throws or cannot answer makes the result UNAVAILABLE, CLEAR needs every list to answer
CLEAR. The contract for implementations (in the Javadoc): fail closed, never match PDQ below quality 50, compare all 8
dihedral hashes, be thread safe. `ImageHashGateTest` exercises it with an in-memory test double (exact SHA-256 and MD5,
PDQ re-encode and mirror matches, the flat-image quality rule, an unreachable list, composition). No hash list and no
connector to one is shipped.

## Throughput on this machine

Apple M5 Max, 18 cores, JDK 21, while sibling sessions kept the load average at 28 to 40, so these are pessimistic and
noisy. `PdqThroughputBenchmark` (`@Tag("benchmark")`, run with
`mvn -o -pl engine -am test -Dtest=PdqThroughputBenchmark -Dgroups=benchmark -DexcludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false`).
Synthetic JPEG scenes; "reference" is the literal transcription, the reference's data flow.

| Image | JPEG | Decode | PDQ hash (port) | PDQ hash (reference flow) | Allocated per hash, port | Allocated per hash, reference flow |
|---|---|---|---|---|---|---|
| 640 x 480 | 12 KB | 1.6 ms | 2.1 ms | 12.0 ms | 0.1 KB | 9.4 MB |
| 1024 x 768 | 25 KB | 2.8 ms | 4.1 ms | 27.2 ms | 0.1 KB | 24.0 MB |
| 1600 x 1200 | 48 KB | 5.4 ms | 8.7 ms | 46.6 ms | 0.1 KB | 58.6 MB |
| 4032 x 3024 | 240 KB | 29.0 ms | 58.0 ms | 402 ms | 0.1 KB | 372 MB |

End to end (decode, PDQ, 8 dihedral hashes) on 1024 x 768 JPEGs: 113 images/s on 1 thread (8.8 ms each), 388 on 4,
899 on 16. The single-thread figure is under the 10 ms of CPU per image that `budget.py` plans for the S-PDQ backfill
(435M images, 3.1 days on the 16 encode cores); the 16-thread figure on this loaded machine would be 5.6 days, so the
backfill figure is still a P0a measurement on the real nodes and real ad photos. Decode is now about 40% of the cost;
image fetch I/O is not counted.

## Follow-ups

1. **Ingest hook (WS5 + WS2).** In the enrichment worker, per distinct image: `ImageDigest.of(bytes)`, then
   `gate.checkThen(digest, ...)` before YuNet, AuraFace, the scene model or OCR; on MATCH set `Image.csamHashHit` (WS2
   `media.sql` already has it), start the report path and store nothing beyond the hash and report record; on
   UNAVAILABLE hold the image for retry. On CLEAR store `Image.pdq`, `pdqQuality`, and (hot window only) `pdqBands`,
   probe radius 15 for ingest dedup, and assign PDQ-cluster representatives (`pdqClusterRep`). Needs WS2 merged and
   WS5's MediaJob stages; one `PdqHasher` per worker thread on the dedicated encode pool (engine-concurrency rules).
2. **Schema (for WS2's `media.sql`, which leaves these to WS12):**
   ```sql
   CREATE PROPERTY Image.pdq IF NOT EXISTS BINARY;              -- 32 bytes, BitHash.toBytes
   CREATE PROPERTY Image.pdqQuality IF NOT EXISTS SHORT;        -- 0 to 100
   CREATE PROPERTY Image.pdqClusterRep IF NOT EXISTS LINK;      -- representative of the PDQ cluster (S-IMG membership)
   -- hot-window media_<p> and case databases only (4c.1):
   CREATE PROPERTY Image.pdqBands IF NOT EXISTS LIST OF LONG;
   CREATE INDEX IF NOT EXISTS ON Image (pdqBands BY ITEM) NOTUNIQUE;
   ```
3. **A real gate.** PhotoDNA through the qualified reporting interface and the known-hash lists (NCMEC MD5 and PDQ,
   ThreatExchange) are owner and legal decisions; the implementation binds to `ImageHashGate` and follows its contract.
4. **S-PDQ IVF (E18, E19).** Corpus-wide radius 31 belongs to the `LSM_IVF_VECTOR` index with the `HAMMING` metric;
   the 32-byte `Image.pdq` is its stored code.
5. **WebP and HEIC.** Still not decodable by the JDK (06 open question 3); ads in those formats cannot be hashed or gated
   until a decoder plugin is approved, which the gate must treat as UNAVAILABLE, not CLEAR.
6. **Small:** `similarity.hash.neighbors` could sort its probe keys as `PdqBands` does, for key-ordered index reads.

## Verification

- `PdqHasherTest` (7): reference regression hashes and quality on four photos, the 8 dihedral hashes, the reference's own
  unit test, the streaming port against the literal transcription on 88 synthetic images, luminance input, the
  `PerceptualHash` delegation, flat images and refused input.
- `PdqBandsTest` (2): keys equal `hamming.bandKeys(pdq, 8)`, probe counts 264 and 43,912, sorted unique unions; exact
  radius-31 recall on an LSM index against brute force through Java, SQL and the Cypher procedure.
- `ImageHashGateTest` (7): exact and MD5 matches, re-encode and mirror PDQ matches, CLEAR running the vision stage once,
  fail-closed UNAVAILABLE and null, the quality rule, composition, digest contents.
- `SimilaritySQLFunctionsTest.imagePdqReturnsTheReferenceHashAndQuality`: `image.pdq` from SQL and Cypher, dihedral,
  BINARY storage with `hamming.distance` and `hamming.bandKeys`, refused arguments.
- `LetQueryStepCorrelatedResultCacheTest`: `image.pdq` classified repeatable.
