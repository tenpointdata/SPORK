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
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.similarity.SoftSimilarity;
import com.arcadedb.similarity.SoftSimilarity.ImageStats;
import com.arcadedb.similarity.SoftSimilarity.StyleStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.arcadedb.query.opencypher.procedures.identity.ClusterProfile.NO_TIME;

/**
 * Scores whether two clusters of records belong to the same individual (docs/spork/04-identity-resolution-design.md).
 * <p>
 * A Fellegi-Sunter model with term-frequency adjustment and time: every piece of evidence is a likelihood ratio
 * P(evidence | same) / P(evidence | different), and their logs add up to the match weight. Three kinds of evidence:
 * </p>
 * <ul>
 *   <li><b>SHARED</b>: both clusters reach identifier i of kind k. P(same) = m_k. P(different) = u_i + h_k(gap): u_i is the
 *   chance the larger cluster reaches i by coincidence, from i's frequency in the population, and h_k(gap) is the chance a
 *   different person carries i, rising from {@code concurrent} (same moment: agency phones) to {@code handoff} (long gaps:
 *   recycled numbers, sold accounts) with the kind's half-life, where gap is the shortest time between a sighting of i in one
 *   cluster and in the other. Extra shared identifiers of one kind are damped by {@code redundancy}, since they are
 *   correlated.</li>
 *   <li><b>DISJOINT</b>: both clusters use kind k and share none of its identifiers. LR = 1 - m_k * rho_k(gap), rho_k the
 *   share of the identity binding left after the gap between the two clusters' windows of use of k: concurrent disjoint
 *   phones say "different", phones years apart say little.</li>
 *   <li><b>CONFLICT</b>: impossible travel. Of the pairs of members one per cluster within {@code conflictWindowHours} of each
 *   other, the fraction f that sits more than {@code minConflictKm} apart at more than {@code maxSpeedKmh}; weight
 *   f * ln(conflictLR).</li>
 *   <li><b>SOFT</b> (slice 3): the style vectors and image hashes of the two clusters. Agreement is a style pair at or above
 *   the cosine {@code threshold}, or an image pair within {@code maxDistance} bits. P(agree | same) = m;
 *   P(agree | different) = q = c + (1 - c) * (1 - (1 - falseMatchRate)^pairs), c the {@code concurrent} hazard (stolen and
 *   stock photos; for style, an operator writing for several people). Agreement weighs ln(m / q), disagreement
 *   ln((1 - m) / (1 - q)), each clamped to its own sign. The raw pair statistics come from {@link SoftSimilarity}; all four
 *   numbers per signal are for the MLOps pipeline to fit.</li>
 * </ul>
 * <p>
 * The interval: m_k is Beta(m * mStrength, (1 - m) * mStrength), the population rate of i is the Jeffreys posterior of its
 * count, the conflict fraction is the Jeffreys posterior of its pairs. Each weight's variance follows by the delta method
 * (exactly, through trigamma, for the log of a Beta variable); the terms are summed as independent on the log-odds scale
 * and mapped through the logistic function. So the interval is wide when the evidence rests on few observations and narrow
 * when it rests on many, and it is never symmetric in probability.
 * </p>
 * <p>
 * One instance per call: {@link #kindIndex} registers kinds as profiles are built. Not thread safe.
 * </p>
 */
public final class IdentityMatchModel {
  public enum Verdict {SAME, LIKELY_SAME, UNDETERMINED, LIKELY_DIFFERENT, DIFFERENT, INSUFFICIENT_EVIDENCE}

  public enum EvidenceType {SHARED, DISJOINT, CONFLICT, SOFT}

  /** The soft signals: writing style and image near-duplicates. */
  public enum SoftSignal {
    STYLE, IMAGE;

    public String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The number of records in the population that carry an identifier; a negative answer means unknown. */
  @FunctionalInterface
  public interface FrequencyLookup {
    long frequency(RID identifier);
  }

