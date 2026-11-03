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
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * Perceptual image hashes (docs/spork/06-stylometry-perceptual-hash.md): near-duplicate images (re-encoded, resized,
 * recompressed, lightly edited) get hashes a few bits apart, so the Hamming distance links re-posted photos that share no
 * byte. Four methods:
 * <ul>
 *   <li>{@link Method#AHASH} (64 bits): 8x8 area-averaged luminance above its mean</li>
 *   <li>{@link Method#DHASH} (64 bits): 9x8 luminance, each pixel brighter than its left neighbour</li>
 *   <li>{@link Method#PHASH} (64 bits): 32x32 luminance, the 8x8 lowest DCT-II frequencies above their median</li>
 *   <li>{@link Method#PDQ} (256 bits): the PDQ construction (Facebook's published algorithm): luminance, two passes of a
 *   box (Jarosz) filter sized to the image, decimation to 64x64, the 16x16 lowest DCT frequencies after the DC term above
 *   their median, packed and printed as the reference prints it</li>
 * </ul>
 * Images are decoded with the JDK's ImageIO (JPEG, PNG, GIF, BMP, WBMP), from memory only (no cache file), refused above
 * a pixel budget read from the header before any pixel is decoded, and subsampled while decoding so the short side stays
 * at least 512 pixels: every method downsamples to 64x64 or less, so the rest of a large photo costs decode time and no
 * accuracy. A hash is only comparable with hashes of the same method computed by this class: bit parity with other
 * libraries (Python imagehash, the PDQ reference) is not promised, because their resampling filters differ.
 * <p>
 * Thread safe; the DCT tables are shared and immutable.
 * </p>
 */
public final class PerceptualHash {
  /** About 8000 x 8000: larger declared sizes are refused before decoding, a decompression bomb guard. */
  public static final long DEFAULT_MAX_PIXELS = 64_000_000L;
  /** The short side kept by subsampling while decoding. */
  static final int DECODE_SHORT_SIDE = 512;

  public enum Method {
    AHASH(64), DHASH(64), PHASH(64), PDQ(256);

    private final int bits;

    Method(final int bits) {
      this.bits = bits;
    }

    public int bits() {
      return bits;
    }

    public static Method parse(final String name) {
      try {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
      } catch (final IllegalArgumentException | NullPointerException e) {
        throw new IllegalArgumentException("Unknown image hash method '" + name + "', expected one of ahash, dhash, phash, pdq");
      }
    }
  }

  /** Luminance (Rec. 601, 0 to 255) row by row. */
  public record Luma(int width, int height, float[] values) {
  }

  private static final double[][] PHASH_DCT = dctMatrix(8, 32, 0, 1.0);
  private static final double[][] PDQ_DCT   = dctMatrix(16, 64, 1, Math.sqrt(2.0 / 64));

  private PerceptualHash() {
  }

  public static long[] hash(final byte[] image, final Method method) {
    return hash(decode(image, DEFAULT_MAX_PIXELS), method);
  }

  /** The hashes of the 8 dihedral transforms (rotations and mirrors) of the image; the first is {@link #hash}. */
  public static long[][] dihedral(final byte[] image, final Method method) {
    return dihedral(decode(image, DEFAULT_MAX_PIXELS), method);
  }

  /**
   * PDQ's quality score, 0 to 100: the luminance gradient of the 64x64 image. Featureless images (blank, solid, nearly
   * uniform) score low, and their hashes are noise around a flat signal that collides with every other featureless image,
   * so a link must never rest on a low-quality hash (the PDQ guidance is to discard below 50).
   */
  public static int quality(final byte[] image) {
    return quality(decode(image, DEFAULT_MAX_PIXELS));
  }

  public static long[] hash(final Luma luma, final Method method) {
    return switch (method) {
      case AHASH -> aHash(luma);
      case DHASH -> dHash(luma);
      case PHASH -> pHash(luma);
      case PDQ -> pdq(luma);
    };
  }

  public static long[][] dihedral(final Luma luma, final Method method) {
    final long[][] hashes = new long[8][];
    for (int t = 0; t < 8; t++)
      hashes[t] = hash(transform(luma, t), method);
    return hashes;
  }

  public static int quality(final Luma luma) {
    final float[] grid = pdqGrid(luma);
    int gradientSum = 0;
    for (int i = 0; i < 63; i++)
      for (int j = 0; j < 64; j++)
        gradientSum += Math.abs((int) ((grid[i * 64 + j] - grid[(i + 1) * 64 + j]) * 100 / 255));
    for (int i = 0; i < 64; i++)
      for (int j = 0; j < 63; j++)
        gradientSum += Math.abs((int) ((grid[i * 64 + j] - grid[i * 64 + j + 1]) * 100 / 255));
    return Math.min(100, gradientSum / 90);
  }

  /** Decodes an image into its luminance, refusing more than {@code maxPixels} pixels and anything ImageIO cannot read. */
  public static Luma decode(final byte[] image, final long maxPixels) {
    if (image == null || image.length == 0)
      throw new IllegalArgumentException("An image cannot be null or empty");
    try (final ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
      final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext())
        throw new IllegalArgumentException("The bytes are not a decodable image (" + image.length + " bytes)");
      final ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        final int width = reader.getWidth(0);
        final int height = reader.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > maxPixels)
          throw new IllegalArgumentException(
              "The image declares " + width + " x " + height + " pixels, more than the " + maxPixels + " pixels allowed");
        final ImageReadParam param = reader.getDefaultReadParam();
        final int step = Math.max(1, Math.min(width, height) / DECODE_SHORT_SIDE);
        if (step > 1)
          param.setSourceSubsampling(step, step, 0, 0);
        return luma(reader.read(0, param));
      } finally {
        reader.dispose();
      }
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final IOException | RuntimeException e) {
      throw new IllegalArgumentException("The bytes are not a decodable image: " + e.getMessage(), e);
    }
  }

  public static Luma luma(final BufferedImage image) {
    final int w = image.getWidth();
    final int h = image.getHeight();
    final float[] values = new float[w * h];
    final int[] row = new int[w];
    for (int y = 0; y < h; y++) {
      image.getRGB(0, y, w, 1, row, 0, w);
      for (int x = 0; x < w; x++) {
        final int rgb = row[x];
        values[y * w + x] = (float) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
      }
    }
    return new Luma(w, h, values);
  }

  // ---- methods ----

  private static long[] aHash(final Luma luma) {
    final float[] grid = resize(luma, 8, 8);
    double mean = 0;
    for (final float v : grid)
      mean += v;
    mean /= grid.length;
    long h = 0;
    for (final float v : grid)
      h = (h << 1) | (v > mean ? 1 : 0);
    return new long[] { h };
  }

  private static long[] dHash(final Luma luma) {
    final float[] grid = resize(luma, 9, 8);
    long h = 0;
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++)
        h = (h << 1) | (grid[y * 9 + x + 1] > grid[y * 9 + x] ? 1 : 0);
    return new long[] { h };
  }

  private static long[] pHash(final Luma luma) {
    final double[] coefficients = dct2(resize(luma, 32, 32), 32, PHASH_DCT);
    final double[] sorted = coefficients.clone();
    Arrays.sort(sorted);
    final double median = (sorted[31] + sorted[32]) / 2;
    long h = 0;
    for (final double c : coefficients)
      h = (h << 1) | (c > median ? 1 : 0);
    return new long[] { h };
  }

  private static long[] pdq(final Luma luma) {
    final double[] coefficients = dct2(pdqGrid(luma), 64, PDQ_DCT);
    final double[] sorted = coefficients.clone();
    Arrays.sort(sorted);
    final double median = sorted[127];
    // PDQ packs bit (i, j) as bit j of 16-bit word i and prints the words from 15 down to 0
    final long[] hash = new long[4];
    for (int i = 0; i < 16; i++)
      for (int j = 0; j < 16; j++)
        if (coefficients[i * 16 + j] > median)
          hash[(15 - i) >> 2] |= 1L << (48 - 16 * ((15 - i) & 3) + j);
    return hash;
  }

  /** PDQ's 64x64 image: two passes of a box filter of about half the decimation step in each direction, then decimation. */
  private static float[] pdqGrid(final Luma luma) {
    final int w = luma.width();
    final int h = luma.height();
    final int windowX = (w + 2 * 64 - 1) / (2 * 64);
    final int windowY = (h + 2 * 64 - 1) / (2 * 64);
    float[] buffer = luma.values().clone();
    float[] scratch = new float[buffer.length];
    for (int pass = 0; pass < 2; pass++) {
      boxRows(buffer, scratch, w, h, windowX);
      boxColumns(scratch, buffer, w, h, windowY);
    }
    final float[] grid = new float[64 * 64];
    for (int i = 0; i < 64; i++) {
      final int y = Math.min(h - 1, (int) ((i + 0.5) * h / 64));
      for (int j = 0; j < 64; j++)
        grid[i * 64 + j] = buffer[y * w + Math.min(w - 1, (int) ((j + 0.5) * w / 64))];
    }
    return grid;
  }

  // ---- signal helpers ----

  /** The rows x columns low-frequency block of the 2-D DCT of an n x n grid: D A D^T with D rows x n. */
  private static double[] dct2(final float[] grid, final int n, final double[][] d) {
    final int k = d.length;
    final double[] t = new double[k * n];
    for (int i = 0; i < k; i++)
      for (int x = 0; x < n; x++) {
        double s = 0;
        for (int y = 0; y < n; y++)
          s += d[i][y] * grid[y * n + x];
        t[i * n + x] = s;
      }
    final double[] out = new double[k * k];
    for (int i = 0; i < k; i++)
      for (int j = 0; j < k; j++) {
        double s = 0;
        for (int x = 0; x < n; x++)
          s += t[i * n + x] * d[j][x];
        out[i * k + j] = s;
      }
    return out;
  }

  private static double[][] dctMatrix(final int rows, final int n, final int firstFrequency, final double scale) {
    final double[][] d = new double[rows][n];
    for (int i = 0; i < rows; i++)
      for (int j = 0; j < n; j++)
        d[i][j] = scale * Math.cos(Math.PI / (2.0 * n) * (i + firstFrequency) * (2 * j + 1));
    return d;
  }

  /** Area-averaging resize: every target pixel is the mean of the source area it covers, fractional pixels weighted. */
  static float[] resize(final Luma luma, final int targetWidth, final int targetHeight) {
    final int w = luma.width();
    final int h = luma.height();
    final float[] src = luma.values();
    final float[] rows = new float[h * targetWidth];
    final double sx = (double) w / targetWidth;
    for (int x = 0; x < targetWidth; x++) {
      final double from = x * sx;
      final double to = (x + 1) * sx;
      final int last = Math.min(w, (int) Math.ceil(to));
      for (int y = 0; y < h; y++) {
        double s = 0;
        for (int i = (int) from; i < last; i++)
          s += src[y * w + i] * (Math.min(to, i + 1) - Math.max(from, i));
        rows[y * targetWidth + x] = (float) (s / sx);
      }
    }
    final float[] out = new float[targetHeight * targetWidth];
    final double sy = (double) h / targetHeight;
    for (int y = 0; y < targetHeight; y++) {
      final double from = y * sy;
      final double to = (y + 1) * sy;
      final int last = Math.min(h, (int) Math.ceil(to));
      for (int x = 0; x < targetWidth; x++) {
        double s = 0;
        for (int i = (int) from; i < last; i++)
          s += rows[i * targetWidth + x] * (Math.min(to, i + 1) - Math.max(from, i));
        out[y * targetWidth + x] = (float) (s / sy);
      }
    }
    return out;
  }

  /** A centred running-mean box filter along each row, averaging only the pixels inside the image at the edges. */
  private static void boxRows(final float[] in, final float[] out, final int w, final int h, final int window) {
    final int before = (window - 1) / 2;
    final int after = window / 2;
    for (int y = 0; y < h; y++) {
      final int base = y * w;
      double sum = 0;
      int count = 0;
      for (int i = 0; i < Math.min(w, after); i++) {
        sum += in[base + i];
        count++;
      }
      for (int x = 0; x < w; x++) {
        final int enter = x + after;
        if (enter < w) {
          sum += in[base + enter];
          count++;
        }
        final int leave = x - before - 1;
        if (leave >= 0) {
          sum -= in[base + leave];
          count--;
        }
        out[base + x] = (float) (sum / count);
      }
    }
  }

  private static void boxColumns(final float[] in, final float[] out, final int w, final int h, final int window) {
    final int before = (window - 1) / 2;
    final int after = window / 2;
    for (int x = 0; x < w; x++) {
      double sum = 0;
      int count = 0;
      for (int i = 0; i < Math.min(h, after); i++) {
        sum += in[i * w + x];
        count++;
      }
      for (int y = 0; y < h; y++) {
        final int enter = y + after;
        if (enter < h) {
          sum += in[enter * w + x];
          count++;
        }
        final int leave = y - before - 1;
        if (leave >= 0) {
          sum -= in[leave * w + x];
          count--;
        }
        out[y * w + x] = (float) (sum / count);
      }
    }
  }

  /**
   * Dihedral transform t of the image: 0 identity, 1 to 3 rotations by 90, 180 and 270 degrees clockwise, 4 mirror
   * left-right, 5 mirror top-bottom, 6 transpose, 7 anti-transpose.
   */
  static Luma transform(final Luma luma, final int t) {
    if (t == 0)
      return luma;
    final int w = luma.width();
    final int h = luma.height();
    final boolean swap = t == 1 || t == 3 || t == 6 || t == 7;
    final int ow = swap ? h : w;
    final int oh = swap ? w : h;
    final float[] src = luma.values();
    final float[] out = new float[src.length];
    for (int y = 0; y < oh; y++)
      for (int x = 0; x < ow; x++) {
        final int sx, sy;
        switch (t) {
        case 1 -> { sx = y; sy = h - 1 - x; }
        case 2 -> { sx = w - 1 - x; sy = h - 1 - y; }
        case 3 -> { sx = w - 1 - y; sy = x; }
        case 4 -> { sx = w - 1 - x; sy = y; }
        case 5 -> { sx = x; sy = h - 1 - y; }
        case 6 -> { sx = y; sy = x; }
        default -> { sx = w - 1 - y; sy = h - 1 - x; }
        }
        out[y * ow + x] = src[sy * w + sx];
      }
    return new Luma(ow, oh, out);
  }
}
