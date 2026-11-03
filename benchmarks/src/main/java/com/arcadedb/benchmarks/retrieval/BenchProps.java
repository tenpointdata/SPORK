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
package com.arcadedb.benchmarks.retrieval;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The {@code -Darcadedb.retrievalBench.*} knobs every entry point of the harness reads, in one place so the run script and the
 * documentation name the same things.
 */
public final class BenchProps {
  public static final String PREFIX = "arcadedb.retrievalBench.";

  private BenchProps() {
  }

  public static String string(final String name, final String defaultValue) {
    return System.getProperty(PREFIX + name, defaultValue);
  }

  public static long longValue(final String name, final long defaultValue) {
    return Long.getLong(PREFIX + name, defaultValue);
  }

  public static int intValue(final String name, final int defaultValue) {
    return Integer.getInteger(PREFIX + name, defaultValue);
  }

  public static boolean bool(final String name, final boolean defaultValue) {
    final String v = System.getProperty(PREFIX + name);
    return v == null ? defaultValue : Boolean.parseBoolean(v);
  }

  /** Dataset root ({@code root}), default {@code target/retrieval-bench/<records>} under the working directory. */
  public static Path root() {
    final String explicit = System.getProperty(PREFIX + "root");
    if (explicit != null)
      return Paths.get(explicit);
    return Paths.get("target", "retrieval-bench", Long.toString(records()));
  }

  /** Record count ({@code records}), default 5,000,000; pass 50000000 for the large run. */
  public static long records() {
    return longValue("records", 5_000_000L);
  }

  public static long seed() {
    return longValue("seed", 42L);
  }

  /** Where every run writes its tables and JSON ({@code results}), default {@code target/retrieval-results}. */
  public static Path results() {
    return Paths.get(string("results", "target/retrieval-results"));
  }

  public static int[] intList(final String name, final String defaultValue) {
    final String[] parts = string(name, defaultValue).split(",");
    final int[] values = new int[parts.length];
    for (int i = 0; i < parts.length; i++)
      values[i] = Integer.parseInt(parts[i].trim());
    return values;
  }
}
