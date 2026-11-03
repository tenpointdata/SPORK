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

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * SPORK canonical identifier keys (docs/spork/01-architecture.md 2.3): the only way one SPORK database refers to another,
 * the UNIQUE HASH key of every Identifier vertex, and the values of {@code Target.identifierKeys[]}. One function per kind,
 * each returning {@code <prefix>:<normalized>} or null when the input normalizes to nothing (no key, no vertex).
 * <ul>
 *   <li>Phone {@code ph:}: Recon's engine link key ({@code engine.ts} canonPhone with the posting place): ASCII digits, the
 *   last 10 when longer, and a 7-digit number inherits the area code of the record's {@code "City, ST"}
 *   ({@link ReconGeography}) with {@code inferredAreaCode=true}.</li>
 *   <li>Email {@code em:}: JS-trimmed, lowercased, one leading {@code @} stripped.</li>
 *   <li>Handle {@code sh:<platform>:<handle>}: both trimmed and lowercased, the handle's leading {@code @} stripped, a blank
 *   platform is Recon's default {@code social}.</li>
 *   <li>Business {@code bz:}: lowercase ASCII alphanumerics (the cluster and dossier rule).</li>
 *   <li>Image hash {@code ih:}: the upstream string verbatim, never trimmed, truncated or re-cased.</li>
 *   <li>Name {@code nm:}: NFKD, lowercased, letters only, whitespace runs between letters kept as one space.</li>
 *   <li>Place {@code pl:<cc>:<state>:<city>[:<geohash7>]} and its LONG {@code placeId}.</li>
 * </ul>
 * Where the canonical key differs from one of Recon's use sites ({@link ReconNormalizers}) the golden vectors show both;
 * those differences are WS4 shadow-diff decisions, not bugs. Pure functions, thread safe.
 */
public final class SporkKeys {
  public static final String PHONE      = "ph:";
  public static final String EMAIL      = "em:";
  public static final String HANDLE     = "sh:";
  public static final String NAME       = "nm:";
  public static final String BUSINESS   = "bz:";
  public static final String IMAGE_HASH = "ih:";
  public static final String PLACE      = "pl:";

  /** Recon's default platform for a social without one ({@code api-source.ts} mapRecord). */
  public static final String DEFAULT_PLATFORM = "social";

  private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

  private SporkKeys() {
  }

  // ── Phones ──────────────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code ph:<digits>} with the posting place of the record (city and state may be null), or null without digits. */
  public static String phone(final String raw, final String city, final String state) {
    final String d = phoneDigits(raw, city, state);
    return d.isEmpty() ? null : PHONE + d;
  }

  /** {@code ph:<digits>} without a place: a 7-digit number keeps its 7 digits (board snapshots carry no city). */
  public static String phone(final String raw) {
    return phone(raw, null, null);
  }

  /** The digits of {@link #phone(String, String, String)}, "" when there are none. */
  public static String phoneDigits(final String raw, final String city, final String state) {
    return ReconNormalizers.engineCanonPhone(raw, city, state);
  }

  /** True when the key's area code was inherited from the posting place (a 7-digit number in a known city). */
  public static boolean isInferredAreaCode(final String raw, final String city, final String state) {
    return lastDigitsLength(raw) == 7 && ReconGeography.areaCodeOfPlace(city, state) != null;
  }

  /**
   * Every phone key a record or target contributes to {@code identifierKeys[]}: the canonical key, and for an inferred area
   * code also the raw 7-digit key, so deconfliction stays a superset of Recon's 7-digit matches (2.1 Target).
   */
  public static String[] phoneKeys(final String raw, final String city, final String state) {
    final String key = phone(raw, city, state);
    if (key == null)
      return new String[0];
    if (isInferredAreaCode(raw, city, state))
      return new String[] { phone(raw), key };
    return new String[] { key };
  }

  private static int lastDigitsLength(final String raw) {
    final int n = JsText.digits(raw).length();
    return Math.min(n, 10);
  }

  // ── Emails, handles, businesses, image hashes, names ────────────────────────────────────────────────────────────────

  public static String email(final String raw) {
    final String v = JsText.stripLeadingAt(JsText.lower(JsText.trim(raw)));
    return v.isEmpty() ? null : EMAIL + v;
  }

  /** {@code sh:<platform>:<handle>}; a null or blank platform is {@value #DEFAULT_PLATFORM}. */
  public static String handle(final String platform, final String handle) {
    final String h = JsText.stripLeadingAt(JsText.lower(JsText.trim(handle)));
    if (h.isEmpty())
      return null;
    final String p = platform == null ? "" : JsText.lower(JsText.trim(platform));
    return HANDLE + (p.isEmpty() ? DEFAULT_PLATFORM : p) + ":" + h;
  }

