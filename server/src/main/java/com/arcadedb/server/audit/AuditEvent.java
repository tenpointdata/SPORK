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

import com.arcadedb.serializer.json.JSONObject;

import java.util.regex.Pattern;

/** One action to record: Recon's {@code AuditEntry} fields before the chain assigns {@code seq}, {@code at} and the hashes. */
public final class AuditEvent {
  private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

  final AuditActor actor;
  final String     action;
  String     caseRef;
  String     purpose;
  String     subject;
  JSONObject detail;

  public AuditEvent(final AuditActor actor, final String action) {
    if (actor == null)
      throw new IllegalArgumentException("An audit entry needs an actor");
    if (action == null || !ACTION.matcher(action).matches())
      throw new IllegalArgumentException("Invalid audit action: " + action);
    this.actor = actor;
    this.action = action;
  }

  public AuditEvent caseRef(final String caseRef) {
    this.caseRef = caseRef;
    return this;
  }

  public AuditEvent purpose(final String purpose) {
    this.purpose = purpose;
    return this;
  }

  public AuditEvent subject(final String subject) {
    this.subject = subject;
    return this;
  }

  /** Metadata only: counts, ids, field names. Never record text. Non-primitive values are dropped when appended. */
  public AuditEvent detail(final JSONObject detail) {
    this.detail = detail;
    return this;
  }
}
