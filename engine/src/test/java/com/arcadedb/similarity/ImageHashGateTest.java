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

import com.arcadedb.similarity.HashGateDecision.Outcome;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arcadedb.similarity.SimilarityTestImages.encode;
import static com.arcadedb.similarity.SimilarityTestImages.flat;
import static com.arcadedb.similarity.SimilarityTestImages.scaled;
import static com.arcadedb.similarity.SimilarityTestImages.scene;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 5.7 hash gate hook with an in-memory test double standing in for a known-hash list: no real list is used or shipped.
 * The vision stage is a counter, so each test shows it runs only on CLEAR.
 */
class ImageHashGateTest {
  private static final byte[] LISTED   = PdqHasherTest.resource("bridge-1-original.jpg");
  private static final byte[] MIRRORED = PdqHasherTest.resource("bridge-5-flipx.jpg");

  @Test
  void anExactDigestMatchKeepsVisionModelsAway() {
    final AtomicInteger vision = new AtomicInteger();
    final InMemoryHashListGate gate = new InMemoryHashListGate("test-list").addSha256(sha256(LISTED));
    final HashGateDecision decision = gate.checkThen(ImageDigest.of(LISTED), vision::incrementAndGet);
    assertThat(decision.outcome()).isEqualTo(Outcome.MATCH);
    assertThat(decision.algorithm()).isEqualTo("sha256");
    assertThat(decision.list()).isEqualTo("test-list");
    assertThat(decision.distance()).isEqualTo(0);
    assertThat(decision.allowsVisionModels()).isFalse();
    assertThat(vision.get()).isEqualTo(0);

    final byte[] md5 = ImageDigest.of(LISTED).md5();
    assertThat(new InMemoryHashListGate("md5-list").addMd5(md5).check(ImageDigest.of(LISTED)).algorithm()).isEqualTo("md5");
  }

  @Test
  void aReEncodedOrMirroredRepostMatchesThroughPdq() {
    final InMemoryHashListGate gate = new InMemoryHashListGate("test-list").addPdq(PdqHasher.hashImage(LISTED).hash());
    final AtomicInteger vision = new AtomicInteger();

    // re-encoded at half size: different bytes, PDQ a few bits away
    final BufferedImage original = decode(LISTED);
    final byte[] reEncoded = encode(scaled(original, original.getWidth() / 2, original.getHeight() / 2), "jpg");
    final HashGateDecision resized = gate.checkThen(ImageDigest.of(reEncoded), vision::incrementAndGet);
    assertThat(resized.outcome()).isEqualTo(Outcome.MATCH);
    assertThat(resized.algorithm()).isEqualTo("pdq");
    assertThat(resized.distance()).isBetween(1, PdqHasher.NEAR_DUPLICATE_DISTANCE);

    // mirrored: far from the plain hash, caught by the dihedral hashes
    final ImageDigest mirrored = ImageDigest.of(MIRRORED);
    assertThat(BitHash.hamming(mirrored.pdq(), PdqHasher.hashImage(LISTED).hash())).isGreaterThan(PdqHasher.NEAR_DUPLICATE_DISTANCE);
    assertThat(gate.checkThen(mirrored, vision::incrementAndGet).outcome()).isEqualTo(Outcome.MATCH);
    assertThat(vision.get()).isEqualTo(0);
  }

  @Test
  void anUnlistedImageIsClearAndVisionRunsOnce() {
    final InMemoryHashListGate gate = new InMemoryHashListGate("test-list").addPdq(PdqHasher.hashImage(LISTED).hash())
        .addSha256(sha256(LISTED));
    final AtomicInteger vision = new AtomicInteger();
    final HashGateDecision decision = gate.checkThen(ImageDigest.of(encode(scene(5, 320, 240), "png")), vision::incrementAndGet);
    assertThat(decision.outcome()).isEqualTo(Outcome.CLEAR);
    assertThat(decision.allowsVisionModels()).isTrue();
    assertThat(vision.get()).isEqualTo(1);
  }