  /**
   * One piece of evidence. {@code weight} and {@code stdDev} are in bits (log2 of the likelihood ratio), after damping.
   * For SHARED, {@code countA}/{@code countB} are the members reaching the identifier; for DISJOINT, the members using the
   * kind; for CONFLICT, the conflicting and the comparable pairs. {@code gapDays} is NaN when unknown.
   */
  public record Evidence(EvidenceType type, String kind, RID identifier, int countA, int countB, long frequency, double gapDays,
                         double weight, double stdDev) {
  }

  /**
   * @param logOdds     prior log-odds plus every weight, natural log
   * @param stdDev      standard deviation of logOdds
   * @param matchWeight sum of the weights in bits, without the prior
   */
  public record MatchResult(int sizeA, int sizeB, double logOdds, double stdDev, double probability, double lower, double upper,
                            double matchWeight, Verdict verdict, List<Evidence> evidence, int shared, long firstA, long lastA,
                            long firstB, long lastB, int comparablePairs, int conflicts, boolean pairsTruncated) {
  }

  private static final double LN2    = Math.log(2);
  private static final double DAY_MS = 86_400_000.0;

  private static final class Kind {
    final String name;
    Double m, concurrent, handoff, halfLifeDays;

    Kind(final String name) {
      this.name = name;
    }
  }

  private record Term(EvidenceType type, int kind, String label, RID identifier, int countA, int countB, long frequency,
                      double gapDays, double weight, double variance) {
  }

  /**
   * One identifier both clusters reach, as {@link #features} found it: the counts, the frequency the score uses (the
   * reported one, raised to what the two clusters show), and the shortest gap between sightings in ms (-1 = unknown).
   */
  public record SharedFeature(int kind, RID identifier, int countA, int countB, long frequency, long gap) {
  }

  /** A kind both clusters use with no identifier in common: members using it, and the gap between the windows (-1 = unknown). */
  public record DisjointFeature(int kind, int countA, int countB, long gap) {
  }

  /**
   * The raw terms of one pair, before any parameter is applied: what {@code identity.features} exports for training and
   * what {@link #score} turns into weights. {@code population} is the one the score uses (at least the two sizes).
   */
  public record Features(int sizeA, int sizeB, long population, boolean temporal, List<SharedFeature> shared,
                         List<DisjointFeature> disjoint, int comparablePairs, int conflicts, boolean pairsTruncated, long firstA,
                         long lastA, long firstB, long lastB, StyleStats style, ImageStats image, int imageMaxDistance) {
  }

  private final List<Kind>           kinds     = new ArrayList<>();
  private final Map<String, Integer> kindByName = new HashMap<>();

  private double  prior                = 0.05;
  private long    population;
  private double  defaultM             = 0.6;
  private double  defaultConcurrent    = 0.001;
  private double  defaultHandoff       = 0.05;
  private double  defaultHalfLifeDays  = 365;
  private double  mStrength            = 20;
  private double  redundancy           = 0.5;
  private double  maxSpeedKmh          = 900;
  private double  conflictWindowHours  = 24;
  private double  minConflictKm        = 100;
  private double  conflictLR           = 0.2;
  private double  confidence           = 0.95;
  private double  matchThreshold       = 0.9;
  private double  nonMatchThreshold    = 0.1;
  private int     maxPairs             = 100_000;
  private boolean temporal             = true;
  // Per soft signal (SoftSignal ordinal): threshold (cosine for style, max Hamming distance for images; NaN = by hash
  // width), m, falseMatchRate per pair, concurrent hazard
  private final double[][] soft = { { 0.9, 0.3, 0.01, 0.05 }, { Double.NaN, 0.4, 0.00001, 0.002 } };

  // ---- settings ----

  /** The prior probability that two compared clusters are one individual: the base rate of the candidate pairs. */
  public IdentityMatchModel prior(final double prior) {
    this.prior = openUnit("prior", prior);
    return this;
  }

