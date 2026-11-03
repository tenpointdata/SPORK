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

import com.arcadedb.database.RID;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.Evidence;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.EvidenceType;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.MatchResult;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The scoring model behind {@code identity.compare} and {@code identity.cohesion} (docs/spork/04-identity-resolution-design.md),
 * tested without a database. Identifiers are RIDs whose bucket names their kind: bucket 10 is a phone, 11 an email.
 */
class IdentityMatchModelTest {
  private static final long DAY = 86_400_000L;
  private static final long T0  = 1_700_000_000_000L;

  private static final long   PHOTO      = 0x00FF00FF00FF00FFL;
  private static final long   OTHER      = 0x7E7E7E7E7E7E7E7EL;
  private static final long[] IMAGE_POOL = { PHOTO, PHOTO ^ 1, OTHER, OTHER ^ 6 };

  private IdentityMatchModel model;
  private int                phoneKind;
  private int                emailKind;

  @BeforeEach
  void setUp() {
    model = new IdentityMatchModel().population(10_000_000L);
    phoneKind = model.kindIndex("Phone");
    emailKind = model.kindIndex("Email");
  }

  // ---- statistics ----

  @Test
  void trigammaMatchesClosedForms() {
    assertThat(IdentityStatistics.trigamma(1.0)).isCloseTo(Math.PI * Math.PI / 6, within(1e-10));
    assertThat(IdentityStatistics.trigamma(0.5)).isCloseTo(Math.PI * Math.PI / 2, within(1e-10));
    // psi1(x + 1) = psi1(x) - 1 / x^2
    assertThat(IdentityStatistics.trigamma(7.25)).isCloseTo(IdentityStatistics.trigamma(6.25) - 1 / (6.25 * 6.25), within(1e-12));
  }

  @Test
  void normalQuantileMatchesTables() {
    assertThat(IdentityStatistics.normalQuantile(0.975)).isCloseTo(1.959964, within(1e-6));
    assertThat(IdentityStatistics.normalQuantile(0.5)).isCloseTo(0.0, within(1e-9));
    assertThat(IdentityStatistics.normalQuantile(0.995)).isCloseTo(2.575829, within(1e-6));
    assertThat(IdentityStatistics.normalQuantile(0.001)).isCloseTo(-3.090232, within(1e-6));
  }

  // ---- compare ----

