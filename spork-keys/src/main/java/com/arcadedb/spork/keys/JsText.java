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

import java.util.Locale;

/**
 * The JavaScript string primitives Recon's normalizations are written in, with JavaScript's semantics rather than Java's:
 * JS {@code \s} and {@code trim()} cover the Unicode space separators, U+FEFF and the line terminators but not the C0
 * controls Java's {@code String.trim()} strips; JS {@code \d} and {@code \D} are ASCII only. Hand-written loops, no regex,
 * so a key costs one pass and at most one allocation.
 */
final class JsText {
  private JsText() {
  }

  /** JS {@code \s}: WhiteSpace plus LineTerminator of ECMA-262 (the same set {@code String.prototype.trim} removes). */
  static boolean isWhitespace(final char c) {
    switch (c) {
    case '\t', '\n', '\u000b', '\f', '\r', ' ', '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff':
      return true;
    default:
      return c >= '\u2000' && c <= '\u200a';
    }
  }

  static boolean isAsciiDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  /** {@code s.trim()} */
  static String trim(final String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isWhitespace(s.charAt(start)))
      start++;
    while (end > start && isWhitespace(s.charAt(end - 1)))
      end--;
    return start == 0 && end == s.length() ? s : s.substring(start, end);
  }

  /** {@code s.toLowerCase()}: the locale-independent full Unicode mapping, final sigma included. */
  static String lower(final String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  /** {@code s.replace(/\D/g, '')} */
  static String digits(final String s) {
    final int len = s.length();
    int n = 0;
    for (int i = 0; i < len; i++)
      if (isAsciiDigit(s.charAt(i)))
        n++;
    if (n == len)
      return s;
    final char[] out = new char[n];
    int j = 0;
    for (int i = 0; i < len; i++) {
      final char c = s.charAt(i);
      if (isAsciiDigit(c))
        out[j++] = c;
    }
    return new String(out);
  }

  /** {@code s.toLowerCase().replace(/[^a-z0-9]+/g, '')} */
  static String lowerAlnum(final String s) {
    final String l = lower(s);
    final StringBuilder out = new StringBuilder(l.length());
    for (int i = 0; i < l.length(); i++) {
      final char c = l.charAt(i);
      if ((c >= 'a' && c <= 'z') || isAsciiDigit(c))
        out.append(c);
    }
    return out.toString();
  }

  /** {@code s.replace(/\s+/g, ' ')} */
  static String collapseWhitespace(final String s) {
    StringBuilder out = null;
    final int len = s.length();
    for (int i = 0; i < len; i++) {
      final char c = s.charAt(i);
      if (isWhitespace(c)) {
        int j = i + 1;
        while (j < len && isWhitespace(s.charAt(j)))
          j++;
        if (out == null) {
          if (c == ' ' && j == i + 1)
            continue;
          out = new StringBuilder(len).append(s, 0, i);
        }
        out.append(' ');
        i = j - 1;
      } else if (out != null)
        out.append(c);
    }
    return out == null ? s : out.toString();
  }

  /** {@code s.replace(/^@/, '')} */
  static String stripLeadingAt(final String s) {
    return !s.isEmpty() && s.charAt(0) == '@' ? s.substring(1) : s;
  }

  /** {@code s.replace(/[\u0000-\u001f\u007f]/g, ' ')} */
  static String controlsToSpace(final String s) {
    char[] out = null;
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c <= '\u001f' || c == '\u007f') {
        if (out == null)
          out = s.toCharArray();
        out[i] = ' ';
      }
    }
    return out == null ? s : new String(out);
  }
}
