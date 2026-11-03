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

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDQ against Meta's reference (facebook/ThreatExchange {@code pdq/java}): the published regression hashes of the reference's
 * own test photos ({@code pdq/java/reg_test/expected/out}, {@code PDQHasherTest}), and bit-for-bit agreement with a literal
 * transcription of the reference on synthetic images of every shape and pixel layout.
 */
public class PdqHasherTest {
  // pdq/java/reg_test/expected/out: pdq-photo-hasher-tool on pdq/data/reg-test-input/dih/*.jpg
  private static final String ORIGINAL         = "f8f8f0cee0f4a84f06370a22038f63f0b36e2ed596621e1d33e6b39c4e9c9b22";
  private static final String ROTATE_90        = "30a10efd71cc3d429013d48d0ffffc52e34e0e17ada952a9d29685211ea9e5af";
  private static final String FLIP_X           = "f8f80f31e0f417b20e37f5cd028f980fb36ed02a9662c1e233e64c634e9c64dd";
  private static final String FLIP_MINUS_1     = "a5f05aa8a4896a17c906a2d85aaaab07b61b5b42f8fc07fc87c3d0741bfcb0fa";
  // pdq-photo-hasher-tool --pdqdih on bridge-1-original.jpg: the 8 dihedral hashes from one DCT
  private static final String[] DIHEDRAL_OF_ORIGINAL = {
      "f8f8f0cee0f4a84f06370a22038f63f0b36e2ed596621e1d33e6b39c4e9c9b22",
      "30a10efd71c83f429013d48d0ffffc52e34e0e17ada952a9d29685211ea9e5af",
      "adad5a64b5a102e55b62a08856dacd5ae63b847fc337b4b766b319361bc93188",
      "a5f0b457a49995e8c1065c275aaa54d8b61ba49df8fcfc0383c32f8b0bfc4f05",
      "f8f80f31e0f457b00637f5d5028f980fb36ed12a9622e1e233e64c634e9c64dd",
      "0dad2599b1a1bd1a5362576752da32a5e63b7380c2374b4866b346c91bc9ce77",
      "f0a5e102f1ccc0bd945308720fff038de34ef1e8ada9a956d2967ade5ea91a50",
      "a5f04ba8a4996a17c906a3d85aaaa927b61b5b42f8fc03fc87c3d0740bfcb0fa" };

  @Test
  void matchesTheReferenceRegressionHashes() {
    assertGolden("bridge-1-original.jpg", ORIGINAL);
    assertGolden("bridge-2-rotate-90.jpg", ROTATE_90);
    assertGolden("bridge-5-flipx.jpg", FLIP_X);
    assertGolden("bridge-8-flip-minus-1.jpg", FLIP_MINUS_1);
  }

  @Test
  void dihedralHashesMatchTheReference() {
    final PdqHasher.Dihedral dihedral = PdqHasher.dihedralImage(resource("bridge-1-original.jpg"));
    assertThat(dihedral.quality()).isEqualTo(100);
    final String[] hex = new String[8];
    for (int i = 0; i < 8; i++)
      hex[i] = BitHash.toHex(dihedral.hashes()[i]);
    assertThat(hex).containsExactly(DIHEDRAL_OF_ORIGINAL);
    // the transformed hashes of the original are near the hashes of the transformed files
    assertThat(BitHash.hamming(dihedral.hashes()[1], BitHash.parseHex(ROTATE_90))).isLessThanOrEqualTo(PdqHasher.NEAR_DUPLICATE_DISTANCE);
    assertThat(BitHash.hamming(dihedral.hashes()[4], BitHash.parseHex(FLIP_X))).isLessThanOrEqualTo(PdqHasher.NEAR_DUPLICATE_DISTANCE);
    assertThat(BitHash.hamming(dihedral.hashes()[7], BitHash.parseHex(FLIP_MINUS_1))).isLessThanOrEqualTo(PdqHasher.NEAR_DUPLICATE_DISTANCE);
  }

  /** The reference's own unit test (PDQHasherTest.testFromBufferedImage): rotation, grey scale and half resolution. */
  @Test
  void matchesTheReferenceUnitTest() {
    final BufferedImage original = read("bridge-1-original.jpg");
    final PdqHasher hasher = new PdqHasher();
    final long[] hash = hasher.hash(original).hash();
    assertThat(BitHash.toHex(hash)).isEqualTo(ORIGINAL);
    assertThat(BitHash.hamming(hash, hasher.hash(read("bridge-2-rotate-90.jpg")).hash())).isEqualTo(120);

    final BufferedImage grey = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(original, null);
    assertThat(BitHash.hamming(hash, hasher.hash(grey).hash())).isEqualTo(0);

    final BufferedImage half = new BufferedImage(original.getWidth() / 2, original.getHeight() / 2, original.getType());
    final Graphics2D g = half.createGraphics();
    g.drawImage(original, 0, 0, original.getWidth() / 2, original.getHeight() / 2, null);
    g.dispose();
    assertThat(BitHash.hamming(hash, hasher.hash(half).hash())).isEqualTo(4);
  }

