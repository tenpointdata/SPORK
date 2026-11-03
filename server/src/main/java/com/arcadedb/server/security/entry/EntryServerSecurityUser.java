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

import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.security.ServerSecurityUser;

/**
 * An Entry person as an ArcadeDB principal: named {@code entry:<account id>}, with the databases and groups their Entry
 * permissions grant. Never persisted and never in the users file, so it cannot be logged into with a password, and an
 * ArcadeDB session token minted for it does not resolve (the session path re-reads the users map).
 */
public class EntryServerSecurityUser extends ServerSecurityUser {
  private final EntryPrincipal principal;

  public EntryServerSecurityUser(final ArcadeDBServer server, final EntryPrincipal principal, final JSONObject databases) {
    super(server, new JSONObject().put("name", principal.userName()).put("databases", databases));
    this.principal = principal;
  }

  public EntryPrincipal getPrincipal() {
    return principal;
  }
}
