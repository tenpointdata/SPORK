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

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Java Flight Recorder file into the tables a flame graph would give you, in text: top frames by CPU (self and
 * inclusive), top allocation sites, monitor contention, thread parks and synchronous I/O inside the sampled threads. Frames
 * are printed as {@code module/path/Class.java:line} so a finding points at a file.
 * <p>
 * Standalone: {@code java ... JfrTopFrames recording.jfr [topN]}.
 */
public final class JfrTopFrames {
  private static final String EXECUTION_SAMPLE  = "jdk.ExecutionSample";
  private static final String ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample";
  private static final String MONITOR_ENTER     = "jdk.JavaMonitorEnter";
  private static final String THREAD_PARK       = "jdk.ThreadPark";
  private static final String GC                = "jdk.GarbageCollection";
  private static final Set<String> IO_EVENTS    = Set.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite",
      "jdk.FileForce");

  private JfrTopFrames() {
  }

  public static void main(final String[] args) throws IOException {
    if (args.length < 1) {
      System.err.println("usage: JfrTopFrames <recording.jfr> [topN]");
      System.exit(2);
    }
    System.out.println(analyze(Paths.get(args[0]), args.length > 1 ? Integer.parseInt(args[1]) : 25));
  }

  /** One accumulator: a count and a weight (bytes, nanoseconds) keyed by frame or class. */
  private static final class Tally {
    final Map<String, long[]> byKey = new HashMap<>();

    void add(final String key, final long count, final long weight) {
      final long[] v = byKey.computeIfAbsent(key, k -> new long[2]);
      v[0] += count;
      v[1] += weight;
    }

    List<Map.Entry<String, long[]>> top(final int n, final int sortIndex) {
      final List<Map.Entry<String, long[]>> entries = new ArrayList<>(byKey.entrySet());
      entries.sort((a, b) -> Long.compare(b.getValue()[sortIndex], a.getValue()[sortIndex]));
      return entries.size() > n ? entries.subList(0, n) : entries;
    }
  }

  public static String analyze(final Path recording, final int topN) throws IOException {
    final Tally selfCpu = new Tally(), selfCpuArcade = new Tally(), inclusiveArcade = new Tally();
    final Tally allocBySite = new Tally(), allocByClass = new Tally();
    final Tally monitors = new Tally(), parks = new Tally(), io = new Tally();
    final Tally threads = new Tally();
    long cpuSamples = 0, gcCount = 0, gcNanos = 0, allocWeight = 0;

    for (final RecordedEvent event : RecordingFile.readAllEvents(recording)) {
      final String type = event.getEventType().getName();
      switch (type) {
      case EXECUTION_SAMPLE -> {
        cpuSamples++;
        final RecordedStackTrace stack = event.getStackTrace();
        if (stack == null)
          break;
        final List<RecordedFrame> frames = stack.getFrames();
        if (frames.isEmpty())
          break;
        selfCpu.add(frame(frames.get(0)), 1, 0);
        final String selfArcade = firstArcadeFrame(frames);
        if (selfArcade != null)
          selfCpuArcade.add(selfArcade, 1, 0);
        final Set<String> seen = new HashSet<>();
        for (final RecordedFrame f : frames) {
          final String key = frame(f);
          if (isArcade(f) && seen.add(key))
            inclusiveArcade.add(key, 1, 0);
        }
        threads.add(threadName(event), 1, 0);
      }
      case ALLOCATION_SAMPLE -> {
        final long weight = event.getLong("weight");
        allocWeight += weight;
        final RecordedClass cls = event.getClass("objectClass");
        allocByClass.add(cls == null ? "?" : cls.getName(), 1, weight);
        final RecordedStackTrace stack = event.getStackTrace();
        if (stack != null && !stack.getFrames().isEmpty()) {
          final String site = firstArcadeFrame(stack.getFrames());
          allocBySite.add((site != null ? site : frame(stack.getFrames().get(0))) + " <- " + (cls == null ? "?" : shortName(cls.getName())),
              1, weight);
        }
      }
      case MONITOR_ENTER -> {
        final RecordedClass cls = event.getClass("monitorClass");
        monitors.add((cls == null ? "?" : cls.getName()) + " @ " + siteOf(event), 1, event.getDuration().toNanos());
      }
      case THREAD_PARK -> parks.add(siteOf(event), 1, event.getDuration().toNanos());
      case GC -> {
        gcCount++;
        gcNanos += event.getDuration().toNanos();
      }
      default -> {
        if (IO_EVENTS.contains(type)) {
          long bytes = 0;
          if (event.hasField("bytesRead"))
            bytes = event.getLong("bytesRead");
          else if (event.hasField("bytesWritten"))
            bytes = event.getLong("bytesWritten");
          io.add(type.substring(4) + " [" + threadGroup(threadName(event)) + "] @ " + siteOf(event), 1, event.getDuration().toNanos() * 1_000
              + Math.min(bytes, 999));
        }
      }
      }
    }

    final StringBuilder sb = new StringBuilder();
    sb.append(String.format("CPU samples: %,d. Allocation samples weight: %,d MB. GC: %d collections, %,d ms total.%n%n", cpuSamples,
        allocWeight >> 20, gcCount, gcNanos / 1_000_000L));

    sb.append("### Threads sampled on CPU\n\n| thread | samples | share |\n|---|---:|---:|\n");
    for (final Map.Entry<String, long[]> e : threads.top(8, 0))
      sb.append(String.format("| %s | %d | %.1f%% |%n", e.getKey(), e.getValue()[0], pct(e.getValue()[0], cpuSamples)));

    sb.append("\n### Top frames by self CPU (all code)\n\n| frame | samples | share |\n|---|---:|---:|\n");
    for (final Map.Entry<String, long[]> e : selfCpu.top(topN, 0))
      sb.append(String.format("| %s | %d | %.1f%% |%n", e.getKey(), e.getValue()[0], pct(e.getValue()[0], cpuSamples)));

    sb.append("\n### Top ArcadeDB frames by self CPU (first com.arcadedb frame on the stack)\n\n| frame | samples | share |\n|---|---:|---:|\n");
    for (final Map.Entry<String, long[]> e : selfCpuArcade.top(topN, 0))
      sb.append(String.format("| %s | %d | %.1f%% |%n", e.getKey(), e.getValue()[0], pct(e.getValue()[0], cpuSamples)));

    sb.append("\n### Top ArcadeDB frames by inclusive CPU (share of samples with the frame anywhere on the stack)\n\n| frame | samples | share |\n|---|---:|---:|\n");
    for (final Map.Entry<String, long[]> e : inclusiveArcade.top(topN, 0))
      sb.append(String.format("| %s | %d | %.1f%% |%n", e.getKey(), e.getValue()[0], pct(e.getValue()[0], cpuSamples)));

    sb.append("\n### Top allocation sites (by sampled bytes, first com.arcadedb frame <- allocated class)\n\n| site | samples | MB | share |\n|---|---:|---:|---:|\n");
    for (final Map.Entry<String, long[]> e : allocBySite.top(topN, 1))
      sb.append(String.format("| %s | %d | %.1f | %.1f%% |%n", e.getKey(), e.getValue()[0], e.getValue()[1] / 1048576.0,
          pct(e.getValue()[1], allocWeight)));

    sb.append("\n### Top allocated classes (by sampled bytes)\n\n| class | samples | MB | share |\n|---|---:|---:|---:|\n");
    for (final Map.Entry<String, long[]> e : allocByClass.top(Math.min(topN, 15), 1))
      sb.append(String.format("| %s | %d | %.1f | %.1f%% |%n", e.getKey(), e.getValue()[0], e.getValue()[1] / 1048576.0,
          pct(e.getValue()[1], allocWeight)));

    sb.append("\n### Monitor contention (jdk.JavaMonitorEnter >= 1 ms)\n\n| monitor @ site | count | total ms |\n|---|---:|---:|\n");
    if (monitors.byKey.isEmpty())
      sb.append("| none | 0 | 0 |\n");
    for (final Map.Entry<String, long[]> e : monitors.top(topN, 1))
      sb.append(String.format("| %s | %d | %.1f |%n", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e6));

    sb.append("\n### Thread parks (jdk.ThreadPark >= 1 ms, by site)\n\n| site | count | total ms |\n|---|---:|---:|\n");
    if (parks.byKey.isEmpty())
      sb.append("| none | 0 | 0 |\n");
    for (final Map.Entry<String, long[]> e : parks.top(Math.min(topN, 12), 1))
      sb.append(String.format("| %s | %d | %.1f |%n", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e6));

    sb.append("\n### Synchronous I/O (file and socket events, by thread group and site)\n\n| event [thread group] @ site | count | total ms |\n|---|---:|---:|\n");
    if (io.byKey.isEmpty())
      sb.append("| none | 0 | 0 |\n");
    for (final Map.Entry<String, long[]> e : io.top(topN, 1))
      sb.append(String.format("| %s | %d | %.1f |%n", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e9));
    return sb.toString();
  }

  private static double pct(final long part, final long total) {
    return total == 0 ? 0.0 : 100.0 * part / total;
  }

  private static String threadName(final RecordedEvent event) {
    // an execution or allocation sample names the thread it sampled in its own field; getThread() is the event's committer
    RecordedThread t = event.hasField("sampledThread") ? event.getThread("sampledThread") : null;
    if (t == null)
      t = event.getThread();
    return t == null ? "?" : t.getJavaName();
  }

  /** Thread names with their numeric suffix removed, so a pool reads as one group. */
  static String threadGroup(final String threadName) {
    return threadName == null ? "?" : threadName.replaceAll("[-_ ]?\\d+$", "");
  }

  private static String siteOf(final RecordedEvent event) {
    final RecordedStackTrace stack = event.getStackTrace();
    if (stack == null || stack.getFrames().isEmpty())
      return "?";
    final String arcade = firstArcadeFrame(stack.getFrames());
    return arcade != null ? arcade : frame(stack.getFrames().get(0));
  }

  private static String firstArcadeFrame(final List<RecordedFrame> frames) {
    for (final RecordedFrame f : frames)
      if (isArcade(f))
        return frame(f);
    return null;
  }

  private static boolean isArcade(final RecordedFrame f) {
    return f.getMethod() != null && f.getMethod().getType().getName().startsWith("com.arcadedb.");
  }

  /** {@code module/src/main/java/pkg/Outer.java:line method} for ArcadeDB code, {@code Class.method:line} otherwise. */
  static String frame(final RecordedFrame f) {
    if (f.getMethod() == null)
      return "?";
    final String type = f.getMethod().getType().getName();
    final String method = f.getMethod().getName();
    final int line = f.getLineNumber();
    if (!type.startsWith("com.arcadedb."))
      return shortName(type) + "." + method + (line > 0 ? ":" + line : "");
    final String outer = type.indexOf('$') > 0 ? type.substring(0, type.indexOf('$')) : type;
    final String module = type.startsWith("com.arcadedb.server.") ? "server" : type.startsWith("com.arcadedb.network.") ? "network"
        : type.startsWith("com.arcadedb.benchmarks.") ? "benchmarks" : "engine";
    return module + "/src/main/java/" + outer.replace('.', '/') + ".java" + (line > 0 ? ":" + line : "") + " " + shortName(type) + "." + method;
  }

  static String shortName(final String type) {
    return type.substring(type.lastIndexOf('.') + 1);
  }
}
