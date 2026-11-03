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
package com.arcadedb.graph;

import com.arcadedb.database.*;
import com.arcadedb.serializer.BinaryTypes;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arcadedb.schema.Property.RID_PROPERTY;

public class MutableEdgeSegment extends BaseRecord implements EdgeSegment, RecordInternal {
  public static final byte RECORD_TYPE = 3;
  public static final int CONTENT_START_POSITION =
          Binary.BYTE_SERIALIZED_SIZE + Binary.INT_SERIALIZED_SIZE + BinaryTypes.getTypeSize(BinaryTypes.TYPE_RID);
  private static final int PREVIOUS_OFFSET = Binary.BYTE_SERIALIZED_SIZE + Binary.INT_SERIALIZED_SIZE;
  private final RID NULL_RID;

  private int bufferSize;
  // A VIEW ON A CACHED PAGE READ FOR A WALK (see view()): WRITING THROUGH IT WOULD WRITE THE PAGE CACHE
  private final boolean readOnly;

  public MutableEdgeSegment(final Database database, final RID rid) {
    super(database, rid, null);
    NULL_RID = new RID(-1, -1);
    this.buffer = null;
    this.readOnly = false;
  }

  public MutableEdgeSegment(final Database database, final RID rid, final Binary buffer) {
    this(database, rid, buffer, false);
  }

  private MutableEdgeSegment(final Database database, final RID rid, final Binary buffer, final boolean readOnly) {
    super(database, rid, buffer);
    NULL_RID = new RID(-1, -1);
    this.buffer = buffer;
    this.readOnly = readOnly;
    if (buffer != null) {
      this.buffer.setAutoResizable(false);
      this.bufferSize = buffer.size();
    }
  }

  public MutableEdgeSegment(final DatabaseInternal database, final int bufferSize) {
    super(database, null, new Binary(bufferSize));
    NULL_RID = new RID(-1, -1);
    this.readOnly = false;
    this.buffer.setAutoResizable(false);
    this.bufferSize = bufferSize;
    buffer.putByte(0, RECORD_TYPE);
    buffer.putInt(Binary.BYTE_SERIALIZED_SIZE, CONTENT_START_POSITION);
    buffer.position(Binary.BYTE_SERIALIZED_SIZE + Binary.INT_SERIALIZED_SIZE);
    database.getSerializer().serializeValue(database, buffer, BinaryTypes.TYPE_RID, NULL_RID); // NEXT
  }

  /**
   * A read-only segment over {@code content}, a view of the page the chunk is stored in rather than a copy of it: for the walks,
   * which read and never write (retrieval speed, S7). Every mutator refuses on it.
   */
  public static MutableEdgeSegment view(final Database database, final RID rid, final Binary content) {
    return new MutableEdgeSegment(database, rid, content, true);
  }

  /** True for a {@link #view} of a page, which nothing ever writes. */
  boolean isReadOnlyView() {
    return readOnly;
  }

  private void checkWritable() {
    if (readOnly)
      throw new IllegalStateException("Edge segment " + rid + " is a read-only view of its page");
  }

  @Override
  public EdgeSegment copy() {
    return new MutableEdgeSegment(database, rid, buffer.copyOfContent());
  }

  /** A copy of what this segment holds, to give back with {@link #restoreContent} (issue #10346). */
  public Binary snapshotContent() {
    return buffer.copyOfContent();
  }

  /**
   * Gives this segment back, in place, the content {@link #snapshotContent} copied: a statement that appended to the object the
   * transaction queued for its commit, and was taken back, leaves it holding edges that exist no more.
   */
  public void restoreContent(final Binary snapshot) {
    checkWritable();
    final Binary restored = snapshot.copyOfContent();
    restored.setAutoResizable(false);
    this.buffer = restored;
  }

