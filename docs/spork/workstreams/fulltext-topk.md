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

The sidecar records, next to the compacted file id and the mark, the identity of the series that ends at the mark (its root
page and `seriesFingerprint`, `LSMTreeIndexCompacted.getSeriesFingerprint`). A sidecar is used, and counted current, only when
all three still hold: after a crash recovery that lost the last series of a compaction the series end shrinks below the mark,
and later series can be written over those pages, which the fingerprint tells apart.

The sidecar is rewritten after a compaction when it is missing, belongs to another compacted file (a full compaction), no
longer matches the series it was written from, or the series appended since cover more than a quarter of what it holds.
Automatic compactions only ask for it (`LSMTreeIndex.setCompactionListener`): `BlockMaxBuildScheduler` writes it on one
JVM-wide daemon thread at minimum priority, one index at a time, a request for an index already waiting dropped. `COMPACT
INDEX` (whether or not it compacted anything), `REBUILD INDEX` and `LSMTreeFullTextIndex.buildBlockMax()` write it on the
caller's thread before they return: the listener of an explicit compaction leaves the background builder alone. A database
that opens read-write (after its WAL replay, not at the schema load, which comes before it) asks the background builder for
every sidecar that is missing or outdated, so a build the database closed under, or a sidecar lost while it was closed, is
written again without waiting for the next compaction. One build per index runs at a time (a per-index lock: a second caller
waits, then finds the sidecar current). A build that fails (disk full, an I/O error) keeps the sidecar in place: it stays exact
for as long as it describes the compacted part, which every query checks. An index with fewer than two index
pages never compacts and has no sidecar (nothing to skip at that size).

A build holds the LSM-Tree read lock only to take its snapshot: the compacted file, the mark, the fingerprint, a pin on the file
(`LSMTreeIndexCompacted.pinForReading`, the registration an open series cursor takes, so a full compaction that replaces the
file retires it without dropping it) and the series cursors of the key walk. It then reads without the lock: every page below
the mark is final (a partial compaction appends series, a full one writes another file). Before publishing, it checks the file
is still the index's compacted file; a sidecar of a file a full compaction replaced meanwhile is deleted (that compaction asked
for its own).

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
the current format, then compacts it and writes the sidecars (`IndexInternal.onRebuilt`). A rebuild of ONE bucket sub-index
(`REBUILD INDEX <bucket index>`, `CHECK DATABASE FIX` of a corrupted one) keeps the format of its siblings
(`IndexMetadata.reconstructForRebuild`): the buckets of one logical index never disagree on the format, since from format 2 on
the per-field analyzers apply. The top-k path also plans a query once for every bucket only when every bucket analyzes it the
same way (`FullTextIndexMetadata.analyzesQueriesLike`); buckets that do not (sub-indexes re-created through the schema API from
different definitions) take the general path, which analyzes the query per bucket. `METADATA {"blockMax": false}` keeps
a new index without a sidecar. `arcadedb.fullText.blockMax=false` (database scope) stops queries from reading sidecars, as a
kill-switch; they are still written.

The sidecar is derived data and never needed for correctness: a missing, truncated, foreign or stale file is ignored (the
header is written last, after the data is forced to disk, and the file is moved into place atomically), and the next
compaction writes it again. A file damaged after it was opened (truncated, an I/O error, a block that does not decode to its
own length and last RID) makes the query rerun with that bucket reading its postings; the sidecar is set aside and rewritten in
the background. Only a failure of the file itself does that (`BlockMaxPostings.SidecarReadException`): a query thread
interrupted while it reads (a cancelled future, an executor shutting down) closes the shared `FileChannel` for every reader,
which reopen it under a lock and read on, and the interrupted query fails with its interrupt; a failure of the LSM-Tree pages a
query reads next to the sidecar fails that query as it would without one. Neither retires the sidecar.

Files are never overwritten: each build writes a new generation, `<bucket index name>.<generation>.bmx`, from temporary files
of its own (`Files.createTempFile`), and the generation it replaces is deleted once the last query reading it releases it. Two
builds never share a file, and no open file is moved over or deleted under a reader (which Windows refuses). An open of the
index keeps the newest readable generation and deletes the rest, with any temporary file an interrupted build left. A build
that finishes after its index was closed or dropped deletes what it wrote instead of publishing it. A database that opens
read-write deletes every file of `fulltext-blockmax` that belongs to no live full-text index in the block-max format (an index
dropped by an older version after a downgrade, for one). It is not part of the WAL or of HA replication: a node writes its own when it runs the compaction
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

