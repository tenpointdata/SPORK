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

import com.arcadedb.serializer.BinaryComparator;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The block-max sidecar of a BM25 full-text bucket index: for every term, its postings sorted by RID in blocks of
 * {@link #BLOCK_SIZE}, each block described by a header (last RID, largest tf, smallest document length) the top-k scorer reads
 * to bound the BM25 contribution of every posting of the block without decoding it, and to jump over it.
 * <p>
 * The sidecar is a SNAPSHOT of the compacted part of the LSM-Tree: the series whose root page lies below {@link #getSeriesEnd()}
 * of the compacted file {@link #getCompactedFileId()}, resolved among themselves. Everything written after it (the mutable pages
 * and the series appended since) is read from the LSM-Tree at query time and merged in, so the sidecar stays exact across later
 * compactions; a full compaction, which writes a new compacted file, makes it unusable until it is rebuilt. It is derived data:
 * missing, truncated or stale, the scorer falls back to reading the postings, never to a wrong answer.
 * <p>
 * Layout (all integers big-endian, varints unsigned LEB128):
 * <pre>
 * header (64 bytes)  magic, version, block size, compacted file id, series end, series fingerprint (the root page and the
 *                    fingerprint of the series that ends at the mark, see LSMTreeIndexCompacted.getSeriesFingerprint), term
 *                    count, dictionary offset, sparse index offset, sparse entries, complete flag, smallest document
 *                    frequency kept (a term with fewer postings is not in the file and is read from the LSM-Tree)
 * per term           blocks of postings: (RID delta, tf, document length) varints; then each block's impacts, the
 *                    (tf, document length) pairs of its postings no other posting of the block beats on both (count, then
 *                    the pairs, varints); then the term's block headers, {@link #HEADER_BYTES} each: last RID, payload
 *                    offset, payload length, count, max tf, min document length, impacts offset, impacts length
 * <p>
 * The impacts are what makes a block's bound tight: BM25 grows with tf and shrinks with length, so the best posting of a block
 * is one of its impacts, and the bound is the best impact scored with the query's statistics. The largest tf with the smallest
 * length, taken from two different postings, would bound every block of a frequent term far above any score it holds.
 * dictionary         per term in key order: key (varint length + UTF-8), document frequency, block count, headers offset (8
 *                    bytes), max tf, min document length
 * sparse index       every {@link #SPARSE_STEP}-th dictionary entry: key and its offset, loaded in memory on open
 * </pre>
 * A RID is one long ordered as {@link com.arcadedb.database.RID#compareTo}: bucket id above bit 47, offset below.
 * <p>
 * Files are never overwritten: each build writes a new GENERATION, {@code <index name>.<generation>.bmx}, from temporary files
 * of its own, and the generation it replaces is deleted once the last query reading it has released it. An open file is
 * therefore never moved over or deleted under a reader, which some platforms refuse.
 */
final class BlockMaxPostings implements Closeable {
  static final int    MAGIC        = 0x41424D58; // "ABMX"
  static final int    VERSION      = 2;
  static final int    BLOCK_SIZE   = 128;
  static final int    HEADER_BYTES = 48;
  static final int    SPARSE_STEP  = 64;
  static final String EXTENSION    = ".bmx";
  /**
   * A term with fewer postings than this in the bucket is left out: it has nothing to skip, and a header, impacts and a
   * dictionary entry for each of the many rare terms of a vocabulary would make the sidecar larger than the index. A query reads
   * such a term from the LSM-Tree.
   */
  static final int    MIN_DOCUMENT_FREQUENCY = BLOCK_SIZE;

  private static final int FILE_HEADER_BYTES = 64;

  private final File          file;
  // Reopened when a reader's interrupt closed it (FileChannel closes itself for every reader then), guarded by channelLock
  private volatile FileChannel channel;
  private final Object        channelLock = new Object();
  // Set once the last reference is released: the channel is not reopened after that. Guarded by channelLock
  private boolean             channelReleased;
  private final int           compactedFileId;
  private final int           seriesEnd;
  private final long          seriesFingerprint;
  private final long          termCount;
  private final int           minDocumentFrequency;
  private final long          dictionaryEnd;
  private final String[]      sparseKeys;
  private final long[]        sparseOffsets;
  // One owner reference plus one per reader: the channel closes when the last of them is released
  private final AtomicInteger references = new AtomicInteger(1);
  // Set when a newer generation replaces this one or it is found unusable: the file is deleted once the channel closes
  private volatile boolean    obsolete;

  private BlockMaxPostings(final File file, final FileChannel channel, final int compactedFileId, final int seriesEnd,
      final long seriesFingerprint, final long termCount, final int minDocumentFrequency, final long dictionaryEnd,
      final String[] sparseKeys, final long[] sparseOffsets) {
    this.file = file;
    this.channel = channel;
    this.compactedFileId = compactedFileId;
    this.seriesEnd = seriesEnd;
    this.seriesFingerprint = seriesFingerprint;
    this.termCount = termCount;
    this.minDocumentFrequency = minDocumentFrequency;
    this.dictionaryEnd = dictionaryEnd;
    this.sparseKeys = sparseKeys;
    this.sparseOffsets = sparseOffsets;
  }

  /**
   * The sidecar's own content cannot be read: the file was truncated, damaged or became unreadable after it was opened. The one
   * failure that retires a sidecar. Everything else a read can meet - the reading thread's own interrupt
   * ({@link ClosedByInterruptException}), a channel another reader's interrupt closed - is not the file's fault and leaves it in
   * place.
   */
  static final class SidecarReadException extends IOException {
    SidecarReadException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  /** The postings of one term: its block headers in memory, the payload read block by block. */
  static final class Term {
    final BlockMaxPostings postings;
    final long             documentFrequency;
    final int              maxTf;
    final int              minDocLength;
    final long[]           lastRids;
    final long[]           payloadOffsets;
    final int[]            payloadLengths;
    final int[]            counts;
    final int[]            blockMaxTf;
    final int[]            blockMinDocLength;
    /** Block {@code i}'s impacts are {@code impactTfs/impactDocLengths[impactStarts[i] .. impactStarts[i + 1])}. */
    int[]                  impactStarts;
    int[]                  impactTfs;
    int[]                  impactDocLengths;

    private Term(final BlockMaxPostings postings, final long documentFrequency, final int maxTf, final int minDocLength,
        final int blocks) {
      this.postings = postings;
      this.documentFrequency = documentFrequency;
      this.maxTf = maxTf;
      this.minDocLength = minDocLength;
      this.lastRids = new long[blocks];
      this.payloadOffsets = new long[blocks];
      this.payloadLengths = new int[blocks];
      this.counts = new int[blocks];
      this.blockMaxTf = new int[blocks];
      this.blockMinDocLength = new int[blocks];
    }

    int blocks() {
      return lastRids.length;
    }

    /**
     * Decodes block {@code block} into the arrays, which must hold {@link #BLOCK_SIZE} entries, and answers its count. {@code buffer}
     * is scratch space reused across calls.
     */
    int decode(final int block, final long[] rids, final int[] tfs, final int[] docLengths, final byte[][] buffer) throws IOException {
      try {
        return decodeBlock(block, rids, tfs, docLengths, buffer);
      } catch (final RuntimeException e) {
        // Bytes that do not decode (an index out of the arrays, a negative length): damage
        throw new SidecarReadException("Damaged block " + block + " in block-max file " + postings.file, e);
      }
    }

    private int decodeBlock(final int block, final long[] rids, final int[] tfs, final int[] docLengths, final byte[][] buffer)
        throws IOException {
      final int length = payloadLengths[block];
      if (buffer[0] == null || buffer[0].length < length)
        buffer[0] = new byte[Math.max(length, 1024)];
      final byte[] bytes = buffer[0];
      postings.readFully(payloadOffsets[block], bytes, length);

      long rid = block > 0 ? lastRids[block - 1] : 0L;
      int position = 0;
      final int count = counts[block];
      for (int i = 0; i < count; i++) {
        long value = 0;
        int shift = 0;
        byte b;
        do {
          b = bytes[position++];
          value |= (long) (b & 0x7F) << shift;
          shift += 7;
        } while (b < 0);
        rid += value;
        rids[i] = rid;

        int tf = 0;
        shift = 0;
        do {
          b = bytes[position++];
          tf |= (b & 0x7F) << shift;
          shift += 7;
        } while (b < 0);
        tfs[i] = tf;

        int docLength = 0;
        shift = 0;
        do {
          b = bytes[position++];
          docLength |= (b & 0x7F) << shift;
          shift += 7;
        } while (b < 0);
        docLengths[i] = docLength;
      }
      // A block decodes to exactly its bytes and ends on the RID its header names: anything else is a damaged file, which the
      // caller answers by reading the postings instead
      if (position != length || rid != lastRids[block])
        throw new SidecarReadException("Damaged block " + block + " in block-max file " + postings.file, null);
      return count;
    }
  }

  int getCompactedFileId() {
    return compactedFileId;
  }

  int getSeriesEnd() {
    return seriesEnd;
  }

  /** The identity of the series that ends at the mark, as {@code LSMTreeIndexCompacted.getSeriesFingerprint} answered it. */
  long getSeriesFingerprint() {
    return seriesFingerprint;
  }

  long getTermCount() {
    return termCount;
  }

  /** Terms with fewer postings than this are not in the file: absent, a term may still have postings in the LSM-Tree. */
  int getMinDocumentFrequency() {
    return minDocumentFrequency;
  }

  File getFile() {
    return file;
  }

  /** True while the file channel is open: some reference (the owner's or a reader's) is still held. For the tests. */
  boolean isOpen() {
    return channel.isOpen();
  }

  /** Takes a reader reference, or answers false when the sidecar is already closed. */
  boolean acquire() {
    while (true) {
      final int current = references.get();
      if (current <= 0)
        return false;
      if (references.compareAndSet(current, current + 1))
        return true;
    }
  }

  void release() {
    if (references.decrementAndGet() == 0) {
      synchronized (channelLock) {
        channelReleased = true;
        try {
          channel.close();
        } catch (final IOException e) {
          // NOTHING LEFT TO DO WITH IT
        }
      }
      if (obsolete)
        deleteQuietly(file);
    }
  }

  /** Releases the owner reference: the channel closes once the readers still using it are done. The file stays. */
  @Override
  public void close() {
    release();
  }

  /** Releases the owner reference of a generation that is no longer wanted: its file is deleted once the readers are done. */
  void retire() {
    obsolete = true;
    release();
  }

  static void deleteQuietly(final File file) {
    try {
      Files.deleteIfExists(file.toPath());
    } catch (final IOException e) {
      // A platform that refuses to delete a file still open elsewhere: the next open of the index deletes it
    }
  }

  /**
   * The generation of {@code fileName} when it is a sidecar of the index {@code indexName} ({@code <indexName>.<generation>.bmx}),
   * otherwise -1.
   */
  static long generationOf(final String indexName, final String fileName) {
    if (!fileName.startsWith(indexName + ".") || !fileName.endsWith(EXTENSION))
      return -1L;
    final String middle = fileName.substring(indexName.length() + 1, fileName.length() - EXTENSION.length());
    if (middle.isEmpty() || middle.length() > 18)
      return -1L;
    for (int i = 0; i < middle.length(); i++)
      if (middle.charAt(i) < '0' || middle.charAt(i) > '9')
        return -1L;
    return Long.parseLong(middle);
  }

  /** True for a file a build of {@code indexName} left behind: a temporary file, or a sidecar in the naming before generations. */
  static boolean isLeftoverOf(final String indexName, final String fileName) {
    if (fileName.equals(indexName + EXTENSION) || fileName.startsWith(indexName + EXTENSION + "."))
      return true;
    // <indexName>.<generation>.bmx.<random>.tmp
    return fileName.endsWith(".tmp") && Pattern.matches(Pattern.quote(indexName) + "\\.\\d{1,18}\\.bmx\\..+", fileName);
  }

  /**
   * True for a file of the sidecar directory in one of the shapes a sidecar takes: a generation, a temporary file of a build, the
   * naming before generations. Whose it is, {@link #generationOf} and {@link #isLeftoverOf} answer.
   */
  static boolean isSidecarFileName(final String fileName) {
    return fileName.endsWith(EXTENSION) || fileName.contains(EXTENSION + ".");
  }

  static File generationFile(final File directory, final String indexName, final long generation) {
    return new File(directory, indexName + "." + generation + EXTENSION);
  }

  /**
   * The postings of {@code key}, or null when the snapshot holds none.
   */
  Term term(final String key) throws IOException {
    try {
      return findTerm(key);
    } catch (final RuntimeException e) {
      // A dictionary entry or block headers that do not decode: damage
      throw new SidecarReadException("Damaged dictionary in block-max file " + file, e);
    }
  }

  private Term findTerm(final String key) throws IOException {
    if (sparseKeys.length == 0)
      return null;
    int low = 0;
    int high = sparseKeys.length - 1;
    int chunk = -1;
    while (low <= high) {
      final int mid = (low + high) >>> 1;
      if (BinaryComparator.compareStrings(sparseKeys[mid], key) <= 0) {
        chunk = mid;
        low = mid + 1;
      } else
        high = mid - 1;
    }
    if (chunk < 0)
      return null;

    final long start = sparseOffsets[chunk];
    final long end = chunk + 1 < sparseOffsets.length ? sparseOffsets[chunk + 1] : dictionaryEnd;
    final byte[] bytes = new byte[(int) (end - start)];
    readFully(start, bytes, bytes.length);

    final int[] position = { 0 };
    while (position[0] < bytes.length) {
      final int keyLength = (int) readVarLong(bytes, position);
      final String entryKey = new String(bytes, position[0], keyLength, StandardCharsets.UTF_8);
      position[0] += keyLength;
      final long documentFrequency = readVarLong(bytes, position);
      final int blocks = (int) readVarLong(bytes, position);
      final long headersOffset = ByteBuffer.wrap(bytes, position[0], 8).getLong();
      position[0] += 8;
      final int maxTf = (int) readVarLong(bytes, position);
      final int minDocLength = (int) readVarLong(bytes, position);

      final int cmp = BinaryComparator.compareStrings(entryKey, key);
      if (cmp == 0)
        return loadTerm(documentFrequency, maxTf, minDocLength, blocks, headersOffset);
      if (cmp > 0)
        return null;
    }
    return null;
  }

  private Term loadTerm(final long documentFrequency, final int maxTf, final int minDocLength, final int blocks,
      final long headersOffset) throws IOException {
    final Term term = new Term(this, documentFrequency, maxTf, minDocLength, blocks);
    final ByteBuffer headers = ByteBuffer.allocate(blocks * HEADER_BYTES);
    readFully(headersOffset, headers);
    headers.flip();
    final long[] impactOffsets = new long[blocks];
    final int[] impactLengths = new int[blocks];
    for (int i = 0; i < blocks; i++) {
      term.lastRids[i] = headers.getLong();
      term.payloadOffsets[i] = headers.getLong();
      term.payloadLengths[i] = headers.getInt();
      term.counts[i] = headers.getInt();
      term.blockMaxTf[i] = headers.getInt();
      term.blockMinDocLength[i] = headers.getInt();
      impactOffsets[i] = headers.getLong();
      impactLengths[i] = headers.getInt();
      headers.getInt();
    }

    // The impacts of every block are contiguous: one read
    final long impactsStart = impactOffsets[0];
    final long impactsEnd = impactOffsets[blocks - 1] + impactLengths[blocks - 1];
    final byte[] bytes = new byte[(int) (impactsEnd - impactsStart)];
    readFully(impactsStart, bytes, bytes.length);
    term.impactStarts = new int[blocks + 1];
    int[] tfs = new int[blocks * 2];
    int[] docLengths = new int[blocks * 2];
    int size = 0;
    final int[] position = { 0 };
    for (int i = 0; i < blocks; i++) {
      term.impactStarts[i] = size;
      final int pairs = (int) readVarLong(bytes, position);
      if (size + pairs > tfs.length) {
        tfs = Arrays.copyOf(tfs, Math.max(tfs.length * 2, size + pairs));
        docLengths = Arrays.copyOf(docLengths, tfs.length);
      }
      for (int p = 0; p < pairs; p++) {
        tfs[size] = (int) readVarLong(bytes, position);
        docLengths[size] = (int) readVarLong(bytes, position);
        ++size;
      }
    }
    term.impactStarts[blocks] = size;
    term.impactTfs = tfs;
    term.impactDocLengths = docLengths;
    return term;
  }

  private void readFully(final long position, final byte[] bytes, final int length) throws IOException {
    readFully(position, ByteBuffer.wrap(bytes, 0, length));
  }

  /**
   * Reads from the shared channel. A thread interrupted while it reads closes the channel for every reader (that is how
   * {@link FileChannel} answers an interrupt): the interrupted reader reopens it for the others and fails with the interrupt, its
   * query's own error; a reader that finds it closed that way reopens it and reads on. Only a failure of the file itself is a
   * {@link SidecarReadException}.
   */
  private void readFully(long position, final ByteBuffer buffer) throws IOException {
    int reopened = 0;
    while (buffer.hasRemaining()) {
      final FileChannel current = channel;
      final int read;
      try {
        read = current.read(buffer, position);
      } catch (final ClosedByInterruptException e) {
        try {
          reopen(current);
        } catch (final IOException ignored) {
          // The next reader tries again, and reports it if the file cannot be opened
        }
        throw e;
      } catch (final ClosedChannelException e) {
        // Closed under this reader by another reader's interrupt. Bounded: a channel that keeps closing is reported, not retried
        if (++reopened > MAX_REOPENS)
          throw e;
        reopen(current);
        continue;
      } catch (final IOException e) {
        throw new SidecarReadException("Cannot read block-max file " + file, e);
      }
      if (read < 0)
        throw new SidecarReadException("Unexpected end of block-max file " + file, null);
      position += read;
    }
  }

  /** The times a read reopens the channel closed under it before it gives up. */
  private static final int MAX_REOPENS = 8;

  /**
   * Puts a new channel in place of {@code closed}, unless another reader already did or the sidecar was released (the last
   * reference closes the channel for good).
   */
  private void reopen(final FileChannel closed) throws IOException {
    synchronized (channelLock) {
      if (channelReleased)
        throw new ClosedChannelException();
      if (channel != closed || closed.isOpen())
        return;
      try {
        channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
      } catch (final IOException e) {
        throw new SidecarReadException("Cannot reopen block-max file " + file, e);
      }
    }
  }

  private static long readVarLong(final byte[] bytes, final int[] position) {
    long value = 0;
    int shift = 0;
    byte b;
    do {
      b = bytes[position[0]++];
      value |= (long) (b & 0x7F) << shift;
      shift += 7;
    } while (b < 0);
    return value;
  }

  /**
   * Opens a sidecar, or answers null when the file is missing, from another version, or was not completely written.
   */
  static BlockMaxPostings open(final File file) {
    if (!file.exists())
      return null;
    FileChannel channel = null;
    try {
      channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
      final ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
      while (header.hasRemaining())
        if (channel.read(header, header.position()) < 0)
          throw new IOException("truncated header");
      header.flip();
      if (header.getInt() != MAGIC || header.getInt() != VERSION || header.getInt() != BLOCK_SIZE)
        throw new IOException("not a block-max file of this version");
      final int compactedFileId = header.getInt();
      final int seriesEnd = header.getInt();
      final long seriesFingerprint = header.getLong();
      final long termCount = header.getLong();
      final long dictionaryOffset = header.getLong();
      final long sparseOffset = header.getLong();
      final int sparseEntries = header.getInt();
      final int complete = header.getInt();
      final int minDocumentFrequency = header.getInt();
      if (complete != 1 || sparseOffset > channel.size())
        throw new IOException("incomplete block-max file");

      final ByteBuffer sparse = ByteBuffer.allocate((int) (channel.size() - sparseOffset));
      long position = sparseOffset;
      while (sparse.hasRemaining()) {
        final int read = channel.read(sparse, position);
        if (read < 0)
          throw new IOException("truncated sparse index");
        position += read;
      }
      final byte[] bytes = sparse.array();
      final String[] keys = new String[sparseEntries];
      final long[] offsets = new long[sparseEntries];
      final int[] cursor = { 0 };
      for (int i = 0; i < sparseEntries; i++) {
        final int keyLength = (int) readVarLong(bytes, cursor);
        keys[i] = new String(bytes, cursor[0], keyLength, StandardCharsets.UTF_8);
        cursor[0] += keyLength;
        offsets[i] = ByteBuffer.wrap(bytes, cursor[0], 8).getLong();
        cursor[0] += 8;
      }
      return new BlockMaxPostings(file, channel, compactedFileId, seriesEnd, seriesFingerprint, termCount, minDocumentFrequency,
          sparseOffset, keys, offsets);
    } catch (final IOException | RuntimeException e) {
      if (channel != null)
        try {
          channel.close();
        } catch (final IOException ignored) {
          // ALREADY FAILING
        }
      return null;
    }
  }

  /**
   * Writes a sidecar term by term, in key order, into temporary files of its own that {@link #finish()} completes and moves into
   * place: two writers never share a file, and a writer that fails leaves the target untouched.
   */
  static final class Writer implements Closeable {
    /**
     * The targets of the writers of this JVM that have not closed yet. A schema reload builds the successor of a full-text index while
     * the build of the instance it replaces may still be writing (an HA follower reloads for every compaction it applies): the
     * successor's open must neither delete that build's temporary files nor pick the generation it is writing.
     */
    private static final Set<File> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final File                  target;
    private final File                  temporary;
    private final File                  dictionaryFile;
    private final CountingOutputStream  out;
    private final CountingOutputStream  dictionary;
    private final int                   compactedFileId;
    private final int                   seriesEnd;
    private final long                  seriesFingerprint;
    private final List<String>          sparseKeys    = new ArrayList<>();
    private final List<Long>            sparseOffsets = new ArrayList<>();
    private final byte[]                scratch       = new byte[10];
    private       long                  termCount;
    private       String                lastKey;
    private       boolean               finished;

    Writer(final File target, final int compactedFileId, final int seriesEnd, final long seriesFingerprint) throws IOException {
      this.target = target.getAbsoluteFile();
      final File directory = this.target.getParentFile();
      IN_FLIGHT.add(this.target);
      File temporaryFile = null;
      File dictionaryTemporary = null;
      try {
        if (!directory.exists() && !directory.mkdirs() && !directory.exists())
          throw new IOException("Cannot create directory " + directory);
        temporaryFile = Files.createTempFile(directory.toPath(), target.getName() + ".", ".tmp").toFile();
        this.temporary = temporaryFile;
        dictionaryTemporary = Files.createTempFile(directory.toPath(), target.getName() + ".", ".tmp").toFile();
        this.dictionaryFile = dictionaryTemporary;
        this.compactedFileId = compactedFileId;
        this.seriesEnd = seriesEnd;
        this.seriesFingerprint = seriesFingerprint;
        this.out = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(temporary), 1 << 16));
        // The header is written last: reserve its room
        out.write(new byte[FILE_HEADER_BYTES]);
        this.dictionary = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(dictionaryFile), 1 << 16));
      } catch (final IOException | RuntimeException e) {
        if (temporaryFile != null)
          deleteQuietly(temporaryFile);
        if (dictionaryTemporary != null)
          deleteQuietly(dictionaryTemporary);
        IN_FLIGHT.remove(this.target);
        throw e;
      }
    }

    /**
     * True when {@code file} is the target of a writer still open, or one of its temporary files
     * ({@code <target name>.<random>.tmp}).
     */
    static boolean isInFlight(final File file) {
      if (IN_FLIGHT.isEmpty())
        return false;
      final File absolute = file.getAbsoluteFile();
      for (final File target : IN_FLIGHT)
        if (target.equals(absolute) || (target.getParentFile().equals(absolute.getParentFile()) && absolute.getName()
            .startsWith(target.getName() + ".")))
          return true;
      return false;
    }

    /** The highest generation of {@code indexName} a writer still open in {@code directory} is writing, or -1. */
    static long highestGenerationInFlight(final File directory, final String indexName) {
      long highest = -1L;
      if (IN_FLIGHT.isEmpty())
        return highest;
      final File absoluteDirectory = directory.getAbsoluteFile();
      for (final File target : IN_FLIGHT)
        if (target.getParentFile().equals(absoluteDirectory))
          highest = Math.max(highest, generationOf(indexName, target.getName()));
      return highest;
    }

    /**
     * Adds a term with its postings, {@code rids} strictly ascending. Terms must come in key order.
     */
    void addTerm(final String key, final long[] rids, final int[] tfs, final int[] docLengths, final int count) throws IOException {
      if (count < MIN_DOCUMENT_FREQUENCY)
        return;
      if (lastKey != null && BinaryComparator.compareStrings(lastKey, key) >= 0)
        throw new IllegalArgumentException("Terms out of order: '" + lastKey + "' then '" + key + "'");
      lastKey = key;

      final int blocks = (count + BLOCK_SIZE - 1) / BLOCK_SIZE;
      final ByteBuffer headers = ByteBuffer.allocate(blocks * HEADER_BYTES);
      final long[] payloadOffsets = new long[blocks];
      final int[] payloadLengths = new int[blocks];
      final int[] maxTfs = new int[blocks];
      final int[] minDocLengths = new int[blocks];
      int termMaxTf = 0;
      int termMinDocLength = Integer.MAX_VALUE;
      long previous = 0L;
      for (int block = 0; block < blocks; block++) {
        final int from = block * BLOCK_SIZE;
        final int to = Math.min(count, from + BLOCK_SIZE);
        final long payloadOffset = out.count();
        int maxTf = 0;
        int minDocLength = Integer.MAX_VALUE;
        for (int i = from; i < to; i++) {
          writeVarLong(out, rids[i] - previous);
          previous = rids[i];
          writeVarLong(out, tfs[i]);
          writeVarLong(out, docLengths[i]);
          maxTf = Math.max(maxTf, tfs[i]);
          minDocLength = Math.min(minDocLength, docLengths[i]);
        }
        payloadOffsets[block] = payloadOffset;
        payloadLengths[block] = (int) (out.count() - payloadOffset);
        maxTfs[block] = maxTf;
        minDocLengths[block] = minDocLength;
        termMaxTf = Math.max(termMaxTf, maxTf);
        termMinDocLength = Math.min(termMinDocLength, minDocLength);
      }

      final long[] impactOffsets = new long[blocks];
      final int[] impactLengths = new int[blocks];
      for (int block = 0; block < blocks; block++) {
        final int from = block * BLOCK_SIZE;
        final int to = Math.min(count, from + BLOCK_SIZE);
        impactOffsets[block] = out.count();
        writeImpacts(tfs, docLengths, from, to);
        impactLengths[block] = (int) (out.count() - impactOffsets[block]);
      }

      for (int block = 0; block < blocks; block++) {
        headers.putLong(rids[Math.min(count, (block + 1) * BLOCK_SIZE) - 1]);
        headers.putLong(payloadOffsets[block]);
        headers.putInt(payloadLengths[block]);
        headers.putInt(Math.min(count, (block + 1) * BLOCK_SIZE) - block * BLOCK_SIZE);
        headers.putInt(maxTfs[block]);
        headers.putInt(minDocLengths[block]);
        headers.putLong(impactOffsets[block]);
        headers.putInt(impactLengths[block]);
        headers.putInt(0);
      }
      final long headersOffset = out.count();
      out.write(headers.array());

      if (termCount % SPARSE_STEP == 0) {
        sparseKeys.add(key);
        sparseOffsets.add(dictionary.count());
      }
      final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
      writeVarLong(dictionary, keyBytes.length);
      dictionary.write(keyBytes);
      writeVarLong(dictionary, count);
      writeVarLong(dictionary, blocks);
      dictionary.write(ByteBuffer.allocate(8).putLong(headersOffset).array());
      writeVarLong(dictionary, termMaxTf);
      writeVarLong(dictionary, termMinDocLength);
      ++termCount;
    }

    private final long[] pairs = new long[BLOCK_SIZE];

    /**
     * Writes the impacts of the postings {@code [from, to)}: the (tf, length) pairs no other posting of the block matches or beats
     * on both, by tf descending. A posting with no statistics ({@code tf < 0}) contributes nothing and has none.
     */
    private void writeImpacts(final int[] tfs, final int[] docLengths, final int from, final int to) throws IOException {
      // Packed so an ascending sort orders by tf descending, then length ascending
      int count = 0;
      for (int i = from; i < to; i++)
        if (tfs[i] >= 0)
          pairs[count++] = ((long) (Integer.MAX_VALUE - tfs[i]) << 32) | docLengths[i];
      Arrays.sort(pairs, 0, count);
      // Keep a pair only when its length is below every length kept so far
      int kept = 0;
      long shortest = Long.MAX_VALUE;
      for (int i = 0; i < count; i++) {
        final long docLength = pairs[i] & 0xFFFFFFFFL;
        if (docLength < shortest) {
          shortest = docLength;
          pairs[kept++] = pairs[i];
        }
      }
      writeVarLong(out, kept);
      for (int i = 0; i < kept; i++) {
        writeVarLong(out, Integer.MAX_VALUE - (int) (pairs[i] >>> 32));
        writeVarLong(out, pairs[i] & 0xFFFFFFFFL);
      }
    }

    /** Appends the dictionary and the sparse index, writes the header, and moves the file into place atomically. */
    void finish() throws IOException {
      dictionary.close();
      final long dictionaryOffset = out.count();
      Files.copy(dictionaryFile.toPath(), out);
      final long sparseOffset = out.count();
      for (int i = 0; i < sparseKeys.size(); i++) {
        final byte[] keyBytes = sparseKeys.get(i).getBytes(StandardCharsets.UTF_8);
        writeVarLong(out, keyBytes.length);
        out.write(keyBytes);
        out.write(ByteBuffer.allocate(8).putLong(dictionaryOffset + sparseOffsets.get(i)).array());
      }
      out.close();

      final ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
      header.putInt(MAGIC).putInt(VERSION).putInt(BLOCK_SIZE).putInt(compactedFileId).putInt(seriesEnd).putLong(seriesFingerprint)
          .putLong(termCount)
          .putLong(dictionaryOffset).putLong(sparseOffset).putInt(sparseKeys.size()).putInt(1).putInt(MIN_DOCUMENT_FREQUENCY);
      header.rewind();
      try (final FileChannel channel = FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE)) {
        while (header.hasRemaining())
          channel.write(header, header.position());
        channel.force(true);
      }
      // A fresh generation: nothing reads the target, so the move never replaces an open file
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Files.deleteIfExists(dictionaryFile.toPath());
      finished = true;
    }

    @Override
    public void close() throws IOException {
      if (finished) {
        IN_FLIGHT.remove(target);
        return;
      }
      try {
        try {
          out.close();
        } finally {
          dictionary.close();
        }
      } finally {
        try {
          Files.deleteIfExists(temporary.toPath());
          Files.deleteIfExists(dictionaryFile.toPath());
        } finally {
          IN_FLIGHT.remove(target);
        }
      }
    }

    private void writeVarLong(final OutputStream stream, long value) throws IOException {
      int length = 0;
      while ((value & ~0x7FL) != 0) {
        scratch[length++] = (byte) ((value & 0x7F) | 0x80);
        value >>>= 7;
      }
      scratch[length++] = (byte) value;
      stream.write(scratch, 0, length);
    }
  }

  /** Counts the bytes written, which are the file offsets. */
  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private       long         count;

    CountingOutputStream(final OutputStream delegate) {
      this.delegate = delegate;
    }

    long count() {
      return count;
    }

    @Override
    public void write(final int b) throws IOException {
      delegate.write(b);
      ++count;
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
      delegate.write(b, off, len);
      count += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  /** Sorts parallel posting arrays by RID ascending, in place and without boxing. */
  static void sortByRid(final long[] rids, final int[] tfs, final int[] docLengths, final int count) {
    boolean sorted = true;
    for (int i = 1; i < count && sorted; i++)
      sorted = rids[i - 1] <= rids[i];
    if (!sorted)
      quickSort(rids, tfs, docLengths, 0, count - 1);
  }

  private static void quickSort(final long[] rids, final int[] tfs, final int[] docLengths, int low, int high) {
    while (high - low > 16) {
      final long pivot = rids[(low + high) >>> 1];
      int i = low;
      int j = high;
      while (i <= j) {
        while (rids[i] < pivot)
          ++i;
        while (rids[j] > pivot)
          --j;
        if (i <= j)
          swap(rids, tfs, docLengths, i++, j--);
      }
      // Recurse into the smaller side, loop on the larger: bounded stack depth
      if (j - low < high - i) {
        quickSort(rids, tfs, docLengths, low, j);
        low = i;
      } else {
        quickSort(rids, tfs, docLengths, i, high);
        high = j;
      }
    }
    for (int i = low + 1; i <= high; i++)
      for (int j = i; j > low && rids[j - 1] > rids[j]; j--)
        swap(rids, tfs, docLengths, j, j - 1);
  }

  private static void swap(final long[] rids, final int[] tfs, final int[] docLengths, final int a, final int b) {
    final long rid = rids[a];
    rids[a] = rids[b];
    rids[b] = rid;
    final int tf = tfs[a];
    tfs[a] = tfs[b];
    tfs[b] = tf;
    final int docLength = docLengths[a];
    docLengths[a] = docLengths[b];
    docLengths[b] = docLength;
  }
}
