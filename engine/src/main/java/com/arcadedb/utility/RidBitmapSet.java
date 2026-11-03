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

import com.arcadedb.database.RID;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;

/**
 * A read-only {@code Set<RID>} view over a {@link RidBitmap}, so a saved selection can be handed to every API that takes an
 * allow-list of RIDs (the {@code RIDFilter} of an indexed function, a vector index's pre-filter) without copying it into a
 * {@code HashSet} at about 100 bytes per member (docs/spork/03-f1-selections-design.md, 2.5). {@link #contains} is a bitmap
 * probe and {@link #size} its cardinality; iteration is in bucket, then position, order.
 * <p>
 * The view does not copy the bitmap, which its holder must therefore not change afterwards.
 */
public final class RidBitmapSet extends AbstractSet<RID> {
  private final RidBitmap bitmap;
  private final int       size;

  public RidBitmapSet(final RidBitmap bitmap) {
    this.bitmap = bitmap;
    this.size = (int) Math.min(Integer.MAX_VALUE, bitmap.cardinality());
  }

  public RidBitmap bitmap() {
    return bitmap;
  }

  /** The buckets that hold a member, ascending: what lets a search skip every other bucket without walking the members. */
  public int[] bucketIds() {
    return bitmap.bucketIds();
  }

  @Override
  public boolean contains(final Object o) {
    return o instanceof RID rid && bitmap.contains(rid);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public Iterator<RID> iterator() {
    // RidBitmap's iterator does not support remove(), which keeps the view read-only through it as well
    return bitmap.iterator();
  }

  @Override
  public boolean add(final RID rid) {
    throw new UnsupportedOperationException("A selection's RID set is read-only");
  }

  @Override
  public boolean remove(final Object o) {
    throw new UnsupportedOperationException("A selection's RID set is read-only");
  }

  @Override
  public boolean retainAll(final Collection<?> c) {
    throw new UnsupportedOperationException("A selection's RID set is read-only");
  }

  @Override
  public boolean removeAll(final Collection<?> c) {
    throw new UnsupportedOperationException("A selection's RID set is read-only");
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException("A selection's RID set is read-only");
  }
}
