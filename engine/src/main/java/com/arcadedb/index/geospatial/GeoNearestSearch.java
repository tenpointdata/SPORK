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
package com.arcadedb.index.geospatial;

import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.function.sql.geo.GeoUtils;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.utility.RidHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact k-nearest-neighbour search over the GeoHash cells of a geospatial index, without scanning the type.
 * <p>
 * A best-first walk (Hjaltason and Samet's incremental nearest neighbour) over ONE priority queue holding three kinds of
 * items, each keyed by a great-circle distance that never exceeds the distance of anything it can still produce:
 * <ul>
 *   <li>a CELL, keyed by the distance from the query point to its rectangle. Popping it opens one prefix range scan per
 *   bucket index over the entries stored at or below it. When the cell holds more entries than the scan budget, the scan
 *   stops and the cell is split into its 32 children instead, so a sparse region is read in one coarse scan and a dense one
 *   is narrowed down until its cells are small: no density has to be known up front;</li>
 *   <li>an ENTRY, one index entry (a RID under a cell token), keyed by the distance to the cell of its token. Nothing is
 *   loaded to queue it. On a point index the token is the point's own cell at the index precision (about 15 cm at the
 *   default 11), so the key is within centimetres of the exact distance and only the records that can still be among the
 *   nearest are ever loaded;</li>
 *   <li>a HIT, a record the {@link Resolver} loaded and measured, keyed by its exact distance.</li>
 * </ul>
 * A HIT at the head of the queue is nearer than everything still queued could be, so it is the next nearest record: the
 * walk ends after {@code k} of them, when the head is farther than the maximum distance, or when the queue empties. This is
 * the "stop when the k-th distance is below the next ring's minimum distance" rule, with the rings being whatever cells
 * are queued rather than a fixed annulus.
 * <p>
 * A shape that is not a point is indexed under several cells, some coarser than the cells the walk reaches by splitting:
 * those are the entries whose key is the split cell's own token, kept when the cell is split (they sort first in its
 * range), and looked up exactly on the bucket indexes the scan had not reached. A record met under several cells is
 * measured once, at the nearest of them.
 * <p>
 * The legacy FULL layout stores the whole ancestor chain of each cell: its range scans start after the cell's own
 * (ancestor) token, which every record below the cell carries, and its leaf tokens end with {@code '+'}, ignored when a
 * token is decoded. It answers the same, reading more entries.
 */
public final class GeoNearestSearch {
  /** The GeoHash alphabet, in the order of the cell values. */
  static final         String BASE32          = "0123456789bcdefghjkmnpqrstuvwxyz";
  private static final byte[] BASE32_VALUES   = new byte[128];
  /** Entries a cell scan reads before it gives up on the cell and splits it instead, at least: see {@link #scanBudget}. */
  static final         int    MIN_SCAN_BUDGET = 256;
  /** Taken off every lower bound so that floating point rounding cannot lift it above the distance it bounds. */
  private static final double ROUNDING_SLACK  = 1e-12;
  private static final byte   CELL            = 0;
  private static final byte   ENTRY           = 1;
  private static final byte   HIT             = 2;

  static {
    Arrays.fill(BASE32_VALUES, (byte) -1);
    for (int i = 0; i < BASE32.length(); i++)
      BASE32_VALUES[BASE32.charAt(i)] = (byte) i;
  }

  /**
   * A record of the answer and its exact great-circle distance from the query point, in radians of arc (multiply by
   * {@link GeoUtils#EARTH_RADIUS_KM} for kilometres).
   */
  public record Hit(Document record, double distanceRadians) {
  }

  /**
   * Loads a candidate the walk reached and measures it from the query point.
   */
  @FunctionalInterface
  public interface Resolver {
    /**
     * @return the record and its exact distance in radians, or null to leave it out of the answer: gone from its bucket,
     * rejected by a filter, or holding no geometry the caller can measure
     */
    Hit resolve(RID rid);
  }

  private final List<LSMTreeGeoIndex> indexes;
  private final double                lon;
  private final double                lat;
  private final int                   k;
  private final double                maxRadians;
  private final Resolver              resolver;
  private final WorkGuard             guard;
  private final int                   precision;
  private final int                   budget;
  private final double[]              box  = new double[4];
  private final RidHashSet            seen = new RidHashSet();

