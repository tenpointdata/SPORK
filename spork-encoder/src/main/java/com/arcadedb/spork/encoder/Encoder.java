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

/**
 * One loaded model. {@link #encode} is called concurrently by the scheduler's workers, so an implementation must be
 * thread-safe ({@code OrtSession.run} is).
 */
public interface Encoder extends AutoCloseable {
  ModelManifest manifest();

  default ModelId id() {
    return manifest().id();
  }

  default int dimensions() {
    return manifest().dimensions();
  }

  /**
   * @return {@code texts.length x dimensions()} floats, row {@code i} for {@code texts[i]}, each row L2-normalized when
   * the manifest says so
   */
  float[] encode(String[] texts) throws Exception;

  @Override
  void close();
}
