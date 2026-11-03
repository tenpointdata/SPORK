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
package com.arcadedb.similarity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * What an {@link ImageHashGate} decides on: the image's exact digests (SHA-256 and MD5, the forms known-hash lists are
 * published in) and its PDQ hash, quality and 8 dihedral hashes (mirrored and rotated re-posts). Everything here is a hash:
 * no pixel, model output or embedding, so computing it is all that happens to an image before the gate answers.
 *
 * @param pdq         the PDQ hash ({@link BitHash} layout), the first of {@code pdqDihedral}
 * @param pdqQuality  PDQ's quality, 0 to 100; a gate must not match on a PDQ hash below {@link PdqHasher#MIN_QUALITY}
 * @param pdqDihedral the 8 dihedral PDQ hashes in the reference's order
 * @param sha256      the SHA-256 of the image bytes
 * @param md5         the MD5 of the image bytes
 */
public record ImageDigest(long[] pdq, int pdqQuality, long[][] pdqDihedral, byte[] sha256, byte[] md5) {

  /** Digests image bytes: one full-resolution decode for PDQ (the 8 dihedral hashes cost one) and two passes for the digests. */
  public static ImageDigest of(final byte[] image) {
    final PdqHasher.Dihedral dihedral = PdqHasher.dihedralImage(image);
    return new ImageDigest(dihedral.hashes()[0], dihedral.quality(), dihedral.hashes(), digest("SHA-256", image),
        digest("MD5", image));
  }

  private static byte[] digest(final String algorithm, final byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is a JDK standard algorithm and should always be available", e);
    }
  }
}