  // the priority queue: a binary min-heap over three parallel arrays
  private double[] priorities = new double[256];
  private byte[]   kinds      = new byte[256];
  private Object[] items      = new Object[256];
  private int      size;

  // the entries of the cell being scanned, reused from one cell to the next
  private String[] scannedKeys = new String[64];
  private RID[]    scannedRIDs = new RID[64];
  private int      scannedCount;

  private GeoNearestSearch(final List<LSMTreeGeoIndex> indexes, final double lon, final double lat, final int k,
      final double maxRadians, final Resolver resolver, final WorkGuard guard) {
    this.indexes = indexes;
    this.lon = lon;
    this.lat = lat;
    this.k = k;
    this.maxRadians = maxRadians;
    this.resolver = resolver;
    this.guard = guard;
    int minPrecision = Integer.MAX_VALUE;
    for (final LSMTreeGeoIndex index : indexes)
      minPrecision = Math.min(minPrecision, index.getPrecision());
    this.precision = minPrecision;
    this.budget = scanBudget(k);
  }

  /**
   * The {@code k} records nearest to the point ({@code lon}, {@code lat}) held by any of {@code indexes}, nearest first.
   *
   * @param indexes    the bucket indexes to search, all of the same type index
   * @param k          the number of records to answer at most
   * @param maxRadians the distance beyond which no record is answered, in radians of arc; {@code +Infinity} for none
   * @param resolver   loads and measures each candidate the walk reaches
   * @param guard      the command's deadline, checked before each cell scan; null for none
   */
  public static List<Hit> search(final List<LSMTreeGeoIndex> indexes, final double lon, final double lat, final int k,
      final double maxRadians, final Resolver resolver, final WorkGuard guard) {
    if (k <= 0 || indexes.isEmpty() || !(maxRadians >= 0))
      return List.of();
    return new GeoNearestSearch(indexes, lon, lat, k, maxRadians, resolver, guard).run();
  }

  /**
   * Entries a cell scan may read before the cell is split. Reading entries is cheap (no record is loaded, each costs a token
   * decode and a heap push) while a split costs up to 32 child scans, each opening a cursor per bucket: the budget leans
   * towards reading, and grows with {@code k}, since a large answer is going to read that many entries anyway.
   */
  static int scanBudget(final int k) {
    return Math.max(MIN_SCAN_BUDGET, 4 * k);
  }

  private List<Hit> run() {
    final List<Hit> answer = new ArrayList<>(Math.min(k, 1024));

    for (int i = 0; i < BASE32.length(); i++)
      pushCell(String.valueOf(BASE32.charAt(i)));

    while (size > 0 && answer.size() < k) {
      if (priorities[0] > maxRadians)
        break;

      final byte kind = kinds[0];
      final Object item = items[0];
      pop();

      switch (kind) {
      case HIT -> answer.add((Hit) item);
      case ENTRY -> {
        final RID rid = (RID) item;
        if (!seen.add(rid))
          continue;
        final Hit hit = resolver.resolve(rid);
        if (hit != null && hit.distanceRadians() <= maxRadians)
          push(hit.distanceRadians(), HIT, hit);
      }
      default -> scanCell((String) item);
      }
    }
    return answer;
  }

