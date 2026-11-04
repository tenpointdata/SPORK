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

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.engine.BasePage;
import com.arcadedb.engine.ComponentFactory;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.engine.MutablePage;
import com.arcadedb.engine.PageId;
import com.arcadedb.engine.PaginatedComponent;
import com.arcadedb.index.IndexException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The one page file of an {@code LSM_IVF_VECTOR} bucket index: header, centroids and posting lists, all as ordinary
 * {@link PaginatedComponent} pages, so every write rides the transaction that made it into the WAL and onto the
 * replication stream, and the page cache serves every read.
 * <p>
 * <b>Layout</b> (offsets are into the page content, after {@link BasePage#PAGE_HEADER_SIZE}):
 * <ul>
 *   <li>page 0, the header: magic, format, dimensions, {@code nlist}, coarse count, similarity/scan/rerank codes, the
 *   trained flag, the centroid version, the first page of every section below, and the entry counters</li>
 *   <li>thresholds: {@code dimensions} floats, the per-dimension BINARY threshold</li>
 *   <li>coarse centroids, fine centroids: whole centroids per page, {@code floor(content / (dimensions * 4))} each</li>
 *   <li>coarse starts: {@code coarseCount + 1} ints</li>
 *   <li>directory: one 12-byte entry per fine centroid: head page, tail page, entries appended</li>
 *   <li>posting pages, appended on demand: a 16-byte header (centroid, entry count, next page, reserved) and fixed-size
 *   entries: live flag, record position, scan code (+ scale), rerank code (+ scale)</li>
 *   <li>the position locator, a linear hash table of record position to posting slot ({@link IVFLocator}), so a delete does not
 *   walk the list</li>
 * </ul>
 * A training writes a fresh set of the centroid and directory sections after the last page and points the header at
 * them; the previous sections stay as dead pages (a {@code REBUILD INDEX} drops the file anyway).
 */
public class IVFPostingsComponent extends PaginatedComponent {
  public static final String FILE_EXT          = "ivfpost";
  public static final int    CURRENT_VERSION   = 1;
  public static final int    DEFAULT_PAGE_SIZE = 65536;
  public static final int    MAGIC             = 0x49564631; // "IVF1"

  // Header page offsets
  static final int H_MAGIC         = 0;
  static final int H_FORMAT        = 4;
  static final int H_DIMENSIONS    = 8;
  static final int H_NLIST         = 12;
  static final int H_COARSE_COUNT  = 16;
  static final int H_SIMILARITY    = 20;
  static final int H_SCAN_CODE     = 21;
  static final int H_RERANK_CODE   = 22;
  static final int H_TRAINED       = 23;
  static final int H_VERSION       = 24;
  static final int H_THRESHOLDS    = 28;
  static final int H_COARSE_PAGE   = 32;
  static final int H_FINE_PAGE     = 36;
  static final int H_STARTS_PAGE   = 40;
  static final int H_DIRECTORY     = 44;
  static final int H_DIR_PAGES     = 48;
  static final int H_TOTAL_ENTRIES = 52;
  static final int H_LIVE_ENTRIES  = 60;
  static final int H_PROJECTION    = 68;
  // The position locator (see IVFLocator)
  static final int H_LOC_DIR       = 72;
  static final int H_LOC_LEVEL     = 76;
  static final int H_LOC_SPLIT     = 80;
  static final int H_LOC_ENTRIES   = 84;
  /** Live entries the last training saw (0 in a file written before it was kept): the automatic retraining's baseline. */
  static final int H_TRAINED_AT    = 92;

  // Posting page offsets
  public static final int P_CENTROID    = 0;
  public static final int P_COUNT       = 4;
  public static final int P_NEXT        = 8;
  public static final int P_ENTRIES     = 16;
  // Entry offsets
  public static final int E_LIVE        = 0;
  public static final int E_POSITION    = 1;
  public static final int E_CODES       = 9;

  static final int DIRECTORY_ENTRY = 12;
  static final int NO_PAGE         = -1;

  /** For tests only: sees every page this component reads and may answer another page in its place (a page of a commit in flight). */
  interface PageReadObserver {
    BasePage observe(IVFPostingsComponent component, int pageNumber, BasePage page);
  }

  private static volatile PageReadObserver pageReadObserver;

  /** For tests only: {@code observer} sees every page read of every postings file from now on; null removes it. */
  static void setPageReadObserver(final PageReadObserver observer) {
    pageReadObserver = observer;
  }

  private BasePage observed(final int pageNumber, final BasePage page) {
    final PageReadObserver observer = pageReadObserver;
    return observer == null ? page : observer.observe(this, pageNumber, page);
  }

  public IVFPostingsComponent(final DatabaseInternal database, final String name, final String filePath, final ComponentFile.MODE mode,
      final int pageSize) throws IOException {
    super(database, name, filePath, FILE_EXT, mode, pageSize, CURRENT_VERSION);
  }

  protected IVFPostingsComponent(final DatabaseInternal database, final String name, final String filePath, final int id,
      final ComponentFile.MODE mode, final int pageSize, final int version) throws IOException {
    super(database, name, filePath, id, mode, pageSize, version);
  }

  public static class PaginatedComponentFactoryHandler implements ComponentFactory.PaginatedComponentFactoryHandler {
    @Override
    public PaginatedComponent createOnLoad(final DatabaseInternal database, final String name, final String filePath, final int id,
        final ComponentFile.MODE mode, final int pageSize, final int version) throws IOException {
      return new IVFPostingsComponent(database, name, filePath, id, mode, pageSize, version);
    }
  }

  @Override
  public Object getMainComponent() {
    return this;
  }

  public int contentSize() {
    return pageSize - BasePage.PAGE_HEADER_SIZE;
  }

  public BasePage readPage(final int pageNumber) {
    try {
      return observed(pageNumber, database.getTransaction().getPage(new PageId(database, getFileId(), pageNumber), pageSize));
    } catch (final IOException e) {
      throw new IndexException("IVF postings '" + getName() + "': error reading page " + pageNumber, e);
    }
  }

  public MutablePage modifyPage(final int pageNumber) {
    try {
      return database.getTransaction().getPageToModify(new PageId(database, getFileId(), pageNumber), pageSize, false);
    } catch (final IOException e) {
      throw new IndexException("IVF postings '" + getName() + "': error modifying page " + pageNumber, e);
    }
  }

  /**
   * A posting page for a reader that is not the transaction's thread (a parallel scan worker): the page cache's shared view, read
   * at absolute positions only. Valid only while the caller's transaction holds no page of this file and runs READ_COMMITTED,
   * where {@link #readPage} answers the same committed bytes (see {@code TransactionContext.getPageForAbsoluteReads}).
   */
  public BasePage readPageShared(final int pageNumber) {
    try {
      return observed(pageNumber, database.getPageManager().getSharedImmutablePage(new PageId(database, getFileId(), pageNumber), pageSize));
    } catch (final IOException e) {
      throw new IndexException("IVF postings '" + getName() + "': error reading page " + pageNumber, e);
    }
  }

  /** {@link #readPage} for a reader at absolute positions on the transaction's thread: no page copy on a READ_COMMITTED miss. */
  public BasePage readPageForScan(final int pageNumber) {
    try {
      return observed(pageNumber, database.getTransaction().getPageForAbsoluteReads(new PageId(database, getFileId(), pageNumber), pageSize));
    } catch (final IOException e) {
      throw new IndexException("IVF postings '" + getName() + "': error reading page " + pageNumber, e);
    }
  }

  /** Appends a page to the file inside the current transaction and answers it, zeroed. */
  public MutablePage allocatePage() {
    final int pageNumber = getTotalPages();
    return database.getTransaction().addPage(new PageId(database, getFileId(), pageNumber), pageSize);
  }

  // ---------------------------------------------------------------- header

  /** What the header page says; {@code null} when the file has no page yet (a follower before the first apply). */
  public Header readHeader() {
    if (getTotalPages() == 0)
      return null;
    return readHeader(readPage(0));
  }

  /** What the header page says in the committed state, the calling transaction ignored; {@code null} when nothing is committed yet. */
  public Header readCommittedHeader() {
    if (getCommittedPageCount() == 0)
      return null;
    return readHeader(readPageShared(0));
  }

  private Header readHeader(final BasePage page) {
    if (page.readInt(H_MAGIC) != MAGIC)
      throw new IndexException("IVF postings '" + getName() + "': bad magic in header page");
    return new Header(page.readInt(H_DIMENSIONS), page.readInt(H_NLIST), page.readInt(H_COARSE_COUNT),
        IVFSimilarity.values()[page.readByte(H_SIMILARITY)], IVFScanCode.values()[page.readByte(H_SCAN_CODE)],
        IVFRerankCode.values()[page.readByte(H_RERANK_CODE)], page.readByte(H_TRAINED) != 0, page.readInt(H_VERSION),
        page.readInt(H_THRESHOLDS), page.readInt(H_COARSE_PAGE), page.readInt(H_FINE_PAGE), page.readInt(H_STARTS_PAGE),
        page.readInt(H_DIRECTORY), page.readInt(H_DIR_PAGES), page.readLong(H_TOTAL_ENTRIES), page.readLong(H_LIVE_ENTRIES),
        page.readInt(H_PROJECTION), page.readLong(H_TRAINED_AT));
  }

  /** The centroid version alone, the cheapest check a search makes to learn whether its resident centroids are current. */
  public int readCentroidVersion() {
    if (getTotalPages() == 0)
      return -1;
    return readPage(0).readInt(H_VERSION);
  }

  /** {@link #readCentroidVersion()} in the committed state, the calling transaction ignored. */
  public int readCommittedCentroidVersion() {
    if (getCommittedPageCount() == 0)
      return -1;
    return readPageShared(0).readInt(H_VERSION);
  }

  public record Header(int dimensions, int nlist, int coarseCount, IVFSimilarity similarity, IVFScanCode scanCode,
                       IVFRerankCode rerankCode, boolean trained, int version, int thresholdsPage, int coarsePage, int finePage,
                       int startsPage, int directoryPage, int directoryPages, long totalEntries, long liveEntries,
                       int projectionSeed, long trainedAt) {
  }

  // ---------------------------------------------------------------- layout

  /**
   * Writes the centroid and directory sections for {@code centroids} after the current last page and the header
   * pointing at them, inside the current transaction. The first call on an empty file writes the header page itself.
   *
   * @param resetEntries the new layout starts empty (a training that re-populates every list): the entry counters restart from
   *                     0 and a fresh locator is laid out; the pages of the previous layout stay as dead pages
   */
  public void writeLayout(final IVFCentroids centroids, final IVFSimilarity similarity, final IVFScanCode scanCode,
      final IVFRerankCode rerankCode, final boolean resetEntries) {
    final int dimensions = centroids.dimensions;
    final int content = contentSize();
    if (dimensions * 4 > content)
      throw new IndexException("IVF postings '" + getName() + "': " + dimensions + " dimensions do not fit a page of " + pageSize);

    final boolean empty = getTotalPages() == 0;
    final MutablePage header = empty ? allocatePage() : modifyPage(0);
    final boolean fresh = empty || resetEntries;
    final long totalEntries = fresh ? 0 : header.readLong(H_TOTAL_ENTRIES);
    final long liveEntries = fresh ? 0 : header.readLong(H_LIVE_ENTRIES);

    final int thresholdsPage = writeFloats(centroids.thresholds, dimensions);
    final int coarsePage = writeFloats(centroids.coarse, dimensions);
    final int finePage = writeFloats(centroids.fine, dimensions);
    final int startsPage = writeInts(centroids.coarseStart);

    final int directoryPerPage = content / DIRECTORY_ENTRY;
    final int directoryPages = Math.max(1, (centroids.nlist + directoryPerPage - 1) / directoryPerPage);
    final int directoryPage = getTotalPages();
    for (int p = 0; p < directoryPages; p++) {
      final MutablePage page = allocatePage();
      final int entries = Math.min(directoryPerPage, centroids.nlist - p * directoryPerPage);
      for (int e = 0; e < entries; e++) {
        final int offset = e * DIRECTORY_ENTRY;
        page.writeInt(offset, NO_PAGE);
        page.writeInt(offset + 4, NO_PAGE);
        page.writeInt(offset + 8, 0);
      }
    }

    header.writeInt(H_MAGIC, MAGIC);
    header.writeInt(H_FORMAT, CURRENT_VERSION);
    header.writeInt(H_DIMENSIONS, dimensions);
    header.writeInt(H_NLIST, centroids.nlist);
    header.writeInt(H_COARSE_COUNT, centroids.coarseCount);
    header.writeByte(H_SIMILARITY, (byte) similarity.ordinal());
    header.writeByte(H_SCAN_CODE, (byte) scanCode.ordinal());
    header.writeByte(H_RERANK_CODE, (byte) rerankCode.ordinal());
    header.writeByte(H_TRAINED, (byte) (centroids.trained ? 1 : 0));
    header.writeInt(H_VERSION, centroids.version);
    header.writeInt(H_THRESHOLDS, thresholdsPage);
    header.writeInt(H_COARSE_PAGE, coarsePage);
    header.writeInt(H_FINE_PAGE, finePage);
    header.writeInt(H_STARTS_PAGE, startsPage);
    header.writeInt(H_DIRECTORY, directoryPage);
    header.writeInt(H_DIR_PAGES, directoryPages);
    header.writeLong(H_TOTAL_ENTRIES, totalEntries);
    header.writeLong(H_LIVE_ENTRIES, liveEntries);
    header.writeInt(H_PROJECTION, centroids.projectionSeed);
    if (fresh) {
      header.writeLong(H_TRAINED_AT, 0L);
      new IVFLocator(this).init(header);
    }
  }

  /** Records, inside the current transaction, the live entries the training that wrote the current layout saw. */
  public void writeTrainedAt(final long entries) {
    modifyPage(0).writeLong(H_TRAINED_AT, entries);
  }

  /** Writes {@code values} as whole {@code dimensions}-wide rows per page; answers the first page. */
  private int writeFloats(final float[] values, final int dimensions) {
    final int rowBytes = dimensions * 4;
    final int rowsPerPage = contentSize() / rowBytes;
    final int rows = values.length / dimensions;
    final int first = getTotalPages();
    final int pages = Math.max(1, (rows + rowsPerPage - 1) / rowsPerPage);
    for (int p = 0; p < pages; p++) {
      final MutablePage page = allocatePage();
      final int rowsHere = Math.min(rowsPerPage, rows - p * rowsPerPage);
      int offset = 0;
      for (int r = 0; r < rowsHere; r++) {
        final int base = (p * rowsPerPage + r) * dimensions;
        for (int d = 0; d < dimensions; d++, offset += 4)
          page.writeFloat(offset, values[base + d]);
      }
    }
    return first;
  }

  private int writeInts(final int[] values) {
    final int perPage = contentSize() / 4;
    final int first = getTotalPages();
    final int pages = Math.max(1, (values.length + perPage - 1) / perPage);
    for (int p = 0; p < pages; p++) {
      final MutablePage page = allocatePage();
      final int here = Math.min(perPage, values.length - p * perPage);
      for (int i = 0; i < here; i++)
        page.writeInt(i * 4, values[p * perPage + i]);
    }
    return first;
  }

  /**
   * Reads the resident centroid set the header points at, through the calling transaction. Answers {@code null} when the header
   * points at pages that do not exist yet or the coarse starts do not frame the fine centroids: the header of a training whose
   * other pages are still being published, read in that window (a page past the file reads as zeros).
   */
  public IVFCentroids readCentroids(final Header header) {
    return readCentroids(header, false);
  }

  /** {@link #readCentroids(Header)} in the committed state ({@code shared}: the page cache's pages, the calling transaction ignored). */
  public IVFCentroids readCentroids(final Header header, final boolean shared) {
    final int dimensions = header.dimensions();
    final int pages = shared ? getCommittedPageCount() : getTotalPages();
    if (lastPageOf(header.thresholdsPage(), dimensions * 4, 1) >= pages
        || lastPageOf(header.coarsePage(), dimensions * 4, header.coarseCount()) >= pages
        || lastPageOf(header.finePage(), dimensions * 4, header.nlist()) >= pages
        || lastPageOf(header.startsPage(), 4, header.coarseCount() + 1) >= pages)
      return null;
    final float[] thresholds = readFloats(header.thresholdsPage(), dimensions, 1, shared);
    final float[] coarse = readFloats(header.coarsePage(), dimensions, header.coarseCount(), shared);
    final float[] fine = readFloats(header.finePage(), dimensions, header.nlist(), shared);
    final int[] starts = readInts(header.startsPage(), header.coarseCount() + 1, shared);
    if (starts[0] != 0 || starts[header.coarseCount()] != header.nlist())
      return null;
    return new IVFCentroids(dimensions, fine, coarse, starts, thresholds, header.version(), header.trained(), header.projectionSeed());
  }

  /** The last page a section of {@code rows} rows of {@code rowBytes} bytes starting at {@code firstPage} occupies. */
  private int lastPageOf(final int firstPage, final int rowBytes, final int rows) {
    if (firstPage <= 0)
      return Integer.MAX_VALUE;
    final int rowsPerPage = contentSize() / rowBytes;
    return firstPage + Math.max(0, rows - 1) / rowsPerPage;
  }

  private BasePage read(final int pageNumber, final boolean shared) {
    return shared ? readPageShared(pageNumber) : readPage(pageNumber);
  }

  private float[] readFloats(final int firstPage, final int dimensions, final int rows, final boolean shared) {
    final int rowsPerPage = contentSize() / (dimensions * 4);
    final float[] values = new float[rows * dimensions];
    ByteBuffer buffer = null;
    for (int r = 0; r < rows; r++) {
      // One page read per page, not per row: a set of 1.5M rows is a few thousand pages
      if (r % rowsPerPage == 0)
        buffer = read(firstPage + r / rowsPerPage, shared).getContent();
      int position = BasePage.PAGE_HEADER_SIZE + (r % rowsPerPage) * dimensions * 4;
      final int base = r * dimensions;
      for (int d = 0; d < dimensions; d++, position += 4)
        values[base + d] = buffer.getFloat(position);
    }
    return values;
  }

  private int[] readInts(final int firstPage, final int count, final boolean shared) {
    final int perPage = contentSize() / 4;
    final int[] values = new int[count];
    ByteBuffer buffer = null;
    for (int i = 0; i < count; i++) {
      if (i % perPage == 0)
        buffer = read(firstPage + i / perPage, shared).getContent();
      values[i] = buffer.getInt(BasePage.PAGE_HEADER_SIZE + (i % perPage) * 4);
    }
    return values;
  }

  // ---------------------------------------------------------------- directory and postings

  /** The page of the directory entry of {@code centroid} and the offset of the entry inside it. */
  private int directoryPageOf(final Header header, final int centroid) {
    return header.directoryPage() + centroid / (contentSize() / DIRECTORY_ENTRY);
  }

  private int directoryOffsetOf(final int centroid) {
    return (centroid % (contentSize() / DIRECTORY_ENTRY)) * DIRECTORY_ENTRY;
  }

  /** The head posting page of {@code centroid}, or {@link #NO_PAGE}. */
  public int headPage(final Header header, final int centroid) {
    return readPage(directoryPageOf(header, centroid)).readInt(directoryOffsetOf(centroid));
  }

  /** Entries ever appended to {@code centroid} (dead ones included). */
  public int appendedEntries(final Header header, final int centroid) {
    return readPage(directoryPageOf(header, centroid)).readInt(directoryOffsetOf(centroid) + 8);
  }

  /**
   * Appends one entry to the posting list of {@code centroid} inside the current transaction: the tail page when it has
   * room, a new page linked after it otherwise. {@code entry} is the whole entry minus the live flag and position. A live entry
   * the locator already holds for {@code position} is cleared first, so a position has at most one live entry.
   */
  public void append(final Header header, final int centroid, final long position, final byte[] entry, final int entrySize) {
    final MutablePage directory = modifyPage(directoryPageOf(header, centroid));
    final int directoryOffset = directoryOffsetOf(centroid);
    int tail = directory.readInt(directoryOffset + 4);
    MutablePage page;
    int count;
    if (tail == NO_PAGE) {
      page = newPostingPage(centroid);
      tail = page.getPageId().getPageNumber();
      directory.writeInt(directoryOffset, tail);
      directory.writeInt(directoryOffset + 4, tail);
      count = 0;
    } else {
      page = modifyPage(tail);
      count = page.readInt(P_COUNT);
      if (P_ENTRIES + (count + 1) * entrySize > contentSize()) {
        final MutablePage next = newPostingPage(centroid);
        page.writeInt(P_NEXT, next.getPageId().getPageNumber());
        page = next;
        tail = next.getPageId().getPageNumber();
        directory.writeInt(directoryOffset + 4, tail);
        count = 0;
      }
    }
    final int offset = P_ENTRIES + count * entrySize;
    page.writeByte(offset + E_LIVE, (byte) 1);
    page.writeLong(offset + E_POSITION, position);
    page.writeByteArray(offset + E_CODES, entry);
    page.writeInt(P_COUNT, count + 1);
    directory.writeInt(directoryOffset + 8, directory.readInt(directoryOffset + 8) + 1);

    final MutablePage head = modifyPage(0);
    head.writeLong(H_TOTAL_ENTRIES, head.readLong(H_TOTAL_ENTRIES) + 1);
    head.writeLong(H_LIVE_ENTRIES, head.readLong(H_LIVE_ENTRIES) + 1);

    final long replaced = new IVFLocator(this).put(position, tail, count);
    if (replaced != IVFLocator.NOT_FOUND)
      clearSlot(replaced, position, entrySize);
  }

  /** Clears the live flag of the entry at {@code slot} if it is a live entry for {@code position}; answers whether it was. */
  private boolean clearSlot(final long slot, final long position, final int entrySize) {
    final int pageNumber = (int) (slot >>> 32);
    final int index = (int) slot;
    final BasePage page = readPage(pageNumber);
    final int offset = P_ENTRIES + index * entrySize;
    if (index >= page.readInt(P_COUNT) || page.readByte(offset + E_LIVE) == 0 || page.readLong(offset + E_POSITION) != position)
      return false;
    modifyPage(pageNumber).writeByte(offset + E_LIVE, (byte) 0);
    final MutablePage head = modifyPage(0);
    head.writeLong(H_LIVE_ENTRIES, head.readLong(H_LIVE_ENTRIES) - 1);
    return true;
  }

  /** Locator lookups served, for the statistics. */
  public long locatorEntries() {
    return new IVFLocator(this).size();
  }

  private MutablePage newPostingPage(final int centroid) {
    final MutablePage page = allocatePage();
    page.writeInt(P_CENTROID, centroid);
    page.writeInt(P_COUNT, 0);
    page.writeInt(P_NEXT, NO_PAGE);
    page.writeInt(12, 0);
    return page;
  }

  /**
   * Clears the live flag of the entry for {@code position}: through the locator (a few page reads), or, when the locator does not
   * know the position, by walking the posting list of {@code centroid} from its head ({@code centroid < 0}: no walk). Answers 1
   * when the locator found it, 2 when the walk did, 0 when no live entry exists.
   */
  public int markDeleted(final Header header, final int centroid, final long position, final int entrySize) {
    final IVFLocator locator = new IVFLocator(this);
    final long slot = locator.get(position);
    if (slot != IVFLocator.NOT_FOUND) {
      locator.remove(position);
      if (clearSlot(slot, position, entrySize))
        return 1;
    }
    if (centroid < 0 || centroid >= header.nlist())
      return 0;
    int pageNumber = headPage(header, centroid);
    while (pageNumber != NO_PAGE) {
      final BasePage page = readPage(pageNumber);
      final ByteBuffer buffer = page.getContent();
      final int count = buffer.getInt(BasePage.PAGE_HEADER_SIZE + P_COUNT);
      int offset = BasePage.PAGE_HEADER_SIZE + P_ENTRIES;
      for (int i = 0; i < count; i++, offset += entrySize) {
        if (buffer.get(offset + E_LIVE) != 0 && buffer.getLong(offset + E_POSITION) == position) {
          final MutablePage mutable = modifyPage(pageNumber);
          mutable.writeByte(P_ENTRIES + i * entrySize + E_LIVE, (byte) 0);
          final MutablePage head = modifyPage(0);
          head.writeLong(H_LIVE_ENTRIES, head.readLong(H_LIVE_ENTRIES) - 1);
          return 2;
        }
      }
      pageNumber = buffer.getInt(BasePage.PAGE_HEADER_SIZE + P_NEXT);
    }
    return 0;
  }
}
