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

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authorship features of a text (docs/spork/06-stylometry-perceptual-hash.md). Two families:
 * <ul>
 *   <li><b>Recon parity</b> ({@code style24@v1}): {@link #reconStyleVector} and {@link #reconFingerprint} are ports of Recon's
 *   {@code identity.ts} {@code styleVector} and {@code stylometricFingerprint}, equal to them to the last bit of a double
 *   (golden values in the tests), so a vector computed in the database thresholds exactly like one computed in Recon. Case
 *   and punctuation are features: never normalize the text before calling them.</li>
 *   <li><b>Character n-gram profile</b> ({@code styleNgram@v1}): {@link #profile}, the signal short-text authorship work
 *   relies on. Character 2- to 4-grams of the text, case and punctuation kept, hashed with a sign into a power-of-two
 *   number of buckets, square-rooted and L2-normalized, so cosine similarity compares two writing habits. Phone numbers,
 *   emails and URLs are masked first: they are identity evidence of their own, and letting them into the style vector
 *   would count a shared phone twice.</li>
 * </ul>
 * Both are pure functions, thread safe.
 */
public final class Stylometry {
  public static final int RECON_DIMENSIONS = 24;
  public static final int MIN_PROFILE_DIMENSIONS = 16;
  public static final int MAX_PROFILE_DIMENSIONS = 4096;

  /** Recon's function words, in Recon's order: the first 20 dimensions of the vector. */
  private static final String[] FUNCTION_WORDS = { "the", "a", "and", "to", "of", "in", "for", "is", "you", "i", "me", "my", "with",
      "no", "only", "now", "call", "text", "available", "new" };

  private static final Pattern URL   = Pattern.compile("(?i)(?:https?://|www\\.)\\S+");
  private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

  private Stylometry() {
  }

  /**
   * Recon's 24-d style vector: the relative frequency of each function word among the words, then the '!', '?' and
   * ellipsis counts per word and the share of capital letters, L2-normalized. Words are the runs of {@code [a-z']} of the
   * lower-cased text, as Recon's {@code /[a-z']+/g}.
   */
  public static double[] reconStyleVector(final String text) {
    final String raw = text == null ? "" : text;
    final Words words = Words.of(raw);
    final double n = words.count == 0 ? 1 : words.count;
    final double[] v = new double[RECON_DIMENSIONS];
    for (int i = 0; i < FUNCTION_WORDS.length; i++)
      v[i] = words.counts[i] / n;
    v[20] = count(raw, '!') / n;
    v[21] = count(raw, '?') / n;
    v[22] = ellipses(raw) / n;
    v[23] = capitals(raw) / (double) Math.max(1, raw.length());

    // Recon: Math.sqrt(v.reduce((s, x) => s + x * x, 0)) || 1, summed left to right
    double sum = 0;
    for (final double x : v)
      sum += x * x;
    double norm = Math.sqrt(sum);
    if (norm == 0)
      norm = 1;
    for (int i = 0; i < v.length; i++)
      v[i] = v[i] / norm;
    return v;
  }

  /** {@link #reconStyleVector} as float32, the form Recon compares and SPORK stores ({@code styleVec}). */
  public static float[] reconStyleVectorFloat(final String text) {
    final double[] v = reconStyleVector(text);
    final float[] f = new float[v.length];
    for (int i = 0; i < v.length; i++)
      f[i] = (float) v[i];
    return f;
  }

  /**
   * Recon's coarse fingerprint {@code fw<base36>.p<ex><q><dots><emoji>.x<len><caps>}: function-word usage quantized to
   * 0 (absent), 1 (present) or 2 (above 6% of the words) read as a base-3 number, punctuation habits and lexical shape in
   * buckets. One author's consistent writing collapses to one label, so it groups by equality.
   */
  public static String reconFingerprint(final String text) {
    final String raw = text == null ? "" : text;
    final Words words = Words.of(raw);
    final double n = words.count == 0 ? 1 : words.count;

    long fw = 0;
    for (int i = 0; i < FUNCTION_WORDS.length; i++) {
      final double f = words.counts[i] / n;
      fw = fw * 3 + (f > 0.06 ? 2 : f > 0 ? 1 : 0);
    }

    final int ex = count(raw, '!');
    final int q = count(raw, '?');
    final int dots = ellipses(raw);
    final int emoji = hasEmoji(raw) ? 1 : 0;
    final double avgLen = words.letters / n;
    final double caps = capitals(raw) / (double) Math.max(1, raw.length());

    return "fw" + Long.toString(fw, 36) + ".p" + bucket(ex, 0, 1, 3) + bucket(q, 0, 1, 3) + bucket(dots, 0, 1) + emoji + ".x"
        + bucket(avgLen, 3.5, 4.5, 5.5) + bucket(caps, 0.02, 0.06, 0.12);
  }

  /**
   * The character n-gram authorship profile of a text: {@code dimensions} (a power of two, 16 to 4096) floats of unit length,
   * or all zeros for a text with fewer than two characters. Digits become '0' and whitespace runs one space, so number
   * formatting habits stay ("555-1234" against "(555) 1234") while the numbers themselves do not; URLs and emails become
   * one placeholder each.
   */
  public static float[] profile(final String text, final int dimensions) {
    if (dimensions < MIN_PROFILE_DIMENSIONS || dimensions > MAX_PROFILE_DIMENSIONS || Integer.bitCount(dimensions) != 1)
      throw new IllegalArgumentException(
          "dimensions must be a power of 2 between " + MIN_PROFILE_DIMENSIONS + " and " + MAX_PROFILE_DIMENSIONS + ", got "
              + dimensions);
    final float[] v = new float[dimensions];
    if (text == null || text.isEmpty())
      return v;

    final int[] cps = normalize(text);
    final int mask = dimensions - 1;
    for (int n = 2; n <= 4; n++)
      for (int start = 0; start + n <= cps.length; start++) {
        long h = 0xcbf29ce484222325L ^ n;
        for (int k = start; k < start + n; k++) {
          h ^= cps[k];
          h *= 0x100000001b3L;
        }
        h ^= h >>> 29;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 32;
        v[(int) h & mask] += h < 0 ? -1 : 1;
      }

    double sum = 0;
    for (int i = 0; i < v.length; i++) {
      final float x = v[i];
      v[i] = (float) (Math.signum(x) * Math.sqrt(Math.abs(x)));
      sum += v[i] * v[i];
    }
    if (sum > 0) {
      final double norm = Math.sqrt(sum);
      for (int i = 0; i < v.length; i++)
        v[i] = (float) (v[i] / norm);
    }
    return v;
  }

  /** Cosine similarity; 0 when either vector is all zeros. */
  public static double cosine(final float[] a, final float[] b) {
    if (a.length != b.length)
      throw new IllegalArgumentException("Cannot compare a " + a.length + "-d vector with a " + b.length + "-d vector");
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }

  private static int[] normalize(final String text) {
    String s = URL.matcher(text).replaceAll("\u0001");
    final Matcher email = EMAIL.matcher(s);
    if (email.find())
      s = email.replaceAll("\u0002");
    final int[] out = new int[s.length()];
    int size = 0;
    boolean space = false;
    for (int i = 0; i < s.length(); ) {
      final int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isWhitespace(cp)) {
        if (!space && size > 0)
          out[size++] = ' ';
        space = true;
        continue;
      }
      space = false;
      out[size++] = Character.isDigit(cp) ? '0' : cp;
    }
    if (size > 0 && out[size - 1] == ' ')
      size--;
    return size == out.length ? out : Arrays.copyOf(out, size);
  }

  /** The words of a text as Recon sees them: runs of [a-z'] after lower-casing, counted per function word. */
  private static final class Words {
    final int[] counts = new int[FUNCTION_WORDS.length];
    int         count;
    long        letters;

    static Words of(final String raw) {
      final Words w = new Words();
      final String t = raw.toLowerCase(Locale.ROOT);
      int i = 0;
      while (i < t.length()) {
        if (!isWordChar(t.charAt(i))) {
          i++;
          continue;
        }
        final int start = i;
        while (i < t.length() && isWordChar(t.charAt(i)))
          i++;
        w.count++;
        w.letters += i - start;
        for (int f = 0; f < FUNCTION_WORDS.length; f++) {
          final String fw = FUNCTION_WORDS[f];
          if (fw.length() == i - start && t.regionMatches(start, fw, 0, fw.length())) {
            w.counts[f]++;
            break;
          }
        }
      }
      return w;
    }

    private static boolean isWordChar(final char c) {
      return (c >= 'a' && c <= 'z') || c == '\'';
    }
  }

  private static int count(final String s, final char c) {
    int n = 0;
    for (int i = 0; i < s.length(); i++)
      if (s.charAt(i) == c)
        n++;
    return n;
  }

  /** Recon's {@code /\.\.\.|…/g}: non-overlapping "..." left to right, and every '…'. */
  private static int ellipses(final String s) {
    int n = 0;
    for (int i = 0; i < s.length(); ) {
      if (s.charAt(i) == '…') {
        n++;
        i++;
      } else if (s.startsWith("...", i)) {
        n++;
        i += 3;
      } else
        i++;
    }
    return n;
  }

  private static int capitals(final String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c >= 'A' && c <= 'Z')
        n++;
    }
    return n;
  }

  /** Recon's emoji class: code points U+1F300 to U+1FAFF and U+2600 to U+27BF. */
  private static boolean hasEmoji(final String s) {
    for (int i = 0; i < s.length(); ) {
      final int cp = s.codePointAt(i);
      if ((cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF))
        return true;
      i += Character.charCount(cp);
    }
    return false;
  }

  /** Recon's {@code bucket}: the number of edges the value is above. */
  private static int bucket(final double v, final double... edges) {
    int b = 0;
    for (final double e : edges)
      if (v > e)
        b++;
    return b;
  }
}
