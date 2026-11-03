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
package com.arcadedb.query.opencypher.procedures.similarity;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.opencypher.procedures.CypherProcedureRegistry;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.similarity.HammingBands;
import com.arcadedb.similarity.PerceptualHash;
import com.arcadedb.similarity.PerceptualHash.Method;
import com.arcadedb.similarity.Stylometry;
import com.arcadedb.utility.RidBitmap;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.arcadedb.similarity.SimilarityTestImages.encode;
import static com.arcadedb.similarity.SimilarityTestImages.flat;
import static com.arcadedb.similarity.SimilarityTestImages.scaled;
import static com.arcadedb.similarity.SimilarityTestImages.scene;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code similarity.cluster}, {@code similarity.compare} and {@code similarity.hash.neighbors}
 * (docs/spork/06-stylometry-perceptual-hash.md) over a small ad graph: a1, a2, a3 re-post one photo (resized, re-encoded)
 * and are written by author A; b1, b2 re-post another and are written by author B; c1 has its own photo; d1 has none;
 * f1 and f2 post featureless grey squares.
 */
class SimilarityProceduresTest extends TestHelper {
  private static final String[] AUTHOR_A = {
      "Hiii loves!! 😘 im new in town n ready 2 play... txt me ONLY, no calls pls!! xoxo",
      "Hiii babes!! 😘 im back in town n ready 2 have fun... txt me ONLY, no blocked #s pls!! xoxo",
      "Hiii loves!! 😘 im in ur city n ready 2 play... txt me ONLY, no calls pls!! xoxo" };
  private static final String[] AUTHOR_B = {
      "Professional, discreet companion available for upscale gentlemen. Please call to arrange an appointment. Thank you.",
      "Discreet, professional companion available for refined gentlemen. Please call in advance to arrange a meeting. Thank you." };

  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("Ad");
    database.transaction(() -> {
      final BufferedImage photoA = scene(41, 640, 480);
      final BufferedImage photoB = scene(42, 640, 480);
      ad("a1", AUTHOR_A[0], encode(photoA, "png"));
      ad("a2", AUTHOR_A[1], encode(scaled(photoA, 320, 240), "jpg"));
      ad("a3", AUTHOR_A[2], encode(scaled(photoA, 480, 360), "jpg"));
      ad("b1", AUTHOR_B[0], encode(photoB, "jpg"));
      ad("b2", AUTHOR_B[1], encode(scaled(photoB, 300, 225), "png"));
      ad("c1", "Just a normal listing, nothing to see here.", encode(scene(43, 400, 300), "png"));
      ad("d1", "No photo for this one.", null);
      ad("f1", "grey", encode(flat(200, 200), "png"));
      ad("f2", "grey", encode(flat(300, 300), "jpg"));
    });
  }

  // ---- similarity.cluster ----

  @Test
  void clustersNearDuplicateImages() {
    final List<Result> rows = cluster(List.of("a1", "a2", "a3", "b1", "b2", "c1", "d1"),
        Map.of("property", "phashes", "metric", "hamming"));
    assertThat(rows).hasSize(2);
    assertThat(names(rows.get(0))).containsExactlyInAnyOrder("a1", "a2", "a3");
    assertThat(names(rows.get(1))).containsExactlyInAnyOrder("b1", "b2");
    assertThat(rows.get(0).<Integer>getProperty("clusterId")).isZero();
    assertThat(rows.get(0).<Integer>getProperty("size")).isEqualTo(3);
    assertThat(rows.get(0).<String>getProperty("key")).hasSize(16);
    assertThat(rows.get(0).<Integer>getProperty("links")).isGreaterThanOrEqualTo(2);
    assertThat(rows.get(0).<Double>getProperty("cohesion")).isGreaterThan(0.85).isLessThanOrEqualTo(1.0);

    // the key is stable: the same group from another input order has the same key
    final List<Result> reversed = cluster(List.of("d1", "c1", "b2", "b1", "a3", "a2", "a1"),
        Map.of("property", "phashes", "metric", "hamming"));
    assertThat(reversed.get(0).<String>getProperty("key")).isEqualTo(rows.get(0).getProperty("key"));

    // minSize 1 keeps the singleton; the record without an image is in no group
    assertThat(cluster(List.of("a1", "c1", "d1"), Map.of("property", "phashes", "metric", "hamming", "minSize", 1))).hasSize(2);
  }

  @Test
  void leaderLinkageAndPdqHashes() {
    final List<Result> rows = cluster(List.of("a1", "a2", "a3", "b1", "b2", "c1"),
        Map.of("property", "pdq", "metric", "hamming", "linkage", "leader"));
    assertThat(rows).hasSize(2);
    assertThat(names(rows.get(0))).containsExactlyInAnyOrder("a1", "a2", "a3");
    assertThat(rows.get(0).<String>getProperty("key")).hasSize(64);
  }

  @Test
  void featurelessImagesClusterUnlessQualityGatesThem() {
    assertThat(cluster(List.of("f1", "f2", "c1"), Map.of("property", "phashes", "metric", "hamming"))).hasSize(1);
    assertThat(cluster(List.of("f1", "f2", "c1"), Map.of("property", "phashes", "metric", "hamming", "qualityProperty", "quality")))
        .isEmpty();
  }

  @Test
  void clustersAuthorsByWritingStyle() {
    final List<Result> rows = cluster(List.of("a1", "b1", "a2", "c1", "b2", "a3"),
        Map.of("property", "text", "metric", "cosine", "threshold", 0.5));
    assertThat(rows).hasSize(2);
    assertThat(names(rows.get(0))).containsExactlyInAnyOrder("a1", "a2", "a3");
    assertThat(names(rows.get(1))).containsExactlyInAnyOrder("b1", "b2");
    assertThat(rows.get(1).<String>getProperty("key")).isEqualTo(rids.get("b1").toString());

    // a precomputed vector property works the same as computing it from the text
    final List<Result> fromVectors = cluster(List.of("a1", "b1", "a2", "c1", "b2", "a3"),
        Map.of("property", "styleProfile", "metric", "cosine", "threshold", 0.5));
    assertThat(fromVectors.stream().map(this::names).toList()).isEqualTo(
        rows.stream().map(this::names).toList());
  }

  @Test
  void clusterRefusesBadOptions() {
    assertThatThrownBy(() -> cluster(List.of("a1"), Map.of("property", "phashes"))).hasMessageContaining("'metric' is required");
    assertThatThrownBy(() -> cluster(List.of("a1"), Map.of("property", "phashes", "metric", "jaccard"))).hasMessageContaining(
        "'hamming' or 'cosine'");
    assertThatThrownBy(() -> cluster(List.of("a1"), Map.of("property", "phashes", "metric", "hamming", "treshold", 3)))
        .hasMessageContaining("unknown options key 'treshold'");
    assertThatThrownBy(() -> cluster(List.of("a1", "b1"), Map.of("property", "text", "metric", "hamming"))).hasMessageContaining(
        "is not a hash");
    assertThatThrownBy(() -> cluster(List.of("a1", "b1"), Map.of("property", "text", "metric", "cosine", "maxRecords", 1)))
        .hasMessageContaining("maxRecords");
  }

  // ---- similarity.compare ----

  @Test
  void compareReportsStyleAndImageEvidence() {
    final Map<String, Object> params = new HashMap<>();
    params.put("a", sel("a1", "a2", "c1"));
    params.put("b", sel("a3", "b1", "c1"));
    params.put("opts", Map.of("styleProperty", "text", "hashProperty", "phashes", "styleThreshold", 0.5));
    final Result r = rows("CALL similarity.compare($a, $b, $opts) YIELD sizeA, sizeB, sharedMembers, style, images "
        + "RETURN sizeA, sizeB, sharedMembers, style, images", params).getFirst();
    assertThat(r.<Integer>getProperty("sizeA")).isEqualTo(2);
    assertThat(r.<Integer>getProperty("sizeB")).isEqualTo(2);
    assertThat(r.<Integer>getProperty("sharedMembers")).isEqualTo(1);

    final Map<String, Object> style = r.getProperty("style");
    assertThat(style.get("pairs")).isEqualTo(4L);
    assertThat((Long) style.get("pairsAbove")).isEqualTo(2L);
    assertThat((Double) style.get("max")).isGreaterThan(0.5);
    @SuppressWarnings("unchecked") final Map<String, Object> best = (Map<String, Object>) style.get("bestPair");
    assertThat(best.get("b")).isEqualTo(rids.get("a3"));

    final Map<String, Object> images = r.getProperty("images");
    assertThat(images.get("nearPairs")).isEqualTo(2);
    assertThat((Integer) images.get("minDistance")).isLessThanOrEqualTo(8);
    @SuppressWarnings("unchecked") final List<Map<String, Object>> pairs = (List<Map<String, Object>>) images.get("pairs");
    assertThat(pairs).allSatisfy(p -> assertThat(p.get("b")).isEqualTo(rids.get("a3")));

    params.put("opts", Map.of("hashProperty", "phashes"));
    final Result imagesOnly = rows("CALL similarity.compare($a, $b, $opts) YIELD style, images RETURN style, images", params).getFirst();
    assertThat(imagesOnly.<Object>getProperty("style")).isNull();
    assertThatThrownBy(() -> {
      params.put("opts", Map.of());
      rows("CALL similarity.compare($a, $b, $opts) YIELD style RETURN style", params);
    }).hasMessageContaining("styleProperty, hashProperty or both");
  }

  // ---- similarity.hash.neighbors ----

  @Test
  void neighborsThroughTheBandIndexMatchAScan() {
    database.command("sql", "CREATE DOCUMENT TYPE Img");
    database.command("sql", "CREATE PROPERTY Img.pdq STRING");
    database.command("sql", "CREATE PROPERTY Img.pdqBands LIST OF LONG");
    database.command("sql", "CREATE INDEX ON Img (pdqBands BY ITEM) NOTUNIQUE");
    final BufferedImage photo = scene(51, 500, 400);
    database.transaction(() -> {
      image("orig", encode(photo, "png"));
      image("small", encode(scaled(photo, 250, 200), "jpg"));
      image("medium", encode(scaled(photo, 400, 320), "jpg"));
      for (int i = 0; i < 30; i++)
        image("other" + i, encode(scene(200 + i, 200, 160), "png"));
    });
    final String query = BitHash.toHex(PerceptualHash.hash(encode(photo, "png"), Method.PDQ));

    final List<Result> viaIndex = neighbors(query,
        Map.of("type", "Img", "property", "pdq", "bandsProperty", "pdqBands", "bands", 16, "maxDistance", 31));
    final List<Result> viaScan = neighbors(query, Map.of("type", "Img", "property", "pdq", "scan", true, "maxDistance", 31));
    assertThat(viaIndex.stream().map(r -> ((Document) r.getProperty("node")).getString("name")).toList())
        .startsWith("orig").containsExactlyInAnyOrder("orig", "small", "medium")
        .isEqualTo(viaScan.stream().map(r -> ((Document) r.getProperty("node")).getString("name")).toList());
    assertThat(viaIndex.getFirst().<Integer>getProperty("distance")).isZero();
    assertThat(viaIndex.getFirst().<Double>getProperty("similarity")).isEqualTo(1.0);
    assertThat(viaIndex.get(1).<Integer>getProperty("distance")).isBetween(1, 31);

    assertThat(neighbors(query, Map.of("type", "Img", "property", "pdq", "bandsProperty", "pdqBands", "bands", 16, "limit", 1)))
        .hasSize(1);
    assertThatThrownBy(() -> neighbors(query, Map.of("type", "Img", "property", "pdq"))).hasMessageContaining("scan: true");
    assertThatThrownBy(() -> neighbors(query, Map.of("type", "Img", "property", "pdq", "bandsProperty", "pdq", "bands", 16)))
        .hasMessageContaining("has no index on pdq");
    assertThatThrownBy(() -> neighbors(query, Map.of("type", "Img", "property", "pdq", "bandsProperty", "pdqBands")))
        .hasMessageContaining("bands is required");
  }

  // ---- selection names ----

  @Test
  void clusterAndCompareAcceptSelectionNames() {
    saveSelection("photos", "a1", "a2", "a3", "b1", "b2", "c1", "d1");
    saveSelection("left", "a1", "a2", "c1");
    saveSelection("right", "a3", "b1", "c1");

    final Map<String, Object> options = Map.of("property", "phashes", "metric", "hamming");
    final Map<String, Object> params = new HashMap<>();
    params.put("records", "photos");
    params.put("opts", options);
    final List<Result> byName = rows("CALL similarity.cluster($records, $opts) YIELD key, size, members RETURN key, size, members",
        params);
    final List<Result> byList = cluster(List.of("a1", "a2", "a3", "b1", "b2", "c1", "d1"), options);
    assertThat(byName.stream().map(r -> r.<String>getProperty("key")).toList()).isEqualTo(
        byList.stream().map(r -> r.<String>getProperty("key")).toList());
    assertThat(byName.stream().map(this::names).toList()).isEqualTo(byList.stream().map(this::names).toList());

    final Map<String, Object> compareOptions = Map.of("styleProperty", "text", "hashProperty", "phashes", "styleThreshold", 0.5);
    params.clear();
    params.put("a", "left");
    params.put("b", "right");
    params.put("opts", compareOptions);
    final Result viaNames = rows("CALL similarity.compare($a, $b, $opts) YIELD sizeA, sizeB, sharedMembers, style, images "
        + "RETURN sizeA, sizeB, sharedMembers, style, images", params).getFirst();
    params.put("a", sel("a1", "a2", "c1"));
    params.put("b", sel("a3", "b1", "c1"));
    final Result viaLists = rows("CALL similarity.compare($a, $b, $opts) YIELD sizeA, sizeB, sharedMembers, style, images "
        + "RETURN sizeA, sizeB, sharedMembers, style, images", params).getFirst();
    for (final String field : List.of("sizeA", "sizeB", "sharedMembers"))
      assertThat(viaNames.<Object>getProperty(field)).as(field).isEqualTo(viaLists.getProperty(field));
    assertThat(((Map<?, ?>) viaNames.getProperty("images")).get("nearPairs")).isEqualTo(
        ((Map<?, ?>) viaLists.getProperty("images")).get("nearPairs"));
    assertThat(((Map<?, ?>) viaNames.getProperty("style")).get("pairsAbove")).isEqualTo(
        ((Map<?, ?>) viaLists.getProperty("style")).get("pairsAbove"));

    params.put("records", "nope");
    params.put("opts", options);
    assertThatThrownBy(() -> rows("CALL similarity.cluster($records, $opts) YIELD key RETURN key", params)).hasMessageContaining(
        "no selection named 'nope'");
  }

  // ---- identity integration ----

  @Test
  void imageClustersBecomeIdentityEvidence() {
    database.getSchema().createVertexType("ImageCluster");
    database.getSchema().createEdgeType("HAS_IMAGE_CLUSTER");
    final List<Result> groups = cluster(List.of("a1", "a2", "a3", "b1", "b2", "c1"), Map.of("property", "phashes", "metric", "hamming"));
    database.transaction(() -> {
      for (final Result g : groups) {
        final MutableVertex c = database.newVertex("ImageCluster").set("key", g.getProperty("key")).set("recordCount",
            g.<Integer>getProperty("size")).save();
        for (final RID member : g.<List<RID>>getProperty("members"))
          member.asVertex().newEdge("HAS_IMAGE_CLUSTER", c);
      }
    });

    final Map<String, Object> params = new HashMap<>();
    params.put("a", sel("a1", "a2"));
    params.put("b", sel("a3"));
    params.put("opts", Map.of("via", List.of("HAS_IMAGE_CLUSTER"), "frequencyProperty", "recordCount", "population", 1000));
    final Result r = rows("CALL identity.compare($a, $b, $opts) YIELD probability, evidence RETURN probability, evidence", params)
        .getFirst();
    final List<Map<String, Object>> evidence = r.getProperty("evidence");
    assertThat(evidence).anySatisfy(e -> {
      assertThat(e.get("type")).isEqualTo("SHARED");
      assertThat(e.get("kind")).isEqualTo("ImageCluster");
    });
    assertThat(r.<Double>getProperty("probability")).isGreaterThan(0.05);
  }

  @Test
  void proceduresAreRegistered() {
    for (final String name : Set.of(SimilarityCluster.NAME, SimilarityCompare.NAME, SimilarityHashNeighbors.NAME))
      assertThat(CypherProcedureRegistry.get(name)).as(name).isNotNull();
  }

  // ---- helpers ----

  private void ad(final String name, final String text, final byte[] image) {
    final MutableVertex v = database.newVertex("Ad").set("name", name).set("text", text);
    v.set("styleProfile", Stylometry.profile(text, 256));
    if (image != null) {
      v.set("phashes", List.of(BitHash.toHex(PerceptualHash.hash(image, Method.PHASH))));
      v.set("pdq", BitHash.toHex(PerceptualHash.hash(image, Method.PDQ)));
      v.set("quality", PerceptualHash.quality(image));
    }
    rids.put(name, v.save().getIdentity());
  }

  private void image(final String name, final byte[] bytes) {
    final long[] pdq = PerceptualHash.hash(bytes, Method.PDQ);
    final List<Long> bands = new ArrayList<>();
    for (final long k : HammingBands.bandKeys(pdq, 16))
      bands.add(k);
    database.newDocument("Img").set("name", name).set("pdq", BitHash.toHex(pdq)).set("pdqBands", bands).save();
  }

  private void saveSelection(final String name, final String... members) {
    final RidBitmap bitmap = new RidBitmap();
    for (final String m : members)
      bitmap.add(rids.get(m));
    database.transaction(() -> new SelectionStore(database, "test").save(name, bitmap, 0, null, null, null, false));
  }

  private List<Object> sel(final String... names) {
    final List<Object> out = new ArrayList<>();
    for (final String n : names)
      out.add(rids.get(n));
    return out;
  }

  private List<Result> cluster(final List<String> names, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("records", sel(names.toArray(new String[0])));
    params.put("opts", options);
    return rows("CALL similarity.cluster($records, $opts) YIELD clusterId, key, size, members, links, cohesion "
        + "RETURN clusterId, key, size, members, links, cohesion", params);
  }

  private List<Result> neighbors(final String hash, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("hash", hash);
    params.put("opts", options);
    return rows("CALL similarity.hash.neighbors($hash, $opts) YIELD node, distance, similarity, hash "
        + "RETURN node, distance, similarity, hash", params);
  }

  private Set<String> names(final Result row) {
    final Map<RID, String> byRid = rids.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    return row.<List<RID>>getProperty("members").stream().map(byRid::get).collect(Collectors.toSet());
  }

  private List<Result> rows(final String query, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      final List<Result> out = new ArrayList<>();
      rs.forEachRemaining(out::add);
      return out;
    }
  }
}