  /** The number of records the identifier frequencies are counted over; at least the two clusters together. */
  public IdentityMatchModel population(final long population) {
    if (population < 0)
      throw new IllegalArgumentException("population must not be negative, got " + population);
    this.population = population;
    return this;
  }

  public IdentityMatchModel defaults(final Double m, final Double concurrent, final Double handoff, final Double halfLifeDays) {
    if (m != null)
      defaultM = openUnit("defaultM", m);
    if (concurrent != null)
      defaultConcurrent = halfOpenUnit("defaultConcurrent", concurrent);
    if (handoff != null)
      defaultHandoff = halfOpenUnit("defaultHandoff", handoff);
    if (halfLifeDays != null)
      defaultHalfLifeDays = nonNegative("defaultHalfLifeDays", halfLifeDays);
    return this;
  }

  /**
   * Settings for one identifier kind; a null keeps the default. {@code halfLifeDays} 0 means the kind never decays.
   *
   * @param m            P(the clusters share an identifier of the kind | same individual, both use the kind)
   * @param concurrent   P(a different individual carries the identifier at the same time)
   * @param handoff      P(a different individual carries the identifier after a long gap)
   * @param halfLifeDays time after which half of the identifier's binding to its owner is gone
   */
  public IdentityMatchModel kind(final String name, final Double m, final Double concurrent, final Double handoff,
      final Double halfLifeDays) {
    final Kind kind = kinds.get(kindIndex(name));
    if (m != null)
      kind.m = openUnit(name + ".m", m);
    if (concurrent != null)
      kind.concurrent = halfOpenUnit(name + ".concurrent", concurrent);
    if (handoff != null)
      kind.handoff = halfOpenUnit(name + ".handoff", handoff);
    if (halfLifeDays != null)
      kind.halfLifeDays = nonNegative(name + ".halfLifeDays", halfLifeDays);
    return this;
  }

  /** Pseudo-count behind m: how many labelled pairs the m values are worth. */
  public IdentityMatchModel mStrength(final double mStrength) {
    if (!(mStrength > 0) || Double.isInfinite(mStrength))
      throw new IllegalArgumentException("mStrength must be a positive number, got " + mStrength);
    this.mStrength = mStrength;
    return this;
  }

  /** Factor applied to each further shared identifier of one kind (1 = independent, 0 = only the strongest counts). */
  public IdentityMatchModel redundancy(final double redundancy) {
    if (!(redundancy >= 0 && redundancy <= 1))
      throw new IllegalArgumentException("redundancy must be in [0, 1], got " + redundancy);
    this.redundancy = redundancy;
    return this;
  }

  public IdentityMatchModel travel(final Double maxSpeedKmh, final Double conflictWindowHours, final Double minConflictKm) {
    if (maxSpeedKmh != null)
      this.maxSpeedKmh = positive("maxSpeedKmh", maxSpeedKmh);
    if (conflictWindowHours != null)
      this.conflictWindowHours = nonNegative("conflictWindowHours", conflictWindowHours);
    if (minConflictKm != null)
      this.minConflictKm = nonNegative("minConflictKm", minConflictKm);
    return this;
  }

  /** Likelihood ratio of a cluster pair whose comparable pairs all conflict. */
  public IdentityMatchModel conflictLR(final double conflictLR) {
    this.conflictLR = openUnit("conflictLR", conflictLR);
    return this;
  }

  public IdentityMatchModel confidence(final double confidence) {
    this.confidence = openUnit("confidence", confidence);
    return this;
  }

  /** The verdict thresholds: SAME needs the interval above matchThreshold, DIFFERENT below nonMatchThreshold. */
  public IdentityMatchModel thresholds(final double matchThreshold, final double nonMatchThreshold) {
    openUnit("matchThreshold", matchThreshold);
    openUnit("nonMatchThreshold", nonMatchThreshold);
    if (nonMatchThreshold >= matchThreshold)
      throw new IllegalArgumentException(
          "nonMatchThreshold (" + nonMatchThreshold + ") must be below matchThreshold (" + matchThreshold + ")");
    this.matchThreshold = matchThreshold;
    this.nonMatchThreshold = nonMatchThreshold;
    return this;
  }

