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
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;

/**
 * Substring search: every whitespace-separated token is folded (lower case, ASCII) and stored whole plus as every gram of
 * {@code minGram} to {@code maxGram} code points, so {@code kitty} finds {@code sexy_kitty_2024} and {@code 4567} finds
 * {@code 702-555-4567} with a single term lookup. Tokens are split on whitespace only, so punctuation inside a handle or a
 * number is part of what can be searched.
 * <p>
 * A query fragment is folded the same way and looked up whole; one longer than {@code maxGram} is split into windows of
 * {@code maxGram} that must all match. A fragment shorter than {@code minGram} only matches a whole token.
 * <p>
 * The cost is index size: a token of {@code L} code points stores about {@code (maxGram - minGram + 1) * L} grams. Use it on
 * short fields (handles, identifiers, phone numbers), not on prose.
 * <p>
 * Spec: {@code ngram} or {@code ngram(min,max)}, default {@code ngram(3,8)}.
 */
public final class NGramAnalyzer extends PartialMatchAnalyzer {
  public static final int DEFAULT_MIN_GRAM = 3;
  public static final int DEFAULT_MAX_GRAM = 8;

  private final int      minGram;
  private final int      maxGram;
  private final Analyzer queryAnalyzer;

  public NGramAnalyzer() {
    this(DEFAULT_MIN_GRAM, DEFAULT_MAX_GRAM);
  }

  public NGramAnalyzer(final int minGram, final int maxGram) {
    if (minGram < 1 || maxGram < minGram)
      throw new IllegalArgumentException("Invalid n-gram sizes min=" + minGram + " max=" + maxGram + ": need 1 <= min <= max");
    this.minGram = minGram;
    this.maxGram = maxGram;
    this.queryAnalyzer = new FoldingAnalyzer(true, 0, maxGram);
  }

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    final Tokenizer source = new WhitespaceTokenizer();
    return new TokenStreamComponents(source, new NGramTokenFilter(fold(source), minGram, maxGram, true));
  }

  @Override
  protected TokenStream normalize(final String fieldName, final TokenStream in) {
    return fold(in);
  }

  @Override
  public Analyzer getQueryAnalyzer() {
    return queryAnalyzer;
  }

  public int getMinGram() {
    return minGram;
  }

  public int getMaxGram() {
    return maxGram;
  }
}
