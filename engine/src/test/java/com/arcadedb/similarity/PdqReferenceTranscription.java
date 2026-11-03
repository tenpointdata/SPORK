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

/**
 * The PDQ reference's structure written out literally (facebook/ThreatExchange {@code pdq/java} {@code PDQHasher}: two
 * full-image float buffers, per-pixel {@code getRGB}, strided column passes, 2-D matrices, 16-bit words printed from 15
 * down to 0), slow and simple on purpose. {@link PdqHasher} streams, reads rasters directly and packs into
 * {@link BitHash} words; {@code PdqHasherTest} checks the two agree bit for bit on images of every shape and pixel layout,
 * and the published goldens check this one.
 */
final class PdqReferenceTranscription {
  private PdqReferenceTranscription() {
  }

  record Result(String hex, int quality) {
  }

  static Result hash(final BufferedImage img) {
    final int numRows = img.getHeight();
    final int numCols = img.getWidth();
    final float[] buffer1 = new float[numRows * numCols];
    final float[] buffer2 = new float[numRows * numCols];
    for (int i = 0; i < numRows; i++)
      for (int j = 0; j < numCols; j++) {
        final int rgb = img.getRGB(j, i);
        buffer1[i * numCols + j] = (float) 0.299 * ((rgb >> 16) & 0xff) + (float) 0.587 * ((rgb >> 8) & 0xff)
            + (float) 0.114 * (rgb & 0xff);
      }
    final int windowAlongRows = (numCols + 128 - 1) / 128;
    final int windowAlongCols = (numRows + 128 - 1) / 128;
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < numRows; i++)
        box1D(buffer1, i * numCols, buffer2, i * numCols, numCols, 1, windowAlongRows);
      for (int j = 0; j < numCols; j++)
        box1D(buffer2, j, buffer1, j, numRows, numCols, windowAlongCols);
    }
    final float[][] a = new float[64][64];
    for (int i = 0; i < 64; i++) {
      final int ini = (int) (((i + 0.5) * numRows) / 64);
      for (int j = 0; j < 64; j++) {
        final int inj = (int) (((j + 0.5) * numCols) / 64);
        a[i][j] = buffer1[ini * numCols + inj];
      }
    }
    int gradientSum = 0;
    for (int i = 0; i < 63; i++)
      for (int j = 0; j < 64; j++)
        gradientSum += Math.abs((int) (((a[i][j] - a[i + 1][j]) * 100) / 255));
    for (int i = 0; i < 64; i++)
      for (int j = 0; j < 63; j++)
        gradientSum += Math.abs((int) (((a[i][j] - a[i][j + 1]) * 100) / 255));
    final int quality = Math.min(100, gradientSum / 90);

    final float[][] d = new float[16][64];
    final float scale = (float) Math.sqrt(2.0 / 64.0);
    for (int i = 0; i < 16; i++)
      for (int j = 0; j < 64; j++)
        d[i][j] = (float) (scale * Math.cos((Math.PI / 2 / 64.0) * (i + 1) * (2 * j + 1)));
    final float[][] t = new float[16][64];
    for (int i = 0; i < 16; i++)
      for (int j = 0; j < 64; j++) {
        float sumk = 0f;
        for (int k = 0; k < 64; k++)
          sumk += d[i][k] * a[k][j];
        t[i][j] = sumk;
      }
    final float[][] b = new float[16][16];
    for (int i = 0; i < 16; i++)
      for (int j = 0; j < 16; j++) {
        float sumk = 0f;
        for (int k = 0; k < 64; k++)
          sumk += t[i][k] * d[j][k];
        b[i][j] = sumk;
      }
    final float[] flat = new float[256];
    for (int i = 0; i < 16; i++)
      System.arraycopy(b[i], 0, flat, i * 16, 16);
    final float median = PdqHasher.torben(flat);
    final int[] words = new int[16];
    for (int i = 0; i < 16; i++)
      for (int j = 0; j < 16; j++)
        if (b[i][j] > median)
          words[i] |= 1 << j;
    final StringBuilder hex = new StringBuilder(64);
    for (int w = 15; w >= 0; w--)
      hex.append(String.format("%04x", words[w] & 0xffff));
    return new Result(hex.toString(), quality);
  }

  private static void box1D(final float[] invec, final int inStart, final float[] outvec, final int outStart, final int length,
      final int stride, final int fullWindowSize) {
    final int halfWindowSize = (fullWindowSize + 2) / 2;
    final int phase1 = halfWindowSize - 1;
    final int phase2 = fullWindowSize - halfWindowSize + 1;
    final int phase3 = length - fullWindowSize;
    final int phase4 = halfWindowSize - 1;
    int li = 0;
    int ri = 0;
    int oi = 0;
    float sum = 0f;
    int current = 0;
    for (int i = 0; i < phase1; i++) {
      sum += invec[inStart + ri];
      current++;
      ri += stride;
    }
    for (int i = 0; i < phase2; i++) {
      sum += invec[inStart + ri];
      current++;
      outvec[outStart + oi] = sum / current;
      ri += stride;
      oi += stride;
    }
    for (int i = 0; i < phase3; i++) {
      sum += invec[inStart + ri];
      sum -= invec[inStart + li];
      outvec[outStart + oi] = sum / current;
      li += stride;
      ri += stride;
      oi += stride;
    }
    for (int i = 0; i < phase4; i++) {
      sum -= invec[inStart + li];
      current--;
      outvec[outStart + oi] = sum / current;
      li += stride;
      oi += stride;
    }
  }
}