  @Test
  void anUnreachableListFailsClosed() {
    final InMemoryHashListGate gate = new InMemoryHashListGate("test-list").addSha256(sha256(LISTED));
    gate.available = false;
    final AtomicInteger vision = new AtomicInteger();
    final HashGateDecision decision = gate.checkThen(ImageDigest.of(encode(scene(6, 320, 240), "png")), vision::incrementAndGet);
    assertThat(decision.outcome()).isEqualTo(Outcome.UNAVAILABLE);
    assertThat(decision.reason()).contains("unreachable");
    assertThat(vision.get()).isEqualTo(0);
    assertThatThrownBy(() -> ((ImageHashGate) d -> null).checkThen(ImageDigest.of(LISTED), vision::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);
    assertThat(vision.get()).isEqualTo(0);
  }

  @Test
  void aFeaturelessImageNeverMatchesOnPdq() {
    final byte[] grey = encode(flat(320, 240), "png");
    final byte[] otherGrey = encode(flat(300, 200), "jpg");
    final ImageDigest listed = ImageDigest.of(grey);
    assertThat(listed.pdqQuality()).isLessThan(PdqHasher.MIN_QUALITY);
    final InMemoryHashListGate gate = new InMemoryHashListGate("test-list").addPdq(listed.pdq());
    // the two flat images' hashes are noise and may well be close, but quality below 50 must not decide a match
    assertThat(gate.check(ImageDigest.of(otherGrey)).outcome()).isEqualTo(Outcome.CLEAR);
    assertThat(gate.check(listed).outcome()).isEqualTo(Outcome.CLEAR);
  }

  @Test
  void severalListsComposeMatchFirstThenUnavailable() {
    final ImageDigest digest = ImageDigest.of(LISTED);
    final InMemoryHashListGate clear = new InMemoryHashListGate("a");
    final InMemoryHashListGate down = new InMemoryHashListGate("b");
    down.available = false;
    final InMemoryHashListGate hit = new InMemoryHashListGate("c").addSha256(sha256(LISTED));
    final ImageHashGate throwing = d -> {
      throw new IllegalStateException("socket closed");
    };

    assertThat(ImageHashGate.all(clear, down, hit).check(digest).list()).isEqualTo("c");
    assertThat(ImageHashGate.all(clear, down).check(digest).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    assertThat(ImageHashGate.all(clear, throwing).check(digest).reason()).contains("socket closed");
    assertThat(ImageHashGate.all(clear, new InMemoryHashListGate("d")).check(digest).outcome()).isEqualTo(Outcome.CLEAR);
  }

  @Test
  void theDigestHoldsOnlyHashes() throws Exception {
    final ImageDigest digest = ImageDigest.of(LISTED);
    assertThat(digest.sha256()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(LISTED));
    assertThat(digest.md5()).isEqualTo(MessageDigest.getInstance("MD5").digest(LISTED));
    assertThat(BitHash.toHex(digest.pdq())).isEqualTo("f8f8f0cee0f4a84f06370a22038f63f0b36e2ed596621e1d33e6b39c4e9c9b22");
    assertThat(digest.pdqDihedral()).hasNumberOfRows(8);
    assertThat(digest.pdqDihedral()[0]).isEqualTo(digest.pdq());
    assertThat(digest.pdqQuality()).isEqualTo(100);
  }

  /**
   * A test double for a known-hash list: exact SHA-256 and MD5 sets and PDQ hashes matched at the reference's distance on
   * any of the 8 dihedral hashes, quality-gated. Not a list SPORK ships.
   */
  static final class InMemoryHashListGate implements ImageHashGate {
    private final    String       name;
    private final    Set<String>  sha256 = new HashSet<>();
    private final    Set<String>  md5    = new HashSet<>();
    private final    List<long[]> pdq    = new ArrayList<>();
    volatile boolean              available = true;

    InMemoryHashListGate(final String name) {
      this.name = name;
    }

    InMemoryHashListGate addSha256(final byte[] digest) {
      sha256.add(HexFormat.of().formatHex(digest));
      return this;
    }

    InMemoryHashListGate addMd5(final byte[] digest) {
      md5.add(HexFormat.of().formatHex(digest));
      return this;
    }

    InMemoryHashListGate addPdq(final long[] hash) {
      pdq.add(hash);
      return this;
    }

    @Override
    public HashGateDecision check(final ImageDigest digest) {
      if (!available)
        return HashGateDecision.unavailable("list " + name + " unreachable");
      if (sha256.contains(HexFormat.of().formatHex(digest.sha256())))
        return HashGateDecision.match(name, "sha256", 0);
      if (md5.contains(HexFormat.of().formatHex(digest.md5())))
        return HashGateDecision.match(name, "md5", 0);
      if (digest.pdqQuality() >= PdqHasher.MIN_QUALITY) {
        int best = Integer.MAX_VALUE;
        for (final long[] listed : pdq)
          for (final long[] variant : digest.pdqDihedral())
            best = Math.min(best, BitHash.hamming(listed, variant));
        if (best <= PdqHasher.NEAR_DUPLICATE_DISTANCE)
          return HashGateDecision.match(name, "pdq", best);
      }
      return HashGateDecision.clear();
    }
  }

  private static byte[] sha256(final byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static BufferedImage decode(final byte[] bytes) {
    try {
      return ImageIO.read(new ByteArrayInputStream(bytes));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
