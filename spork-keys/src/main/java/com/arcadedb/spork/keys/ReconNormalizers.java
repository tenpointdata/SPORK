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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact ports of every identifier normalization Recon runs today, one method per use site, so SPORK can reproduce Recon's
 * current output side by side with its canonical key (01-architecture.md 2.3) in the WS4 shadow-diff harness. Recon has no
 * single normalization: the six phone rules below disagree on 11-digit numbers, short numbers and the posting place, and
 * the board keys business names differently from the cluster and the dossier. Each method names its source; the golden
 * vectors replay every one of them against Recon's own TypeScript.
 * <p>
 * Use {@link SporkKeys} for keys. These are for parity and diffing only.
 */
public final class ReconNormalizers {
  public static final String PHONE         = "phone";
  public static final String EMAIL         = "email";
  public static final String SOCIAL        = "social";
  public static final String BUSINESS_NAME = "businessName";
  public static final String IMAGE_HASH    = "imageHash";

  /** {@code ops/audit.ts} MAX_SUBJECT, MAX_SUBJECT_TOKENS */
  private static final int MAX_SUBJECT        = 200;
  private static final int MAX_SUBJECT_TOKENS = 12;

  /** {@code engine.ts} classifySource: {@code /@[\w.-]+\.\w{2,}/} (Java's \w is ASCII like JS's) */
  private static final Pattern EMAIL_SHAPE = Pattern.compile("@[\\w.-]+\\.\\w{2,}");

  private ReconNormalizers() {
  }

  /**
   * {@code engine.ts} canonPhone(p, r): the last 10 digits; a 7-digit number inherits the representative area code of the
   * record's {@code "City, ST"} (case sensitive). Pass a null city and state for the record-less form ({@code normPhone}).
   * A record always has both (Recon defaults them to "Unknown" and "??"), so one null means no record.
   */
  public static String engineCanonPhone(final String p, final String city, final String state) {
    String d = JsText.digits(p);
    if (d.length() > 10)
      d = d.substring(d.length() - 10);
    if (d.length() == 7 && city != null && state != null) {
      final String area = ReconGeography.areaCodeOfPlace(city, state);
      if (area != null)
        d = area + d;
    }
    return d;
  }

  /** {@code engine.ts} areaCodeOf(p, r): the first 3 digits of a 10-digit canonical phone, else "". */
  public static String engineAreaCodeOf(final String p, final String city, final String state) {
    final String c = engineCanonPhone(p, city, state);
    return c.length() == 10 ? c.substring(0, 3) : "";
  }

  /** {@code engine.ts} clusterKey(by, v, r) for phone, email and businessName; other kinds are returned verbatim. */
  public static String engineClusterKey(final String by, final String v, final String city, final String state) {
    return switch (by) {
      case PHONE -> engineCanonPhone(v, city, state);
      case EMAIL -> JsText.lower(v);
      case BUSINESS_NAME -> JsText.lowerAlnum(v);
      default -> v;
    };
  }

  /**
   * {@code engine.ts} link and pivot keys for email and businessName ({@code v.toLowerCase()}), also the compare keyer for
   * every field but phones. Image hashes link verbatim.
   */
  public static String engineLinkKey(final String v) {
    return JsText.lower(v);
  }

  /** {@code engine.ts} social link key: {@code `${s.platform}:${s.handle}`.toLowerCase()} */
  public static String engineSocialLinkKey(final String platform, final String handle) {
    return JsText.lower(platform + ":" + handle);
  }

  /** {@code compare.ts} normPhone: digits, the last 10 when longer. */
  public static String compareNormPhone(final String p) {
    final String d = JsText.digits(p);
    return d.length() > 10 ? d.substring(d.length() - 10) : d;
  }

  /** {@code ops/board.ts} normalizeAnchor(type, value): the input of the board id ({@code tgt_}). */
  public static String boardNormalizeAnchor(final String type, final String value) {
    final String v = JsText.trim(value);
    if (PHONE.equals(type)) {
      String d = JsText.digits(v);
      if (d.length() == 11 && d.charAt(0) == '1')
        d = d.substring(1);
      return !d.isEmpty() ? d : JsText.lower(v);
    }
    final String s = JsText.collapseWhitespace(JsText.lower(v));
    return SOCIAL.equals(type) ? JsText.stripLeadingAt(s) : s;
  }

