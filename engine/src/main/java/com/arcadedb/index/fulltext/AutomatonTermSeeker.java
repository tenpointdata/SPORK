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
package com.arcadedb.index.fulltext;

import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.automaton.Transition;

/**
 * Drives a walk over the sorted term dictionary of a full-text index with a deterministic automaton over code points, the way
 * Lucene's {@code AutomatonTermsEnum} does over its own term dictionary: a term the automaton rejects tells where the next term
 * that could be accepted starts, and the walk seeks there instead of reading every term (and every posting) in between.
 * <p>
 * The LSM-Tree orders string keys by their UTF-8 bytes, which is code-point order, the order the automaton labels are in. The
 * automaton must have no dead state (every state reaches an accepting one), so every string the seek produces is a prefix of an
 * accepted one.
 */
final class AutomatonTermSeeker {
  private AutomatonTermSeeker() {
  }

  /**
   * The automaton accepting every term within {@code maxEdits} edits of {@code term} whose first {@code prefixLength} code points
   * are those of {@code term}. {@code transpositions} counts a swap of two adjacent code points as one edit (Damerau), as the
   * Lucene {@code term~N} syntax does by default.
   */
  static Automaton fuzzy(final String term, final int prefixLength, final int maxEdits, final boolean transpositions) {
    final int prefixChars = term.offsetByCodePoints(0, Math.min(prefixLength, term.codePointCount(0, term.length())));
    Automaton automaton = new LevenshteinAutomata(term.substring(prefixChars), transpositions).toAutomaton(maxEdits,
        term.substring(0, prefixChars));
    if (!automaton.isDeterministic())
      automaton = Operations.determinize(automaton, Operations.DEFAULT_DETERMINIZE_WORK_LIMIT);
    return Operations.removeDeadStates(automaton);
  }

  /**
   * True when the automaton accepts {@code term}.
   */
  static boolean accepts(final Automaton automaton, final String term) {
    int state = 0;
    for (int i = 0; i < term.length(); ) {
      final int codePoint = term.codePointAt(i);
      state = automaton.step(state, codePoint);
      if (state < 0)
        return false;
      i += Character.charCount(codePoint);
    }
    return automaton.isAccept(state);
  }

  /**
   * The smallest string strictly greater than {@code term} that is a prefix of a string the automaton accepts, or null when no
   * accepted string is greater than {@code term}. Every accepted string greater than {@code term} is greater than or equal to
   * the answer, so a sorted walk that just rejected {@code term} can seek to it.
   */
  static String nextAfter(final Automaton automaton, final String term) {
    final int[] codePoints = term.codePoints().toArray();
    final int length = codePoints.length;
    final int[] states = new int[length + 1];

    // Follow the term as far as the automaton goes
    int alive = 0;
    while (alive < length) {
      final int next = automaton.step(states[alive], codePoints[alive]);
      if (next < 0)
        break;
      states[alive + 1] = next;
      ++alive;
    }

    final Transition transition = new Transition();
    if (alive == length) {
      // The whole term is a live prefix: the smallest string after it is the term followed by the smallest label it can take
      final int label = smallestLabelAtLeast(automaton, states[length], 0, transition);
      if (label >= 0)
        return new StringBuilder(term.length() + 2).append(term).appendCodePoint(label).toString();
      alive = length - 1;
    }

    // Backtrack: at the deepest position possible, replace the term's code point with the next greater one the state accepts
    for (int position = alive; position >= 0; --position) {
      final int label = smallestLabelAtLeast(automaton, states[position], codePoints[position] + 1, transition);
      if (label >= 0) {
        final StringBuilder next = new StringBuilder(term.length() + 2);
        for (int i = 0; i < position; i++)
          next.appendCodePoint(codePoints[i]);
        return next.appendCodePoint(label).toString();
      }
    }
    return null;
  }

  /**
   * The smallest code point not below {@code min} that {@code state} has a transition for, or -1. Surrogate code points are not
   * characters a string can end on, so a range that starts among them answers the first code point after them.
   */
  private static int smallestLabelAtLeast(final Automaton automaton, final int state, final int min, final Transition transition) {
    final int count = automaton.initTransition(state, transition);
    for (int i = 0; i < count; i++) {
      automaton.getNextTransition(transition);
      if (transition.max < min)
        continue;
      int label = Math.max(transition.min, min);
      if (label >= Character.MIN_SURROGATE && label <= Character.MAX_SURROGATE)
        label = Character.MAX_SURROGATE + 1;
      if (label <= transition.max)
        return label;
    }
    return -1;
  }
}
