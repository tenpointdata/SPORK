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

import com.arcadedb.database.BasicDatabase;
import com.arcadedb.database.DatabaseRID;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.IndexException;
import com.arcadedb.index.lsm.FullTextPostingRID;
import com.arcadedb.query.sql.executor.WorkGuard;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The BM25 top-k scorer for queries made of plain terms: a disjunction, a conjunction, or required terms plus optional ones,
 * with boosts and field qualifiers. It answers exactly what the general path ({@link FullTextQueryExecutor} matching, then
 * {@link LSMTreeFullTextIndex} scoring) answers, the same documents with the same float scores, at a fraction of the cost:
 * <ul>
 *   <li>each term's postings are read ONCE, into primitive arrays (position, tf, document length), where the general path
 *   reads them twice (match, then score);</li>
 *   <li>no candidate set is materialized: the general path puts every match into a {@code Map<RID, AtomicInteger>}, copies it
 *   into a {@code Set<RID>}, seeds a {@code Map<RID, double[]>} with it and collects the scored postings into a list. Here a
 *   single-term query scores straight into a bounded heap, and a multi-term one accumulates into one open-addressing table of
 *   primitives;</li>
 *   <li>the best {@code k} of each bucket are kept in a primitive heap, so nothing proportional to the matches is allocated
 *   as objects.</li>
 * </ul>
 * The cost stays linear in the length of the posting lists: an LSM-Tree key's postings are neither ordered by RID nor split
 * into blocks the full-text layer can address, so WAND / Block-Max MaxScore have nothing to skip over. Skipping needs an index
 * format that stores postings in RID or impact order (see docs/spork/workstreams/fulltext-topk.md).
 * <p>
 * Per-document scores are summed over the terms in key order, the order {@link LSMTreeFullTextIndex} sums them in too, so the
 * two paths add the same doubles in the same sequence.
 */
public final class FullTextTopK {
  /** The highest number of terms a query may have here: one bit per term in the match mask. */
  static final int MAX_TERMS = 63;

  /** One scoring term: the key its postings are stored under, its boost, and whether a match must contain it. */
  record Term(String key, float boost, boolean required) {
  }

  private static final AtomicLong INVOCATIONS           = new AtomicLong();
  private static final AtomicLong BLOCK_MAX_INVOCATIONS = new AtomicLong();
  private static final AtomicLong BLOCKS_DECODED        = new AtomicLong();

  private FullTextTopK() {
  }

  static long getInvocationsForTesting() {
    return INVOCATIONS.get();
  }

  /** Bucket scorings answered from a block-max sidecar. Test and benchmark instrumentation. */
  public static long getBlockMaxInvocations() {
    return BLOCK_MAX_INVOCATIONS.get();
  }

  /** Sidecar blocks decoded by the block-max scorings. Test and benchmark instrumentation. */
  public static long getBlocksDecoded() {
    return BLOCKS_DECODED.get();
  }

  /**
   * The postings of one term in one bucket. A posting with no statistics (a plain RID) keeps {@code tf = -1}: it matches and
   * contributes nothing, as on the general path.
   */
  private static final class Postings {
    long[] rids = new long[16];
    int[]  tfs  = new int[16];
    int[]  dls  = new int[16];
    int    size;
    long   live;

    void add(final long rid, final int tf, final int dl) {
      if (size == rids.length) {
        final int capacity = grownCapacity(size);
        rids = Arrays.copyOf(rids, capacity);
        tfs = Arrays.copyOf(tfs, capacity);
        dls = Arrays.copyOf(dls, capacity);
      }
      rids[size] = rid;
      tfs[size] = tf;
      dls[size] = dl;
      ++size;
    }
  }

  /** The longest primitive array the JVM reliably allocates. */
  static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

  /** The most slots the multi-term accumulator may have: 2^30 slots are already 24 GB of primitive arrays. */
  static final long MAX_ACCUMULATOR_SLOTS = 1L << 30;

  /** The capacity a postings array of {@code size} entries grows to, computed without overflowing. */
  static int grownCapacity(final int size) {
    if (size >= MAX_ARRAY_LENGTH)
      throw new IndexException("A full-text query term has more than " + MAX_ARRAY_LENGTH
          + " postings in one bucket: too many for the top-k scorer");
    return (int) Math.min((long) Math.max(size, 8) << 1, MAX_ARRAY_LENGTH);
  }