**Where it helps less.** Two-term AND queries decoded most blocks of the more frequent term: the leapfrog from the rarer term
lands in nearly every block of the common one, and the shallow block-max check used the lead's BLOCK bound. They now check each
candidate against the lead's exact contribution plus the other required terms' block bounds (read from the headers) before
decoding anything for it; see "AND queries: exact lead bound" below. Terms short enough to stay under the 16-block floor run the
scan path (same speed as the scan column).

**Costs.** Sidecars hold only terms with at least 128 postings per bucket: 22-46% of the LSM index size here. Writing them
adds a pass over the compacted part after a compaction (6.5 s at 1M, 70 s at 5M including the compaction itself).
Nothing on the write path.

### AND queries: exact lead bound

`FullTextConjunctionBenchmark` (`com.arcadedb.index.fulltext`, `@Tag("benchmark")`): same Zipf corpus and 8 buckets as above,
sidecars written, top-20. A = the block-bound check alone (before), B = plus the candidate check on the lead's exact
contribution (`BlockMaxScorer.exactLeadBound`). Measured as alternating A/B pairs on one database (the first side alternates
each pair) because other worktrees were building on the same laptop; both sides return the same 20 matches with the same
scores (asserted). Blocks are sidecar blocks decoded per query.

```bash
JAVA_TOOL_OPTIONS=-Xmx12g mvn -o -pl engine test -Dtest=FullTextConjunctionBenchmark -Dgroups=benchmark -DexcludedGroups=none \
  -Darcadedb.conjunctionBenchmark.documents=5000000 -Darcadedb.conjunctionBenchmark.pairs=20
```

| Query (top-20) | 1M A p50 / p95 (ms) | 1M B p50 / p95 | 1M blocks A / B | 5M A p50 / p95 (ms) | 5M B p50 / p95 | 5M blocks A / B |
|----------------|--------------------:|---------------:|----------------:|--------------------:|---------------:|----------------:|
| `+t3 +t30` | 11.1 / 11.7 | **9.8 / 10.6** | 3,821 / 3,086 | 78.7 / 97.1 | **59.5 / 64.7** | 16,944 / 8,268 |
| `+t30 +t300` | 3.0 / 3.3 | 3.5 / 3.7 | 487 / 487 | 9.5 / 10.2 | 9.5 / 10.3 | 2,298 / 2,297 |
| `+t1 +t10` | 12.4 / 13.4 | **8.1 / 8.4** | 5,699 / 2,645 | 175.8 / 283.2 | **128.8 / 163.9** | 25,510 / 8,886 |
| `+t10 +t100` | 2.4 / 2.8 | 2.5 / 2.9 | 1,399 / 1,392 | 23.1 / 25.0 | 22.2 / 23.0 | 6,518 / 6,239 |
| `+t3 +t30 +t300` | 5.8 / 6.1 | 5.8 / 6.1 | 3,281 / 3,281 | 60.5 / 147.3 | 59.4 / 117.7 | 15,479 / 15,479 |
| `+t1 +t3` | 32.2 / 36.7 | **23.1 / 26.5** | 8,971 / 6,305 | 632.6 / 889.5 | **490.5 / 711.1** | 39,488 / 24,860 |

Where both terms are common the check halves to thirds the blocks decoded at 5M (rank 1 + rank 10: 25,510 to 8,886) and cuts
p50 by 22-28%. Where the lead is rare enough that its own contributions are high (rank 30 + rank 300), nearly every candidate
passes the check and it changes nothing at 5M; at 1M it costs ~15% there (3.0 to 3.5 ms p50, same blocks), the price of one
more contribution and one binary search per candidate. The three-term query is dominated by the third term's blocks and does
not move. The remaining cost of AND is the lead's own blocks, which the deferred whole-block check would address.

### Remaining work

