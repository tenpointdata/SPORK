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
package com.arcadedb.server.audit;

import com.arcadedb.security.SecurityUser;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.security.entry.EntryPrincipal;
import com.arcadedb.server.security.entry.EntryServerSecurityUser;

/**
 * Who an audit entry is about acting. An Entry person is named by their Entry account id, the same string Recon's gateway
 * writes as {@code actor}, plus the exact Entry credential and account lifecycle position, all inside the hashed body.
 * Automation is {@code system:<job>} acting on behalf of a person; local break-glass users are {@code local:<name>}.
 */
public final class AuditActor {
  private static final String SYSTEM_PREFIX = "system:";

  private final String     actor;
  private final String     onBehalfOf;
  private final JSONObject entry;

  private AuditActor(final String actor, final String onBehalfOf, final JSONObject entry) {
    this.actor = actor;
    this.onBehalfOf = onBehalfOf;
    this.entry = entry;
  }

  public static AuditActor entry(final EntryPrincipal principal) {
    return new AuditActor(principal.sourceUserId(), null, new JSONObject()//
        .put("env", principal.environmentId())//
        .put("identity", principal.identityId())//
        .put("credential", principal.credentialId())//
        .put("account", principal.accountSequence()));
  }

  public static AuditActor local(final String userName) {
    return new AuditActor("local:" + userName, null, null);
  }

  public static AuditActor system(final String job, final String onBehalfOf) {
    return new AuditActor(job.startsWith(SYSTEM_PREFIX) ? job : SYSTEM_PREFIX + job, onBehalfOf, null);
  }

  public static AuditActor of(final SecurityUser user) {
    if (user instanceof EntryServerSecurityUser entryUser)
      return entry(entryUser.getPrincipal());
    return local(user != null ? user.getName() : "anonymous");
  }

  public String getActor() {
    return actor;
  }

  void writeTo(final JSONObject body) {
    body.put("actor", actor);
    if (onBehalfOf != null)
      body.put("onBehalfOf", onBehalfOf);
    if (entry != null)
      body.put("entry", new JSONObject(entry.toString()));
  }
}
