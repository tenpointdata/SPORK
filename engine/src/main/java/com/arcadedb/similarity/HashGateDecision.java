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
package com.arcadedb.similarity;

/**
 * An {@link ImageHashGate}'s answer for one image.
 *
 * @param outcome   {@link Outcome#CLEAR}, {@link Outcome#MATCH} or {@link Outcome#UNAVAILABLE}
 * @param list      the known-hash list that matched (MATCH only)
 * @param algorithm the hash that matched: {@code sha256}, {@code md5}, {@code pdq}, or a gate's own (MATCH only)
 * @param distance  the Hamming distance of a perceptual match, 0 for an exact one, -1 when there is no match
 * @param reason    why the list could not answer (UNAVAILABLE only)
 */
public record HashGateDecision(Outcome outcome, String list, String algorithm, int distance, String reason) {
  public enum Outcome {
    /** No list holds the image: vision models may run. */
    CLEAR,
    /**
     * A known-hash list holds the image: it is never embedded, templated, OCRed or stored beyond the hash and the report
     * record, and the report path starts (01-architecture.md 5.7).
     */
    MATCH,
    /** A list could not be consulted: fail closed, hold the image (no vision model) and ask again later. */
    UNAVAILABLE
  }

  private static final HashGateDecision CLEAR_DECISION = new HashGateDecision(Outcome.CLEAR, null, null, -1, null);

  public static HashGateDecision clear() {
    return CLEAR_DECISION;
  }

  public static HashGateDecision match(final String list, final String algorithm, final int distance) {
    return new HashGateDecision(Outcome.MATCH, list, algorithm, distance, null);
  }

  public static HashGateDecision unavailable(final String reason) {
    return new HashGateDecision(Outcome.UNAVAILABLE, null, null, -1, reason);
  }

  /** True only for CLEAR: a match and an unanswered check both keep every vision model away from the image. */
  public boolean allowsVisionModels() {
    return outcome == Outcome.CLEAR;
  }
}
