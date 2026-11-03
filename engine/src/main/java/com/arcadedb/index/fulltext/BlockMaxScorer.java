/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.index.fulltext;

import com.arcadedb.query.sql.executor.WorkGuard;

import java.io.IOException;
import java.util.Arrays;

/**
 * Exact Block-Max MaxScore over the block-max postings of one bucket, the algorithm of the sparse-vector index's
 * {@code BmwScorer} (essential / non-essential split, block-max shallow skip, early abandon of a document) with BM25 scored in
 * double precision exactly as the general path scores it.
 * <p>
 * Pruning never changes the answer: a block's bound is the best BM25 contribution among its impacts (the (tf, length) pairs no
 * other posting of the block beats on both; BM25 grows with tf and shrinks with length, so no posting of the block scores more),
 * inflated by {@link #BOUND_SLACK} to cover rounding, and a document is dropped only when its bound stays below the float just under the k-th best score, so it could
 * neither beat nor tie it. A document that is scored is scored by summing its terms' contributions in key order, the order the
 * general path sums them in, so the float that reaches the heap is the same float.
 */
final class BlockMaxScorer {
  /** Relative margin added to every upper bound: covers the rounding of a sum computed in another order. */
  static final double BOUND_SLACK = 1e-9;

  private BlockMaxScorer() {
  }

  /** The BM25 constants of one query: what turns (tf, document length) into a term's contribution. */
  record Scoring(double avgdl, double k1, double b) {
    double contribution(final int tf, final int docLength, final double idf, final float boost) {
      return tf < 0 ? 0.0 : BM25Scorer.termScore(idf, tf, docLength, avgdl, k1, b) * boost;
    }

    double bound(final int maxTf, final int minDocLength, final double idf, final float boost) {
      return contribution(maxTf, minDocLength, idf, boost) * (1.0 + BOUND_SLACK);
    }
  }

  /**
   * One term's postings in a bucket in RID order: the sidecar snapshot without the RIDs written or deleted since, merged with the
   * postings written since. Exposes the bound of the block a RID falls in, so whole blocks can be skipped without decoding.
   */
  static final class TermCursor {
    static final long END = Long.MAX_VALUE;

    private final BlockMaxPostings.Term term;
    private final LongSet               excluded;
    private final long[]                tailRids;
    private final int[]                 tailTfs;
    private final int[]                 tailDocLengths;
    private final int                   tailSize;

    private final long[]   blockRids       = new long[BlockMaxPostings.BLOCK_SIZE];
    private final int[]    blockTfs        = new int[BlockMaxPostings.BLOCK_SIZE];
    private final int[]    blockDocLengths = new int[BlockMaxPostings.BLOCK_SIZE];
    private final byte[][] buffer          = new byte[1][];
    private       int      decodedBlock    = -1;
    private       int      decodedCount;
    private       int      inBlock;

    private long sidecarDoc;
    private int  sidecarTf;
    private int  sidecarDocLength;
    private int  tailPosition;

    long doc;
    int  tf;
    int  docLength;

    private double[] blockBounds;
    private double   tailBound;
    double globalBound;
    double idf;
    float  boost;
    long   blocksDecoded;

    /**
     * @param term     the sidecar postings, or null when the snapshot holds none (or a key-wide deletion since voids them)
     * @param excluded the RIDs whose sidecar posting is no longer current: deleted since, or written again since
     * @param tailRids the postings written since, sorted by RID, with their statistics ({@code tf = -1}: no statistics)
     */
    TermCursor(final BlockMaxPostings.Term term, final LongSet excluded, final long[] tailRids, final int[] tailTfs,
        final int[] tailDocLengths, final int tailSize) {
      this.term = term;
      this.excluded = excluded;
      this.tailRids = tailRids;
      this.tailTfs = tailTfs;
      this.tailDocLengths = tailDocLengths;
      this.tailSize = tailSize;
    }

