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

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.log.LogManager;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.utility.ResettableIterator;

import java.util.ConcurrentModificationException;
import java.util.logging.Level;

public abstract class ResettableIteratorBase<T> implements ResettableIterator<T> {
  protected final DatabaseInternal database;
  private final   EdgeSegment      initialContainer;
  protected       EdgeSegment      currentContainer;
  // THE POSITION OF THE NEXT ENTRY IN currentContainer: A PLAIN int, READ AND WRITTEN BY THIS THREAD ONLY (S6)
  protected       int              currentPosition;
  // SCRATCH FOR THE FOUR NUMBERS OF AN ENTRY, SEE EdgeSegment.nextEntry(): ONE PER ITERATOR, NOT ONE PER ENTRY
  protected final long[]           entry           = new long[4];
  protected       int              browsed         = 0;
  private         int              neighborBucketId = -1;
  private         long             neighborPosition = -1;
  private         boolean          neighborFiltered = false;
  private         ChainCycleGuard  cycleGuard;
  // THE ENTRY THE LAST next() RETURNED, SEE entryReturned(): ITS RIDS ARE DECODED ONLY WHEN remove() NEEDS THEM
  private         EdgeSegment      returnedContainer;
  private         int              returnedBegin;
  private         int              returnedEnd;
  private         RID              returnedEdge;
  private         RID              returnedVertex;
  // WHERE THE EDGE POINTER OF THE ENTRY THE LAST next() RETURNED SITS, SEE lastEntryAt()
  private         EdgeSegment      lastEntryContainer;
  private         int              lastEntryPosition;
  // SET WHEN THE WALKED LIST WAS CHANGED BY SOMETHING ELSE DURING A remove(): THE ITERATOR'S VIEW OF IT IS STALE
  private         boolean          invalidated;
  // SET ONCE AN UNLINK MOVED THE WALK ONTO THE TRANSACTION'S OWN COPY OF THE SEGMENT, SEE entryReturned(EdgeSegment, int, int)
  private         boolean          walkingWrittenCopy;
  // THE LIST THIS ITERATION READS, WHICH COUNTS THE TWINS OF ITS LIGHTWEIGHT EDGES WHEN ASKED (ISSUE #10008)
  private         EdgeLinkedList   censusSource;

  protected ResettableIteratorBase(final DatabaseInternal database, final EdgeSegment current) {
    if (current == null)
      throw new IllegalArgumentException("Edge chunk is null");
    this.database = database;
    this.initialContainer = current;
    this.currentContainer = walkable(current);
    this.currentPosition = MutableEdgeSegment.CONTENT_START_POSITION;
  }

  /**
   * The segment a walk reads: the segment itself, or a PRIVATE COPY of it when the transaction has written it (issue #10308).
   * <p>
   * A segment the transaction wrote is the transaction's own cached instance, and a lookup hands that very instance to the walk.
   * Whatever the transaction does to the list afterwards then happens under the walk's position: an edge removed from the segment
   * moves the entries behind it back, so every second entry is never reached, and an edge added moves them forward, so some are
   * reached twice. A segment the transaction has not written is not at risk: its first write goes to a copy of the page, and the
   * walk goes on reading the version it started on.
   * <p>
   * Nothing walked a list while writing to it as long as the Gremlin neighbor expansion read the whole list up front; now the walk
   * is lazy and a traversal can drop what it walks.
   * <p>
   * The identity test relies on {@code lookupByRID} answering the transaction's cached instance for a record it has written, which
   * is what {@code getWrittenRecord} holds. The price is one copy of the segment, bounded by its size, per walk that reaches a
   * segment the transaction wrote. And the walk no longer sees an edge the transaction adds to that segment after it reached it: it
   * reads the list as it stood when it got there.
   */
  private static EdgeSegment walkable(final EdgeSegment segment) {
    return segment != null && isTransactionsWrittenRecord(segment) ? segment.copy() : segment;
  }

  /** Tells whether {@code segment} is the very instance the transaction holds as its written copy of the chunk. */
  private static boolean isTransactionsWrittenRecord(final EdgeSegment segment) {
    final RID rid = segment.getIdentity();
    if (rid == null)
      return false;
    final TransactionContext tx = ((DatabaseInternal) segment.getDatabase()).getTransactionIfExists();
    return tx != null && tx.hasWrittenRecords() && tx.getWrittenRecord(rid) == segment;
  }

