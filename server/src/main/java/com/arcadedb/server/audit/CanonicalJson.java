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
package com.arcadedb.server.audit;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON, byte for byte what Recon's {@code canonicalJSON} (recon-next {@code src/ops/audit.ts}) writes: object
 * keys sorted by UTF-16 code unit, null members omitted, strings escaped as {@code JSON.stringify} escapes them. Numbers are
 * limited to integers, because Java and JavaScript disagree on how to print a fraction and a hash must not depend on which
 * of them wrote it.
 */
public final class CanonicalJson {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private CanonicalJson() {
  }

  public static String write(final Object value) {
    final StringBuilder out = new StringBuilder(128);
    write(out, value);
    return out.toString();
  }

  private static void write(final StringBuilder out, final Object value) {
    if (value == null || value == JSONObject.NULL)
      out.append("null");
    else if (value instanceof String s)
      quote(out, s);
    else if (value instanceof Boolean b)
      out.append(b.booleanValue());
    else if (value instanceof Number n)
      out.append(integral(n));
    else if (value instanceof JSONObject o)
      writeObject(out, o.toMap(false));
    else if (value instanceof Map<?, ?> m)
      writeObject(out, m);
    else if (value instanceof JSONArray a)
      writeArray(out, a.toList(false));
    else if (value instanceof Collection<?> c)
      writeArray(out, c);
    else if (value instanceof Object[] array)
      writeArray(out, List.of(array));
    else
      throw new IllegalArgumentException("Value of type " + value.getClass().getSimpleName() + " has no canonical JSON form");
  }

  private static void writeObject(final StringBuilder out, final Map<?, ?> map) {
    final List<String> keys = new ArrayList<>(map.size());
    for (final Map.Entry<?, ?> e : map.entrySet())
      if (e.getValue() != null && e.getValue() != JSONObject.NULL)
        keys.add(e.getKey().toString());
    Collections.sort(keys);
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0)
        out.append(',');
      quote(out, keys.get(i));
      out.append(':');
      write(out, map.get(keys.get(i)));
    }
    out.append('}');
  }

  private static void writeArray(final StringBuilder out, final Collection<?> items) {
    out.append('[');
    boolean first = true;
    for (final Object item : items) {
      if (!first)
        out.append(',');
      first = false;
      write(out, item);
    }
    out.append(']');
  }

  private static String integral(final Number n) {
    if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte)
      return n.toString();
    final double d = n.doubleValue();
    if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) <= 9_007_199_254_740_991d)
      return Long.toString((long) d);
    if (n instanceof BigDecimal big && big.stripTrailingZeros().scale() <= 0)
      return big.toBigIntegerExact().toString();
    throw new IllegalArgumentException("Only integral numbers have a canonical form shared with JavaScript: " + n);
  }

  /** {@code JSON.stringify} string escaping: the short escapes, {@code \\u00xx} for other controls and lone surrogates. */
  private static void quote(final StringBuilder out, final String s) {
    out.append('"');
    for (int i = 0, n = s.length(); i < n; i++) {
      final char c = s.charAt(i);
      switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\b' -> out.append("\\b");
      case '\f' -> out.append("\\f");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      case '\t' -> out.append("\\t");
      default -> {
        if (c < 0x20 || (Character.isSurrogate(c) && !pairedSurrogate(s, i)))
          out.append("\\u").append(HEX[c >> 12 & 15]).append(HEX[c >> 8 & 15]).append(HEX[c >> 4 & 15]).append(HEX[c & 15]);
        else
          out.append(c);
      }
      }
    }
    out.append('"');
  }

  private static boolean pairedSurrogate(final String s, final int i) {
    final char c = s.charAt(i);
    if (Character.isHighSurrogate(c))
      return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
    return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
  }
}
