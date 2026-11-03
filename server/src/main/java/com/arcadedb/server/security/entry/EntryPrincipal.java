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
 * A person as Entry proved them for one exact credential: the session proof's correlation fields (entry-contracts
 * {@code TConsumerSessionProof}) plus the account's email and permissions. Carries no credential.
 *
 * @param accountSequence the account's lifecycle cursor when the proof was read; 0 when Entry has no account event
 * @param credentialId    Entry's one-way fingerprint of the presented credential
 */
public record EntryPrincipal(String environmentId, String sourceUserId, String identityId, String credentialId, long accountSequence,
                             String email, String[] permissions, long expiresAtMillis) {
  /** The engine user name: never {@code root}, so an Entry person cannot administer the server. */
  public String userName() {
    return "entry:" + sourceUserId;
  }
}
