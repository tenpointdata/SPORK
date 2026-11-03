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

import com.arcadedb.server.security.ServerSecurityException;

/**
 * A refused Entry credential. The code is stable and safe to return; it never carries Entry's answer, a URL or the token.
 */
public class EntryAuthenticationException extends ServerSecurityException {
  private final String code;

  public EntryAuthenticationException(final String code) {
    super("Entry authentication refused: " + code);
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  /** 403 for a known person who may not proceed, 503 when SPORK cannot ask Entry or read standing, 401 otherwise. */
  public int getHttpStatus() {
    return switch (code) {
      case "no_permission", "standing_denied" -> 403;
      case "proof_unavailable", "lookup_saturated", "state_unavailable" -> 503;
      default -> 401;
    };
  }
}
