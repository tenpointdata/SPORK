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
package com.arcadedb.index.ivfvector;

import java.util.Locale;

/**
 * The code every posting entry carries for the first pass of a search: the code the scan computes its approximate
 * distance on, over whole posting pages, before the survivors are reranked.
 * <ul>
 *   <li>{@link #BINARY}: one bit per dimension (the sign against the per-dimension threshold learned with the centroids),
 *   scored by Hamming distance on 64-bit words: 32 bytes for a 256-d vector, the cheapest scan there is</li>
 *   <li>{@link #INT8}: one signed byte per dimension plus a per-vector scale, scored by an integer dot product</li>
 *   <li>{@link #PRODUCT}: product quantization codes. Accepted by the parser, NOT implemented by this spike: the index
 *   refuses it at creation</li>
 * </ul>
 */
public enum IVFScanCode {
  BINARY, INT8, PRODUCT;

  public static IVFScanCode parse(final String name) {
    if (name == null)
      throw new IllegalArgumentException("scanCode is null. Supported values: BINARY, INT8, PRODUCT");
    try {
      return valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid scanCode: " + name + ". Supported values: BINARY, INT8, PRODUCT");
    }
  }
}
