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
package com.arcadedb.spork.encoder;

import java.util.regex.Pattern;

/**
 * The {@code algo@ver} tag every stored vector and code carries (01-architecture.md 4c.2): codes of two versions never
 * compare, so the index keeps them in separate codebook namespaces. Both parts are restricted to
 * {@code [A-Za-z0-9._-]}, which also makes them safe as the model's directory names.
 */
public record ModelId(String algo, String ver) {
  private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]+");

  public ModelId {
    if (algo == null || !PART.matcher(algo).matches() || algo.startsWith("."))
      throw new IllegalArgumentException("Invalid model algo '" + algo + "'");
    if (ver == null || !PART.matcher(ver).matches() || ver.startsWith("."))
      throw new IllegalArgumentException("Invalid model version '" + ver + "'");
  }

  public static ModelId parse(final String id) {
    final int at = id.indexOf('@');
    if (at < 0 || id.indexOf('@', at + 1) >= 0)
      throw new IllegalArgumentException("Model id '" + id + "' is not algo@ver");
    return new ModelId(id.substring(0, at), id.substring(at + 1));
  }

  @Override
  public String toString() {
    return algo + '@' + ver;
  }
}
