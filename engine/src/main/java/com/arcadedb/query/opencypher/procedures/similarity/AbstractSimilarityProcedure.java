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
package com.arcadedb.query.opencypher.procedures.similarity;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.index.vector.VectorUtils;
import com.arcadedb.query.opencypher.procedures.algo.AbstractAlgoProcedure;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.similarity.Stylometry;
import com.arcadedb.utility.RidBitmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared input handling of the {@code similarity.*} procedures (docs/spork/06-stylometry-perceptual-hash.md): record
 * lists, option values, and reading hashes, quality scores and style vectors from records.
 */
abstract class AbstractSimilarityProcedure extends AbstractAlgoProcedure {
  static final String HAMMING = "hamming";
  static final String COSINE  = "cosine";

  /** The style a record gets from a text property: the n-gram profile, or Recon's 24-d vector. */
  enum StyleMethod {PROFILE, RECON}

  /** Default distances: about 12% of the bits, the usual near-duplicate cut for pHash (8 of 64) and PDQ (31 of 256). */
  static int defaultMaxDistance(final int bits) {
    return bits <= 64 ? 8 : 31;
  }

  /**
   * The existing records of a list of records, RIDs or RID strings, in order and without repeats, or of a saved selection
   * when the argument is its name (docs/spork/03-f1-selections-design.md), in RID order. Deleted records are skipped,
   * because a saved cluster can go stale.
   */
  List<Document> extractRecords(final Object arg, final String paramName, final Database database) {
    if (arg instanceof String selectionName)
      return selectionRecords(selectionName, paramName, database);
    if (!(arg instanceof Collection<?> items))
      throw new IllegalArgumentException(
          getName() + "(): " + paramName + " must be a list of nodes or RIDs, or the name of a saved selection");
    final Map<RID, Document> records = new LinkedHashMap<>();
    for (final Object item : items) {
      final RID rid = switch (item) {
        case null -> throw new IllegalArgumentException(getName() + "(): " + paramName + " holds a null");
        case Identifiable identifiable -> identifiable.getIdentity();
        case Result result when result.isElement() -> result.getElement().get().getIdentity();
        case String s -> parseRid(s, paramName);
        default -> throw new IllegalArgumentException(
            getName() + "(): " + paramName + " must hold nodes or RIDs, got " + item.getClass().getSimpleName());
      };
      if (rid == null || records.containsKey(rid))
        continue;
      final Record record;
      try {
        record = item instanceof Document d ? d : database.lookupByRID(rid, true);
      } catch (final RecordNotFoundException e) {
        continue;
      }
      if (!(record instanceof Document document))
        throw new IllegalArgumentException(getName() + "(): " + paramName + " holds " + rid + ", which is not a record");
      records.put(rid, document);
    }
    return new ArrayList<>(records.values());
  }

  private List<Document> selectionRecords(final String name, final String paramName, final Database database) {
    final RidBitmap members = new SelectionStore(database, getName()).require(name).members();
    final List<Document> records = new ArrayList<>((int) Math.min(members.cardinality(), Integer.MAX_VALUE));
    final Iterator<RID> it = members.iterator(database);
    while (it.hasNext()) {
      final RID rid = it.next();
      final Record record;
      try {
        record = database.lookupByRID(rid, true);
      } catch (final RecordNotFoundException e) {
        continue;
      }
      if (!(record instanceof Document document))
        throw new IllegalArgumentException(
            getName() + "(): selection '" + name + "' (" + paramName + ") holds " + rid + ", which is not a record");
      records.add(document);
    }
    return records;
  }

  private RID parseRid(final String s, final String paramName) {
    try {
      return new RID(s.trim());
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException(getName() + "(): " + paramName + " holds '" + s + "', which is not a RID", e);
    }
  }

  String requiredString(final Map<String, Object> config, final String key) {
    final String value = optionalString(config, key);
    if (value == null)
      throw new IllegalArgumentException(getName() + "(): option '" + key + "' is required");
    return value;
  }

  String optionalString(final Map<String, Object> config, final String key) {
    final Object value = config == null ? null : config.get(key);
    if (value == null)
      return null;
    if (!(value instanceof String s) || s.isBlank())
      throw new IllegalArgumentException(getName() + "(): " + key + " must be a non-empty string, got " + value);
    return s;
  }

  int intOption(final Map<String, Object> config, final String key, final int defaultValue, final int minimum) {
    final Object value = config == null ? null : config.get(key);
    if (value == null)
      return defaultValue;
    if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()))
      throw new IllegalArgumentException(getName() + "(): " + key + " must be an integer, got " + value);
    return extractInt(n, key, minimum);
  }

  double doubleOption(final Map<String, Object> config, final String key, final double defaultValue, final double min,
      final double max) {
    final Object value = config == null ? null : config.get(key);
    if (value == null)
      return defaultValue;
    if (!(value instanceof Number n) || Double.isNaN(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
      throw new IllegalArgumentException(getName() + "(): " + key + " must be a number between " + min + " and " + max + ", got " + value);
    return n.doubleValue();
  }

  StyleMethod styleMethod(final Map<String, Object> config) {
    final String value = optionalString(config, "styleMethod");
    if (value == null || value.equals("profile"))
      return StyleMethod.PROFILE;
    if (value.equals("recon"))
      return StyleMethod.RECON;
    throw new IllegalArgumentException(getName() + "(): styleMethod must be 'profile' or 'recon', got '" + value + "'");
  }

  /** The hashes a record holds in {@code property}, without those of images below {@code minQuality}. */
  long[][] readHashes(final Document record, final String property, final String qualityProperty, final int minQuality) {
    final long[][] hashes;
    try {
      hashes = BitHash.parseAll(record.get(property));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          getName() + "(): " + record.getIdentity() + "." + property + " is not a hash: " + e.getMessage());
    }
    if (qualityProperty == null || hashes.length == 0)
      return hashes;
    final Object quality = record.get(qualityProperty);
    if (quality == null)
      return new long[0][];
    if (quality instanceof Number n)
      return n.doubleValue() >= minQuality ? hashes : new long[0][];
    if (quality instanceof List<?> scores && scores.size() == hashes.length) {
      final List<long[]> kept = new ArrayList<>(hashes.length);
      for (int i = 0; i < hashes.length; i++)
        if (scores.get(i) instanceof Number n && n.doubleValue() >= minQuality)
          kept.add(hashes[i]);
      return kept.toArray(new long[0][]);
    }
    throw new IllegalArgumentException(getName() + "(): " + record.getIdentity() + "." + qualityProperty
        + " must be a number or a list of numbers parallel to " + property);
  }

  /**
   * The style vector of a record: the vector stored in {@code property}, or the style of the text stored there. Null when
   * the record has none, or its text has no style (all zeros).
   */
  float[] readStyle(final Document record, final String property, final StyleMethod method) {
    final Object value = record.get(property);
    if (value == null)
      return null;
    final float[] v;
    if (value instanceof String text)
      v = method == StyleMethod.RECON ? Stylometry.reconStyleVectorFloat(text) : Stylometry.profile(text, 256);
    else
      try {
        v = VectorUtils.toFloatArray(value);
      } catch (final RuntimeException e) {
        throw new IllegalArgumentException(
            getName() + "(): " + record.getIdentity() + "." + property + " is neither a text nor a vector");
      }
    for (final float x : v)
      if (x != 0)
        return v;
    return null;
  }
}
