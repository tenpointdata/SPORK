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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;

/**
 * The commitment a data-class audit entry carries to the record keys a response released (01-architecture 5.2): a binary
 * SHA-256 Merkle tree over the distinct keys in ascending order (Java string order, which is byte order for the ASCII keys SPORK
 * mints), leaves {@code SHA-256(0x00 || utf8(key))}, nodes {@code SHA-256(0x01 || left || right)}, an odd node carried up
 * unchanged; the empty set is {@code SHA-256("")}. The domain-separation bytes keep a leaf from passing for a node.
 */
public final class MerkleRoot {
  private static final byte LEAF = 0;
  private static final byte NODE = 1;

  private MerkleRoot() {
  }

  public static String of(final Collection<String> keys) {
    final MessageDigest digest = sha256();
    final String[] sorted = keys.stream().distinct().sorted().toArray(String[]::new);
    if (sorted.length == 0)
      return HexFormat.of().formatHex(digest.digest());

    byte[][] level = new byte[sorted.length][];
    for (int i = 0; i < sorted.length; i++) {
      digest.update(LEAF);
      level[i] = digest.digest(sorted[i].getBytes(StandardCharsets.UTF_8));
    }
    while (level.length > 1) {
      final byte[][] next = new byte[(level.length + 1) / 2][];
      for (int i = 0; i < level.length; i += 2) {
        if (i + 1 == level.length) {
          next[i / 2] = level[i];
          continue;
        }
        digest.update(NODE);
        digest.update(level[i]);
        next[i / 2] = digest.digest(level[i + 1]);
      }
      level = next;
    }
    return HexFormat.of().formatHex(level[0]);
  }

  /** Same as {@link #of(Collection)} over a primitive array, without boxing: the keys are decimal {@code record_id}s. */
  public static String ofRecordIds(final long[] recordIds, final int count) {
    final String[] keys = new String[count];
    for (int i = 0; i < count; i++)
      keys[i] = Long.toUnsignedString(recordIds[i]);
    return of(Arrays.asList(keys));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
