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
import com.arcadedb.similarity.SoftSimilarity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the identity match model knows about one cluster: for every member its time and place, for every identifier the
 * members reach which of them reach it, how many and when, and for every identifier kind when the cluster used it.
 * <p>
 * Times are epoch milliseconds, {@link #NO_TIME} when unknown; places are degrees, NaN when unknown. Every per-identifier
 * and per-kind time list is sorted, so the model reads gaps and windows with merges rather than pairwise loops.
 * </p>
 * <p>
 * For the soft signals (slice 3) it also carries each member's style vector (null when none) and image hashes (one
 * {@code long[]} per image), read by {@link SoftSimilarity} with the excluded member as skip index.
 * </p>
 * <p>
 * {@link #without(int)} answers the same questions for the cluster minus one member without copying anything: every
 * accessor takes the shared arrays and skips one entry. That is what makes {@code identity.cohesion} linear in the size of
 * the cluster per member rather than a rebuild of the rest each time.
 * </p>
 */
public final class ClusterProfile {
  public static final long NO_TIME = Long.MIN_VALUE;

  private static final long[] NO_TIMES = new long[0];
  private static final int[]  NO_KINDS = new int[0];
  private static final int[]  NO_MEMBERS = new int[0];
  private static final long[][] NO_IMAGES = new long[0][];

  static final class Identifier {
    final int    kind;
    int    count;
    long[] times = NO_TIMES;
    int    timeCount;
    int[]  members = NO_MEMBERS;
    int    memberCount;

    Identifier(final int kind) {
      this.kind = kind;
    }

    void addTime(final long time) {
      if (timeCount == times.length)
        times = Arrays.copyOf(times, Math.max(4, timeCount * 2));
      times[timeCount++] = time;
    }

    /** Members are added in ascending member order, so the list needs no sort. */
    void addMember(final int member) {
      if (memberCount == members.length)
        members = Arrays.copyOf(members, Math.max(4, memberCount * 2));
      members[memberCount++] = member;
    }
  }

  private final long[]                  memberTime;
  private final RID[][]                 memberIds;
  private final int[][]                 memberKinds;
  private final int[]                   memberPoint;
  private final long[]                  sortedTimes;
  private final Map<RID, Identifier>    identifiers;
  private final int[]                   kindMembers;
  private final long[][]                kindTimes;
  private final long[]                  pointTime;
  private final double[]                pointLat;
  private final double[]                pointLon;
  private final float[][]               memberStyle;
  private final long[][][]              memberImages;
  private final int                     styleMembers;
  private final int                     imageMembers;
  // Leave-one-out view: the member every accessor skips, or -1
  private final int                     excluded;

  private ClusterProfile(final long[] memberTime, final RID[][] memberIds, final int[][] memberKinds, final int[] memberPoint,
      final long[] sortedTimes, final Map<RID, Identifier> identifiers, final int[] kindMembers, final long[][] kindTimes,
      final long[] pointTime, final double[] pointLat, final double[] pointLon, final float[][] memberStyle,
      final long[][][] memberImages, final int excluded) {
    this.memberTime = memberTime;
    this.memberIds = memberIds;
    this.memberKinds = memberKinds;
    this.memberPoint = memberPoint;
    this.sortedTimes = sortedTimes;
    this.identifiers = identifiers;
    this.kindMembers = kindMembers;
    this.kindTimes = kindTimes;
    this.pointTime = pointTime;
    this.pointLat = pointLat;
    this.pointLon = pointLon;
    this.memberStyle = memberStyle;
    this.memberImages = memberImages;
    int styled = 0;
    int imaged = 0;
    for (int m = 0; m < memberStyle.length; m++) {
      if (memberStyle[m] != null)
        styled++;
      if (memberImages[m].length > 0)
        imaged++;
    }
    this.styleMembers = styled;
    this.imageMembers = imaged;
    this.excluded = excluded;
  }

  /** The same cluster without member {@code member} (in the order members were added), sharing every array. */
  public ClusterProfile without(final int member) {
    if (member < 0 || member >= memberTime.length)
      throw new IndexOutOfBoundsException("member " + member + " of " + memberTime.length);
    if (excluded >= 0)
      throw new IllegalStateException("a leave-one-out view cannot leave out a second member");
    return new ClusterProfile(memberTime, memberIds, memberKinds, memberPoint, sortedTimes, identifiers, kindMembers, kindTimes,
        pointTime, pointLat, pointLon, memberStyle, memberImages, member);
  }

  /** A profile of member {@code member} alone, built from what this profile already read. */
  public ClusterProfile only(final int member) {
    final RID[] ids = memberIds[member];
    final int[] kinds = new int[ids.length];
    for (int i = 0; i < ids.length; i++)
      kinds[i] = identifiers.get(ids[i]).kind;
    final int point = memberPoint[member];
    return new Builder().addMember(memberTime[member], point < 0 ? Double.NaN : pointLat[point], point < 0 ? Double.NaN : pointLon[point],
        ids, kinds, memberStyle[member], memberImages[member]).build();
  }

  public int size() {
    return memberTime.length - (excluded >= 0 ? 1 : 0);
  }

  /** The identifiers of the full cluster; a leave-one-out view may answer a {@link #count} of 0 for some of them. */
  Set<RID> identifierIds() {
    return identifiers.keySet();
  }

  int identifierCount() {
    return identifiers.size();
  }

  /** How many members reach the identifier. */
  int count(final RID id) {
    final Identifier identifier = identifiers.get(id);
    if (identifier == null)
      return 0;
    return identifier.count - (excludedReaches(id) ? 1 : 0);
  }

  int kind(final RID id) {
    final Identifier identifier = identifiers.get(id);
    return identifier == null ? -1 : identifier.kind;
  }

  /** The sorted known times of the members reaching the identifier; read it with {@link #skipIndex(RID)}. */
  long[] times(final RID id) {
    final Identifier identifier = identifiers.get(id);
    return identifier == null ? NO_TIMES : identifier.times;
  }

  /**
   * The members reaching the identifier, in ascending member order: which records put this identifier on this side, so an
   * evidence row can name them instead of only counting them. Read-only, and shared with the full profile unless this is a
   * leave-one-out view that excludes one of them, which gets a filtered copy.
   */
  int[] members(final RID id) {
    final Identifier identifier = identifiers.get(id);
    if (identifier == null)
      return NO_MEMBERS;
    if (!excludedReaches(id))
      return identifier.members;
    final int[] kept = new int[identifier.memberCount - 1];
    int k = 0;
    for (int i = 0; i < identifier.memberCount; i++)
      if (identifier.members[i] != excluded)
        kept[k++] = identifier.members[i];
    return kept;
  }

  /** The earliest known time of the members reaching the identifier, {@link #NO_TIME} when none of them has one. */
  long firstTime(final RID id) {
    return first(times(id), skipIndex(id));
  }

  long lastTime(final RID id) {
    return last(times(id), skipIndex(id));
  }

  /** The time of one member, {@link #NO_TIME} when it has none. */
  long memberTime(final int member) {
    return memberTime[member];
  }

  /** The index in {@link #times(RID)} a leave-one-out view skips, or -1. */
  int skipIndex(final RID id) {
    if (excluded < 0 || memberTime[excluded] == NO_TIME || !excludedReaches(id))
      return -1;
    return Arrays.binarySearch(identifiers.get(id).times, memberTime[excluded]);
  }

  /** How many members reach at least one identifier of the kind. */
  int kindMembers(final int kind) {
    if (kind >= kindMembers.length)
      return 0;
    return kindMembers[kind] - (excludedHasKind(kind) ? 1 : 0);
  }

  long kindFirst(final int kind) {
    if (kind >= kindTimes.length)
      return NO_TIME;
    return first(kindTimes[kind], kindSkipIndex(kind));
  }

  long kindLast(final int kind) {
    if (kind >= kindTimes.length)
      return NO_TIME;
    return last(kindTimes[kind], kindSkipIndex(kind));
  }

  /** The earliest known member time, {@link #NO_TIME} when no member has one. */
  public long first() {
    return first(sortedTimes, memberSkipIndex(sortedTimes));
  }

  public long last() {
    return last(sortedTimes, memberSkipIndex(sortedTimes));
  }

  /** Whether a member left in this view has a style vector. */
  boolean hasStyle() {
    return styleMembers - (excluded >= 0 && memberStyle[excluded] != null ? 1 : 0) > 0;
  }

  /** Whether a member left in this view has an image hash. */
  boolean hasImages() {
    return imageMembers - (excluded >= 0 && memberImages[excluded].length > 0 ? 1 : 0) > 0;
  }

  /** Every member's style vector, null for none; read with {@link #skipMember()}. */
  float[][] styles() {
    return memberStyle;
  }

  /** The width of the cluster's image hashes in 64-bit words (1 for 64-bit, 4 for PDQ), 0 when it has none. */
  int imageWords() {
    for (final long[][] images : memberImages)
      if (images.length > 0)
        return images[0].length;
    return 0;
  }

  /** Every member's image hashes; read with {@link #skipMember()}. */
  long[][][] images() {
    return memberImages;
  }

  /** The member a leave-one-out view skips, or {@link SoftSimilarity#NONE}. */
  int skipMember() {
    return excluded < 0 ? SoftSimilarity.NONE : excluded;
  }

  /** Members with a time and a place, sorted by time. */
  int pointCount() {
    return pointTime.length;
  }

  long pointTime(final int point) {
    return pointTime[point];
  }

  double pointLat(final int point) {
    return pointLat[point];
  }

  double pointLon(final int point) {
    return pointLon[point];
  }

  /** The point a leave-one-out view skips, or -1. */
  int skipPoint() {
    return excluded < 0 ? -1 : memberPoint[excluded];
  }

  private boolean excludedReaches(final RID id) {
    if (excluded < 0)
      return false;
    for (final RID rid : memberIds[excluded])
      if (rid.equals(id))
        return true;
    return false;
  }

  private boolean excludedHasKind(final int kind) {
    if (excluded < 0)
      return false;
    for (final int k : memberKinds[excluded])
      if (k == kind)
        return true;
    return false;
  }

  private int kindSkipIndex(final int kind) {
    if (!excludedHasKind(kind) || memberTime[excluded] == NO_TIME)
      return -1;
    return Arrays.binarySearch(kindTimes[kind], memberTime[excluded]);
  }

  private int memberSkipIndex(final long[] times) {
    if (excluded < 0 || memberTime[excluded] == NO_TIME)
      return -1;
    return Arrays.binarySearch(times, memberTime[excluded]);
  }

  private static long first(final long[] times, final int skip) {
    for (int i = 0; i < times.length; i++)
      if (i != skip)
        return times[i];
    return NO_TIME;
  }

  private static long last(final long[] times, final int skip) {
    for (int i = times.length - 1; i >= 0; i--)
      if (i != skip)
        return times[i];
    return NO_TIME;
  }

  /** Collects the members of one cluster. Identifiers a member reaches twice count once for it. */
  public static final class Builder {
    private long[]   times  = new long[16];
    private double[] lats   = new double[16];
    private double[] lons   = new double[16];
    private RID[][]  ids    = new RID[16][];
    private int[][]  kinds  = new int[16][];
    private float[][] styles = new float[16][];
    private long[][][] images = new long[16][][];
    private int      size;

    /**
     * @param time  epoch milliseconds, or {@link #NO_TIME}
     * @param lat   degrees, or NaN
     * @param lon   degrees, or NaN
     * @param ids   the identifiers the member reaches
     * @param kinds the kind of each identifier, as the model's kind index
     */
    public Builder addMember(final long time, final double lat, final double lon, final RID[] ids, final int[] kinds) {
      return addMember(time, lat, lon, ids, kinds, null, NO_IMAGES);
    }

    /**
     * As {@link #addMember(long, double, double, RID[], int[])}, with the member's style vector (null for none) and image
     * hashes (one {@code long[]} per image, empty for none) for the soft signals.
     */
    public Builder addMember(final long time, final double lat, final double lon, final RID[] ids, final int[] kinds,
        final float[] style, final long[][] images) {
      if (ids.length != kinds.length)
        throw new IllegalArgumentException("one kind per identifier expected");
      if (size == times.length) {
        final int capacity = size * 2;
        times = Arrays.copyOf(times, capacity);
        lats = Arrays.copyOf(lats, capacity);
        lons = Arrays.copyOf(lons, capacity);
        this.ids = Arrays.copyOf(this.ids, capacity);
        this.kinds = Arrays.copyOf(this.kinds, capacity);
        styles = Arrays.copyOf(styles, capacity);
        this.images = Arrays.copyOf(this.images, capacity);
      }
      // Deduplicate: a member linked twice to one identifier still counts once for it
      final RID[] uniqueIds = new RID[ids.length];
      final int[] uniqueKinds = new int[ids.length];
      int unique = 0;
      outer:
      for (int i = 0; i < ids.length; i++) {
        for (int j = 0; j < unique; j++)
          if (uniqueIds[j].equals(ids[i]))
            continue outer;
        uniqueIds[unique] = ids[i];
        uniqueKinds[unique++] = kinds[i];
      }
      times[size] = time;
      lats[size] = lat;
      lons[size] = lon;
      this.ids[size] = unique == ids.length ? uniqueIds : Arrays.copyOf(uniqueIds, unique);
      this.kinds[size] = unique == ids.length ? uniqueKinds : Arrays.copyOf(uniqueKinds, unique);
      styles[size] = style;
      this.images[size] = images == null ? NO_IMAGES : images;
      size++;
      return this;
    }

    public ClusterProfile build() {
      final long[] memberTime = Arrays.copyOf(times, size);
      final RID[][] memberIds = Arrays.copyOf(ids, size);
      final int[][] memberKinds = new int[size][];
      final Map<RID, Identifier> identifiers = new HashMap<>();

      int kindCount = 0;
      for (int m = 0; m < size; m++)
        for (final int k : kinds[m])
          kindCount = Math.max(kindCount, k + 1);
      final int[] kindMembers = new int[kindCount];
      final long[][] kindTimes = new long[kindCount][];
      final int[] kindTimeCount = new int[kindCount];
      for (int k = 0; k < kindCount; k++)
        kindTimes[k] = NO_TIMES;

      int timed = 0;
      for (int m = 0; m < size; m++) {
        final long time = memberTime[m];
        if (time != NO_TIME)
          timed++;
        for (int i = 0; i < memberIds[m].length; i++) {
          final int kind = kinds[m][i];
          final Identifier identifier = identifiers.computeIfAbsent(memberIds[m][i], rid -> new Identifier(kind));
          identifier.count++;
          identifier.addMember(m);
          if (time != NO_TIME)
            identifier.addTime(time);
        }
        // Each kind the member uses, once
        int distinct = 0;
        final int[] memberKindList = new int[kinds[m].length];
        outer:
        for (final int k : kinds[m]) {
          for (int j = 0; j < distinct; j++)
            if (memberKindList[j] == k)
              continue outer;
          memberKindList[distinct++] = k;
          kindMembers[k]++;
          if (time != NO_TIME) {
            if (kindTimeCount[k] == kindTimes[k].length)
              kindTimes[k] = Arrays.copyOf(kindTimes[k], Math.max(4, kindTimeCount[k] * 2));
            kindTimes[k][kindTimeCount[k]++] = time;
          }
        }
        memberKinds[m] = distinct == 0 ? NO_KINDS : Arrays.copyOf(memberKindList, distinct);
      }

      for (final Identifier identifier : identifiers.values()) {
        identifier.times = Arrays.copyOf(identifier.times, identifier.timeCount);
        Arrays.sort(identifier.times);
        identifier.members = Arrays.copyOf(identifier.members, identifier.memberCount);
      }
      for (int k = 0; k < kindCount; k++) {
        kindTimes[k] = Arrays.copyOf(kindTimes[k], kindTimeCount[k]);
        Arrays.sort(kindTimes[k]);
      }

      final long[] sortedTimes = new long[timed];
      int t = 0;
      for (int m = 0; m < size; m++)
        if (memberTime[m] != NO_TIME)
          sortedTimes[t++] = memberTime[m];
      Arrays.sort(sortedTimes);

      // Points: members with a time and a place, sorted by time (ties by member order)
      int pointCount = 0;
      for (int m = 0; m < size; m++)
        if (hasPoint(m))
          pointCount++;
      final Integer[] pointMembers = new Integer[pointCount];
      int p = 0;
      for (int m = 0; m < size; m++)
        if (hasPoint(m))
          pointMembers[p++] = m;
      Arrays.sort(pointMembers, (x, y) -> Long.compare(memberTime[x], memberTime[y]));
      final long[] pointTime = new long[pointCount];
      final double[] pointLat = new double[pointCount];
      final double[] pointLon = new double[pointCount];
      final int[] memberPoint = new int[size];
      Arrays.fill(memberPoint, -1);
      for (int i = 0; i < pointCount; i++) {
        final int m = pointMembers[i];
        pointTime[i] = memberTime[m];
        pointLat[i] = lats[m];
        pointLon[i] = lons[m];
        memberPoint[m] = i;
      }

      return new ClusterProfile(memberTime, memberIds, memberKinds, memberPoint, sortedTimes, identifiers, kindMembers, kindTimes,
          pointTime, pointLat, pointLon, Arrays.copyOf(styles, size), Arrays.copyOf(images, size), -1);
    }

    private boolean hasPoint(final int m) {
      return times[m] != NO_TIME && Double.isFinite(lats[m]) && Double.isFinite(lons[m]);
    }
  }
}
