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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeCacheTest {

  @Test
  void returnsWhatWasPutForTheSameModelAndTextOnly() {
    final ProbeCache cache = new ProbeCache(1_000);
    final float[] v = { 1f, 2f };
    cache.put("m@1", "hello", v);

    assertThat(cache.get("m@1", "hello")).isSameAs(v);
    assertThat(cache.get("m@2", "hello")).isNull();
    assertThat(cache.get("m@1", "hello ")).isNull();
    assertThat(cache.hits()).isEqualTo(1);
    assertThat(cache.misses()).isEqualTo(2);
  }

  @Test
  void keyDoesNotConfuseModelAndTextBoundaries() {
    final ProbeCache cache = new ProbeCache(1_000);
    cache.put("a@1", "bc", new float[] { 1f });
    assertThat(cache.get("a@1b", "c")).isNull();
  }

  @Test
  void staysWithinItsBoundAndEvictsTheLeastRecentlyUsed() {
    final ProbeCache cache = new ProbeCache(64);
    for (int i = 0; i < 10_000; i++)
      cache.put("m@1", "text " + i, new float[] { i });
    assertThat(cache.size()).isLessThanOrEqualTo(64);
    assertThat(cache.get("m@1", "text 0")).isNull();
    assertThat(cache.get("m@1", "text 9999")).isNotNull();
  }

  @Test
  void aZeroSizedCacheIsDisabled() {
    final ProbeCache cache = new ProbeCache(0);
    cache.put("m@1", "x", new float[] { 1f });
    assertThat(cache.get("m@1", "x")).isNull();
    assertThat(cache.size()).isZero();
  }
}
