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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/** Deterministic synthetic images for the perceptual hash tests: seeded scenes, their resized, mirrored and flat variants. */
public final class SimilarityTestImages {
  private SimilarityTestImages() {
  }

  /** A seeded scene: a gradient background and a dozen coloured shapes, different for every seed. */
  public static BufferedImage scene(final long seed, final int width, final int height) {
    final Random random = new Random(seed);
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setPaint(new GradientPaint(0, 0, randomColor(random), width, height, randomColor(random)));
    g.fillRect(0, 0, width, height);
    for (int i = 0; i < 12; i++) {
      g.setColor(randomColor(random));
      final int x = random.nextInt(width);
      final int y = random.nextInt(height);
      final int w = width / 8 + random.nextInt(width / 3);
      final int h = height / 8 + random.nextInt(height / 3);
      if (random.nextBoolean())
        g.fillOval(x - w / 2, y - h / 2, w, h);
      else
        g.fillRect(x - w / 2, y - h / 2, w, h);
    }
    g.dispose();
    return image;
  }

  public static BufferedImage flat(final int width, final int height) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(128, 128, 128));
    g.fillRect(0, 0, width, height);
    g.dispose();
    return image;
  }

  public static BufferedImage scaled(final BufferedImage source, final int width, final int height) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(source, 0, 0, width, height, null);
    g.dispose();
    return image;
  }

  public static BufferedImage mirror(final BufferedImage source) {
    final BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    final AffineTransform flip = AffineTransform.getScaleInstance(-1, 1);
    flip.translate(-source.getWidth(), 0);
    g.drawImage(source, flip, null);
    g.dispose();
    return image;
  }

  public static byte[] encode(final BufferedImage image, final String format) {
    try {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(image, format, out))
        throw new IllegalStateException("No writer for " + format);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Color randomColor(final Random random) {
    return new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256));
  }
}