  /** Bound on the member pairs the impossible-travel sweep compares. */
  public IdentityMatchModel maxPairs(final int maxPairs) {
    if (maxPairs < 0)
      throw new IllegalArgumentException("maxPairs must not be negative, got " + maxPairs);
    this.maxPairs = maxPairs;
    return this;
  }

  /**
   * false makes the model time-blind: every gap counts as concurrent. With time on (the default), an unknown gap counts as
   * a long one, so evidence that needs a time to be strong is not.
   */
  public IdentityMatchModel temporal(final boolean temporal) {
    this.temporal = temporal;
    return this;
  }

  /**
   * Settings of a soft signal; a null keeps the current value.
   *
   * @param threshold      style: the cosine at or above which two members agree, in [-1, 1]; image: the largest Hamming
   *                       distance at which two hashes agree, a whole number of bits; unset, it follows the hash width
   *                       ({@link #defaultMaxDistance}), since one fixed cut cannot fit both 64-bit hashes and PDQ
   * @param m              P(the clusters agree on the signal | same individual, both carry it)
   * @param falseMatchRate P(one pair of members of different individuals agrees by chance)
   * @param concurrent     P(different individuals agree for a reason: stolen or stock photos, one operator writing)
   */
  public IdentityMatchModel soft(final SoftSignal signal, final Double threshold, final Double m, final Double falseMatchRate,
      final Double concurrent) {
    final double[] s = soft[signal.ordinal()];
    final String name = signal.label();
    if (threshold != null) {
      if (signal == SoftSignal.STYLE && !(threshold >= -1 && threshold <= 1))
        throw new IllegalArgumentException(name + ".threshold must be a cosine in [-1, 1], got " + threshold);
      if (signal == SoftSignal.IMAGE && !(threshold >= 0 && threshold == Math.rint(threshold) && threshold <= 256))
        throw new IllegalArgumentException(name + ".maxDistance must be a whole number of bits in [0, 256], got " + threshold);
      s[0] = threshold;
    }
    if (m != null)
      s[1] = openUnit(name + ".m", m);
    if (falseMatchRate != null)
      s[2] = halfOpenUnit(name + ".falseMatchRate", falseMatchRate);
    if (concurrent != null)
      s[3] = halfOpenUnit(name + ".concurrent", concurrent);
    return this;
  }

  /** The agreement threshold of a soft signal: a cosine for style, a number of bits for images (NaN = by hash width). */
  public double softThreshold(final SoftSignal signal) {
    return soft[signal.ordinal()][0];
  }

  /**
   * The image distance used when none is set, by hash width: 8 bits for a 64-bit hash, 31 for a 256-bit PDQ hash (its
   * reference near-duplicate cut), and about 12% of the bits otherwise. A 64-bit cut on PDQ would drop most near
   * duplicates without a sign, so the width decides.
   */
  public static int defaultMaxDistance(final int words) {
    return switch (words) {
      case 1 -> 8;
      case 4 -> 31;
      default -> (int) Math.round(words * 64 * 0.12);
    };
  }

  /** The index of a kind, registering it with the default settings the first time. */
  public int kindIndex(final String name) {
    final Integer index = kindByName.get(name);
    if (index != null)
      return index;
    kinds.add(new Kind(name));
    kindByName.put(name, kinds.size() - 1);
    return kinds.size() - 1;
  }

  // ---- scoring ----

  public MatchResult compare(final ClusterProfile a, final ClusterProfile b, final FrequencyLookup frequencies) {
    return score(features(a, b, frequencies));
  }

  /** The kind name of a kind index. */
  public String kindName(final int kind) {
    return kinds.get(kind).name;
  }