  /**
   * Queues the entries stored at or below {@code token}, or splits the cell when they are more than the budget.
   */
  private void scanCell(final String token) {
    if (guard != null)
      guard.check();

    final boolean canSplit = token.length() < precision;
    scannedCount = 0;
    int overflowAt = -1;
    int overflowIndexStart = 0;

    for (int i = 0; i < indexes.size() && overflowAt < 0; i++) {
      final LSMTreeGeoIndex index = indexes.get(i);
      final int indexStart = scannedCount;
      if (readCell(index.openNearestCellRange(token), index, token, canSplit)
          || readCell(index.openNearestPendingRange(token), index, token, canSplit)) {
        overflowAt = i;
        overflowIndexStart = indexStart;
      }
    }

    if (overflowAt < 0) {
      for (int i = 0; i < scannedCount; i++)
        pushEntry(scannedKeys[i], scannedRIDs[i]);
      return;
    }

    // Too dense to read whole: keep the entries of the cell itself, which no child holds, and split it. The own entries of
    // the bucket indexes read whole are in the buffer; the others are asked for them with an exact lookup, which also
    // answers the ones the transaction has not committed yet.
    final LSMTreeGeoIndex layout = indexes.get(overflowAt);
    for (int i = 0; i < overflowIndexStart; i++)
      if (layout.isNearestCellOwnKey(scannedKeys[i], token))
        pushEntry(scannedKeys[i], scannedRIDs[i]);
    for (int i = overflowAt; i < indexes.size(); i++) {
      final IndexCursor cursor = indexes.get(i).openNearestCellOwnEntries(token);
      try {
        while (cursor.hasNext()) {
          final Identifiable entry = cursor.next();
          if (entry != null)
            pushEntry(token, entry.getIdentity());
        }
      } finally {
        cursor.close();
      }
    }

    for (int i = 0; i < BASE32.length(); i++)
      pushCell(token + BASE32.charAt(i));
  }

  /**
   * Buffers the entries of one cursor over the cell, and closes it.
   *
   * @return true when the cell holds more entries than the budget and can be split instead
   */
  private boolean readCell(final IndexCursor cursor, final LSMTreeGeoIndex index, final String token, final boolean canSplit) {
    if (cursor == null)
      return false;
    try {
      while (cursor.hasNext()) {
        final Identifiable entry = cursor.next();
        if (entry == null)
          continue;
        final String key = (String) cursor.getKeys()[0];
        // the cell's own entries sort first in its range and are never given up on: no split can reach them
        if (canSplit && scannedCount >= budget && !index.isNearestCellOwnKey(key, token))
          return true;
        addScanned(scannedCount++, key, entry.getIdentity());
      }
      return false;
    } finally {
      cursor.close();
    }
  }

  private void addScanned(final int position, final String key, final RID rid) {
    if (position == scannedKeys.length) {
      scannedKeys = Arrays.copyOf(scannedKeys, position * 2);
      scannedRIDs = Arrays.copyOf(scannedRIDs, position * 2);
    }
    scannedKeys[position] = key;
    scannedRIDs[position] = rid;
  }

  private void pushCell(final String token) {
    final double bound = lowerBound(token);
    if (bound <= maxRadians)
      push(bound, CELL, token);
  }

  private void pushEntry(final String key, final RID rid) {
    if (seen.contains(rid))
      return;
    final double bound = lowerBound(key);
    if (bound <= maxRadians)
      push(bound, ENTRY, rid);
  }

  /** The distance to the cell of {@code token}; 0 for a token that is not a GeoHash, so that its record is still met. */
  private double lowerBound(final String token) {
    if (!decodeGeohash(token, box))
      return 0.0;
    return minRadiansToRectangle(lon, lat, box[0], box[1], box[2], box[3]);
  }

  // ---- the priority queue ----

  private void push(final double priority, final byte kind, final Object item) {
    if (size == priorities.length) {
      final int capacity = size * 2;
      priorities = Arrays.copyOf(priorities, capacity);
      kinds = Arrays.copyOf(kinds, capacity);
      items = Arrays.copyOf(items, capacity);
    }
    int i = size++;
    while (i > 0) {
      final int parent = (i - 1) >>> 1;
      if (priorities[parent] <= priority)
        break;
      move(parent, i);
      i = parent;
    }
    priorities[i] = priority;
    kinds[i] = kind;
    items[i] = item;
  }

  private void pop() {
    final int last = --size;
    final double priority = priorities[last];
    final byte kind = kinds[last];
    final Object item = items[last];
    items[last] = null;
    if (last == 0)
      return;

    int i = 0;
    while (true) {
      int child = 2 * i + 1;
      if (child >= last)
        break;
      if (child + 1 < last && priorities[child + 1] < priorities[child])
        ++child;
      if (priorities[child] >= priority)
        break;
      move(child, i);
      i = child;
    }
    priorities[i] = priority;
    kinds[i] = kind;
    items[i] = item;
  }

