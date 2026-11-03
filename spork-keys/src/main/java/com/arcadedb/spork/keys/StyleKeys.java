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
package com.arcadedb.spork.keys;

import com.arcadedb.similarity.Stylometry;

/**
 * {@code Record.style} and {@code Record.styleVec}: Recon's {@code identity.ts} {@code stylometricFingerprint} and
 * {@code styleVector}, computed per record from the exact {@code article_body} at ingest, before text dedup (2.1, 6.1). The
 * port itself is {@link Stylometry} in the engine; this names the two record fields and fixes their form. Never normalize the
 * text first: case and punctuation are features.
 */
public final class StyleKeys {
  public static final String METHOD = "style24@v1";

  private StyleKeys() {
  }

  /** {@code Record.style}: the fingerprint label Recon's compare groups by. */
  public static String fingerprint(final String articleBody) {
    return Stylometry.reconFingerprint(articleBody);
  }

  /** {@code Record.styleVec}: the 24 float32 Recon compares (96 B). */
  public static float[] vector(final String articleBody) {
    return Stylometry.reconStyleVectorFloat(articleBody);
  }
}