    /** The live postings of the term in this bucket: the sidecar's, minus those no longer current, plus the tail's. */
    long documentFrequency(final long[] sortedExcluded, final int excludedCount) throws IOException {
      if (term == null)
        return tailSize;
      long stale = 0;
      for (int i = 0; i < excludedCount; i++)
        if (sidecarContains(sortedExcluded[i]))
          ++stale;
      decodedBlock = -1;
      return term.documentFrequency - stale + tailSize;
    }

    private boolean sidecarContains(final long rid) throws IOException {
      final int block = blockOf(rid, 0);
      if (block >= term.blocks())
        return false;
      decodeBlock(block);
      return Arrays.binarySearch(blockRids, 0, decodedCount, rid) >= 0;
    }

    /** Computes the bounds with the query's statistics and positions the cursor on its first posting. */
    void start(final Scoring scoring, final double idf, final float boost) throws IOException {
      this.idf = idf;
      this.boost = boost;
      globalBound = 0.0;
      if (term != null) {
        blockBounds = new double[term.blocks()];
        // A block's bound is its best impact under the query's statistics: no posting of the block scores more
        for (int i = 0; i < blockBounds.length; i++) {
          double bound = 0.0;
          for (int p = term.impactStarts[i]; p < term.impactStarts[i + 1]; p++)
            bound = Math.max(bound, scoring.bound(term.impactTfs[p], term.impactDocLengths[p], idf, boost));
          blockBounds[i] = bound;
          globalBound = Math.max(globalBound, bound);
        }
      }
      tailBound = 0.0;
      for (int i = 0; i < tailSize; i++)
        tailBound = Math.max(tailBound, scoring.contribution(tailTfs[i], tailDocLengths[i], idf, boost) * (1.0 + BOUND_SLACK));
      globalBound = Math.max(globalBound, tailBound);

      decodedBlock = -1;
      inBlock = 0;
      tailPosition = 0;
      sidecarDoc = -1;
      doc = -1;
      advance(0);
    }

    /** Moves to the first posting at or after {@code target}. */
    void advance(final long target) throws IOException {
      if (doc >= target)
        return;
      if (sidecarDoc < target)
        sidecarAdvance(target);
      while (tailPosition < tailSize && tailRids[tailPosition] < target)
        ++tailPosition;
      final long tailDoc = tailPosition < tailSize ? tailRids[tailPosition] : END;
      if (tailDoc < sidecarDoc) {
        doc = tailDoc;
        tf = tailTfs[tailPosition];
        docLength = tailDocLengths[tailPosition];
      } else {
        doc = sidecarDoc;
        tf = sidecarTf;
        docLength = sidecarDocLength;
      }
    }

    void next() throws IOException {
      advance(doc + 1);
    }

    /** A cursor over the same postings with the same bounds and its own position, for random lookups. */
    TermCursor probe() {
      final TermCursor probe = new TermCursor(term, excluded, tailRids, tailTfs, tailDocLengths, tailSize);
      probe.blockBounds = blockBounds;
      probe.tailBound = tailBound;
      probe.globalBound = globalBound;
      probe.idf = idf;
      probe.boost = boost;
      return probe;
    }

    /** Looks {@code rid} up out of order; on a hit, {@link #tf} and {@link #docLength} are its statistics. */
    boolean lookup(final long rid) throws IOException {
      if (tailSize > 0) {
        final int i = Arrays.binarySearch(tailRids, 0, tailSize, rid);
        if (i >= 0) {
          tf = tailTfs[i];
          docLength = tailDocLengths[i];
          return true;
        }
      }
      if (term == null || (excluded != null && excluded.contains(rid)))
        return false;
      final int block = blockOf(rid, 0);
      if (block >= term.blocks())
        return false;
      decodeBlock(block);
      final int i = Arrays.binarySearch(blockRids, 0, decodedCount, rid);
      if (i < 0)
        return false;
      tf = blockTfs[i];
      docLength = blockDocLengths[i];
      return true;
    }

