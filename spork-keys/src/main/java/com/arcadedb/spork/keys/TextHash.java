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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code textHash}, the key of {@code TextBody} and the link from {@code Record} to its text (01-architecture.md 2.1, A5):
 * SHA-256 over the exact bytes, with no normalization of any kind, because Recon's style vector counts case and punctuation
 * and a normalized dedup would merge texts whose style differs.
 * <p>
 * The digest covers the 4-byte big-endian UTF-8 length of the title, then the title's UTF-8, then the body's UTF-8. The
 * length prefix keeps ("Hi", "there") and ("Hit", "here") apart: {@code TextBody} stores one title per hash and derives the
 * body from the stored text by title length, so two splits of the same concatenation must not share a document.
 */
public final class TextHash {
  public static final int LENGTH = 32;

  private TextHash() {
  }

  /** 32 bytes. Null title or body read as "". */
  public static byte[] of(final String title, final String body) {
    final byte[] t = (title == null ? "" : title).getBytes(StandardCharsets.UTF_8);
    final byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
    final MessageDigest md = Sha256.reset();
    md.update((byte) (t.length >>> 24));
    md.update((byte) (t.length >>> 16));
    md.update((byte) (t.length >>> 8));
    md.update((byte) t.length);
    md.update(t);
    md.update(b);
    return md.digest();
  }
}