  @Test
  void noEvidenceLeavesThePriorAndSaysSo() {
    final MatchResult r = compare(cluster(at(0, phone(1))), cluster(at(0, email(2))));

    assertThat(r.probability()).isCloseTo(0.05, within(1e-12));
    assertThat(r.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
    assertThat(r.evidence()).isEmpty();
  }

  @Test
  void aRareSharedIdentifierInTheSameWeekIsStrongEvidence() {
    final MatchResult r = compare(cluster(at(0, phone(1)), at(2, email(1))), cluster(at(3, phone(1))));

    assertThat(r.matchWeight()).isGreaterThan(8.0);
    assertThat(r.probability()).isGreaterThan(0.9);
    assertThat(r.lower()).isLessThan(r.probability());
    assertThat(r.upper()).isGreaterThan(r.probability());
    assertThat(r.verdict()).isIn(Verdict.SAME, Verdict.LIKELY_SAME);
    assertThat(r.shared()).isEqualTo(1);

    final Evidence e = r.evidence().getFirst();
    assertThat(e.type()).isEqualTo(EvidenceType.SHARED);
    assertThat(e.kind()).isEqualTo("Phone");
    assertThat(e.identifier()).isEqualTo(phone(1));
    assertThat(e.gapDays()).isCloseTo(3.0, within(1e-9));
  }

  @Test
  void compareIsSymmetric() {
    final ClusterProfile a = cluster(at(0, phone(1), email(1)), at(40, phone(2)));
    final ClusterProfile b = cluster(at(10, phone(1)), at(500, phone(3), email(9)), at(900, phone(2)));

    final MatchResult ab = model.compare(a, b, id -> 3);
    final MatchResult ba = model.compare(b, a, id -> 3);

    assertThat(ab.logOdds()).isCloseTo(ba.logOdds(), within(1e-12));
    assertThat(ab.stdDev()).isCloseTo(ba.stdDev(), within(1e-12));
  }

  @Test
  void sharedEvidenceDecaysWithTheGapBetweenSightings() {
    double previous = Double.MAX_VALUE;
    for (final int gapDays : new int[] { 0, 30, 365, 3 * 365 }) {
      final double weight = compare(cluster(at(0, phone(1))), cluster(at(gapDays, phone(1)))).matchWeight();
      assertThat(weight).isLessThan(previous);
      previous = weight;
    }
    // Long gaps are capped by the hand-off hazard, never turned into evidence against
    assertThat(previous).isGreaterThan(0.0);
  }

  @Test
  void halfLifeZeroMeansAKindNeverDecays() {
    model.kind("Phone", null, null, null, 0.0);

    final double now = compare(cluster(at(0, phone(1))), cluster(at(0, phone(1)))).matchWeight();
    final double later = compare(cluster(at(0, phone(1))), cluster(at(3 * 365, phone(1)))).matchWeight();

    assertThat(later).isCloseTo(now, within(1e-12));
  }

  @Test
  void timeBlindModelIgnoresGaps() {
    model.temporal(false);

    final double now = compare(cluster(at(0, phone(1))), cluster(at(0, phone(1)))).matchWeight();
    final double later = compare(cluster(at(0, phone(1))), cluster(at(3 * 365, phone(1)))).matchWeight();

    assertThat(later).isCloseTo(now, within(1e-12));
  }

  @Test
  void commonIdentifiersWeighLessThanRareOnesAndHubsWeighNothing() {
    final ClusterProfile a = cluster(at(0, phone(1)));
    final ClusterProfile b = cluster(at(0, phone(1)));

    final double rare = model.compare(a, b, id -> 2).matchWeight();
    final double common = model.compare(a, b, id -> 20_000).matchWeight();
    final MatchResult hub = model.compare(a, b, id -> 9_000_000);

    assertThat(common).isLessThan(rare);
    assertThat(hub.matchWeight()).isCloseTo(0.0, within(1e-12));
    // A hub shares the kind, so it still suppresses the disjoint-kind penalty and is listed with no weight
    assertThat(hub.evidence()).extracting(Evidence::type).containsOnly(EvidenceType.SHARED);
    assertThat(hub.evidence().getFirst().weight()).isZero();
    assertThat(hub.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void aFrequencyBelowWhatTheClustersShowIsRaisedToTheObservedCount() {
    final ClusterProfile a = cluster(at(0, phone(1)), at(0, phone(1)), at(0, phone(1)));
    final ClusterProfile b = cluster(at(0, phone(1)), at(0, phone(1)));

    assertThat(model.compare(a, b, id -> 1).matchWeight()).isCloseTo(model.compare(a, b, id -> 5).matchWeight(), within(1e-12));
  }

  @Test
  void disjointIdentifiersOfAKindBothUseAreEvidenceAgainst() {
    final MatchResult r = compare(cluster(at(0, phone(1)), at(1, phone(2))), cluster(at(0, phone(3))));

    assertThat(r.matchWeight()).isLessThan(0.0);
    assertThat(r.evidence()).singleElement().satisfies(e -> {
      assertThat(e.type()).isEqualTo(EvidenceType.DISJOINT);
      assertThat(e.kind()).isEqualTo("Phone");
      assertThat(e.countA()).isEqualTo(2);
      assertThat(e.countB()).isEqualTo(1);
    });
  }

  @Test
  void disjointEvidenceFadesWhenTheActivityWindowsAreFarApart() {
    final double concurrent = compare(cluster(at(0, phone(1))), cluster(at(0, phone(2)))).matchWeight();
    final double yearsApart = compare(cluster(at(0, phone(1))), cluster(at(4 * 365, phone(2)))).matchWeight();

    assertThat(concurrent).isLessThan(yearsApart);
    assertThat(yearsApart).isLessThan(0.0);
  }

  @Test
  void aSharedIdentifierOfAKindSuppressesTheDisjointPenaltyForThatKind() {
    final MatchResult r = compare(cluster(at(0, phone(1), phone(2))), cluster(at(0, phone(1), phone(3))));

    assertThat(r.evidence()).extracting(Evidence::type).containsOnly(EvidenceType.SHARED);
  }

  @Test
  void extraSharedIdentifiersOfOneKindAreDamped() {
    final double one = compare(cluster(at(0, phone(1))), cluster(at(0, phone(1)))).matchWeight();
    final double two = compare(cluster(at(0, phone(1), phone(2))), cluster(at(0, phone(1), phone(2)))).matchWeight();
    final double twoKinds = compare(cluster(at(0, phone(1), email(1))), cluster(at(0, phone(1), email(1)))).matchWeight();

    assertThat(two).isCloseTo(one * 1.5, within(1e-9)); // redundancy 0.5
    assertThat(twoKinds).isCloseTo(one * 2, within(1e-9));
  }

  @Test
  void impossibleTravelIsEvidenceAgainst() {
    // New York and Los Angeles, one hour apart: about 3,900 km/h
    final MatchResult far = compare(cluster(atPlace(0, 40.71, -74.00, phone(1))), cluster(atPlaceHours(1, 34.05, -118.24, email(1))));
    final MatchResult near = compare(cluster(atPlace(0, 40.71, -74.00, phone(1))), cluster(atPlaceHours(1, 40.73, -73.99, email(1))));

    assertThat(far.conflicts()).isEqualTo(1);
    assertThat(far.comparablePairs()).isEqualTo(1);
    assertThat(far.matchWeight()).isLessThan(0.0);
    assertThat(far.evidence()).extracting(Evidence::type).containsExactly(EvidenceType.CONFLICT);

    assertThat(near.conflicts()).isZero();
    assertThat(near.comparablePairs()).isEqualTo(1);
    assertThat(near.matchWeight()).isZero();
  }

  @Test
  void aWiderConfidenceLevelGivesAWiderInterval() {
    final ClusterProfile a = cluster(at(0, phone(1)));
    final ClusterProfile b = cluster(at(10, phone(1)));

    final MatchResult r95 = model.compare(a, b, id -> 2);
    final MatchResult r99 = model.confidence(0.99).compare(a, b, id -> 2);

    assertThat(r99.probability()).isCloseTo(r95.probability(), within(1e-12));
    assertThat(r99.lower()).isLessThan(r95.lower());
    assertThat(r99.upper()).isGreaterThan(r95.upper());
  }

  @Test
  void strongNegativeEvidenceSaysDifferent() {
    model.conflictLR(0.001);
    final MatchResult r = compare(cluster(atPlace(0, 40.71, -74.00, phone(1)), atPlace(0, 40.71, -74.00, email(1))),
        cluster(atPlaceHours(1, 34.05, -118.24, phone(2)), atPlaceHours(1, 34.05, -118.24, email(2))));

    assertThat(r.verdict()).isIn(Verdict.DIFFERENT, Verdict.LIKELY_DIFFERENT);
    assertThat(r.upper()).isLessThan(0.5);
  }

  @Test
  void refusesSettingsOutOfRange() {
    assertThatThrownBy(() -> model.prior(1.0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prior");
    assertThatThrownBy(() -> model.kind("Phone", 1.5, null, null, null)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("m");
    assertThatThrownBy(() -> model.confidence(0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confidence");
    assertThatThrownBy(() -> model.thresholds(0.2, 0.3)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonMatchThreshold");
  }

  // ---- soft evidence ----

  @Test
  void aNearDuplicateImageIsStrongEvidenceAndUnmatchedImagesMildlyAgainst() {
    final MatchResult same = compare(cluster(at(0, phone(1)).withImages(PHOTO)), cluster(at(0, email(1)).withImages(PHOTO ^ 3)));
    final Evidence image = same.evidence().getFirst();
    assertThat(image.type()).isEqualTo(EvidenceType.SOFT);
    assertThat(image.kind()).isEqualTo("image");
    assertThat(image.countA()).isEqualTo(1); // hash pairs within the distance
    assertThat(image.countB()).isEqualTo(1); // hash pairs compared
    assertThat(image.weight()).isGreaterThan(5.0);

    final MatchResult different = compare(cluster(at(0, phone(1)).withImages(PHOTO)), cluster(at(0, email(1)).withImages(OTHER)));
    assertThat(different.evidence()).singleElement().satisfies(e -> {
      assertThat(e.type()).isEqualTo(EvidenceType.SOFT);
      assertThat(e.weight()).isBetween(-2.0, 0.0);
    });
  }

  @Test
  void matchingStyleIsWeakEvidenceBecauseOneWriterPostsForSeveralPeople() {
    final double style = compare(cluster(at(0, phone(1)).withStyle(1, 0.1f, 0)), cluster(at(0, email(1)).withStyle(1, 0.12f, 0)))
        .matchWeight();
    final double image = compare(cluster(at(0, phone(1)).withImages(PHOTO)), cluster(at(0, email(1)).withImages(PHOTO))).matchWeight();

    assertThat(style).isGreaterThan(0.0);
    assertThat(style).isLessThan(3.0);
    assertThat(style).isLessThan(image);
  }

  @Test
  void softSignalsNeedDataOnBothSides() {
    final MatchResult r = compare(cluster(at(0, phone(1)).withImages(PHOTO).withStyle(1, 0, 0)), cluster(at(0, email(1))));
    assertThat(r.evidence()).isEmpty();
    assertThat(r.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void softSettingsChangeTheWeights() {
    final ClusterProfile a = cluster(at(0, phone(1)).withImages(PHOTO));
    final ClusterProfile b = cluster(at(0, email(1)).withImages(PHOTO ^ 1));
    final double base = model.compare(a, b, id -> 2).matchWeight();

    model.soft(IdentityMatchModel.SoftSignal.IMAGE, null, 0.9, null, null);
    assertThat(model.compare(a, b, id -> 2).matchWeight()).isGreaterThan(base);

    // A distance above maxDistance is no longer a match
    model.soft(IdentityMatchModel.SoftSignal.IMAGE, 0.0, null, null, null);
    assertThat(model.compare(a, b, id -> 2).matchWeight()).isLessThan(0.0);
  }

  @Test
  void imageMaxDistanceDefaultsByHashWidth() {
    // Two 256-bit PDQ hashes 20 bits apart: a near duplicate for PDQ (default 31), far for a 64-bit cut (8)
    final long[] pdq = { PHOTO, OTHER, PHOTO, OTHER };
    final long[] near = { PHOTO ^ 0xFFFFFL, OTHER, PHOTO, OTHER };
    final ClusterProfile a = cluster(new Member(T0, Double.NaN, Double.NaN, new RID[0], null, new long[][] { pdq }));
    final ClusterProfile b = cluster(new Member(T0, Double.NaN, Double.NaN, new RID[0], null, new long[][] { near }));

    final IdentityMatchModel.Features byWidth = model.features(a, b, id -> 2);
    assertThat(byWidth.imageMaxDistance()).isEqualTo(31);
    assertThat(byWidth.image().pairsWithin()).isEqualTo(1);
    assertThat(model.compare(a, b, id -> 2).matchWeight()).isGreaterThan(5.0);

    // 64-bit hashes default to 8: 3 bits apart agree, 9 do not
    assertThat(model.features(cluster(at(0).withImages(PHOTO)), cluster(at(0).withImages(PHOTO ^ 0x7L)), id -> 2).imageMaxDistance())
        .isEqualTo(8);
    assertThat(compare(cluster(at(0).withImages(PHOTO)), cluster(at(0).withImages(PHOTO ^ 0x1FFL))).matchWeight()).isLessThan(0.0);

    // An explicit maxDistance wins over the width
    model.soft(IdentityMatchModel.SoftSignal.IMAGE, 10.0, null, null, null);
    assertThat(model.features(a, b, id -> 2).imageMaxDistance()).isEqualTo(10);
    assertThat(model.compare(a, b, id -> 2).matchWeight()).isLessThan(0.0);
  }

  @Test
  void softEvidenceIsSymmetric() {
    final ClusterProfile a = cluster(at(0, phone(1)).withImages(PHOTO, OTHER).withStyle(1, 0, 0), at(3).withStyle(0.9f, 0.1f, 0));
    final ClusterProfile b = cluster(at(1, email(1)).withImages(PHOTO ^ 1).withStyle(1, 0.05f, 0));

    assertThat(model.compare(a, b, id -> 2).logOdds()).isCloseTo(model.compare(b, a, id -> 2).logOdds(), within(1e-12));
  }

  @Test
  void refusesSoftSettingsOutOfRange() {
    assertThatThrownBy(() -> model.soft(IdentityMatchModel.SoftSignal.STYLE, 1.5, null, null, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("style.threshold");
    assertThatThrownBy(() -> model.soft(IdentityMatchModel.SoftSignal.IMAGE, -1.0, null, null, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image.maxDistance");
    assertThatThrownBy(() -> model.soft(IdentityMatchModel.SoftSignal.IMAGE, null, null, 1.0, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image.falseMatchRate");
  }

  // ---- leave one out ----

  @Test
  void leaveOneOutViewMatchesARebuiltProfile() {
    final Random random = new Random(42);
    for (int round = 0; round < 30; round++) {
      final List<Member> members = new ArrayList<>();
      final int size = 2 + random.nextInt(12);
      for (int i = 0; i < size; i++) {
        final List<RID> ids = new ArrayList<>();
        final int count = random.nextInt(4);
        for (int k = 0; k < count; k++)
          ids.add(random.nextBoolean() ? phone(random.nextInt(6)) : email(random.nextInt(4)));
        final boolean timed = random.nextInt(5) > 0;
        final boolean placed = random.nextBoolean();
        Member member = new Member(timed ? T0 + random.nextInt(2000) * DAY / 10 : ClusterProfile.NO_TIME,
            placed ? 30 + random.nextDouble() * 15 : Double.NaN, placed ? -120 + random.nextDouble() * 50 : Double.NaN,
            ids.toArray(new RID[0]));
        if (random.nextBoolean())
          member = member.withStyle(random.nextFloat(), random.nextFloat(), random.nextFloat());
        final long[] hashes = new long[random.nextInt(3)];
        for (int h = 0; h < hashes.length; h++)
          // A small pool, one bit apart or far apart, so near duplicates happen
          hashes[h] = IMAGE_POOL[random.nextInt(IMAGE_POOL.length)];
        members.add(member.withImages(hashes));
      }
      final ClusterProfile full = build(members);

      for (int j = 0; j < size; j++) {
        final List<Member> rest = new ArrayList<>(members);
        final Member alone = rest.remove(j);
        final MatchResult viaView = model.compare(build(List.of(alone)), full.without(j), id -> 4);
        final MatchResult rebuilt = model.compare(build(List.of(alone)), build(rest), id -> 4);

        assertThat(viaView.logOdds()).as("round %d member %d", round, j).isCloseTo(rebuilt.logOdds(), within(1e-9));
        assertThat(viaView.stdDev()).isCloseTo(rebuilt.stdDev(), within(1e-9));
        assertThat(viaView.comparablePairs()).isEqualTo(rebuilt.comparablePairs());
        assertThat(viaView.firstB()).isEqualTo(rebuilt.firstB());
        assertThat(viaView.evidence()).extracting(Evidence::type).containsExactlyInAnyOrderElementsOf(
            rebuilt.evidence().stream().map(Evidence::type).toList());
        assertThat(viaView.lastB()).isEqualTo(rebuilt.lastB());
      }
    }
  }

  // ---- helpers ----

  private record Member(long time, double lat, double lon, RID[] ids, float[] style, long[][] images) {
    Member(final long time, final double lat, final double lon, final RID[] ids) {
      this(time, lat, lon, ids, null, new long[0][]);
    }

    Member withStyle(final float... style) {
      return new Member(time, lat, lon, ids, style, images);
    }

    Member withImages(final long... hashes) {
      final long[][] images = new long[hashes.length][];
      for (int i = 0; i < hashes.length; i++)
        images[i] = new long[] { hashes[i] };
      return new Member(time, lat, lon, ids, style, images);
    }
  }

  private MatchResult compare(final ClusterProfile a, final ClusterProfile b) {
    return model.compare(a, b, id -> 2);
  }

  private static RID phone(final int n) {
    return new RID(10, n);
  }

  private static RID email(final int n) {
    return new RID(11, n);
  }

  private static Member at(final int day, final RID... ids) {
    return new Member(T0 + day * DAY, Double.NaN, Double.NaN, ids);
  }

  private static Member atPlace(final int day, final double lat, final double lon, final RID... ids) {
    return new Member(T0 + day * DAY, lat, lon, ids);
  }

  private static Member atPlaceHours(final int hours, final double lat, final double lon, final RID... ids) {
    return new Member(T0 + hours * 3_600_000L, lat, lon, ids);
  }

  private ClusterProfile cluster(final Member... members) {
    return build(List.of(members));
  }

  private ClusterProfile build(final List<Member> members) {
    final ClusterProfile.Builder builder = new ClusterProfile.Builder();
    for (final Member m : members) {
      final int[] kinds = new int[m.ids().length];
      for (int i = 0; i < kinds.length; i++)
        kinds[i] = m.ids()[i].getBucketId() == 10 ? phoneKind : emailKind;
      builder.addMember(m.time(), m.lat(), m.lon(), m.ids(), kinds, m.style(), m.images());
    }
    return builder.build();
  }
}