  @Override
  public byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * If there is room in the current segment, adds the RID at the beginning of the list. This allows to maintain the list of edges always ordered
   * by descending insertion time.
   *
   * @param edgeRID
   * @param vertexRID
   * @return
   */
  @Override
  public boolean add(final RID edgeRID, final RID vertexRID) {
    checkWritable();
    final Binary ridSerialized = database.getContext().getTemporaryBuffer1();
    database.getSerializer().serializeValue(database, ridSerialized, BinaryTypes.TYPE_COMPRESSED_RID, edgeRID);
    database.getSerializer().serializeValue(database, ridSerialized, BinaryTypes.TYPE_COMPRESSED_RID, vertexRID);

    final int used = getUsed();

    final int ridSerializedSize = ridSerialized.size();

    if (used + ridSerializedSize <= bufferSize) {
      // APPEND AT THE BEGINNING OF THE CURRENT CHUNK
      buffer.move(CONTENT_START_POSITION, CONTENT_START_POSITION + ridSerializedSize, used - CONTENT_START_POSITION);

      buffer.putByteArray(CONTENT_START_POSITION, ridSerialized.getContent(), ridSerialized.getContentBeginOffset(),
              ridSerializedSize);

      // UPDATE USED BYTES
      setUsed(used + ridSerializedSize);

      return true;
    }

    // NO ROOM
    return false;
  }

  /**
   * Appends the edge at the end of the segment in O(1) without shifting existing content.
   * Ideal for bulk import where insertion order within a segment is irrelevant.
   */
  @Override
  public boolean addAtEnd(final RID edgeRID, final RID vertexRID) {
    checkWritable();
    final Binary ridSerialized = database.getContext().getTemporaryBuffer1();
    database.getSerializer().serializeValue(database, ridSerialized, BinaryTypes.TYPE_COMPRESSED_RID, edgeRID);
    database.getSerializer().serializeValue(database, ridSerialized, BinaryTypes.TYPE_COMPRESSED_RID, vertexRID);

    final int used = getUsed();
    final int ridSerializedSize = ridSerialized.size();

    if (used + ridSerializedSize <= bufferSize) {
      // APPEND AT THE END (NO SHIFT NEEDED)
      buffer.putByteArray(used, ridSerialized.getContent(), ridSerialized.getContentBeginOffset(), ridSerializedSize);
      setUsed(used + ridSerializedSize);
      return true;
    }

    // NO ROOM
    return false;
  }

  /**
   * Fast path for bulk import: writes 4 zigzag+VLQ encoded numbers directly into the segment buffer
   * without creating RID objects, temporary buffers, or going through the serializer dispatch.
   * Typically 3-5x faster than addAtEnd(RID, RID) for the encoding step.
   */
  @Override
  public boolean addAtEndDirect(final int edgeBucketId, final long edgePosition,
      final int vertexBucketId, final long vertexPosition) {
    checkWritable();
    // Pre-calculate total bytes needed (avoids writing then rolling back if no room)
    final int bytesNeeded = Binary.getNumberSpace(edgeBucketId)
        + Binary.getNumberSpace(edgePosition)
        + Binary.getNumberSpace(vertexBucketId)
        + Binary.getNumberSpace(vertexPosition);

    final int used = getUsed();
    if (used + bytesNeeded > bufferSize)
      return false;

    // Write directly at the end position using inlined VLQ encoding
    buffer.position(used);
    buffer.putNumber(edgeBucketId);
    buffer.putNumber(edgePosition);
    buffer.putNumber(vertexBucketId);
    buffer.putNumber(vertexPosition);
    setUsed(used + bytesNeeded);

    return true;
  }

  @Override
  public int addManyAtEndDirect(final int[] edgeBucketIds, final long[] edgePositions,
      final int[] vertexBucketIds, final long[] vertexPositions, final int from, final int to) {
    checkWritable();
    int used = getUsed();
    final int limit = bufferSize;
    int written = 0;

    for (int i = from; i < to; i++) {
      final int bytesNeeded = Binary.getNumberSpace(edgeBucketIds[i])
          + Binary.getNumberSpace(edgePositions[i])
          + Binary.getNumberSpace(vertexBucketIds[i])
          + Binary.getNumberSpace(vertexPositions[i]);

      if (used + bytesNeeded > limit)
        break;

      buffer.position(used);
      buffer.putNumber(edgeBucketIds[i]);
      buffer.putNumber(edgePositions[i]);
      buffer.putNumber(vertexBucketIds[i]);
      buffer.putNumber(vertexPositions[i]);
      used += bytesNeeded;
      written++;
    }

    if (written > 0)
      setUsed(used);

    return written;
  }