  /**
   * The slots of an accumulator for {@code expected} postings: a power of two at least twice as large, computed in long. Refused
   * with a clear error past {@link #MAX_ACCUMULATOR_SLOTS}, instead of an int overflow that sized the table at 0.
   */
  static int accumulatorCapacity(final long expected) {
    final long capacity = Long.highestOneBit(Math.max(16L, expected) * 2 - 1) << 1;
    if (capacity > MAX_ACCUMULATOR_SLOTS)
      throw new IndexException("A full-text query reads " + expected + " postings in one bucket: too many for the top-k scorer ("
          + "the most is " + (MAX_ACCUMULATOR_SLOTS >> 1) + "); add a filter or a more selective term");
    return (int) capacity;
  }

  /** A failure to read a block-max sidecar, carried out of the scoring so the query can run again without it. */
  private static final class SidecarFailure extends RuntimeException {
    final int bucket;

    SidecarFailure(final int bucket, final Throwable cause) {
      super(cause);
      this.bucket = bucket;
    }
  }

  /**
   * The best {@code limit} matches of every bucket index, scored against type-wide corpus statistics when there are several
   * buckets and against the bucket's own when there is one, exactly as {@link FullTextSearch#search} does on its general path.
   *
   * @param terms the query terms, sorted by key, at most {@link #MAX_TERMS}
   *
   * @return the matches with their scores, or null when a posting falls outside what this scorer encodes (the caller then
   * takes the general path)
   */
  static Map<RID, Float> search(final List<LSMTreeFullTextIndex> bucketIndexes, final List<Term> terms, final int limit,
      final WorkGuard guard) {
    // A sidecar that fails while it is read (damaged, truncated or unreadable after it was opened) is set aside, and the query
    // runs again: that bucket then reads its postings. Each round sets one aside, so this ends
    for (int attempt = 0; ; attempt++) {
      final LSMTreeFullTextIndex.BlockMaxView[] views = new LSMTreeFullTextIndex.BlockMaxView[bucketIndexes.size()];
      try {
        return searchOnce(bucketIndexes, terms, limit, guard, views);
      } catch (final SidecarFailure failure) {
        final LSMTreeFullTextIndex.BlockMaxView view = views[failure.bucket];
        // Released first, so the file can be deleted at once
        for (int b = 0; b < views.length; b++)
          if (views[b] != null) {
            views[b].release();
            views[b] = null;
          }
        bucketIndexes.get(failure.bucket).invalidateBlockMax(view.postings(), failure.getCause());
        if (attempt > bucketIndexes.size())
          throw new IndexException("Error reading the block-max postings of a full-text index", failure.getCause());
      } finally {
        for (final LSMTreeFullTextIndex.BlockMaxView view : views)
          if (view != null)
            view.release();
      }
    }
  }