  final void setCensusSource(final EdgeLinkedList list) {
    this.censusSource = list;
  }

  /** The census the lightweight edges handed out by this iteration share, or null when the iteration has none. */
  protected final LightEdgeCensus lightEdgeCensus() {
    return censusSource != null ? censusSource.lightEdgeCensus() : null;
  }

  /**
   * Restricts the iteration to the entries whose neighbour vertex is the given one.
   * <p>
   * Both the edge pointer and the neighbour pointer are stored inline in the segment, so the
   * neighbour is already in hand when an entry is examined. Rejecting on it costs two primitive
   * comparisons and, crucially, happens <b>before</b> the edge record is materialised. That is what
   * makes an "is A connected to B" probe over a super-node cost a scan of the edge list rather than
   * one record load - plus one property deserialization - per edge in it. The scan itself is
   * unchanged: the segment still yields an RID per entry whether or not the filter is set.
   *
   * @param neighbor the only neighbour vertex to accept, or {@code null} to iterate everything
   */
  public void setNeighborVertexFilter(final RID neighbor) {
    this.neighborFiltered = neighbor != null;
    this.neighborBucketId = neighbor != null ? neighbor.getBucketId() : -1;
    this.neighborPosition = neighbor != null ? neighbor.getPosition() : -1;
  }

  /** Tells whether the entry just read out of the segment survives the neighbour filter. */
  protected final boolean matchesNeighborFilter(final RID neighbor) {
    return !neighborFiltered
        || (neighbor != null && neighbor.getBucketId() == neighborBucketId && neighbor.getPosition() == neighborPosition);
  }

  /** {@link #matchesNeighborFilter(RID)} on the two numbers of the neighbour pointer, before any RID is built for it. */
  protected final boolean matchesNeighborFilter(final long bucketId, final long position) {
    return !neighborFiltered || (bucketId == neighborBucketId && position == neighborPosition);
  }

  @Override
  public void reset() {
    this.currentContainer = walkable(initialContainer);
    cycleGuard = null;
    currentPosition = MutableEdgeSegment.CONTENT_START_POSITION;
    walkingWrittenCopy = false;
    browsed = 0;
    returnedContainer = null;
    lastEntryContainer = null;
  }

  /**
   * Remembers where the entry {@code next()} is about to hand out begins, so its edge pointer can be decoded on demand by
   * {@link #lastEntryEdgeRID()}. The iterators that walk the vertex pointers only skip the edge pointer on the raw numbers
   * of the segment (issue #8417), and so pay for its decoding only when somebody asks for it (issue #10148).
   */
  protected final void lastEntryAt(final EdgeSegment container, final int entryBegin) {
    lastEntryContainer = container;
    lastEntryPosition = entryBegin;
  }

  /** The edge pointer of the entry registered by {@link #lastEntryAt}, decoded now. */
  protected final RID lastEntryEdgeRID() {
    if (lastEntryContainer == null)
      throw new IllegalStateException("No entry: next() has not returned one yet");
    lastEntryContainer.nextEntry(lastEntryPosition, entry);
    return RID.create(lastEntryContainer.getDatabase(), (int) entry[0], entry[1]);
  }

  /**
   * Records the entry {@code next()} is about to hand out, the one a following {@link #removeReturnedEntry} is about.
   * Not the cursor: a {@code hasNext()} in between peeks the entry after it, possibly in another segment (#9715).
   */
  protected final void entryReturned(final EdgeSegment container, final int begin, final int end, final RID edge,
      final RID vertex) {
    returnedContainer = container;
    returnedBegin = begin;
    returnedEnd = end;
    returnedEdge = edge;
    returnedVertex = vertex;
  }

