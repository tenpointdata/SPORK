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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour the golden vectors do not pin: helper forms, bounds and the JS string primitives. */
class SporkKeysTest {

  @Test
  void sevenDigitPhoneContributesBothKeysWhenTheAreaCodeIsInferred() {
    assertThat(SporkKeys.phoneKeys("555-0142", "Houston", "TX")).containsExactly("ph:5550142", "ph:2815550142");
    assertThat(SporkKeys.isInferredAreaCode("555-0142", "Houston", "TX")).isTrue();
    assertThat(SporkKeys.phoneKeys("555-0142", "Boise", "ID")).containsExactly("ph:5550142");
    assertThat(SporkKeys.phoneKeys("(713) 555-0142", "Houston", "TX")).containsExactly("ph:7135550142");
    assertThat(SporkKeys.phoneKeys("call me", "Houston", "TX")).isEmpty();
    assertThat(SporkKeys.phone("+1 713 555 0142")).isEqualTo("ph:7135550142");
  }

  @Test
  void onlyLowercaseSha256HexPacks() {
    final String hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    assertThat(SporkKeys.unpackImageHash(SporkKeys.packImageHash(hex))).isEqualTo(hex);
    assertThat(SporkKeys.packImageHash(hex.toUpperCase())).isNull();
    assertThat(SporkKeys.packImageHash("abc")).isNull();
    assertThat(SporkKeys.packImageHash(null)).isNull();
    assertThat(SporkKeys.imageHash("AbC")).isEqualTo("ih:AbC");
  }

  @Test
  void upstreamRidBounds() {
    final long p = UpstreamRid.parse("168:8");
    assertThat(UpstreamRid.clusterId(p)).isEqualTo(168);
    assertThat(UpstreamRid.position(p)).isEqualTo(8L);
    assertThat(UpstreamRid.parse("99999999999999999999:1")).isEqualTo(UpstreamRid.INVALID);
    assertThat(UpstreamRid.parse(null)).isEqualTo(UpstreamRid.INVALID);
    assertThat(UpstreamRid.parse("#")).isEqualTo(UpstreamRid.INVALID);
    assertThat(UpstreamRid.parse(":5")).isEqualTo(UpstreamRid.INVALID);
    assertThatThrownBy(() -> UpstreamRid.pack(-1, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordIdCarriesItsPeriod() {
    final long id = RecordId.of(12, 123_456_789L);
    assertThat(RecordId.period(id)).isEqualTo(12);
    assertThat(RecordId.sequence(id)).isEqualTo(123_456_789L);
    assertThat(RecordId.of(RecordId.MAX_PERIOD, RecordId.MAX_SEQUENCE)).isPositive();
    assertThatThrownBy(() -> RecordId.of(RecordId.MAX_PERIOD + 1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RecordId.of(0, RecordId.MAX_SEQUENCE + 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void textHashSeparatesTitleFromBody() {
    assertThat(TextHash.of("Hi", "there")).hasSize(TextHash.LENGTH).isNotEqualTo(TextHash.of("Hit", "here"));
    assertThat(TextHash.of(null, null)).isEqualTo(TextHash.of("", ""));
  }

  @Test
  void jsWhitespaceIsNotJavaWhitespace() {
    // JS trim keeps C0 controls that String.trim() would strip, and strips NBSP and U+FEFF that it would keep
    assertThat(JsText.trim("\u0001a\u0001")).isEqualTo("\u0001a\u0001");
    assertThat(JsText.trim("\u00a0\ufeffa\u3000")).isEqualTo("a");
    assertThat(JsText.collapseWhitespace("a \t\u00a0b c")).isEqualTo("a b c");
    assertThat(JsText.collapseWhitespace("a b")).isSameAs("a b");
    assertThat(JsText.digits("٧７ 7")).isEqualTo("7");
  }

  @Test
  void intakeSubjectSortsDedupesAndBounds() {
    assertThat(ReconNormalizers.auditIntakeSubject(List.of("B@x.com", "+1 (713) 555-0142", "b@x.com", "", "7135550142")))
        .isEqualTo("7135550142, b@x.com");
    final List<String> many = List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "b1", "b2", "b3", "b4", "b5");
    assertThat(ReconNormalizers.auditIntakeSubject(many)).endsWith(" (+2 more)").startsWith("a1, a2");
  }
}
