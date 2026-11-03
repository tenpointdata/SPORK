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

import com.arcadedb.engine.BasePage;
import com.arcadedb.engine.MutablePage;

import java.nio.ByteBuffer;

/**
 * The record-position to posting-slot map of an {@code LSM_IVF_VECTOR} index, so a delete (and the replace of an update)
 * clears the entry it targets with a handful of page reads instead of walking the whole posting list.
 * <p>
 * Record positions are not dense ({@code bucketPage * maxRecordsInPage + slot}: a page of 2 KB records numbers 30 of its 2,048
 * slots), so the map is not an array indexed by position but a <b>linear hash table</b> in pages of the postings file: it grows
 * one bucket at a time (the bucket under the split pointer is rehashed into itself and its new sibling), never by a rewrite of
 * the whole table, which is what makes it possible to keep it inside the transaction of every commit at 300M entries.
 * <ul>
 *   <li>directory: a chain of pages of bucket page numbers ({@code [next page][bucket page]...}), header field
 *   {@link IVFPostingsComponent#H_LOC_DIR}</li>
 *   <li>bucket page: {@code [count][overflow page]} then 16-byte entries {@code [position long][posting page int][entry index int]};
 *   a full bucket chains an overflow page</li>
 *   <li>header page: level, split pointer, entry count ({@link IVFPostingsComponent#H_LOC_LEVEL} and the next two)</li>
 * </ul>
 * Every method runs inside the caller's transaction (the commit replay, or a build) on the pages of that transaction. Cost at
 * 16 bytes per entry and a 0.75 load: about 21 bytes per indexed vector, 3.6% of a 512-d BINARY + INT8 entry.
 */
final class IVFLocator {
  static final  long   NOT_FOUND    = -1L;
  private static final int    B_COUNT      = 0;
  private static final int    B_OVERFLOW   = 4;
  private static final int    B_ENTRIES    = 8;
  private static final int    ENTRY        = 16;
  private static final double MAX_LOAD     = 0.75;

  private final IVFPostingsComponent component;
  private final int                  perPage;
  private final int                  dirPerPage;

  IVFLocator(final IVFPostingsComponent component) {
    this.component = component;
    this.perPage = (component.contentSize() - B_ENTRIES) / ENTRY;
    this.dirPerPage = component.contentSize() / 4 - 1;
  }

  /** A fresh, empty table: one directory page, one bucket, level 0. The header page must be the caller's mutable page 0. */
  void init(final MutablePage header) {
    final MutablePage directory = component.allocatePage();
    final MutablePage bucket = newBucketPage();
    directory.writeInt(0, IVFPostingsComponent.NO_PAGE);
    directory.writeInt(4, bucket.getPageId().getPageNumber());
    header.writeInt(IVFPostingsComponent.H_LOC_DIR, directory.getPageId().getPageNumber());
    header.writeInt(IVFPostingsComponent.H_LOC_LEVEL, 0);
    header.writeInt(IVFPostingsComponent.H_LOC_SPLIT, 0);
    header.writeLong(IVFPostingsComponent.H_LOC_ENTRIES, 0L);
  }

  /** The slot of {@code position}: {@code postingPage << 32 | entryIndex}, or {@link #NOT_FOUND}. */
  long get(final long position) {
    final BasePage header = component.readPage(0);
    final int directory = header.readInt(IVFPostingsComponent.H_LOC_DIR);
    if (directory == IVFPostingsComponent.NO_PAGE || directory == 0)
      return NOT_FOUND;
    int page = bucketPage(directory, bucketOf(position, header.readInt(IVFPostingsComponent.H_LOC_LEVEL),
        header.readInt(IVFPostingsComponent.H_LOC_SPLIT)));
    while (page != IVFPostingsComponent.NO_PAGE) {
      final BasePage bucket = component.readPage(page);
      final ByteBuffer buffer = bucket.getContent();
      final int base = BasePage.PAGE_HEADER_SIZE;
      final int count = buffer.getInt(base + B_COUNT);
      int offset = base + B_ENTRIES;
      for (int i = 0; i < count; i++, offset += ENTRY)
        if (buffer.getLong(offset) == position)
          return ((long) buffer.getInt(offset + 8) << 32) | (buffer.getInt(offset + 12) & 0xFFFFFFFFL);
      page = buffer.getInt(base + B_OVERFLOW);
    }
    return NOT_FOUND;
  }

