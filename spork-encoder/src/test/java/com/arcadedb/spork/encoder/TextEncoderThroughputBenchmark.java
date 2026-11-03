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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.spork.encoder;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S-TEXT throughput on this machine, for docs/spork/workstreams/ws13-encoder.md: texts per second through the
 * scheduler's bulk lane at a fixed core budget, per thread split, micro-batch and token length, plus the latency of one
 * probe on an idle sidecar. Prints a Markdown table and writes it to {@code target/ws13-throughput.md}.
 * <p>
 * Run: {@code mvn -o -pl spork-encoder test -Dtest=TextEncoderThroughputBenchmark -DexcludedGroups= -Dgroups=benchmark}
 * ({@code -Dspork.bench.cores=4 -Dspork.bench.seconds=8} to change the budget). Needs the exported model.
 */
@Tag("benchmark")
class TextEncoderThroughputBenchmark {
  private static final String[] WORDS = ("sweet discreet new in town available call me now 24/7 nueva en la ciudad muy cariñosa llámame "
      + "solo hoy nouvelle en ville disponible ce soir appelez-moi 新到的女孩 24小时服务 请致电 новая девушка в городе звоните "
      + "ฉันมาใหม่ในเมือง โทรหาฉันได้ c4ll m3 🔥 f!ve 💋 no b1@ck m3n hotel incall outcall gfe roses donation kisses 555-0142 snap kik").split(" ");

  private final StringBuilder report = new StringBuilder();

  @Test
  void measure() throws Exception {
    final Path models = Path.of(System.getProperty("spork.encoder.models", "models"));
    assumeTrue(Files.isRegularFile(models.resolve("gte-multilingual-base/1/model.json")), "S-TEXT model not exported");
    System.setProperty("ai.djl.offline", "true");
    final int cores = Integer.getInteger("spork.bench.cores", 4);
    final double seconds = Double.parseDouble(System.getProperty("spork.bench.seconds", "8"));

    line("Machine: %s, %d CPUs, %s %s; budget %d cores, %.0f s per point", System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors(), System.getProperty("java.vm.name"), System.getProperty("java.version"), cores, seconds);

    // 1. Thread split and micro-batch at 128 tokens
    line("");
    line("| workers x intra-op | micro-batch | tokens | texts/s | core-ms per text |");
    line("|---|---|---|---|---|");
    for (final int intraOp : new int[] { 1, 2, cores })
      for (final int batch : new int[] { 1, 8, 32 })
        point(models, cores / intraOp, intraOp, batch, 128, seconds);

    // 2. The default split across token lengths
    line("");
    line("| workers x intra-op | micro-batch | tokens | texts/s | core-ms per text |");
    line("|---|---|---|---|---|");
    for (final int tokens : new int[] { 16, 32, 64, 128, 256, 512 })
      point(models, cores, 1, 32, tokens, seconds);

    // 3. One probe on an idle sidecar
    line("");
    line("| intra-op threads | tokens | probe p50 ms | probe p95 ms |");
    line("|---|---|---|---|");
    for (final int intraOp : new int[] { 1, cores })
      for (final int tokens : new int[] { 16, 64, 128 })
        probe(models, intraOp, tokens);

    Files.writeString(Path.of("target", "ws13-throughput.md"), report);
  }

  private void point(final Path models, final int workers, final int intraOp, final int batch, final int tokens, final double seconds)
      throws Exception {
    try (final ModelRegistry registry = ModelRegistry.load(models, (m, dir) -> new OnnxTextEncoder(m, dir, intraOp));
        final EncodeScheduler scheduler = new EncodeScheduler(workers, 16, batch, 16, 1 << 16)) {
      final OnnxTextEncoder encoder = (OnnxTextEncoder) registry.resolve("S-TEXT", null);
      final String[] texts = texts(encoder, tokens, 512, new Random(tokens));
      final int request = Math.max(workers * batch * 4, 64);
      // Warm-up: every worker's buffers and ONNX Runtime's arena reach their size
      scheduler.encodeBulk(encoder, slice(texts, 0, request), 600_000);
      int done = 0;
      final long start = System.nanoTime();
      while (System.nanoTime() - start < seconds * 1e9) {
        scheduler.encodeBulk(encoder, slice(texts, done, request), 600_000);
        done += request;
      }
      final double elapsed = (System.nanoTime() - start) / 1e9;
      final double perSecond = done / elapsed;
      line("| %d x %d | %d | %d | %.1f | %.1f |", workers, intraOp, batch, tokens, perSecond, workers * intraOp * 1000.0 / perSecond);
      assertThat(perSecond).isPositive();
    }
  }

  private void probe(final Path models, final int intraOp, final int tokens) throws Exception {
    try (final ModelRegistry registry = ModelRegistry.load(models, (m, dir) -> new OnnxTextEncoder(m, dir, intraOp));
        final EncodeScheduler scheduler = new EncodeScheduler(1, 16, 32, 16, 16)) {
      final OnnxTextEncoder encoder = (OnnxTextEncoder) registry.resolve("S-TEXT", null);
      final String[] texts = texts(encoder, tokens, 120, new Random(31 * tokens));
      final double[] ms = new double[100];
      for (int i = 0; i < 20; i++)
        scheduler.submitProbe(encoder, texts[i]).get();
      for (int i = 0; i < ms.length; i++) {
        final long start = System.nanoTime();
        scheduler.submitProbe(encoder, texts[20 + i]).get();
        ms[i] = (System.nanoTime() - start) / 1e6;
      }
      Arrays.sort(ms);
      line("| %d | %d | %.1f | %.1f |", intraOp, tokens, ms[49], ms[94]);
    }
  }

  /** Distinct texts of exactly {@code tokens} tokens, specials included (truncation cuts the last word to fit). */
  private static String[] texts(final OnnxTextEncoder encoder, final int tokens, final int count, final Random random) {
    final List<String> texts = new ArrayList<>(count);
    while (texts.size() < count) {
      final StringBuilder text = new StringBuilder();
      while (encoder.tokenize(text.toString()).length < tokens)
        text.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
      texts.add(text.toString());
    }
    return texts.toArray(new String[0]);
  }

  private static String[] slice(final String[] texts, final int from, final int count) {
    final String[] out = new String[count];
    for (int i = 0; i < count; i++)
      out[i] = texts[(from + i) % texts.length];
    return out;
  }

  private void line(final String format, final Object... args) throws IOException {
    final String line = String.format(Locale.ROOT, format, args);
    System.out.println(line);
    report.append(line).append('\n');
  }
}
