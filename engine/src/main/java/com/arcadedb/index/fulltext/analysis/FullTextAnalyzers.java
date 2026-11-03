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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the analyzer a full-text index configuration names. A spec is either
 * <ul>
 *   <li>the class name of a Lucene {@link Analyzer} with a constructor that takes no arguments, as before, or</li>
 *   <li>a built-in partial-match analyzer by alias or by class name, optionally followed by its parameters in parentheses:
 *   {@code ngram}, {@code ngram(2,10)}, {@code edge_ngram(1,20)}, {@code reverse}, {@code phone(4,44)}, {@code phone(4,44,0)},
 *   {@code phonetic(beider_morse)}.</li>
 * </ul>
 * The spec is persisted verbatim in the index metadata, so an index reopens with the very same parameters.
 */
public final class FullTextAnalyzers {
  private static final Map<String, Function<String[], Analyzer>> BUILT_INS = new HashMap<>();

  static {
    register(args -> new NGramAnalyzer(intArg(args, 0, NGramAnalyzer.DEFAULT_MIN_GRAM), intArg(args, 1, NGramAnalyzer.DEFAULT_MAX_GRAM)),
        NGramAnalyzer.class, "ngram");
    register(args -> new EdgeNGramAnalyzer(intArg(args, 0, EdgeNGramAnalyzer.DEFAULT_MIN_GRAM),
        intArg(args, 1, EdgeNGramAnalyzer.DEFAULT_MAX_GRAM)), EdgeNGramAnalyzer.class, "edge_ngram", "edgengram");
    register(args -> {
      checkArity(args, 0, "reverse");
      return new ReverseAnalyzer();
    }, ReverseAnalyzer.class, "reverse", "reversed");
    register(args -> {
      checkArity(args, 3, "phone");
      final String countryCode = args.length > 1 ? args[1] : PhoneAnalyzer.DEFAULT_COUNTRY_CODE;
      return new PhoneAnalyzer(intArg(args, 0, PhoneAnalyzer.DEFAULT_MIN_SUFFIX), countryCode,
          args.length > 2 ? args[2] : PhoneAnalyzer.defaultTrunkPrefix(countryCode));
    }, PhoneAnalyzer.class, "phone");
    register(args -> new PhoneticAnalyzer(args.length > 0 && !args[0].isEmpty() ?
        PhoneticAnalyzer.encoderOf(args[0]) :
        PhoneticAnalyzer.Encoder.DOUBLE_METAPHONE), PhoneticAnalyzer.class, "phonetic");
  }

  private FullTextAnalyzers() {
  }

  private static void register(final Function<String[], Analyzer> factory, final Class<? extends Analyzer> clazz,
      final String... aliases) {
    BUILT_INS.put(clazz.getName(), factory);
    BUILT_INS.put(clazz.getSimpleName().toLowerCase(Locale.ROOT), factory);
    for (final String alias : aliases)
      BUILT_INS.put(alias, factory);
  }

  /**
   * True when {@code spec} names a built-in analyzer, with or without parameters.
   */
  public static boolean isBuiltIn(final String spec) {
    return spec != null && BUILT_INS.containsKey(key(name(spec.trim())));
  }

  /**
   * Builds the analyzer {@code spec} names.
   *
   * @throws IllegalArgumentException when the spec names no analyzer or carries invalid parameters
   */
  public static Analyzer create(final String spec) {
    if (spec == null || spec.isBlank())
      throw new IllegalArgumentException("Empty analyzer name");
    final String trimmed = spec.trim();
    final String name = name(trimmed);
    final String[] args = args(trimmed);

    final Function<String[], Analyzer> builtIn = BUILT_INS.get(key(name));
    if (builtIn != null)
      return builtIn.apply(args);

    if (args.length > 0)
      throw new IllegalArgumentException("Analyzer '" + name + "' takes no parameters");
    try {
      return (Analyzer) Class.forName(name).getDeclaredConstructor().newInstance();
    } catch (final Exception e) {
      throw new IllegalArgumentException("Cannot instantiate analyzer: " + name, e);
    }
  }

  /**
   * The analyzer a query against a field indexed with {@code indexing} is analyzed with: the partial-match analyzers expand on
   * the index side only.
   */
  public static Analyzer forQuery(final Analyzer indexing) {
    return indexing instanceof PartialMatchAnalyzer partial ? partial.getQueryAnalyzer() : indexing;
  }

  private static String key(final String name) {
    // Class names are case-sensitive, aliases are not
    return BUILT_INS.containsKey(name) ? name : name.toLowerCase(Locale.ROOT);
  }

  private static String name(final String spec) {
    final int paren = spec.indexOf('(');
    return paren > 0 ? spec.substring(0, paren).trim() : spec;
  }

  private static String[] args(final String spec) {
    final int open = spec.indexOf('(');
    if (open < 0)
      return new String[0];
    if (!spec.endsWith(")"))
      throw new IllegalArgumentException("Invalid analyzer spec '" + spec + "': missing ')'");
    final String inside = spec.substring(open + 1, spec.length() - 1).trim();
    if (inside.isEmpty())
      return new String[0];
    final String[] args = inside.split(",", -1);
    for (int i = 0; i < args.length; i++)
      args[i] = args[i].trim();
    return args;
  }

  private static int intArg(final String[] args, final int index, final int defaultValue) {
    if (args.length <= index || args[index].isEmpty())
      return defaultValue;
    try {
      return Integer.parseInt(args[index]);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("Invalid analyzer parameter '" + args[index] + "': an integer is expected");
    }
  }

  private static void checkArity(final String[] args, final int max, final String name) {
    if (args.length > max)
      throw new IllegalArgumentException("Analyzer '" + name + "' takes at most " + max + " parameters");
  }
}
