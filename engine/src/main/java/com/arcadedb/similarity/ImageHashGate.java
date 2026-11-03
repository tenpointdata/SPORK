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
 * The known-hash gate of docs/spork/01-architecture.md 5.7: every image is checked against the known-hash lists before any
 * vision model (YuNet, AuraFace, the scene model, OCR) sees it, and only a {@link HashGateDecision.Outcome#CLEAR} answer
 * lets one run. The gate sees an {@link ImageDigest} (exact digests and PDQ), never pixels.
 * <p>
 * This is the hook only: SPORK ships no hash list and no implementation that reaches one (PhotoDNA through the qualified
 * reporting interface, or a PDQ and MD5 list). The enrichment worker binds one when WS5 lands
 * (docs/spork/workstreams/ws12-pdq.md). An implementation must:
 * </p>
 * <ul>
 *   <li>answer {@link HashGateDecision#unavailable} rather than throw or answer CLEAR when its list cannot be consulted,
 *   so the caller fails closed;</li>
 *   <li>not match on a PDQ hash whose quality is below {@link PdqHasher#MIN_QUALITY} (featureless images collide);</li>
 *   <li>compare the 8 dihedral PDQ hashes, so a mirrored or rotated re-post is caught;</li>
 *   <li>be thread safe: enrichment workers share one gate.</li>
 * </ul>
 */
@FunctionalInterface
public interface ImageHashGate {
  HashGateDecision check(ImageDigest digest);

  /**
   * Asks the gate, then runs {@code visionModels} only if the image is clear. The single place the order is decided: the
   * vision stage cannot run before or without the answer, and does not run on a match or an unanswered check.
   */
  default HashGateDecision checkThen(final ImageDigest digest, final Runnable visionModels) {
    final HashGateDecision decision = check(digest);
    if (decision == null)
      throw new IllegalStateException("An image hash gate must answer, got null");
    if (decision.allowsVisionModels())
      visionModels.run();
    return decision;
  }

  /**
   * Several lists as one gate: the first MATCH wins (every gate is asked until one matches); otherwise UNAVAILABLE if any
   * gate could not answer, CLEAR only when every gate answered CLEAR.
   */
  static ImageHashGate all(final ImageHashGate... gates) {
    final ImageHashGate[] copy = gates.clone();
    return digest -> {
      HashGateDecision unavailable = null;
      for (final ImageHashGate gate : copy) {
        HashGateDecision decision;
        try {
          decision = gate.check(digest);
        } catch (final RuntimeException e) {
          decision = HashGateDecision.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (decision == null)
          decision = HashGateDecision.unavailable("a gate answered null");
        if (decision.outcome() == HashGateDecision.Outcome.MATCH)
          return decision;
        if (decision.outcome() == HashGateDecision.Outcome.UNAVAILABLE && unavailable == null)
          unavailable = decision;
      }
      return unavailable != null ? unavailable : HashGateDecision.clear();
    };
  }
}