  /** {@code ops/dossier.ts} identKey(type, value): the deconfliction key, {@code type:normalized}. */
  public static String dossierIdentKey(final String type, final String value) {
    final String v = JsText.trim(value);
    switch (type) {
    case PHONE: {
      final String d = JsText.digits(v);
      return "phone:" + (d.length() >= 7 ? (d.length() > 10 ? d.substring(d.length() - 10) : d) : JsText.lower(v));
    }
    case BUSINESS_NAME:
      return "businessName:" + JsText.lowerAlnum(v);
    case IMAGE_HASH:
      return "imageHash:" + v;
    default:
      return type + ":" + JsText.lower(v);
    }
  }

  /** {@code ops/feed.ts} phoneKey(raw): digits with a US country code dropped; under 7 digits the lowercased token. */
  public static String feedPhoneKey(final String raw) {
    final String t = JsText.trim(raw);
    String digits = JsText.digits(t);
    if (digits.length() < 7)
      return JsText.lower(t);
    if (digits.length() == 11 && digits.charAt(0) == '1')
      digits = digits.substring(1);
    return digits;
  }

  /** {@code ops/audit.ts} intakeSubject(tokens): the stable audit subject of an intake. */
  public static String auditIntakeSubject(final Collection<String> tokens) {
    final Set<String> unique = new LinkedHashSet<>();
    for (final String raw : tokens) {
      final String n = intakeToken(raw);
      if (!n.isEmpty())
        unique.add(n);
    }
    final List<String> norm = new ArrayList<>(unique);
    norm.sort(null); // JS default sort: UTF-16 code unit order, which is String.compareTo
    final StringBuilder shown = new StringBuilder();
    for (int i = 0; i < norm.size() && i < MAX_SUBJECT_TOKENS; i++) {
      if (i > 0)
        shown.append(", ");
      shown.append(norm.get(i));
    }
    final String more = norm.size() > MAX_SUBJECT_TOKENS ? " (+" + (norm.size() - MAX_SUBJECT_TOKENS) + " more)" : "";
    final int max = MAX_SUBJECT - more.length();
    return (shown.length() > max ? shown.substring(0, max) : shown.toString()) + more;
  }

  private static String intakeToken(final String raw) {
    final String t = JsText.trim(JsText.controlsToSpace(raw));
    String digits = JsText.digits(t);
    if (!isPhoneShaped(t) || digits.length() < 7)
      return JsText.collapseWhitespace(JsText.lower(t));
    if (digits.length() == 11 && digits.charAt(0) == '1' && !digits.equals(t))
      digits = digits.substring(1);
    return digits;
  }

  /** {@code engine.ts} classifySource(t): 'ad', 'email', 'phone' or 'social'. */
  public static String classifySource(final String t) {
    if (isRid(t))
      return "ad";
    if (t.indexOf('@') >= 0)
      return EMAIL_SHAPE.matcher(t).find() ? "email" : SOCIAL;
    if (JsText.digits(t).length() >= 7 && isPhoneShaped(t))
      return PHONE;
    return SOCIAL;
  }

  /** {@code /^\d+:\d+$/} */
  static boolean isRid(final String t) {
    final int colon = t.indexOf(':');
    if (colon <= 0 || colon == t.length() - 1)
      return false;
    for (int i = 0; i < t.length(); i++)
      if (i != colon && !JsText.isAsciiDigit(t.charAt(i)))
        return false;
    return true;
  }

  /**
   * {@code /^[\d\s()+.-]+$/} (audit) and {@code /^[\d().+\s-]+$/} (classifySource): the same character
   * set, a non-empty run of digits, JS whitespace and {@code ( ) + . -}.
   */
  private static boolean isPhoneShaped(final String t) {
    if (t.isEmpty())
      return false;
    for (int i = 0; i < t.length(); i++) {
      final char c = t.charAt(i);
      if (!JsText.isAsciiDigit(c) && !JsText.isWhitespace(c) && c != '(' && c != ')' && c != '+' && c != '.' && c != '-')
        return false;
    }
    return true;
  }
}
