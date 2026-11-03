# Workstream: BM25 top-k for full-text search

Retrieval speed is the owner's first directive. A full-text query with a limit (`SEARCH_INDEX(...) ORDER BY $score DESC
LIMIT 20`, `db.index.fulltext.queryNodes(...)` with a limit, `FullTextSearch.search(index, query, k)`) used to cost far more
than its 20 rows: the general path materializes every match before scoring it.

## What the general path does (unchanged, still the fallback)

For each bucket index, `FullTextQueryExecutor.collectQueryMatches` walks every posting of every query term and puts each match
in a `Map<RID, AtomicInteger>`; `FullTextSearch.searchBM25` copies the match set into a `Set<RID>`; scoring
(`LSMTreeFullTextIndex.computeBM25Scores`) seeds a `Map<RID, double[]>` with every candidate, walks every posting a SECOND
time collecting the candidate postings into a list, and only then ranks with a bounded heap. For a term in 800,000 documents
that is two full posting scans and four structures holding 800,000 boxed entries each, to return 20 rows.

## What changed

`FullTextTopK` answers a BM25 query with a limit when the query is made of plain terms:

- **Eligible:** term clauses with boosts and field qualifiers, combined with OR (`a b`), AND (`+a +b`, `a AND b`, or the
  `defaultOperator: AND` index setting) or required plus optional terms (`+a b`); a nested group of optional terms under an
  optional clause, or of required terms under a required clause. Planned by `FullTextQueryExecutor.planTopK` from the same
  parsed query, with the same stored keys and boosts the general path records.
- **Not eligible, general path:** phrases, wildcard / prefix / fuzzy / regexp expansions, `NOT` / `-term`,
  minimum-should-match, a RID filter pushed down from the SQL `WHERE` (#9909/#11069), CLASSIC similarity, queries without a
  limit, more than 63 terms, MoreLikeThis.
- **How:** pass one reads each term's postings of each bucket ONCE into primitive arrays (RID packed in a `long`, tf, document
  length) and counts the type-wide document frequencies on the way; pass two scores each bucket against the pooled statistics,
  a single term straight into a primitive top-k heap, several terms term-at-a-time into one open-addressing table of
  primitives (score + bitmask of the terms matched, which decides AND), then the heap. Each bucket keeps its best k, and the
  union is what the general path returns too (the per-bucket top-k merge of `FullTextSearch.searchBM25` is preserved: scores
  are comparable because every bucket scores with the same type-wide N, avgdl and df; a single bucket scores with its own
  statistics, as before).
- **Identical results:** same documents, same `float` scores, same tie-break (higher score, then lower RID). To make the
  scores bit-identical, the general path now sums a document's term contributions in key order (it used the hash order of a
  map); the top-k scorer sums in the same order. `FullTextTopKEquivalenceTest` compares both paths on random Zipf-like
  corpora (4 index shapes: several buckets, one bucket, multi-property with field boosts, `defaultOperator: AND`; deletions and
  updates applied) over 400 random queries and limits, and SQL `ORDER BY $score DESC LIMIT` end to end; mutating the AND
  filter or the tie-break makes it fail.
- **Switch:** `arcadedb.fullText.topK` (database scope, default `true`) forces the general path when `false`.

## Skipping postings: the block-max sidecar (index format 2)

The scan above still reads every posting of every query term once: an LSM-Tree key's postings come back in page order, not
RID order, and cannot be addressed by block, so WAND / Block-Max MaxScore had nothing to skip over. Format 2 adds a second,
derived layout next to each BM25 bucket index that makes skipping possible.

### Layout

`BlockMaxPostings` writes `<database>/fulltext-blockmax/<bucket index name>.bmx`. For every term with at least 128 postings in
the bucket: the postings sorted by RID in blocks of 128 (RID delta, tf, document length as varints); per block, its
**impacts**, the (tf, document length) pairs no other posting of the block beats on both; and a 48-byte block header (last RID,
payload offset and length, count, max tf, min length, impacts offset and length). A term dictionary in key order with a sparse
in-memory index (every 64th key) finds a term's headers with one read. Terms rarer than one block are left out (they have
nothing to skip, and their headers would make the file bigger than the index): a query reads them from the LSM-Tree.

A block's upper bound is computed at query time as the best impact scored with the query's idf, boost and the corpus avgdl:
BM25 grows with tf and shrinks with length, so no posting of the block scores more, whatever the avgdl is when the query
runs. (Storing max tf and min length only, as first designed, takes them from two different postings and bounds a block of a
frequent term far above any score it holds; on a Zipf corpus that skipped almost nothing. The impact frontier is what Lucene
stores for the same reason.) Bounds are inflated by 1e-9 and a block or document is dropped only when its bound stays below
the float just under the k-th best score, so rounding can never drop a document that would have entered or tied.

### What it is a snapshot of, and how it stays exact

The sidecar is a snapshot of the **compacted part** of the LSM-Tree: the series of the compacted file whose root page is below
a recorded mark, resolved among themselves (`LSMTreeIndexMutable.searchCompactedOlderThan`). Everything newer, the mutable
pages and any series appended by later compactions, is read at query time through the LSM-Tree's own newest-first walk
(`LSMTreeIndexMutable.searchNewerThan`), which also collects the tombstones that delete or re-write snapshot postings. A query
term's live postings in a bucket are then: sidecar postings minus (deleted since or written again since), plus the newer
postings. This is the LSM-Tree's own read semantics split at the mark, so inserts, deletes, updates (same RID, new terms),
key-wide removals and later compactions all stay exact without touching the sidecar; both reads happen under the LSM-Tree's
read lock, so a compaction cannot swap the parts in between. The newer postings carry their own exact bound, so "maintained on
inserts" costs nothing on the write path: a block bound can only be too high (a deleted posting), never too low.

The sidecar is rewritten after a compaction when it is missing, belongs to another compacted file (a full compaction), or the
series appended since cover more than a quarter of what it holds. Automatic compactions run it on the compaction thread
(`LSMTreeIndex.setCompactionListener`); `COMPACT INDEX`, `REBUILD INDEX` and `LSMTreeFullTextIndex.buildBlockMax()` run it at
once. An index with fewer than two index pages never compacts and has no sidecar (nothing to skip at that size).

### Scorer

`BlockMaxScorer` is the algorithm of the sparse-vector `BmwScorer` (Block-Max MaxScore: essential / non-essential split by
term bound, block-max shallow skip, early abandon of a document), in double precision with the final score summed in key
order, so the float is bit-identical to the general path's. Two additions matter on BM25:

- **one term:** the blocks are read by decreasing bound, after the postings written since, and every block whose bound cannot
  reach the current k-th score is skipped; the cost is the competitive blocks, not the list;
- **several terms:** before the document-at-a-time pass, the documents of the 4 highest-bound blocks are scored exactly with
  random-access lookups into the other terms, so the threshold starts high instead of at the first k documents in RID order;
  a seen-set keeps them from being scored twice. AND leapfrogs from the rarest required term.

A bucket uses it when it has a current sidecar, the caller's transaction holds no uncommitted changes to the index (those are
only visible through the LSM-Tree lookup), and the query's terms span at least 16 sidecar blocks there (below that, reading
the postings is cheaper than opening the sidecar). Otherwise the bucket is read the way described above. The two can mix in
one query: document frequencies are summed across buckets either way.

### Format version and migration

`FullTextIndexMetadata` records `ft_formatVersion`. A definition persisted before this work has none and loads as **1**: no
sidecar is written or read, the index answers with the top-k scan exactly as before, and nothing changes on disk. New indexes
are created as **2**. `REBUILD INDEX <name>` migrates: it re-creates the index from a copy of the definition, which is written in
the current format, then compacts it and writes the sidecars (`IndexInternal.onRebuilt`). `METADATA {"blockMax": false}` keeps
a new index without a sidecar. `arcadedb.fullText.blockMax=false` (database scope) stops queries from reading sidecars, as a
kill-switch; they are still written.

The sidecar is derived data and never needed for correctness: a missing, truncated, foreign or stale file is ignored (the
header is written last, after the data is forced to disk, and the file is moved into place atomically), and the next
compaction writes it again. It is not part of the WAL or of HA replication: a node writes its own when it runs the compaction
(or `COMPACT INDEX` / `REBUILD INDEX`) itself; a node that only receives replicated index files answers by scanning until then.
A dropped index deletes its file.

## Measured

`engine/src/test/java/performance/FullTextTopKBenchmark.java` (`@Tag("benchmark")`): 1,000,000 documents of 10-30 tokens
drawn from a 50,000-term Zipf(1.0) vocabulary (term `tN` is the N-th most frequent), one type with 8 buckets, BM25, indexes
compacted. Each row is `FullTextSearch.search(index, query, 20)` with the switch off (general path, "before") and on
("after") on the same database, 3 warm-up runs then 25 measured; every row also asserts that both return the same 20
matches with the same scores. Laptop (Apple Silicon, `-Xmx8g`).

```bash
JAVA_TOOL_OPTIONS=-Xmx8g mvn -o -pl engine test -Dtest=FullTextTopKBenchmark -Dgroups=benchmark -DexcludedGroups=none
```

| Query (top-20) | Postings read | Before p50 (ms) | Before p95 | After p50 (ms) | After p95 | Speed-up (p50) |
|----------------|--------------:|----------------:|-----------:|---------------:|----------:|---------------:|
| 1 term, rank 1 | 815,242 | 290.5 | 323.8 | 65.6 | 76.7 | 4.4x |
| 1 term, rank 3 | 438,170 | 134.7 | 155.8 | 32.7 | 35.0 | 4.1x |
| 1 term, rank 10 | 160,714 | 41.0 | 43.6 | 10.1 | 10.5 | 4.1x |
| 1 term, rank 30 | 56,413 | 15.2 | 16.8 | 3.7 | 3.9 | 4.0x |
| 1 term, rank 100 | 17,428 | 4.1 | 4.2 | 1.0 | 1.1 | 4.0x |
| 1 term, rank 300 | 5,824 | 1.2 | 1.4 | 0.3 | 0.4 | 3.8x |
| 1 term, rank 1,000 | 1,715 | 0.3 | 0.4 | 0.1 | 0.1 | 2.8x |
| 1 term, rank 10,000 | 174 | 0.2 | 0.2 | 0.1 | 0.1 | 3.3x |
| 2-term AND, rank 3 + rank 30 | 494,583 | 128.5 | 143.5 | 41.9 | 45.9 | 3.1x |
| 2-term OR, rank 3 + rank 30 | 494,583 | 157.6 | 178.3 | 42.9 | 47.9 | 3.7x |
| 2-term AND, rank 30 + rank 300 | 62,237 | 11.6 | 12.3 | 4.7 | 4.9 | 2.5x |
| 2-term OR, rank 30 + rank 300 | 62,237 | 18.5 | 19.2 | 4.8 | 4.8 | 3.9x |
| 2-term OR, rank 1 + rank 10 | 975,956 | 546.5 | 622.5 | 137.5 | 215.0 | 4.0x |
| SQL `SEARCH_INDEX ... ORDER BY $score DESC LIMIT 20`, rank 10 | 160,714 | 85.2 | | 21.4 | | 4.0x |

**Scaling with posting-list length:** both paths are linear in the number of postings read, which is the floor of any exact
top-k on this layout (see above). The general path costs about 350 ns per posting (two scans, four boxed structures); the
top-k scorer about 80 ns, most of it the LSM-Tree cursor decoding the posting. So the speed-up is a constant ~4x from a few
thousand postings up to a million; below ~2,000 postings both answer in a fraction of a millisecond. A top-20 on a term in
80% of a 1M corpus still costs ~65 ms; at Recon's 290M records the same query would read ~240M postings, which is why the
block-max layout above is the next step for common terms.

The AND rows gain less than OR because the general path already only scores the intersection; the top-k scorer still reads
both lists in full (no RID order to leapfrog on).

### Block-max sidecar (index format 2)

Same benchmark, extended to three columns on the same database: **general** (the path before this work), **scan** (the top-k
scorer reading every posting once, `arcadedb.fullText.blockMax=false`) and **block-max** (skipping with the sidecar). Every row
asserts that all three return the same 20 matches with the same scores. "Blocks read" is the sidecar blocks decoded per query
against the blocks the query's terms span; 0 means the terms were too short for the sidecar (under 16 blocks in a bucket) and
the bucket was scanned. Laptop (Apple Silicon, 128 GB, `-Xmx12g`), shared with other build sessions: the 5M p95 column in
particular is noisy (GC pauses under a 5M-document heap; a row whose block-max column used the scan path, rank 1,000 at 5M, ran
the same code as its scan column and still differs 2.5x).

```bash
JAVA_TOOL_OPTIONS=-Xmx12g mvn -o -pl engine test -Dtest=FullTextTopKBenchmark -Dgroups=benchmark -DexcludedGroups=none \
  -Darcadedb.topKBenchmark.documents=5000000 -Darcadedb.topKBenchmark.runs=15
