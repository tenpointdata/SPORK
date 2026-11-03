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
package com.arcadedb.similarity;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link Stylometry}: bit-for-bit parity of the 24-d vector and the fingerprint with Recon's {@code identity.ts} (golden
 * values produced by running Recon's own TypeScript under bun), and the behaviour of the n-gram authorship profile.
 */
class StylometryTest {

  @Test
  void reconStyleVectorMatchesReconGoldenValues() throws IOException {
    final JSONArray golden = golden();
    assertThat(golden.length()).isGreaterThanOrEqualTo(10);
    for (int i = 0; i < golden.length(); i++) {
      final JSONObject row = golden.getJSONObject(i);
      final String text = row.getString("text");
      final JSONArray expected = row.getJSONArray("vec");
      final double[] actual = Stylometry.reconStyleVector(text);
      assertThat(actual).as("dimensions of %s", text).hasSize(Stylometry.RECON_DIMENSIONS);
      for (int d = 0; d < actual.length; d++)
        assertThat(actual[d]).as("component %d of '%s'", d, text).isCloseTo(expected.getDouble(d), within(1e-15));
    }
  }

  @Test
  void reconFingerprintMatchesReconGoldenValues() throws IOException {
    final JSONArray golden = golden();
    for (int i = 0; i < golden.length(); i++) {
      final JSONObject row = golden.getJSONObject(i);
      assertThat(Stylometry.reconFingerprint(row.getString("text"))).as("fingerprint of '%s'", row.getString("text"))
          .isEqualTo(row.getString("fp"));
    }
  }

  @Test
  void nullTextIsTheEmptyText() {
    assertThat(Stylometry.reconStyleVector(null)).isEqualTo(Stylometry.reconStyleVector(""));
    assertThat(Stylometry.reconFingerprint(null)).isEqualTo("fw0.p0000.x00");
  }

  @Test
  void profileIsUnitLengthAndDeterministic() {
    final float[] p = Stylometry.profile("Hey there!! New in town, call me now... xoxo", 256);
    assertThat(p).hasSize(256);
    assertThat(norm(p)).isCloseTo(1.0, within(1e-5));
    assertThat(Stylometry.profile("Hey there!! New in town, call me now... xoxo", 256)).isEqualTo(p);
    assertThat(norm(Stylometry.profile("", 64))).isEqualTo(0.0);
  }

  @Test
  void profileRanksTheSameAuthorAboveAnotherAuthor() {
    final String authorA1 = "Hiii loves!! 😘 im new in town n ready 2 play... txt me ONLY, no calls pls!! xoxo";
    final String authorA2 = "Hiii babes!! 😘 im back in town n ready 2 have fun... txt me ONLY, no blocked #s pls!! xoxo";
    final String authorB = "Professional, discreet companion available for upscale gentlemen. Please call to arrange an appointment. Thank you.";

    final double same = Stylometry.cosine(Stylometry.profile(authorA1, 256), Stylometry.profile(authorA2, 256));
    final double different = Stylometry.cosine(Stylometry.profile(authorA1, 256), Stylometry.profile(authorB, 256));
    assertThat(same).isGreaterThan(different + 0.3);
  }

  @Test
  void profileIgnoresTheIdentifiersItCouldLeak() {
    // Phones, emails and URLs are identity evidence of their own: they must not also count as style
    final String a = "Sweet girl, call 555-123-4567 or mail candy99@example.com, see www.example.com/candy";
    final String b = "Sweet girl, call 212-987-6543 or mail honey42@other.net, see www.other.net/honey";
    assertThat(Stylometry.cosine(Stylometry.profile(a, 256), Stylometry.profile(b, 256))).isCloseTo(1.0, within(1e-5));
  }

  @Test
  void profileKeepsCaseAndPunctuationHabits() {
    final double shouting = Stylometry.cosine(Stylometry.profile("call me now", 256), Stylometry.profile("CALL ME NOW!!!", 256));
    assertThat(shouting).isLessThan(0.5);
  }

  @Test
  void profileRefusesBadDimensions() {
    assertThatThrownBy(() -> Stylometry.profile("x", 100)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of 2");
    assertThatThrownBy(() -> Stylometry.profile("x", 8)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stylometry.profile("x", 8192)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cosineHandlesZeroVectors() {
    assertThat(Stylometry.cosine(new float[] { 0, 0 }, new float[] { 1, 0 })).isEqualTo(0.0);
    assertThatThrownBy(() -> Stylometry.cosine(new float[] { 1 }, new float[] { 1, 0 })).isInstanceOf(
        IllegalArgumentException.class);
  }

  private static double norm(final float[] v) {
    double s = 0;
    for (final float x : v)
      s += x * x;
    return Math.sqrt(s);
  }

  private static JSONArray golden() throws IOException {
    try (final InputStream in = StylometryTest.class.getResourceAsStream("/similarity/recon-style-golden.json")) {
      assertThat(in).isNotNull();
      return new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