  private void move(final int from, final int to) {
    priorities[to] = priorities[from];
    kinds[to] = kinds[from];
    items[to] = items[from];
  }

  // ---- geometry ----

  /**
   * Decodes a GeoHash cell token into its rectangle, {@code {minX, maxX, minY, maxY}} in degrees, without allocating. A
   * trailing {@code '+'} (the leaf marker of the legacy FULL layout) is ignored.
   *
   * @return false when the token is not a GeoHash
   */
  static boolean decodeGeohash(final String token, final double[] box) {
    int length = token.length();
    if (length > 0 && token.charAt(length - 1) == '+')
      --length;

    double minX = -180.0;
    double maxX = 180.0;
    double minY = -90.0;
    double maxY = 90.0;
    boolean longitude = true;
    for (int i = 0; i < length; i++) {
      final char c = token.charAt(i);
      final int value = c < 128 ? BASE32_VALUES[c] : -1;
      if (value < 0)
        return false;
      for (int mask = 16; mask > 0; mask >>= 1) {
        if (longitude) {
          final double mid = (minX + maxX) / 2;
          if ((value & mask) != 0)
            minX = mid;
          else
            maxX = mid;
        } else {
          final double mid = (minY + maxY) / 2;
          if ((value & mask) != 0)
            minY = mid;
          else
            maxY = mid;
        }
        longitude = !longitude;
      }
    }
    box[0] = minX;
    box[1] = maxX;
    box[2] = minY;
    box[3] = maxY;
    return true;
  }

  /**
   * The great-circle distance, in radians of arc, from the point ({@code lon}, {@code lat}) to the nearest point of the
   * longitude/latitude rectangle (no wider than 180 degrees, as every GeoHash cell below the root is).
   * <p>
   * Inside the rectangle's longitudes the nearest point is straight north or south, on an edge parallel, and the distance
   * is the latitude difference: no point of the rectangle can be closer, since a great-circle distance is never shorter than
   * the latitudes it spans. Outside them the nearest point is on one of the two edge meridians (along an edge parallel the
   * distance only grows with the longitude difference, so its nearest point is a corner), see
   * {@link #radiansToMeridianSegment}.
   */
  static double minRadiansToRectangle(final double lon, final double lat, final double minX, final double maxX,
      final double minY, final double maxY) {
    final double distance;
    if (lon >= minX && lon <= maxX) {
      if (lat >= minY && lat <= maxY)
        return 0.0;
      distance = Math.toRadians(lat < minY ? minY - lat : lat - maxY);
    } else
      distance = Math.min(radiansToMeridianSegment(lon, lat, minX, minY, maxY),
          radiansToMeridianSegment(lon, lat, maxX, minY, maxY));
    return Math.max(0.0, distance - ROUNDING_SLACK);
  }

  /**
   * The distance from the point to the segment of the meridian {@code meridianLon} between the two latitudes.
   * <p>
   * When the meridian faces the point (less than 90 degrees of longitude away) its nearest point, the foot of the
   * perpendicular, has latitude {@code atan(tan(lat) / cos(dLon))}, and the distance grows monotonically away from it along
   * the meridian, so the foot clamped into the segment is the nearest point of the segment. When it faces away, the foot is
   * on the opposite meridian and this one holds the FARTHEST point of the great circle instead: the distance falls from
   * there towards both poles, so the nearest point of the segment is one of its two ends, not necessarily the one on the
   * point's side.
   */
  private static double radiansToMeridianSegment(final double lon, final double lat, final double meridianLon,
      final double minY, final double maxY) {
    final double cosDeltaLon = Math.cos(Math.toRadians(meridianLon - lon));
    if (cosDeltaLon <= 0)
      return Math.min(GeoUtils.haversineRadians(lon, lat, meridianLon, minY),
          GeoUtils.haversineRadians(lon, lat, meridianLon, maxY));

    final double footLat = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(lat)) / cosDeltaLon));
    return GeoUtils.haversineRadians(lon, lat, meridianLon, Math.max(minY, Math.min(maxY, footLat)));
  }
}
