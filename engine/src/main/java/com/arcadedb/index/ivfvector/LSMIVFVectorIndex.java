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
package com.arcadedb.index.ivfvector;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.database.TransactionIndexContext;
import com.arcadedb.engine.BasePage;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.engine.PaginatedComponent;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.olap.GraphAlgorithms;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.IndexException;
import com.arcadedb.index.IndexFactoryHandler;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.lsm.LSMTreeIndex;
import com.arcadedb.index.lsm.LSMTreeIndexAbstract;
import com.arcadedb.index.vector.VectorUtils;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.QueryEngineManager;
import com.arcadedb.schema.IndexBuilder;
import com.arcadedb.schema.IndexMetadata;
import com.arcadedb.schema.LSMIVFVectorIndexMetadata;
import com.arcadedb.schema.LocalSchema;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.IntHashSet;
import com.arcadedb.utility.LongHashSet;
import com.arcadedb.utility.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * SPANN-style inverted-file (IVF) dense vector index: centroids trained once per build and kept resident, one posting
 * list per centroid stored as pages of an {@link IVFPostingsComponent}, nothing per vector on the heap.
 * <p>
 * <b>Writes</b> ride the transaction: {@link #put} assigns the vector to its nearest centroid and codes it on the
 * writing thread, queues an {@link IVFReplayKey} on the transaction's append-only lane, and {@link #putReplay} appends
 * the entry to the posting page at commit, under the commit lock of the file, inside the committing transaction - so
 * the posting pages reach the WAL and the replication stream exactly as bucket pages do. A delete finds the entry through
 * the position locator ({@link IVFLocator}) and clears its live flag.
 * <p>
 * <b>Searches</b> pick {@code nprobe} centroids in two levels ({@link IVFCentroids#select}), scan the live entries of
 * their posting pages on the scan code ({@link IVFCodec}) - the lists of one query on several threads of the query pool
 * when there is more than one - keep an absolute budget of candidates ({@code rerankCandidates}, 2,000 by default) in
 * primitive heaps merged into one, rerank them on the rerank code read off the same pages, optionally rescore the best of
 * them on their stored vectors ({@code exactRerank}), and answer {@code k}. An allow-list of at most
 * {@code exactFilterThreshold} records is scored exactly from the records; a wider one is a bitmap probed per entry that widens
 * the probe in proportion to its selectivity.
 * <p>
 * An index created on an empty type is untrained (one list); it trains by itself, in one transaction, once it holds
 * {@code autoTrainThreshold} vectors, and trains again in the background each time its live entries reach four times what the last
 * training saw, so the lists keep about {@code targetPostingSize} entries as the corpus grows (up to a re-population that fits one
 * transaction; past it a WARNING asks for a REBUILD INDEX).
 * <p>
 * Like {@code LSM_SPARSE_VECTOR} the index is built on an {@link LSMTreeIndex} shell that holds its registration and
 * never a key; the postings component is a sibling file resolved by name.
 */
public class LSMIVFVectorIndex implements Index, IndexInternal {
  public static final String COMPONENT_SUFFIX = "_ivf";
  /** Widest the allow-list may stretch the probe: a filter at 1/16 of the corpus and below probes 16 times the lists. */
  private static final int MAX_FILTER_PROBE_FACTOR = 16;
  /** Survivors at or above which the rerank is split over the query pool (when the scan was). */
  private static final int PARALLEL_RERANK_MIN     = 512;
  /** Lists per scan task at least: a task per list costs more in hand-off than a 200-entry list costs to scan. */
  private static final int LISTS_PER_TASK          = 2;
  /** Vectors a build codes per parallel batch before appending them on the build's thread. */
  private static final int BUILD_CODING_BATCH      = 8_192;
  /**
   * How long a read that met a publication half done (a link to a page past the committed page count, a header whose sections are not
   * there yet) waits for it before giving up: a training of a large partition publishes tens of thousands of pages.
   */
  private static final long CONSISTENT_READ_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
  /** Longest single pause between two reads of such a wait. */
  private static final long MAX_READ_PAUSE_NANOS   = TimeUnit.MILLISECONDS.toNanos(5);
  /** Optimistic attempts of the automatic first training against concurrent writers before the one under an explicit lock. */
  private static final int AUTO_TRAIN_ATTEMPTS     = 2;
  /** First pause after an automatic training lost to a writer, doubled on every further loss. */
  private static final long AUTO_TRAIN_BACKOFF_MS  = 50;
  /** Growth of the live entries over what the last training saw at which the automatic training runs again (sized lists only). */
  private static final int  RETRAIN_GROWTH_FACTOR  = 4;
  /** Writes to a trained index between two checks of its growth: one header read, after the commit of the write that crosses it. */
  private static final int  GROWTH_CHECK_EVERY     = 1 << 10;
  /**
   * Largest re-population (live entries x entry size) the automatic retraining runs in its one transaction; past it the index logs a
   * WARNING asking for a REBUILD INDEX, which commits in batches.
   */
  private static final long RETRAIN_MAX_TRANSACTION_BYTES = 128L << 20;
  /** Average live entries per list, in multiples of {@code targetPostingSize}, past which lists no automatic training shortens are warned of. */
  private static final int  LONG_LIST_FACTOR       = 4;
  /** Minimum training sample per list wanted: below it the lists are fewer than asked for (k-means cannot have more lists than points). */
  private static final int SAMPLE_PER_LIST         = 30;

  /** Diagnostic switch for A/B measurements of the build: pass 2 codes its batches on the query pool when true. */
  private static volatile boolean parallelBuild = true;
  /**
   * Diagnostic switch for A/B measurements of the build: a build that commits in chunks stages its entries on the commit lane when
   * true; false is the build of the third review, which wrote the layout in its first chunk and appended on the pages.
   */
  private static volatile boolean stagedBuild   = true;
  /** Diagnostic switch for A/B measurements of allow-lists, JVM-wide: the spike's sorted-array filter instead of the blocked bitmap. */
  private static volatile boolean sortedFilter  = false;

  private final    LSMTreeIndex              shell;
  private final    DatabaseInternal          database;
  private          LSMIVFVectorIndexMetadata ivfMetadata;
  private          TypeIndex                 typeIndex;
  private volatile IVFPostingsComponent      postings;
  private volatile IVFCentroids              centroids;
  /** The set {@link #centroids} replaced: what a REPEATABLE_READ transaction that pinned the header before a training still reads. */
  private volatile IVFCentroids              previousCentroids;
  /** One loader of the committed centroids at a time: the others wait for its set instead of reading their own. */
  private final    ReentrantLock             centroidLoadLock    = new ReentrantLock();
  private volatile EntryLayout               entryLayout;
  /** Diagnostic switch for A/B measurements: the posting lists of one query are read on the query pool when true. */
  private volatile boolean                   parallelScan        = true;
  private final    AtomicBoolean             autoTraining        = new AtomicBoolean();
  /** Live entries the untrained list must reach before the next automatic training, after one that failed; 0 for the threshold. */
  private volatile long                      nextAutoTrainAt;
  /** Built once: the attachment key of an uncommitted training. */
  private volatile String                    trainingKey;
  /** Built once: the after-commit key is asked on every write to an untrained index. */
  private volatile String                    autoTrainKey;
  /** Built once: marks the transaction whose commit replay released its read pins of the postings file. */
  private volatile String                    replayKey;
  /** The writes that overlap a chunked build, from the commit of its layout to the commit of its last chunk; null otherwise. */
  private volatile BuildOverlap              buildOverlap;
  private final    AtomicLong                searches            = new AtomicLong();
  private final    AtomicLong                scannedEntries      = new AtomicLong();
  private final    AtomicLong                rerankedCandidates  = new AtomicLong();
  private final    AtomicLong                parallelSearches    = new AtomicLong();
  private final    AtomicLong                exactFilterSearches = new AtomicLong();
  private final    AtomicLong                exactReranks        = new AtomicLong();
  private final    AtomicLong                locatorDeletes      = new AtomicLong();
  private final    AtomicLong                walkedDeletes       = new AtomicLong();
  private final    AtomicLong                trainings           = new AtomicLong();
  private final    AtomicLong                autoTrainings       = new AtomicLong();
  private final    AtomicLong                autoRetrainings     = new AtomicLong();
  /** Writes to a trained index, counted to check its growth every {@link #GROWTH_CHECK_EVERY}. */
  private final    AtomicInteger             growthWrites        = new AtomicInteger();
  /** Live entries at the last WARNING about long lists: the next one waits until they have doubled. */
  private volatile long                      longListsWarnedAt;
  private final    AtomicLong                replayRecodes       = new AtomicLong();
  private final    AtomicLong                retriedSearches     = new AtomicLong();
  private final    AtomicLong                skippedBuildEntries = new AtomicLong();

  public static class LSMIVFVectorIndexFactoryHandler implements IndexFactoryHandler {
    @Override
    public IndexInternal create(final IndexBuilder<? extends Index> builder) {
      if (builder.isUnique())
        throw new IllegalArgumentException("IVF vector index cannot be unique");
      final Type[] keyTypes = builder.getKeyTypes();
      if (keyTypes == null || keyTypes.length != 1 || keyTypes[0] != Type.ARRAY_OF_FLOATS)
        throw new IllegalArgumentException("IVF vector index requires exactly one ARRAY_OF_FLOATS property, found: "
            + (keyTypes == null ? "none" : Arrays.toString(keyTypes)));
      if (!(builder.getMetadata() instanceof LSMIVFVectorIndexMetadata metadata))
        throw new IllegalArgumentException("IVF vector index requires LSMIVFVectorIndexMetadata, found: "
            + (builder.getMetadata() == null ? "null" : builder.getMetadata().getClass().getSimpleName()));
      if (metadata.dimensions < 1)
        throw new IndexException("LSM_IVF_VECTOR index '" + builder.getIndexName() + "' requires a positive 'dimensions' setting");
      if (metadata.scanCode == IVFScanCode.PRODUCT)
        throw new IndexException("LSM_IVF_VECTOR scanCode PRODUCT is not implemented yet; use BINARY or INT8");
      if (metadata.similarity == IVFSimilarity.HAMMING && metadata.scanCode != IVFScanCode.BINARY)
        throw new IndexException("LSM_IVF_VECTOR with HAMMING similarity scans BINARY codes only");
      if (metadata.similarity == IVFSimilarity.HAMMING && metadata.rerankCode != IVFRerankCode.NONE)
        throw new IndexException("LSM_IVF_VECTOR with HAMMING similarity has nothing to rerank on: use rerankCode NONE");
      if (metadata.scanCode == IVFScanCode.INT8 && metadata.rerankCode == IVFRerankCode.INT4)
        throw new IndexException("LSM_IVF_VECTOR with an INT8 scan code reranks on the scan code itself: use rerankCode INT8 or NONE");
      return new LSMIVFVectorIndex(builder.getDatabase(), builder.getIndexName(), builder.getFilePath(), ComponentFile.MODE.READ_WRITE,
          builder.getPageSize(), builder.getNullStrategy(), metadata);
    }
  }

  /** Load time: wraps the shell the schema read back, the postings component is resolved by name on first use. */
  public LSMIVFVectorIndex(final LSMTreeIndex shell, final LSMIVFVectorIndexMetadata metadata) {
    this.shell = shell;
    this.database = shell.getMutableIndex().getDatabase();
    this.ivfMetadata = metadata;
  }

  /** Creation time: the shell, the postings file and its untrained layout, all inside the creating transaction. */
  public LSMIVFVectorIndex(final DatabaseInternal database, final String name, final String filePath, final ComponentFile.MODE mode,
      final int pageSize, final LSMTreeIndexAbstract.NULL_STRATEGY nullStrategy, final LSMIVFVectorIndexMetadata metadata) {
    this.database = database;
    this.ivfMetadata = metadata;
    this.shell = new LSMTreeIndex(database, name, false, filePath, mode, new Type[] { Type.ARRAY_OF_FLOATS }, pageSize, nullStrategy);
    final String componentName = name + COMPONENT_SUFFIX;
    try {
      this.postings = new IVFPostingsComponent(database, componentName, database.getDatabasePath() + File.separator + componentName, mode,
          IVFPostingsComponent.DEFAULT_PAGE_SIZE);
    } catch (final IOException e) {
      throw new IndexException("Cannot create the postings file of IVF index '" + name + "'", e);
    }
    ((LocalSchema) database.getSchema().getEmbedded()).registerFile(postings);
    final IVFCentroids untrained = IVFCentroids.untrained(metadata.dimensions, 1, projectionSeed(metadata));
    postings.writeLayout(untrained, metadata.similarity, metadata.scanCode, metadata.rerankCode, true);
    this.centroids = untrained;
  }

  // ---------------------------------------------------------------- state

  private IVFPostingsComponent postings() {
    IVFPostingsComponent component = postings;
    if (component == null) {
      final Object file = ((LocalSchema) database.getSchema().getEmbedded()).getFileByName(shell.getName() + COMPONENT_SUFFIX);
      if (!(file instanceof IVFPostingsComponent found))
        throw new IndexException("IVF index '" + getName() + "': postings file '" + shell.getName() + COMPONENT_SUFFIX + "' not found");
      postings = component = found;
    }
    return component;
  }

  /** The postings file, for the tests of its structures (the locator). */
  IVFPostingsComponent getPostingsComponent() {
    return postings();
  }

  /**
   * The centroids of the layout the calling transaction sees: the resident set when its version is the one the header of that view
   * names, the set it replaced when that is the one (a REPEATABLE_READ transaction that pinned the header before a training), and
   * otherwise a set read from the pages.
   * <p>
   * A set read from the COMMITTED state is published for every caller, by one loader at a time ({@link #centroidLoadLock}): the
   * others wait for it rather than each reading its own copy and starting its own graph build, and a published set is never
   * replaced by an older one. A header can name sections that are not published yet ({@link #readCommittedCentroids}): the read waits
   * for them. A set the calling transaction alone sees (its own uncommitted training, an older header it pinned) is read privately and
   * never published.
   */
  private IVFCentroids centroids() {
    final IVFPostingsComponent component = postings();
    long deadline = 0;
    for (int attempt = 0; ; attempt++) {
      final int version = component.readCentroidVersion();
      final IVFCentroids resident = centroids;
      if (resident != null && resident.version == version)
        return resident;
      final IVFCentroids replaced = previousCentroids;
      if (replaced != null && replaced.version == version)
        return replaced;

      if (version == component.readCommittedCentroidVersion()) {
        centroidLoadLock.lock();
        try {
          final IVFCentroids current = centroids;
          if (current != null && current.version == version)
            return current;
          final IVFCentroids loaded = readCommittedCentroids(component);
          publishCentroids(loaded);
          if (loaded.version == version)
            return loaded;
          // A newer training committed since the caller's view was read: published for everybody, the view is read again
          continue;
        } finally {
          centroidLoadLock.unlock();
        }
      }

      final TransactionContext tx = database.getTransactionIfExists();
      if (tx != null && tx.getAttachment(trainingAttachmentKey()) instanceof IVFCentroids trained && trained.version == version)
        return trained;
      final IVFPostingsComponent.Header header = component.readHeader();
      if (header == null)
        return IVFCentroids.untrained(ivfMetadata.dimensions, -1, projectionSeed(ivfMetadata));
      final IVFCentroids own = component.readCentroids(header);
      if (own != null)
        return own;
      // The view moved on to a training still being published (READ_COMMITTED): read it again once it is
      if (deadline == 0)
        deadline = System.nanoTime() + CONSISTENT_READ_TIMEOUT_NANOS;
      if (!pauseForPublication(attempt, deadline))
        throw new IndexException("IVF index '" + getName() + "': the header names centroid pages the transaction cannot read");
    }
  }

  /**
   * The centroids a write codes its entry against: the transaction's own uncommitted training when it has one, the committed set
   * otherwise - never the header page read through the transaction. Under REPEATABLE_READ that read pinned page 0 for the rest of
   * the transaction, the commit replay appended on the pinned copy, and every commit to the index in between, whatever list or
   * record it touched, failed the page-version check. The assignment is a hint anyway: the replay codes the entry again when the
   * layout it lands on is of another version ({@link #applyAdd}).
   */
  private IVFCentroids centroidsForWrite() {
    final TransactionContext tx = database.getTransactionIfExists();
    if (tx != null && tx.getAttachment(trainingAttachmentKey()) instanceof IVFCentroids trained)
      return trained;
    final IVFPostingsComponent component = postings();
    final int committed = component.readCommittedCentroidVersion();
    final IVFCentroids resident = centroids;
    if (resident != null && resident.version == committed)
      return resident;
    if (committed < 0)
      // Nothing committed yet: the layout is the creating transaction's own
      return centroids();
    centroidLoadLock.lock();
    try {
      final IVFCentroids current = centroids;
      if (current != null && current.version >= committed)
        return current;
      final IVFCentroids loaded = readCommittedCentroids(component);
      publishCentroids(loaded);
      return loaded;
    } finally {
      centroidLoadLock.unlock();
    }
  }

  /** Where a transaction that trains keeps its uncommitted set, for its own later writes: never in the shared field before the commit. */
  private String trainingAttachmentKey() {
    String key = trainingKey;
    if (key == null)
      trainingKey = key = getName() + "#ivfTraining";
    return key;
  }

  /** Makes {@code loaded} the resident set unless a newer one is resident already; the one it replaces is kept as the previous. */
  private void publishCentroids(final IVFCentroids loaded) {
    centroidLoadLock.lock();
    try {
      final IVFCentroids current = centroids;
      if (current != null && current.version >= loaded.version)
        return;
      previousCentroids = current;
      centroids = loaded;
    } finally {
      centroidLoadLock.unlock();
    }
    // Only the set actually published gets a graph: a loser of the race never starts a build of its own
    loaded.buildGraphInBackground();
  }

  /**
   * The centroid set of the committed header. The sections a header names are new pages that nothing writes again, so a read is
   * complete when every page of them lies below the committed page count read after the header, and the coarse starts frame the fine
   * rows ({@link IVFPostingsComponent#readCentroids(IVFPostingsComponent.Header, boolean)}): the page count is raised only once a page
   * is in the read cache (after the whole publication on a commit, page by page in allocation order on a replicated apply). A header
   * whose sections are not there yet is a publication in flight: the read waits for it, bounded by time, and never takes the
   * page-manager lock (a load of a large set takes seconds, and that lock is every committer's of the JVM).
   */
  private IVFCentroids readCommittedCentroids(final IVFPostingsComponent component) {
    final long deadline = System.nanoTime() + CONSISTENT_READ_TIMEOUT_NANOS;
    for (int attempt = 0; ; attempt++) {
      final IVFCentroids loaded = readCommittedCentroidsOnce(component);
      if (loaded != null)
        return loaded;
      if (!pauseForPublication(attempt, deadline))
        throw new IndexException("IVF index '" + getName() + "': the committed header names centroid pages that were not published within "
            + TimeUnit.NANOSECONDS.toSeconds(CONSISTENT_READ_TIMEOUT_NANOS) + "s");
    }
  }

  private IVFCentroids readCommittedCentroidsOnce(final IVFPostingsComponent component) {
    final IVFPostingsComponent.Header header = component.readCommittedHeader();
    return header == null ? IVFCentroids.untrained(ivfMetadata.dimensions, -1, projectionSeed(ivfMetadata)) :
        component.readCentroids(header, true);
  }

  /**
   * A short pause before a read that met a publication half done is read again: a few yields, then parks that double up to
   * {@link #MAX_READ_PAUSE_NANOS}. Answers false, without pausing, once {@code deadline} has passed or the thread is interrupted.
   * <p>
   * Nothing here looks at the page manager's publication sequence: that counter is one for the whole JVM, so every commit of every
   * database moved it, and a search validated against it was read again for writes to files it never touches, then run under the
   * page-manager lock, which froze every committer of the process for the length of the scan. A replicated apply does not even hold
   * that lock (#10941), so waiting for it waited for nothing.
   */
  private static boolean pauseForPublication(final int attempt, final long deadline) {
    if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
      return false;
    if (attempt < 4)
      Thread.yield();
    else
      LockSupport.parkNanos(Math.min(MAX_READ_PAUSE_NANOS, 20_000L << Math.min(attempt - 4, 10)));
    return true;
  }

  /** SimHash seed of the BINARY codes: none for HAMMING, whose vectors are the bits already. */
  private static int projectionSeed(final LSMIVFVectorIndexMetadata metadata) {
    return metadata.similarity == IVFSimilarity.HAMMING ? 0 : 7919;
  }

  public LSMIVFVectorIndexMetadata getIVFMetadata() {
    return ivfMetadata;
  }

  public int getCentroidCount() {
    return reading(() -> centroids().nlist);
  }

  public boolean isTrained() {
    return reading(() -> centroids().trained);
  }

  /** Runs {@code reader} inside the calling transaction, or a read-only one opened and closed for it. */
  private <T> T reading(final Supplier<T> reader) {
    if (database.isTransactionActive())
      return reader.get();
    database.begin();
    try {
      return reader.get();
    } finally {
      database.rollback();
    }
  }

  /** Bytes the index keeps on the heap: the centroids, and nothing per vector. */
  public long getResidentBytes() {
    final IVFCentroids resident = centroids;
    return resident == null ? 0 : resident.residentBytes();
  }

  public long getSearches() {
    return searches.get();
  }

  public long getScannedEntries() {
    return scannedEntries.get();
  }

  /** Diagnostic switch for A/B measurements: false reads the posting lists of every query on the calling thread alone. */
  public void setParallelScan(final boolean parallelScan) {
    this.parallelScan = parallelScan;
  }

  /** Diagnostic switch for A/B measurements of allow-lists, JVM-wide: true probes them through a sorted array (the spike's fallback). */
  public static void setSortedFilter(final boolean sorted) {
    sortedFilter = sorted;
  }

  /** Diagnostic switch for A/B measurements of builds, JVM-wide: false codes pass 2 on the build's thread alone. */
  public static void setParallelBuild(final boolean parallel) {
    parallelBuild = parallel;
  }

  /** Diagnostic switch for A/B measurements of builds, JVM-wide: false appends a chunked build's entries in its chunk's body. */
  public static void setStagedBuild(final boolean staged) {
    stagedBuild = staged;
  }

  // ---------------------------------------------------------------- entry layout

  private static final int RERANK_NONE = 0;
  private static final int RERANK_INT8 = 1;
  private static final int RERANK_INT4 = 2;

  /** Where the codes sit inside an entry, derived from the metadata; see {@link IVFPostingsComponent} for the page layout. */
  private final class EntryLayout {
    final int     dimensions   = ivfMetadata.dimensions;
    final boolean binaryScan   = ivfMetadata.scanCode == IVFScanCode.BINARY;
    final int     scanBytes    = binaryScan ? IVFCodec.binaryBytes(dimensions) : dimensions + 4;
    /** An INT8 scan code is its own rerank: no second code is kept then. */
    final int     rerankKind   = binaryScan ?
        (ivfMetadata.rerankCode == IVFRerankCode.INT8 ? RERANK_INT8 : ivfMetadata.rerankCode == IVFRerankCode.INT4 ? RERANK_INT4 : RERANK_NONE) :
        RERANK_NONE;
    final boolean hasRerank    = rerankKind != RERANK_NONE;
    final int     rerankBytes  = rerankKind == RERANK_INT8 ? dimensions + 4 : rerankKind == RERANK_INT4 ? IVFCodec.int4Bytes(dimensions) : 0;
    /** Offset of the rerank code inside the entry payload (after the live flag and the position). */
    final int     rerankOffset = scanBytes;
    final int     payloadSize  = scanBytes + rerankBytes;
    final int     entrySize    = IVFPostingsComponent.E_CODES + payloadSize;
  }

  private EntryLayout layout() {
    EntryLayout layout = entryLayout;
    if (layout == null)
      entryLayout = layout = new EntryLayout();
    return layout;
  }

  /** Bytes of one posting entry: live flag, position, scan code and rerank code. */
  public int getEntrySize() {
    return layout().entrySize;
  }

  /**
   * Codes {@code vector} (normalized when the similarity asks for it) into an entry payload for list {@code centroid} of
   * {@code resident}. Touches no page and no transaction, so the build runs it on the query pool.
   */
  private byte[] encode(final float[] vector, final int centroid, final IVFCentroids resident, final EntryLayout layout) {
    final byte[] payload = new byte[layout.payloadSize];
    final ByteBuffer buffer = ByteBuffer.wrap(payload);
    if (layout.binaryScan) {
      final long[] words = new long[IVFCodec.binaryWords(layout.dimensions)];
      IVFCodec.binaryCode(vector, resident.projection, resident.thresholds, words);
      for (final long word : words)
        buffer.putLong(word);
    } else {
      final float scale = IVFCodec.int8Scale(vector);
      final byte[] code = new byte[layout.dimensions];
      IVFCodec.int8Code(vector, scale, code);
      buffer.put(code).putFloat(scale);
    }
    if (layout.rerankKind == RERANK_INT8) {
      final float scale = IVFCodec.int8Scale(vector);
      final byte[] code = new byte[layout.dimensions];
      IVFCodec.int8Code(vector, scale, code);
      buffer.put(code).putFloat(scale);
    } else if (layout.rerankKind == RERANK_INT4)
      IVFCodec.int4ResidualCode(ivfMetadata.similarity, vector, resident.fine, centroid * layout.dimensions, payload, layout.rerankOffset);
    return payload;
  }

  // ---------------------------------------------------------------- writes

  @Override
  public void put(final Object[] keys, final RID[] rids) {
    if (rids == null || rids.length == 0)
      return;
    if (isReplayKey(keys)) {
      applyAdd((IVFReplayKey) keys[0]);
      return;
    }
    final float[] vector = prepare(keys[0]);
    if (vector == null)
      return;
    final EntryLayout layout = layout();
    final IVFCentroids resident = centroidsForWrite();
    final int centroid = resident.assign(vector);
    final byte[] payload = encode(vector, centroid, resident, layout);
    for (final RID rid : rids)
      queueOrApply(TransactionIndexContext.IndexKey.IndexKeyOperation.ADD,
          new IVFReplayKey(centroid, resident.version, rid.getPosition(), payload, vector), rid);
    if (resident.trained ? (growthWrites.incrementAndGet() & (GROWTH_CHECK_EVERY - 1)) == 0 : ivfMetadata.autoTrainThreshold > 0)
      scheduleAutoTrain();
  }

  @Override
  public void remove(final Object[] keys) {
    if (isReplayKey(keys))
      throw new IllegalStateException("IVF replay REMOVE needs the RID");
    LogManager.instance().log(this, Level.WARNING,
        "%s.remove(keys) called without a RID; the IVF index needs the per-RID variant and removed nothing", null, getName());
  }

  @Override
  public void remove(final Object[] keys, final Identifiable rid) {
    if (rid == null)
      return;
    if (isReplayKey(keys)) {
      applyRemove((IVFReplayKey) keys[0]);
      return;
    }
    final float[] vector = prepare(keys[0]);
    if (vector == null)
      return;
    final RID identity = rid.getIdentity();
    // The locator finds the entry by position; the list the old vector maps to is only the fallback of a position it does not know
    final IVFCentroids resident = centroidsForWrite();
    queueOrApply(TransactionIndexContext.IndexKey.IndexKeyOperation.REMOVE,
        new IVFReplayKey(resident.assign(vector), resident.version, identity.getPosition(), null, vector), identity);
  }

  @Override
  public void putReplay(final Object[] keys, final RID[] rids) {
    if (isReplayKey(keys))
      applyAdd((IVFReplayKey) keys[0]);
    else
      put(keys, rids);
  }

  @Override
  public void removeReplay(final Object[] keys, final Identifiable rid) {
    if (isReplayKey(keys))
      applyRemove((IVFReplayKey) keys[0]);
    else
      remove(keys, rid);
  }

  private static boolean isReplayKey(final Object[] keys) {
    return keys != null && keys.length == 1 && keys[0] instanceof IVFReplayKey;
  }

  /** The vector of a key as the indexer hands it, normalized for COSINE; null for no vector or the wrong width. */
  private float[] prepare(final Object key) {
    if (key == null)
      return null;
    final float[] raw;
    try {
      raw = VectorUtils.toFloatArray(key);
    } catch (final IllegalArgumentException e) {
      throw new IndexException("IVF index '" + getName() + "': unsupported vector value " + key.getClass().getSimpleName(), e);
    }
    if (raw == null)
      return null;
    if (raw.length != ivfMetadata.dimensions)
      throw new IndexException("IVF index '" + getName() + "': vector has " + raw.length + " dimensions, the index " + ivfMetadata.dimensions);
    final float[] vector = raw.clone();
    if (ivfMetadata.similarity == IVFSimilarity.COSINE)
      IVFCodec.normalize(vector);
    return vector;
  }

  private void queueOrApply(final TransactionIndexContext.IndexKey.IndexKeyOperation operation, final IVFReplayKey key, final RID rid) {
    final TransactionContext tx = database.getTransactionIfExists();
    if (tx != null && tx.getStatus() == TransactionContext.STATUS.BEGUN) {
      tx.addIndexOperation(this, operation, new Object[] { key }, rid);
      return;
    }
    if (tx != null && tx.isActive()) {
      // A commit in flight (a deferred update indexed from commit1stPhase): the pages written now are the committing transaction's
      if (operation == TransactionIndexContext.IndexKey.IndexKeyOperation.REMOVE)
        applyRemove(key);
      else
        applyAdd(key);
      return;
    }
    database.transaction(() -> {
      if (operation == TransactionIndexContext.IndexKey.IndexKeyOperation.REMOVE)
        applyRemove(key);
      else
        applyAdd(key);
    });
  }

  /**
   * Appends the entry of {@code key} at commit. A key coded against centroids a training has since replaced (the automatic first
   * training landed between the write and its commit) is assigned and coded again from its vector against the current ones.
   */
  private void applyAdd(final IVFReplayKey key) {
    final IVFPostingsComponent component = postings();
    releaseReadPinsBeforeReplay(component);
    final IVFPostingsComponent.Header header = component.readHeader();
    if (header == null) {
      // The index is registered but its creation has not committed (a writer the database lock no longer holds back,
      // arcadedb.backup.enabled=false): the build that follows the creation scans the record this writer commits now
      LogManager.instance().log(this, Level.FINE, "IVF index '%s': no committed layout yet, position %d is left to the build", null,
          getName(), key.position());
      return;
    }
    if (key.build() && isStaleBuildEntry(key.position())) {
      skippedBuildEntries.incrementAndGet();
      return;
    }
    final EntryLayout layout = layout();
    int centroid = key.centroid();
    byte[] entry = key.entry();
    if (key.version() != header.version() || centroid >= header.nlist()) {
      final IVFCentroids resident = centroids();
      if (key.vector() == null)
        throw new IndexException("IVF index '" + getName() + "': an entry coded for centroids " + key.version()
            + " cannot be appended to the lists of version " + header.version());
      centroid = resident.assign(key.vector());
      entry = encode(key.vector(), centroid, resident, layout);
      replayRecodes.incrementAndGet();
      IVFVectorIndexMetrics.INSTANCE.replayRecodes.increment();
    }
    component.append(header, centroid, key.position(), entry, layout.entrySize);
  }

  private void applyRemove(final IVFReplayKey key) {
    final IVFPostingsComponent component = postings();
    releaseReadPinsBeforeReplay(component);
    final IVFPostingsComponent.Header header = component.readHeader();
    if (header == null)
      return;
    final BuildOverlap overlap = buildOverlap;
    if (overlap != null)
      overlap.removed(key.position());
    int centroid = key.centroid();
    if (key.version() != header.version())
      centroid = key.vector() == null ? -1 : centroids().assign(key.vector());
    final int found = component.markDeleted(header, centroid, key.position(), layout().entrySize);
    if (found == 1) {
      locatorDeletes.incrementAndGet();
      IVFVectorIndexMetrics.INSTANCE.locatorDeletes.increment();
    } else if (found == 2) {
      walkedDeletes.incrementAndGet();
      IVFVectorIndexMetrics.INSTANCE.walkedDeletes.increment();
    } else
      LogManager.instance().log(this, Level.FINE, "IVF index '%s': no live entry for position %d", null, getName(), key.position());
  }

  /**
   * Whether the entry a build staged for {@code position} is older than what the index holds now. The build is not a snapshot: it
   * stages the vector its scan read, and the chunk commits later, while writers keep committing (always to records the scan has not
   * reached yet, and to the open chunk's records too once the database lock is off, {@code arcadedb.backup.enabled=false}, or when the
   * build is called directly). Appending the staged entry over the write would replace the writer's newer entry (one live entry per
   * position) or bring a deleted record back as a live ghost. Every write that can make the staged entry stale removes the entry of
   * the record (an update, a delete, a vector cleared), so the entry is stale exactly when a removal of its position committed after
   * the layout ({@link #buildOverlap}). A write that only adds (an insert committed after the layout, before the scan reached it) leaves
   * an entry with the vector the scan then read: the staged one replaces it with the same vector, which costs one dead entry and
   * avoids a locator lookup per staged entry. Runs under the commit lock of the postings file, which every such write takes too.
   * <p>
   * This keeps the index of the JVM that runs the build right, and nothing more. Under Raft with the database lock off, a writer
   * on the leader that commits while a CREATE INDEX or REBUILD INDEX session is open waits for the session only up to
   * {@code HA_QUORUM_TIMEOUT} ({@code RaftReplicatedDatabase.waitForActiveRecordingSession}), then ships an ordinary transaction
   * entry. If that transaction wrote to the index being built, its pages name a file that exists only in the leader's uncommitted
   * session, and a follower that applies the entry before the session's schema entry drops them (the #4083 symptom), so the
   * followers can diverge from the leader. That is a general limitation of an index build inside a schema session on HA with
   * {@code arcadedb.backup.enabled=false}, for every index kind, not something this removal record can close.
   */
  private boolean isStaleBuildEntry(final long position) {
    final BuildOverlap overlap = buildOverlap;
    return overlap != null && overlap.replayed(position);
  }

  /**
   * The positions writes removed an entry of (or tried to: the record was not indexed yet) while a chunked build runs. A removal at
   * or below the highest position whose staged entry the replay has already decided on is not kept: the build stages positions in
   * ascending order (the bucket scan's), and chunks commit in that order, so that entry is on the pages already and the removal found
   * it. What is kept is therefore the removals ahead of the build, in a primitive set (about 11 bytes each), until the build ends.
   * It records only the removals this node replays, never one another node committed: why that is enough under HA, and what makes a
   * build fail rather than run where it would not be, is {@link #checkBuildIsExclusive}.
   */
  private static final class BuildOverlap {
    private final LongHashSet removed         = new LongHashSet();
    private       long        replayedThrough = -1L;

    synchronized void removed(final long position) {
      if (position > replayedThrough)
        removed.add(position);
    }

    /** Records that the staged entry of {@code position} is being replayed; answers whether a write removed it since the layout. */
    synchronized boolean replayed(final long position) {
      if (position > replayedThrough)
        replayedThrough = position;
      return removed.contains(position);
    }
  }

  /**
   * The commit replay appends on the postings pages as they are committed, under the commit lock. A REPEATABLE_READ transaction keeps
   * every page its body read (a search, a header read) pinned at the version it read, and the replay would append on that copy: any
   * commit to the index in between would fail the page-version check, whatever list or record it touched. So the first replay of a
   * commit releases those pins, unless the body wrote pages of the file itself (a training, a build in the caller's transaction): those
   * are the transaction's own state, and the version check is what judges them.
   */
  private void releaseReadPinsBeforeReplay(final IVFPostingsComponent component) {
    final TransactionContext tx = database.getTransactionIfExists();
    if (tx == null || tx.getIsolationLevel() != Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ
        || tx.getStatus() == TransactionContext.STATUS.BEGUN)
      return;
    final String key = replayPinsKey();
    if (tx.getAttachment(key) != null)
      return;
    tx.setAttachment(key, Boolean.TRUE);
    final IntHashSet files = new IntHashSet(1);
    files.add(component.getFileId());
    if (!tx.hasChangesInFiles(files))
      tx.unpinFiles(List.of(component.getFileId()));
  }

  private String replayPinsKey() {
    String key = replayKey;
    if (key == null)
      replayKey = key = getName() + "#ivfReplay";
    return key;
  }

  // ---------------------------------------------------------------- automatic training

  /**
   * An untrained index trains as soon as it holds {@code autoTrainThreshold} vectors, and a trained one trains again once its live
   * entries reach {@value #RETRAIN_GROWTH_FACTOR} times what its last training saw: checked after the commit of a write that landed on
   * the untrained list, or of every {@value #GROWTH_CHECK_EVERY}th write to a trained one (an after-commit callback, so the training
   * never joins the writer's transaction).
   */
  private void scheduleAutoTrain() {
    final TransactionContext tx = database.getTransactionIfExists();
    if (tx != null && tx.isActive())
      tx.addAfterCommitCallbackIfAbsent(autoTrainCallbackKey(), this::maybeAutoTrain);
    else
      maybeAutoTrain();
  }

  private String autoTrainCallbackKey() {
    String key = autoTrainKey;
    if (key == null)
      autoTrainKey = key = getName() + "#ivfAutoTrain";
    return key;
  }

  /**
   * Hands the automatic training to the query pool when {@link #trainingDue} says so, and after a training that lost to concurrent
   * writers, once the index holds twice what it held then: the writer whose commit crossed the threshold only reads the header, and
   * never trains on its own thread unless the pool is saturated (caller-runs). An index whose lists outgrew what any automatic training
   * covers is warned about instead.
   */
  void maybeAutoTrain() {
    if (!isValid() || autoTraining.get() || database.isTransactionActive())
      return;
    final IVFPostingsComponent.Header header = reading(() -> postings().readHeader());
    if (!trainingDue(header)) {
      warnOfLongLists(header);
      return;
    }
    if (!autoTraining.compareAndSet(false, true))
      return;
    try {
      QueryEngineManager.getInstance().getExecutorService().submit(this::autoTrain);
    } catch (final RuntimeException e) {
      autoTraining.set(false);
      LogManager.instance().log(this, Level.WARNING, "IVF index '%s': cannot schedule the automatic training", e, getName());
    }
  }

  /**
   * Whether the layout of {@code header} asks for an automatic training: the first one at {@code autoTrainThreshold} vectors, or one
   * more once the corpus has outgrown the last ({@link #outgrown}) and its re-population fits one transaction
   * ({@link #RETRAIN_MAX_TRANSACTION_BYTES}). Never with {@code autoTrainThreshold} 0, nor before the vectors a failed training
   * waits for.
   */
  private boolean trainingDue(final IVFPostingsComponent.Header header) {
    if (header == null || ivfMetadata.autoTrainThreshold <= 0 || header.liveEntries() < nextAutoTrainAt)
      return false;
    if (!header.trained())
      return header.liveEntries() >= ivfMetadata.autoTrainThreshold;
    return outgrown(header) && header.liveEntries() * layout().entrySize <= RETRAIN_MAX_TRANSACTION_BYTES;
  }

  /**
   * Whether a training now would make more lists than the layout has, the corpus having grown {@value #RETRAIN_GROWTH_FACTOR} times
   * since the last one (a file written before the count was kept: since its lists were full). Never with an explicit {@code nlist}: a
   * training makes the same lists then.
   */
  private boolean outgrown(final IVFPostingsComponent.Header header) {
    if (ivfMetadata.nlist > 0)
      return false;
    final long baseline = header.trainedAt() > 0 ? header.trainedAt() : (long) header.nlist() * ivfMetadata.targetPostingSize;
    return header.liveEntries() >= RETRAIN_GROWTH_FACTOR * Math.max(1, baseline) && listsWanted(header.liveEntries()) > header.nlist();
  }

  /**
   * A WARNING, once per doubling of the corpus, when the lists of a trained layout average more than {@value #LONG_LIST_FACTOR} times
   * {@code targetPostingSize} and no automatic training will shorten them (an explicit {@code nlist}, {@code autoTrainThreshold} 0, a
   * re-population past one transaction): every query scans proportionally more, and only a REBUILD INDEX re-sizes the partition.
   */
  private void warnOfLongLists(final IVFPostingsComponent.Header header) {
    if (header == null || !header.trained() || header.nlist() <= 0)
      return;
    final long live = header.liveEntries();
    final long perList = live / header.nlist();
    if (perList <= (long) LONG_LIST_FACTOR * ivfMetadata.targetPostingSize || live < 2 * longListsWarnedAt)
      return;
    longListsWarnedAt = live;
    LogManager.instance().log(this, Level.WARNING,
        "IVF index '%s': %d live entries in %d lists (%d per list, target %d), and no automatic training will re-size the partition; "
            + "searches scan proportionally more until a REBUILD INDEX", null, getName(), live, header.nlist(), perList,
        ivfMetadata.targetPostingSize);
  }

  /**
   * Trains the centroids once (pass 1 and k-means, no transaction written), then re-populates every list in ONE transaction of its
   * own, for the first training as for one that follows the growth of the corpus: the centroid, directory and posting pages of the new layout reach the WAL and the replicas together, and a reader sees
   * either the untrained list or the trained lists, never a mix. A writer that commits meanwhile conflicts with the re-population
   * on the header page (every append bumps its counters), so the re-population retries rather than publish a layout that misses
   * the writer's entry; a writer whose key was coded against the untrained centroid and commits after the training is coded again
   * at replay ({@link #applyAdd}).
   * <p>
   * Under a sustained stream of commits an optimistic re-population never wins, so after {@link #AUTO_TRAIN_ATTEMPTS} conflicts,
   * with a growing pause between them, the last attempt takes an explicit lock on the bucket and its indexes first: writers then wait
   * for it at their commit (the re-population of {@code autoTrainThreshold} entries), and it cannot lose. A training that still
   * fails waits until the list has doubled.
   */
  private void autoTrain() {
    final boolean contextCreated = DatabaseContext.INSTANCE.getContextIfExists(database.getDatabasePath()) == null;
    if (contextCreated)
      DatabaseContext.INSTANCE.init(database);
    try {
      autoTrainOnThisThread();
    } finally {
      autoTraining.set(false);
      if (contextCreated)
        DatabaseContext.INSTANCE.removeContext(database.getDatabasePath());
    }
  }

  private void autoTrainOnThisThread() {
    final int threshold = ivfMetadata.autoTrainThreshold;
    long live = 0;
    try {
      if (!isValid())
        return;
      final int[] previousLists = new int[1];
      final Training training = reading(() -> {
        final IVFPostingsComponent.Header header = postings().readHeader();
        if (!trainingDue(header))
          return null;
        previousLists[0] = header.trained() ? header.nlist() : 0;
        return train(true);
      });
      if (training == null)
        return;
      live = training.seen;
      final String bucketName = database.getSchema().getBucketById(getAssociatedBucketId()).getName();
      for (int attempt = 0; attempt <= AUTO_TRAIN_ATTEMPTS && isValid(); attempt++) {
        final boolean locked = attempt == AUTO_TRAIN_ATTEMPTS;
        final boolean[] trained = new boolean[1];
        // Through the wrapper, as database.transaction() does: under HA the commit is what ships the layout to the replicas
        final DatabaseInternal db = database.getWrappedDatabaseInstance();
        try {
          db.begin();
          try {
            if (locked)
              // Before any read: an explicit lock must be the transaction's first act
              db.getTransaction().lock().bucket(bucketName).lock();
            final IVFPostingsComponent.Header header = postings().readHeader();
            if (trainingDue(header))
              trained[0] = populateTrained(training, true, 0, null) >= 0;
            db.commit();
          } finally {
            if (db.isTransactionActive())
              db.rollback();
          }
        } catch (final NeedRetryException e) {
          LogManager.instance().log(this, Level.FINE, "IVF index '%s': automatic training conflicted with a writer (attempt %d)", null,
              getName(), attempt + 1);
          if (!locked)
            pauseBeforeRetry(attempt);
          continue;
        }
        if (trained[0]) {
          nextAutoTrainAt = 0;
          if (previousLists[0] > 0) {
            autoRetrainings.incrementAndGet();
            IVFVectorIndexMetrics.INSTANCE.autoRetrainings.increment();
            LogManager.instance().log(this, Level.INFO, "IVF index '%s': trained again at %d vectors, %d lists instead of %d", null,
                getName(), training.seen, training.centroids.nlist, previousLists[0]);
          } else {
            autoTrainings.incrementAndGet();
            IVFVectorIndexMetrics.INSTANCE.autoTrainings.increment();
          }
        }
        return;
      }
      nextAutoTrainAt = Math.max(threshold, live) * 2L;
      LogManager.instance().log(this, Level.INFO,
          "IVF index '%s': automatic training gave up after %d conflicts with concurrent writers; retried at %d vectors", null,
          getName(), AUTO_TRAIN_ATTEMPTS + 1, nextAutoTrainAt);
    } catch (final RuntimeException e) {
      nextAutoTrainAt = Math.max(threshold, live) * 2L;
      LogManager.instance().log(this, Level.WARNING, "IVF index '%s': automatic training failed, the index stays untrained", e, getName());
    }
  }

  private static void pauseBeforeRetry(final int attempt) {
    try {
      Thread.sleep(AUTO_TRAIN_BACKOFF_MS << attempt);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------- search

  /** {@link #findNeighbors(float[], int, int, int, int, Set)} with the index's default exact rerank. */
  public List<Pair<RID, Float>> findNeighbors(final float[] query, final int k, final int nprobe, final int rerankOversample,
      final Set<RID> allowedRIDs) {
    return findNeighbors(query, k, nprobe, rerankOversample, -1, allowedRIDs);
  }

  /**
   * The {@code k} nearest records to {@code query}, nearest first, each with its distance (see {@link IVFSimilarity}).
   *
   * @param nprobe           posting lists to scan, {@code <= 0} for the index default
   * @param rerankOversample candidates per answer the scan keeps for the rerank: {@code < 0} for the index default budget
   *                         ({@code max(k * rerankOversample, rerankCandidates)} of the metadata), 0 for none, n for exactly
   *                         {@code k * n}
   * @param exactRerank      best reranked candidates rescored on their stored vectors, {@code < 0} for the index default
   *                         ({@code exactRerank} of the metadata), 0 for none
   * @param allowedRIDs      records the answer may hold, or null for all; records of other buckets are ignored
   */
  public List<Pair<RID, Float>> findNeighbors(final float[] query, final int k, final int nprobe, final int rerankOversample,
      final int exactRerank, final Set<RID> allowedRIDs) {
    if (k <= 0)
      return new ArrayList<>(0);
    checkIsValid();
    searches.incrementAndGet();
    IVFVectorIndexMetrics.INSTANCE.searches.increment();

    final float[] vector = prepare(query);
    if (vector == null)
      throw new IndexException("IVF index '" + getName() + "': null query vector");
    final int bucketId = getAssociatedBucketId();

    // The allow-list as the positions of this bucket, probed per scanned entry without boxing: a sorted array when the exact path
    // answers it (only the pending entries ask), a blocked bitmap otherwise
    final PositionFilter allowed = allowedRIDs == null ? null :
        PositionFilter.of(allowedRIDs, bucketId, ivfMetadata.exactFilterThreshold);
    if (allowed != null && allowed.size == 0)
      return new ArrayList<>(0);

    // A search reads many pages - the header, the directory, posting pages linked by page number - and a commit publishes its pages
    // one at a time: a search overlapping a publication can follow a link into a page that is not published yet (past the committed
    // page count, so it reads as zeros), or read a header of a newer training than its centroids. The scan detects both and the
    // search is read again once the publication is over, waiting for it bounded by time. Any other overlap is a read of committed
    // pages at two adjacent states of one list - an entry missed or met twice, never a foreign one - which an approximate search
    // tolerates: the answer holds a record once
    final long deadline = System.nanoTime() + CONSISTENT_READ_TIMEOUT_NANOS;
    for (int attempt = 0; ; attempt++) {
      final List<Pair<RID, Float>> answer = search(vector, k, nprobe, rerankOversample, exactRerank, allowedRIDs, allowed, bucketId,
          centroids());
      if (answer != null)
        return answer;
      retriedSearches.incrementAndGet();
      releasePinnedPages();
      if (!pauseForPublication(attempt, deadline))
        throw new ConcurrentModificationException("IVF index '" + getName() + "': the posting lists kept changing under the search");
    }
  }

  /** A REPEATABLE_READ transaction drops the postings pages it pinned, so a search read again after a torn read sees one state. */
  private void releasePinnedPages() {
    final TransactionContext tx = database.getTransactionIfExists();
    if (tx != null && tx.getIsolationLevel() == Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ)
      tx.unpinFiles(List.of(postings().getFileId()));
  }

  /**
   * One read of {@link #findNeighbors}: null when it read pages of two states - a header of another centroid version than
   * {@code resident}, or a link into a page that is not a posting page of the list it walks.
   */
  private List<Pair<RID, Float>> search(final float[] vector, final int k, final int nprobe, final int rerankOversample,
      final int exactRerank, final Set<RID> allowedRIDs, final PositionFilter allowed, final int bucketId, final IVFCentroids resident) {
    final IVFPostingsComponent component = postings();
    final IVFPostingsComponent.Header header = component.readHeader();
    if (header != null && header.version() != resident.version)
      // A training committed between the centroids and the header: the list ids of the one do not index the directory of the other
      return null;
    final EntryLayout layout = layout();

    int probes = nprobe > 0 ? nprobe : ivfMetadata.nprobe;
    if (allowed != null && header != null && header.liveEntries() > 0) {
      // A selective filter leaves few matches per list: widen the probe in proportion, up to a bound
      final long factor = Math.min(MAX_FILTER_PROBE_FACTOR, Math.max(1, header.liveEntries() / allowed.size));
      probes = (int) Math.min((long) resident.nlist, probes * factor);
    }
    final int capacity = candidateBudget(k, rerankOversample, layout, allowed, probes, resident.nlist);
    final boolean rerank = layout.hasRerank && (rerankOversample > 0 || rerankOversample < 0 && ivfMetadata.rerankOversample > 0);

    // Pending writes of the calling transaction: they outrank the pages, which do not have them yet
    final Overlay overlay = overlay();

    // A filter of at most exactFilterThreshold records is answered from the records themselves: every allowed vector is scored,
    // as the graph index does under VECTOR_INDEX_PREFILTER_MAX_SELECTIVITY. An absolute count, because the cost is one record read
    // per allowed record whatever the corpus size (a ratio became 50K record reads at 5M)
    final boolean exact = allowed != null && header != null && allowed.size <= ivfMetadata.exactFilterThreshold;

    final ScanQuery scan = new ScanQuery(vector, layout, resident, allowed, overlay);
    CandidateHeap heap;
    boolean offThread = false;
    if (exact) {
      exactFilterSearches.incrementAndGet();
      IVFVectorIndexMetrics.INSTANCE.exactFilterSearches.increment();
      heap = new CandidateHeap(k);
      scoreAllowedRecords(allowedRIDs, bucketId, vector, overlay, heap);
    } else if (header != null && header.liveEntries() > 0) {
      final int[] lists = resident.select(vector, probes);
      scan.lists = lists;
      scan.pageLimit = component.getTotalPages();
      final int[] heads = new int[lists.length];
      for (int i = 0; i < lists.length; i++)
        heads[i] = component.headPage(header, lists[i]);
      // One task's worth of lists stays on the caller: a hand-off costs more than it saves there
      offThread = parallelScan && lists.length > LISTS_PER_TASK && canReadOffThread();
      heap = offThread ? scanParallel(scan, component, heads, capacity) : scanSerial(scan, component, heads, capacity);
      if (scan.torn)
        return null;
    } else
      heap = new CandidateHeap(0);

    // Rerank the survivors on the code sitting beside the scan code, on pages the scan has just read
    final int survivors = heap.size;
    final float[] distances = new float[survivors];
    final long[] positions = Arrays.copyOf(heap.positions, survivors);
    if (exact || !rerank && !layout.binaryScan)
      System.arraycopy(heap.distances, 0, distances, 0, survivors);
    else if (rerank) {
      rerankedCandidates.addAndGet(survivors);
      IVFVectorIndexMetrics.INSTANCE.rerankedCandidates.add(survivors);
      rerankAll(scan, component, heap, distances, offThread);
    } else
      for (int i = 0; i < survivors; i++)
        distances[i] = finalDistance(heap.distances[i], layout);

    // The exact tier: the best reranked candidates scored again on their stored vectors, which lifts the ceiling of the codes
    final int exactWanted = exactRerank >= 0 ? exactRerank : ivfMetadata.exactRerank;
    final int exactCount = exact || exactWanted <= 0 ? 0 : Math.min(survivors, Math.max(k, exactWanted));
    float[] tierDistances = distances;
    long[] tierPositions = positions;
    int total = survivors;
    if (exactCount > 0) {
      final int[] order = IVFCentroids.sortedIndexes(distances);
      tierDistances = new float[exactCount];
      tierPositions = new long[exactCount];
      total = 0;
      for (int i = 0; i < exactCount; i++) {
        final long position = positions[order[i]];
        final float[] stored = storedVector(RID.create(database, bucketId, position));
        if (stored == null)
          continue;
        tierDistances[total] = exactDistance(vector, stored);
        tierPositions[total++] = position;
      }
      exactReranks.addAndGet(exactCount);
      IVFVectorIndexMetrics.INSTANCE.exactReranks.add(exactCount);
    }

    // Merge the transaction's own pending entries, scored on their vectors (exact when a rerank answers distances, the proxy otherwise)
    float[] mergedDistances = tierDistances;
    long[] mergedPositions = tierPositions;
    if (overlay != null && !overlay.added.isEmpty()) {
      mergedDistances = Arrays.copyOf(tierDistances, total + overlay.added.size());
      mergedPositions = Arrays.copyOf(tierPositions, total + overlay.added.size());
      final long[] queryBits = scan.queryBits;
      for (final IVFReplayKey pending : overlay.added.values()) {
        final long position = pending.position();
        if (allowed != null && !allowed.contains(position))
          continue;
        // On the scale of the committed entries it is merged with: the proxy of its scan code when nothing re-scored them
        final float distance;
        if ((rerank || exactCount > 0 || !layout.binaryScan) && pending.vector() != null)
          distance = exactDistance(vector, pending.vector());
        else {
          final long[] words = new long[queryBits.length];
          final ByteBuffer wrapped = ByteBuffer.wrap(pending.entry());
          for (int w = 0; w < words.length; w++)
            words[w] = wrapped.getLong(w * 8);
          distance = finalDistance(IVFCodec.hamming(queryBits, words), layout);
        }
        mergedDistances[total] = distance;
        mergedPositions[total++] = position;
      }
    }

    // A record once, at its best distance: a scan that overlapped the publication of an update can meet its old entry and its new one
    final int[] order = IVFCentroids.sortedIndexes(Arrays.copyOf(mergedDistances, total));
    final int answer = Math.min(k, total);
    final List<Pair<RID, Float>> result = new ArrayList<>(answer);
    // The positions answered, position + 1 per slot (0 = empty), at most half full: O(k) whatever the survivors
    final long[] answered = new long[Integer.highestOneBit(Math.max(1, answer)) << 2];
    final int mask = answered.length - 1;
    for (int i = 0; i < total && result.size() < k; i++) {
      final long key = mergedPositions[order[i]] + 1;
      int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
      while (answered[slot] != 0 && answered[slot] != key)
        slot = (slot + 1) & mask;
      if (answered[slot] == key)
        continue;
      answered[slot] = key;
      result.add(new Pair<>(RID.create(database, bucketId, key - 1), mergedDistances[order[i]]));
    }
    return result;
  }

  /**
   * Candidates the scan keeps for the rerank. The default is an absolute budget, {@code max(k * rerankOversample, rerankCandidates)}:
   * what it has to cover is the near-duplicate density around a query, which grows with the corpus, not with {@code k}. A query that
   * names its oversample gets exactly {@code k * n}. Under an allow-list the budget also covers the allowed entries the widened
   * probe is expected to meet, so the answer within the filter is the rerank's precision and not the one-bit scan's.
   */
  private int candidateBudget(final int k, final int rerankOversample, final EntryLayout layout, final PositionFilter allowed,
      final int probes, final int nlist) {
    if (!layout.hasRerank)
      return k;
    long budget;
    if (rerankOversample >= 0)
      budget = (long) k * rerankOversample;
    else if (ivfMetadata.rerankOversample == 0)
      budget = k;
    else
      budget = Math.max((long) k * ivfMetadata.rerankOversample, ivfMetadata.rerankCandidates);
    if (allowed != null && budget > k)
      budget = Math.max(budget, Math.min(allowed.size, 2L * probes * allowed.size / Math.max(1, nlist) + k));
    return (int) Math.max(k, Math.min(VectorUtils.MAX_FETCH_CANDIDATES, budget));
  }

  /**
   * Whether the posting pages can be read off the calling thread: worker threads have no transaction, so they read the page cache's
   * committed pages, which is what the caller would read only under READ_COMMITTED and only while its transaction holds no page it
   * wrote itself (the postings are written at commit, and a training writes them in a transaction no search shares).
   */
  private boolean canReadOffThread() {
    final TransactionContext tx = database.getTransactionIfExists();
    return tx == null || tx.getIsolationLevel() == Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED && tx.getModifiedPages() == 0;
  }

  /** What every scan task of one query reads: the query codes and filters, immutable once built. */
  private final class ScanQuery {
    final float[]        vector;
    final EntryLayout    layout;
    final IVFCentroids   resident;
    final PositionFilter allowed;
    final Overlay        overlay;
    final long[]         queryBits;
    final byte[]         queryInt8;
    final float          queryScale;
    final float          querySquaredNorm;
    final IVFSimilarity  similarity = ivfMetadata.similarity;
    int[]                lists;
    /** Pages of the file when the scan started: a link at or past it names a page of a commit still being published. */
    int                  pageLimit;
    /** Set by a scan task that met a page of another state: the search is read again. */
    volatile boolean     torn;

    ScanQuery(final float[] vector, final EntryLayout layout, final IVFCentroids resident, final PositionFilter allowed,
        final Overlay overlay) {
      this.vector = vector;
      this.layout = layout;
      this.resident = resident;
      this.allowed = allowed;
      this.overlay = overlay;
      this.queryBits = layout.binaryScan ? new long[IVFCodec.binaryWords(layout.dimensions)] : null;
      if (queryBits != null)
        IVFCodec.binaryCode(vector, resident.projection, resident.thresholds, queryBits);
      final boolean int8Needed = !layout.binaryScan || layout.rerankKind == RERANK_INT8;
      this.queryInt8 = int8Needed ? new byte[layout.dimensions] : null;
      this.queryScale = int8Needed ? IVFCodec.int8Scale(vector) : 0f;
      if (queryInt8 != null)
        IVFCodec.int8Code(vector, queryScale, queryInt8);
      this.querySquaredNorm = IVFCodec.squaredNorm(vector);
    }
  }

  private CandidateHeap scanSerial(final ScanQuery scan, final IVFPostingsComponent component, final int[] heads, final int capacity) {
    final CandidateHeap heap = new CandidateHeap(capacity);
    final long scanned = scanLists(scan, component, heads, new AtomicInteger(), heap, false);
    scannedEntries.addAndGet(scanned);
    IVFVectorIndexMetrics.INSTANCE.scannedEntries.add(scanned);
    return heap;
  }

  /**
   * The posting lists of one query on the query pool (engine-concurrency skill: the pool of query-time parallelism, no pool of
   * its own): every task, the caller included, pulls the next list off a shared counter into its own primitive heap, and the
   * heaps are merged into one. The caller waits with the reclaiming {@link GraphAlgorithms#awaitFutures}, so a search running on
   * a pool thread itself never parks on a task nobody is left to run.
   */
  private CandidateHeap scanParallel(final ScanQuery scan, final IVFPostingsComponent component, final int[] heads, final int capacity) {
    final ExecutorService executor = QueryEngineManager.getInstance().getExecutorService();
    final int tasks = Math.max(1, Math.min(QueryEngineManager.getInstance().getMaxParallelism(),
        (heads.length + LISTS_PER_TASK - 1) / LISTS_PER_TASK));
    final AtomicInteger next = new AtomicInteger();
    final CandidateHeap[] heaps = new CandidateHeap[tasks];
    final long[] scanned = new long[tasks];
    final Future<?>[] futures = new Future<?>[tasks - 1];
    for (int t = 1; t < tasks; t++) {
      final int task = t;
      heaps[task] = new CandidateHeap(capacity);
      futures[t - 1] = executor.submit(() -> {
        scanned[task] = scanLists(scan, component, heads, next, heaps[task], true);
      });
    }
    heaps[0] = new CandidateHeap(capacity);
    scanned[0] = scanLists(scan, component, heads, next, heaps[0], true);
    GraphAlgorithms.awaitFutures(futures, tasks - 1);

    parallelSearches.incrementAndGet();
    IVFVectorIndexMetrics.INSTANCE.parallelSearches.increment();
    long total = 0;
    for (final long s : scanned)
      total += s;
    scannedEntries.addAndGet(total);
    IVFVectorIndexMetrics.INSTANCE.scannedEntries.add(total);

    // Merge into the largest heap, so the merge offers the fewest entries
    int largest = 0;
    for (int t = 1; t < tasks; t++)
      if (heaps[t].size > heaps[largest].size)
        largest = t;
    final CandidateHeap merged = heaps[largest];
    for (int t = 0; t < tasks; t++)
      if (t != largest) {
        final CandidateHeap other = heaps[t];
        for (int i = 0; i < other.size; i++)
          merged.offer(other.distances[i], other.positions[i], other.pages[i], other.offsets[i], other.lists[i]);
      }
    return merged;
  }

  /** Scans the lists {@code next} hands out until there are none left; answers the entries read. */
  private long scanLists(final ScanQuery scan, final IVFPostingsComponent component, final int[] heads, final AtomicInteger next,
      final CandidateHeap heap, final boolean shared) {
    final EntryLayout layout = scan.layout;
    final PositionFilter allowed = scan.allowed;
    final Overlay overlay = scan.overlay;
    final int entrySize = layout.entrySize;
    final int base = BasePage.PAGE_HEADER_SIZE;
    final int pageLimit = scan.pageLimit;
    final int maxCount = (component.contentSize() - IVFPostingsComponent.P_ENTRIES) / entrySize;
    long scanned = 0;
    for (int list; (list = next.getAndIncrement()) < heads.length && !scan.torn; ) {
      final int centroid = scan.lists[list];
      int pageNumber = heads[list];
      int walked = 0;
      while (pageNumber != IVFPostingsComponent.NO_PAGE) {
        // Page 0 is the header, never a posting page; a page at or past the file's end is a page of a commit still being
        // published, which reads as zeros (its next link 0, the header). Neither is followed: the search is read again
        if (pageNumber <= 0 || pageNumber >= pageLimit || ++walked > pageLimit) {
          scan.torn = true;
          break;
        }
        final BasePage page = shared ? component.readPageShared(pageNumber) : component.readPageForScan(pageNumber);
        final ByteBuffer buffer = page.getContent();
        final int count = buffer.getInt(base + IVFPostingsComponent.P_COUNT);
        if (buffer.getInt(base + IVFPostingsComponent.P_CENTROID) != centroid || count < 0 || count > maxCount) {
          scan.torn = true;
          break;
        }
        int offset = base + IVFPostingsComponent.P_ENTRIES;
        for (int i = 0; i < count; i++, offset += entrySize) {
          if (buffer.get(offset + IVFPostingsComponent.E_LIVE) == 0)
            continue;
          final long position = buffer.getLong(offset + IVFPostingsComponent.E_POSITION);
          if (allowed != null && !allowed.contains(position))
            continue;
          // A position the transaction removed, or wrote again (an update: the merge scores its pending entry), is not the page's
          if (overlay != null && overlay.masks(position))
            continue;
          final int codes = offset + IVFPostingsComponent.E_CODES;
          final float distance = layout.binaryScan ?
              IVFCodec.hamming(scan.queryBits, buffer, codes) :
              IVFCodec.int8Distance(scan.similarity, scan.queryInt8, scan.queryScale, scan.querySquaredNorm, buffer, codes);
          heap.offer(distance, position, pageNumber, offset, list);
        }
        scanned += count;
        pageNumber = buffer.getInt(base + IVFPostingsComponent.P_NEXT);
      }
    }
    return scanned;
  }

  /** Scores every survivor on its rerank code into {@code distances}, in chunks over the query pool when the scan ran there. */
  private void rerankAll(final ScanQuery scan, final IVFPostingsComponent component, final CandidateHeap heap, final float[] distances,
      final boolean offThread) {
    final int survivors = heap.size;
    final float[] listConstants;
    if (scan.layout.rerankKind == RERANK_INT4) {
      // The per-list term of the residual distance, once per probed list rather than once per candidate
      listConstants = new float[scan.lists.length];
      for (int i = 0; i < scan.lists.length; i++)
        listConstants[i] = IVFCodec.int4ListConstant(scan.similarity, scan.vector, scan.resident.fine,
            scan.lists[i] * scan.layout.dimensions);
    } else
      listConstants = null;

    if (!offThread || survivors < PARALLEL_RERANK_MIN) {
      rerankRange(scan, component, heap, distances, listConstants, 0, survivors, false);
      return;
    }
    final ExecutorService executor = QueryEngineManager.getInstance().getExecutorService();
    final int tasks = Math.min(QueryEngineManager.getInstance().getMaxParallelism(), survivors / (PARALLEL_RERANK_MIN / 2));
    final int chunk = (survivors + tasks - 1) / tasks;
    final Future<?>[] futures = new Future<?>[tasks - 1];
    int launched = 0;
    for (int t = 1; t < tasks; t++) {
      final int from = t * chunk;
      final int to = Math.min(from + chunk, survivors);
      if (from >= to)
        break;
      futures[launched++] = executor.submit(() -> rerankRange(scan, component, heap, distances, listConstants, from, to, true));
    }
    rerankRange(scan, component, heap, distances, listConstants, 0, Math.min(chunk, survivors), true);
    GraphAlgorithms.awaitFutures(futures, launched);
  }

  private void rerankRange(final ScanQuery scan, final IVFPostingsComponent component, final CandidateHeap heap, final float[] distances,
      final float[] listConstants, final int from, final int to, final boolean shared) {
    final int rerankOffset = IVFPostingsComponent.E_CODES + scan.layout.rerankOffset;
    final boolean int4 = scan.layout.rerankKind == RERANK_INT4;
    int lastPage = -1;
    ByteBuffer buffer = null;
    for (int i = from; i < to; i++) {
      final int pageNumber = heap.pages[i];
      if (pageNumber != lastPage) {
        buffer = (shared ? component.readPageShared(pageNumber) : component.readPageForScan(pageNumber)).getContent();
        lastPage = pageNumber;
      }
      distances[i] = int4 ?
          IVFCodec.int4Distance(scan.similarity, scan.vector, listConstants[heap.lists[i]], buffer, heap.offsets[i] + rerankOffset) :
          IVFCodec.int8Distance(scan.similarity, scan.queryInt8, scan.queryScale, scan.querySquaredNorm, buffer,
              heap.offsets[i] + rerankOffset);
    }
  }

  /** The stored vector of {@code rid}, prepared as the index prepares it; null when the record is gone or holds none. */
  private float[] storedVector(final RID rid) {
    try {
      final Document record = (Document) database.lookupByRID(rid, true);
      return prepare(record.get(getPropertyNames().getFirst()));
    } catch (final RecordNotFoundException | IndexException e) {
      return null;
    }
  }

  /**
   * The exact path of a selective filter: the stored vector of every allowed record of this bucket, scored against the query.
   * A record that is gone, or holds no vector of the index's width, is skipped; the calling transaction's own deletes are honoured.
   */
  private void scoreAllowedRecords(final Set<RID> allowedRIDs, final int bucketId, final float[] query, final Overlay overlay,
      final CandidateHeap heap) {
    for (final RID rid : allowedRIDs) {
      // A position with a pending entry is scored by the overlay merge, from the vector the transaction wrote
      if (rid.getBucketId() != bucketId || overlay != null && overlay.masks(rid.getPosition()))
        continue;
      final float[] vector = storedVector(rid);
      if (vector == null)
        continue;
      heap.offer(exactDistance(query, vector), rid.getPosition(), -1, -1, -1);
    }
  }

  private float exactDistance(final float[] query, final float[] vector) {
    switch (ivfMetadata.similarity) {
    case HAMMING: {
      int bits = 0;
      for (int d = 0; d < query.length; d++)
        if ((query[d] > 0.5f) != (vector[d] > 0.5f))
          bits++;
      return bits;
    }
    case EUCLIDEAN: {
      float sum = 0f;
      for (int d = 0; d < query.length; d++) {
        final float diff = query[d] - vector[d];
        sum += diff * diff;
      }
      return sum;
    }
    default: {
      float dot = 0f;
      for (int d = 0; d < query.length; d++)
        dot += query[d] * vector[d];
      return ivfMetadata.similarity == IVFSimilarity.COSINE ? 1f - dot : -dot;
    }
    }
  }

  /**
   * The positions of an allow-list inside one bucket, probed once per scanned entry. A record position is
   * {@code bucketPage * maxRecordsInPage + slot}, so positions are sparse when records are large (a 2 KB record leaves ~30 of a
   * page's 2,048 positions used: 300M such records reach position ~20G). The filter therefore costs what it holds, never how far it
   * reaches:
   * <ul>
   *   <li>at most {@code sortedUpTo} positions (an allow-list the exact path answers, which probes it only for the transaction's
   *   pending entries): a sorted array</li>
   *   <li>otherwise a blocked bitmap, 64-byte bit blocks of 512 positions each, only for the blocks that hold an allowed position,
   *   found through a block index: a dense int per block (two array reads per probe) while the positions average at most
   *   {@link #DENSE_BLOCKS_PER_POSITION} blocks apart, so the index never costs more than one bit block per position; past that, an
   *   open-addressing table of the allowed blocks alone</li>
   * </ul>
   * A dense index sized by the largest position cost 160 MB for one record at 20G, built before the exact path that never scans.
   */
  static final class PositionFilter {
    private static final int BLOCK_SHIFT               = 9;              // 512 positions per block
    private static final int BLOCK_WORDS               = 1 << (BLOCK_SHIFT - 6);
    /** Blocks of dense index per allowed position at most: 16 ints, the 64 bytes of the bit block a lone position takes anyway. */
    private static final int DENSE_BLOCKS_PER_POSITION = 16;

    final         int    size;
    private final int[]  blockOf;     // dense: block number -> 1 + index of its bit block, 0 = no allowed position in it; or null
    private final long[] blockKeys;   // sparse: block number + 1 per slot, 0 = empty; or null
    private final int[]  blockValues; // sparse: index of the bit block of the slot's block
    private final int    mask;
    private final long[] words;       // the bit blocks, BLOCK_WORDS longs each
    private final long[] sorted;      // sorted positions, or null

    private PositionFilter(final int size, final int[] blockOf, final long[] blockKeys, final int[] blockValues, final long[] words,
        final long[] sorted) {
      this.size = size;
      this.blockOf = blockOf;
      this.blockKeys = blockKeys;
      this.blockValues = blockValues;
      this.mask = blockKeys == null ? 0 : blockKeys.length - 1;
      this.words = words;
      this.sorted = sorted;
    }

    /**
     * @param sortedUpTo positions at or below which the filter is a sorted array: the exact-path threshold, where the filter is only
     *                   asked about the pending entries of the transaction
     */
    static PositionFilter of(final Set<RID> rids, final int bucketId, final int sortedUpTo) {
      long[] positions = new long[rids.size()];
      int n = 0;
      long max = -1;
      for (final RID rid : rids)
        if (rid.getBucketId() == bucketId) {
          final long position = rid.getPosition();
          positions[n++] = position;
          if (position > max)
            max = position;
        }
      if (n == 0)
        return new PositionFilter(0, null, null, null, null, null);
      if (sortedFilter || n <= sortedUpTo) {
        positions = n == positions.length ? positions : Arrays.copyOf(positions, n);
        Arrays.sort(positions);
        return new PositionFilter(n, null, null, null, null, positions);
      }

      final long denseBlocks = (max >>> BLOCK_SHIFT) + 1;
      if (denseBlocks <= (long) n * DENSE_BLOCKS_PER_POSITION && denseBlocks <= Integer.MAX_VALUE - 8) {
        final int[] blockOf = new int[(int) denseBlocks];
        int blocks = 0;
        for (int i = 0; i < n; i++) {
          final int block = (int) (positions[i] >>> BLOCK_SHIFT);
          if (blockOf[block] == 0)
            blockOf[block] = ++blocks;
        }
        final long[] words = new long[blocks * BLOCK_WORDS];
        for (int i = 0; i < n; i++)
          setBit(words, blockOf[(int) (positions[i] >>> BLOCK_SHIFT)] - 1, positions[i]);
        return new PositionFilter(n, blockOf, null, null, words, null);
      }

      // Sparse: a table of the allowed blocks, at most half full
      final int capacity = (int) Math.min(1L << 30, Long.highestOneBit(Math.max(2L, n) - 1) << 2);
      final int tableMask = capacity - 1;
      final long[] keys = new long[capacity];
      final int[] values = new int[capacity];
      final int[] bitBlockOf = new int[n];
      int blocks = 0;
      for (int i = 0; i < n; i++) {
        final long key = (positions[i] >>> BLOCK_SHIFT) + 1;
        int slot = slotOf(key, tableMask);
        while (keys[slot] != 0 && keys[slot] != key)
          slot = (slot + 1) & tableMask;
        if (keys[slot] == 0) {
          keys[slot] = key;
          values[slot] = blocks++;
        }
        bitBlockOf[i] = values[slot];
      }
      final long[] words = new long[blocks * BLOCK_WORDS];
      for (int i = 0; i < n; i++)
        setBit(words, bitBlockOf[i], positions[i]);
      return new PositionFilter(n, null, keys, values, words, null);
    }

    private static void setBit(final long[] words, final int bitBlock, final long position) {
      words[bitBlock * BLOCK_WORDS + (int) ((position >>> 6) & (BLOCK_WORDS - 1))] |= 1L << (position & 63);
    }

    private static int slotOf(final long key, final int tableMask) {
      final long hash = key * 0x9E3779B97F4A7C15L;
      return (int) (hash ^ (hash >>> 32)) & tableMask;
    }

    private boolean bit(final int bitBlock, final long position) {
      return (words[bitBlock * BLOCK_WORDS + (int) ((position >>> 6) & (BLOCK_WORDS - 1))] & (1L << (position & 63))) != 0;
    }

    boolean contains(final long position) {
      if (blockOf != null) {
        final long block = position >>> BLOCK_SHIFT;
        if (block >= blockOf.length)
          return false;
        final int index = blockOf[(int) block];
        return index != 0 && bit(index - 1, position);
      }
      if (blockKeys != null) {
        final long key = (position >>> BLOCK_SHIFT) + 1;
        for (int slot = slotOf(key, mask); ; slot = (slot + 1) & mask) {
          final long found = blockKeys[slot];
          if (found == key)
            return bit(blockValues[slot], position);
          if (found == 0)
            return false;
        }
      }
      return sorted != null && Arrays.binarySearch(sorted, position) >= 0;
    }

    /** Heap bytes of the filter's arrays, for the tests of its footprint. */
    long bytes() {
      return (blockOf == null ? 0 : 4L * blockOf.length) + (blockKeys == null ? 0 : 12L * blockKeys.length)
          + (words == null ? 0 : 8L * words.length) + (sorted == null ? 0 : 8L * sorted.length);
    }
  }

  /** The distance an entry is answered with when nothing reranks it. */
  private float finalDistance(final float scanDistance, final EntryLayout layout) {
    if (!layout.binaryScan || ivfMetadata.similarity == IVFSimilarity.HAMMING)
      return scanDistance;
    return IVFCodec.hammingProxy((int) scanDistance, layout.dimensions);
  }

  /**
   * A bounded max-heap on the distance, in primitive arrays: the worst candidate sits at the root and is evicted by any
   * better one once the heap is full. The arrays start small and double up to the bound, so a parallel scan does not allocate
   * the whole budget once per task.
   */
  private static final class CandidateHeap {
    private static final int INITIAL = 256;

    final int capacity;
    float[] distances;
    long[]  positions;
    int[]   pages;
    int[]   offsets;
    /** The index of the probed list in the query's list array (the INT4 rerank reads its per-list constant by it). */
    int[]   lists;
    int     size;

    CandidateHeap(final int capacity) {
      this.capacity = capacity;
      final int initial = Math.min(capacity, INITIAL);
      distances = new float[initial];
      positions = new long[initial];
      pages = new int[initial];
      offsets = new int[initial];
      lists = new int[initial];
    }

    void offer(final float distance, final long position, final int page, final int offset, final int list) {
      if (size < capacity) {
        if (size == distances.length)
          grow();
        int i = size++;
        distances[i] = distance;
        positions[i] = position;
        pages[i] = page;
        offsets[i] = offset;
        lists[i] = list;
        // sift up
        while (i > 0) {
          final int parent = (i - 1) >>> 1;
          if (distances[parent] >= distances[i])
            break;
          swap(i, parent);
          i = parent;
        }
        return;
      }
      if (capacity == 0 || distance >= distances[0])
        return;
      distances[0] = distance;
      positions[0] = position;
      pages[0] = page;
      offsets[0] = offset;
      lists[0] = list;
      // sift down
      int i = 0;
      while (true) {
        final int left = 2 * i + 1;
        if (left >= size)
          break;
        int child = left;
        if (left + 1 < size && distances[left + 1] > distances[left])
          child = left + 1;
        if (distances[child] <= distances[i])
          break;
        swap(i, child);
        i = child;
      }
    }

    private void grow() {
      final int length = (int) Math.min(capacity, Math.max(1L, distances.length * 2L));
      distances = Arrays.copyOf(distances, length);
      positions = Arrays.copyOf(positions, length);
      pages = Arrays.copyOf(pages, length);
      offsets = Arrays.copyOf(offsets, length);
      lists = Arrays.copyOf(lists, length);
    }

    private void swap(final int a, final int b) {
      final float d = distances[a];
      distances[a] = distances[b];
      distances[b] = d;
      final long p = positions[a];
      positions[a] = positions[b];
      positions[b] = p;
      final int pg = pages[a];
      pages[a] = pages[b];
      pages[b] = pg;
      final int o = offsets[a];
      offsets[a] = offsets[b];
      offsets[b] = o;
      final int l = lists[a];
      lists[a] = lists[b];
      lists[b] = l;
    }
  }

  /** The calling transaction's pending entries for this index: additions by position, and removed positions. */
  private static final class Overlay {
    final Map<Long, IVFReplayKey> added   = new HashMap<>();
    final Set<Long>               removed = new HashSet<>();

    /** Whether the committed entry of {@code position} is not the transaction's: it removed it, or wrote it again. */
    boolean masks(final long position) {
      return removed.contains(position) || added.containsKey(position);
    }
  }

  private Overlay overlay() {
    final TransactionContext tx = database.getTransactionIfExists();
    if (tx == null || tx.getStatus() != TransactionContext.STATUS.BEGUN)
      return null;
    final List<TransactionIndexContext.IndexKey> lane = tx.getIndexChanges().getUnorderedIndexKeys(getName());
    if (lane == null || lane.isEmpty())
      return null;
    final Overlay overlay = new Overlay();
    for (final TransactionIndexContext.IndexKey key : lane) {
      if (!(key.keyValues[0] instanceof IVFReplayKey replay))
        continue;
      if (key.operation == TransactionIndexContext.IndexKey.IndexKeyOperation.REMOVE) {
        overlay.added.remove(replay.position());
        overlay.removed.add(replay.position());
      } else {
        overlay.removed.remove(replay.position());
        overlay.added.put(replay.position(), replay);
      }
    }
    return overlay;
  }

  // ---------------------------------------------------------------- build

  /**
   * Two passes over the bucket: the first samples the vectors (reservoir) and trains the centroids, whose pages a short transaction
   * under an explicit lock writes; the second codes every vector against them - in batches fanned out over the query pool - and
   * stages the entries on the commit lane on the build's thread, committing every {@code buildIndexBatchSize} records: they are
   * appended under the commit lock like a live write, so the pages reach the WAL and the replication stream like one, and a writer
   * committing to the index meanwhile cannot fail a chunk (see {@link #populateTrained}).
   */
  @Override
  public long build(final int buildIndexBatchSize, final boolean sharesCallerTransaction, final BuildIndexCallback callback) {
    final List<String> propertyNames = getPropertyNames();
    if (propertyNames == null || propertyNames.isEmpty())
      throw new IndexException("Cannot build index '" + getName() + "' because metadata information are missing");
    checkBuildIsExclusive();
    if (!setStatus(new INDEX_STATUS[] { INDEX_STATUS.AVAILABLE }, INDEX_STATUS.UNAVAILABLE))
      throw new NeedRetryException("Error on building index '" + getName() + "' because not available");

    try {
      final long start = System.currentTimeMillis();
      LogManager.instance().log(this, Level.INFO, "Building IVF vector index '%s' on %s...", getName(), getTypeName() + propertyNames);
      final long total = trainAndPopulate(sharesCallerTransaction, buildIndexBatchSize, callback);
      LogManager.instance().log(this, Level.INFO, "Completed building IVF vector index '%s': processed %d records in %dms", getName(),
          Math.max(0, total), System.currentTimeMillis() - start);
      return Math.max(0, total);
    } finally {
      setStatus(new INDEX_STATUS[] { INDEX_STATUS.UNAVAILABLE }, INDEX_STATUS.AVAILABLE);
    }
  }

  /**
   * Refuses every build on a replicated database outside the schema session of the leader. The removal record that keeps a chunk
   * from replaying a stale entry ({@link #buildOverlap}) lives in this JVM and sees only the writes this node replays: a write
   * another node commits reaches this one as page bytes applied by the replication layer, with no replay. Under Raft that cannot
   * happen inside the session ({@code RaftReplicatedDatabase.recordFileChanges}, which every CREATE INDEX and REBUILD INDEX build
   * runs in, on the leader): it makes the leader exclusive on the database ({@code ArcadeStateMachine.beginLeaderExclusive}), so a
   * transaction of another node is refused, retryably, until the build has ended. The session opens a recording of file changes on
   * the build's thread, which is what is checked here. A direct {@code Index.build} outside it (embedded code on an HA server, or
   * any build on a follower) would let a follower's update or delete commit after the scan passed its record and be overwritten by
   * the build's older entry. That holds for a build in one transaction, or one sharing the caller's, as much as for a chunked one:
   * the scan reads the committed state while it runs, whatever commits it. So every such build is refused, not only a chunked one.
   * No shipped path reaches here outside a recording: CREATE INDEX, REBUILD INDEX, ALTER TYPE (bucket or super type), TRUNCATE
   * TYPE and CHECK DATABASE FIX all build inside {@code recordFileChanges}, and the automatic training does not call build.
   */
  private void checkBuildIsExclusive() {
    if (!database.getWrappedDatabaseInstance().isReplicated() || database.getFileManager().isRecordingChangesOnCurrentThread())
      return;
    throw new IndexException("IVF index '" + getName() + "': a build on a replicated database runs only inside CREATE INDEX or "
        + "REBUILD INDEX on the leader, where writes of other nodes wait for it; use REBUILD INDEX instead");
  }

  /** A centroid set trained from the bucket, not written anywhere yet: what {@link #populateTrained} lays out. */
  private record Training(IVFCentroids centroids, long seen) {
  }

  /**
   * Samples and trains (pass 1), writes the new layout with empty lists, and indexes every record into it (pass 2). Answers the
   * records processed by pass 2, or -1 when the bucket holds no vector to train on (nothing written then).
   *
   * @param commitEvery records per batch commit, 0 for none
   */
  private long trainAndPopulate(final boolean sharesCallerTransaction, final int commitEvery, final BuildIndexCallback callback) {
    final Training training = train(sharesCallerTransaction);
    return training == null ? -1 : populateTrained(training, sharesCallerTransaction, commitEvery, callback);
  }

  /**
   * Pass 1: samples the vectors of the bucket (reservoir) and trains the centroids on them; writes nothing. Answers null when the
   * bucket holds no vector. The centroids carry no version yet: {@link #populateTrained} gives them the one of its transaction.
   * <p>
   * The sample is at least {@code trainingSample}, and at least {@value #SAMPLE_PER_LIST} vectors per list wanted within an eighth
   * of the heap: k-means cannot make more lists than it has points, so a sample capped below the lists wanted made fewer, longer
   * lists than the latency model assumes (100K lists of 3,000 entries at 300M vectors instead of 1.5M lists of 200).
   */
  private Training train(final boolean sharesCallerTransaction) {
    final String bucketName = database.getSchema().getBucketById(getAssociatedBucketId()).getName();
    final String property = getPropertyNames().getFirst();

    final int sampleLimit = sampleLimit(database.countBucket(bucketName));
    final float[][] sample = new float[sampleLimit][];
    final long[] seen = new long[1];
    final int[] sampled = new int[1];
    final Random random = new Random(42);
    database.scanBucket(bucketName, record -> {
      final Document source = IndexInternal.buildSourceRecord(database, record, sharesCallerTransaction);
      final float[] vector = prepare(source.get(property));
      if (vector == null)
        return true;
      final long n = seen[0]++;
      if (n < sampleLimit)
        sample[sampled[0]++] = vector;
      else {
        final long slot = (long) (random.nextDouble() * (n + 1));
        if (slot < sampleLimit)
          sample[(int) slot] = vector;
      }
      return true;
    }, (rid, exception) -> {
      if (exception instanceof RuntimeException re)
        throw re;
      throw new IndexException("Error on building index '" + getName() + "' at record " + rid, exception);
    });
    if (seen[0] == 0)
      return null;

    final float[][] training = Arrays.copyOf(sample, sampled[0]);
    final int k = listsWanted(seen[0]);
    final long trainingStart = System.currentTimeMillis();
    final IVFCentroids trained = IVFKMeans.train(training, ivfMetadata.dimensions, k, ivfMetadata.kmeansIterations, 0,
        projectionSeed(ivfMetadata), random);
    final IVFCentroids effective = ivfMetadata.similarity == IVFSimilarity.HAMMING ? withHammingThresholds(trained) : trained;
    // A large partition is assigned and searched through a graph over its centroids, built before pass 2 assigns every vector
    effective.buildGraph();
    if (effective.nlist < k)
      LogManager.instance().log(this, Level.WARNING,
          "IVF index '%s': %d lists wanted but the training sample of %d vectors made %d; raise 'trainingSample' (heap permitting)", null,
          getName(), k, training.length, effective.nlist);
    LogManager.instance().log(this, Level.INFO, "IVF index '%s': trained %d centroids (%d coarse) on %d of %d vectors in %dms",
        getName(), effective.nlist, effective.coarseCount, training.length, seen[0], System.currentTimeMillis() - trainingStart);
    return new Training(effective, seen[0]);
  }

  /** The lists a training of {@code vectors} vectors makes: {@code nlist}, or one per {@code targetPostingSize} vectors. */
  private int listsWanted(final long vectors) {
    return ivfMetadata.nlist > 0 ? ivfMetadata.nlist :
        (int) Math.min(Integer.MAX_VALUE, Math.max(1, (vectors + ivfMetadata.targetPostingSize - 1) / ivfMetadata.targetPostingSize));
  }

  /** The reservoir size of pass 1 for a bucket of about {@code records} records, see {@link #train}. */
  private int sampleLimit(final long records) {
    final long rowBytes = ivfMetadata.dimensions * 4L + 16;
    final long heapRows = Math.max(1, Runtime.getRuntime().maxMemory() / 8 / rowBytes);
    final long perList = Math.min((long) listsWanted(records) * SAMPLE_PER_LIST, heapRows);
    return (int) Math.min(Integer.MAX_VALUE - 8, Math.max(ivfMetadata.trainingSample, Math.min(perList, records)));
  }

  /**
   * Writes the layout of {@code training} with empty lists, under the version that follows the committed one, and indexes every
   * record into it (pass 2). Answers the records processed.
   * <p>
   * A build that commits in chunks ({@code commitEvery > 0}, a transaction of its own) is one transaction per chunk, each open for
   * the time it takes to scan and code 5,000 records, while writers keep committing to the index (its registration came first): an
   * entry appended to a page inside a chunk, the header page above all, which every append rewrites, failed the chunk's commit on
   * the page version as soon as one writer committed in between, and the failure threw the whole build away. So the layout is
   * written in a short transaction whose first act is an explicit lock of the bucket and its indexes (writers wait for it at their
   * commit, and it cannot lose), and pass 2 stages its entries on the commit lane like a live write: {@link #putReplay} appends them
   * at the chunk's commit, under the commit lock, on the pages as they are then. The coding stays on the query pool.
   * <p>
   * Any other caller (the automatic training, a build in the caller's transaction) writes in the transaction it was given, entries
   * included: one transaction that conflicts or not as a whole, which its caller retries.
   */
  private long populateTrained(final Training training, final boolean sharesCallerTransaction, final int commitEvery,
      final BuildIndexCallback callback) {
    final String bucketName = database.getSchema().getBucketById(getAssociatedBucketId()).getName();
    final String property = getPropertyNames().getFirst();
    final boolean staged = stagedBuild && !sharesCallerTransaction && commitEvery > 0;
    // Through the wrapper, as the batch commits do: under HA the commit is what ships the pages to the replicas
    final DatabaseInternal db = database.getWrappedDatabaseInstance();
    if (staged) {
      // The transaction the build was handed holds at most the creation of the index (pass 1 only read): committed now, as the
      // first batch commit would have, so the explicit lock can be the next transaction's first act
      db.commit();
      db.begin();
      db.getTransaction().lock().bucket(bucketName).lock();
    }
    final IVFCentroids effective = writeTrainedLayout(training);
    if (!staged)
      // Pass 2: code in parallel batches, append on this thread
      return populate(bucketName, property, effective, sharesCallerTransaction, commitEvery, false, callback);

    // From the layout on, a writer can commit to a record between the scan that staged its entry and the commit of the chunk: its
    // removals are kept until the last chunk commits, so the replay can tell a staged entry the write made stale. Installed while
    // this transaction still holds its explicit lock: every writer that replayed before is published already (a writer holds its
    // locks from its replay to its publication), so the scan reads the record as that writer left it, and none replays until the
    // layout has committed, so a removal kept here was never one of the previous layout
    buildOverlap = new BuildOverlap();
    try {
      db.commit();
      db.begin();
      // Pass 2: code in parallel batches, stage on this thread
      final long total = populate(bucketName, property, effective, false, commitEvery, true, callback);
      // The last chunk is committed here, not by the caller, so it still sees the removals
      db.commit();
      db.begin();
      return total;
    } finally {
      buildOverlap = null;
    }
  }

  /**
   * Writes the layout of {@code training} in the current transaction, under the version that follows the committed one, and
   * publishes its centroids once that transaction commits. Answers the centroids, versioned.
   */
  private IVFCentroids writeTrainedLayout(final Training training) {
    // The version the committed header holds, plus one, read inside the training's transaction: a training that loses to a
    // concurrent one conflicts on the header page, so no two committed layouts share a version
    final IVFPostingsComponent.Header current = postings().readHeader();
    final IVFCentroids effective = training.centroids.withVersion((current == null ? 0 : current.version()) + 1);
    postings().writeLayout(effective, ivfMetadata.similarity, ivfMetadata.scanCode, ivfMetadata.rerankCode, true);
    // The baseline of the next automatic training, persisted so a reopen keeps the trigger
    postings().writeTrainedAt(training.seen);
    // Published for every caller only once the layout commits: a set published before could be one a conflict throws away, under
    // the version its retry reuses. Until then the training transaction's own writes find it attached to the transaction. Counted
    // there too: an attempt that lost to a writer, or a build that rolled back, trained nothing
    final TransactionContext tx = database.getTransaction();
    tx.setAttachment(trainingAttachmentKey(), effective);
    tx.addAfterCommitCallback(() -> {
      publishCentroids(effective);
      trainings.incrementAndGet();
      IVFVectorIndexMetrics.INSTANCE.trainings.increment();
    });
    return effective;
  }

  private long populate(final String bucketName, final String property, final IVFCentroids resident, final boolean sharesCallerTransaction,
      final int commitEvery, final boolean staged, final BuildIndexCallback callback) {
    final EntryLayout layout = layout();
    final RID[] rids = new RID[BUILD_CODING_BATCH];
    final float[][] vectors = new float[BUILD_CODING_BATCH][];
    final Document[] sources = new Document[BUILD_CODING_BATCH];
    final int[] pending = new int[1];
    final long[] total = new long[1];
    database.scanBucket(bucketName, record -> {
      final Document source = IndexInternal.buildSourceRecord(database, record, sharesCallerTransaction);
      final float[] vector = prepare(source.get(property));
      if (vector == null) {
        recordIndexed(source, ++total[0], commitEvery, sharesCallerTransaction, callback);
        return true;
      }
      rids[pending[0]] = record.getIdentity();
      vectors[pending[0]] = vector;
      sources[pending[0]++] = source;
      if (pending[0] == BUILD_CODING_BATCH) {
        appendBatch(rids, vectors, sources, pending[0], resident, layout, total, commitEvery, sharesCallerTransaction, staged, callback);
        pending[0] = 0;
      }
      return true;
    }, (rid, exception) -> {
      if (exception instanceof RuntimeException re)
        throw re;
      throw new IndexException("Error on building index '" + getName() + "' at record " + rid, exception);
    });
    if (pending[0] > 0)
      appendBatch(rids, vectors, sources, pending[0], resident, layout, total, commitEvery, sharesCallerTransaction, staged, callback);
    return total[0];
  }

  /**
   * Assigns and codes {@code count} vectors on the query pool, then, in scan order on this thread, stages their entries on the
   * transaction's commit lane ({@code staged}) or appends them to the pages.
   */
  private void appendBatch(final RID[] rids, final float[][] vectors, final Document[] sources, final int count,
      final IVFCentroids resident, final EntryLayout layout, final long[] total, final int commitEvery,
      final boolean sharesCallerTransaction, final boolean staged, final BuildIndexCallback callback) {
    final int[] lists = new int[count];
    final byte[][] payloads = new byte[count][];
    forRange(count, (from, to) -> {
      for (int i = from; i < to; i++) {
        lists[i] = resident.assign(vectors[i]);
        payloads[i] = encode(vectors[i], lists[i], resident, layout);
      }
    });
    final IVFPostingsComponent component = postings();
    final IVFPostingsComponent.Header header = staged ? null : component.readHeader();
    for (int i = 0; i < count; i++) {
      final RID rid = rids[i];
      if (staged)
        // The transaction of the current chunk, asked per entry: a batch spans the chunk commits recordIndexed makes
        database.getTransaction().addIndexOperation(this, TransactionIndexContext.IndexKey.IndexKeyOperation.ADD,
            new Object[] { new IVFReplayKey(lists[i], resident.version, rid.getPosition(), payloads[i], vectors[i], true) }, rid);
      else
        component.append(header, lists[i], rid.getPosition(), payloads[i], layout.entrySize);
      rids[i] = null;
      vectors[i] = null;
      payloads[i] = null;
      recordIndexed(sources[i], ++total[0], commitEvery, sharesCallerTransaction, callback);
      sources[i] = null;
    }
    IVFVectorIndexMetrics.INSTANCE.builtVectors.add(count);
  }

  private void recordIndexed(final Document source, final long indexed, final int commitEvery, final boolean sharesCallerTransaction,
      final BuildIndexCallback callback) {
    if (commitEvery > 0)
      IndexInternal.commitBuildBatch(database, indexed, commitEvery, sharesCallerTransaction);
    if (callback != null)
      callback.onDocumentIndexed(source, indexed);
  }

  private interface RangeTask {
    void run(int from, int to);
  }

  /** {@code task} over {@code [0, n)} in chunks on the query pool, the caller running the first; serial below 2 chunks' worth. */
  private static void forRange(final int n, final RangeTask task) {
    final int parallelism = parallelBuild ? QueryEngineManager.getInstance().getMaxParallelism() : 1;
    if (parallelism < 2 || n < 256) {
      task.run(0, n);
      return;
    }
    final int chunk = (n + parallelism - 1) / parallelism;
    final ExecutorService executor = QueryEngineManager.getInstance().getExecutorService();
    final Future<?>[] futures = new Future<?>[parallelism - 1];
    int launched = 0;
    for (int t = 1; t < parallelism; t++) {
      final int from = t * chunk;
      final int to = Math.min(from + chunk, n);
      if (from >= to)
        break;
      futures[launched++] = executor.submit(() -> task.run(from, to));
    }
    task.run(0, Math.min(chunk, n));
    GraphAlgorithms.awaitFutures(futures, launched);
  }

  /** HAMMING vectors are bits already: the threshold is the midpoint of 0 and 1, whatever the sample's means. */
  private static IVFCentroids withHammingThresholds(final IVFCentroids trained) {
    final float[] thresholds = new float[trained.dimensions];
    Arrays.fill(thresholds, 0.5f);
    return trained.withCodes(thresholds, 0);
  }

  // ---------------------------------------------------------------- IndexInternal plumbing

  @Override
  public Schema.INDEX_TYPE getType() {
    return Schema.INDEX_TYPE.LSM_IVF_VECTOR;
  }

  @Override
  public IndexCursor get(final Object[] keys) {
    return get(keys, -1);
  }

  @Override
  public IndexCursor get(final Object[] keys, final int limit) {
    throw new UnsupportedOperationException(
        "Direct lookup is not supported on LSM_IVF_VECTOR indexes; use vector.neighbors(...) for top-K retrieval");
  }

  @Override
  public long countEntries() {
    checkIsValid();
    return reading(() -> {
      final IVFPostingsComponent.Header header = postings().readHeader();
      return header == null ? 0L : header.liveEntries();
    });
  }

  @Override
  public boolean compact() {
    return false;
  }

  @Override
  public IndexMetadata getMetadata() {
    return shell.getMetadata();
  }

  @Override
  public IndexMetadata getMetadataForNewFile() {
    return ivfMetadata != null ? ivfMetadata : shell.getMetadata();
  }

  @Override
  public void setMetadata(final IndexMetadata metadata) {
    shell.setMetadata(metadata);
  }

  @Override
  public void setMetadata(final JSONObject indexJSON) {
    shell.setMetadata(indexJSON);
    final LSMIVFVectorIndexMetadata m = new LSMIVFVectorIndexMetadata(shell.getMetadata().typeName,
        shell.getPropertyNames() != null ? shell.getPropertyNames().toArray(new String[0]) : new String[0],
        shell.getMetadata().associatedBucketId);
    m.fromJSON(indexJSON);
    this.ivfMetadata = m;
    this.entryLayout = null;
  }

  @Override
  public boolean setStatus(final INDEX_STATUS[] expectedStatuses, final INDEX_STATUS newStatus) {
    return shell.setStatus(expectedStatuses, newStatus);
  }

  @Override
  public boolean isCompacting() {
    return false;
  }

  @Override
  public boolean scheduleCompaction() {
    return false;
  }

  @Override
  public String getMostRecentFileName() {
    return shell.getMostRecentFileName();
  }

  @Override
  public IndexInternal getAssociatedIndex() {
    return null;
  }

  @Override
  public String getTypeName() {
    return shell.getTypeName();
  }

  @Override
  public List<String> getPropertyNames() {
    return shell.getPropertyNames();
  }

  @Override
  public void close() {
    final IVFPostingsComponent component = postings;
    if (component != null)
      component.close();
    shell.close();
  }

  @Override
  public void drop() {
    try {
      final IVFPostingsComponent component = postings();
      database.getFileManager().dropFile(component.getFileId());
    } catch (final IOException e) {
      throw new IndexException("Failed to drop the postings file of IVF index '" + getName() + "'", e);
    } catch (final IndexException e) {
      // The postings file is already gone (a creation that failed before registering it)
    }
    shell.drop();
  }

  @Override
  public String getName() {
    return shell.getName();
  }

  @Override
  public Map<String, Long> getStats() {
    final Map<String, Long> stats = new HashMap<>();
    final IVFCentroids resident = centroids;
    stats.put("centroids", resident == null ? 0L : (long) resident.nlist);
    stats.put("trained", resident != null && resident.trained ? 1L : 0L);
    stats.put("centroidGraph", resident != null && resident.hasGraph() ? 1L : 0L);
    stats.put("residentBytes", getResidentBytes());
    stats.put("searches", searches.get());
    stats.put("scannedEntries", scannedEntries.get());
    stats.put("rerankedCandidates", rerankedCandidates.get());
    stats.put("parallelSearches", parallelSearches.get());
    stats.put("exactFilterSearches", exactFilterSearches.get());
    stats.put("exactReranks", exactReranks.get());
    stats.put("locatorDeletes", locatorDeletes.get());
    stats.put("walkedDeletes", walkedDeletes.get());
    stats.put("trainings", trainings.get());
    stats.put("autoTrainings", autoTrainings.get());
    stats.put("autoRetrainings", autoRetrainings.get());
    stats.put("replayRecodes", replayRecodes.get());
    stats.put("retriedSearches", retriedSearches.get());
    stats.put("skippedBuildEntries", skippedBuildEntries.get());
    IVFPostingsComponent component = postings;
    if (component == null && isValid())
      try {
        component = postings();
      } catch (final IndexException e) {
        // Not registered (yet): no file statistics
      }
    if (component != null) {
      stats.put("postingPages", (long) component.getTotalPages());
      if (isValid())
        try {
          final IVFPostingsComponent.Header header = reading(component::readHeader);
          if (header != null) {
            stats.put("liveEntries", header.liveEntries());
            stats.put("trainedAtEntries", header.trainedAt());
          }
        } catch (final RuntimeException e) {
          // Statistics are best effort: a header that cannot be read now leaves its two entries out
        }
    }
    return stats;
  }

  @Override
  public LSMTreeIndexAbstract.NULL_STRATEGY getNullStrategy() {
    return shell.getNullStrategy();
  }

  @Override
  public void setNullStrategy(final LSMTreeIndexAbstract.NULL_STRATEGY nullStrategy) {
    shell.setNullStrategy(nullStrategy);
  }

  @Override
  public int getFileId() {
    return shell.getFileId();
  }

  @Override
  public boolean isUnique() {
    return false;
  }

  /** Entries ride the append-only lane: never unique, and the in-transaction read path reads that lane back (see {@link #overlay()}). */
  @Override
  public boolean isTransactionKeyOrderRequired() {
    return false;
  }

  @Override
  public PaginatedComponent getComponent() {
    return shell.getComponent();
  }

  @Override
  public Type[] getKeyTypes() {
    return shell.getKeyTypes();
  }

  @Override
  public byte[] getBinaryKeyTypes() {
    return shell.getBinaryKeyTypes();
  }

  @Override
  public int getAssociatedBucketId() {
    return shell.getAssociatedBucketId();
  }

  @Override
  public boolean supportsOrderedIterations() {
    return false;
  }

  @Override
  public boolean isAutomatic() {
    return shell.isAutomatic();
  }

  @Override
  public int getPageSize() {
    return shell.getPageSize();
  }

  /** The shell's files and the postings file: every page a commit writes is under one of them (see the engine CLAUDE.md on locks). */
  @Override
  public List<Integer> getFileIds() {
    final List<Integer> ids = new ArrayList<>(shell.getFileIds());
    final IVFPostingsComponent component = postings;
    if (component != null)
      ids.add(component.getFileId());
    else {
      final Object file = ((LocalSchema) database.getSchema().getEmbedded()).getFileByName(shell.getName() + COMPONENT_SUFFIX);
      if (file instanceof IVFPostingsComponent found) {
        postings = found;
        ids.add(found.getFileId());
      }
    }
    return ids;
  }

  @Override
  public void setTypeIndex(final TypeIndex typeIndex) {
    this.typeIndex = typeIndex;
  }

  @Override
  public TypeIndex getTypeIndex() {
    return typeIndex;
  }

  @Override
  public void updateTypeName(final String newTypeName) {
    shell.updateTypeName(newTypeName);
  }

  @Override
  public boolean isValid() {
    return shell.isValid();
  }

  private void checkIsValid() {
    if (!isValid())
      throw new IndexException("Index '" + getName() + "' is not valid. Probably has been drop or rebuilt");
  }

  @Override
  public JSONObject toJSON() {
    final JSONObject json = new JSONObject();
    json.put("type", getType());
    json.put("bucket", database.getSchema().getBucketById(getAssociatedBucketId()).getName());
    json.put("properties", getPropertyNames());
    json.put("nullStrategy", getNullStrategy());
    json.put("unique", isUnique());
    if (ivfMetadata != null)
      ivfMetadata.toJSON(json);
    return json;
  }

  public LSMTreeIndex getUnderlyingIndex() {
    return shell;
  }
}
