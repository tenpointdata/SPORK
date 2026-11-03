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
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Prefix search as you type: every word (standard tokenizer, folded to lower-case ASCII) is stored whole plus as each of its
 * prefixes of {@code minGram} to {@code maxGram} code points, so {@code joh} finds {@code Johnson} with one term lookup instead
 * of a range scan. A query word is folded the same way; one longer than {@code maxGram} code points matches the word itself or,
 * cut to {@code maxGram}, a longer word it starts (both are stored), the whole word ranking first.
 * <p>
 * Spec: {@code edge_ngram} or {@code edge_ngram(min,max)}, default {@code edge_ngram(2,15)}.
 */
public final class EdgeNGramAnalyzer extends PartialMatchAnalyzer {
  public static final int DEFAULT_MIN_GRAM = 2;
  public static final int DEFAULT_MAX_GRAM = 15;

  private final int      minGram;
  private final int      maxGram;
  private final Analyzer queryAnalyzer;

  public EdgeNGramAnalyzer() {
    this(DEFAULT_MIN_GRAM, DEFAULT_MAX_GRAM);
  }

  public EdgeNGramAnalyzer(final int minGram, final int maxGram) {
    if (minGram < 1 || maxGram < minGram)
      throw new IllegalArgumentException("Invalid edge n-gram sizes min=" + minGram + " max=" + maxGram + ": need 1 <= min <= max");
    this.minGram = minGram;
    this.maxGram = maxGram;
    this.queryAnalyzer = new FoldingAnalyzer(false, maxGram, 0);
  }

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    final Tokenizer source = new StandardTokenizer();
    return new TokenStreamComponents(source, new EdgeNGramTokenFilter(fold(source), minGram, maxGram, true));
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
