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

/**
 * The purposes an analyst declares in the Operating Context (01-architecture 5.1). The gates on them are a proposal pending owner
 * question 10; {@link DataClass} and {@link SporkRoutes} are the only places that decide what each one allows.
 */
public enum Purpose {
  VICTIM_IDENTIFICATION("victim_identification"),
  INVESTIGATIVE_LEAD("investigative_lead"),
  NETWORK_DISRUPTION("network_disruption"),
  STANDING_MONITORING("standing_monitoring"),
  TRAINING("training");

  private static final Purpose[] VALUES = values();

  private final String wireName;

  Purpose(final String wireName) {
    this.wireName = wireName;
  }

  /** The value Recon sends and the audit chain records. */
  public String wireName() {
    return wireName;
  }

  /** The purpose with exactly this wire name, or null. */
  public static Purpose fromWire(final String wireName) {
    for (final Purpose purpose : VALUES)
      if (purpose.wireName.equals(wireName))
        return purpose;
    return null;
  }
}
