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
package com.arcadedb.similarity;

import java.util.List;

/**
 * Binary hashes (perceptual image hashes and any other bit-string fingerprint) as {@code long[]}, most significant bit
 * first: bit 0 of the hash is bit 63 of word 0, so the hex form reads left to right like the hash. A 64-bit hash is one
 * word, a 256-bit PDQ hash four.
 */
public final class BitHash {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private BitHash() {
  }

  /**
   * A hash from any of the forms a property or a parameter holds it in: a hex string (optionally {@code 0x}-prefixed; a
   * string shorter than a whole number of words is left-padded with zeros), a number (one 64-bit word), bytes (big
   * endian, a multiple of 8), a {@code long[]}, or a list of numbers (one word each).
   */
  public static long[] parse(final Object value) {
    switch (value) {
    case null -> throw new IllegalArgumentException("A hash cannot be null");
    case long[] words -> {
      if (words.length == 0)
        throw new IllegalArgumentException("A hash cannot be empty");
      return words;
    }
    case String s -> {
      return parseHex(s);
    }
    case Long l -> {
      return new long[] { l };
    }
    case Integer i -> {
      return new long[] { i.longValue() };
    }
    case Short s -> {
      return new long[] { s.longValue() };
    }
    case byte[] bytes -> {
      if (bytes.length == 0 || bytes.length % 8 != 0)
        throw new IllegalArgumentException("A binary hash must be a positive multiple of 8 bytes, got " + bytes.length);
      final long[] words = new long[bytes.length / 8];
      for (int i = 0; i < bytes.length; i++)
        words[i >>> 3] = (words[i >>> 3] << 8) | (bytes[i] & 0xFF);
      return words;
    }
    case List<?> list -> {
      if (list.isEmpty())
        throw new IllegalArgumentException("A hash cannot be empty");
      final long[] words = new long[list.size()];
      for (int i = 0; i < words.length; i++) {
        final Object item = list.get(i);
        if (!(item instanceof Long) && !(item instanceof Integer) && !(item instanceof Short))
          throw new IllegalArgumentException("A hash as a list holds integer words, got " + item);
        words[i] = ((Number) item).longValue();
      }
      return words;
    }
    default -> throw new IllegalArgumentException(
        "A hash is a hex string, an integer, bytes or a list of integers, got " + value.getClass().getSimpleName());
    }
  }

  /**
   * One value holding one hash or several, as a record holds the hashes of its images: a list of strings, bytes or
   * {@code long[]} is several hashes, anything else one ({@link #parse}; a list of numbers is the words of one hash).
   * Null and an empty list are no hash.
   */
  public static long[][] parseAll(final Object value) {
    if (value == null)
      return new long[0][];
    if (value instanceof List<?> list && (list.isEmpty() || !(list.getFirst() instanceof Number))) {
      final long[][] hashes = new long[list.size()][];
      for (int i = 0; i < hashes.length; i++)
        hashes[i] = parse(list.get(i));
      return hashes;
    }
    return new long[][] { parse(value) };
  }

  public static long[] parseHex(final String text) {
    String s = text.trim();
    if (s.startsWith("0x") || s.startsWith("0X"))
      s = s.substring(2);
    if (s.isEmpty())
      throw new IllegalArgumentException("A hex hash cannot be empty");
    final int words = (s.length() + 15) / 16;
    final long[] hash = new long[words];
    final int padding = words * 16 - s.length();
    for (int i = 0; i < s.length(); i++) {
      final int digit = Character.digit(s.charAt(i), 16);
      if (digit < 0)
        throw new IllegalArgumentException("Not a hex hash: '" + text + "'");
      final int position = i + padding;
      hash[position >>> 4] = (hash[position >>> 4] << 4) | digit;
    }
    return hash;
  }

  public static String toHex(final long[] hash) {
    final char[] out = new char[hash.length * 16];
    for (int w = 0; w < hash.length; w++)
      for (int i = 0; i < 16; i++)
        out[w * 16 + i] = HEX[(int) (hash[w] >>> (60 - 4 * i)) & 0xF];
    return new String(out);
  }

  /** The number of differing bits; the two hashes must have the same width. */
  public static int hamming(final long[] a, final long[] b) {
    if (a.length != b.length)
      throw new IllegalArgumentException(
          "Cannot compare a " + a.length * 64 + "-bit hash with a " + b.length * 64 + "-bit hash");
    int d = 0;
    for (int i = 0; i < a.length; i++)
      d += Long.bitCount(a[i] ^ b[i]);
    return d;
  }

  /** Like {@link #hamming} but gives up past {@code limit}, returning {@code limit + 1}. */
  static int hamming(final long[] a, final long[] b, final int limit) {
    int d = 0;
    for (int i = 0; i < a.length; i++) {
      d += Long.bitCount(a[i] ^ b[i]);
      if (d > limit)
        return limit + 1;
    }
    return d;
  }

  /** {@code length} (at most 32) bits starting at bit {@code start}, as an unsigned value. */
  static long chunk(final long[] hash, final int start, final int length) {
    final int word = start >>> 6;
    final int offset = start & 63;
    final long mask = (1L << length) - 1;
    if (offset + length <= 64)
      return (hash[word] >>> (64 - offset - length)) & mask;
    final int fromNext = offset + length - 64;
    return ((hash[word] << fromNext) | (hash[word + 1] >>> (64 - fromNext))) & mask;
  }
}
