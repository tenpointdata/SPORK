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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.spork.encoder.image;

/**
 * Decodes the bytes of a fetched image for the encode stages. Implementations must refuse decompression bombs: the
 * header's dimensions are checked against {@code maxPixels} before any pixel is decoded.
 */
public interface ImageDecoder extends AutoCloseable {
  /**
   * @param encoded   the image file as fetched (JPEG, PNG, WebP, HEIC, GIF first frame)
   * @param maxEdge   the longest edge to shrink to while decoding (shrink-on-load), or 0 to keep the size
   * @param maxPixels the largest {@code width x height} the header may declare
   * @param channels  3 for RGB, 1 for grayscale
   */
  DecodedImage decode(byte[] encoded, int maxEdge, long maxPixels, int channels) throws ImageDecodeException;

  @Override
  void close();
}
