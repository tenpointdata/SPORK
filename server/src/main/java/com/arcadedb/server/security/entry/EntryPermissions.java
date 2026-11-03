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

/**
 * Entry permission matching as Recon's gateway does it ({@code gateway/auth.ts}): a held permission covers a required one when
 * they are equal or when the required one continues the held one after a dot, so {@code spork} covers {@code spork.console}.
 */
public final class EntryPermissions {
  private EntryPermissions() {
  }

  public static boolean holds(final String[] held, final String required) {
    if (held == null || required == null)
      return false;
    for (final String permission : held)
      if (permission != null && !permission.isEmpty() && (permission.equals(required) || (required.startsWith(permission)
          && required.length() > permission.length() && required.charAt(permission.length()) == '.')))
        return true;
    return false;
  }
}
