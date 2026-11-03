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
package com.arcadedb.index.fulltext.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phone numbers however they are written. A value is split into candidate numbers on anything that cannot be part of one (a
 * letter, a comma, a slash...): digits, spaces, {@code ( ) - . +} stay inside a number. Each number is reduced to its digits and
 * stored as
 * <ul>
 *   <li>its canonical form: the international prefix ({@code +}, {@code 00}, {@code 011}) dropped; a national number that starts
 *   with the trunk prefix ({@code 0} in most countries outside North America) loses it and gets the default country code in front
 *   ({@code 020 7946 0958} becomes {@code 442079460958} with country code 44); otherwise a national number of exactly 10 digits
 *   gets the default country code in front ({@code (702) 555-1234} becomes {@code 17025551234} with country code 1);</li>
 *   <li>every suffix of the canonical form of at least {@code minSuffix} digits, so the last 4 or 7 digits, or the national
 *   number without its country code, find it with one term lookup.</li>
 * </ul>
 * A query is reduced to its digits the same way, without the expansion, and also tried (as alternatives at the same position)
 * without its trunk prefix and without the default country code when it is long enough to be a whole number, so the national
 * and the international spelling of a number find each other: {@code 555-1234}, {@code (702) 555 1234}, {@code +1 702 555 1234}
 * and {@code +7025551234} (a plus with the country code left out) all find the number above, and with country code 44
 * {@code 020 7946 0958} and {@code +44 20 7946 0958} find each other. Only the default country code is known: a national number
 * of another country, written without its country code, is not canonicalized. Quote a query that is more than one token
 * ({@code phone:"(702) 555-1234"}, {@code phone:"020 7946 0958"}): parentheses are grouping syntax to the query parser and spaces
 * separate clauses.
 * <p>
 * Spec: {@code phone}, {@code phone(minSuffix,countryCode)} or {@code phone(minSuffix,countryCode,trunkPrefix)}, default
 * {@code phone(4,1)}; the trunk prefix defaults to {@code 0} for a country code other than 1 and to none for 1 (whose national
 * numbers never start with 0 or 1). An empty country code ({@code phone(4,)}) leaves national numbers as they are, an empty
 * trunk prefix ({@code phone(4,44,)}) keeps the trunk digit.
 */
public final class PhoneAnalyzer extends PartialMatchAnalyzer {
  public static final int    DEFAULT_MIN_SUFFIX   = 4;
  public static final String DEFAULT_COUNTRY_CODE = "1";
  /**
   * The fewest digits a national number has without its trunk prefix and country code (9 in France, Italy, Australia...): shorter
   * digits are a fragment (the last 4 or 7 digits), which keeps its trunk-like leading 0 and its country-code-like leading 1.
   */
  static final int           NATIONAL_MIN_LENGTH  = 9;

  private final int      minSuffix;
  private final String   countryCode;
  private final String   trunkPrefix;
  private final Analyzer queryAnalyzer;

  public PhoneAnalyzer() {
    this(DEFAULT_MIN_SUFFIX, DEFAULT_COUNTRY_CODE);
  }

  public PhoneAnalyzer(final int minSuffix, final String countryCode) {
    this(minSuffix, countryCode, defaultTrunkPrefix(countryCode));
  }

  public PhoneAnalyzer(final int minSuffix, final String countryCode, final String trunkPrefix) {
    if (minSuffix < 1)
      throw new IllegalArgumentException("Invalid phone suffix length " + minSuffix + ": need >= 1");
    if (countryCode == null || !countryCode.chars().allMatch(c -> c >= '0' && c <= '9'))
      throw new IllegalArgumentException("Invalid phone country code '" + countryCode + "': digits only");
    if (trunkPrefix == null || !trunkPrefix.chars().allMatch(c -> c >= '0' && c <= '9'))
      throw new IllegalArgumentException("Invalid phone trunk prefix '" + trunkPrefix + "': digits only");
    this.minSuffix = minSuffix;
    this.countryCode = countryCode;
    this.trunkPrefix = trunkPrefix;
    this.queryAnalyzer = new Analyzer() {
      @Override
      protected TokenStreamComponents createComponents(final String fieldName) {
        return new TokenStreamComponents(new PhoneNumberTokenizer(minSuffix, countryCode, trunkPrefix, false));
      }
    };
  }

