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
/*
 * Portions of this file are derived from the PDQ reference implementation in facebook/ThreatExchange
 * (pdq/java/src/main/java/pdqhashing/hasher/PDQHasher.java and pdqhashing/utils/MatrixUtil.java), under this license:
 *
 * BSD License
 *
 * For ThreatExchange software
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 *  * Neither the name Facebook nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.arcadedb.similarity;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.Arrays;

/**
 * Meta's PDQ perceptual hash (256 bits and a 0 to 100 quality score), ported from the BSD-licensed Java reference
 * implementation in facebook/ThreatExchange ({@code pdq/java}, {@code pdqhashing.hasher.PDQHasher}; see ATTRIBUTIONS.md),
 * docs/spork/workstreams/ws12-pdq.md.
 * <p>
 * <b>Bit parity.</b> The arithmetic is the reference's, operation for operation, in {@code float}: the Rec. 601 luminance,
 * two X-then-Y passes of the Jarosz box filter (window {@code ceil(side / 128)}, the reference's four-phase running sum),
 * decimation to 64x64 at the pixel centres, the image-domain quality metric, the 16x16 DCT block after the DC term, the
 * Torben median and the bit packing. An image decoded by the JDK's ImageIO (as the reference decodes) therefore hashes
 * to exactly the reference's hex string, which is what lets SPORK match against hash lists made elsewhere (5.7 of
 * docs/spork/01-architecture.md). Other decoders (the C++ reference uses CImg) differ by a few bits on JPEG.
 * </p>
 * <p>
 * <b>Cost.</b> The reference holds two {@code float} copies of the whole image. This port streams instead: each row is
 * converted to luminance and box-filtered as it is read, each column pass keeps only a ring of {@code window + 1} rows and
 * one running sum per column, and the second row pass keeps only the 64 columns the decimation reads, so the second column pass also runs on a ring; the working set is about {@code (ceil(height / 128) + 1) x (width + 64)}
 * floats (0.4 MB for a 4000 x 3000 photo instead of 96 MB), bounded by the pixel budget for any shape. Interchanging the loops changes no result: every column still sees its own additions and subtractions in the
 * reference's order. Instances keep their buffers between calls and are not thread safe; use one per worker thread, or the
 * static methods, which allocate a hasher per call.
 * </p>
 */
public final class PdqHasher {
  public static final int BITS                    = 256;
  /** The reference's near-duplicate cut: about 12% of the bits. */
  public static final int NEAR_DUPLICATE_DISTANCE = 31;
  /** Hashes scoring below this are noise around a flat signal: the reference's guidance is to discard them. */
  public static final int MIN_QUALITY             = 50;

  /** A PDQ hash in {@link BitHash} layout (4 words, most significant first, printed as the reference prints it). */
  public record Hash(long[] hash, int quality) {
    public String hex() {
      return BitHash.toHex(hash);
    }
  }

  /**
   * The hashes of the 8 dihedral transforms in the reference's order: original, rotate 90, rotate 180, rotate 270, flip X,
   * flip Y, flip +1 (transpose), flip -1 (anti-transpose). They come from sign flips and transposes of one DCT, so all 8
   * cost one hash; they equal the reference's {@code --pdqdih} output.
   */
  public record Dihedral(long[][] hashes, int quality) {
  }

  private static final int   GRID                = 64;
  private static final int   DCT                 = 16;
  private static final int   WINDOW_DIVISOR      = 128;
  private static final float DCT_SCALE           = (float) Math.sqrt(2.0 / 64.0);
  private static final float LUMA_R              = (float) 0.299;
  private static final float LUMA_G              = (float) 0.587;
  private static final float LUMA_B              = (float) 0.114;
  /** {@code (float) 0.299 * v}: a float product is correctly rounded, so the table gives the reference's value. */
  private static final float[] LUMA_FROM_R       = lumaTable(LUMA_R);
  private static final float[] LUMA_FROM_G       = lumaTable(LUMA_G);
  private static final float[] LUMA_FROM_B       = lumaTable(LUMA_B);
  /** The reference's 16 x 64 DCT matrix, row-major. */
  private static final float[] DCT_MATRIX        = dctMatrix();

