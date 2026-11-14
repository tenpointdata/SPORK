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
package com.arcadedb.database;

import com.arcadedb.Profiler;
import com.arcadedb.TestHelper;
import com.arcadedb.exception.ConcurrentModificationException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 5 of the SPORK stress report: {@code txRetries} counts the transaction blocks a retryable conflict made run
 * again, which the page-conflict counter cannot tell (most of its conflicts are merged and cost no retry).
 */
class TxRetriesCounterTest extends TestHelper {

  @Test
  void eachReRunOfTheBlockIsCounted() {
    database.getSchema().createDocumentType("Doc");
    final long before = retries();
    final long profilerBefore = Profiler.INSTANCE.toJSON().getJSONObject("txRetries").getLong("count");

    final AtomicInteger runs = new AtomicInteger();
    database.transaction(() -> {
      database.newDocument("Doc").set("n", runs.get()).save();
      if (runs.incrementAndGet() < 3)
        throw new ConcurrentModificationException("test conflict");
    }, false, 5);

    assertThat(runs.get()).isEqualTo(3);
    assertThat(retries()).isEqualTo(before + 2);
    assertThat(Profiler.INSTANCE.toJSON().getJSONObject("txRetries").getLong("count")).isGreaterThanOrEqualTo(profilerBefore + 2);
    assertThat(database.countType("Doc", false)).isEqualTo(1);
  }

  @Test
  void theLastFailedAttemptIsNotARetry() {
    final long before = retries();

    assertThatThrownBy(() -> database.transaction(() -> {
      throw new ConcurrentModificationException("test conflict");
    }, false, 2)).isInstanceOf(ConcurrentModificationException.class);

    assertThat(retries()).isEqualTo(before + 1);
  }

  private long retries() {
    return ((Number) ((LocalDatabase) database).getStats().get("txRetries")).longValue();
  }
}