  /** The trunk prefix of national numbers under {@code countryCode}: {@code 0}, except in North America (1) and with none. */
  public static String defaultTrunkPrefix(final String countryCode) {
    return countryCode == null || countryCode.isEmpty() || "1".equals(countryCode) ? "" : "0";
  }

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    return new TokenStreamComponents(new PhoneNumberTokenizer(minSuffix, countryCode, trunkPrefix, true));
  }

  @Override
  public Analyzer getQueryAnalyzer() {
    return queryAnalyzer;
  }

  /**
   * The digits of the numbers found in {@code value}: when {@code indexing}, the canonical form of each number followed by its
   * suffixes; otherwise the digits of each number with the international prefix dropped, followed by its alternative spellings.
   * {@code increments} receives the position increment of every token: 1 for the first token of a number, 0 for an alternative
   * of the query side (the index side lists every token at its own position, as before).
   */
  static void numbers(final CharSequence value, final int minSuffix, final String countryCode, final String trunkPrefix,
      final boolean indexing, final List<String> out, final List<Integer> increments) {
    final StringBuilder digits = new StringBuilder(16);
    boolean plus = false;
    final int length = value.length();
    for (int i = 0; i <= length; i++) {
      final char c = i < length ? value.charAt(i) : ',';
      final int digit = Character.digit(c, 10);
      if (digit >= 0)
        digits.append((char) ('0' + digit));
      else if (c == '+') {
        if (digits.isEmpty())
          plus = true;
      } else if (!(c == ' ' || c == '(' || c == ')' || c == '-' || c == '.' || Character.isSpaceChar(c))) {
        // Not part of a number: it ends the current one
        if (!digits.isEmpty())
          emit(digits, plus, minSuffix, countryCode, trunkPrefix, indexing, out, increments);
        digits.setLength(0);
        plus = false;
      }
    }
  }

  private static void emit(final StringBuilder digits, final boolean plus, final int minSuffix, final String countryCode,
      final String trunkPrefix, final boolean indexing, final List<String> out, final List<Integer> increments) {
    String number = digits.toString();
    boolean international = plus;
    if (number.startsWith("011") && number.length() > 3) {
      number = number.substring(3);
      international = true;
    } else if (number.startsWith("00") && number.length() > 2) {
      number = number.substring(2);
      international = true;
    }
    final String withoutTrunk = !international && !trunkPrefix.isEmpty() && number.startsWith(trunkPrefix)
        && number.length() - trunkPrefix.length() >= NATIONAL_MIN_LENGTH ? number.substring(trunkPrefix.length()) : null;

    if (!indexing) {
      final int first = out.size();
      out.add(number);
      increments.add(1);
      if (withoutTrunk != null)
        addAlternative(withoutTrunk, first, minSuffix, out, increments);
      // The country code in front of a whole number: also its national form, which finds the number stored without the code
      if (!countryCode.isEmpty() && number.startsWith(countryCode) && number.length() - countryCode.length() >= NATIONAL_MIN_LENGTH)
        addAlternative(number.substring(countryCode.length()), first, minSuffix, out, increments);
      return;
    }

    if (number.length() < minSuffix)
      return;

    final String canonical;
    if (withoutTrunk != null)
      canonical = countryCode + withoutTrunk;
    else if (!international && number.length() == 10 && !countryCode.isEmpty())
      canonical = countryCode + number;
    else
      canonical = number;
    out.add(canonical);
    increments.add(1);
    for (int start = 1; canonical.length() - start >= minSuffix; start++) {
      out.add(canonical.substring(start));
      increments.add(1);
    }
  }

  private static void addAlternative(final String alternative, final int first, final int minSuffix, final List<String> out,
      final List<Integer> increments) {
    if (alternative.length() < minSuffix)
      return;
    for (int i = first; i < out.size(); i++)
      if (out.get(i).equals(alternative))
        return;
    out.add(alternative);
    increments.add(0);
  }

  /**
   * Reads the whole value, then hands out the numbers {@link #numbers} found in it.
   */
  private static final class PhoneNumberTokenizer extends Tokenizer {
    private final CharTermAttribute          termAtt   = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final int                        minSuffix;
    private final String                     countryCode;
    private final String                     trunkPrefix;
    private final boolean                    indexing;
    private final List<String>               tokens     = new ArrayList<>();
    private final List<Integer>              increments = new ArrayList<>();
    private final StringBuilder              value      = new StringBuilder(32);
    private final char[]                     buffer     = new char[256];
    private       boolean                    filled;
    private       int                        next;

    PhoneNumberTokenizer(final int minSuffix, final String countryCode, final String trunkPrefix, final boolean indexing) {
      this.minSuffix = minSuffix;
      this.countryCode = countryCode;
      this.trunkPrefix = trunkPrefix;
      this.indexing = indexing;
    }

    @Override
    public boolean incrementToken() throws IOException {
      clearAttributes();
      if (!filled) {
        value.setLength(0);
        int read;
        while ((read = input.read(buffer)) > 0)
          value.append(buffer, 0, read);
        numbers(value, minSuffix, countryCode, trunkPrefix, indexing, tokens, increments);
        filled = true;
      }
      if (next >= tokens.size())
        return false;
      termAtt.append(tokens.get(next));
      posIncAtt.setPositionIncrement(increments.get(next));
      ++next;
      return true;
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      tokens.clear();
      increments.clear();
      filled = false;
      next = 0;
    }
  }
}
