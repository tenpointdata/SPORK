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
package com.arcadedb.spork.keys;

/**
 * {@code Record.record_id}, assigned by SPORK with the period in its high bits so a hydrate by {@code record_id} needs no
 * {@code recdir} probe (01-architecture.md 2.5): {@code period << 48 | sequence}. A LONG rather than a UUID because the HASH
 * point index keeps fixed-width keys on 4 KB pages (0.4). The period is the {@code SHORT} that {@code recdir} stores, 0 to
 * 32767, so every id is non-negative.
 */
public final class RecordId {
  public static final int  SEQUENCE_BITS = 48;
  public static final int  MAX_PERIOD    = Short.MAX_VALUE;
  public static final long MAX_SEQUENCE  = (1L << SEQUENCE_BITS) - 1;

  private RecordId() {
  }

  public static long of(final int period, final long sequence) {
    if (period < 0 || period > MAX_PERIOD || sequence < 0 || sequence > MAX_SEQUENCE)
      throw new IllegalArgumentException("record_id out of range: period " + period + ", sequence " + sequence);
    return (long) period << SEQUENCE_BITS | sequence;
  }

  public static int period(final long recordId) {
    return (int) (recordId >>> SEQUENCE_BITS);
  }

  public static long sequence(final long recordId) {
    return recordId & MAX_SEQUENCE;
  }
}