  /**
   * The raw terms of the pair: shared identifiers, kinds used on both sides with nothing shared, and the travel sweep. No
   * parameter of the model is read here except the travel bounds, which define what a conflict is.
   */
  public Features features(final ClusterProfile a, final ClusterProfile b, final FrequencyLookup frequencies) {
    final int sizeA = a.size();
    final int sizeB = b.size();
    final long population = Math.max(this.population, (long) sizeA + sizeB);
    final List<SharedFeature> shared = new ArrayList<>();
    final List<DisjointFeature> disjoint = new ArrayList<>();
    int comparablePairs = 0;
    int conflicts = 0;
    boolean truncated = false;

    if (sizeA > 0 && sizeB > 0) {
      final int kindCount = kinds.size();
      final boolean[] kindShared = new boolean[kindCount];
      final ClusterProfile iterated = a.identifierCount() <= b.identifierCount() ? a : b;
      for (final RID id : iterated.identifierIds()) {
        final int countA = a.count(id);
        if (countA == 0)
          continue;
        final int countB = b.count(id);
        if (countB == 0)
          continue;
        final int kind = iterated.kind(id);
        kindShared[kind] = true;
        final long observed = (long) countA + countB;
        final long reported = frequencies == null ? -1 : frequencies.frequency(id);
        shared.add(new SharedFeature(kind, id, countA, countB, Math.max(reported, observed),
            minGap(a.times(id), a.skipIndex(id), b.times(id), b.skipIndex(id))));
      }
      // RID order, so the features do not depend on which side was iterated
      shared.sort((x, y) -> x.identifier().compareTo(y.identifier()));

      for (int k = 0; k < kindCount; k++)
        if (!kindShared[k] && a.kindMembers(k) > 0 && b.kindMembers(k) > 0)
          disjoint.add(new DisjointFeature(k, a.kindMembers(k), b.kindMembers(k),
              windowGap(a.kindFirst(k), a.kindLast(k), b.kindFirst(k), b.kindLast(k))));

      // Impossible travel
      if (a.pointCount() > 0 && b.pointCount() > 0) {
        final long window = (long) (conflictWindowHours * 3_600_000.0);
        final int skipA = a.skipPoint();
        final int skipB = b.skipPoint();
        int start = 0;
        sweep:
        for (int i = 0; i < a.pointCount(); i++) {
          if (i == skipA)
            continue;
          final long t = a.pointTime(i);
          while (start < b.pointCount() && b.pointTime(start) < t - window)
            start++;
          for (int j = start; j < b.pointCount() && b.pointTime(j) <= t + window; j++) {
            if (j == skipB)
              continue;
            if (comparablePairs == maxPairs) {
              truncated = true;
              break sweep;
            }
            comparablePairs++;
            final double km = GeoUtils.haversineRadians(a.pointLon(i), a.pointLat(i), b.pointLon(j), b.pointLat(j))
                * GeoUtils.EARTH_RADIUS_KM;
            if (km <= minConflictKm)
              continue;
            final double hours = Math.abs(b.pointTime(j) - t) / 3_600_000.0;
            if (hours == 0 || km / hours > maxSpeedKmh)
              conflicts++;
          }
        }
      }
    }
    StyleStats style = null;
    ImageStats image = null;
    int imageMaxDistance = -1;
    if (sizeA > 0 && sizeB > 0) {
      if (a.hasStyle() && b.hasStyle())
        style = SoftSimilarity.style(a.styles(), a.skipMember(), b.styles(), b.skipMember(), softThreshold(SoftSignal.STYLE));
      if (a.hasImages() && b.hasImages()) {
        final double set = softThreshold(SoftSignal.IMAGE);
        imageMaxDistance = Double.isNaN(set) ? defaultMaxDistance(a.imageWords()) : (int) set;
        image = SoftSimilarity.images(a.images(), a.skipMember(), b.images(), b.skipMember(), imageMaxDistance);
      }
    }
    return new Features(sizeA, sizeB, population, temporal, shared, disjoint, comparablePairs, conflicts, truncated, a.first(),
        a.last(), b.first(), b.last(), style, image, imageMaxDistance);
  }