- AND queries: a check of whole lead blocks from the headers before decoding them (lead block bound plus the best bound of
  the other required terms over the lead block's RID range), and leading from the term with the fewest *blocks above the
  threshold* rather than the fewest postings.
- The per-query cost of loading every block header of a very long term (31,834 for rank 1 at 5M) could be cut with a
  two-level skip index (headers of 64 blocks summarized by one entry), as Lucene's skip lists do.
- The sidecar is rewritten whole after a compaction that makes it outdated (more than a quarter of new series, or a full
  compaction). At Recon's scale, a per-series sidecar (one segment per compacted series, merged at query time like LSM levels)
  would make that incremental.
- The sidecar file is not replicated: an HA follower answers by scanning until it compacts or rebuilds the index itself.


## Review findings and fixes

An adversarial review of the full-text work (block-max sidecar, top-k scorer, partial-match analyzers) found the problems
below. Each fix has a regression test that fails without it (checked by reverting the fix for the sidecar ones).

| Severity | Finding | Fix | Regression test |
|----------|---------|-----|-----------------|
| Blocker | Two builds of one index at once (REBUILD INDEX racing the build its compaction asked for, two COMPACT INDEX) wrote the same fixed `.bmx.tmp` / `.bmx.dict.tmp` files: the second truncated and interleaved with the first, which published a file with a valid header and mixed content (wrong documents and scores, or errors), kept across restarts. | A per-index build lock (a second caller waits, then finds the sidecar current); each build writes temporary files of its own (`Files.createTempFile`) and a new generation file, never an existing one. | `FullTextBlockMaxTest.concurrentBuildsOfOneIndexNeverMixTheirFiles` |
| Major | The whole build ran under the LSM-Tree read lock, on the compaction thread. A compaction publishing meanwhile (write lock) blocked on it and, the locks being fair, so did every query, put and commit queued behind it: at Recon scale, minutes of unavailability per type. | The build holds the read lock only to take its snapshot (compacted file, mark, fingerprint, a pin on the file, the series cursors) and reads below the mark without it; before publishing it checks the file is still the index's. Automatic compactions hand the build to `BlockMaxBuildScheduler` (one JVM-wide daemon thread at minimum priority, coalescing). | `FullTextBlockMaxTest.aCompactionPublishesWhileASidecarIsBeingWritten` (times out with the lock held) |
| Major | The sidecar was trusted on (file id, series end) alone: after a crash recovery that lost the last series, `getSeriesEnd()` fell below the mark, `isBlockMaxCurrent` answered "current" (negative difference) and the sidecar was accepted again once later series were written over those pages: phantom and missing matches, wrong document frequencies. | The header records the root page and `seriesFingerprint` of the series at the mark (format version 2 of the file). Current and usable only when the file id matches, the series end is at least the mark and the fingerprint at the mark is the recorded one. | `FullTextBlockMaxTest.aSidecarOfOtherSeriesIsNeitherCurrentNorRead` |
| Major | Per-field analyzers were applied to indexes written before they existed: every update or delete re-analyzed the old value with the field analyzer, removed tokens never stored and left the real ones, so deleted documents kept matching and the BM25 length counters drifted. | Per-field analyzers apply only to a definition with `ft_formatVersion >= 2` (`FullTextIndexMetadata.isFieldAnalyzersApplied`). An older index indexes and queries with its index-wide analyzer until `REBUILD INDEX`; the open logs a warning naming it. | `FullTextPerFieldAnalyzerTest.anIndexWrittenBeforePerFieldAnalyzersKeepsTheIndexWideAnalyzerUntilRebuilt` |
| Major | `PhoneAnalyzer` canonicalized North American numbers only: `020 7946 0958` and `+44 20 7946 0958` never found each other, nor `+7025551234` (plus, no country code) and `17025551234`. | A trunk prefix (`phone(min,cc,trunk)`, default `0` for a country code other than 1): a national number starting with it is stored as country code + number. The query side also tries the number without its trunk prefix and without the country code (same position), when it is a whole number (9 or more national digits), so a 4- or 7-digit fragment is never altered. | `FullTextAnalyzersTest.phoneWithATrunkPrefixFindsTheNationalAndTheInternationalForm`, `FullTextPartialMatchTest.phoneNumbersMatchAcrossNationalAndInternationalForms` |
| Minor | A sidecar damaged after it was opened (truncated, I/O error, garbage) failed every query until a compaction rewrote it. | `FullTextTopK.search` reruns the query with that bucket scanning and `invalidateBlockMax` sets the sidecar aside (deleted once released) and schedules a rebuild. A decoded block must end exactly at its length and on its header's last RID, so most garbage is an error, not a silent wrong answer. | `FullTextBlockMaxTest.aSidecarDamagedWhileOpenFallsBackToThePostings` |
| Minor | A `reverse` field made the parser accept leading wildcards on every field of the index, so `name:*son` walked every `name` token though `allowLeadingWildcard` was off. | The parser still accepts them, but the wildcard walk refuses a leading wildcard on a field without reversed tokens (`FullTextQueryParseException`) unless `allowLeadingWildcard` is set. Regexp and fuzzy walks were allowed without a prefix before this work and are unchanged. | `FullTextPartialMatchTest.leadingWildcardOnlyWhereTokensAreReversed` |
| Minor | AND decoded most blocks of the common term: the per-candidate check used the lead's block bound. | Implemented: each candidate is checked against the lead's exact contribution plus the other required terms' block bounds (headers only) before any block is decoded for it. Measured below. | `FullTextBlockMaxTest.conjunctionsSkipTheBlocksOfTheCommonTermAndStayExact` (200 random AND queries against the general path, plus fewer blocks decoded) |
| Minor | `edge_ngram` cut a long query word to `maxGram`, so `internationalization` matched `internationalizing` as strongly as itself. | The query side emits the whole word and, at the same position, the cut prefix: both still match, the whole word ranks first. | `FullTextAnalyzersTest.edgeNGramIndexesPrefixesAndTruncatesQueries`, `FullTextPartialMatchTest.edgeNGramRanksTheWholeLongWordFirst` |
| Minor | `phonetic(beider_morse)` replaced the word with its forms (prefix, wildcard and fuzzy clauses never matched a spelling), and folded to ASCII before encoding, which removes the diacritics the rules read. | A filter emits the word, then its forms at the same position; encoding runs on the lower-cased word, folding after. Script coverage documented. | `FullTextAnalyzersTest.beiderMorseKeepsTheWordAndEncodesItBeforeFolding`, `FullTextPartialMatchTest.beiderMorseFieldsMatchBySpellingToo` |
| Minor | The scan path sized tables in int arithmetic: past 2^30 postings in a bucket the accumulator got 0 slots, postings arrays a negative length. | Capacities computed in long; past 2^29 postings for the accumulator, or the largest array for one term, a clear `IndexException`. | `FullTextTopKCapacityTest` |
| Minor | The open sidecar was overwritten in place by `Files.move(REPLACE_EXISTING)` (fails on Windows while open); a build finishing after close or drop published into a closed index or re-created the file of a dropped one. | Generations: each build writes `<name>.<generation>.bmx`, the replaced one is deleted when its last reader releases it; close, drop and the database close (`releaseBackgroundResources`, see below) set a flag under the publication monitor, a late build deletes what it wrote; drop deletes every generation and temporary file. | `FullTextBlockMaxTest.aBuildThatOutlivesTheDropOfItsIndexLeavesNothing`, `dropDeletesTheSidecar` |
| Minor | The top-k path planned the stored keys once, with the first bucket's analyzers, and looked them up in every bucket. A single-bucket rebuild (`REBUILD INDEX <bucket index>`, `CHECK DATABASE FIX`) re-created that bucket from a copy of the definition in the current format, so one bucket of a format-1 index with a `<field>_analyzer` applied it and the others did not: with a LIMIT, the matches of the buckets that disagreed with the first were silently missed. | A single-bucket rebuild keeps the format of its siblings (`IndexMetadata.reconstructForRebuild`); the top-k path runs only when every bucket analyzes queries alike (`FullTextSearch.analyzeQueriesAlike`, comparing format, analyzers, parser options, boosts and BM25 parameters), else the general path. | `FullTextPerFieldAnalyzerTest.aSingleBucketRebuildKeepsTheFormatOfItsSiblings`, `topKAnswersLikeTheGeneralPathWhenBucketsAnalyzeTheQueryDifferently` |
| Minor | Any error met while reading a sidecar retired it: a query's own interrupt (`ClosedByInterruptException`, which also closes the shared channel for every other reader, who then got `ClosedChannelException`) or a failure of the LSM-Tree reads under the lock deleted a healthy file and cost a full rewrite, with every query scanning meanwhile. | `BlockMaxPostings` wraps its own read and decode failures in `SidecarReadException`, the only one that invalidates. A closed channel is reopened under a lock (bounded retries); the interrupted query fails with its interrupt; other errors fail the query and leave the sidecar. | `FullTextBlockMaxTest.anInterruptedQueryKeepsTheSidecar` |
| Minor | Only a compaction or an explicit command asked for a build: a build the database closed under, or a missing sidecar at open, left a read-mostly database scanning until its next compaction. A failed build (disk full) published nothing and retired the previous sidecar, which was still exact. | `LSMTreeFullTextIndex.afterDatabaseOpen` (called by `LocalDatabase` once the WAL is replayed, read-write only) schedules every outdated sidecar; a failed build keeps the published one. Not in `initBlockMax`, which runs during the schema load, before the WAL replay. | `FullTextBlockMaxTest.aSidecarMissingAtOpenIsWrittenInTheBackground`, `aFailedBuildKeepsThePublishedSidecar` |
| Minor | `COMPACT INDEX` wrote the sidecar on the caller's thread only when there was nothing to compact; after a compaction that ran it only asked the background builder, so the statement returned before the sidecar existed. The Checksums deferral below also claimed undetected damage could only loosen a bound. | An explicit compaction writes the sidecar before it returns (its listener skips the background request). Deferral text corrected. | `FullTextBlockMaxTest.compactIndexWritesTheSidecarBeforeItReturns` |
| Minor | Sidecar files were deleted only by the open or drop of the index whose name they carry: an index dropped another way (an older version's DROP or REBUILD INDEX after a downgrade) left its files (22-46% of the index size) for good. | The read-write open deletes every sidecar-shaped file whose index is not a live block-max full-text index; `partial-text.md` documents the manual cleanup for a downgraded database. | `FullTextBlockMaxTest.theOpenDeletesTheSidecarsOfIndexesThatAreGone` |
| Major | Closing or dropping a database never called `LSMTreeFullTextIndex.close()`: `LocalDatabase.closeInternal` flushes each index and calls `releaseBackgroundResources()`, which the full-text index did not override, and `LocalSchema.close()` only clears its maps. A build in progress (the one the open asks for takes minutes at scale) kept reading pages while the close flushed and purged the shared read cache (the hazard #10156 and #6133 fixed for the index warm-up), then failed with a WARNING; a queued request ran against the closed instance; the published sidecar's channel stayed open (one descriptor per bucket index per close until GC; on Windows, DROP DATABASE could not delete the `.bmx` files and the reopened instance could not delete older generations). | `releaseBackgroundResources()` (and `close()`) set the closed flag and take the sidecar under the publication monitor, drop the queued request (`BlockMaxBuildScheduler.cancel`), close the sidecar's owner reference (the file stays for the next open) and wait for the build lock, bounded at 30 s: the build checks the flag after every term, so the wait is about one term. A build that took the lock after the close returns at once, and a snapshot failure during a close logs nothing. `drop()` also drops the queued request. | `FullTextBlockMaxTest.aDatabaseCloseStopsTheBuildInProgressAndClosesTheSidecar` (build parked mid-walk by `blockMaxBuildProbe`; asserts the build stopped before `close()` returned, the queued request is gone, the channel is closed, the file kept, no WARNING, and the reopen rewrites it) |
| Major | Found while writing the test above: the background builder ran `isBlockMaxCurrent()` before setting up the database context its page reads need (`getSeriesEnd` asks for the thread's transaction). With a sidecar in place, that is every index past its first build, it threw `TransactionException`, the builder logged a WARNING and wrote nothing: a sidecar an automatic compaction outdated was never rewritten in the background, and the index scanned until an explicit command (only a missing sidecar, whose check reads no page, was ever built there). | The freshness check and the build run in one context (`inDatabaseContext`). | `FullTextBlockMaxTest.anAutomaticCompactionRewritesAnOutdatedSidecarInTheBackground` |
| Major | A schema reload retired the full-text wrapper without telling it: an HA follower applies every replicated compaction, rename or retired file with a full `LocalSchema.load()`, which builds a new wrapper per bucket index and calls `IndexInternal.onSuperseded()` (#8310) on the previous one, a no-op for the full-text index. The retired wrapper kept its sidecar channel open (one descriptor per bucket index per reload until GC; on Windows DROP DATABASE could not delete the `.bmx`); its build, and the request queued behind it (`BlockMaxBuildScheduler` holds wrappers by identity), ran against the superseded components, were not stopped by the database close, and could publish into the dead instance and retire the generation the live wrapper had open; the successor's open deleted that build's temporary files; and nothing asked for the successor's build, so after its first replicated compaction a follower answered every top-k query by reading the postings until a restart. Results stayed correct (every read checks `describes()`). | `LSMTreeFullTextIndex.onSuperseded()` sets the closed and superseded flags and takes the sidecar under the publication monitor, drops the queued request, removes its own compaction listener (`LSMTreeIndex.removeCompactionListener`, compare-and-set, so a successor wrapping the same LSM-Tree keeps its own) and releases the sidecar with `close()`, not `retire()`, since the successor opened the same file. It does not wait for the build lock (the reload runs on the follower's apply thread): the build stops at its next term. A build of a superseded instance that finishes anyway keeps its file instead of publishing or deleting it (the successor may have opened it; the next open deletes it if it is not the newest). `BlockMaxPostings.Writer` keeps a JVM-wide set of the targets it is writing: `initBlockMax` leaves their temporary files alone and `nextBlockMaxGeneration` skips their generations. Once a publication is done, `LocalSchema.commitStagedPublication` calls `LSMTreeFullTextIndex.afterSchemaReload`, which schedules a check of every full-text index the publication created, only on a read-write database whose open has completed (`getOpenedOn() != 0`, so after the WAL replay; the open itself uses `afterDatabaseOpen`), and without reading a page on the reload's thread. Residual: a build of the retired wrapper that is mid-term when the database closes finishes that term on closed files and stops silently, since the close waits only for the live wrapper's build. | `FullTextBlockMaxTest.aSchemaReloadRetiresTheSupersededSidecarAndBuildsTheSuccessors` (build parked mid-walk by `blockMaxBuildProbe`, a second request queued, then `getEmbedded().load(READ_WRITE, true)` as a follower does: asserts the old wrapper is closed and no longer pending, its channel closed and its file kept, its temporary files survived the successor's open, its build stopped, the successor current and top-k equal to the general path; fails without each of the three parts of the fix), `HaFullTextBlockMaxFollowerIT` (two-node cluster: after a compaction on the leader, which the follower applies with a full load, the follower's top-k reads a sidecar it wrote itself and answers like the general path; times out without the successor's build) |
| Minor | (a) `isBM25()` had lost its javadoc; (b) downgrade unsupported for alias and per-field analyzers; (c) the phone javadoc example `(702) 555 1234` does not work unquoted. | (a) moved back; (b) documented in `partial-text.md` (DROP or REBUILD before downgrading); (c) docs and javadoc say to quote every multi-token number. | none (docs) |

### Deferred

- **Streaming a term's postings into primitive arrays during the build.** The build still collects each term's postings of the
  compacted part into a `HashSet<IndexCursorEntry>` (the LSM lookup resolves deletions and rewrites into a set). It no longer
  holds any lock while doing so and runs on the background thread, but a term with tens of millions of postings in one bucket
  still allocates in proportion. Needs a lookup in `LSMTreeIndexCompacted` that yields primitive (RID, tf, length) triples.
- **Checksums.** The header is checked at open and every decoded block is checked against its own length and last RID, but
  the block headers, the impacts and the dictionary carry no checksum. Damage there that still decodes is NOT harmless: the
  bounds come from the impacts and the skipping decisions from the headers' last RIDs, so an impact with a lower tf or a longer
  length, or a last RID that still decodes, tightens a bound or sends a skip past a block, and the scorer drops competitive
  postings without decoding them: a silently wrong top-k. A damaged dictionary can also hide a term. The fix is a CRC per term
  over its headers and impacts, checked when the term is loaded, which turns that damage into the existing invalidate-and-scan
  fallback; it changes the file format (a new sidecar version, rebuilt on first open).
- **Whole lead blocks skipped from the headers** before decoding, and choosing the AND lead by blocks above the threshold
  (see Remaining work).
- **N-gram fragments shorter than `min`** match only whole tokens; documented in `partial-text.md` rather than changed (a
  prefix walk over grams finds the fragment at the start or middle of a token but not at its end).
- **Phone numbers of other countries** written without their country code are not canonicalized: only the configured country
  code and trunk prefix are known.