  private static Map<RID, Float> searchOnce(final List<LSMTreeFullTextIndex> bucketIndexes, final List<Term> terms,
      final int limit, final WorkGuard guard, final LSMTreeFullTextIndex.BlockMaxView[] views) {
    INVOCATIONS.incrementAndGet();
    guard.check();
    final int termCount = terms.size();
    final int buckets = bucketIndexes.size();

    // Pass one, per bucket: the block-max sidecar and the postings written since when the bucket has a current one, otherwise
    // every term's postings read once; either way the type-wide document frequencies are counted
    {
      final Postings[][] postings = new Postings[buckets][];
      final long[] documentFrequencies = new long[termCount];
      int ticks = 0;
      for (int b = 0; b < buckets; b++) {
        views[b] = bucketIndexes.get(b).openBlockMaxView(terms);
        if (views[b] != null) {
          for (int t = 0; t < termCount; t++)
            documentFrequencies[t] += views[b].documentFrequencies()[t];
          continue;
        }
        postings[b] = new Postings[termCount];
        for (int t = 0; t < termCount; t++) {
          final Postings termPostings = new Postings();
          try (final IndexCursor cursor = bucketIndexes.get(b).getPostings(terms.get(t).key())) {
            while (cursor.hasNext()) {
              guard.checkPeriodically(++ticks);
              final Identifiable posting = cursor.next();
              final RID rid = posting.getIdentity();
              // Deletion markers carry a negative bucket id: not live documents, neither matches nor counted
              if (rid.getBucketId() < 0)
                continue;
              final long encoded = encode(rid);
              if (encoded < 0)
                return null;
              if (posting instanceof FullTextPostingRID stats)
                termPostings.add(encoded, stats.getTf(), stats.getDocLength());
              else
                termPostings.add(encoded, -1, 0);
              ++termPostings.live;
            }
          }
          postings[b][t] = termPostings;
          documentFrequencies[t] += termPostings.live;
        }
      }

      final LSMTreeFullTextIndex first = bucketIndexes.getFirst();
      final long totalDocs;
      final double avgdl;
      if (buckets == 1) {
        // One bucket: its own statistics, as the bucket-level scoring of the general path uses
        final double[] statistics = first.bucketBM25Statistics();
        totalDocs = (long) statistics[0];
        avgdl = statistics[1];
      } else {
        first.ensureTypeWideBM25Counters();
        totalDocs = Math.max(1L, first.getTypeWideDocumentCount());
        avgdl = BM25Scorer.lengthNormalizer(first.getTypeWideAverageDocumentLength());
      }
      final double k1 = first.getFullTextMetadata().getBm25K1();
      final double b = first.getFullTextMetadata().getBm25B();
      final BlockMaxScorer.Scoring scoring = new BlockMaxScorer.Scoring(avgdl, k1, b);

      final double[] idfs = new double[termCount];
      long requiredMask = 0L;
      for (int t = 0; t < termCount; t++) {
        idfs[t] = documentFrequencies[t] > 0 ? BM25Scorer.idf(totalDocs, documentFrequencies[t]) : 0.0;
        if (terms.get(t).required())
          requiredMask |= 1L << t;
      }

      // Pass two: score each bucket and keep its best `limit`
      final BasicDatabase database = first.getDatabase();
      final Map<RID, Float> results = new HashMap<>();
      final TopKHeap heap = new TopKHeap(limit);
      for (int bucket = 0; bucket < buckets; bucket++) {
        heap.clear();
        if (views[bucket] != null) {
          BLOCK_MAX_INVOCATIONS.incrementAndGet();
          final BlockMaxScorer.TermCursor[] cursors = views[bucket].cursors();
          try {
            for (int t = 0; t < termCount; t++)
              cursors[t].start(scoring, idfs[t], terms.get(t).boost());
            BlockMaxScorer.score(cursors, requiredMask, heap, scoring, guard);
          } catch (final BlockMaxPostings.SidecarReadException e) {
            // The file itself: set aside, and the query runs again without it
            throw new SidecarFailure(bucket, e);
          } catch (final IOException e) {
            // Not the file: this thread's interrupt, or a channel that keeps closing. The query fails, the sidecar stays
            throw readError(e);
          }
          for (final BlockMaxScorer.TermCursor cursor : cursors)
            BLOCKS_DECODED.addAndGet(cursor.blocksDecoded);
        } else if (termCount == 1)
          scoreSingleTerm(postings[bucket][0], idfs[0], terms.getFirst().boost(), avgdl, k1, b, heap, guard);
        else
          scoreTerms(postings[bucket], terms, idfs, requiredMask, avgdl, k1, b, heap, guard);
        heap.drainInto(results, database);
      }
      return results;
    }
  }

  private static void scoreSingleTerm(final Postings postings, final double idf, final float boost, final double avgdl,
      final double k1, final double b, final TopKHeap heap, final WorkGuard guard) {
    for (int i = 0; i < postings.size; i++) {
      guard.checkPeriodically(i);
      final double score = contribution(postings.tfs[i], postings.dls[i], idf, boost, avgdl, k1, b);
      heap.offer((float) score, postings.rids[i]);
    }
  }

  private static void scoreTerms(final Postings[] postings, final List<Term> terms, final double[] idfs, final long requiredMask,
      final double avgdl, final double k1, final double b, final TopKHeap heap, final WorkGuard guard) {
    long capacity = 0;
    for (final Postings termPostings : postings)
      capacity += termPostings.size;
    final Accumulator accumulator = new Accumulator(accumulatorCapacity(capacity));

    int ticks = 0;
    // Terms in key order: the order the general path sums them in
    for (int t = 0; t < postings.length; t++) {
      final Postings termPostings = postings[t];
      if (termPostings.size == 0)
        continue;
      final long bit = 1L << t;
      final double idf = idfs[t];
      final float boost = terms.get(t).boost();
      for (int i = 0; i < termPostings.size; i++) {
        guard.checkPeriodically(++ticks);
        accumulator.add(termPostings.rids[i], contribution(termPostings.tfs[i], termPostings.dls[i], idf, boost, avgdl, k1, b), bit);
      }
    }

    for (int slot = 0; slot < accumulator.keys.length; slot++) {
      final long rid = accumulator.keys[slot];
      if (rid == Accumulator.EMPTY)
        continue;
      guard.checkPeriodically(++ticks);
      final long mask = accumulator.masks[slot];
      if ((mask & requiredMask) == requiredMask)
        heap.offer((float) accumulator.scores[slot], rid);
    }
  }

  /**
   * The error a query reports for a sidecar read that failed for a reason other than the file: its thread was interrupted (a
   * cancelled future, an executor shutting down), or the shared channel kept closing under it. Neither retires the sidecar.
   */
  static RuntimeException readError(final IOException e) {
    if (e instanceof ClosedByInterruptException)
      return new CommandExecutionException("The full-text query has been interrupted", e);
    return new IndexException("Error reading the block-max postings of a full-text index", e);
  }

