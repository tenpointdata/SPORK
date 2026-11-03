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

import com.arcadedb.spork.encoder.ModelId;

/**
 * One {@link MediaJobStage} implemented for one model (STUB, WS13): the image counterpart of
 * {@code Encoder}. The scheduler will batch stages of the same model across images the way it
 * batches texts; results carry the {@code algo@ver} like every vector the sidecar returns.
 */
public interface MediaEncodeStage extends AutoCloseable {
  MediaJobStage stage();

  ModelId model();

  /** @return one result per image, in order: a vector (or hash) as {@code float[]} or {@code byte[]}, or null to skip */
  Object[] run(DecodedImage[] images) throws Exception;

  @Override
  void close();
}
