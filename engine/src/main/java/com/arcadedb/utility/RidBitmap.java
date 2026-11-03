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
package com.arcadedb.utility;

import com.arcadedb.database.BasicDatabase;
import com.arcadedb.database.RID;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Compressed set of RIDs with fast set algebra: the storage behind selections (docs/spork/03-f1-selections-design.md).
 * <p>
 * A RID's position is {@code pageId * maxRecordsInPage + slot} and can pass 2^32 in one large bucket, while a
 * {@link RoaringBitmap} holds 32-bit values. So the set is a sorted array of 64-bit keys
 * {@code (bucketId << 32) | (position >>> 32)}, each owning one RoaringBitmap of the low 32 position bits. In practice that
 * is one key per bucket. Keys and bitmaps live in parallel primitive arrays and the last key hit is remembered, so
 * {@link #add} and {@link #contains} neither box nor search on the common path of consecutive RIDs from one bucket.
 * <p>
 * Invariant: no empty bitmap is ever stored, so two sets with the same members are {@link #equals equal}.
 * <p>
 * Not thread-safe.
 */
public final class RidBitmap implements Iterable<RID> {
  private static final byte FORMAT_VERSION = 1;

  @FunctionalInterface
  public interface RidConsumer {
    void accept(int bucketId, long position);
  }

  private long[]         keys    = new long[4];
  private RoaringBitmap[] chunks = new RoaringBitmap[4];
  private int            size;
  private int            lastIndex;

  public boolean add(final RID rid) {
    return add(rid.getBucketId(), rid.getPosition());
  }

  public boolean add(final int bucketId, final long position) {
    if (bucketId < 0 || position < 0)
      throw new IllegalArgumentException("A RidBitmap holds record addresses only, got #" + bucketId + ":" + position);

    final long key = key(bucketId, position);
    int index = indexOf(key);
    if (index < 0) {
      index = -(index + 1);
      insertAt(index, key, new RoaringBitmap());
      lastIndex = index;
    }
    return chunks[index].checkedAdd((int) position);
  }

  public boolean remove(final RID rid) {
    final int bucketId = rid.getBucketId();
    final long position = rid.getPosition();
    if (bucketId < 0 || position < 0)
      return false;

    final int index = indexOf(key(bucketId, position));
    if (index < 0)
      return false;

    final boolean removed = chunks[index].checkedRemove((int) position);
    if (chunks[index].isEmpty())
      removeAt(index);
    return removed;
  }

  public boolean contains(final RID rid) {
    return contains(rid.getBucketId(), rid.getPosition());
  }

  public boolean contains(final int bucketId, final long position) {
    if (bucketId < 0 || position < 0)
      return false;
    final int index = indexOf(key(bucketId, position));
    return index >= 0 && chunks[index].contains((int) position);
  }

  public long cardinality() {
    long total = 0;
    for (int i = 0; i < size; i++)
      total += chunks[i].getLongCardinality();
    return total;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** The distinct buckets holding a member, ascending, read off the keys without walking a single member. */
  public int[] bucketIds() {
    final int[] buckets = new int[size];
    int n = 0;
    for (int i = 0; i < size; i++) {
      final int bucketId = (int) (keys[i] >>> 32);
      if (n == 0 || buckets[n - 1] != bucketId)
        buckets[n++] = bucketId;
    }
    return n == size ? buckets : Arrays.copyOf(buckets, n);
  }

  public RidBitmap copy() {
    final RidBitmap copy = new RidBitmap();
    copy.ensureCapacity(size);
    for (int i = 0; i < size; i++) {
      copy.keys[i] = keys[i];
      copy.chunks[i] = chunks[i].clone();
    }
    copy.size = size;
    return copy;
  }

  /** Converts the bitmaps to run containers where that is smaller. Worth calling once on a set that is kept or serialized. */
  public void runOptimize() {
    for (int i = 0; i < size; i++)
      chunks[i].runOptimize();
  }

  // ---- static set operations: new result, inputs untouched ----

  public static RidBitmap and(final RidBitmap a, final RidBitmap b) {
    final RidBitmap result = new RidBitmap();
    int i = 0, j = 0;
    while (i < a.size && j < b.size) {
      final long ka = a.keys[i], kb = b.keys[j];
      if (ka < kb)
        i++;
      else if (ka > kb)
        j++;
      else {
        result.appendIfNotEmpty(ka, RoaringBitmap.and(a.chunks[i], b.chunks[j]));
        i++;
        j++;
      }
    }
    return result;
  }

  public static RidBitmap or(final RidBitmap a, final RidBitmap b) {
    final RidBitmap result = new RidBitmap();
    result.ensureCapacity(a.size + b.size);
    int i = 0, j = 0;
    while (i < a.size || j < b.size) {
      if (j >= b.size || (i < a.size && a.keys[i] < b.keys[j])) {
        result.appendIfNotEmpty(a.keys[i], a.chunks[i].clone());
        i++;
      } else if (i >= a.size || b.keys[j] < a.keys[i]) {
        result.appendIfNotEmpty(b.keys[j], b.chunks[j].clone());
        j++;
      } else {
        result.appendIfNotEmpty(a.keys[i], RoaringBitmap.or(a.chunks[i], b.chunks[j]));
        i++;
        j++;
      }
    }
    return result;
  }

  public static RidBitmap andNot(final RidBitmap a, final RidBitmap b) {
    final RidBitmap result = new RidBitmap();
    result.ensureCapacity(a.size);
    for (int i = 0; i < a.size; i++) {
      final int j = b.indexOf(a.keys[i]);
      result.appendIfNotEmpty(a.keys[i], j < 0 ? a.chunks[i].clone() : RoaringBitmap.andNot(a.chunks[i], b.chunks[j]));
    }
    return result;
  }

  public static RidBitmap xor(final RidBitmap a, final RidBitmap b) {
    final RidBitmap result = new RidBitmap();
    result.ensureCapacity(a.size + b.size);
    int i = 0, j = 0;
    while (i < a.size || j < b.size) {
      if (j >= b.size || (i < a.size && a.keys[i] < b.keys[j])) {
        result.appendIfNotEmpty(a.keys[i], a.chunks[i].clone());
        i++;
      } else if (i >= a.size || b.keys[j] < a.keys[i]) {
        result.appendIfNotEmpty(b.keys[j], b.chunks[j].clone());
        j++;
      } else {
        result.appendIfNotEmpty(a.keys[i], RoaringBitmap.xor(a.chunks[i], b.chunks[j]));
        i++;
        j++;
      }
    }
    return result;
  }

  /** The size of {@code and(a, b)} without building it. */
  public static long andCardinality(final RidBitmap a, final RidBitmap b) {
    long total = 0;
    int i = 0, j = 0;
    while (i < a.size && j < b.size) {
      final long ka = a.keys[i], kb = b.keys[j];
      if (ka < kb)
        i++;
      else if (ka > kb)
        j++;
      else {
        total += RoaringBitmap.andCardinality(a.chunks[i], b.chunks[j]);
        i++;
        j++;
      }
    }
    return total;
  }

  // ---- in-place set operations: this is the result, other is untouched ----

  public RidBitmap andWith(final RidBitmap other) {
    int kept = 0;
    for (int i = 0; i < size; i++) {
      final int j = other.indexOf(keys[i]);
      if (j < 0)
        continue;
      chunks[i].and(other.chunks[j]);
      if (!chunks[i].isEmpty()) {
        keys[kept] = keys[i];
        chunks[kept++] = chunks[i];
      }
    }
    truncate(kept);
    return this;
  }

  public RidBitmap orWith(final RidBitmap other) {
    for (int j = 0; j < other.size; j++) {
      final int i = indexOf(other.keys[j]);
      if (i >= 0)
        chunks[i].or(other.chunks[j]);
      else
        insertAt(-(i + 1), other.keys[j], other.chunks[j].clone());
    }
    return this;
  }

  public RidBitmap andNotWith(final RidBitmap other) {
    int kept = 0;
    for (int i = 0; i < size; i++) {
      final int j = other.indexOf(keys[i]);
      if (j >= 0)
        chunks[i].andNot(other.chunks[j]);
      if (!chunks[i].isEmpty()) {
        keys[kept] = keys[i];
        chunks[kept++] = chunks[i];
      }
    }
    truncate(kept);
    return this;
  }

  public RidBitmap xorWith(final RidBitmap other) {
    for (int j = 0; j < other.size; j++) {
      final int i = indexOf(other.keys[j]);
      if (i >= 0) {
        chunks[i].xor(other.chunks[j]);
        if (chunks[i].isEmpty())
          removeAt(i);
      } else
        insertAt(-(i + 1), other.keys[j], other.chunks[j].clone());
    }
    return this;
  }

  // ---- iteration ----

  /** Visits every member in bucket, then position, order without allocating a RID per member. */
  public void forEach(final RidConsumer consumer) {
    for (int i = 0; i < size; i++) {
      final int bucketId = (int) (keys[i] >>> 32);
      final long high = (keys[i] & 0xFFFF_FFFFL) << 32;
      final IntIterator it = chunks[i].getIntIterator();
      while (it.hasNext())
        consumer.accept(bucketId, high | (it.next() & 0xFFFF_FFFFL));
    }
  }

  /** Members as bare RIDs, in bucket, then position, order. */
  @Override
  public Iterator<RID> iterator() {
    return iterator(null);
  }

  /** Members as RIDs bound to {@code database} (so they can be loaded), in bucket, then position, order. */
  public Iterator<RID> iterator(final BasicDatabase database) {
    return new Iterator<>() {
      private int         index = -1;
      private int         bucketId;
      private long        high;
      private IntIterator current;

      @Override
      public boolean hasNext() {
        while (current == null || !current.hasNext()) {
          if (++index >= size)
            return false;
          bucketId = (int) (keys[index] >>> 32);
          high = (keys[index] & 0xFFFF_FFFFL) << 32;
          current = chunks[index].getIntIterator();
        }
        return true;
      }

      @Override
      public RID next() {
        if (!hasNext())
          throw new NoSuchElementException();
        return RID.create(database, bucketId, high | (current.next() & 0xFFFF_FFFFL));
      }
    };
  }

  // ---- serialization ----

  /**
   * A version byte, the key count, then per key the 64-bit key and the bitmap in RoaringBitmap's portable format, which
   * the RoaringBitmap ports in other languages (the {@code roaring} npm package among them) read as is.
   */
  public byte[] toBytes() {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(estimateSerializedSize());
    try (final DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(FORMAT_VERSION);
      out.writeInt(size);
      for (int i = 0; i < size; i++) {
        out.writeLong(keys[i]);
        chunks[i].serialize(out);
      }
    } catch (final IOException e) {
      // NOT REACHABLE: A BYTE ARRAY STREAM DOES NOT FAIL
      throw new IllegalStateException("Cannot serialize the RID bitmap", e);
    }
    return bytes.toByteArray();
  }

  public static RidBitmap fromBytes(final byte[] bytes) {
    try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      final byte version = in.readByte();
      if (version != FORMAT_VERSION)
        throw new IllegalArgumentException("Unsupported RID bitmap format version " + version);
      final int count = in.readInt();
      if (count < 0)
        throw new IllegalArgumentException("Corrupted RID bitmap: negative key count " + count);

      final RidBitmap result = new RidBitmap();
      result.ensureCapacity(count);
      long previous = Long.MIN_VALUE;
      for (int i = 0; i < count; i++) {
        final long key = in.readLong();
        if (key <= previous)
          throw new IllegalArgumentException("Corrupted RID bitmap: keys out of order");
        previous = key;
        final RoaringBitmap chunk = new RoaringBitmap();
        chunk.deserialize(in);
        result.appendIfNotEmpty(key, chunk);
      }
      return result;
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final IOException | RuntimeException e) {
      // TRUNCATED INPUT (EOFException) OR A CONTAINER ROARING ITSELF REFUSES (InvalidRoaringFormat): BAD INPUT, NOT A SERVER FAULT
      throw new IllegalArgumentException("Corrupted RID bitmap", e);
    }
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o)
      return true;
    if (!(o instanceof RidBitmap other) || size != other.size)
      return false;
    for (int i = 0; i < size; i++)
      if (keys[i] != other.keys[i] || !chunks[i].equals(other.chunks[i]))
        return false;
    return true;
  }

  @Override
  public int hashCode() {
    int hash = size;
    for (int i = 0; i < size; i++)
      hash = 31 * (31 * hash + Long.hashCode(keys[i])) + chunks[i].hashCode();
    return hash;
  }

  @Override
  public String toString() {
    return "RidBitmap{cardinality=" + cardinality() + ", keys=" + size + "}";
  }

  // ---- internals ----

  private static long key(final int bucketId, final long position) {
    return ((long) bucketId << 32) | (position >>> 32);
  }

  /** The index of {@code key}, or {@code -(insertionPoint + 1)} as {@link Arrays#binarySearch} answers. */
  private int indexOf(final long key) {
    if (lastIndex < size && keys[lastIndex] == key)
      return lastIndex;
    final int index = Arrays.binarySearch(keys, 0, size, key);
    if (index >= 0)
      lastIndex = index;
    return index;
  }

  private void insertAt(final int index, final long key, final RoaringBitmap chunk) {
    ensureCapacity(size + 1);
    System.arraycopy(keys, index, keys, index + 1, size - index);
    System.arraycopy(chunks, index, chunks, index + 1, size - index);
    keys[index] = key;
    chunks[index] = chunk;
    size++;
  }

  private void removeAt(final int index) {
    System.arraycopy(keys, index + 1, keys, index, size - index - 1);
    System.arraycopy(chunks, index + 1, chunks, index, size - index - 1);
    chunks[--size] = null;
  }

  /** Appends in key order; callers feed keys ascending. */
  private void appendIfNotEmpty(final long key, final RoaringBitmap chunk) {
    if (chunk.isEmpty())
      return;
    ensureCapacity(size + 1);
    keys[size] = key;
    chunks[size++] = chunk;
  }

  private void truncate(final int newSize) {
    Arrays.fill(chunks, newSize, size, null);
    size = newSize;
  }

  private void ensureCapacity(final int capacity) {
    if (capacity <= keys.length)
      return;
    final int newCapacity = Math.max(capacity, keys.length * 2);
    keys = Arrays.copyOf(keys, newCapacity);
    chunks = Arrays.copyOf(chunks, newCapacity);
  }

  private int estimateSerializedSize() {
    int total = 5;
    for (int i = 0; i < size; i++)
      total += 8 + chunks[i].serializedSizeInBytes();
    return total;
  }
}