  /** One term's share of a document's score, the expression the general path evaluates. */
  private static double contribution(final int tf, final int dl, final double idf, final float boost, final double avgdl,
      final double k1, final double b) {
    return tf < 0 ? 0.0 : BM25Scorer.termScore(idf, tf, dl, avgdl, k1, b) * boost;
  }

  /**
   * A RID as one long that orders like {@link RID#compareTo}: bucket id in the high bits, offset in the low 47. Answers -1 for a
   * RID that does not fit, which sends the query to the general path.
   */
  static long encode(final RID rid) {
    return encode(rid.getBucketId(), rid.getPosition());
  }

  static long encode(final int bucketId, final long offset) {
    if (bucketId < 0 || offset < 0 || offset >= (1L << 47) || bucketId >= (1 << 16))
      return -1L;
    return ((long) bucketId << 47) | offset;
  }

  private static RID decode(final BasicDatabase database, final long encoded) {
    return new DatabaseRID(database, (int) (encoded >>> 47), encoded & ((1L << 47) - 1));
  }

  /**
   * Open addressing over primitive arrays: a document's running score and the bits of the terms it matched.
   */
  private static final class Accumulator {
    static final long EMPTY = -1L;
    final long[]   keys;
    final double[] scores;
    final long[]   masks;
    final int      mask;

    /** @param capacity a power of two, from {@link #accumulatorCapacity} */
    Accumulator(final int capacity) {
      keys = new long[capacity];
      Arrays.fill(keys, EMPTY);
      scores = new double[capacity];
      masks = new long[capacity];
      mask = capacity - 1;
    }

    void add(final long key, final double score, final long bit) {
      int slot = (int) mix(key) & mask;
      while (true) {
        final long current = keys[slot];
        if (current == key) {
          scores[slot] += score;
          masks[slot] |= bit;
          return;
        }
        if (current == EMPTY) {
          keys[slot] = key;
          scores[slot] = score;
          masks[slot] = bit;
          return;
        }
        slot = (slot + 1) & mask;
      }
    }

    private static long mix(long key) {
      key ^= key >>> 33;
      key *= 0xff51afd7ed558ccdL;
      key ^= key >>> 33;
      return key;
    }
  }

  /**
   * The best {@code limit} (score, RID) pairs, best meaning higher score and then lower RID, the order of the general path's
   * ranking. A binary min-heap on primitives whose root is the worst kept pair.
   */
  static final class TopKHeap {
    private final int     limit;
    private       float[] scores;
    private       long[]  rids;
    private       int     size;

    TopKHeap(final int limit) {
      this.limit = limit;
      this.scores = new float[Math.min(limit, 1 << 16)];
      this.rids = new long[scores.length];
    }

    void clear() {
      size = 0;
    }

    boolean isFull() {
      return size >= limit;
    }

    /** The score of the worst pair kept: the one a newcomer has to beat. Only meaningful when {@link #isFull()}. */
    float worstScore() {
      return scores[0];
    }

    /** True when (s1, r1) ranks below (s2, r2). */
    private static boolean worse(final float s1, final long r1, final float s2, final long r2) {
      final int cmp = Float.compare(s1, s2);
      return cmp != 0 ? cmp < 0 : r1 > r2;
    }

    void offer(final float score, final long rid) {
      if (size < limit) {
        if (size == scores.length)
          grow();
        int i = size++;
        while (i > 0) {
          final int parent = (i - 1) >>> 1;
          if (!worse(score, rid, scores[parent], rids[parent]))
            break;
          scores[i] = scores[parent];
          rids[i] = rids[parent];
          i = parent;
        }
        scores[i] = score;
        rids[i] = rid;
      } else if (worse(scores[0], rids[0], score, rid)) {
        // Replace the worst and sift it down
        int i = 0;
        while (true) {
          final int left = 2 * i + 1;
          if (left >= size)
            break;
          final int right = left + 1;
          final int worst = right < size && worse(scores[right], rids[right], scores[left], rids[left]) ? right : left;
          if (!worse(scores[worst], rids[worst], score, rid))
            break;
          scores[i] = scores[worst];
          rids[i] = rids[worst];
          i = worst;
        }
        scores[i] = score;
        rids[i] = rid;
      }
    }

    private void grow() {
      final int capacity = (int) Math.min(limit, (long) scores.length << 1);
      scores = Arrays.copyOf(scores, capacity);
      rids = Arrays.copyOf(rids, capacity);
    }

    void drainInto(final Map<RID, Float> results, final BasicDatabase database) {
      for (int i = 0; i < size; i++)
        results.put(decode(database, rids[i]), scores[i]);
      size = 0;
    }
  }
}