  // reusable buffers, grown on demand
  private final ColumnBox firstColumns  = new ColumnBox();
  private final ColumnBox secondColumns = new ColumnBox();
  private       float[]   lumaRow       = new float[0];
  private       float[]   filteredRow   = new float[0];
  private       int[]     rgbRow        = new int[0];
  private final float[]   grid          = new float[GRID * GRID];
  private final float[]   dct16x64      = new float[DCT * GRID];
  private final float[]   dct           = new float[DCT * DCT];
  private final float[]   aux           = new float[DCT * DCT];
  private final int[]     gridColumns   = new int[GRID];
  private final int[]     gridRows      = new int[GRID];
  private       int       nextGridRow;

  /** Hashes image bytes decoded at full resolution (the reference decodes this way; subsampling would change bits). */
  public static Hash hashImage(final byte[] image) {
    return new PdqHasher().hash(image);
  }

  public static Dihedral dihedralImage(final byte[] image) {
    return new PdqHasher().dihedral(image);
  }

  public Hash hash(final byte[] image) {
    return hash(PerceptualHash.decodeImage(image, PerceptualHash.DEFAULT_MAX_PIXELS, 0));
  }

  public Dihedral dihedral(final byte[] image) {
    return dihedral(PerceptualHash.decodeImage(image, PerceptualHash.DEFAULT_MAX_PIXELS, 0));
  }

  public Hash hash(final BufferedImage image) {
    final int quality = transform(image, null, image.getWidth(), image.getHeight());
    return new Hash(bits(dct), quality);
  }

  public Dihedral dihedral(final BufferedImage image) {
    final int quality = transform(image, null, image.getWidth(), image.getHeight());
    return new Dihedral(dihedralBits(), quality);
  }

  /** Hashes a luminance image (row-major, {@code width x height}), skipping the RGB conversion. */
  public Hash hash(final float[] luma, final int width, final int height) {
    final int quality = transform(null, luma, width, height);
    return new Hash(bits(dct), quality);
  }

  public Dihedral dihedral(final float[] luma, final int width, final int height) {
    final int quality = transform(null, luma, width, height);
    return new Dihedral(dihedralBits(), quality);
  }

  // ---- the pipeline ----

  /** Filters, decimates and transforms the image into {@link #grid} and {@link #dct}; returns the quality. */
  private int transform(final BufferedImage image, final float[] luma, final int width, final int height) {
    if (width < 1 || height < 1)
      throw new IllegalArgumentException("An image needs at least one pixel, got " + width + " x " + height);
    if (luma != null && luma.length < (long) width * height)
      throw new IllegalArgumentException("The luminance holds " + luma.length + " values, fewer than " + width + " x " + height);
    final int windowAlongRows = (width + WINDOW_DIVISOR - 1) / WINDOW_DIVISOR;
    final int windowAlongColumns = (height + WINDOW_DIVISOR - 1) / WINDOW_DIVISOR;
    if (lumaRow.length < width) {
      lumaRow = new float[width];
      filteredRow = new float[width];
      rgbRow = new int[width];
    }
    for (int i = 0; i < GRID; i++) {
      gridColumns[i] = (int) (((i + 0.5) * width) / GRID);
      gridRows[i] = (int) (((i + 0.5) * height) / GRID);
    }
    nextGridRow = 0;
    firstColumns.reset(width, windowAlongColumns);
    secondColumns.reset(GRID, windowAlongColumns);

    // X, Y, X, Y: pass 1 X on each row as it is read, pass 1 Y on a ring of rows, pass 2 X on each row pass 1 Y completes,
    // pass 2 Y on the 64 columns the decimation reads
    final PixelReader reader = image == null ? null : PixelReader.of(image);
    for (int r = 0; r < height; r++) {
      final int slot = firstColumns.slot();
      if (reader != null) {
        reader.luma(image, r, lumaRow, rgbRow);
        box(lumaRow, 0, firstColumns.ring, slot, width, windowAlongRows);
      } else
        box(luma, r * width, firstColumns.ring, slot, width, windowAlongRows);
      if (firstColumns.push())
        secondPass(width, windowAlongRows);
    }
    for (int k = 0; k < firstColumns.tailRows(); k++) {
      firstColumns.tail(k, height);
      secondPass(width, windowAlongRows);
    }
    for (int k = 0; k < secondColumns.tailRows(); k++) {
      secondColumns.tail(k, height);
      decimate();
    }

    final int quality = quality(grid);
    dct64To16();
    return quality;
  }

