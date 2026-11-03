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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.structured.RecordFilter;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.utility.RidHashSet;

import java.util.Arrays;

/**
 * What a person may see of the corpus (01-architecture 5.3), as the structured-search core's per-record filter: a record whose
 * markings carry a bit the person does not hold, or an analyst import of another tenant, is absent from rows and counts, not
 * redacted.
 * <p>
 * {@link #forPartition} adds what a count needs to stay on the index: the RIDs of the partition that this person cannot see,
 * read from the sparse {@code (markings)} and {@code (tenantId)} indexes of schema v1 (only marked records and imports are in
 * them). An entry outside that set is visible without reading its record. When the set would exceed {@link #MAX_HIDDEN}, or the
 * partition lacks the sparse indexes, every counted entry is read instead: slower, never wrong.
 */
final class RecordVisibility implements RecordFilter {
  static final String RECORD_TYPE = "Record";

  static final int MAX_HIDDEN = 100_000;

  private final String tenant;
  private final long   heldMarkings;

  RecordVisibility(final String tenant, final long heldMarkings) {
    this.tenant = tenant;
    this.heldMarkings = heldMarkings;
  }

  @Override
  public boolean accepts(final Document record) {
    final String owner = record.getString("tenantId");
    if (owner != null && !owner.equals(tenant))
      return false;
    final long markings = record.get("markings") instanceof Number n ? n.longValue() : 0L;
    return (markings & ~heldMarkings) == 0L;
  }

  /** This rule with the partition's hidden RIDs, so a count reads only the records it may have to hide. */
  RecordFilter forPartition(final Database partition) {
    final DocumentType type = partition.getSchema().getType(RECORD_TYPE);
    final TypeIndex markings = single(type, "markings");
    final TypeIndex tenants = single(type, "tenantId");
    if (markings == null || tenants == null)
      return this;

    final RidHashSet hidden = new RidHashSet();
    // markings other than 0 that carry a bit the person does not hold
    if (!collect(markings.range(true, new Object[] { Long.MIN_VALUE }, true, new Object[] { -1L }, true), hidden, true)
        || !collect(markings.range(true, new Object[] { 1L }, true, new Object[] { Long.MAX_VALUE }, true), hidden, true))
      return this;
    // imports of every other tenant
    final IndexCursor imports = tenants.iterator(true);
    if (!collect(imports, hidden, false))
      return this;

    final RecordVisibility rule = this;
    return new RecordFilter() {
      @Override
      public boolean accepts(final Document record) {
        return rule.accepts(record);
      }

      @Override
      public boolean mustRead(final RID rid) {
        return hidden.contains(rid);
      }
    };
  }

  /** False when the set grew past {@link #MAX_HIDDEN}. */
  private boolean collect(final IndexCursor cursor, final RidHashSet hidden, final boolean byMarkings) {
    try {
      while (cursor.hasNext()) {
        final RID rid = cursor.next().getIdentity();
        final Object key = cursor.getKeys()[0];
        final boolean hide = byMarkings ? key instanceof Number n && (n.longValue() & ~heldMarkings) != 0L : !tenant.equals(key);
        if (hide && hidden.add(rid) && hidden.size() > MAX_HIDDEN)
          return false;
      }
      return true;
    } finally {
      cursor.close();
    }
  }

  private static TypeIndex single(final DocumentType type, final String property) {
    for (final Index index : type.getAllIndexes(false))
      if (index instanceof TypeIndex typeIndex && typeIndex.supportsOrderedIterations() && typeIndex.getPropertyNames()
          .equals(Arrays.asList(property)))
        return typeIndex;
    return null;
  }
}