  public static String business(final String raw) {
    final String v = JsText.lowerAlnum(raw);
    return v.isEmpty() ? null : BUSINESS + v;
  }

  public static String imageHash(final String raw) {
    return raw == null || raw.isEmpty() ? null : IMAGE_HASH + raw;
  }

  /**
   * The 32 bytes of an upstream image hash that is 64 lowercase hex characters (a SHA-256 as Recon prints it), else null:
   * only that form round-trips to the exact string, so any other is stored as the string itself (2.1 ImageHash).
   */
  public static byte[] packImageHash(final String raw) {
    if (raw == null || raw.length() != 64)
      return null;
    for (int i = 0; i < 64; i++) {
      final char c = raw.charAt(i);
      if (!JsText.isAsciiDigit(c) && (c < 'a' || c > 'f'))
        return null;
    }
    return HexFormat.of().parseHex(raw);
  }

  public static String unpackImageHash(final byte[] packed) {
    return HexFormat.of().formatHex(packed);
  }

  public static String name(final String raw) {
    final String l = JsText.lower(Normalizer.normalize(raw, Normalizer.Form.NFKD));
    final StringBuilder out = new StringBuilder(l.length());
    boolean pendingSpace = false;
    for (int i = 0; i < l.length(); ) {
      final int cp = l.codePointAt(i);
      i += Character.charCount(cp);
      if (cp <= 0xffff && JsText.isWhitespace((char) cp))
        pendingSpace = true;
      else if (Character.isLetter(cp)) {
        if (pendingSpace && !out.isEmpty())
          out.append(' ');
        pendingSpace = false;
        out.appendCodePoint(cp);
      }
    }
    return out.isEmpty() ? null : NAME + out;
  }

  // ── Places ──────────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code pl:<cc>:<state>:<city>} plus {@code :<geohash7>} when the point is known. Components are JS-trimmed, lowercased,
   * whitespace-collapsed, with ':' read as a space. Recon's 0 means "no coordinate", so (0, 0) and non-finite points carry
   * no cell.
   */
  public static String place(final String country, final String state, final String city, final double lat, final double lon) {
    final StringBuilder key = new StringBuilder(48).append(PLACE).append(placeComponent(country)).append(':')
        .append(placeComponent(state)).append(':').append(placeComponent(city));
    final String cell = geohash7(lat, lon);
    if (cell != null)
      key.append(':').append(cell);
    return key.toString();
  }

  /** {@code Place.cityNorm} and {@code Record.cityNorm}: the city component of the place key. */
  public static String cityNorm(final String city) {
    return placeComponent(city);
  }

  /** {@code placeId}: the first 8 bytes of SHA-256 over the key's UTF-8, big-endian. */
  public static long placeId(final String placeKey) {
    final byte[] h = Sha256.digest(placeKey.getBytes(StandardCharsets.UTF_8));
    long v = 0;
    for (int i = 0; i < 8; i++)
      v = (v << 8) | (h[i] & 0xff);
    return v;
  }

  /** The 7-character geohash ({@code Record.geoCell}), null for an unknown point. */
  public static String geohash7(final double lat, final double lon) {
    if (!Double.isFinite(lat) || !Double.isFinite(lon) || (lat == 0 && lon == 0))
      return null;
    return geohash(lat, lon, 7);
  }

  public static String geohash(final double lat, final double lon, final int precision) {
    double latLo = -90, latHi = 90, lonLo = -180, lonHi = 180;
    boolean even = true;
    int bit = 0, ch = 0;
    final char[] out = new char[precision];
    int n = 0;
    while (n < precision) {
      if (even) {
        final double mid = (lonLo + lonHi) / 2;
        if (lon >= mid) {
          ch = (ch << 1) | 1;
          lonLo = mid;
        } else {
          ch <<= 1;
          lonHi = mid;
        }
      } else {
        final double mid = (latLo + latHi) / 2;
        if (lat >= mid) {
          ch = (ch << 1) | 1;
          latLo = mid;
        } else {
          ch <<= 1;
          latHi = mid;
        }
      }
      even = !even;
      if (++bit == 5) {
        out[n++] = BASE32.charAt(ch);
        bit = 0;
        ch = 0;
      }
    }
    return new String(out);
  }

  private static String placeComponent(final String raw) {
    if (raw == null)
      return "";
    return JsText.collapseWhitespace(JsText.lower(JsText.trim(raw.replace(':', ' '))));
  }
}
