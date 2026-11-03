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

import org.apache.commons.codec.language.bm.NameType;
import org.apache.commons.codec.language.bm.PhoneticEngine;
import org.apache.commons.codec.language.bm.RuleType;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FullTextAnalyzersTest {

  private static List<String> tokens(final Analyzer analyzer, final String text) throws IOException {
    final List<String> tokens = new ArrayList<>();
    try (final TokenStream stream = analyzer.tokenStream("contents", text)) {
      final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken())
        tokens.add(term.toString());
      stream.end();
    }
    return tokens;
  }

  private static String fold(final String text) {
    final char[] output = new char[text.length() * 4];
    final int length = ASCIIFoldingFilter.foldToASCII(text.toCharArray(), 0, output, 0, text.length());
    return new String(output, 0, length);
  }

  private static List<Integer> positionIncrements(final Analyzer analyzer, final String text) throws IOException {
    final List<Integer> increments = new ArrayList<>();
    try (final TokenStream stream = analyzer.tokenStream("contents", text)) {
      final PositionIncrementAttribute posInc = stream.addAttribute(PositionIncrementAttribute.class);
      stream.reset();
      while (stream.incrementToken())
        increments.add(posInc.getPositionIncrement());
      stream.end();
    }
    return increments;
  }

  @Test
  void aliasesAndClassNamesBuildTheBuiltIns() {
    assertThat(FullTextAnalyzers.create("ngram")).isInstanceOf(NGramAnalyzer.class);
    assertThat(FullTextAnalyzers.create("NGram")).isInstanceOf(NGramAnalyzer.class);
    assertThat(FullTextAnalyzers.create(NGramAnalyzer.class.getName())).isInstanceOf(NGramAnalyzer.class);
    assertThat(FullTextAnalyzers.create("edge_ngram")).isInstanceOf(EdgeNGramAnalyzer.class);
    assertThat(FullTextAnalyzers.create("reverse")).isInstanceOf(ReverseAnalyzer.class);
    assertThat(FullTextAnalyzers.create("phone")).isInstanceOf(PhoneAnalyzer.class);
    assertThat(FullTextAnalyzers.create("phonetic")).isInstanceOf(PhoneticAnalyzer.class);
    assertThat(FullTextAnalyzers.create("org.apache.lucene.analysis.core.WhitespaceAnalyzer")).isInstanceOf(WhitespaceAnalyzer.class);

    assertThat(FullTextAnalyzers.isBuiltIn("ngram(2,4)")).isTrue();
    assertThat(FullTextAnalyzers.isBuiltIn("org.apache.lucene.analysis.core.WhitespaceAnalyzer")).isFalse();
  }

  @Test
  void parametersAreApplied() {
    final NGramAnalyzer ngram = (NGramAnalyzer) FullTextAnalyzers.create("ngram( 2 , 5 )");
    assertThat(ngram.getMinGram()).isEqualTo(2);
    assertThat(ngram.getMaxGram()).isEqualTo(5);

    final EdgeNGramAnalyzer edge = (EdgeNGramAnalyzer) FullTextAnalyzers.create(EdgeNGramAnalyzer.class.getName() + "(1,20)");
    assertThat(edge.getMinGram()).isEqualTo(1);
    assertThat(edge.getMaxGram()).isEqualTo(20);

    assertThat(((PhoneticAnalyzer) FullTextAnalyzers.create("phonetic(beider_morse)")).getEncoder())
        .isEqualTo(PhoneticAnalyzer.Encoder.BEIDER_MORSE);
  }

  @Test
  void invalidSpecsAreRefused() {
    assertThatThrownBy(() -> FullTextAnalyzers.create("ngram(5,2)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("ngram(a,2)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("ngram(2,4")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("phonetic(soundex)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("phone(4,+1)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("reverse(1)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("com.example.NoSuchAnalyzer")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FullTextAnalyzers.create("org.apache.lucene.analysis.core.WhitespaceAnalyzer(1)"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ngramIndexesEveryGramAndQueriesWhole() throws IOException {
    final NGramAnalyzer analyzer = new NGramAnalyzer(3, 4);
    assertThat(tokens(analyzer, "Kitty")).containsExactlyInAnyOrder("kit", "kitt", "itt", "itty", "tty", "kitty");
    // query side: folded, whole
    assertThat(tokens(analyzer.getQueryAnalyzer(), "KiT")).containsExactly("kit");
    // a fragment longer than the largest gram becomes end-aligned windows of it, each at its own position
    assertThat(tokens(analyzer.getQueryAnalyzer(), "abcdefghij")).containsExactly("abcd", "efgh", "ghij");
    assertThat(positionIncrements(analyzer.getQueryAnalyzer(), "abcdefghij")).containsExactly(1, 1, 1);
    assertThat(tokens(analyzer.getQueryAnalyzer(), "abcdefgh")).containsExactly("abcd", "efgh");
  }

  @Test
  void edgeNGramIndexesPrefixesAndTruncatesQueries() throws IOException {
    final EdgeNGramAnalyzer analyzer = new EdgeNGramAnalyzer(2, 4);
    assertThat(tokens(analyzer, "Johnson Zoë")).containsExactly("jo", "joh", "john", "johnson", "zo", "zoe");
    // a query word longer than the largest gram: the whole word (stored as is), or the prefix it starts with, at one position
    assertThat(tokens(analyzer.getQueryAnalyzer(), "Johnso")).containsExactly("johnso", "john");
    assertThat(positionIncrements(analyzer.getQueryAnalyzer(), "Johnso Al")).containsExactly(1, 0, 1);
    assertThat(tokens(analyzer.getQueryAnalyzer(), "Joh")).containsExactly("joh");
  }

  @Test
  void reverseIndexesAMarkedReversedCopy() throws IOException {
    final ReverseAnalyzer analyzer = new ReverseAnalyzer();
    assertThat(tokens(analyzer, "Kitty_22")).containsExactly("kitty_22", PartialMatchAnalyzer.REVERSED_TOKEN_MARKER + "22_yttik");
    assertThat(positionIncrements(analyzer, "a b")).containsExactly(1, 0, 1, 0);
    assertThat(tokens(analyzer.getQueryAnalyzer(), "Kitty_22")).containsExactly("kitty_22");
    assertThat(PartialMatchAnalyzer.isInternalToken(ReverseAnalyzer.reversedKey("abc"))).isTrue();
    assertThat(PartialMatchAnalyzer.isInternalToken("abc")).isFalse();
  }

  @Test
  void phoneCanonicalizesAndIndexesSuffixes() throws IOException {
    final PhoneAnalyzer analyzer = new PhoneAnalyzer();
    assertThat(tokens(analyzer, "(702) 555-1234")).containsExactly("17025551234", "7025551234", "025551234", "25551234",
        "5551234", "551234", "51234", "1234");
    // international prefixes are dropped, the country code is not added again
    assertThat(tokens(analyzer, "+1 702.555.1234").getFirst()).isEqualTo("17025551234");
    assertThat(tokens(analyzer, "001-702-555-1234").getFirst()).isEqualTo("17025551234");
    assertThat(tokens(analyzer, "+44 20 7946 0958").getFirst()).isEqualTo("442079460958");
    // several numbers in one value
    assertThat(tokens(analyzer, "call 555-1234 or 555-9876, ext 12")).contains("5551234", "1234", "5559876", "9876")
        .doesNotContain("55512345559876", "12");

    final Analyzer query = analyzer.getQueryAnalyzer();
    assertThat(tokens(query, "(702) 555-1234")).containsExactly("7025551234");
    // a whole number with the country code is also tried without it, at the same position
    assertThat(tokens(query, "+1 702 555 1234")).containsExactly("17025551234", "7025551234");
    assertThat(positionIncrements(query, "+1 702 555 1234")).containsExactly(1, 0);
    assertThat(tokens(query, "555-1234")).containsExactly("5551234");
    // a fragment keeps its leading 1: it is the last digits, not a country code
    assertThat(tokens(query, "1234567")).containsExactly("1234567");
  }

  @Test
  void phoneWithATrunkPrefixFindsTheNationalAndTheInternationalForm() throws IOException {
    final Analyzer uk = FullTextAnalyzers.create("phone(4,44)");
    // stored: the trunk 0 dropped and the country code added, whichever way it was written
    assertThat(tokens(uk, "020 7946 0958").getFirst()).isEqualTo("442079460958");
    assertThat(tokens(uk, "+44 20 7946 0958").getFirst()).isEqualTo("442079460958");
    // queried: the national form is also tried without its trunk prefix, the international one without the country code
    final Analyzer query = ((PhoneAnalyzer) uk).getQueryAnalyzer();
    assertThat(tokens(query, "020 7946 0958")).containsExactly("02079460958", "2079460958");
    assertThat(tokens(query, "+44 20 7946 0958")).containsExactly("442079460958", "2079460958");
    // the last digits keep their 0: a fragment, not a national number
    assertThat(tokens(query, "0958")).containsExactly("0958");
    // the trunk prefix can be configured, or turned off
    assertThat(tokens(FullTextAnalyzers.create("phone(4,44,)"), "020 7946 0958").getFirst()).isEqualTo("02079460958");
    assertThat(tokens(FullTextAnalyzers.create("phone(4,39,9)"), "9 06 1234 5678").getFirst()).isEqualTo("390612345678");
    // the default country code 1 has no trunk prefix: its national numbers never start with 0
    assertThat(tokens(new PhoneAnalyzer(), "020 7946 0958").getFirst()).isEqualTo("02079460958");
  }

  @Test
  void phoneWithoutCountryCodeKeepsNationalNumbers() throws IOException {
    assertThat(tokens(new PhoneAnalyzer(4, ""), "702-555-1234").getFirst()).isEqualTo("7025551234");
    assertThat(tokens(FullTextAnalyzers.create("phone(7,44)"), "020 7946 0958"))
        .containsExactly("442079460958", "42079460958", "2079460958", "079460958", "79460958", "9460958");
  }

  @Test
  void phoneticEmitsCodes() throws IOException {
    final PhoneticAnalyzer metaphone = new PhoneticAnalyzer();
    assertThat(tokens(metaphone, "Smith")).contains("smith", "SM0");
    assertThat(tokens(metaphone, "Smyth")).contains("SM0");
    assertThat(tokens(metaphone, "Jon")).containsAnyElementsOf(tokens(metaphone, "John").subList(1, 2));

    final PhoneticAnalyzer beiderMorse = new PhoneticAnalyzer(PhoneticAnalyzer.Encoder.BEIDER_MORSE);
    final List<String> schwarz = tokens(beiderMorse, "Schwarz");
    assertThat(schwarz).isNotEmpty();
    assertThat(tokens(beiderMorse, "Shwartz")).containsAnyElementsOf(schwarz);
  }

  @Test
  void beiderMorseKeepsTheWordAndEncodesItBeforeFolding() throws IOException {
    final PhoneticAnalyzer beiderMorse = new PhoneticAnalyzer(PhoneticAnalyzer.Encoder.BEIDER_MORSE);
    // the word itself, folded, first; its forms at the same position
    final List<String> schwarzenegger = tokens(beiderMorse, "Schwarzenegger");
    assertThat(schwarzenegger.getFirst()).isEqualTo("schwarzenegger");
    assertThat(schwarzenegger).hasSizeGreaterThan(1);
    assertThat(positionIncrements(beiderMorse, "Schwarzenegger Smith").stream().filter(i -> i == 1)).hasSize(2);
    assertThat(tokens(beiderMorse, "Müller").getFirst()).isEqualTo("muller");

    // the forms are those of the word with its diacritics, which the language rules read
    final PhoneticEngine engine = new PhoneticEngine(NameType.GENERIC, RuleType.APPROX, true);
    for (final String name : new String[] { "Müller", "Łukasz", "Søren" }) {
      final List<String> expected = new ArrayList<>();
      final Matcher matcher = Pattern.compile("([^()|-]+)").matcher(engine.encode(name.toLowerCase(Locale.ROOT)));
      while (matcher.find())
        expected.add(fold(matcher.group(1)));
      assertThat(tokens(beiderMorse, name)).as(name).containsAll(expected);
    }
  }

  @Test
  void queryCounterpartOfAPlainAnalyzerIsItself() {
    final Analyzer whitespace = new WhitespaceAnalyzer();
    assertThat(FullTextAnalyzers.forQuery(whitespace)).isSameAs(whitespace);
    final NGramAnalyzer ngram = new NGramAnalyzer();
    assertThat(FullTextAnalyzers.forQuery(ngram)).isSameAs(ngram.getQueryAnalyzer());
  }
}
