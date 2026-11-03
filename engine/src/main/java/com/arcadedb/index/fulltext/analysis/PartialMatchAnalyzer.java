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
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.LowerCaseFilter;

/**
 * An analyzer that indexes MORE than the words of a value (its n-grams, its prefixes, its reversed form, the suffixes of a phone
 * number) so a fragment of a value is found by an ordinary term lookup instead of a scan of the whole term dictionary.
 * <p>
 * Such an analyzer must not run on the query side as well: the grams of the query would be OR-ed together and match almost
 * everything. So the full-text index analyzes a query with {@link #getQueryAnalyzer()} whenever this analyzer is the one
 * configured for a field, unless a {@code query_analyzer} names another one explicitly. A query fragment that yields several
 * tokens (a fragment longer than the largest gram) is matched as a conjunction of them.
 */
public abstract class PartialMatchAnalyzer extends Analyzer {
  /**
   * Marks the reversed copy of a token, so a reversed token can never be mistaken for a word of the value. The same marker as
   * Lucene's {@code ReverseStringFilter.START_OF_HEADING_MARKER}.
   */
  public static final char REVERSED_TOKEN_MARKER = '\u0001';

  /**
   * The analyzer queries against a field indexed with this one are analyzed with.
   */
  public abstract Analyzer getQueryAnalyzer();

  /**
   * True when this analyzer also stores every token reversed and prefixed with {@link #REVERSED_TOKEN_MARKER}, which turns a
   * suffix search ({@code *1234}) into a range scan.
   */
  public boolean isIndexingReversedTokens() {
    return false;
  }

  /**
   * The folding every built-in analyzer applies on both sides: lower case, then accents folded to ASCII.
   */
  static TokenStream fold(final TokenStream in) {
    return new ASCIIFoldingFilter(new LowerCaseFilter(in));
  }

  /**
   * True for a token the index stores for its own use (a reversed copy) rather than as a word of the value.
   */
  public static boolean isInternalToken(final String token) {
    return !token.isEmpty() && token.charAt(0) == REVERSED_TOKEN_MARKER;
  }
}
