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

import com.arcadedb.spork.keys.UpstreamRid;

/**
 * One id as Recon sends it to hydrate (01-architecture 2.5, 7.2):
 * <ul>
 * <li>an upstream OrientDB RID {@code "168:8"}, stored as {@code upstreamRid = clusterId << 40 | clusterPos} and mapped to its
 * period by {@code recdir};</li>
 * <li>a SPORK {@code record_id} as an unsigned decimal string (it does not fit a JavaScript number), whose high bits are
 * the period, so it needs no directory;</li>
 * <li>{@code imp:<importedId>}, an analyst import, scoped to the tenant.</li>
 * </ul>
 * The packing of both is {@code spork-keys}' ({@code UpstreamRid}, {@code RecordId}); this class only tells the forms apart.
 *
 * @param kind       which form
 * @param value      the packed upstream RID or the record_id; 0 for an import
 * @param importedId the analyst's own id for an import, else null
 */
public record RecordRef(Kind kind, long value, String importedId) {
  public enum Kind {UPSTREAM_RID, RECORD_ID, IMPORTED}

  public static final  String IMPORTED_PREFIX     = "imp:";
  private static final int    MAX_IMPORTED_LENGTH = 256;

  /** The ref, or null for an id in none of the three forms. */
  public static RecordRef parse(final String id) {
    if (id == null || id.isEmpty())
      return null;
    if (id.startsWith(IMPORTED_PREFIX))
      return parseImported(id.substring(IMPORTED_PREFIX.length()));

    if (id.indexOf(':') >= 0) {
      final long upstreamRid = UpstreamRid.parse(id);
      return upstreamRid != UpstreamRid.INVALID ? new RecordRef(Kind.UPSTREAM_RID, upstreamRid, null) : null;
    }
    if (id.length() > 20 || !digits(id))
      return null;
    try {
      return new RecordRef(Kind.RECORD_ID, Long.parseUnsignedLong(id), null);
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  private static RecordRef parseImported(final String importedId) {
    if (importedId.isEmpty() || importedId.length() > MAX_IMPORTED_LENGTH)
      return null;
    for (int i = 0; i < importedId.length(); i++)
      if (importedId.charAt(i) < 0x20 || importedId.charAt(i) == 0x7f)
        return null;
    return new RecordRef(Kind.IMPORTED, 0L, importedId);
  }

  private static boolean digits(final String s) {
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c < '0' || c > '9')
        return false;
    }
    return true;
  }
}
