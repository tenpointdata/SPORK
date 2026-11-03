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
 * The code a search scores the survivors of the scan pass on, kept beside the scan code in the same posting page the scan has
 * already read, so the rerank costs no further I/O.
 * <ul>
 *   <li>{@link #INT8}: a signed byte per dimension of the vector plus a per-vector scale ({@code dims + 4} bytes)</li>
 *   <li>{@link #INT4}: four bits per dimension of the RESIDUAL of the vector against the centroid of its list, plus a scale and
 *   one correction float ({@code ceil(dims / 2) + 8} bytes, a quarter of the INT8 bytes at the same width once the scan code
 *   is counted out). The residual is a fraction of the vector's magnitude on clustered data, so the same 16 levels resolve
 *   it several times finer than a code of the vector itself, and the query side stays in floats (asymmetric distance)</li>
 *   <li>{@link #NONE}: the scan distances as they are (the only choice for HAMMING)</li>
 * </ul>
 */
public enum IVFRerankCode {
  // Ordinals are persisted in the header page: append, never reorder
  INT8, NONE, INT4;

  public static IVFRerankCode parse(final String name) {
    if (name == null)
      throw new IllegalArgumentException("rerankCode is null. Supported values: INT8, INT4, NONE");
    try {
      return valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid rerankCode: " + name + ". Supported values: INT8, INT4, NONE");
    }
  }
}
