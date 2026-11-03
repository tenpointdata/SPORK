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
package com.arcadedb.spork.recon;

import com.arcadedb.database.ProtocolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hydrate chunk is a client's request running on a thread of the plugin's own, so it must carry the request's
 * {@link ProtocolContext}: an untagged worker reads {@code INTERNAL}, which the HA snapshot gate exempts and the metrics
 * attribute to the engine (issue #10094, {@code ProtocolContextCensusTest}).
 */
class HydratePoolTest {
  private HydratePool pool;

  @AfterEach
  void tearDown() {
    ProtocolContext.clear();
    if (pool != null)
      pool.close();
  }

  @Test
  void aChunkOnAWorkerRunsUnderTheRequestsProtocolAndLeavesTheWorkerUntagged() throws Exception {
    pool = new HydratePool(1, 4);
    final String[] seen = new String[2];

    pool.getExecutorService().submit(() -> HydratePool.runTagged("http", () -> seen[0] = ProtocolContext.get()))
        .get(10, TimeUnit.SECONDS);
    // the single worker again, now running something that is not a chunk
    pool.getExecutorService().submit(() -> seen[1] = ProtocolContext.get()).get(10, TimeUnit.SECONDS);

    assertThat(seen[0]).isEqualTo("http");
    assertThat(seen[1]).as("the tag does not outlive the chunk on a pooled thread").isEqualTo(ProtocolContext.INTERNAL);
  }

  @Test
  void aChunkTheCallerRunsItselfRestoresTheCallersTag() {
    final String[] seen = new String[1];

    ProtocolContext.set("bolt");
    HydratePool.runTagged("http", () -> seen[0] = ProtocolContext.get());
    assertThat(seen[0]).isEqualTo("http");
    assertThat(ProtocolContext.get()).isEqualTo("bolt");

    ProtocolContext.clear();
    HydratePool.runTagged("http", () -> {
    });
    assertThat(ProtocolContext.get()).isEqualTo(ProtocolContext.INTERNAL);
  }

  @Test
  void aFailingChunkStillRestoresTheTag() {
    ProtocolContext.set("bolt");
    try {
      HydratePool.runTagged("http", () -> {
        throw new SecurityException("no grant");
      });
    } catch (final SecurityException expected) {
      // the refusal travels to the request as it was raised
    }
    assertThat(ProtocolContext.get()).isEqualTo("bolt");
  }
}
