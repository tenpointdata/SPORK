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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Merkle root of the record keys a data-class response released (01-architecture 5.2). */
class MerkleRootTest {

  private static byte[] sha256(final byte[]... parts) throws Exception {
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (final byte[] part : parts)
      digest.update(part);
    return digest.digest();
  }

  private static byte[] leaf(final String key) throws Exception {
    return sha256(new byte[] { 0 }, key.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void matchesTheDocumentedConstruction() throws Exception {
    // sorted: "1", "2", "3"; leaves 0x00||key, nodes 0x01||left||right, an odd node is carried up unchanged
    final byte[] left = sha256(new byte[] { 1 }, leaf("1"), leaf("2"));
    final byte[] expected = sha256(new byte[] { 1 }, left, leaf("3"));

    assertThat(MerkleRoot.of(List.of("3", "1", "2"))).isEqualTo(HexFormat.of().formatHex(expected));
  }

  @Test
  void isASetCommitment() {
    assertThat(MerkleRoot.of(List.of("b", "a", "a"))).isEqualTo(MerkleRoot.of(List.of("a", "b")));
    assertThat(MerkleRoot.of(List.of("a", "b"))).isNotEqualTo(MerkleRoot.of(List.of("a", "c")));
  }

  @Test
  void aSingleKeyIsItsLeafAndTheEmptySetIsTheEmptyDigest() throws Exception {
    assertThat(MerkleRoot.of(List.of("42"))).isEqualTo(HexFormat.of().formatHex(leaf("42")));
    assertThat(MerkleRoot.of(List.of())).isEqualTo(HexFormat.of().formatHex(sha256()));
  }
}
