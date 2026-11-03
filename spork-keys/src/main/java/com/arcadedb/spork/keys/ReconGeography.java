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
package com.arcadedb.spork.keys;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Recon's geographic reference tables, ported from {@code geography.ts} (CITIES, AREA_CODE_TO_PLACE), {@code engine.ts}
 * (PLACE_TO_AREA) and {@code api-source.ts} (the GAZ gazetteer). They decide which area code a 7-digit phone inherits and
 * which coordinates a record without a geocode is plotted at, so they are part of the canonical phone key.
 * <p>
 * PLACE_TO_AREA keeps the FIRST area code of each place in {@code Object.entries} order, and JavaScript enumerates
 * integer-like keys in ascending numeric order, not in insertion order: Houston inherits 281, not the 713 that
 * {@code CITIES} lists first. The port reproduces that (golden vector {@code area-codes.json}).
 */
public final class ReconGeography {
  /** {@code geography.ts} CITIES: city, state, area codes in Recon's order. */
  private static final String[][] CITIES = {
      { "Houston", "TX", "713", "281", "832" },
      { "Dallas", "TX", "214", "469", "972" },
      { "Austin", "TX", "512" },
      { "San Antonio", "TX", "210" },
      { "New Orleans", "LA", "504" },
      { "Atlanta", "GA", "404", "470" },
      { "Miami", "FL", "305", "786" },
      { "Phoenix", "AZ", "602" },
      { "Las Vegas", "NV", "702" },
      { "Los Angeles", "CA", "213", "323" } };

  /** {@code api-source.ts} GAZ: "city,st" lowercased to {lat, lon}. */
  private static final Map<String, double[]> GAZ = new HashMap<>();

  private static final Map<String, String> AREA_CODE_TO_PLACE;
  private static final Map<String, String> PLACE_TO_AREA;

  static {
    // Integer-like keys enumerate in ascending numeric order in JS: a TreeMap on the 3-digit codes gives the same order
    final TreeMap<Integer, String> byCode = new TreeMap<>();
    for (final String[] c : CITIES)
      for (int i = 2; i < c.length; i++)
        byCode.put(Integer.parseInt(c[i]), c[0] + ", " + c[1]);
    final Map<String, String> a2p = new LinkedHashMap<>();
    final Map<String, String> p2a = new LinkedHashMap<>();
    for (final Map.Entry<Integer, String> e : byCode.entrySet()) {
      final String area = String.valueOf(e.getKey());
      a2p.put(area, e.getValue());
      p2a.putIfAbsent(e.getValue(), area);
    }
    AREA_CODE_TO_PLACE = Collections.unmodifiableMap(a2p);
    PLACE_TO_AREA = Collections.unmodifiableMap(p2a);

    gaz("houston,tx", 29.76, -95.37);
    gaz("dallas,tx", 32.78, -96.8);
    gaz("austin,tx", 30.27, -97.74);
    gaz("san antonio,tx", 29.42, -98.49);
    gaz("new orleans,la", 29.95, -90.07);
    gaz("atlanta,ga", 33.75, -84.39);
    gaz("miami,fl", 25.76, -80.19);
    gaz("phoenix,az", 33.45, -112.07);
    gaz("las vegas,nv", 36.17, -115.14);
    gaz("los angeles,ca", 34.05, -118.24);
    gaz("new york,ny", 40.71, -74.01);
    gaz("chicago,il", 41.88, -87.63);
  }

  private ReconGeography() {
  }

  private static void gaz(final String key, final double lat, final double lon) {
    GAZ.put(key, new double[] { lat, lon });
  }

  /** {@code AREA_CODE_TO_PLACE}: "713" to "Houston, TX". */
  public static Map<String, String> areaCodeToPlace() {
    return AREA_CODE_TO_PLACE;
  }

  /** {@code PLACE_TO_AREA}: "Houston, TX" to its representative area code ("281"). */
  public static Map<String, String> placeToArea() {
    return PLACE_TO_AREA;
  }

  /** {@code PLACE_TO_AREA[`${city}, ${state}`]}: case sensitive, as in Recon. Null when the place is unknown. */
  public static String areaCodeOfPlace(final String city, final String state) {
    if (city == null || state == null)
      return null;
    return PLACE_TO_AREA.get(city + ", " + state);
  }

  /**
   * {@code api-source.ts} {@code coord(city, state)}: the gazetteer point, {0, 0} when unknown. A fresh array per call; the
   * caller owns it.
   */
  public static double[] coord(final String city, final String state) {
    final String key = JsText.lower(city == null ? "" : city) + "," + JsText.lower(state == null ? "" : state);
    final double[] p = GAZ.get(key);
    return p == null ? new double[2] : p.clone();
  }
}