  /** Pass 2 X on the row pass 1 Y just completed, keeping the 64 sampled columns for pass 2 Y. */
  private void secondPass(final int width, final int windowAlongRows) {
    box(firstColumns.out, 0, filteredRow, 0, width, windowAlongRows);
    final float[] ring = secondColumns.ring;
    final int slot = secondColumns.slot();
    for (int j = 0; j < GRID; j++)
      ring[slot + j] = filteredRow[gridColumns[j]];
    if (secondColumns.push())
      decimate();
  }

  /** Copies the row pass 2 Y just completed into every grid row sampling it (several when the image is under 64 high). */
  private void decimate() {
    final int row = secondColumns.emitted - 1;
    while (nextGridRow < GRID && gridRows[nextGridRow] == row)
      System.arraycopy(secondColumns.out, 0, grid, nextGridRow++ * GRID, GRID);
  }

  /**
   * The reference's {@code box1DFloat} down every column of an image at once, fed one row at a time: each column keeps
   * its own running sum and sees the reference's additions and subtractions in the reference's order, and only the last
   * {@code window + 1} rows are held.
   */
  private static final class ColumnBox {
    private float[] ring = new float[0];
    private float[] sums = new float[0];
    private float[] out  = new float[0];
    private int     width;
    private int     window;
    private int     ringRows;
    private int     phase1;
    private int     received;
    private int     count;
    private int     emitted;

    void reset(final int width, final int window) {
      this.width = width;
      this.window = window;
      this.ringRows = window + 1;
      this.phase1 = (window + 2) / 2 - 1;
      if (ring.length < ringRows * width)
        ring = new float[ringRows * width];
      if (sums.length < width) {
        sums = new float[width];
        out = new float[width];
      }
      Arrays.fill(sums, 0, width, 0f);
      received = 0;
      count = 0;
      emitted = 0;
    }

    /** Where the caller writes the next input row. */
    int slot() {
      return (received % ringRows) * width;
    }

    /** Takes the row written at {@link #slot()}; true when that completes an output row, now in {@link #out}. */
    boolean push() {
      final int r = received++;
      final int slot = (r % ringRows) * width;
      if (r < window) {
        // phases 1 and 2: the window grows
        for (int x = 0; x < width; x++)
          sums[x] += ring[slot + x];
        count++;
        if (r < phase1)
          return false;
        for (int x = 0; x < width; x++)
          out[x] = sums[x] / count;
      } else {
        // phase 3: add the new row, drop the one `window` rows back
        final int leaving = ((r - window) % ringRows) * width;
        for (int x = 0; x < width; x++) {
          float s = sums[x] + ring[slot + x];
          s -= ring[leaving + x];
          sums[x] = s;
          out[x] = s / count;
        }
      }
      emitted++;
      return true;
    }

    /** Phase 4's length: the output rows left once every input row is in. */
    int tailRows() {
      return phase1;
    }

    /** Phase 4, step {@code k}: the window shrinks; the output row is in {@link #out}. */
    void tail(final int k, final int rows) {
      final int leaving = ((rows - window + k) % ringRows) * width;
      count--;
      for (int x = 0; x < width; x++) {
        final float s = sums[x] - ring[leaving + x];
        sums[x] = s;
        out[x] = s / count;
      }
      emitted++;
    }
  }

  /** The reference's {@code box1DFloat} with stride 1: a centred running mean, the window shrinking at both ends. */
  static void box(final float[] in, final int inOffset, final float[] out, final int outOffset, final int length,
      final int window) {
    final int half = (window + 2) / 2;
    final int phase1 = half - 1;
    final int phase2 = window - half + 1;
    final int phase3 = length - window;
    final int phase4 = half - 1;
    int li = inOffset;
    int ri = inOffset;
    int oi = outOffset;
    float sum = 0f;
    int current = 0;
    for (int i = 0; i < phase1; i++) {
      sum += in[ri++];
      current++;
    }
    for (int i = 0; i < phase2; i++) {
      sum += in[ri++];
      current++;
      out[oi++] = sum / current;
    }
    for (int i = 0; i < phase3; i++) {
      sum += in[ri++];
      sum -= in[li++];
      out[oi++] = sum / current;
    }
    for (int i = 0; i < phase4; i++) {
      sum -= in[li++];
      current--;
      out[oi++] = sum / current;
    }
  }

