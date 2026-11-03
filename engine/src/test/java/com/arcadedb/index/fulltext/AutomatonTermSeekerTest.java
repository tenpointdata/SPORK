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

import com.arcadedb.serializer.BinaryComparator;
import org.apache.lucene.util.automaton.Automaton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The automaton-driven seek a fuzzy query walks the term dictionary with: a walk that jumps with {@code nextAfter} must find
 * exactly the terms a brute-force edit distance finds, and visit far fewer.
 */
class AutomatonTermSeekerTest {

  /** Optimal string alignment distance over code points: Levenshtein plus adjacent transpositions, as Lucene counts them. */
  private static int distance(final String a, final String b, final boolean transpositions) {
    final int[] x = a.codePoints().toArray();
    final int[] y = b.codePoints().toArray();
    final int[][] d = new int[x.length + 1][y.length + 1];
    for (int i = 0; i <= x.length; i++)
      d[i][0] = i;
    for (int j = 0; j <= y.length; j++)
      d[0][j] = j;
    for (int i = 1; i <= x.length; i++)
      for (int j = 1; j <= y.length; j++) {
        final int cost = x[i - 1] == y[j - 1] ? 0 : 1;
        d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (transpositions && i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1])
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
      }
    return d[x.length][y.length];
  }

  private static boolean bruteForce(final String term, final String candidate, final int prefixLength, final int maxEdits,
      final boolean transpositions) {
    final int prefixChars = term.offsetByCodePoints(0, Math.min(prefixLength, term.codePointCount(0, term.length())));
    final String prefix = term.substring(0, prefixChars);
    return candidate.startsWith(prefix) && distance(term.substring(prefixChars), candidate.substring(prefixChars), transpositions)
        <= maxEdits;
  }

  /** A sorted walk over {@code dictionary} that seeks with {@code nextAfter}, as the executor's walk does. */
  private static List<String> walk(final TreeSet<String> dictionary, final Automaton automaton, final int[] visited) {
    final List<String> accepted = new ArrayList<>();
    String key = dictionary.isEmpty() ? null : dictionary.first();
    while (key != null) {
      ++visited[0];
      if (AutomatonTermSeeker.accepts(automaton, key)) {
        accepted.add(key);
        key = dictionary.higher(key);
      } else {
        final String next = AutomatonTermSeeker.nextAfter(automaton, key);
        if (next != null)
          assertThat(BinaryComparator.compareStrings(next, key)).as("seek target after '%s'", key).isPositive();
        key = next == null ? null : dictionary.ceiling(next);
      }
    }
    return accepted;
  }

  private static String randomWord(final Random random, final String alphabet) {
    final int length = 1 + random.nextInt(9);
    final StringBuilder word = new StringBuilder(length);
    for (int i = 0; i < length; i++)
      word.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0, random.nextInt(alphabet.codePointCount(0,
          alphabet.length())))));
    return word.toString();
  }

  @Test
  void seekingWalkFindsExactlyWhatBruteForceFinds() {
    final Random random = new Random(42);
    // A small alphabet makes near misses common; the last letters exercise multi-byte and supplementary code points
    final String alphabet = "abcdejkmnos0129éß😀";
    final TreeSet<String> dictionary = new TreeSet<>(BinaryComparator::compareStrings);
    while (dictionary.size() < 20_000)
      dictionary.add(randomWord(random, alphabet));

    long visitedTotal = 0;
    for (int round = 0; round < 200; round++) {
      final String term = randomWord(random, alphabet);
      final int maxEdits = random.nextInt(3);
      final int prefixLength = random.nextInt(3);
      final boolean transpositions = random.nextBoolean();

      final Automaton automaton = AutomatonTermSeeker.fuzzy(term, prefixLength, maxEdits, transpositions);
      final int[] visited = new int[1];
      final List<String> found = walk(dictionary, automaton, visited);
      visitedTotal += visited[0];

      final List<String> expected = new ArrayList<>();
      for (final String candidate : dictionary)
        if (bruteForce(term, candidate, prefixLength, maxEdits, transpositions))
          expected.add(candidate);

      assertThat(found).as("term=%s maxEdits=%d prefix=%d transpositions=%b", term, maxEdits, prefixLength, transpositions)
          .containsExactlyElementsOf(expected);
    }
    // The point of seeking: a query reads a small fraction of the dictionary
    assertThat(visitedTotal / 200).isLessThan(dictionary.size() / 4);
  }

  @Test
  void nextAfterAnswersTheSmallestLivePrefix() {
    final Automaton exact = AutomatonTermSeeker.fuzzy("kitty", 0, 0, false);
    assertThat(AutomatonTermSeeker.nextAfter(exact, "a")).isEqualTo("k");
    assertThat(AutomatonTermSeeker.nextAfter(exact, "ki")).isEqualTo("kit");
    assertThat(AutomatonTermSeeker.nextAfter(exact, "kia")).isEqualTo("kit");
    assertThat(AutomatonTermSeeker.nextAfter(exact, "kitty")).isNull();
    assertThat(AutomatonTermSeeker.nextAfter(exact, "kz")).isNull();
    assertThat(AutomatonTermSeeker.nextAfter(exact, "")).isEqualTo("k");

    final Automaton prefixed = AutomatonTermSeeker.fuzzy("smith", 2, 1, true);
    assertThat(AutomatonTermSeeker.accepts(prefixed, "smyth")).isTrue();
    assertThat(AutomatonTermSeeker.accepts(prefixed, "smiht")).isTrue();
    assertThat(AutomatonTermSeeker.accepts(prefixed, "snith")).isFalse();
    assertThat(AutomatonTermSeeker.nextAfter(prefixed, "aaaa")).isEqualTo("s");
    assertThat(AutomatonTermSeeker.nextAfter(prefixed, "sa")).isEqualTo("sm");
  }
}
