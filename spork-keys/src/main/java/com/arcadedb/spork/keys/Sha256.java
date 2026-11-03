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
package com.arcadedb.spork.keys;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 with one reusable digest per thread: ingest hashes every text and place, so no lookup per call. */
final class Sha256 {
  private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
    }
  });

  private Sha256() {
  }

  static byte[] digest(final byte[] bytes) {
    final MessageDigest md = DIGEST.get();
    md.reset();
    return md.digest(bytes);
  }

  static MessageDigest reset() {
    final MessageDigest md = DIGEST.get();
    md.reset();
    return md;
  }
}
