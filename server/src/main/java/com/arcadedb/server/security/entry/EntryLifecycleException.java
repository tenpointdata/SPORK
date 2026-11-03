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
package com.arcadedb.server.security.entry;

/** A refused lifecycle delivery, with entry-lifecycle's {@code LifecycleReceiverError} codes. */
public class EntryLifecycleException extends RuntimeException {
  private final String code;

  public EntryLifecycleException(final String code) {
    super("Entry lifecycle delivery refused: " + code);
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  public int getHttpStatus() {
    return switch (code) {
      case "invalid_delivery" -> 400;
      case "delivery_conflict", "stale_event", "binding_changed", "revocation_regressed" -> 409;
      case "state_unavailable" -> 503;
      default -> 422;
    };
  }
}