  /** Maps {@code position} to its slot; answers the slot it replaces ({@code postingPage << 32 | entryIndex}), or {@link #NOT_FOUND}. */
  long put(final long position, final int postingPage, final int entryIndex) {
    final MutablePage header = component.modifyPage(0);
    final int directory = header.readInt(IVFPostingsComponent.H_LOC_DIR);
    final int level = header.readInt(IVFPostingsComponent.H_LOC_LEVEL);
    final int split = header.readInt(IVFPostingsComponent.H_LOC_SPLIT);
    final int first = bucketPage(directory, bucketOf(position, level, split));

    // Replace in place when present; remember the first page of the chain with room (and the last page) for the append otherwise
    int page = first;
    int last = first;
    int room = IVFPostingsComponent.NO_PAGE;
    while (page != IVFPostingsComponent.NO_PAGE) {
      final BasePage bucket = component.readPage(page);
      final int count = bucket.readInt(B_COUNT);
      for (int i = 0; i < count; i++)
        if (bucket.readLong(B_ENTRIES + i * ENTRY) == position) {
          final long replaced = ((long) bucket.readInt(B_ENTRIES + i * ENTRY + 8) << 32) | (bucket.readInt(B_ENTRIES + i * ENTRY + 12)
              & 0xFFFFFFFFL);
          final MutablePage mutable = component.modifyPage(page);
          mutable.writeInt(B_ENTRIES + i * ENTRY + 8, postingPage);
          mutable.writeInt(B_ENTRIES + i * ENTRY + 12, entryIndex);
          return replaced;
        }
      if (room == IVFPostingsComponent.NO_PAGE && count < perPage)
        room = page;
      last = page;
      page = bucket.readInt(B_OVERFLOW);
    }
    appendToChain(room != IVFPostingsComponent.NO_PAGE ? room : last, position, postingPage, entryIndex);
    final long entries = header.readLong(IVFPostingsComponent.H_LOC_ENTRIES) + 1;
    header.writeLong(IVFPostingsComponent.H_LOC_ENTRIES, entries);

    final long buckets = (1L << level) + split;
    if (entries > buckets * perPage * MAX_LOAD)
      splitNext(header, directory, level, split);
    return NOT_FOUND;
  }

  /** Forgets {@code position}; answers whether it was mapped. */
  boolean remove(final long position) {
    final MutablePage header = component.modifyPage(0);
    final int directory = header.readInt(IVFPostingsComponent.H_LOC_DIR);
    if (directory == IVFPostingsComponent.NO_PAGE || directory == 0)
      return false;
    int page = bucketPage(directory, bucketOf(position, header.readInt(IVFPostingsComponent.H_LOC_LEVEL),
        header.readInt(IVFPostingsComponent.H_LOC_SPLIT)));
    while (page != IVFPostingsComponent.NO_PAGE) {
      final BasePage bucket = component.readPage(page);
      final int count = bucket.readInt(B_COUNT);
      for (int i = 0; i < count; i++)
        if (bucket.readLong(B_ENTRIES + i * ENTRY) == position) {
          removeAt(page, i);
          header.writeLong(IVFPostingsComponent.H_LOC_ENTRIES, header.readLong(IVFPostingsComponent.H_LOC_ENTRIES) - 1);
          return true;
        }
      page = bucket.readInt(B_OVERFLOW);
    }
    return false;
  }

  long size() {
    return component.readPage(0).readLong(IVFPostingsComponent.H_LOC_ENTRIES);
  }

  // ---------------------------------------------------------------- internals

  /** Linear hashing: the low {@code level} bits, one more for the buckets the split pointer has already passed. */
  static int bucketOf(final long position, final int level, final int split) {
    final long h = mix(position);
    final int bucket = (int) (h & ((1L << level) - 1));
    return bucket < split ? (int) (h & ((1L << (level + 1)) - 1)) : bucket;
  }

  /** The 64-bit finalizer of MurmurHash3: positions of one bucket page are consecutive, the table wants them spread. */
  private static long mix(long h) {
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= h >>> 33;
    return h;
  }

  private int bucketPage(final int firstDirectoryPage, final int bucket) {
    int directory = firstDirectoryPage;
    int index = bucket;
    while (index >= dirPerPage) {
      directory = component.readPage(directory).readInt(0);
      index -= dirPerPage;
    }
    return component.readPage(directory).readInt(4 + index * 4);
  }

  private void setBucketPage(final int firstDirectoryPage, final int bucket, final int page) {
    int directory = firstDirectoryPage;
    int index = bucket;
    while (index >= dirPerPage) {
      int next = component.readPage(directory).readInt(0);
      if (next == IVFPostingsComponent.NO_PAGE) {
        final MutablePage created = component.allocatePage();
        created.writeInt(0, IVFPostingsComponent.NO_PAGE);
        next = created.getPageId().getPageNumber();
        component.modifyPage(directory).writeInt(0, next);
      }
      directory = next;
      index -= dirPerPage;
    }
    component.modifyPage(directory).writeInt(4 + index * 4, page);
  }

  private MutablePage newBucketPage() {
    final MutablePage page = component.allocatePage();
    page.writeInt(B_COUNT, 0);
    page.writeInt(B_OVERFLOW, IVFPostingsComponent.NO_PAGE);
    return page;
  }