  /**
   * {@link #entryReturned(EdgeSegment, int, int, RID, RID)} for a walk that did not build the entry's RIDs: they are decoded
   * from {@code container} at {@code begin} if and when {@link #removeReturnedEntry} asks for them (S6). The bytes there do not
   * move in the meantime when the container is this walk's private view or copy, whose only writer is the unlink itself. When it
   * is the transaction's own written copy of the chunk, which the transaction can shift before {@code remove()}, they are decoded
   * now.
   */
  protected final void entryReturned(final EdgeSegment container, final int begin, final int end) {
    entryReturned(container, begin, end, null, null);
    // ON THE TRANSACTION'S LIVE COPY OF THE CHUNK (WHERE AN UNLINK MOVES THE WALK, OR A CHUNK THE CALLER HANDED IN THAT THE
    // TRANSACTION WROTE SINCE) THE TRANSACTION ITSELF CAN SHIFT THE ENTRIES BETWEEN next() AND remove(): THERE THE RIDS ARE CAPTURED
    // NOW, SO THAT remove() CAN TELL THE ENTRY MOVED INSTEAD OF DELETING WHATEVER SITS AT ITS OLD POSITION. THE FLAG ALONE IS NOT
    // TRUSTED, THE INSTANCE IS ASKED; A PAGE VIEW IS NEVER WRITTEN, SO A WALK OVER VIEWS SKIPS THE TRANSACTION LOOKUP
    if (container == currentContainer && (walkingWrittenCopy || (!isReadOnlyView(container) && isTransactionsWrittenRecord(
        container))))
      decodeReturnedEntry();
  }

  private static boolean isReadOnlyView(final EdgeSegment segment) {
    return segment instanceof MutableEdgeSegment mutable && mutable.isReadOnlyView();
  }

  /** The edge RID of the returned entry, decoded on first use. */
  private RID returnedEdgeRID() {
    if (returnedEdge == null)
      decodeReturnedEntry();
    return returnedEdge;
  }

  /** The vertex RID of the returned entry, decoded on first use. */
  private RID returnedVertexRID() {
    if (returnedVertex == null)
      decodeReturnedEntry();
    return returnedVertex;
  }

  private void decodeReturnedEntry() {
    returnedContainer.nextEntry(returnedBegin, entry);
    returnedEdge = RID.create(returnedContainer.getDatabase(), (int) entry[0], entry[1]);
    returnedVertex = RID.create(returnedContainer.getDatabase(), (int) entry[2], entry[3]);
  }

  /**
   * {@code Iterator.remove()} of every iterator over an edge list: DELETES the edge of the entry the last {@code next()}
   * returned, then takes the entry out of the list being walked (#9779).
   * <p>
   * The delete goes through the database like {@code edge.delete()} does - the {@code DELETE_RECORD} check, the index
   * cleanup, the delete events, the disconnection from the far endpoint - and anything it raises reaches the caller
   * with the entry still in place. A refused delete used to be logged and dropped, and the entry unlinked anyway: a
   * LIGHTWEIGHT edge the user may not delete was gone from this side and still listed on the other, a record-backed
   * one was left alive and reachable from one endpoint only.
   * <p>
   * The delete skips THIS list ({@code listOwner}, {@code listDirection}) because the entry is taken out of it here, by
   * position. Removing it by RID as well removed it twice: once the iterator's own copy of the segment was the one the
   * transaction held, the positional removal took out the entry AFTER it, dropping a sibling edge.
   * <p>
   * Only an entry that names no edge is unlinked without a delete, because unlinking it is the repair: its edge record
   * is gone, its slot holds a record that is not an edge (which must not be deleted through it), or its lightweight
   * type no longer exists.
   *
   * @throws IllegalStateException         when {@code next()} returned nothing since the last removal
   * @throws UnsupportedOperationException when the iterator does not know whose list it walks
   */
  protected final void removeReturnedEntry(final RID listOwner, final Vertex.DIRECTION listDirection) {
    checkNotInvalidated();
    if (listOwner == null || listDirection == null)
      throw new UnsupportedOperationException("remove() on an edge list iterator that does not know the vertex it walks");
    if (returnedContainer == null)
      throw new IllegalStateException("No entry to remove: next() has not been called since the last remove()");

    final EdgeSegment container = returnedContainer;
    final DatabaseInternal database = (DatabaseInternal) container.getDatabase();
    // THE TRANSACTION'S COPY, NOT THE WALKED ONE: A SEGMENT THE TRANSACTION HAD WRITTEN IS WALKED AS A PRIVATE COPY (see walkable())
    final int usedBefore = ((EdgeSegment) database.lookupByRID(container.getIdentity(), true)).getUsed();

    if (!deleteEdgeOfEntry(database, listOwner, listDirection)) {
      // VETOED BY A BEFORE-DELETE LISTENER: NOTHING WAS DELETED, SO THE ENTRY STAYS, AS WITH edge.delete()
      returnedContainer = null;
      return;
    }

    // THE DELETE RUNS THE DELETE LISTENERS AND TRIGGERS, AND THOSE CAN WRITE TO THIS VERY LIST (A CASCADE DELETE, A NEW
    // EDGE). THE POSITION IS TRUSTED ONLY ON THE TRANSACTION'S CURRENT COPY OF THE SEGMENT, AND ONLY IF THAT COPY STILL
    // HOLDS THE ENTRY THERE WITH NOTHING ELSE MOVED
    final EdgeSegment current = (EdgeSegment) database.lookupByRID(container.getIdentity(), true);
    if (current.getUsed() == usedBefore && holdsReturnedEntryAt(current))
      unlinkReturnedEntry(current);
    else {
      removeReturnedEntryByIdentity(current);
      // THE ITERATOR'S POSITIONS NO LONGER DESCRIBE THE LIST: FAIL FAST, AS java.util COLLECTIONS DO WHEN MODIFIED OUTSIDE
      // THEIR ITERATOR
      invalidated = true;
    }
  }