  /** Turns the raw terms of a pair into weights, their variances, the posterior, its interval and the verdict. */
  public MatchResult score(final Features f) {
    final List<Term> terms = new ArrayList<>();

    @SuppressWarnings("unchecked")
    final List<Term>[] sharedByKind = new List[kinds.size()];
    for (final SharedFeature s : f.shared()) {
      if (sharedByKind[s.kind()] == null)
        sharedByKind[s.kind()] = new ArrayList<>();
      sharedByKind[s.kind()].add(sharedTerm(f, s));
    }
    for (final List<Term> kindTerms : sharedByKind) {
      if (kindTerms == null)
        continue;
      // Strongest first, RID order between equals
      kindTerms.sort(Comparator.comparingDouble(Term::weight).reversed().thenComparing(Term::identifier));
      double damping = 1;
      for (final Term t : kindTerms) {
        terms.add(new Term(t.type, t.kind, t.label, t.identifier, t.countA, t.countB, t.frequency, t.gapDays, t.weight * damping,
            t.variance * damping * damping));
        damping *= redundancy;
      }
    }
    for (final DisjointFeature d : f.disjoint()) {
      final Term t = disjointTerm(f, d);
      if (t != null)
        terms.add(t);
    }
    if (f.conflicts() > 0) {
      final double lnLR = Math.log(conflictLR);
      final double fraction = (double) f.conflicts() / f.comparablePairs();
      final double variance = lnLR * lnLR * IdentityStatistics.betaVariance(f.conflicts() + 0.5,
          f.comparablePairs() - f.conflicts() + 0.5);
      terms.add(new Term(EvidenceType.CONFLICT, -1, null, null, f.conflicts(), f.comparablePairs(), -1, Double.NaN, fraction * lnLR,
          variance));
    }

    if (f.style() != null && f.style().pairs() > 0)
      addSoftTerm(terms, SoftSignal.STYLE, f.style().pairs(), f.style().pairsAbove());
    if (f.image() != null && f.image().pairs() > 0)
      addSoftTerm(terms, SoftSignal.IMAGE, f.image().pairs(), f.image().pairsWithin());

    double weight = 0;
    double variance = 0;
    boolean informative = false;
    for (final Term t : terms) {
      weight += t.weight;
      variance += t.variance;
      if (t.weight != 0)
        informative = true;
    }

    final double logOdds = IdentityStatistics.logit(prior) + weight;
    final double stdDev = Math.sqrt(variance);
    final double z = IdentityStatistics.normalQuantile(0.5 + confidence / 2);
    final double probability = IdentityStatistics.sigmoid(logOdds);
    final double lower = IdentityStatistics.sigmoid(logOdds - z * stdDev);
    final double upper = IdentityStatistics.sigmoid(logOdds + z * stdDev);

    final Verdict verdict;
    if (!informative)
      verdict = Verdict.INSUFFICIENT_EVIDENCE;
    else if (lower >= matchThreshold)
      verdict = Verdict.SAME;
    else if (lower >= 0.5)
      verdict = Verdict.LIKELY_SAME;
    else if (weight < 0 && upper <= nonMatchThreshold)
      verdict = Verdict.DIFFERENT;
    else if (weight < 0 && upper <= 0.5)
      verdict = Verdict.LIKELY_DIFFERENT;
    else
      verdict = Verdict.UNDETERMINED;

    final List<Evidence> evidence = new ArrayList<>(terms.size());
    for (final Term t : terms)
      evidence.add(new Evidence(t.type, t.label != null ? t.label : t.kind < 0 ? null : kinds.get(t.kind).name, t.identifier, t.countA, t.countB, t.frequency,
          t.gapDays, t.weight / LN2, Math.sqrt(t.variance) / LN2));
    evidence.sort(Comparator.comparingDouble((Evidence e) -> -Math.abs(e.weight())).thenComparing(Evidence::type)
        .thenComparing(e -> e.identifier() == null ? "" : e.identifier().toString()));

    return new MatchResult(f.sizeA(), f.sizeB(), logOdds, stdDev, probability, lower, upper, weight / LN2, verdict, evidence,
        f.shared().size(), f.firstA(), f.lastA(), f.firstB(), f.lastB(), f.comparablePairs(), f.conflicts(), f.pairsTruncated());
  }

