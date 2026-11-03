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
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.phonetic.DoubleMetaphoneFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names by how they sound: every word (standard tokenizer, folded to lower-case ASCII) is stored with its phonetic code, so
 * {@code jon} finds {@code John} and {@code smyth} finds {@code Smith}. The same analysis runs on the query side, and a word
 * matches through its spelling or through its code.
 * <ul>
 *   <li>{@code double_metaphone} (default): the word and its Double Metaphone codes (4 characters). Cheap, tuned for English
 *   names.</li>
 *   <li>{@code beider_morse}: the word and its Beider-Morse phonetic forms (generic name type, approximate rules), which also
 *   covers names of other origins; it emits several forms per word. The forms are computed from the word before it is folded to
 *   ASCII, since the rules read its diacritics.</li>
 * </ul>
 * Both encoders read Latin script. A word in another script (Cyrillic, Greek, Arabic, CJK...) gets no code from Double Metaphone,
 * and Beider-Morse covers only the languages of its rule set (Latin-script languages plus Cyrillic, Greek and Hebrew): such a word
 * still matches by its spelling.
 * Spec: {@code phonetic} or {@code phonetic(double_metaphone)} / {@code phonetic(beider_morse)}.
 */
public final class PhoneticAnalyzer extends Analyzer {
  public enum Encoder {DOUBLE_METAPHONE, BEIDER_MORSE}

  private static final int DOUBLE_METAPHONE_CODE_LENGTH = 4;

  private final Encoder        encoder;
  private final PhoneticEngine beiderMorse;

  public PhoneticAnalyzer() {
    this(Encoder.DOUBLE_METAPHONE);
  }

  public PhoneticAnalyzer(final Encoder encoder) {
    this.encoder = encoder;
    this.beiderMorse = encoder == Encoder.BEIDER_MORSE ? new PhoneticEngine(NameType.GENERIC, RuleType.APPROX, true) : null;
  }

  public static Encoder encoderOf(final String name) {
    try {
      return Encoder.valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown phonetic encoder '" + name + "': use double_metaphone or beider_morse");
    }
  }

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    final Tokenizer source = new StandardTokenizer();
    if (encoder == Encoder.BEIDER_MORSE)
      // Encoded before the folding: the language guess and the rules read the diacritics (u, l, o with their marks) that folding
      // removes. The word itself is kept, folded, next to its forms
      return new TokenStreamComponents(source,
          new ASCIIFoldingFilter(new BeiderMorseWithWordFilter(new LowerCaseFilter(source), beiderMorse)));
    return new TokenStreamComponents(source,
        new DoubleMetaphoneFilter(PartialMatchAnalyzer.fold(source), DOUBLE_METAPHONE_CODE_LENGTH, true));
  }

  @Override
  protected TokenStream normalize(final String fieldName, final TokenStream in) {
    return PartialMatchAnalyzer.fold(in);
  }

  public Encoder getEncoder() {
    return encoder;
  }

  /**
   * Each word, then its Beider-Morse forms at the same position. Lucene's {@code BeiderMorseFilter} replaces the word with its
   * forms, so a prefix, wildcard or fuzzy clause on the field could never match a spelling.
   */
  static final class BeiderMorseWithWordFilter extends TokenFilter {
    // One form of an encoding like "(a|b)-(c|d)": what Lucene's BeiderMorseFilter splits it into
    private static final Pattern FORM = Pattern.compile("([^()|-]+)");

    private final CharTermAttribute          term   = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posInc = addAttribute(PositionIncrementAttribute.class);
    private final PhoneticEngine             engine;
    private final List<String>               forms  = new ArrayList<>();
    private       int                        next;
    private       State                      state;

    BeiderMorseWithWordFilter(final TokenStream input, final PhoneticEngine engine) {
      super(input);
      this.engine = engine;
    }

    @Override
    public boolean incrementToken() throws IOException {
      if (next < forms.size()) {
        restoreState(state);
        term.setEmpty().append(forms.get(next++));
        posInc.setPositionIncrement(0);
        return true;
      }
      if (!input.incrementToken())
        return false;
      forms.clear();
      next = 0;
      final String word = term.toString();
      final Matcher matcher = FORM.matcher(engine.encode(word));
      while (matcher.find()) {
        final String form = matcher.group(1);
        if (!form.equals(word) && !forms.contains(form))
          forms.add(form);
      }
      state = captureState();
      return true;
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      forms.clear();
      next = 0;
      state = null;
    }
  }
}
