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
import org.apache.lucene.analysis.reverse.ReverseStringFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;

/**
 * Suffix search: every whitespace-separated token is folded (lower case, ASCII) and stored twice, as is and reversed behind
 * {@link #REVERSED_TOKEN_MARKER}. A leading-wildcard query such as {@code *1234} or {@code *kitty} then becomes a range scan over
 * the reversed tokens starting with {@code 4321} / {@code yttik} instead of a scan of every term, and leading wildcards are
 * accepted on such an index without {@code allowLeadingWildcard}. Every other query works as on a whitespace analyzer.
 * <p>
 * Spec: {@code reverse}.
 */
public final class ReverseAnalyzer extends PartialMatchAnalyzer {
  private final Analyzer queryAnalyzer = new FoldingAnalyzer(true, 0, 0);

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    final Tokenizer source = new WhitespaceTokenizer();
    return new TokenStreamComponents(source, new ReversedCopyFilter(fold(source)));
  }

  @Override
  protected TokenStream normalize(final String fieldName, final TokenStream in) {
    return fold(in);
  }

  @Override
  public Analyzer getQueryAnalyzer() {
    return queryAnalyzer;
  }

  @Override
  public boolean isIndexingReversedTokens() {
    return true;
  }

  /**
   * The key a token is stored under in reversed form.
   */
  public static String reversedKey(final String token) {
    return REVERSED_TOKEN_MARKER + ReverseStringFilter.reverse(token);
  }

  /**
   * Emits every token, then its reversed copy at the same position.
   */
  private static final class ReversedCopyFilter extends TokenFilter {
    private final CharTermAttribute          termAtt   = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private       String                     reversed;

    ReversedCopyFilter(final TokenStream input) {
      super(input);
    }

    @Override
    public boolean incrementToken() throws IOException {
      if (reversed != null) {
        termAtt.setEmpty().append(reversed);
        posIncAtt.setPositionIncrement(0);
        reversed = null;
        return true;
      }
      if (!input.incrementToken())
        return false;
      reversed = reversedKey(termAtt.toString());
      return true;
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      reversed = null;
    }
  }
}