  /** A SOFT term from the pairs compared and the pairs that agree; no term when the evidence points neither way. */
  private void addSoftTerm(final List<Term> terms, final SoftSignal signal, final long pairs, final long agreeing) {
    final double[] s = soft[signal.ordinal()];
    final double m = s[1];
    final double coincidence = -Math.expm1(pairs * Math.log1p(-s[2]));
    // Floor q: with no false matches and no hazard configured, an agreement would otherwise weigh infinitely
    final double q = Math.max(1e-12, coincidence + s[3] - coincidence * s[3]);
    final double weight;
    final double variance;
    if (agreeing > 0) {
      weight = Math.log(m) - Math.log(q);
      variance = IdentityStatistics.trigamma(m * mStrength) - IdentityStatistics.trigamma(mStrength);
    } else {
      weight = Math.log1p(-m) - Math.log1p(-q);
      variance = IdentityStatistics.trigamma((1 - m) * mStrength) - IdentityStatistics.trigamma(mStrength);
    }
    // Agreement is never evidence against, nor disagreement evidence for
    if (agreeing > 0 ? weight <= 0 : weight >= 0)
      return;
    terms.add(new Term(EvidenceType.SOFT, -1, signal.label(), null, (int) Math.min(agreeing, Integer.MAX_VALUE),
        (int) Math.min(pairs, Integer.MAX_VALUE), -1, Double.NaN, weight, variance));
  }

  private Term sharedTerm(final Features f, final SharedFeature s) {
    final Kind kind = kinds.get(s.kind());
    final int sizeA = f.sizeA();
    final int sizeB = f.sizeB();

    // Condition on the smaller cluster carrying i and ask how likely the larger is to reach it by coincidence: the larger
    // cluster has more chances, so this is the conservative side. Equal sizes take the smaller count, also conservative.
    final int sizeSmall = Math.min(sizeA, sizeB);
    final int sizeLarge = Math.max(sizeA, sizeB);
    final int countSmall = sizeA < sizeB ? s.countA() : sizeB < sizeA ? s.countB() : Math.min(s.countA(), s.countB());

    final double successes = s.frequency() - countSmall;
    final double trials = f.population() - sizeSmall;
    final double alphaP = successes + 0.5;
    final double betaP = Math.max(trials - successes, 0) + 0.5;
    final double p = alphaP / (alphaP + betaP);
    final double log1mP = Math.log1p(-p);
    final double u = -Math.expm1(sizeLarge * log1mP);
    final double varLnP = IdentityStatistics.trigamma(alphaP) - IdentityStatistics.trigamma(alphaP + betaP);
    final double gU = sizeLarge * p * Math.exp((sizeLarge - 1) * log1mP) / u;
    final double varLnU = gU * gU * varLnP;

    final double rho = retention(f.temporal(), kind, s.gap());
    final double hazard = concurrent(kind) * rho + handoff(kind) * (1 - rho);

    final double m = m(kind);
    final double alphaM = m * mStrength;
    final double varLnM = IdentityStatistics.trigamma(alphaM) - IdentityStatistics.trigamma(mStrength);

    final double denominator = u + hazard;
    double weight = Math.log(m) - Math.log(denominator);
    final double gDen = u / denominator;
    double variance = varLnM + gDen * gDen * varLnU;
    // A shared identifier so common that sharing it is no surprise is no evidence, never evidence against
    if (weight <= 0) {
      weight = 0;
      variance = 0;
    }
    return new Term(EvidenceType.SHARED, s.kind(), null, s.identifier(), s.countA(), s.countB(), s.frequency(),
        s.gap() < 0 ? Double.NaN : s.gap() / DAY_MS, weight, variance);
  }