  /**
   * Takes the entry the last {@code next()} returned out of the list being walked, and nothing else: the second half of
   * {@link #removeReturnedEntry}, for a repair that has to prune an entry whose edge cannot be deleted.
   */
  protected final void unlinkReturnedEntry() {
    checkNotInvalidated();
    if (returnedContainer == null)
      throw new IllegalStateException("No entry to remove: next() has not been called since the last remove()");
    // THE TRANSACTION'S COPY, NOT THE WALKED ONE, WHICH IS A READ-ONLY VIEW OF ITS PAGE (S7) OR A PRIVATE COPY (see walkable());
    // AND BY POSITION ONLY IF THAT COPY STILL HOLDS THE ENTRY THERE, AS removeReturnedEntry() DOES
    final DatabaseInternal database = (DatabaseInternal) returnedContainer.getDatabase();
    final EdgeSegment current = (EdgeSegment) database.lookupByRID(returnedContainer.getIdentity(), true);
    if (holdsReturnedEntryAt(current))
      unlinkReturnedEntry(current);
    else {
      removeReturnedEntryByIdentity(current);
      invalidated = true;
    }
  }

  /** Throws when a {@code remove()} found the walked list changed under it, see {@link #removeReturnedEntry}. */
  protected final void checkNotInvalidated() {
    if (invalidated)
      throw new ConcurrentModificationException(
          "The edge list was modified during remove(), by a delete listener or trigger: open a new iterator to go on walking it");
  }

  /** Removes the returned entry, by position, from {@code target}: the iterator's own copy of the segment or the transaction's. */
  private void unlinkReturnedEntry(final EdgeSegment target) {
    final EdgeSegment container = returnedContainer;
    target.removeEntry(returnedBegin, returnedEnd);
    // NOT AN APPEND: THE PAGE MUST LEAVE THE EDGE-APPEND MERGE, OR A REBASE ON A CONCURRENT COMMIT REPLAYS THIS
    // TRANSACTION'S APPENDS TO THE SEGMENT AND DROPS THE REMOVAL, WHILE THE EDGE RECORD STAYS DELETED (#10375)
    EdgeLinkedList.updateRewrittenSegment((DatabaseInternal) target.getDatabase(), target);
    returnedContainer = null;

    // A hasNext() PEEK MAY HAVE MOVED THE CURSOR TO AN OLDER SEGMENT ALREADY: THEN NO POSITION IT HOLDS IS IN THIS ONE,
    // AND NOTHING NEEDS TO MOVE
    if (container == currentContainer) {
      // GO ON ON THE COPY THAT WAS WRITTEN
      currentContainer = target;
      walkingWrittenCopy = true;
      // THE ENTRIES AFTER THE REMOVED ONE SHIFTED BACK, THE CURSOR AND ANY ENTRY ALREADY PEEKED WITH THEM
      final int removed = returnedEnd - returnedBegin;
      currentPosition -= removed;
      entriesShiftedBack(removed);
    }
  }