  private void appendToChain(final int lastPage, final long position, final int postingPage, final int entryIndex) {
    MutablePage page = component.modifyPage(lastPage);
    int count = page.readInt(B_COUNT);
    if (count >= perPage) {
      final MutablePage overflow = newBucketPage();
      page.writeInt(B_OVERFLOW, overflow.getPageId().getPageNumber());
      page = overflow;
      count = 0;
    }
    final int offset = B_ENTRIES + count * ENTRY;
    page.writeLong(offset, position);
    page.writeInt(offset + 8, postingPage);
    page.writeInt(offset + 12, entryIndex);
    page.writeInt(B_COUNT, count + 1);
  }

  /** Fills the hole at ({@code page}, {@code index}) with the last entry of the same page: every page of a chain stays packed. */
  private void removeAt(final int page, final int index) {
    final MutablePage target = component.modifyPage(page);
    final int count = target.readInt(B_COUNT);
    final int lastOffset = B_ENTRIES + (count - 1) * ENTRY;
    if (index != count - 1) {
      final int offset = B_ENTRIES + index * ENTRY;
      target.writeLong(offset, target.readLong(lastOffset));
      target.writeInt(offset + 8, target.readInt(lastOffset + 8));
      target.writeInt(offset + 12, target.readInt(lastOffset + 12));
    }
    target.writeInt(B_COUNT, count - 1);
  }

  /** Splits the bucket under the split pointer into itself and bucket {@code split + 2^level}, then advances the pointer. */
  private void splitNext(final MutablePage header, final int directory, final int level, final int split) {
    final int sibling = split + (1 << level);
    // Read the whole chain of the split bucket
    int total = 0;
    int page = bucketPage(directory, split);
    final int first = page;
    while (page != IVFPostingsComponent.NO_PAGE) {
      final BasePage bucket = component.readPage(page);
      total += bucket.readInt(B_COUNT);
      page = bucket.readInt(B_OVERFLOW);
    }
    final long[] positions = new long[total];
    final int[] pages = new int[total];
    final int[] indexes = new int[total];
    int n = 0;
    page = first;
    while (page != IVFPostingsComponent.NO_PAGE) {
      final BasePage bucket = component.readPage(page);
      final int count = bucket.readInt(B_COUNT);
      for (int i = 0; i < count; i++, n++) {
        positions[n] = bucket.readLong(B_ENTRIES + i * ENTRY);
        pages[n] = bucket.readInt(B_ENTRIES + i * ENTRY + 8);
        indexes[n] = bucket.readInt(B_ENTRIES + i * ENTRY + 12);
      }
      final int next = bucket.readInt(B_OVERFLOW);
      // The chain is rewritten from its first page: the overflow pages are emptied and kept linked for the rewrite to reuse
      component.modifyPage(page).writeInt(B_COUNT, 0);
      page = next;
    }

    final MutablePage siblingPage = newBucketPage();
    setBucketPage(directory, sibling, siblingPage.getPageId().getPageNumber());
    final long mask = (1L << (level + 1)) - 1;
    int keepTail = first;
    int moveTail = siblingPage.getPageId().getPageNumber();
    for (int i = 0; i < total; i++) {
      final boolean moves = (mix(positions[i]) & mask) == sibling;
      if (moves)
        moveTail = appendReusing(moveTail, positions[i], pages[i], indexes[i]);
      else
        keepTail = appendReusing(keepTail, positions[i], pages[i], indexes[i]);
    }

    int nextSplit = split + 1;
    int nextLevel = level;
    if (nextSplit == (1 << level)) {
      nextLevel++;
      nextSplit = 0;
    }
    header.writeInt(IVFPostingsComponent.H_LOC_LEVEL, nextLevel);
    header.writeInt(IVFPostingsComponent.H_LOC_SPLIT, nextSplit);
  }

  /** Appends at {@code tail}, moving on to its (emptied) overflow page or a new one when full; answers the new tail. */
  private int appendReusing(final int tail, final long position, final int postingPage, final int entryIndex) {
    MutablePage page = component.modifyPage(tail);
    int count = page.readInt(B_COUNT);
    if (count >= perPage) {
      int next = page.readInt(B_OVERFLOW);
      final MutablePage overflow;
      if (next == IVFPostingsComponent.NO_PAGE) {
        overflow = newBucketPage();
        page.writeInt(B_OVERFLOW, overflow.getPageId().getPageNumber());
      } else
        overflow = component.modifyPage(next);
      page = overflow;
      count = page.readInt(B_COUNT);
    }
    final int offset = B_ENTRIES + count * ENTRY;
    page.writeLong(offset, position);
    page.writeInt(offset + 8, postingPage);
    page.writeInt(offset + 12, entryIndex);
    page.writeInt(B_COUNT, count + 1);
    return page.getPageId().getPageNumber();
  }
}
