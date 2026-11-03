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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;

/**
 * Splits a token longer than {@code size} code points into windows of exactly {@code size} code points, each at its own
 * position, so the query parser turns them into a conjunction. The windows tile the token (the last one aligned on its end)
 * rather than slide one code point at a time: every window is a gram the n-gram index side stored, and a few of them pin a long
 * fragment down as well as all of them would, at a fraction of the posting lookups. A token that fits in one window passes
 * through unchanged.
 */
final class WindowTokenFilter extends TokenFilter {
  private final CharTermAttribute          termAtt   = addAttribute(CharTermAttribute.class);
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
  private final int                        size;
  private       String                     pending;
  private       int[]                      windowStarts;
  private       int                        windowCount;
  private       int                        nextWindow;

  WindowTokenFilter(final TokenStream input, final int size) {
    super(input);
    this.size = size;
  }

  @Override
  public boolean incrementToken() throws IOException {
    if (pending != null) {
      if (nextWindow < windowCount) {
        emitWindow(nextWindow++);
        posIncAtt.setPositionIncrement(1);
        return true;
      }
      pending = null;
    }

    if (!input.incrementToken())
      return false;

    final String term = termAtt.toString();
    final int codePoints = term.codePointCount(0, term.length());
    if (codePoints <= size)
      return true;

    pending = term;
    windowCount = 0;
    windowStarts = new int[codePoints / size + 2];
    for (int start = 0; start + size < codePoints; start += size)
      windowStarts[windowCount++] = start;
    if (windowStarts[windowCount - 1] != codePoints - size)
      windowStarts[windowCount++] = codePoints - size;

    // The first window keeps the position increment of the token it comes from
    emitWindow(0);
    nextWindow = 1;
    return true;
  }

  private void emitWindow(final int window) {
    final int begin = pending.offsetByCodePoints(0, windowStarts[window]);
    final int end = pending.offsetByCodePoints(begin, size);
    termAtt.setEmpty().append(pending, begin, end);
  }

  @Override
  public void reset() throws IOException {
    super.reset();
    pending = null;
  }
}
