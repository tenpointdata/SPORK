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
package com.arcadedb.server.http.handler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AbstractQueryHandler#elevatedMaxResultRows(int, int)}: how
 * {@code arcadedb.server.httpQueryElevatedMaxResultRows} combines with {@code arcadedb.server.httpQueryMaxResultRows}
 * for a caller entitled to the elevated ceiling. It may only ever raise the ordinary ceiling.
 */
class ElevatedMaxResultRowsTest {

  private static final int CEILING = 2_000;

  @Test
  void aNegativeValueFollowsTheOrdinaryCeiling() {
    assertThat(AbstractQueryHandler.elevatedMaxResultRows(CEILING, -1)).isEqualTo(CEILING);
    assertThat(AbstractQueryHandler.elevatedMaxResultRows(CEILING, Integer.MIN_VALUE)).isEqualTo(CEILING);
  }

  @Test
  void aHigherValueRaisesTheCeiling() {
    assertThat(AbstractQueryHandler.elevatedMaxResultRows(CEILING, 50_000)).isEqualTo(50_000);
  }

  @Test
  void aLowerValueNeverLowersTheCeiling() {
    assertThat(AbstractQueryHandler.elevatedMaxResultRows(CEILING, 10)).isEqualTo(CEILING);
    assertThat(AbstractQueryHandler.elevatedMaxResultRows(CEILING, CEILING)).isEqualTo(CEILING);
  }

  @Test
  void zeroMeansUnlimitedForTheTrustedCaller() {
    assertThat(AbstractQueryHandler.elevatedMaxResultRows(CEILING, 0)).isEqualTo(0);
  }

  @Test
  void anUnlimitedOrdinaryCeilingHasNothingToRaise() {
    for (final int unlimited : new int[] { -1, 0 }) {
      assertThat(AbstractQueryHandler.elevatedMaxResultRows(unlimited, 50_000)).isEqualTo(unlimited);
      assertThat(AbstractQueryHandler.elevatedMaxResultRows(unlimited, 0)).isEqualTo(unlimited);
    }
  }
}