  private Term disjointTerm(final Features f, final DisjointFeature d) {
    final Kind kind = kinds.get(d.kind());
    final double rho = retention(f.temporal(), kind, d.gap());
    if (rho == 0)
      return null;
    final double m = m(kind);
    final double share = m * rho;
    final double derivative = rho / (1 - share);
    final double variance = derivative * derivative * IdentityStatistics.betaVariance(m * mStrength, (1 - m) * mStrength);
    return new Term(EvidenceType.DISJOINT, d.kind(), null, null, d.countA(), d.countB(), -1, d.gap() < 0 ? Double.NaN : d.gap() / DAY_MS,
        Math.log1p(-share), variance);
  }

  /** The share of an identifier's binding to its owner left after {@code gap} milliseconds (-1 = unknown). */
  private double retention(final boolean temporal, final Kind kind, final long gap) {
    if (!temporal)
      return 1;
    if (gap < 0)
      return 0;
    final double halfLife = halfLifeDays(kind);
    if (halfLife == 0)
      return 1;
    return Math.pow(2, -(gap / DAY_MS) / halfLife);
  }

  /** The smallest distance between a time of {@code a} and a time of {@code b}, each skipping one index; -1 when unknown. */
  static long minGap(final long[] a, final int skipA, final long[] b, final int skipB) {
    int i = skipA == 0 ? 1 : 0;
    int j = skipB == 0 ? 1 : 0;
    long best = -1;
    while (i < a.length && j < b.length) {
      final long gap = Math.abs(a[i] - b[j]);
      if (best < 0 || gap < best)
        best = gap;
      if (a[i] < b[j]) {
        i++;
        if (i == skipA)
          i++;
      } else {
        j++;
        if (j == skipB)
          j++;
      }
    }
    return best;
  }

  /** The time between two windows, 0 when they overlap, -1 when either is unknown. */
  static long windowGap(final long firstA, final long lastA, final long firstB, final long lastB) {
    if (firstA == NO_TIME || firstB == NO_TIME)
      return -1;
    if (lastA < firstB)
      return firstB - lastA;
    if (lastB < firstA)
      return firstA - lastB;
    return 0;
  }

  private double m(final Kind kind) {
    return kind.m != null ? kind.m : defaultM;
  }

  private double concurrent(final Kind kind) {
    return kind.concurrent != null ? kind.concurrent : defaultConcurrent;
  }

  private double handoff(final Kind kind) {
    return kind.handoff != null ? kind.handoff : defaultHandoff;
  }

  private double halfLifeDays(final Kind kind) {
    return kind.halfLifeDays != null ? kind.halfLifeDays : defaultHalfLifeDays;
  }

  private static double openUnit(final String name, final double value) {
    if (!(value > 0 && value < 1))
      throw new IllegalArgumentException(name + " must be between 0 and 1 (exclusive), got " + value);
    return value;
  }

  private static double halfOpenUnit(final String name, final double value) {
    if (!(value >= 0 && value < 1))
      throw new IllegalArgumentException(name + " must be in [0, 1), got " + value);
    return value;
  }

  private static double nonNegative(final String name, final double value) {
    if (!(value >= 0) || Double.isInfinite(value))
      throw new IllegalArgumentException(name + " must be a non-negative number, got " + value);
    return value;
  }

  private static double positive(final String name, final double value) {
    if (!(value > 0) || Double.isInfinite(value))
      throw new IllegalArgumentException(name + " must be a positive number, got " + value);
    return value;
  }
}