    int blocks() {
      return term != null ? term.blocks() : 0;
    }

    double blockBound(final int block) {
      return blockBounds[block];
    }

    /**
     * The largest contribution any posting in {@code [target, shallowEnd(target)]} can make: the bound of the sidecar block that
     * range lies in, and the tail's bound when the tail still has postings there.
     */
    double shallowBound(final long target) {
      double bound = tailPosition < tailSize && tailRids[tailSize - 1] >= target ? tailBound : 0.0;
      if (term != null) {
        final int block = blockOf(target, Math.max(decodedBlock, 0));
        if (block < term.blocks())
          bound = Math.max(bound, blockBounds[block]);
      }
      return bound;
    }

    /** The last RID of the sidecar block {@code target} falls in: the end of the range {@link #shallowBound} covers. */
    long shallowEnd(final long target) {
      if (term == null)
        return END;
      final int block = blockOf(target, Math.max(decodedBlock, 0));
      return block < term.blocks() ? term.lastRids[block] : END;
    }

    private int blockOf(final long target, final int from) {
      final long[] lastRids = term.lastRids;
      int low = from;
      int high = lastRids.length - 1;
      if (low > high || lastRids[high] < target)
        return lastRids.length;
      while (low < high) {
        final int mid = (low + high) >>> 1;
        if (lastRids[mid] < target)
          low = mid + 1;
        else
          high = mid;
      }
      return low;
    }

    private void decodeBlock(final int block) throws IOException {
      if (block == decodedBlock)
        return;
      decodedCount = term.decode(block, blockRids, blockTfs, blockDocLengths, buffer);
      decodedBlock = block;
      ++blocksDecoded;
    }

    private void sidecarAdvance(final long target) throws IOException {
      if (term == null) {
        sidecarDoc = END;
        return;
      }
      int block = decodedBlock < 0 ? 0 : decodedBlock;
      if (decodedBlock < 0 || term.lastRids[block] < target) {
        block = blockOf(target, block);
        if (block >= term.blocks()) {
          sidecarDoc = END;
          return;
        }
        decodeBlock(block);
        inBlock = 0;
      }
      while (true) {
        // first posting of the block at or after target, then past the stale ones
        while (inBlock < decodedCount && blockRids[inBlock] < target)
          ++inBlock;
        while (inBlock < decodedCount && excluded != null && excluded.contains(blockRids[inBlock]))
          ++inBlock;
        if (inBlock < decodedCount) {
          sidecarDoc = blockRids[inBlock];
          sidecarTf = blockTfs[inBlock];
          sidecarDocLength = blockDocLengths[inBlock];
          return;
        }
        if (decodedBlock + 1 >= term.blocks()) {
          sidecarDoc = END;
          return;
        }
        decodeBlock(decodedBlock + 1);
        inBlock = 0;
      }
    }
  }

  /**
   * The best documents of one bucket into {@code heap}.
   *
   * @param cursors      one cursor per query term, in key order (the order the scores are summed in), each started
   * @param requiredMask the bits of the terms a match must contain; 0 for a disjunction
   */
  static void score(final TermCursor[] cursors, final long requiredMask, final FullTextTopK.TopKHeap heap, final Scoring scoring,
      final WorkGuard guard) throws IOException {
    if (cursors.length == 1) {
      scoreSingleTerm(cursors[0], heap, scoring, guard);
      return;
    }
    final LongSet seeded = seed(cursors, requiredMask, heap, scoring, guard);
    if (requiredMask != 0L)
      scoreConjunction(cursors, requiredMask, heap, scoring, guard, seeded);
    else
      scoreDisjunction(cursors, heap, scoring, guard, seeded);
  }

  /**
   * Whether a conjunction checks each candidate against the lead's exact contribution plus the other required terms' block bounds
   * before decoding their blocks. Not final only so a benchmark can measure it against the block-bound check alone.
   */
  static boolean exactLeadBound = true;

