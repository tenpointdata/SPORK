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
 * Similarity of an {@code LSM_IVF_VECTOR} index. Unlike the dense {@code LSM_VECTOR} family this is not jvector's
 * {@code VectorSimilarityFunction}: {@link #HAMMING} is a first-class member here, for perceptual hashes (PDQ) and other
 * binary embeddings that are compared bit by bit and never reranked.
 * <p>
 * Every search answers a DISTANCE (lower is nearer), with the same shape the {@code LSM_VECTOR} index gives so the two can
 * be swapped behind {@code vector.neighbors}: {@code 1 - cos} for COSINE, the squared L2 for EUCLIDEAN, {@code -dot} for
 * DOT_PRODUCT and the bit count for HAMMING.
 */
public enum IVFSimilarity {
  COSINE, DOT_PRODUCT, EUCLIDEAN, HAMMING;

  public static IVFSimilarity parse(final String name) {
    if (name == null)
      throw new IllegalArgumentException("similarity is null. Supported values: COSINE, DOT_PRODUCT, EUCLIDEAN, HAMMING");
    try {
      return valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid similarity function: " + name + ". Supported values: COSINE, DOT_PRODUCT, EUCLIDEAN, HAMMING");
    }
  }
}