  private boolean holdsReturnedEntryAt(final EdgeSegment segment) {
    if (returnedEnd > segment.getUsed())
      return false;
    final RID edge = returnedEdgeRID();
    final RID vertex = returnedVertexRID();
    final int end = segment.nextEntry(returnedBegin, entry);
    return end == returnedEnd && entry[0] == edge.getBucketId() && entry[1] == edge.getPosition()
        && entry[2] == vertex.getBucketId() && entry[3] == vertex.getPosition();
  }

  /** The returned entry wherever it now is in {@code segment}: entries never move to another segment. */
  private void removeReturnedEntryByIdentity(final EdgeSegment segment) {
    final RID edge = returnedEdgeRID();
    final RID vertex = returnedVertexRID();
    returnedContainer = null;
    final int used = segment.getUsed();
    int position = MutableEdgeSegment.CONTENT_START_POSITION;
    while (position < used) {
      final int begin = position;
      position = segment.nextEntry(position, entry);
      if (entry[0] == edge.getBucketId() && entry[1] == edge.getPosition() && entry[2] == vertex.getBucketId()
          && entry[3] == vertex.getPosition()) {
        segment.removeEntry(begin, position);
        // THE LISTENER THAT SENT THE REMOVAL HERE MAY HAVE APPENDED TO THIS VERY SEGMENT: SEE unlinkReturnedEntry (#10375)
        EdgeLinkedList.updateRewrittenSegment((DatabaseInternal) segment.getDatabase(), segment);
        return;
      }
    }
    // ALREADY GONE: WHATEVER CHANGED THE LIST TOOK IT OUT TOO
  }

  /**
   * Called when {@link #unlinkReturnedEntry} shifted the entries of {@link #currentContainer} that follow the removed one
   * back by {@code removed} bytes: an iterator holding the position of an entry it already peeked moves it too.
   */
  protected void entriesShiftedBack(final int removed) {
    // NOTHING TO MOVE BY DEFAULT: AN ITERATOR THAT DOES NOT PEEK HOLDS NO POSITION BUT THE CURSOR, ALREADY SHIFTED
  }

  /** @return false when a before-delete listener vetoed the delete, true when the entry is to be taken out */
  private boolean deleteEdgeOfEntry(final DatabaseInternal database, final RID listOwner, final Vertex.DIRECTION listDirection) {
    final RID returnedEdge = returnedEdgeRID();
    final RID returnedVertex = returnedVertexRID();
    if (returnedEdge.getPosition() < 0) {
      final DocumentType edgeType = database.getSchema().getTypeByBucketId(returnedEdge.getBucketId());
      if (edgeType == null) {
        logStaleEntry(listOwner, listDirection, "its lightweight edge type no longer exists");
        return true;
      }
      final Edge edge = listDirection == Vertex.DIRECTION.OUT ?
          new ImmutableLightEdge(database, edgeType, returnedEdge.getBucketId(), listOwner, returnedVertex) :
          new ImmutableLightEdge(database, edgeType, returnedEdge.getBucketId(), returnedVertex, listOwner);
      return database.deleteEdgeSkippingEndpoint(edge, listOwner, listDirection);
    }

    final Record record;
    try {
      // LOADED, NOT LAZY: A LAZY HANDLE DOES NOT PROVE THE RECORD EXISTS, AND THE DELETE READS THE CONTENT ANYWAY
      record = database.lookupByRID(returnedEdge, true);
    } catch (final RecordNotFoundException e) {
      logStaleEntry(listOwner, listDirection, "its edge record no longer exists");
      return true;
    }

    if (record instanceof Edge edge)
      // THE EDGE'S OWN DATABASE HANDLE, SO A WRAPPED INSTANCE (SERVER, REPLICATED) SEES THE DELETE GO THROUGH ITS PIPELINE
      return ((DatabaseInternal) edge.getDatabase()).deleteEdgeSkippingEndpoint(edge, listOwner, listDirection);

    // A RECORD THAT IS NOT AN EDGE MUST NOT BE DELETED THROUGH A STALE ENTRY
    logStaleEntry(listOwner, listDirection, "its slot holds a record that is not an edge");
    return true;
  }

  /** A stale entry is repaired by the unlink alone; said at FINE so a quiet repair of corruption can still be traced. */
  private void logStaleEntry(final RID listOwner, final Vertex.DIRECTION listDirection, final String reason) {
    LogManager.instance()
        .log(this, Level.FINE, "Removing edge entry %s from the %s list of vertex %s without a delete: %s", returnedEdge, listDirection,
            listOwner, reason);
  }