  @Test
  void streamingPortEqualsTheLiteralReferenceOnEveryShapeAndLayout() {
    final int[] types = { BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_INT_BGR,
        BufferedImage.TYPE_INT_ARGB_PRE };
    final int[][] sizes = { { 1, 1 }, { 2, 3 }, { 64, 64 }, { 63, 65 }, { 127, 129 }, { 300, 7 }, { 7, 300 }, { 3, 2000 },
        { 640, 480 }, { 129, 257 }, { 1100, 260 } };
    final PdqHasher reused = new PdqHasher();
    long seed = 1;
    for (final int type : types)
      for (final int[] size : sizes) {
        final BufferedImage image = synthetic(type, size[0], size[1], seed++);
        final PdqReferenceTranscription.Result expected = PdqReferenceTranscription.hash(image);
        final PdqHasher.Hash fresh = new PdqHasher().hash(image);
        final PdqHasher.Hash again = reused.hash(image);
        final String label = "type " + type + ", " + size[0] + " x " + size[1];
        assertThat(fresh.hex()).as(label).isEqualTo(expected.hex());
        assertThat(fresh.quality()).as(label).isEqualTo(expected.quality());
        assertThat(again.hex()).as(label + ", reused hasher").isEqualTo(expected.hex());
        assertThat(again.quality()).as(label + ", reused hasher").isEqualTo(expected.quality());
      }
  }

  @Test
  void lumaInputEqualsImageInput() {
    final BufferedImage image = synthetic(BufferedImage.TYPE_3BYTE_BGR, 333, 250, 42);
    final float[] luma = new float[333 * 250];
    for (int y = 0; y < 250; y++)
      for (int x = 0; x < 333; x++) {
        final int rgb = image.getRGB(x, y);
        luma[y * 333 + x] = (float) 0.299 * ((rgb >> 16) & 0xff) + (float) 0.587 * ((rgb >> 8) & 0xff) + (float) 0.114 * (rgb & 0xff);
      }
    final PdqHasher hasher = new PdqHasher();
    assertThat(hasher.hash(luma, 333, 250).hex()).isEqualTo(hasher.hash(image).hex());
    assertThat(hasher.dihedral(luma, 333, 250).hashes()).isDeepEqualTo(hasher.dihedral(image).hashes());
  }

  @Test
  void perceptualHashDelegatesToTheReferencePort() {
    final byte[] bytes = resource("bridge-1-original.jpg");
    assertThat(BitHash.toHex(PerceptualHash.hash(bytes, PerceptualHash.Method.PDQ))).isEqualTo(ORIGINAL);
    assertThat(PerceptualHash.quality(bytes)).isEqualTo(100);
    assertThat(BitHash.toHex(PerceptualHash.dihedral(bytes, PerceptualHash.Method.PDQ)[2])).isEqualTo(DIHEDRAL_OF_ORIGINAL[2]);
  }

  @Test
  void flatImagesScoreLowAndBadInputIsRefused() {
    final BufferedImage flat = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 100; y++)
      for (int x = 0; x < 200; x++)
        flat.setRGB(x, y, 0x808080);
    assertThat(new PdqHasher().hash(flat).quality()).isLessThan(PdqHasher.MIN_QUALITY);
    assertThatThrownBy(() -> PdqHasher.hashImage(new byte[] { 1, 2, 3 })).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PdqHasher().hash(new float[10], 4, 4)).isInstanceOf(IllegalArgumentException.class);
  }

  /** Pixels from integer formulas and an LCG, written to the raster or through setRGB: identical on every JDK and platform. */
  static BufferedImage synthetic(final int type, final int w, final int h, final long seed) {
    final BufferedImage image = new BufferedImage(w, h, type);
    final WritableRaster raster = image.getRaster();
    long s = seed;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        s = s * 6364136223846793005L + 1442695040888963407L;
        final int noise = (int) (s >>> 59);
        final int block = ((x * 8 / w) ^ (y * 5 / h)) & 7;
        final int r = (block * 32 + noise + x) & 255;
        final int g = (block * 27 + (int) (seed & 63) + y) & 255;
        final int b = ((x + y) * 3 + noise * 2) & 255;
        final int a = 128 + (int) ((s >>> 40) & 127);
        switch (type) {
        case BufferedImage.TYPE_BYTE_GRAY -> raster.setSample(x, y, 0, r);
        case BufferedImage.TYPE_USHORT_GRAY -> raster.setSample(x, y, 0, r * 257 + noise);
        default -> image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
        }
      }
    return image;
  }

  private static void assertGolden(final String file, final String expected) {
    final PdqHasher.Hash hash = PdqHasher.hashImage(resource(file));
    assertThat(hash.hex()).as(file).isEqualTo(expected);
    assertThat(hash.quality()).as(file).isEqualTo(100);
  }

  public static byte[] resource(final String file) {
    try (final InputStream in = PdqHasherTest.class.getResourceAsStream("/similarity/pdq/" + file)) {
      assertThat(in).as(file).isNotNull();
      return in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage read(final String file) {
    try {
      return ImageIO.read(new ByteArrayInputStream(resource(file)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
