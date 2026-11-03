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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Document;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HexFormat;

/**
 * The hydrate projection allowlist (01-architecture 7.2): Recon's analysis projection plus {@code textHash}, every field read from
 * the Record document alone, so a hydrate touches no edge, local vertex or text database. The API names are Recon's; the
 * property names are schema v1's ({@code spork-keys} {@code rec.sql}; {@code state}, {@code country} and {@code city} are the
 * display values beside the normalized {@code stateCode}, {@code countryCode} and {@code cityNorm}), and this enum is the one place that
 * maps one to the other.
 * <p>
 * Ad title and text are not here: they live in {@code txt_<p>} behind their own grant and arrive with the text-access slice.
 */
public enum RecordProjection {
  PHONES("phones", "phones", Kind.STRING_LIST),
  EMAILS("emails", "emails", Kind.STRING_LIST),
  SOCIALS("socials", "socials", Kind.STRING_LIST),
  BUSINESS_NAMES("businessNames", "businessNames", Kind.STRING_LIST),
  IMAGE_HASHES("imageHashes", "imageHashes", Kind.STRING_LIST),
  LAT("lat", "lat", Kind.NUMBER),
  LON("lon", "lon", Kind.NUMBER),
  CITY("city", "city", Kind.STRING),
  STATE("state", "state", Kind.STRING),
  COUNTRY("country", "country", Kind.STRING),
  POSTED_AT("postedAt", "tsPosted", Kind.ISO_TIME),
  COLLECTED_AT("collectedAt", "tsCollected", Kind.ISO_TIME),
  AGE("age", "age", Kind.NUMBER),
  SERVICES("services", "services", Kind.STRING_LIST),
  PAYMENT_TYPES("paymentTypes", "paymentTypes", Kind.STRING_LIST),
  CLIENTELE("clientele", "clientele", Kind.STRING_LIST),
  SOURCE("source", "source", Kind.STRING),
  VIEW_COUNT("viewCount", "views", Kind.NUMBER),
  HAS_IMAGES("hasImages", "hasImages", Kind.BOOLEAN),
  STYLE("style", "style", Kind.STRING),
  /** 24 float32 values, 96 B per record: only on request. */
  STYLE_VEC("styleVec", "styleVec", Kind.NUMBER_LIST),
  TEXT_HASH("textHash", "textHash", Kind.HEX),
  PRIORITY_SCORE("priorityScore", "priorityScore", Kind.NUMBER),
  MARKINGS("markings", "markings", Kind.BITS);

  enum Kind {STRING, STRING_LIST, NUMBER, NUMBER_LIST, BOOLEAN, ISO_TIME, HEX, BITS}

  private static final RecordProjection[]  VALUES  = values();
  private static final DateTimeFormatter   ISO     = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final RecordProjection[]  DEFAULT = defaultProjection();

  private final String apiName;
  private final String property;
  private final Kind   kind;

  RecordProjection(final String apiName, final String property, final Kind kind) {
    this.apiName = apiName;
    this.property = property;
    this.kind = kind;
  }

  public String apiName() {
    return apiName;
  }

  public String property() {
    return property;
  }

  /** Every field except {@link #STYLE_VEC}. */
  public static RecordProjection[] defaults() {
    return DEFAULT.clone();
  }

  public static RecordProjection fromApiName(final String apiName) {
    for (final RecordProjection field : VALUES)
      if (field.apiName.equals(apiName))
        return field;
    return null;
  }

  /** Copies this field of {@code record} into {@code out}; an absent or null property, or one of an unexpected type, is omitted. */
  void copy(final Document record, final JSONObject out) {
    final Object value = record.get(property);
    if (value == null)
      return;
    switch (kind) {
    case STRING -> {
      if (value instanceof String s)
        out.put(apiName, s);
    }
    case NUMBER -> {
      if (value instanceof Number n)
        out.put(apiName, n);
    }
    case BOOLEAN -> {
      if (value instanceof Boolean b)
        out.put(apiName, b);
    }
    case ISO_TIME -> {
      if (value instanceof Number n)
        out.put(apiName, ISO.format(Instant.ofEpochMilli(n.longValue())));
    }
    case HEX -> {
      if (value instanceof byte[] bytes)
        out.put(apiName, HexFormat.of().formatHex(bytes));
    }
    case BITS -> {
      if (value instanceof Number n) {
        final JSONArray bits = new JSONArray();
        long mask = n.longValue();
        while (mask != 0) {
          bits.put(Long.numberOfTrailingZeros(mask));
          mask &= mask - 1;
        }
        out.put(apiName, bits);
      }
    }
    case STRING_LIST, NUMBER_LIST -> {
      final JSONArray array = new JSONArray();
      if (value instanceof Collection<?> collection) {
        for (final Object item : collection)
          if (item != null)
            array.put(kind == Kind.STRING_LIST ? item.toString() : item);
      } else if (value instanceof float[] floats) {
        for (final float f : floats)
          array.put(f);
      } else
        return;
      out.put(apiName, array);
    }
    }
  }

  private static RecordProjection[] defaultProjection() {
    final RecordProjection[] fields = new RecordProjection[VALUES.length - 1];
    int i = 0;
    for (final RecordProjection field : VALUES)
      if (field != STYLE_VEC)
        fields[i++] = field;
    return fields;
  }
}