  /** Blocks read up front, by bound, to raise the threshold before the traversal in RID order. */
  static final int SEED_BLOCKS = 4;

  /**
   * One term: the postings written since the sidecar, then the sidecar blocks by decreasing bound, until a block's bound cannot
   * reach the k-th best score. Every block left unread holds no posting that could enter the heap, so this is exact; and it reads
   * only the competitive blocks, however long the posting list.
   */
  private static void scoreSingleTerm(final TermCursor cursor, final FullTextTopK.TopKHeap heap, final Scoring scoring,
      final WorkGuard guard) throws IOException {
    int ticks = 0;
    for (int i = 0; i < cursor.tailSize; i++) {
      guard.checkPeriodically(++ticks);
      heap.offer((float) scoring.contribution(cursor.tailTfs[i], cursor.tailDocLengths[i], cursor.idf, cursor.boost),
          cursor.tailRids[i]);
    }

    final int blocks = cursor.blocks();
    if (blocks == 0)
      return;
    final int[] order = blocksByBound(cursor);
    for (final int block : order) {
      guard.checkPeriodically(++ticks);
      if (cursor.blockBound(block) * (1.0 + BOUND_SLACK) < pruneBelow(heap))
        // Skipped on its own bound: the order is approximate, so every remaining block is still checked on its own
        continue;
      cursor.decodeBlock(block);
      for (int i = 0; i < cursor.decodedCount; i++) {
        final long rid = cursor.blockRids[i];
        if (cursor.excluded != null && cursor.excluded.contains(rid))
          continue;
        heap.offer((float) scoring.contribution(cursor.blockTfs[i], cursor.blockDocLengths[i], cursor.idf, cursor.boost), rid);
      }
    }
  }

  /**
   * The blocks of a cursor by decreasing bound, as floats: the order only decides what is read first, never what is skipped, so
   * its rounding is harmless.
   */
  private static int[] blocksByBound(final TermCursor cursor) {
    final int blocks = cursor.blocks();
    // A non-negative float's bits order like its value: inverted, an ascending sort is by bound descending
    final long[] packed = new long[blocks];
    for (int i = 0; i < blocks; i++)
      packed[i] = ((long) (0x7FFFFFFF - Float.floatToIntBits((float) cursor.blockBound(i))) << 32) | i;
    Arrays.sort(packed);
    final int[] order = new int[blocks];
    for (int i = 0; i < blocks; i++)
      order[i] = (int) packed[i];
    return order;
  }

