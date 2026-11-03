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
package com.arcadedb.function.sql.similarity;

import com.arcadedb.TestHelper;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.similarity.HammingBands;
import com.arcadedb.similarity.PdqHasher;
import com.arcadedb.similarity.PdqHasherTest;
import com.arcadedb.similarity.PerceptualHash;
import com.arcadedb.similarity.PerceptualHash.Method;
import com.arcadedb.similarity.Stylometry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arcadedb.similarity.SimilarityTestImages.encode;
import static com.arcadedb.similarity.SimilarityTestImages.scaled;
import static com.arcadedb.similarity.SimilarityTestImages.scene;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The {@code style.*}, {@code image.*} and {@code hamming.*} SQL functions (docs/spork/06-stylometry-perceptual-hash.md),
 * from SQL and from Cypher, and the band-key index recipe end to end.
 */
class SimilaritySQLFunctionsTest extends TestHelper {
  private static final String AD = "NEW in town!!! Call me now... only the best for you. Text 555-123-4567";

  @Test
  void styleFunctionsMatchTheLibrary() {
    final Result r = one("SELECT style.vector(:t) AS v, style.fingerprint(:t) AS f, style.profile(:t) AS p, "
        + "style.profile(:t, 64) AS p64, style.similarity(:t, :t) AS same, style.similarity(:t, :u, 'recon') AS recon, "
        + "style.similarity(style.vector(:t), style.vector(:u)) AS vectors, style.vector(null) AS none", Map.of("t", AD, "u",
        "hello there, how are you today"));
    assertThat((float[]) r.getProperty("v")).isEqualTo(Stylometry.reconStyleVectorFloat(AD));
    assertThat(r.<String>getProperty("f")).isEqualTo(Stylometry.reconFingerprint(AD));
    assertThat((float[]) r.getProperty("p")).isEqualTo(Stylometry.profile(AD, 256));
    assertThat((float[]) r.getProperty("p64")).hasSize(64);
    assertThat(r.<Double>getProperty("same")).isCloseTo(1.0, within(1e-6));
    assertThat(r.<Double>getProperty("recon")).isCloseTo(r.<Double>getProperty("vectors"), within(1e-6));
    assertThat(r.<Object>getProperty("none")).isNull();
  }

  @Test
  void styleFunctionsRefuseBadArguments() {
    assertThatThrownBy(() -> one("SELECT style.vector(42) AS v", Map.of())).hasMessageContaining("must be a string");
    assertThatThrownBy(() -> one("SELECT style.profile('x', 100) AS v", Map.of())).hasMessageContaining("power of 2");
    assertThatThrownBy(() -> one("SELECT style.similarity('a', 'b', 'luar') AS v", Map.of())).hasMessageContaining("'profile' or 'recon'");
  }

  @Test
  void imageFunctionsHashBytesAndBase64() {
    final byte[] png = encode(scene(21, 320, 240), "png");
    final Result r = one("SELECT image.perceptualHash(:img) AS ph, image.perceptualHash(:img, 'pdq') AS pdq, "
            + "image.perceptualHash(:b64, 'pdq') AS fromBase64, image.perceptualHash(:img, 'pdq', true) AS all8, "
            + "image.quality(:img) AS q, image.perceptualHash(null) AS none",
        Map.of("img", png, "b64", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)));
    assertThat(r.<String>getProperty("ph")).isEqualTo(BitHash.toHex(PerceptualHash.hash(png, Method.PHASH)));
    assertThat(r.<String>getProperty("pdq")).hasSize(64).isEqualTo(r.<String>getProperty("fromBase64"));
    assertThat(r.<List<String>>getProperty("all8")).hasSize(8).first().isEqualTo(r.<String>getProperty("pdq"));
    assertThat(r.<Integer>getProperty("q")).isBetween(0, 100);
    assertThat(r.<Object>getProperty("none")).isNull();