```

**1,000,000 documents** (load 127 s; compaction + sidecars 6.5 s; sidecars 38 MB next to 174 MB of LSM index files)

| Query (top-20) | Postings | General p50 / p95 (ms) | Scan p50 / p95 | Block-max p50 / p95 | Blocks read |
|----------------|---------:|-----------------------:|---------------:|--------------------:|------------:|
| 1 term, rank 1 | 815,242 | 991 / 1,218 | 232 / 261 | **18.7 / 24.9** | 165 / 6,370 |
| 1 term, rank 3 | 438,170 | 503 / 655 | 188 / 220 | **12.6 / 50.5** | 193 / 3,424 |
| 1 term, rank 10 | 160,714 | 135 / 154 | 33.5 / 39.9 | **4.1 / 4.7** | 178 / 1,256 |
| 1 term, rank 30 | 56,413 | 37.7 / 44.3 | 8.4 / 10.8 | **2.0 / 2.6** | 137 / 441 |
| 1 term, rank 100 | 17,428 | 9.9 / 11.2 | 2.5 / 3.0 | 4.0 / 5.3 | 54 / 137 |
| 1 term, rank 300 | 5,824 | 3.7 / 4.9 | 1.3 / 1.7 | 1.2 / 1.6 | 0 / 46 |
| 1 term, rank 10,000 | 174 | 0.4 / 1.0 | 0.1 / 0.1 | 0.1 / 0.2 | 0 / 2 |
| 2-term AND, rank 3 + 30 | 494,583 | 382 / 433 | 132 / 167 | **27.8 / 68.4** | 3,433 / 3,864 |
| 2-term OR, rank 3 + 30 | 494,583 | 546 / 666 | 97.6 / 105 | **24.7 / 41.9** | 2,960 / 3,864 |
| 2-term AND, rank 30 + 300 | 62,237 | 26.9 / 32.2 | 9.9 / 12.0 | **4.1 / 4.9** | 437 / 487 |
| 2-term OR, rank 30 + 300 | 62,237 | 45.8 / 48.2 | 10.4 / 12.1 | **3.4 / 3.8** | 138 / 487 |
| 2-term OR, rank 1 + 10 | 975,956 | 1,598 / 1,993 | 253 / 341 | **39.1 / 51.7** | 2,473 / 7,625 |
| SQL `ORDER BY $score DESC LIMIT 20`, rank 10 | 160,714 | 143 | 27.5 | **4.9** | |

**5,000,000 documents** (load 336 s; compaction + sidecars 70 s; sidecars 277 MB next to 598 MB of LSM index files)

| Query (top-20) | Postings | General p50 / p95 (ms) | Scan p50 / p95 | Block-max p50 / p95 | Blocks read |
|----------------|---------:|-----------------------:|---------------:|--------------------:|------------:|
| 1 term, rank 1 | 4,074,650 | 17,346 / 47,624 | 3,167 / 4,706 | **31.6 / 359** | 180 / 31,834 |
| 1 term, rank 3 | 2,193,079 | 8,535 / 32,907 | 1,617 / 2,894 | **14.3 / 65.1** | 192 / 17,134 |
| 1 term, rank 10 | 802,099 | 1,468 / 2,945 | 296 / 465 | **8.2 / 32.6** | 199 / 6,267 |
| 1 term, rank 30 | 283,665 | 377 / 455 | 149 / 276 | **1.6 / 5.0** | 200 / 2,217 |
| 1 term, rank 100 | 86,660 | 244 / 2,589 | 345 / 797 | **11.9 / 135** | 164 / 678 |
| 1 term, rank 300 | 29,516 | 712 / 1,190 | 161 / 335 | **32.0 / 198** | 228 / 231 |
| 2-term AND, rank 3 + 30 | 2,476,744 | 28,284 / 40,421 | 9,186 / 12,663 | **551 / 1,058** | 18,075 / 19,350 |
| 2-term OR, rank 3 + 30 | 2,476,744 | 19,221 / 27,041 | 2,856 / 6,240 | **274 / 800** | 10,134 / 19,350 |
| 2-term AND, rank 30 + 300 | 313,181 | 856 / 1,569 | 138 / 209 | **11.8 / 16.4** | 2,439 / 2,447 |
| 2-term OR, rank 30 + 300 | 313,181 | 563 / 1,070 | 90.4 / 104 | **10.1 / 17.7** | 2,163 / 2,447 |
| 2-term OR, rank 1 + 10 | 4,876,749 | 14,929 / 119,676 | 1,760 / 3,396 | **113 / 142** | 9,624 / 38,100 |
| SQL `ORDER BY $score DESC LIMIT 20`, rank 10 | 802,099 | 2,089 | 449 | **2.8** | |

**How latency scales with the posting-list length.** With the general path and the scan, cost is at least linear in postings
(1-term rank 30 to rank 1 at 5M: 14x more postings, 21x slower for the scan, 46x for the general path). With the block-max
sidecar a selective 1-term top-20 reads about the same number of blocks whatever the list length: 137-200 blocks for every
term from 56K to 4.1M postings, at 1M and at 5M. Its p50 grows from 1.6 ms to 32 ms over that same 14x longer list at 5M,
against 149 ms to 3.2 s for the scan. The remaining growth is per-query setup
proportional to the term's block count (headers read and bounds computed for every block: 31,834 headers for rank 1 at 5M),
not decoding.

**Where it helps less.** Two-term AND queries still decode most blocks of the more frequent term: the leapfrog from the rarer
term lands in nearly every block of the common one, and the shallow block-max check rarely rejects a candidate whose rare-term
contribution is high. They still gain 10-17x over the scan at 5M because decoding a sidecar block is much cheaper than walking
LSM-Tree postings. Terms short enough to stay under the 16-block floor run the scan path (same speed as the scan column).

**Costs.** Sidecars hold only terms with at least 128 postings per bucket: 22-46% of the LSM index size here. Writing them
adds a pass over the compacted part after a compaction (6.5 s at 1M, 70 s at 5M including the compaction itself).
Nothing on the write path.

### Remaining work

- AND queries: a block-max "AND" check over aligned blocks of all required terms (skip a range only where every required term's
  block bound together fails), and leading from the term with the fewest *blocks above the threshold* rather than the fewest
  postings.
- The per-query cost of loading every block header of a very long term (31,834 for rank 1 at 5M) could be cut with a
  two-level skip index (headers of 64 blocks summarized by one entry), as Lucene's skip lists do.
- The sidecar is rewritten whole after a compaction that makes it outdated (more than a quarter of new series, or a full
  compaction). At Recon's scale, a per-series sidecar (one segment per compacted series, merged at query time like LSM levels)
  would make that incremental.
- The sidecar file is not replicated: an HA follower answers by scanning until it compacts or rebuilds the index itself.

