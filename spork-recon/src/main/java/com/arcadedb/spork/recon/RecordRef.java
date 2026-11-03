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

/**
 * One id as Recon sends it to hydrate (01-architecture 2.5, 7.2):
 * <ul>
 * <li>an upstream OrientDB RID {@code "168:8"}, stored as {@code upstreamRid = clusterId << 40 | clusterPos} and mapped to its
 * period by {@code recdir};</li>
 * <li>a SPORK {@code record_id} as an unsigned decimal string (it does not fit a JavaScript number), whose high
 * {@value #PERIOD_BITS} bits are the period, so it needs no directory;</li>
 * <li>{@code imp:<importedId>}, an analyst import, scoped to the tenant.</li>
 * </ul>
 * The {@code record_id} bit layout is the working assumption until {@code spork-keys} (WS2) fixes it; {@link #periodOf} is the
 * one place that knows it.
 *
 * @param kind       which form
 * @param value      the packed upstream RID or the record_id; 0 for an import
 * @param importedId the analyst's own id for an import, else null
 */
public record RecordRef(Kind kind, long value, String importedId) {
  public enum Kind {UPSTREAM_RID, RECORD_ID, IMPORTED}

  public static final  String IMPORTED_PREFIX     = "imp:";
  static final         int    PERIOD_BITS         = 16;
  private static final int    MAX_IMPORTED_LENGTH = 256;
  private static final long   MAX_CLUSTER         = (1L << 23) - 1;
  private static final long   MAX_POSITION        = (1L << 40) - 1;

  /** The ref, or null for an id in none of the three forms. */
  public static RecordRef parse(final String id) {
    if (id == null || id.isEmpty())
      return null;
    if (id.startsWith(IMPORTED_PREFIX))
      return parseImported(id.substring(IMPORTED_PREFIX.length()));

    final int colon = id.indexOf(':');
    if (colon < 0) {
      if (id.length() > 20 || !digits(id, 0, id.length()))
        return null;
      try {
        return new RecordRef(Kind.RECORD_ID, Long.parseUnsignedLong(id), null);
      } catch (final NumberFormatException e) {
        return null;
      }
    }
    if (colon == 0 || colon > 7 || id.length() - colon - 1 < 1 || id.length() - colon - 1 > 13 || !digits(id, 0, colon) || !digits(id,
        colon + 1, id.length()))
      return null;
    final long cluster = Long.parseLong(id, 0, colon, 10);
    final long position = Long.parseLong(id, colon + 1, id.length(), 10);
    if (cluster > MAX_CLUSTER || position > MAX_POSITION)
      return null;
    return new RecordRef(Kind.UPSTREAM_RID, (cluster << 40) | position, null);
  }

  /** The literal {@code "cluster:position"} rebuilt from a packed {@code upstreamRid} (2.5). */
  public static String formatUpstreamRid(final long upstreamRid) {
    return (upstreamRid >>> 40) + ":" + (upstreamRid & MAX_POSITION);
  }

  /** The period a {@code record_id} belongs to. */
  public static int periodOf(final long recordId) {
    return (int) (recordId >>> (64 - PERIOD_BITS));
  }

  private static RecordRef parseImported(final String importedId) {
    if (importedId.isEmpty() || importedId.length() > MAX_IMPORTED_LENGTH)
      return null;
    for (int i = 0; i < importedId.length(); i++)
      if (importedId.charAt(i) < 0x20 || importedId.charAt(i) == 0x7f)
        return null;
    return new RecordRef(Kind.IMPORTED, 0L, importedId);
  }

  private static boolean digits(final String s, final int from, final int to) {
    for (int i = from; i < to; i++) {
      final char c = s.charAt(i);
      if (c < '0' || c > '9')
        return false;
    }
    return true;
  }
}
