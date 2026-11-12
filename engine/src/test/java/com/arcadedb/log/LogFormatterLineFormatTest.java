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
package com.arcadedb.log;

import com.arcadedb.utility.AnsiLogFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-12, two findings about the text log line:
 * <ul>
 * <li>the level column was cut to five characters, so SEVERE was written {@code SEVER} and WARNING {@code WARNI}, and
 * {@code grep SEVERE} found nothing;</li>
 * <li>the line separator was written at the START of a record, so each record's last line stayed unterminated until the
 * next record: journald (and any line reader) received it only then, a minute late on a quiet node.</li>
 * </ul>
 */
class LogFormatterLineFormatTest {

  private static final String EOL = System.lineSeparator();

  private static LogRecord record(final Level level, final String message, final Throwable thrown) {
    final LogRecord record = new LogRecord(level, message);
    record.setLoggerName("com.arcadedb.server.ha.raft.ArcadeStateMachine");
    record.setThrown(thrown);
    return record;
  }

  @Test
  void theLevelIsWrittenInFull() {
    final LogFormatter formatter = new LogFormatter();
    assertThat(formatter.format(record(Level.SEVERE, "WAL version gap", null)))
        .contains(" SEVERE  [ArcadeStateMachine] WAL version gap");
    assertThat(formatter.format(record(Level.WARNING, "slow", null))).contains(" WARNING [ArcadeStateMachine] slow");
    assertThat(formatter.format(record(Level.INFO, "up", null))).contains(" INFO    [ArcadeStateMachine] up");
    // The console formatter may wrap the column in colour codes, so only the name is checked there
    final AnsiLogFormatter console = new AnsiLogFormatter();
    assertThat(console.format(record(Level.SEVERE, "x", null))).contains("SEVERE").doesNotContain("SEVER ");
    assertThat(console.format(record(Level.WARNING, "x", null))).contains("WARNING");
  }

  /** The column keeps one width, so the requester still starts at the same offset on every line. */
  @Test
  void theLevelColumnKeepsOneWidth() {
    final LogFormatter formatter = new LogFormatter();
    final int severe = formatter.format(record(Level.SEVERE, "x", null)).indexOf('[');
    for (final Level level : List.of(Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST))
      assertThat(formatter.format(record(level, "x", null)).indexOf('[')).as(level.getName()).isEqualTo(severe);
  }

  @Test
  void aRecordEndsWithItsLineSeparatorAndDoesNotStartWithOne() {
    for (final LogFormatter formatter : List.of(new LogFormatter(), new AnsiLogFormatter())) {
      final String line = formatter.format(record(Level.INFO, "Server started", null));
      assertThat(line).as(formatter.getClass().getSimpleName()).endsWith("Server started" + EOL).doesNotStartWith(EOL);
      assertThat(line.split(EOL, -1)).hasSize(2);
    }
  }

  /** A stack trace follows its line and ends terminated too, with no blank line left before the next record. */
  @Test
  void aRecordWithAStackTraceEndsTerminatedWithoutABlankLine() {
    final String text = new LogFormatter().format(record(Level.SEVERE, "apply failed", new IllegalStateException("boom")));

    assertThat(text).startsWith(text.substring(0, 23) + " SEVERE  [ArcadeStateMachine] apply failed" + EOL
        + IllegalStateException.class.getName() + ": boom");
    assertThat(text).endsWith(EOL).doesNotEndWith(EOL + EOL).doesNotStartWith(EOL);
  }
}
