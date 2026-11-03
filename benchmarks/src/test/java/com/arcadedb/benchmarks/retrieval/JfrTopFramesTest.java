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

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JfrTopFramesTest {
  /** Kept as a field so the allocation below cannot be scalar-replaced away. */
  static volatile Object sink;

  @Test
  void aRecordingOfAHotLoopNamesTheHotMethodAndItsAllocations() throws Exception {
    final Path dir = Paths.get("target", "jfr-test");
    Files.createDirectories(dir);
    final Path file = dir.resolve("hot-loop.jfr").toAbsolutePath();
    Files.deleteIfExists(file);

    try (final Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
      recording.enable("jdk.ObjectAllocationSample").with("throttle", "5000/s");
      recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
      recording.start();
      hotLoop();
      recording.stop();
      recording.dump(file);
    }

    final String tables = JfrTopFrames.analyze(file, 20);
    assertThat(tables).contains("Top frames by self CPU").contains("Top allocation sites").contains("Synchronous I/O");
    // an execution sample names the thread it sampled in its own field, not in the committing thread of the event
    assertThat(tables).as("the thread the samples were taken on is named, not '?'\n%s", tables)
        .contains("| " + Thread.currentThread().getName() + " |");
    assertThat(tables).as("the hot method is a com.arcadedb frame, so it appears with a file:line\n%s", tables)
        .contains("com/arcadedb/benchmarks/retrieval/JfrTopFramesTest.java:").contains("JfrTopFramesTest.hotLoop");
  }

  @Test
  void frameAndThreadGroupFormatting() {
    assertThat(JfrTopFrames.threadGroup("http-client-12")).isEqualTo("http-client");
    assertThat(JfrTopFrames.threadGroup("XNIO-1 task-7")).isEqualTo("XNIO-1 task");
    assertThat(JfrTopFrames.threadGroup("main")).isEqualTo("main");
    assertThat(JfrTopFrames.shortName("com.arcadedb.index.lsm.LSMTreeIndexMutable")).isEqualTo("LSMTreeIndexMutable");
  }

  private static void hotLoop() {
    final long until = System.nanoTime() + 600_000_000L;
    final List<Object> keep = new ArrayList<>();
    long acc = 0;
    while (System.nanoTime() < until) {
      final long[] garbage = new long[64];
      for (int i = 0; i < garbage.length; i++)
        garbage[i] = acc += i * 31L;
      if (keep.size() < 1000)
        keep.add(garbage);
      else
        keep.clear();
    }
    sink = keep;
  }
}
