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
package com.arcadedb.query.structured;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One filter of a {@link StructuredQuery}: the property equals one of {@code values}. On a LIST property it holds when any item
 * equals one of them, as a {@code BY ITEM} index reads it. Numbers compare by value whatever their boxed class, so an
 * {@code Integer} facet matches a {@code LONG} property.
 */
public final class Facet {
  /** An age band is a handful of values; this bounds what one facet may open as cursors. */
  public static final int MAX_VALUES = 1_024;

  private final String   property;
  private final Object[] values;

  private Facet(final String property, final Object[] values) {
    if (property == null || property.isBlank())
      throw new IllegalArgumentException("A facet needs a property name");
    if (values == null || values.length == 0)
      throw new IllegalArgumentException("Facet on '" + property + "' needs at least one value");
    if (values.length > MAX_VALUES)
      throw new IllegalArgumentException("Facet on '" + property + "' has " + values.length + " values, more than " + MAX_VALUES);
    for (final Object v : values)
      if (v == null)
        throw new IllegalArgumentException("Facet on '" + property + "' has a null value");
    this.property = property;
    this.values = distinct(values);
  }

  /** Each value once (by normalized value): a repeated value would open a second cursor on the same entries. */
  private static Object[] distinct(final Object[] values) {
    final LinkedHashMap<Object, Object> byNormalized = new LinkedHashMap<>(values.length * 2);
    for (final Object v : values)
      byNormalized.putIfAbsent(normalize(v), v);
    return byNormalized.size() == values.length ? values : byNormalized.values().toArray();
  }

  public static Facet eq(final String property, final Object value) {
    return new Facet(property, new Object[] { value });
  }

  public static Facet in(final String property, final Object... values) {
    return new Facet(property, values == null ? null : values.clone());
  }

  /** Every integer from {@code from} to {@code to}, both included: one cursor each when the facet is walked. */
  public static Facet intRange(final String property, final int from, final int to) {
    if (from > to)
      throw new IllegalArgumentException("Facet on '" + property + "': range " + from + ".." + to + " is empty");
    if ((long) to - from + 1 > MAX_VALUES)
      throw new IllegalArgumentException("Facet on '" + property + "': range " + from + ".." + to + " has more than " + MAX_VALUES + " values");
    final Object[] values = new Object[to - from + 1];
    for (int i = 0; i < values.length; i++)
      values[i] = from + i;
    return new Facet(property, values);
  }

  public String property() {
    return property;
  }

  public List<Object> values() {
    return Arrays.asList(values);
  }

  Object[] valuesArray() {
    return values;
  }

  /** Whether a record's value of the property satisfies the facet. */
  boolean matches(final Object actual) {
    if (actual instanceof final Collection<?> items) {
      for (final Object item : items)
        if (matchesOne(item))
          return true;
      return false;
    }
    return matchesOne(actual);
  }

  private boolean matchesOne(final Object actual) {
    if (actual == null)
      return false;
    final Object normalized = normalize(actual);
    for (final Object v : values)
      if (normalize(v).equals(normalized))
        return true;
    return false;
  }

  /** Integral numbers as {@code Long}, so an index key, a record value and a facet value of different boxed classes compare. */
  static Object normalize(final Object value) {
    if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
      return ((Number) value).longValue();
    return value;
  }

  @Override
  public String toString() {
    return property + " IN " + Arrays.toString(values);
  }
}
