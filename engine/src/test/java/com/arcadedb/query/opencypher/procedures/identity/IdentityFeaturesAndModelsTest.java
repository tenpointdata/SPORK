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
package com.arcadedb.query.opencypher.procedures.identity;

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.query.opencypher.procedures.CypherProcedureRegistry;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Step 1 of docs/spork/05-identity-model-mlops.md: {@code identity.features}, the raw terms the model scores (what training
 * exports read), and the {@code model} option that loads a registered {@code IdentityModel} document. Graph:
 * {@link IdentityFixture}.
 */
class IdentityFeaturesAndModelsTest extends TestHelper {
  private static final double LN2 = Math.log(2);

  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    rids.putAll(IdentityFixture.build(database));
    database.getSchema().createDocumentType("IdentityModel");
    database.getSchema().createDocumentType("IdentityModelAlias");
    database.transaction(() -> {
      registerModel("recon", 1, Map.of("prior", 0.2, "kinds", Map.of("Phone", Map.of("m", 0.95))));
      registerModel("recon", 2, Map.of("prior", 0.01));
      registerModel("broken", 1, Map.of("prior", 0.2, "priorr", 0.3));
      registerModel("greedy", 1, Map.of("limit", 3));
      registerModel("visual", 1, Map.of("imageHashProperty", "img", "soft", Map.of("image", Map.of("m", 0.7))));
      database.newDocument("IdentityModelAlias").set("name", "recon-default").set("model", "recon").set("version", 1).save();
    });
  }

  // ---- identity.features ----

  @Test
  void featuresAreTheRawTermsOfAPair() {
    final Result f = features(sel("a1", "a2"), sel("b1", "b2"), IdentityFixture.options());

    assertThat(((Number) f.getProperty("sizeA")).intValue()).isEqualTo(2);
    assertThat(((Number) f.getProperty("sizeB")).intValue()).isEqualTo(2);
    assertThat(((Number) f.getProperty("population")).longValue()).isEqualTo(10_000_000L);
    assertThat(f.<Boolean>getProperty("temporal")).isTrue();

    final List<Map<String, Object>> shared = f.getProperty("shared");
    assertThat(shared).hasSize(2);
    final Map<String, Object> phone = byIdentifier(shared, rids.get("p1"));
    assertThat(phone.get("kind")).isEqualTo("Phone");
    assertThat(phone.get("countA")).isEqualTo(2);
    assertThat(phone.get("countB")).isEqualTo(1);
    assertThat(phone.get("frequency")).isEqualTo(3L);
    assertThat(phone.get("population")).isEqualTo(10_000_000L);
    assertThat(phone.get("sizeA")).isEqualTo(2);
    assertThat(phone.get("sizeB")).isEqualTo(2);
    assertThat(phone.get("gapDays")).isEqualTo(1.0);
    final Map<String, Object> email = byIdentifier(shared, rids.get("e1"));
    assertThat(email.get("kind")).isEqualTo("Email");
    assertThat(email.get("frequency")).isEqualTo(2L);
    assertThat(email.get("gapDays")).isEqualTo(5.0);

    assertThat(f.<List<?>>getProperty("disjoint")).isEmpty();
    final Map<String, Object> travel = f.getProperty("travel");
    // Only a2 (day 2) and b1 (day 3) are within the 24-hour window, both in New York
    assertThat(travel.get("comparablePairs")).isEqualTo(1);
    assertThat(travel.get("conflicts")).isEqualTo(0);
  }

  @Test
  void featuresOfTwoPeopleHoldDisjointKindsAndConflicts() {
    final Result f = features(sel("a1", "a2"), sel("c1", "c2"), IdentityFixture.options());

    assertThat(f.<List<?>>getProperty("shared")).isEmpty();
    final List<Map<String, Object>> disjoint = f.getProperty("disjoint");
    assertThat(disjoint).extracting(d -> d.get("kind")).containsExactlyInAnyOrder("Phone", "Email");
    final Map<String, Object> phone = disjoint.stream().filter(d -> "Phone".equals(d.get("kind"))).findFirst().orElseThrow();
    assertThat(phone.get("countA")).isEqualTo(2);
    assertThat(phone.get("countB")).isEqualTo(2);
    assertThat(phone.get("gapDays")).isEqualTo(0.0);
    final Map<String, Object> travel = f.getProperty("travel");
    assertThat(travel.get("comparablePairs")).isEqualTo(2);
    assertThat(travel.get("conflicts")).isEqualTo(2);
    assertThat(travel.get("pairsTruncated")).isEqualTo(false);
  }

  /**
   * The guard against training/serving skew: the weights {@code identity.compare} reports are recomputed here from
   * {@code identity.features} alone, with the formulas of 04 2.2 written out independently, as an offline trainer would.
   */
  @Test
  void compareWeightsAreRecomputableFromTheFeatures() {
    final Map<String, Object> tuned = IdentityFixture.options();
    tuned.put("kinds", Map.of("Phone", Map.of("m", 0.8, "halfLifeDays", 30, "concurrent", 0.01, "handoff", 0.2)));
    tuned.put("redundancy", 0.3);
    tuned.put("conflictLR", 0.05);

    final List<List<List<Object>>> pairs = List.of(
        List.of(sel("a1", "a2"), sel("b1", "b2")),
        List.of(sel("a1", "a2"), sel("c1", "c2")),
        List.of(sel("a1", "a2", "x"), sel("b1", "c2")),
        List.of(sel("a2"), sel("b1", "x")),
        List.of(sel("a1", "b1"), sel("a2", "b2", "c1")));
    final Map<String, Object> withSoft = softOptions();
    withSoft.put("soft", Map.of("style", Map.of("threshold", 0.95, "m", 0.35), "image", Map.of("maxDistance", 4, "concurrent", 0.01)));
    for (final Map<String, Object> opts : List.of(IdentityFixture.options(), tuned, softOptions(), withSoft))
      for (final List<List<Object>> pair : pairs) {
        final Result f = features(pair.get(0), pair.get(1), opts);
        final Result c = compare(pair.get(0), pair.get(1), opts);
        final List<Map<String, Object>> evidence = c.getProperty("evidence");

        double total = 0;
        for (final Map.Entry<String, Double> expected : referenceWeights(f, opts).entrySet()) {
          total += expected.getValue();
          final Map<String, Object> e = evidence.stream().filter(x -> key(x).equals(expected.getKey())).findFirst()
              .orElseThrow(() -> new AssertionError("compare has no evidence " + expected.getKey() + " for " + pair));
          assertThat(((Number) e.get("weight")).doubleValue()).as("%s in %s", expected.getKey(), pair)
              .isCloseTo(expected.getValue(), within(1e-9));
        }
        assertThat(evidence).as("evidence of %s", pair).hasSize(referenceWeights(f, opts).size());
        assertThat(((Number) c.getProperty("matchWeight")).doubleValue()).isCloseTo(total, within(1e-9));
      }
  }

  // ---- soft evidence (slice 3) ----

  @Test
  void featuresExportTheSoftStatistics() {
    final Result f = features(sel("a1", "a2"), sel("b1", "b2"), softOptions());
    final Map<String, Map<String, Object>> soft = f.getProperty("soft");

    final Map<String, Object> image = soft.get("image");
    assertThat(image.get("pairs")).isEqualTo(1L);
    assertThat(image.get("pairsWithin")).isEqualTo(1L);
    assertThat(image.get("memberPairsWithin")).isEqualTo(1L);
    assertThat(image.get("minDistance")).isEqualTo(1);
    assertThat(image.get("maxDistance")).isEqualTo(8); // the default for 64-bit hashes

    final Map<String, Object> style = soft.get("style");
    assertThat(style.get("pairs")).isEqualTo(4L);
    assertThat(style.get("pairsAbove")).isEqualTo(4L);
    assertThat(((Number) style.get("best")).doubleValue()).isGreaterThan(0.99);
    assertThat(style.get("threshold")).isEqualTo(0.9);

    // Without the properties there is nothing to export
    assertThat(features(sel("a1"), sel("b1"), IdentityFixture.options()).<Map<String, Object>>getProperty("soft")).isEmpty();
  }

  @Test
  void compareTurnsTheSoftSignalsIntoEvidence() {
    final List<Map<String, Object>> same = compare(sel("a1", "a2"), sel("b1", "b2"), softOptions()).getProperty("evidence");
    assertThat(soft(same, "image")).isGreaterThan(5.0);
    assertThat(soft(same, "style")).isBetween(0.0, 3.0);

    final List<Map<String, Object>> different = compare(sel("a1", "a2"), sel("c1", "c2"), softOptions()).getProperty("evidence");
    assertThat(soft(different, "image")).isLessThan(0.0);
    assertThat(soft(different, "style")).isLessThan(0.0);
  }

  @Test
  void cohesionUsesTheSoftSignals() {
    // c1 shares no identifier with a1 and b1, but its photo and style are compared with theirs
    final Map<String, Object> params = new HashMap<>();
    params.put("cluster", sel("a1", "b1", "c1"));
    params.put("opts", softOptions());
    final List<Result> rows = rows("CALL identity.cohesion($cluster, $opts) YIELD member, evidence RETURN member, evidence", params);
    final Result c1 = rows.stream().filter(r -> rids.get("c1").equals(r.getProperty("member"))).findFirst().orElseThrow();
    assertThat(c1.<List<Map<String, Object>>>getProperty("evidence")).extracting(e -> e.get("type")).contains("SOFT");
  }

  @Test
  void aModelCanCarryTheSoftSignals() {
    final Map<String, Object> viaModel = IdentityFixture.options();
    viaModel.put("model", "visual@1");
    final Map<String, Object> explicit = IdentityFixture.options();
    explicit.put("imageHashProperty", "img");
    explicit.put("soft", Map.of("image", Map.of("m", 0.7)));

    assertThat(num(compare(sel("a1"), sel("b1"), viaModel), "logOdds"))
        .isCloseTo(num(compare(sel("a1"), sel("b1"), explicit), "logOdds"), within(1e-12));
  }

  @Test
  void refusesUnknownSoftSettings() {
    final Map<String, Object> audio = softOptions();
    audio.put("soft", Map.of("audio", Map.of()));
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), audio)).hasStackTraceContaining("unknown soft key 'audio'");

    final Map<String, Object> typo = softOptions();
    typo.put("soft", Map.of("image", Map.of("mm", 0.5)));
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), typo)).hasStackTraceContaining("unknown soft.image key 'mm'");

    final Map<String, Object> range = softOptions();
    range.put("soft", Map.of("style", Map.of("threshold", 2)));
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), range)).hasStackTraceContaining("style.threshold");
  }

  // ---- model option ----

  @Test
  void aRegisteredModelScoresLikeItsSettingsGivenAsOptions() {
    final Map<String, Object> viaModel = IdentityFixture.options();
    viaModel.put("model", "recon@1");
    final Map<String, Object> explicit = IdentityFixture.options();
    explicit.put("prior", 0.2);
    explicit.put("kinds", Map.of("Phone", Map.of("m", 0.95)));

    final Result withModel = compare(sel("a2"), sel("b1"), viaModel);
    assertThat(withModel.<String>getProperty("modelVersion")).isEqualTo("recon@1");
    assertThat(num(withModel, "logOdds")).isCloseTo(num(compare(sel("a2"), sel("b1"), explicit), "logOdds"), within(1e-12));
    assertThat(compare(sel("a2"), sel("b1"), IdentityFixture.options()).<String>getProperty("modelVersion")).isNull();
  }

  @Test
  void anAliasNamesTheChampionAndMovesWithoutACacheGettingInTheWay() {
    final Map<String, Object> opts = IdentityFixture.options();
    opts.put("model", "recon-default");
    final Result first = compare(sel("a2"), sel("b1"), opts);
    assertThat(first.<String>getProperty("modelVersion")).isEqualTo("recon@1");

    database.transaction(() -> database.query("sql", "SELECT FROM IdentityModelAlias WHERE name = 'recon-default'").next()
        .getElement().orElseThrow().modify().set("version", 2).save());

    final Result second = compare(sel("a2"), sel("b1"), opts);
    assertThat(second.<String>getProperty("modelVersion")).isEqualTo("recon@2");
    assertThat(num(second, "logOdds")).isLessThan(num(first, "logOdds")); // prior 0.01 against 0.2
  }

  @Test
  void explicitOptionsOverrideTheModelKindByKind() {
    final Map<String, Object> overridden = IdentityFixture.options();
    overridden.put("model", "recon@1");
    overridden.put("prior", 0.05);
    overridden.put("kinds", Map.of("Phone", Map.of("halfLifeDays", 30)));
    final Map<String, Object> explicit = IdentityFixture.options();
    explicit.put("prior", 0.05);
    explicit.put("kinds", Map.of("Phone", Map.of("m", 0.95, "halfLifeDays", 30)));

    assertThat(num(compare(sel("a1", "a2"), sel("b1", "b2"), overridden), "logOdds"))
        .isCloseTo(num(compare(sel("a1", "a2"), sel("b1", "b2"), explicit), "logOdds"), within(1e-12));
  }

  @Test
  void everyProcedureReportsTheModelVersion() {
    final Map<String, Object> opts = IdentityFixture.options();
    opts.put("model", "recon@1");

    assertThat(features(sel("a1"), sel("b1"), opts).<String>getProperty("modelVersion")).isEqualTo("recon@1");

    final Map<String, Object> params = new HashMap<>();
    params.put("cluster", sel("a1", "a2", "b1"));
    params.put("opts", opts);
    assertThat(rows("CALL identity.cohesion($cluster, $opts) YIELD member, modelVersion RETURN modelVersion", params))
        .extracting(r -> r.<String>getProperty("modelVersion")).containsOnly("recon@1");

    params.put("cluster", sel("a1", "a2"));
    assertThat(rows("CALL identity.candidates($cluster, $opts) YIELD candidate, modelVersion RETURN modelVersion", params))
        .isNotEmpty().extracting(r -> r.<String>getProperty("modelVersion")).containsOnly("recon@1");
  }

  @Test
  void unknownOrInvalidModelsAreRefused() {
    for (final String model : List.of("nope", "recon@9", "recon@x", "nope@1"))
      assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), withModel(model))).as(model)
          .hasStackTraceContaining("unknown model '" + model + "'");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), withModel("broken@1")))
        .hasStackTraceContaining("model broken@1 has unknown settings key 'priorr'");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), withModel("greedy@1")))
        .hasStackTraceContaining("model greedy@1 has unknown settings key 'limit'");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), Map.of("model", 7))).hasStackTraceContaining("model must be");
  }

  @Test
  void aDatabaseWithoutTheModelTypesRefusesAModel() {
    database.getSchema().dropType("IdentityModelAlias");
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), withModel("recon-default")))
        .hasStackTraceContaining("unknown model 'recon-default'");
  }

  @Test
  void featuresIsRegistered() {
    assertThat(CypherProcedureRegistry.hasProcedure("identity.features")).isTrue();
  }

  // ---- the reference implementation of 04 2.2, from features only ----

  private static Map<String, Double> referenceWeights(final Result f, final Map<String, Object> opts) {
    final long population = ((Number) f.getProperty("population")).longValue();
    final double redundancy = ((Number) opts.getOrDefault("redundancy", 0.5)).doubleValue();
    final Map<String, Double> weights = new HashMap<>();

    final Map<String, List<double[]>> byKind = new HashMap<>();
    final Map<String, List<String>> keysByKind = new HashMap<>();
    final List<Map<String, Object>> shared = f.getProperty("shared");
    final List<Map<String, Object>> sorted = new ArrayList<>(shared);
    sorted.sort(Comparator.comparing(s -> (RID) s.get("identifier")));
    for (final Map<String, Object> s : sorted) {
      final String kind = (String) s.get("kind");
      final double[] k = kindParams(opts, kind);
      final int countA = (Integer) s.get("countA");
      final int countB = (Integer) s.get("countB");
      final int sizeA = (Integer) s.get("sizeA");
      final int sizeB = (Integer) s.get("sizeB");
      final long frequency = (Long) s.get("frequency");
      final int sizeSmall = Math.min(sizeA, sizeB);
      final int sizeLarge = Math.max(sizeA, sizeB);
      final int countSmall = sizeA < sizeB ? countA : sizeB < sizeA ? countB : Math.min(countA, countB);
      final double successes = frequency - countSmall;
      final double trials = population - sizeSmall;
      final double p = (successes + 0.5) / (successes + 0.5 + Math.max(trials - successes, 0) + 0.5);
      final double u = 1 - Math.pow(1 - p, sizeLarge);
      final double rho = retention((Double) s.get("gapDays"), k[3]);
      final double hazard = k[1] * rho + k[2] * (1 - rho);
      final double weight = Math.max(0, Math.log(k[0]) - Math.log(u + hazard));
      byKind.computeIfAbsent(kind, x -> new ArrayList<>()).add(new double[] { weight });
      keysByKind.computeIfAbsent(kind, x -> new ArrayList<>()).add("SHARED:" + s.get("identifier"));
    }
    for (final Map.Entry<String, List<double[]>> e : byKind.entrySet()) {
      final List<double[]> w = e.getValue();
      final List<String> keys = keysByKind.get(e.getKey());
      final Integer[] order = new Integer[w.size()];
      for (int i = 0; i < order.length; i++)
        order[i] = i;
      // Strongest first, then RID order (the list is already in RID order and the sort is stable)
      Arrays.sort(order, (x, y) -> Double.compare(w.get(y)[0], w.get(x)[0]));
      double damping = 1;
      for (final int i : order) {
        weights.put(keys.get(i), w.get(i)[0] * damping / LN2);
        damping *= redundancy;
      }
    }

    final List<Map<String, Object>> disjoint = f.getProperty("disjoint");
    for (final Map<String, Object> d : disjoint) {
      final double[] k = kindParams(opts, (String) d.get("kind"));
      final double rho = retention((Double) d.get("gapDays"), k[3]);
      if (rho > 0)
        weights.put("DISJOINT:" + d.get("kind"), Math.log1p(-k[0] * rho) / LN2);
    }

    final Map<String, Map<String, Object>> soft = f.getProperty("soft");
    for (final Map.Entry<String, Map<String, Object>> e : soft.entrySet()) {
      final Map<String, Object> stats = e.getValue();
      final long pairs = (Long) stats.get("pairs");
      if (pairs == 0)
        continue;
      final long agreeing = (Long) stats.get(e.getKey().equals("style") ? "pairsAbove" : "pairsWithin");
      final double[] k = softParams(opts, e.getKey());
      final double coincidence = 1 - Math.pow(1 - k[1], pairs);
      final double q = coincidence + k[2] - coincidence * k[2];
      final double weight = agreeing > 0 ? Math.max(0, Math.log(k[0] / q)) : Math.min(0, Math.log((1 - k[0]) / (1 - q)));
      weights.put("SOFT:" + e.getKey(), weight / LN2);
    }

    final Map<String, Object> travel = f.getProperty("travel");
    final int conflicts = (Integer) travel.get("conflicts");
    if (conflicts > 0) {
      final double conflictLR = ((Number) opts.getOrDefault("conflictLR", 0.2)).doubleValue();
      weights.put("CONFLICT", (double) conflicts / (Integer) travel.get("comparablePairs") * Math.log(conflictLR) / LN2);
    }
    return weights;
  }

  /** m, concurrent, handoff, halfLifeDays of a kind: the kind's options over the documented defaults. */
  @SuppressWarnings("unchecked")
  private static double[] kindParams(final Map<String, Object> opts, final String kind) {
    final Map<String, Object> kinds = (Map<String, Object>) opts.getOrDefault("kinds", Map.of());
    final Map<String, Object> k = (Map<String, Object>) kinds.getOrDefault(kind, Map.of());
    return new double[] { ((Number) k.getOrDefault("m", 0.6)).doubleValue(), ((Number) k.getOrDefault("concurrent", 0.001)).doubleValue(),
        ((Number) k.getOrDefault("handoff", 0.05)).doubleValue(), ((Number) k.getOrDefault("halfLifeDays", 365)).doubleValue() };
  }

  /** m, falseMatchRate, concurrent of a soft signal: its options over the documented defaults. */
  @SuppressWarnings("unchecked")
  private static double[] softParams(final Map<String, Object> opts, final String signal) {
    final Map<String, Object> soft = (Map<String, Object>) opts.getOrDefault("soft", Map.of());
    final Map<String, Object> s = (Map<String, Object>) soft.getOrDefault(signal, Map.of());
    final boolean style = signal.equals("style");
    return new double[] { ((Number) s.getOrDefault("m", style ? 0.3 : 0.4)).doubleValue(),
        ((Number) s.getOrDefault("falseMatchRate", style ? 0.01 : 0.00001)).doubleValue(),
        ((Number) s.getOrDefault("concurrent", style ? 0.05 : 0.002)).doubleValue() };
  }

  /** With a timeProperty, an unknown gap keeps nothing of the binding; halfLifeDays 0 never decays. */
  private static double retention(final Double gapDays, final double halfLifeDays) {
    if (gapDays == null)
      return 0;
    if (halfLifeDays == 0)
      return 1;
    return Math.pow(2, -gapDays / halfLifeDays);
  }

  private static String key(final Map<String, Object> evidence) {
    return switch ((String) evidence.get("type")) {
      case "SHARED" -> "SHARED:" + evidence.get("identifier");
      case "DISJOINT" -> "DISJOINT:" + evidence.get("kind");
      case "SOFT" -> "SOFT:" + evidence.get("kind");
      default -> "CONFLICT";
    };
  }

  // ---- helpers ----

  private void registerModel(final String name, final int version, final Map<String, Object> settings) {
    database.newDocument("IdentityModel").set("name", name).set("version", version).set("settings", settings).save();
  }

  private static Map<String, Object> softOptions() {
    final Map<String, Object> opts = IdentityFixture.options();
    opts.put("styleProperty", "style");
    opts.put("imageHashProperty", "img");
    return opts;
  }

  private static double soft(final List<Map<String, Object>> evidence, final String signal) {
    return evidence.stream().filter(e -> "SOFT".equals(e.get("type")) && signal.equals(e.get("kind"))).findFirst()
        .map(e -> ((Number) e.get("weight")).doubleValue()).orElseThrow(() -> new AssertionError("no SOFT " + signal));
  }

  private static Map<String, Object> withModel(final String model) {
    final Map<String, Object> opts = IdentityFixture.options();
    opts.put("model", model);
    return opts;
  }

  private static Map<String, Object> byIdentifier(final List<Map<String, Object>> rows, final RID identifier) {
    return rows.stream().filter(r -> identifier.equals(r.get("identifier"))).findFirst().orElseThrow();
  }

  private Result features(final List<Object> a, final List<Object> b, final Map<String, Object> options) {
    final List<Result> rows = rows("CALL identity.features($a, $b, $opts) "
        + "YIELD sizeA, sizeB, population, temporal, sharedMembers, shared, disjoint, travel, soft, modelVersion "
        + "RETURN sizeA, sizeB, population, temporal, sharedMembers, shared, disjoint, travel, soft, modelVersion", params(a, b, options));
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private Result compare(final List<Object> a, final List<Object> b, final Map<String, Object> options) {
    final List<Result> rows = rows("CALL identity.compare($a, $b, $opts) YIELD logOdds, matchWeight, evidence, modelVersion "
        + "RETURN logOdds, matchWeight, evidence, modelVersion", params(a, b, options));
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private static Map<String, Object> params(final List<Object> a, final List<Object> b, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("a", a);
    params.put("b", b);
    params.put("opts", options);
    return params;
  }

  private static double num(final Result r, final String name) {
    return ((Number) r.getProperty(name)).doubleValue();
  }

  private List<Object> sel(final String... names) {
    final List<Object> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return list;
  }

  private List<Result> rows(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }
}
