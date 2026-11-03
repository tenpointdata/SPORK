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
package com.arcadedb.spork.recon;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a response carries, which is what purpose gates and audit attach to (01-architecture 5.1, round 4: "gates and audit attach
 * to data classes, not endpoints"). A route may narrow its data class's purposes, never widen them.
 */
public enum DataClass {
  /** No corpus data: declarations and acknowledgements. Open to every purpose. */
  NONE(EnumSet.allOf(Purpose.class)),
  /**
   * Record projections and identifier footprints: intake, pivot, search rows, hydrate, set-scoped graph and geo. Not under
   * {@code standing_monitoring} (watches and detectors only) or {@code training} (synthetic or minimized views only, none of
   * which exist yet).
   */
  RECORD_PROJECTION(EnumSet.of(Purpose.VICTIM_IDENTIFICATION, Purpose.INVESTIGATIVE_LEAD, Purpose.NETWORK_DISRUPTION));

  private final Set<Purpose> purposes;

  DataClass(final Set<Purpose> purposes) {
    this.purposes = purposes;
  }

  public Set<Purpose> purposes() {
    return EnumSet.copyOf(purposes);
  }

  boolean allows(final Purpose purpose) {
    return purposes.contains(purpose);
  }
}