  /** The reference's image-domain quality: significant luminance gradients of the 64x64 image, scaled to 0 to 100. */
  private static int quality(final float[] g) {
    int gradientSum = 0;
    for (int i = 0; i < GRID - 1; i++)
      for (int j = 0; j < GRID; j++) {
        final int d = (int) (((g[i * GRID + j] - g[(i + 1) * GRID + j]) * 100) / 255);
        gradientSum += Math.abs(d);
      }
    for (int i = 0; i < GRID; i++)
      for (int j = 0; j < GRID - 1; j++) {
        final int d = (int) (((g[i * GRID + j] - g[i * GRID + j + 1]) * 100) / 255);
        gradientSum += Math.abs(d);
      }
    return Math.min(100, gradientSum / 90);
  }

  /** B = D A D^T on the 16 frequencies after DC, each sum accumulated in the reference's order (k ascending). */
  private void dct64To16() {
    final float[] d = DCT_MATRIX;
    final float[] t = dct16x64;
    Arrays.fill(t, 0f);
    for (int i = 0; i < DCT; i++)
      for (int k = 0; k < GRID; k++) {
        final float dik = d[i * GRID + k];
        final int a = k * GRID;
        final int o = i * GRID;
        for (int j = 0; j < GRID; j++)
          t[o + j] += dik * grid[a + j];
      }
    for (int i = 0; i < DCT; i++)
      for (int j = 0; j < DCT; j++) {
        float sum = 0f;
        for (int k = 0; k < GRID; k++)
          sum += t[i * GRID + k] * d[j * GRID + k];
        dct[i * DCT + j] = sum;
      }
  }

  private long[][] dihedralBits() {
    final long[][] hashes = new long[8][];
    hashes[0] = bits(dct);
    for (int t = 1; t < 8; t++) {
      for (int i = 0; i < DCT; i++)
        for (int j = 0; j < DCT; j++) {
          final float v = dct[i * DCT + j];
          switch (t) {
          case 1 -> aux[j * DCT + i] = (j & 1) != 0 ? v : -v;          // rotate 90
          case 2 -> aux[i * DCT + j] = ((i + j) & 1) != 0 ? -v : v;    // rotate 180
          case 3 -> aux[j * DCT + i] = (i & 1) != 0 ? v : -v;          // rotate 270
          case 4 -> aux[i * DCT + j] = (i & 1) != 0 ? v : -v;          // flip X
          case 5 -> aux[i * DCT + j] = (j & 1) != 0 ? v : -v;          // flip Y
          case 6 -> aux[j * DCT + i] = v;                              // flip +1
          default -> aux[j * DCT + i] = ((i + j) & 1) != 0 ? -v : v;   // flip -1
          }
        }
      hashes[t] = bits(aux);
    }
    return hashes;
  }

  /**
   * Bit (i, j) is set when coefficient (i, j) exceeds the Torben median; the reference packs it as bit j of 16-bit word i
   * and prints the words from 15 down to 0, which is word {@code (15 - i) / 4} of the {@link BitHash} layout.
   */
  private static long[] bits(final float[] coefficients) {
    final float median = torben(coefficients);
    final long[] hash = new long[4];
    for (int i = 0; i < DCT; i++)
      for (int j = 0; j < DCT; j++)
        if (coefficients[i * DCT + j] > median)
          hash[(15 - i) >> 2] |= 1L << (48 - 16 * ((15 - i) & 3) + j);
    return hash;
  }

  /** Torben's median (the reference's {@code MatrixUtil.torben}): the upper median of 256 values, no sort, no copy. */
  static float torben(final float[] m) {
    final int midn = (m.length + 1) / 2;
    float min = m[0];
    float max = m[0];
    for (final float v : m) {
      if (v < min)
        min = v;
      if (v > max)
        max = v;
    }
    int less;
    int greater;
    int equal;
    float guess;
    float maxLessThanGuess;
    float minGreaterThanGuess;
    while (true) {
      guess = (min + max) / 2;
      less = 0;
      greater = 0;
      equal = 0;
      maxLessThanGuess = min;
      minGreaterThanGuess = max;
      for (final float v : m) {
        if (v < guess) {
          less++;
          if (v > maxLessThanGuess)
            maxLessThanGuess = v;
        } else if (v > guess) {
          greater++;
          if (v < minGreaterThanGuess)
            minGreaterThanGuess = v;
        } else
          equal++;
      }
      if (less <= midn && greater <= midn)
        break;
      else if (less > greater)
        max = maxLessThanGuess;
      else
        min = minGreaterThanGuess;
    }
    if (less >= midn)
      return maxLessThanGuess;
    else if (less + equal >= midn)
      return guess;
    return minGreaterThanGuess;
  }