  /**
   * Scores exactly the documents of the few blocks with the highest bounds, looking the other terms up out of order, so the
   * threshold starts high instead of at the first k documents in RID order. Answers the RIDs scored, which the traversal then
   * skips: a document enters the heap once.
   */
  private static LongSet seed(final TermCursor[] cursors, final long requiredMask, final FullTextTopK.TopKHeap heap,
      final Scoring scoring, final WorkGuard guard) throws IOException {
    final int n = cursors.length;
    // The candidate blocks: each term's best ones; only required terms when there are any, since a match must hold them all
    int total = 0;
    for (int t = 0; t < n; t++)
      if (requiredMask == 0L || (requiredMask & (1L << t)) != 0)
        total += Math.min(SEED_BLOCKS, cursors[t].blocks());
    if (total == 0)
      return null;
    final int[] seedTerms = new int[total];
    final int[] seedBlocks = new int[total];
    final double[] seedBounds = new double[total];
    int count = 0;
    for (int t = 0; t < n; t++) {
      if (requiredMask != 0L && (requiredMask & (1L << t)) == 0)
        continue;
      final int[] order = blocksByBound(cursors[t]);
      for (int i = 0; i < Math.min(SEED_BLOCKS, order.length); i++) {
        seedTerms[count] = t;
        seedBlocks[count] = order[i];
        seedBounds[count] = cursors[t].blockBound(order[i]);
        ++count;
      }
    }
    final Integer[] boxed = new Integer[count];
    for (int i = 0; i < count; i++)
      boxed[i] = i;
    Arrays.sort(boxed, (a, b) -> Double.compare(seedBounds[b], seedBounds[a]));

    final TermCursor[] probes = new TermCursor[n];
    for (int t = 0; t < n; t++)
      probes[t] = cursors[t].probe();
    final LongSet seeded = new LongSet(SEED_BLOCKS * BlockMaxPostings.BLOCK_SIZE * n);
    final double[] contributions = new double[n];
    int ticks = 0;
    for (int s = 0; s < Math.min(count, SEED_BLOCKS); s++) {
      final int t = seedTerms[boxed[s]];
      final TermCursor source = probes[t];
      source.decodeBlock(seedBlocks[boxed[s]]);
      final long[] rids = Arrays.copyOf(source.blockRids, source.decodedCount);
      for (final long rid : rids) {
        guard.checkPeriodically(++ticks);
        if (seeded.contains(rid) || (source.excluded != null && source.excluded.contains(rid)))
          continue;
        long matched = 0L;
        Arrays.fill(contributions, 0.0);
        for (int u = 0; u < n; u++)
          if (probes[u].lookup(rid)) {
            matched |= 1L << u;
            contributions[u] = scoring.contribution(probes[u].tf, probes[u].docLength, probes[u].idf, probes[u].boost);
          }
        if ((matched & requiredMask) != requiredMask)
          continue;
        seeded.add(rid);
        heap.offer((float) sumInKeyOrder(contributions), rid);
      }
    }
    return seeded;
  }

  /** The value a bound must stay below for a document to be dropped: the float just under the k-th best score. */
  private static double pruneBelow(final FullTextTopK.TopKHeap heap) {
    return heap.isFull() ? (double) Math.nextDown(heap.worstScore()) : Double.NEGATIVE_INFINITY;
  }

  private static void scoreDisjunction(final TermCursor[] cursors, final FullTextTopK.TopKHeap heap, final Scoring scoring,
      final WorkGuard guard, final LongSet seeded) throws IOException {
    final int n = cursors.length;
    // Terms by increasing bound: a prefix whose bounds add up below the threshold is non-essential
    final Integer[] boxed = new Integer[n];
    for (int i = 0; i < n; i++)
      boxed[i] = i;
    Arrays.sort(boxed, (a, b) -> Double.compare(cursors[a].globalBound, cursors[b].globalBound));
    final int[] order = new int[n];
    final double[] prefixBound = new double[n + 1];
    for (int i = 0; i < n; i++) {
      order[i] = boxed[i];
      prefixBound[i + 1] = prefixBound[i] + cursors[order[i]].globalBound;
    }

    final double[] contributions = new double[n];
    int split = 0;
    int ticks = 0;
    while (true) {
      guard.checkPeriodically(++ticks);
      final double threshold = pruneBelow(heap);
      while (split < n && prefixBound[split + 1] * (1.0 + BOUND_SLACK) < threshold)
        ++split;
      if (split == n)
        return;

      long candidate = TermCursor.END;
      for (int i = split; i < n; i++)
        candidate = Math.min(candidate, cursors[order[i]].doc);
      if (candidate == TermCursor.END)
        return;

      if (heap.isFull()) {
        // Block-max shallow skip: no document up to the nearest block end of the essential terms can reach the threshold
        double rangeBound = prefixBound[split];
        long rangeEnd = TermCursor.END;
        for (int i = split; i < n; i++) {
          final TermCursor cursor = cursors[order[i]];
          rangeBound += cursor.shallowBound(candidate);
          rangeEnd = Math.min(rangeEnd, cursor.shallowEnd(candidate));
        }
        if (rangeBound * (1.0 + BOUND_SLACK) < threshold) {
          if (rangeEnd == TermCursor.END)
            return;
          for (int i = split; i < n; i++)
            cursors[order[i]].advance(rangeEnd + 1);
          continue;
        }
      }

      if (seeded != null && seeded.contains(candidate)) {
        // Already scored, exactly, by the seeding
        for (int i = split; i < n; i++)
          if (cursors[order[i]].doc == candidate)
            cursors[order[i]].next();
        continue;
      }

      // Score the essential terms on the candidate, then probe the non-essential ones, most valuable first
      Arrays.fill(contributions, 0.0);
      double partial = 0.0;
      for (int i = split; i < n; i++) {
        final TermCursor cursor = cursors[order[i]];
        if (cursor.doc == candidate) {
          final double c = scoring.contribution(cursor.tf, cursor.docLength, cursor.idf, cursor.boost);
          contributions[order[i]] = c;
          partial += c;
        }
      }
      boolean abandoned = false;
      for (int i = split - 1; i >= 0; i--) {
        if ((partial + prefixBound[i + 1]) * (1.0 + BOUND_SLACK) < threshold) {
          abandoned = true;
          break;
        }
        final TermCursor cursor = cursors[order[i]];
        cursor.advance(candidate);
        if (cursor.doc == candidate) {
          final double c = scoring.contribution(cursor.tf, cursor.docLength, cursor.idf, cursor.boost);
          contributions[order[i]] = c;
          partial += c;
        }
      }
      if (!abandoned)
        heap.offer((float) sumInKeyOrder(contributions), candidate);

      for (int i = split; i < n; i++) {
        final TermCursor cursor = cursors[order[i]];
        if (cursor.doc == candidate)
          cursor.next();
      }
    }
  }