  @Override
  public boolean containsEdge(final RID rid) {
    final int used = getUsed();
    if (used == 0)
      return false;

    final int bucketId = rid.getBucketId();
    final long position = rid.getPosition();

    // ABSOLUTE READS: A PAGE VIEW SHARED BY SEVERAL READERS IS NEVER MOVED UNDER THEM (S7)
    int cursor = CONTENT_START_POSITION;
    while (cursor < used) {
      final int currEdgeBucketId = (int) buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      final long currEdgePosition = buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      if (currEdgeBucketId == bucketId && currEdgePosition == position)
        return true;

      // SKIP VERTEX RID
      cursor += buffer.getNumberSizeAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
    }

    return false;
  }

  @Override
  public RID getFirstEdgeConnectedToVertex(final RID rid, final int[] edgeBucketFilter) {
    final int used = getUsed();
    if (used == 0)
      return null;

    final int bucketId = rid.getBucketId();
    final long position = rid.getPosition();

    int cursor = CONTENT_START_POSITION;
    while (cursor < used) {
      final int currEdgeBucketId = (int) buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      final long currEdgePositionInBucket = buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);

      final int currVertexBucketId = (int) buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      final long currVertexPosition = buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      if (currVertexBucketId == bucketId && currVertexPosition == position) {
        if (edgeBucketFilter != null) {
          // FILTER BY EDGE BUCKETS
          for (int i = 0; i < edgeBucketFilter.length; i++) {
            if (currEdgeBucketId == edgeBucketFilter[i])
              return new RID(currEdgeBucketId, currEdgePositionInBucket);
          }
        } else
          return new RID(currEdgeBucketId, currEdgePositionInBucket);
      }
    }