  /**
   * Hops {@link #currentContainer} to the previous chunk in the edge list and sets {@link #currentPosition} on its first
   * entry. Guards against a chunk whose {@code previous} pointer names itself: {@code EdgeLinkedList.containsEdge},
   * {@code containsVertex}, {@code containsLightEdge} and {@code getFirstEdgeConnectedToVertex} have always broken
   * out of such a chain, and so, since #6276, has {@link EdgeVertexIterator}. This is that same guard, lifted here
   * so every chunk-hopping iterator gets it once instead of drifting copies (#6278) - {@link EdgeIterator},
   * {@link VertexIterator}, {@link RIDIterator} and {@link IteratorFilterBase} all call this instead of hopping
   * {@code currentContainer} directly.
   * <p>
   * A longer cycle (A to B to A) ends the walk the same way, detected by a {@link ChainCycleGuard} created on the first
   * hop (#8713).
   * <p>
   * On such a chain the corruption ENDS the walk silently, matching every other consumer of this guard: none of
   * them logs, so a self-loop is reported by {@code CHECK DATABASE} - which is what a corrupted database calls for
   * a diagnosis - rather than by an ordinary traversal.
   * <p>
   * The chunk is read as a view of its cached page, not as a copy of it, through {@link EdgeLinkedList#readChunkForWalk}
   * (S7): a walk reads and never writes the chunks it hops to.
   *
   * @return the new {@link #currentContainer}, or {@code null} when the walk has reached the head of the list or
   * ended early on a self-referencing chunk
   */
  protected final EdgeSegment moveToPreviousChunk() {
    final RID currentIdentity = currentContainer.getIdentity();
    if (cycleGuard == null)
      cycleGuard = new ChainCycleGuard(initialContainer.getIdentity());
    final RID previousRID = currentContainer.getPreviousRID();
    if (previousRID == null) {
      currentContainer = null;
      return null;
    }
    // THE CHUNK'S "previous" POINTER NAMES ITSELF, OR THE CHAIN CLOSES ON A CHUNK ALREADY WALKED: STOP INSTEAD OF LOOPING FOREVER
    if ((currentIdentity != null && currentIdentity.equals(previousRID)) || cycleGuard.revisits(previousRID)) {
      currentContainer = null;
      return null;
    }
    currentContainer = walkable(currentContainer.previousForWalk());
    currentPosition = MutableEdgeSegment.CONTENT_START_POSITION;
    walkingWrittenCopy = false;
    return currentContainer;
  }

  @Override
  public long countEntries() {
    long total = browsed;

    final EdgeSegment savedContainer = currentContainer;
    final int savedCurrentPosition = currentPosition;
    final int savedBrowsed = browsed;
    final ChainCycleGuard savedGuard = cycleGuard == null ? null : cycleGuard.copy();
    // THE COUNT'S CHUNK HOPS CLEAR IT, AND THE CURSOR GOES BACK ON THE CHUNK IT DESCRIBES
    final boolean savedWalkingWrittenCopy = walkingWrittenCopy;
    // THE COUNT WALKS THROUGH next(): THE ENTRY A FOLLOWING remove() IS ABOUT MUST SURVIVE IT
    final EdgeSegment savedReturnedContainer = returnedContainer;
    final int savedReturnedBegin = returnedBegin;
    final int savedReturnedEnd = returnedEnd;
    final RID savedReturnedEdge = returnedEdge;
    final RID savedReturnedVertex = returnedVertex;

    try {
      while (hasNext()) {
        next();
        ++total;
      }
    } finally {
      // RESTORE SAVED POSITION
      currentContainer = savedContainer;
      currentPosition = savedCurrentPosition;
      browsed = savedBrowsed;
      cycleGuard = savedGuard;
      walkingWrittenCopy = savedWalkingWrittenCopy;
      returnedContainer = savedReturnedContainer;
      returnedBegin = savedReturnedBegin;
      returnedEnd = savedReturnedEnd;
      returnedEdge = savedReturnedEdge;
      returnedVertex = savedReturnedVertex;
    }

    return total;
  }

  @Override
  public long getBrowsed() {
    return browsed;
  }
}
