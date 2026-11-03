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
package com.arcadedb.spork.keys;

/**
 * The upstream OrientDB RID of a migrated record as one LONG (01-architecture.md 2.5): {@code clusterId << 40 | clusterPos},
 * UNIQUE HASH on every {@code rec_<p>} and in {@code recdir}. The cluster id takes the 23 bits left above the 40-bit position,
 * so every packed value is non-negative and {@link #INVALID} (-1) can mean "not a RID" without boxing.
 */
public final class UpstreamRid {
  public static final long INVALID      = -1L;
  public static final int  POSITION_BITS = 40;
  public static final long MAX_CLUSTER  = (1L << (63 - POSITION_BITS)) - 1;
  public static final long MAX_POSITION = (1L << POSITION_BITS) - 1;

  private UpstreamRid() {
  }

  /** {@code "168:8"} or {@code "#168:8"} to the packed LONG, {@link #INVALID} for anything else or out of range. */
  public static long parse(final String rid) {
    if (rid == null)
      return INVALID;
    final int start = rid.startsWith("#") ? 1 : 0;
    final int colon = rid.indexOf(':', start);
    if (colon <= start || colon == rid.length() - 1)
      return INVALID;
    final long cluster = digits(rid, start, colon, MAX_CLUSTER);
    final long position = digits(rid, colon + 1, rid.length(), MAX_POSITION);
    if (cluster < 0 || position < 0)
      return INVALID;
    return pack(cluster, position);
  }

  public static long pack(final long clusterId, final long position) {
    if (clusterId < 0 || clusterId > MAX_CLUSTER || position < 0 || position > MAX_POSITION)
      throw new IllegalArgumentException("RID out of range: " + clusterId + ":" + position);
    return clusterId << POSITION_BITS | position;
  }

  public static int clusterId(final long packed) {
    return (int) (packed >>> POSITION_BITS);
  }

  public static long position(final long packed) {
    return packed & MAX_POSITION;
  }

  /** The literal {@code "168:8"} rebuilt on output. */
  public static String format(final long packed) {
    return clusterId(packed) + ":" + position(packed);
  }

  /** The non-negative decimal value of {@code s[from, to)}, -1 when not all ASCII digits or above {@code max}. */
  private static long digits(final String s, final int from, final int to, final long max) {
    if (to - from > 19)
      return -1;
    long v = 0;
    for (int i = from; i < to; i++) {
      final char c = s.charAt(i);
      if (!JsText.isAsciiDigit(c))
        return -1;
      v = v * 10 + (c - '0');
      if (v > max)
        return -1;
    }
    return v;
  }
}