  private static float[] lumaTable(final float coefficient) {
    final float[] table = new float[256];
    for (int v = 0; v < 256; v++)
      table[v] = coefficient * v;
    return table;
  }

  private static float[] dctMatrix() {
    final float[] d = new float[DCT * GRID];
    for (int i = 0; i < DCT; i++)
      for (int j = 0; j < GRID; j++)
        d[i * GRID + j] = (float) (DCT_SCALE * Math.cos((Math.PI / 2 / 64.0) * (i + 1) * (2 * j + 1)));
    return d;
  }

  /**
   * One row of luminance, {@code (float) 0.299 * r + (float) 0.587 * g + (float) 0.114 * b} as the reference computes it from
   * {@code BufferedImage.getRGB}. The layouts ImageIO decodes most photos into are read straight from the raster, where
   * {@code getRGB} would return the same channels unchanged; every other layout goes through {@code getRGB}, colour model
   * conversions included, exactly like the reference.
   */
  private abstract static class PixelReader {
    abstract void luma(BufferedImage image, int y, float[] out, int[] rgb);

    static PixelReader of(final BufferedImage image) {
      final Raster raster = image.getRaster();
      final boolean plain = raster.getParent() == null && raster.getSampleModelTranslateX() == 0
          && raster.getSampleModelTranslateY() == 0 && raster.getDataBuffer().getNumBanks() == 1
          && raster.getDataBuffer().getOffset() == 0;
      if (plain)
        switch (image.getType()) {
        case BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR -> {
          if (raster.getSampleModel() instanceof ComponentSampleModel model && raster.getDataBuffer() instanceof DataBufferByte bytes)
            return new InterleavedBytes(bytes.getData(), model.getScanlineStride(), model.getPixelStride(), model.getBandOffsets());
        }
        case BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB -> {
          if (raster.getSampleModel() instanceof SinglePixelPackedSampleModel model && raster.getDataBuffer() instanceof DataBufferInt ints)
            return new PackedInts(ints.getData(), model.getScanlineStride());
        }
        default -> {
        }
        }
      return RGB;
    }

    private static final PixelReader RGB = new PixelReader() {
      @Override
      void luma(final BufferedImage image, final int y, final float[] out, final int[] rgb) {
        final int w = image.getWidth();
        image.getRGB(0, y, w, 1, rgb, 0, w);
        for (int x = 0; x < w; x++) {
          final int p = rgb[x];
          out[x] = LUMA_FROM_R[(p >> 16) & 0xFF] + LUMA_FROM_G[(p >> 8) & 0xFF] + LUMA_FROM_B[p & 0xFF];
        }
      }
    };
  }

  private static final class InterleavedBytes extends PixelReader {
    private final byte[] data;
    private final int    scanline;
    private final int    pixelStride;
    private final int    red;
    private final int    green;
    private final int    blue;

    InterleavedBytes(final byte[] data, final int scanline, final int pixelStride, final int[] bandOffsets) {
      this.data = data;
      this.scanline = scanline;
      this.pixelStride = pixelStride;
      this.red = bandOffsets[0];
      this.green = bandOffsets[1];
      this.blue = bandOffsets[2];
    }

    @Override
    void luma(final BufferedImage image, final int y, final float[] out, final int[] rgb) {
      final int w = image.getWidth();
      int p = y * scanline;
      for (int x = 0; x < w; x++, p += pixelStride)
        out[x] = LUMA_FROM_R[data[p + red] & 0xFF] + LUMA_FROM_G[data[p + green] & 0xFF] + LUMA_FROM_B[data[p + blue] & 0xFF];
    }
  }

  private static final class PackedInts extends PixelReader {
    private final int[] data;
    private final int   scanline;

    PackedInts(final int[] data, final int scanline) {
      this.data = data;
      this.scanline = scanline;
    }

    @Override
    void luma(final BufferedImage image, final int y, final float[] out, final int[] rgb) {
      final int w = image.getWidth();
      final int base = y * scanline;
      for (int x = 0; x < w; x++) {
        final int p = data[base + x];
        out[x] = LUMA_FROM_R[(p >> 16) & 0xFF] + LUMA_FROM_G[(p >> 8) & 0xFF] + LUMA_FROM_B[p & 0xFF];
      }
    }
  }
}