    return null;
  }

  @Override
  public JSONObject toJSON(final boolean includeMetadata) {
    final JSONObject json = new JSONObject();
    final RID identity = getIdentity();
    if (includeMetadata && identity != null)
      json.put(RID_PROPERTY, identity.toString());
    final int used = getUsed();
    if (used > 0) {
      final JSONArray entries = new JSONArray();

      final long[] entry = new long[4];
      int cursor = CONTENT_START_POSITION;
      while (cursor < used) {
        cursor = nextEntry(cursor, entry);
        final String edge = "#" + entry[0] + ":" + entry[1];
        final String vertex = "#" + entry[2] + ":" + entry[3];
        entries.put(new JSONObject().put("edge", edge).put("vertex", vertex));
      }

      if (!entries.isEmpty())
        json.put("entries", entries);
    }
    return json;
  }

  @Override
  public boolean removeEntry(final int currentPosition, final int nextItemPosition) {
    checkWritable();
    int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return false;

    if (currentPosition > used)
      return false;

    if (used - nextItemPosition < 0) {
      // CORRUPTION, UPDATE THE BOUNDARIES
      setUsed(currentPosition);
      buffer.position(currentPosition);
      return false;
    }

    if (used - nextItemPosition > 0)
      // MOVE THE ENTIRE BUFFER FROM THE NEXT ITEM TO THE CURRENT ONE
      buffer.move(nextItemPosition, currentPosition, used - nextItemPosition);

    used -= nextItemPosition - currentPosition;
    setUsed(used);

    buffer.position(currentPosition);

    return true;
  }

  @Override
  public int removeEdge(final RID rid) {
    checkWritable();
    int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return 0;

    final int bucketId = rid.getBucketId();
    final long position = rid.getPosition();

    buffer.position(CONTENT_START_POSITION);

    int found = 0;
    while (buffer.position() < used) {
      final int lastPos = buffer.position();

      final int currEdgeBucketId = (int) buffer.getNumber();
      final long currEdgePosition = buffer.getNumber();

      buffer.getNumber();
      buffer.getNumber();

      if (currEdgeBucketId == bucketId && currEdgePosition == position) {
        // FOUND MOVE THE ENTIRE BUFFER FROM THE NEXT ITEM TO THE CURRENT ONE
        buffer.move(buffer.position(), lastPos, used - buffer.position());

        used -= buffer.position() - lastPos;
        setUsed(used);

        buffer.position(lastPos);
        ++found;
        break;
      }
    }

    return found;
  }

  @Override
  public int removeVertex(final RID rid) {
    checkWritable();
    int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return 0;

    final int bucketId = rid.getBucketId();
    final long position = rid.getPosition();

    buffer.position(CONTENT_START_POSITION);

    int found = 0;
    while (buffer.position() < used) {
      final int lastPos = buffer.position();

      buffer.getNumber();
      buffer.getNumber();

      final int currVertexBucketId = (int) buffer.getNumber();
      final long currVertexPosition = buffer.getNumber();

      if (currVertexBucketId == bucketId && currVertexPosition == position) {
        // FOUND MOVE THE ENTIRE BUFFER FROM THE NEXT ITEM TO THE CURRENT ONE
        buffer.move(buffer.position(), lastPos, used - buffer.position());

        used -= buffer.position() - lastPos;
        setUsed(used);

        buffer.position(lastPos);
        ++found;
        break;
      }
    }

    return found;
  }

  @Override
  public boolean containsLightEdge(final int edgeTypeBucketId, final RID vertexRID) {
    final int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return false;

    final int vertexBucketId = vertexRID.getBucketId();
    final long vertexPosition = vertexRID.getPosition();

    final long[] entry = new long[4];
    int cursor = CONTENT_START_POSITION;
    while (cursor < used) {
      cursor = nextEntry(cursor, entry);
      if (entry[0] == edgeTypeBucketId && entry[1] < 0 && entry[2] == vertexBucketId && entry[3] == vertexPosition)
        return true;
    }

    return false;
  }

  @Override
  public int countLightEdges(final int edgeTypeBucketId, final RID vertexRID) {
    final int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return 0;

    final int vertexBucketId = vertexRID.getBucketId();
    final long vertexPosition = vertexRID.getPosition();

    int count = 0;
    final long[] entry = new long[4];
    int cursor = CONTENT_START_POSITION;
    while (cursor < used) {
      cursor = nextEntry(cursor, entry);
      if (entry[0] == edgeTypeBucketId && entry[1] < 0 && entry[2] == vertexBucketId && entry[3] == vertexPosition)
        ++count;
    }
    return count;
  }

  @Override
  public int removeLightEdge(final int edgeTypeBucketId, final RID vertexRID) {
    checkWritable();
    int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return 0;

    final int vertexBucketId = vertexRID.getBucketId();
    final long vertexPosition = vertexRID.getPosition();

    buffer.position(CONTENT_START_POSITION);

    while (buffer.position() < used) {
      final int lastPos = buffer.position();

      final int currEdgeBucketId = (int) buffer.getNumber();
      final long currEdgePosition = buffer.getNumber();
      final int currVertexBucketId = (int) buffer.getNumber();
      final long currVertexPosition = buffer.getNumber();

      if (currEdgeBucketId == edgeTypeBucketId && currEdgePosition < 0 && currVertexBucketId == vertexBucketId
          && currVertexPosition == vertexPosition) {
        // FOUND MOVE THE ENTIRE BUFFER FROM THE NEXT ITEM TO THE CURRENT ONE
        buffer.move(buffer.position(), lastPos, used - buffer.position());

        used -= buffer.position() - lastPos;
        setUsed(used);

        buffer.position(lastPos);
        return 1;
      }
    }

    return 0;
  }

  @Override
  public long count(final EdgeBucketMask edgeMask) {
    long total = 0;

    final int used = getUsed();
    if (used > 0) {
      int cursor = CONTENT_START_POSITION;
      while (cursor < used) {
        final long edgeBucketId = buffer.getNumberAt(cursor);
        cursor += buffer.getNumberSizeAt(cursor);
        // SKIP EDGE RID POSITION AND VERTEX RID
        cursor += buffer.getNumberSizeAt(cursor);
        cursor += buffer.getNumberSizeAt(cursor);
        cursor += buffer.getNumberSizeAt(cursor);

        if (edgeMask == null || edgeMask.matches(edgeBucketId))
          ++total;
      }
    }

    return total;
  }

  @Override
  public void countInto(final EdgeBucketMask[] edgeMasks, final EdgeBucketMask[] neighborMasks, final boolean[] skipSelfLoops,
      final RID owner, final long[] counts) {
    // A FLAG WITHOUT ITS OWNER WOULD SILENTLY COUNT THE SELF LOOPS IT ASKS TO LEAVE OUT
    if (skipSelfLoops != null && owner == null)
      throw new IllegalArgumentException("Self loops cannot be left out of the count without the vertex owning the list");

    final int used = getUsed();
    if (used <= CONTENT_START_POSITION)
      return;

    final int filters = edgeMasks.length;
    // THE POSITION OF THE FAR END IS DECODED ONLY WHEN A FILTER HAS TO TELL A SELF LOOP APART
    final boolean checkSelfLoops = skipSelfLoops != null;
    final long ownerBucketId = checkSelfLoops ? owner.getBucketId() : -1;
    final long ownerPosition = checkSelfLoops ? owner.getPosition() : -1;
    // AN ENTRY IS FOUR NUMBERS, IN THE ORDER add() WRITES THEM: EDGE BUCKET, EDGE POSITION, VERTEX BUCKET, VERTEX POSITION
    int cursor = CONTENT_START_POSITION;
    while (cursor < used) {
      final long edgeBucketId = buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor); // EDGE POSITION: NEGATIVE FOR A LIGHT EDGE, IRRELEVANT TO THE COUNT
      final long vertexBucketId = buffer.getNumberAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      final boolean selfLoop = checkSelfLoops && buffer.getNumberAt(cursor) == ownerPosition && vertexBucketId == ownerBucketId;
      cursor += buffer.getNumberSizeAt(cursor); // VERTEX POSITION

      for (int i = 0; i < filters; i++)
        if ((edgeMasks[i] == null || edgeMasks[i].matches(edgeBucketId))
            && (neighborMasks[i] == null || neighborMasks[i].matches(vertexBucketId)) && !(selfLoop && skipSelfLoops[i]))
          ++counts[i];
    }
  }

  /**
   * Hands every entry of this segment to {@code visitor} as the four numbers it is stored as, newest first like every walk,
   * decoding no RID (issue #11012).
   */
  public void forEachEntry(final EdgeListScanner.EntryVisitor visitor) {
    final int used = getUsed();
    final long[] entry = new long[4];
    int position = CONTENT_START_POSITION;
    while (position < used) {
      position = nextEntry(position, entry);
      visitor.visit((int) entry[0], entry[1], (int) entry[2], entry[3]);
    }
  }

  @Override
  public EdgeSegment getPrevious() {
    final RID previousRID = getPreviousRID();
    return previousRID == null ? null : (EdgeSegment) database.lookupByRID(previousRID, true);
  }

  @Override
  public EdgeSegment previousForWalk() {
    final RID previousRID = getPreviousRID();
    return previousRID == null ? null : EdgeLinkedList.readChunkForWalk((DatabaseInternal) database, previousRID);
  }

  @Override
  public RID getPreviousRID() {
    // NOTE: the on-disk pointer is historically labelled "NEXT" but it links to the PREVIOUS chunk in list
    // order (the list is stored head-first, newest chunk first). Read as the two fixed-width numbers it is, at their
    // offsets, without the serializer and without moving the position (S7).
    final int bucketId = buffer.getInt(PREVIOUS_OFFSET);
    final long position = buffer.getLong(PREVIOUS_OFFSET + Binary.INT_SERIALIZED_SIZE);
    if (bucketId == -1 && position == -1)
      return null;
    return RID.create(database, bucketId, position);
  }

  @Override
  public boolean hasPrevious() {
    return buffer.getInt(PREVIOUS_OFFSET) != -1 || buffer.getLong(PREVIOUS_OFFSET + Binary.INT_SERIALIZED_SIZE) != -1;
  }

  @Override
  public void setPrevious(final EdgeSegment previous) {
    setPrevious(previous.getIdentity());
  }

  /**
   * Links to the previous chunk by its RID alone, for a caller that knows it without having the chunk loaded (GraphBatch, #9575).
   */
  public void setPrevious(final RID previous) {
    checkWritable();
    if (previous == null)
      throw new IllegalArgumentException("Previous chunk is not persistent");
    buffer.position(Binary.BYTE_SERIALIZED_SIZE + Binary.INT_SERIALIZED_SIZE);
    database.getSerializer().serializeValue(database, buffer, BinaryTypes.TYPE_RID, previous); // PREVIOUS
  }

  @Override
  public Binary getContent() {
    buffer.position(bufferSize);
    buffer.flip();
    return buffer;
  }

  @Override
  public int getUsed() {
    return buffer.getInt(Binary.BYTE_SERIALIZED_SIZE);
  }

  private void setUsed(final int size) {
    buffer.putInt(Binary.BYTE_SERIALIZED_SIZE, size);
  }

  @Override
  public RID getRID(final AtomicInteger currentPosition) {
    int cursor = currentPosition.get();
    final int bucketId = (int) buffer.getNumberAt(cursor);
    cursor += buffer.getNumberSizeAt(cursor);
    final long position = buffer.getNumberAt(cursor);
    cursor += buffer.getNumberSizeAt(cursor);
    currentPosition.set(cursor);
    return RID.create(database, bucketId, position);
  }

  @Override
  public int nextEntry(final int position, final long[] entry) {
    final ByteBuffer bytes = buffer.getByteBuffer();
    int cursor = position;
    try {
      for (int number = 0; number < 4; number++) {
        final long b0 = bytes.get(cursor++);
        long raw;
        if ((b0 & 0x80L) == 0L)
          // SINGLE-BYTE QUANTITY: THE COMMON CASE OF A BUCKET ID AND OF A SMALL POSITION
          raw = b0;
        else {
          raw = b0 & 0x7FL;
          int shift = 7;
          long b;
          while (((b = bytes.get(cursor++)) & 0x80L) != 0L) {
            raw |= (b & 0x7FL) << shift;
            shift += 7;
            if (shift > 63)
              throw new IllegalArgumentException(
                  "Variable length quantity is too long (must be <= 63) at position " + position + " in buffer of size " + bufferSize);
          }
          raw |= b << shift;
        }
        // ZIGZAG DECODE, AS Binary.getNumber() DOES
        final long temp = (((raw << 63) >> 63) ^ raw) >> 1;
        entry[number] = temp ^ (raw & (1L << 63));
      }
    } catch (final IndexOutOfBoundsException e) {
      throw new IllegalArgumentException("Invalid position " + position + " (size=" + bytes.limit() + ")", e);
    }
    return cursor;
  }

  @Override
  public int nextEntryInBuckets(final int position, final EdgeBucketMask bucketMask) {
    final int used = getUsed();
    int entryStart = position;

    while (entryStart < used) {
      if (bucketMask.matches(buffer.getNumberAt(entryStart)))
        return entryStart;

      // SKIP EDGE BUCKET, EDGE POSITION AND VERTEX RID
      int cursor = entryStart + buffer.getNumberSizeAt(entryStart);
      cursor += buffer.getNumberSizeAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      cursor += buffer.getNumberSizeAt(cursor);
      entryStart = cursor;
    }

    return used;
  }

  @Override
  public void skipRID(final AtomicInteger currentPosition) {
    int cursor = currentPosition.get();
    cursor += buffer.getNumberSizeAt(cursor);
    cursor += buffer.getNumberSizeAt(cursor);
    currentPosition.set(cursor);
  }

  @Override
  public int getRecordSize() {
    return buffer.size();
  }

  @Override
  public void setIdentity(final RID rid) {
    this.rid = rid;
  }

  @Override
  public void unsetDirty() {
    // IGNORE THIS FLAG
  }

  public boolean isEmpty() {
    return getUsed() <= CONTENT_START_POSITION;
  }
}