    assertThatThrownBy(() -> one("SELECT image.perceptualHash(:img) AS h", Map.of("img", new byte[] { 1, 2, 3 })))
        .hasMessageContaining("not a decodable image");
    assertThatThrownBy(() -> one("SELECT image.perceptualHash(:img, 'md5') AS h", Map.of("img", png))).hasMessageContaining("ahash");
  }

  @Test
  void imagePdqReturnsTheReferenceHashAndQuality() {
    final byte[] photo = PdqHasherTest.resource("bridge-1-original.jpg");
    final byte[] flipped = PdqHasherTest.resource("bridge-5-flipx.jpg");
    final Result r = one("SELECT image.pdq(:img) AS p, image.pdq(:img, true) AS all8, image.pdq(null) AS none, "
        + "hamming.distance(image.pdq(:img).hash, image.pdq(:flip).hash) AS d, "
        + "hamming.distance(image.pdq(:img, true).hashes, image.pdq(:flip).hash) AS dihedral", Map.of("img", photo, "flip", flipped));
    final Map<String, Object> p = r.getProperty("p");
    assertThat(p).containsEntry("hash", "f8f8f0cee0f4a84f06370a22038f63f0b36e2ed596621e1d33e6b39c4e9c9b22").containsEntry("quality", 100);
    final Map<String, Object> all8 = r.getProperty("all8");
    assertThat(all8).containsEntry("hash", p.get("hash")).containsEntry("quality", 100);
    assertThat((List<String>) all8.get("hashes")).hasSize(8).first().isEqualTo(p.get("hash"));
    assertThat(r.<Object>getProperty("none")).isNull();
    // a mirrored re-post is far from the plain hash and near one of the dihedral hashes
    assertThat(r.<Integer>getProperty("d")).isGreaterThan(PdqHasher.NEAR_DUPLICATE_DISTANCE);
    assertThat(r.<Integer>getProperty("dihedral")).isLessThanOrEqualTo(PdqHasher.NEAR_DUPLICATE_DISTANCE);

    // stored as 32 bytes, compared and banded as stored
    final Result stored = one("SELECT hamming.distance(:bytes, image.pdq(:img).hash) AS d, hamming.bandKeys(:bytes, 8) AS keys",
        Map.of("img", photo, "bytes", BitHash.toBytes(BitHash.parseHex((String) p.get("hash")))));
    assertThat(stored.<Integer>getProperty("d")).isEqualTo(0);
    assertThat(stored.<List<Long>>getProperty("keys")).hasSize(8);

    assertThatThrownBy(() -> one("SELECT image.pdq(:img) AS h", Map.of("img", new byte[] { 1, 2, 3 })))
        .hasMessageContaining("not a decodable image");
    assertThatThrownBy(() -> one("SELECT image.pdq(:img, 'yes') AS h", Map.of("img", photo))).hasMessageContaining("boolean");
    try (final ResultSet rs = database.query("opencypher", "RETURN image.pdq($img).quality AS q", Map.of("img", photo))) {
      assertThat(((Number) rs.next().getProperty("q")).intValue()).isEqualTo(100);
    }
  }

  @Test
  void hammingFunctions() {
    final Result r = one("SELECT hamming.distance('ff00', '0f00') AS d, hamming.distance(['ffff', '0000'], '0001') AS best, "
        + "hamming.distance(null, 'ff') AS none, hamming.bandKeys('0001000200030004', 4) AS keys, "
        + "hamming.probeKeys('0001000200030004', 4, 7) AS probes", Map.of());
    assertThat(r.<Integer>getProperty("d")).isEqualTo(4);
    assertThat(r.<Integer>getProperty("best")).isEqualTo(1);
    assertThat(r.<Object>getProperty("none")).isNull();
    assertThat(r.<List<Long>>getProperty("keys")).containsExactly(1L, (1L << 32) | 2, (2L << 32) | 3, (3L << 32) | 4);
    assertThat(r.<List<Long>>getProperty("probes")).hasSize(4 * 17);

    assertThatThrownBy(() -> one("SELECT hamming.distance('ff', 'ffffffffffffffffff') AS d", Map.of())).hasMessageContaining("64-bit");
    assertThatThrownBy(() -> one("SELECT hamming.bandKeys('ff', 3) AS d", Map.of())).hasMessageContaining("divide");
  }

  @Test
  void bandKeyIndexFindsNearDuplicatesFromSql() {
    database.command("sql", "CREATE DOCUMENT TYPE Img");
    database.command("sql", "CREATE PROPERTY Img.phash STRING");
    database.command("sql", "CREATE PROPERTY Img.bands LIST OF LONG");
    database.command("sql", "CREATE INDEX ON Img (bands BY ITEM) NOTUNIQUE");

    final byte[] original = encode(scene(31, 400, 300), "png");
    final byte[] nearDuplicate = encode(scaled(scene(31, 400, 300), 200, 150), "jpg");
    database.transaction(() -> {
      insert("orig", original);
      insert("dup", nearDuplicate);
      for (int i = 0; i < 20; i++)
        insert("other" + i, encode(scene(100 + i, 200, 150), "png"));
    });

    final String q = BitHash.toHex(PerceptualHash.hash(original, Method.PHASH));
    final Set<String> found = new HashSet<>();
    try (final ResultSet rs = database.query("sql",
        "SELECT name FROM Img WHERE bands CONTAINSANY hamming.probeKeys(:q, 4, 8) AND hamming.distance(phash, :q) <= 8",
        Map.of("q", q))) {
      rs.forEachRemaining(row -> found.add(row.getProperty("name")));
    }
    final Set<String> expected = new HashSet<>();
    try (final ResultSet rs = database.query("sql", "SELECT name FROM Img WHERE hamming.distance(phash, :q) <= 8", Map.of("q", q))) {
      rs.forEachRemaining(row -> expected.add(row.getProperty("name")));
    }
    assertThat(expected).contains("orig", "dup");
    assertThat(found).isEqualTo(expected);
  }

  @Test
  void functionsAreReachableFromCypher() {
    try (final ResultSet rs = database.query("opencypher",
        "RETURN style.fingerprint($t) AS f, hamming.distance('ff', '0f') AS d", Map.of("t", AD))) {
      final Result r = rs.next();
      assertThat(r.<String>getProperty("f")).isEqualTo(Stylometry.reconFingerprint(AD));
      assertThat(((Number) r.getProperty("d")).intValue()).isEqualTo(4);
    }
  }

  @Test
  void aPropertyNamedImageKeepsItsHashMethod() {
    // image.perceptualHash, not image.hash: a namespaced function shadows <property>.<method>() for a property of that name
    database.command("sql", "CREATE DOCUMENT TYPE Pic");
    database.transaction(() -> database.newDocument("Pic").set("image", "abc").save());
    final Result r = one("SELECT image.hash() AS h, 'abc'.hash() AS expected FROM Pic", Map.of());
    assertThat(r.<String>getProperty("h")).isEqualTo(r.<String>getProperty("expected"));
  }

  private void insert(final String name, final byte[] image) {
    final long[] hash = PerceptualHash.hash(image, Method.PHASH);
    final List<Long> bands = new ArrayList<>();
    for (final long k : HammingBands.bandKeys(hash, 4))
      bands.add(k);
    database.newDocument("Img").set("name", name).set("phash", BitHash.toHex(hash)).set("bands", bands).save();
  }

  private Result one(final String sql, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("sql", sql, params)) {
      assertThat(rs.hasNext()).isTrue();
      return rs.next();
    }
  }
}