  private static void scoreConjunction(final TermCursor[] cursors, final long requiredMask, final FullTextTopK.TopKHeap heap,
      final Scoring scoring, final WorkGuard guard, final LongSet seeded) throws IOException {
    final int n = cursors.length;
    int requiredCount = 0;
    int optionalCount = 0;
    for (int t = 0; t < n; t++)
      if ((requiredMask & (1L << t)) != 0)
        ++requiredCount;
      else
        ++optionalCount;
    final int[] required = new int[requiredCount];
    final int[] optional = new int[optionalCount];
    requiredCount = 0;
    optionalCount = 0;
    for (int t = 0; t < n; t++)
      if ((requiredMask & (1L << t)) != 0)
        required[requiredCount++] = t;
      else
        optional[optionalCount++] = t;

    // Optional terms most valuable first; their bounds as suffix sums for the early abandon
    final Integer[] boxed = new Integer[optionalCount];
    for (int i = 0; i < optionalCount; i++)
      boxed[i] = optional[i];
    Arrays.sort(boxed, (a, b) -> Double.compare(cursors[b].globalBound, cursors[a].globalBound));
    final double[] optionalRemaining = new double[optionalCount + 1];
    for (int i = optionalCount - 1; i >= 0; i--) {
      optional[i] = boxed[i];
      optionalRemaining[i] = optionalRemaining[i + 1] + cursors[optional[i]].globalBound;
    }

    // The lead is the rarest required term: candidates come from its postings only
    int lead = required[0];
    for (final int t : required)
      if (cursorSize(cursors[t]) < cursorSize(cursors[lead]))
        lead = t;
    final TermCursor leader = cursors[lead];

    final double[] contributions = new double[n];
    int ticks = 0;
    while (leader.doc != TermCursor.END) {
      guard.checkPeriodically(++ticks);
      final long candidate = leader.doc;
      final double threshold = pruneBelow(heap);

      if (heap.isFull()) {
        double rangeBound = optionalRemaining[0];
        long rangeEnd = TermCursor.END;
        for (final int t : required) {
          rangeBound += cursors[t].shallowBound(candidate);
          rangeEnd = Math.min(rangeEnd, cursors[t].shallowEnd(candidate));
        }
        if (rangeBound * (1.0 + BOUND_SLACK) < threshold) {
          if (rangeEnd == TermCursor.END)
            return;
          leader.advance(rangeEnd + 1);
          continue;
        }
      }

      if (exactLeadBound && heap.isFull()) {
        // The lead's contribution is known exactly (its block is decoded already); the other required terms are bounded by the
        // block their posting of the candidate would be in, read from the headers. A candidate that cannot reach the threshold
        // is passed over before any other term's block is decoded for it: on a rare lead and a common term, that is most of them
        double bound = scoring.contribution(leader.tf, leader.docLength, leader.idf, leader.boost) * (1.0 + BOUND_SLACK)
            + optionalRemaining[0];
        for (final int t : required)
          if (t != lead)
            bound += cursors[t].shallowBound(candidate);
        if (bound * (1.0 + BOUND_SLACK) < threshold) {
          leader.next();
          continue;
        }
      }

      // Every required term on the candidate, or leapfrog the leader to where the first one that is not stands
      long next = -1;
      for (final int t : required) {
        if (t == lead)
          continue;
        cursors[t].advance(candidate);
        if (cursors[t].doc != candidate) {
          next = cursors[t].doc;
          break;
        }
      }
      if (next != -1) {
        if (next == TermCursor.END)
          return;
        leader.advance(next);
        continue;
      }

      if (seeded != null && seeded.contains(candidate)) {
        leader.next();
        continue;
      }

      Arrays.fill(contributions, 0.0);
      double partial = 0.0;
      for (final int t : required) {
        final TermCursor cursor = cursors[t];
        final double c = scoring.contribution(cursor.tf, cursor.docLength, cursor.idf, cursor.boost);
        contributions[t] = c;
        partial += c;
      }
      boolean abandoned = false;
      for (int i = 0; i < optionalCount; i++) {
        if ((partial + optionalRemaining[i]) * (1.0 + BOUND_SLACK) < threshold) {
          abandoned = true;
          break;
        }
        final TermCursor cursor = cursors[optional[i]];
        cursor.advance(candidate);
        if (cursor.doc == candidate) {
          final double c = scoring.contribution(cursor.tf, cursor.docLength, cursor.idf, cursor.boost);
          contributions[optional[i]] = c;
          partial += c;
        }
      }
      if (!abandoned)
        heap.offer((float) sumInKeyOrder(contributions), candidate);
      leader.next();
    }
  }

