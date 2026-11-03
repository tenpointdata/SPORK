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
package com.arcadedb.index.fulltext;

import com.arcadedb.index.IndexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scan path of the top-k scorer sizes its tables from posting counts. In int arithmetic a bucket past 2^30 postings sized the
 * accumulator at 0 slots and the postings arrays at a negative length, so the query crashed with an
 * {@code ArrayIndexOutOfBoundsException} or a {@code NegativeArraySizeException}. Sizes are computed in long and refused clearly.
 */
class FullTextTopKCapacityTest {

  @Test
  void accumulatorCapacityIsAPowerOfTwoAtLeastTwiceTheExpectedPostings() {
    assertThat(FullTextTopK.accumulatorCapacity(0)).isEqualTo(32);
    assertThat(FullTextTopK.accumulatorCapacity(100)).isEqualTo(256);
    assertThat(FullTextTopK.accumulatorCapacity(1L << 29)).isEqualTo(1 << 30);
  }

  @Test
  void accumulatorPastTheLargestTableIsRefusedNotOverflowed() {
    assertThatThrownBy(() -> FullTextTopK.accumulatorCapacity((1L << 29) + 1)).isInstanceOf(IndexException.class)
        .hasMessageContaining("too many for the top-k scorer");
    assertThatThrownBy(() -> FullTextTopK.accumulatorCapacity(3_000_000_000L)).isInstanceOf(IndexException.class);
  }

  @Test
  void postingsGrowWithoutOverflowing() {
    assertThat(FullTextTopK.grownCapacity(16)).isEqualTo(32);
    assertThat(FullTextTopK.grownCapacity(1 << 30)).isEqualTo(FullTextTopK.MAX_ARRAY_LENGTH);
    assertThat(FullTextTopK.grownCapacity((1 << 30) + 5)).isEqualTo(FullTextTopK.MAX_ARRAY_LENGTH);
    assertThatThrownBy(() -> FullTextTopK.grownCapacity(FullTextTopK.MAX_ARRAY_LENGTH)).isInstanceOf(IndexException.class);
  }
}
