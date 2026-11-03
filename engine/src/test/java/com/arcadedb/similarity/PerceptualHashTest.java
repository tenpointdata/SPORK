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

import com.arcadedb.similarity.PerceptualHash.Method;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.arcadedb.similarity.SimilarityTestImages.encode;
import static com.arcadedb.similarity.SimilarityTestImages.flat;
import static com.arcadedb.similarity.SimilarityTestImages.mirror;
import static com.arcadedb.similarity.SimilarityTestImages.scene;
import static com.arcadedb.similarity.SimilarityTestImages.scaled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PerceptualHash}: the four methods agree with themselves, survive re-encoding and resizing, tell different scenes
 * apart, see through a mirror with the dihedral set, and refuse what they must not decode.
 */
class PerceptualHashTest {

  @Test
  void hashesHaveTheirDocumentedWidths() {
    final byte[] png = encode(scene(1, 320, 240), "png");
    assertThat(PerceptualHash.hash(png, Method.AHASH)).hasSize(1);
    assertThat(PerceptualHash.hash(png, Method.DHASH)).hasSize(1);
    assertThat(PerceptualHash.hash(png, Method.PHASH)).hasSize(1);
    assertThat(PerceptualHash.hash(png, Method.PDQ)).hasSize(4);
    assertThat(BitHash.toHex(PerceptualHash.hash(png, Method.PHASH))).hasSize(16);
    assertThat(BitHash.toHex(PerceptualHash.hash(png, Method.PDQ))).hasSize(64);
    assertThat(Method.PDQ.bits()).isEqualTo(256);
    assertThat(Method.PHASH.bits()).isEqualTo(64);
  }

  @Test
  void sameImageSameHash() {
    final byte[] png = encode(scene(2, 300, 300), "png");
    for (final Method m : Method.values())
      assertThat(PerceptualHash.hash(png, m)).as(m.name()).isEqualTo(PerceptualHash.hash(png.clone(), m));
  }

  @Test
  void nearDuplicatesStayClose() {
    final var original = scene(3, 640, 480);
    final byte[] png = encode(original, "png");
    final byte[] smallJpeg = encode(scaled(original, 320, 240), "jpg");

    assertThat(distance(png, smallJpeg, Method.PHASH)).isLessThanOrEqualTo(8);
    assertThat(distance(png, smallJpeg, Method.DHASH)).isLessThanOrEqualTo(10);
    assertThat(distance(png, smallJpeg, Method.AHASH)).isLessThanOrEqualTo(8);
    assertThat(distance(png, smallJpeg, Method.PDQ)).isLessThanOrEqualTo(31);
  }

  @Test
  void differentScenesAreFarApart() {
    final byte[] a = encode(scene(4, 400, 300), "png");
    final byte[] b = encode(scene(5, 400, 300), "png");
    assertThat(distance(a, b, Method.PHASH)).isGreaterThan(16);
    assertThat(distance(a, b, Method.PDQ)).isGreaterThan(70);
  }

  @Test
  void dihedralSetSeesThroughAMirror() {
    final var original = scene(6, 400, 300);
    final byte[] png = encode(original, "png");
    final long[] mirrored = PerceptualHash.hash(encode(mirror(original), "png"), Method.PDQ);

    assertThat(BitHash.hamming(PerceptualHash.hash(png, Method.PDQ), mirrored)).isGreaterThan(70);

    final long[][] all = PerceptualHash.dihedral(png, Method.PDQ);
    assertThat(all).hasNumberOfRows(8);
    assertThat(all[0]).isEqualTo(PerceptualHash.hash(png, Method.PDQ));
    final int best = Arrays.stream(all).mapToInt(h -> BitHash.hamming(h, mirrored)).min().orElseThrow();
    assertThat(best).isLessThanOrEqualTo(10);

    final long[][] phashes = PerceptualHash.dihedral(png, Method.PHASH);
    final long[] mirroredPhash = PerceptualHash.hash(encode(mirror(original), "png"), Method.PHASH);
    assertThat(Arrays.stream(phashes).mapToInt(h -> BitHash.hamming(h, mirroredPhash)).min().orElseThrow()).isLessThanOrEqualTo(4);
  }

  @Test
  void qualityFlagsFeaturelessImages() {
    assertThat(PerceptualHash.quality(encode(flat(200, 200), "png"))).isLessThan(10);
    assertThat(PerceptualHash.quality(encode(scene(7, 300, 300), "png"))).isGreaterThanOrEqualTo(50);
  }

  @Test
  void refusesGarbageAndOversizedImages() {
    assertThatThrownBy(() -> PerceptualHash.hash(new byte[] { 1, 2, 3, 4 }, Method.PHASH)).isInstanceOf(
        IllegalArgumentException.class).hasMessageContaining("not a decodable image");
    assertThatThrownBy(() -> PerceptualHash.hash((byte[]) null, Method.PHASH)).isInstanceOf(IllegalArgumentException.class);

    final byte[] big = encode(flat(1200, 1000), "png");
    assertThatThrownBy(() -> PerceptualHash.decode(big, 1_000_000)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pixels");
  }

  @Test
  void methodNamesParse() {
    assertThat(Method.parse("pdq")).isEqualTo(Method.PDQ);
    assertThat(Method.parse("pHash")).isEqualTo(Method.PHASH);
    assertThatThrownBy(() -> Method.parse("md5")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ahash");
  }

  private static int distance(final byte[] a, final byte[] b, final Method m) {
    return BitHash.hamming(PerceptualHash.hash(a, m), PerceptualHash.hash(b, m));
  }
}
