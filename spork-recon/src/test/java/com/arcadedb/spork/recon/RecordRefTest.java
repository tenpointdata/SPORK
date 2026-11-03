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

import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.keys.UpstreamRid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The three id forms hydrate accepts (01-architecture 2.5, 7.2): upstream RIDs, {@code record_id}s and {@code imp:} ids. */
class RecordRefTest {

  @Test
  void anUpstreamRidPacksClusterAndPosition() {
    final RecordRef ref = RecordRef.parse("168:8");

    assertThat(ref.kind()).isEqualTo(RecordRef.Kind.UPSTREAM_RID);
    assertThat(ref.value()).isEqualTo((168L << 40) | 8L);
    assertThat(UpstreamRid.format(ref.value())).isEqualTo("168:8");
    assertThat(RecordRef.parse("#168:8")).as("OrientDB's own spelling").isEqualTo(ref);
  }

  @Test
  void aRecordIdIsAnUnsignedDecimalAndCarriesItsPeriod() {
    final long recordId = RecordId.of(3, 12345L);
    final RecordRef ref = RecordRef.parse(Long.toString(recordId));

    assertThat(ref.kind()).isEqualTo(RecordRef.Kind.RECORD_ID);
    assertThat(ref.value()).isEqualTo(recordId);
    assertThat(RecordId.period(ref.value())).isEqualTo(3);
  }

  @Test
  void anImportedIdKeepsTheAnalystsOwnString() {
    final RecordRef ref = RecordRef.parse("imp:my-own:42");

    assertThat(ref.kind()).isEqualTo(RecordRef.Kind.IMPORTED);
    assertThat(ref.importedId()).isEqualTo("my-own:42");
  }

  @Test
  void malformedIdsAreRefused() {
    for (final String id : List.of("", "abc", "168:", ":8", "1:2:3", "-1", "8388608:0", "1:1099511627776", "imp:",
        "99999999999999999999", "imp:" + "x".repeat(257), "168: 8", "１６８:８"))
      assertThat(RecordRef.parse(id)).as(id).isNull();
    assertThat(RecordRef.parse(null)).isNull();
  }
}
