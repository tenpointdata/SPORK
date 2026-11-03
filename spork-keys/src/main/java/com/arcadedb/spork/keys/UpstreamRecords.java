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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The identifier and location fields Recon reads out of an enriched upstream RedLight record, ported from
 * {@code api-source.ts} {@code mapRecord}: the same sub-object field fallbacks, in the same order, with JavaScript's
 * {@code ??} (only a missing or null field falls through, an empty string does not) and {@code filter(Boolean)}. The values
 * are Recon's display values (2.1 denormalized {@code phones[]}, {@code emails[]}, ...), not keys: feed them to
 * {@link SporkKeys} for keys.
 */
public final class UpstreamRecords {
  public static final String UNKNOWN_CITY    = "Unknown";
  public static final String UNKNOWN_STATE   = "??";
  public static final String DEFAULT_COUNTRY = "US";

  private UpstreamRecords() {
  }

  /** {@code rec.Phone[]}: phone_number ?? phone_number_original ?? number ?? phone ?? "(area_code) line". */
  public static List<String> phones(final JSONObject rec) {
    final JSONArray a = array(rec, "Phone");
    final List<String> out = new ArrayList<>(a == null ? 0 : a.length());
    if (a == null)
      return out;
    for (int i = 0; i < a.length(); i++) {
      final JSONObject p = object(a, i);
      if (p == null)
        continue;
      Object v = coalesce(p, "phone_number", "phone_number_original", "number", "phone");
      if (v == null) {
        final Object area = p.opt("area_code");
        final Object line = p.opt("line");
        v = truthy(area) && truthy(line) ? "(" + jsString(area) + ") " + jsString(line) : "";
      }
      if (truthy(v))
        out.add(jsString(v));
    }
    return out;
  }

  /** {@code rec.Email[]}: email_address ?? email ?? address. */
  public static List<String> emails(final JSONObject rec) {
    return strings(rec, "Email", "email_address", "email", "address");
  }

  /** {@code rec.Business[]}: business_name ?? name. */
  public static List<String> businessNames(final JSONObject rec) {
    return strings(rec, "Business", "business_name", "name");
  }

  /** {@code rec.Social[]} as {platform, handle}: social_platform ?? platform ?? 'social', username ?? handle ?? value. */
  public static List<String[]> socials(final JSONObject rec) {
    final JSONArray a = array(rec, "Social");
    final List<String[]> out = new ArrayList<>();
    if (a == null)
      return out;
    for (int i = 0; i < a.length(); i++) {
      final JSONObject s = object(a, i);
      if (s == null)
        continue;
      final Object platform = coalesce(s, "social_platform", "platform");
      final Object handle = coalesce(s, "username", "handle", "value");
      if (truthy(handle))
        out.add(new String[] { platform == null ? SporkKeys.DEFAULT_PLATFORM : jsString(platform), jsString(handle) });
    }
    return out;
  }

  /** {@code rec.File[]} that is not {@code meta_path} and carries a hash: hash ?? sha256, verbatim. */
  public static List<String> imageHashes(final JSONObject rec) {
    final JSONArray a = array(rec, "File");
    final List<String> out = new ArrayList<>();
    if (a == null)
      return out;
    for (int i = 0; i < a.length(); i++) {
      final JSONObject f = object(a, i);
      if (f == null || "meta_path".equals(f.opt("mime_type")))
        continue;
      final Object h = coalesce(f, "hash", "sha256");
      if (truthy(h))
        out.add(jsString(h));
    }
    return out;
  }

  /** {@code first(rec.Location) ?? {}} */
  public static JSONObject location(final JSONObject rec) {
    final JSONArray a = array(rec, "Location");
    final JSONObject l = a == null ? null : object(a, 0);
    return l == null ? new JSONObject() : l;
  }

  public static String city(final JSONObject location) {
    final Object v = location.opt("city");
    return v == null ? UNKNOWN_CITY : jsString(v);
  }

  public static String state(final JSONObject location) {
    final Object v = location.opt("state");
    return v == null ? UNKNOWN_STATE : jsString(v);
  }

  public static String country(final JSONObject location) {
    final Object v = location.opt("country");
    return v == null ? DEFAULT_COUNTRY : jsString(v);
  }

  /**
   * {lat, lon}: the search row's lat/lon, then the record's {@code geocode}, then the gazetteer point of city and state, each
   * coordinate on its own; 0 counts as missing ({@code num}). {0, 0} when nothing is known.
   */
  public static double[] latLon(final JSONObject basic, final JSONObject location) {
    final Object geocode = location.opt("geocode");
    final JSONArray geo = geocode instanceof JSONArray g ? g : null;
    final double[] gaz = ReconGeography.coord(jsStringOrNull(location.opt("city")), jsStringOrNull(location.opt("state")));
    final double[] out = new double[2];
    out[0] = firstNum(basic.opt("lat"), geo, 0, gaz[0]);
    out[1] = firstNum(basic.opt("lon"), geo, 1, gaz[1]);
    return out;
  }

  private static double firstNum(final Object fromBasic, final JSONArray geo, final int index, final double fallback) {
    double v = num(fromBasic);
    if (!Double.isNaN(v))
      return v;
    if (geo != null && index < geo.length()) {
      v = num(geo.get(index));
      if (!Double.isNaN(v))
        return v;
    }
    return fallback;
  }

  /** {@code num(v)}: a JSON number that is not NaN or 0, else NaN for "undefined". */
  private static double num(final Object v) {
    if (v instanceof Number n) {
      final double d = n.doubleValue();
      return d == 0 ? Double.NaN : d;
    }
    return Double.NaN;
  }

  private static List<String> strings(final JSONObject rec, final String array, final String... fields) {
    final JSONArray a = array(rec, array);
    final List<String> out = new ArrayList<>();
    if (a == null)
      return out;
    for (int i = 0; i < a.length(); i++) {
      final JSONObject o = object(a, i);
      if (o == null)
        continue;
      final Object v = coalesce(o, fields);
      if (truthy(v))
        out.add(jsString(v));
    }
    return out;
  }

  /** {@code o.a ?? o.b ?? ...} */
  private static Object coalesce(final JSONObject o, final String... fields) {
    for (final String f : fields) {
      final Object v = o.opt(f);
      if (v != null)
        return v;
    }
    return null;
  }

  private static JSONArray array(final JSONObject rec, final String name) {
    return rec != null && rec.opt(name) instanceof JSONArray a ? a : null;
  }

  private static JSONObject object(final JSONArray a, final int i) {
    return i < a.length() && a.get(i) instanceof JSONObject o ? o : null;
  }

  /** JavaScript truthiness of a JSON value. */
  private static boolean truthy(final Object v) {
    if (v == null)
      return false;
    if (v instanceof String s)
      return !s.isEmpty();
    if (v instanceof Boolean b)
      return b;
    if (v instanceof Number n) {
      final double d = n.doubleValue();
      return d != 0 && !Double.isNaN(d);
    }
    return true;
  }

  private static String jsStringOrNull(final Object v) {
    return v == null ? null : jsString(v);
  }

  /** {@code String(v)} for the JSON scalars upstream sends; integral numbers print without a fraction, as in JS. */
  private static String jsString(final Object v) {
    if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15)
      return Long.toString(d.longValue());
    return v.toString();
  }
}