  private static long cursorSize(final TermCursor cursor) {
    return (cursor.term != null ? cursor.term.documentFrequency : 0L) + cursor.tailSize;
  }

  /** A document's score: its terms' contributions added in key order, as the general path adds them. */
  private static double sumInKeyOrder(final double[] contributions) {
    double sum = 0.0;
    for (final double c : contributions)
      sum += c;
    return sum;
  }

  /** An open-addressing set of RIDs. */
  static final class LongSet {
    private static final long EMPTY = Long.MIN_VALUE;
    private final long[] keys;
    private final int    mask;

    LongSet(final int expected) {
      final int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
      keys = new long[capacity];
      Arrays.fill(keys, EMPTY);
      mask = capacity - 1;
    }

    void add(final long key) {
      int slot = slot(key);
      while (keys[slot] != EMPTY) {
        if (keys[slot] == key)
          return;
        slot = (slot + 1) & mask;
      }
      keys[slot] = key;
    }

    boolean contains(final long key) {
      int slot = slot(key);
      while (keys[slot] != EMPTY) {
        if (keys[slot] == key)
          return true;
        slot = (slot + 1) & mask;
      }
      return false;
    }

    private int slot(long key) {
      key ^= key >>> 33;
      key *= 0xff51afd7ed558ccdL;
      key ^= key >>> 33;
      return (int) key & mask;
    }
  }
}
