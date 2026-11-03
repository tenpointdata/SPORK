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
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;

/**
 * The query side of the built-in partial-match analyzers: the same tokenizer and folding as the index side, without the
 * expansion. Optionally a long token is also tried cut ({@code truncateAt}, edge n-grams: the whole word, which the index stores
 * too, or the prefix it starts with) or split into windows of the largest gram ({@code windowSize}, n-grams) so it still matches
 * what the index side stored.
 */
final class FoldingAnalyzer extends Analyzer {
  private final boolean whitespace;
  private final int     truncateAt;
  private final int     windowSize;

  FoldingAnalyzer(final boolean whitespace, final int truncateAt, final int windowSize) {
    this.whitespace = whitespace;
    this.truncateAt = truncateAt;
    this.windowSize = windowSize;
  }

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    final Tokenizer source = whitespace ? new WhitespaceTokenizer() : new StandardTokenizer();
    TokenStream result = PartialMatchAnalyzer.fold(source);
    if (truncateAt > 0)
      result = new TruncatedAlternativeFilter(result, truncateAt);
    if (windowSize > 0)
      result = new WindowTokenFilter(result, windowSize);
    return new TokenStreamComponents(source, result);
  }

  @Override
  protected TokenStream normalize(final String fieldName, final TokenStream in) {
    return PartialMatchAnalyzer.fold(in);
  }

  /**
   * A token longer than {@code length} code points is followed, at the same position, by its first {@code length} code points:
   * the query matches the word typed in full (stored whole by the edge n-gram analyzer) or a longer word it is the start of, and a
   * value holding the whole word matches both, so it ranks first. Cutting alone made {@code internationalization} match
   * {@code internationalizing} as well as itself.
   */
  static final class TruncatedAlternativeFilter extends TokenFilter {
    private final CharTermAttribute          term   = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posInc = addAttribute(PositionIncrementAttribute.class);
    private final int                        length;
    private       State                      state;
    private       String                     truncated;

    TruncatedAlternativeFilter(final TokenStream input, final int length) {
      super(input);
      this.length = length;
    }

    @Override
    public boolean incrementToken() throws IOException {
      if (truncated != null) {
        restoreState(state);
        term.setEmpty().append(truncated);
        posInc.setPositionIncrement(0);
        truncated = null;
        return true;
      }
      if (!input.incrementToken())
        return false;
      if (Character.codePointCount(term, 0, term.length()) > length) {
        truncated = term.subSequence(0, Character.offsetByCodePoints(term, 0, length)).toString();
        state = captureState();
      }
      return true;
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      truncated = null;
      state = null;
    }
  }
}
