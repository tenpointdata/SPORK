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
package com.arcadedb.index.vector;

import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.RidSet;
import com.arcadedb.utility.RidHashSet;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPORK retrieval-speed workstream S10 (docs/spork/02-retrieval-speed.md, 4.2): a set-scoped vector search used to materialize
 * one {@link RID} per visited node to test it against the {@code Set<RID>} allow-list, and the set hashed it boxed. The
 * location index now answers membership in a primitive set ({@link VectorLocationIndex#isLocationIn}) from the two words it
 * holds, and {@link LiveVectorBitsFilter} takes that path for an allow-list that can answer a primitive pair - a
 * {@link RidSet} or a {@link RidHashSet}. The verdict must be the one the {@code Set<RID>} path gives, for live, deleted and
 * out-of-range ordinals alike.
 */
class LiveVectorBitsFilterAllowListTest {
  private static final int VECTORS = 2_000;

  private final VectorLocationIndex locations = new VectorLocationIndex();
  private final int[]               ordinalMap = new int[VECTORS];
  private final Set<RID>            allowedAsHashSet = new HashSet<>();
  private final RidSet              allowedAsRidSet = new RidSet();
  private final RidHashSet          allowedAsRidHashSet = new RidHashSet();

  LiveVectorBitsFilterAllowListTest() {
    for (int i = 0; i < VECTORS; i++) {
      // three buckets, positions that are not the ids, so a membership answered by the id alone would be wrong
      final RID rid = new RID(i % 3, 10_000L + i * 7L);
      final int id = locations.addVector(false, i * 16L, rid);
      ordinalMap[i] = id;
      if (i % 2 == 0) {
        allowedAsHashSet.add(rid);
        allowedAsRidSet.add(rid);
        allowedAsRidHashSet.add(rid.getBucketId(), rid.getPosition());
      }
    }
    // every 10th vector is deleted, allow-listed or not
    for (int i = 0; i < VECTORS; i += 10)
      locations.markDeleted(ordinalMap[i]);
  }

  @Test
  void isLocationInAnswersMembershipOfALiveVectorWithoutARid() {
    for (int i = 0; i < VECTORS; i++) {
      final boolean live = i % 10 != 0;
      final boolean allowed = i % 2 == 0;
      assertThat(locations.isLocationIn(ordinalMap[i], allowedAsRidHashSet)).as("RidHashSet, vector %d", i).isEqualTo(live && allowed);
      assertThat(locations.isLocationIn(ordinalMap[i], allowedAsRidSet)).as("RidSet, vector %d", i).isEqualTo(live && allowed);
    }
    assertThat(locations.isLocationIn(VECTORS + 100, allowedAsRidHashSet)).as("an id that was never given out").isFalse();
    assertThat(locations.isLocationIn(-1, allowedAsRidSet)).isFalse();
    assertThat(locations.isLocationIn(ordinalMap[2], new RidHashSet())).as("an empty set holds nothing").isFalse();
  }

  @Test
  void thePrimitiveAllowListGivesTheVerdictOfTheSetOfRids() {
    final LiveVectorBitsFilter reference = new LiveVectorBitsFilter(allowedAsHashSet, ordinalMap, locations);
    final LiveVectorBitsFilter viaRidSet = new LiveVectorBitsFilter(allowedAsRidSet, ordinalMap, locations);
    final LiveVectorBitsFilter viaRidHashSet = new LiveVectorBitsFilter(allowedAsRidHashSet, ordinalMap, locations, null, null);

    for (int ordinal = -5; ordinal < VECTORS + 5; ordinal++) {
      final boolean expected = reference.get(ordinal);
      assertThat(viaRidSet.get(ordinal)).as("RidSet allow-list, ordinal %d", ordinal).isEqualTo(expected);
      assertThat(viaRidHashSet.get(ordinal)).as("RidHashSet allow-list, ordinal %d", ordinal).isEqualTo(expected);
      if (ordinal >= 0 && ordinal < VECTORS)
        assertThat(expected).isEqualTo(ordinal % 2 == 0 && ordinal % 10 != 0);
    }
  }

  @Test
  void supersededRidsAreStillRejectedOnThePrimitivePath() {
    final Set<RID> superseded = new HashSet<>();
    superseded.add(locations.getRid(ordinalMap[2]));
    superseded.add(locations.getRid(ordinalMap[4]));

    final LiveVectorBitsFilter filter = new LiveVectorBitsFilter(allowedAsRidSet, ordinalMap, locations, superseded);
    assertThat(filter.get(2)).isFalse();
    assertThat(filter.get(4)).isFalse();
    assertThat(filter.get(6)).isTrue();
    assertThat(filter.get(3)).as("not allow-listed").isFalse();
  }

  @Test
  void anEmptyPrimitiveAllowListMeansEveryLiveVector() {
    final LiveVectorBitsFilter filter = new LiveVectorBitsFilter(new RidHashSet(), ordinalMap, locations, null, null);
    assertThat(filter.get(1)).isTrue();
    assertThat(filter.get(10)).as("deleted").isFalse();
  }

  /** The primitive path materializes nothing per visited node: measured against the {@code Set<RID>} path, on this JVM. */
  @Test
  void thePrimitiveAllowListAllocatesNothingPerVisitedNode() {
    final LiveVectorBitsFilter viaHashSet = new LiveVectorBitsFilter(allowedAsHashSet, ordinalMap, locations);
    final LiveVectorBitsFilter viaRidHashSet = new LiveVectorBitsFilter(allowedAsRidHashSet, ordinalMap, locations, null, null);
    final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long threadId = Thread.currentThread().threadId();
    final int calls = 200_000;

    long boxed = 0, primitive = 0;
    for (int round = 0; round < 4; round++) {
      int accepted = 0;
      final long beforeBoxed = threads.getThreadAllocatedBytes(threadId);
      for (int i = 0; i < calls; i++)
        if (viaHashSet.get(i % VECTORS))
          accepted++;
      boxed = threads.getThreadAllocatedBytes(threadId) - beforeBoxed;

      final long beforePrimitive = threads.getThreadAllocatedBytes(threadId);
      for (int i = 0; i < calls; i++)
        if (viaRidHashSet.get(i % VECTORS))
          accepted--;
      primitive = threads.getThreadAllocatedBytes(threadId) - beforePrimitive;
      assertThat(accepted).isZero();
    }
    assertThat(primitive).as("bytes of %d primitive calls, against %d boxed", calls, boxed).isLessThan(boxed / 4);
  }

  /**
   * SPORK S10 review finding (minor): the bitmap path is taken for exactly {@link RidSet}. A subclass that narrows
   * {@code contains(Object)} - a predicate on top of the bitmap - must be asked through its override, not answered from the
   * bitmap it inherits.
   */
  @Test
  void aRidSetSubclassThatOverridesContainsIsAskedThroughItsOverride() {
    final RidSet narrowed = new RidSet() {
      @Override
      public boolean contains(final Object o) {
        // only bucket 0 of the bitmap's members
        return super.contains(o) && ((RID) o).getBucketId() == 0;
      }
    };
    for (final RID rid : allowedAsHashSet)
      narrowed.add(rid);

    final LiveVectorBitsFilter filter = new LiveVectorBitsFilter(narrowed, ordinalMap, locations);
    for (int ordinal = 0; ordinal < VECTORS; ordinal++)
      assertThat(filter.get(ordinal)).as("ordinal %d", ordinal)
          .isEqualTo(ordinal % 2 == 0 && ordinal % 10 != 0 && ordinal % 3 == 0);
  }
}
