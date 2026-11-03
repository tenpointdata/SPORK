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
 * A decoded image, off the database heap: 8-bit samples, interleaved, row-major, {@code channels} per pixel (3 for RGB,
 * 1 for the grayscale PDQ works on). {@code pixels} holds exactly {@code width x height x channels} bytes.
 */
public record DecodedImage(int width, int height, int channels, byte[] pixels, String sourceFormat) {
  public DecodedImage {
    if (width <= 0 || height <= 0 || channels <= 0 || pixels.length != width * height * channels)
      throw new IllegalArgumentException("Pixel buffer does not match " + width + "x" + height + "x" + channels);
  }
}
