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
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.similarity.HammingBands;
import com.arcadedb.utility.RidHashSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: similarity.hash.neighbors(hash, options)
 * <p>
 * Every record of a type whose perceptual hash is within a Hamming distance of {@code hash}, closest first
 * (docs/spork/06-stylometry-perceptual-hash.md). {@code hash} may be several hashes (the 8 dihedral hashes of
 * {@code image.perceptualHash(img, 'pdq', true)}, or the hashes of a record's images): a record's distance is then the
 * smallest over every pair.
 * </p>
 * <p>
 * With {@code bandsProperty} the search runs on a {@code BY ITEM} index over the band keys the records store
 * ({@code hamming.bandKeys(hash, bands)}, the same {@code bands}): it looks up the probe keys of every query hash, which
 * by the pigeonhole principle reach every hash within the distance, and verifies each candidate with the full distance. A
 * type without such an index is refused, unless {@code scan: true} asks for a full scan.
 * </p>
 * <p>
 * Example:
 * <pre>
 * CALL similarity.hash.neighbors($pdq, {type: 'Image', property: 'pdq', bandsProperty: 'pdqBands', bands: 16, maxDistance: 31})
 * YIELD node, distance
 * </pre>
 * </p>
 */
public class SimilarityHashNeighbors extends AbstractSimilarityProcedure {
  public static final String NAME = "similarity.hash.neighbors";

  private static final Set<String> KEYS = Set.of("type", "property", "bandsProperty", "bands", "maxDistance", "limit", "maxKeys",
      "maxCandidates", "scan", "qualityProperty", "minQuality");

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getMinArgs() {
    return 2;
  }

  @Override
  public int getMaxArgs() {
    return 2;
  }

  @Override
  public String getDescription() {
    return "Finds the records whose perceptual hash is within a Hamming distance of a hash, through a band-key index";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("node", "distance", "similarity", "hash");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Database database = context.getDatabase();
    final Map<String, Object> config = extractConfig(args[1], "options", KEYS);
    final String typeName = requiredString(config, "type");
    final String property = requiredString(config, "property");
    final String bandsProperty = optionalString(config, "bandsProperty");
    final boolean scan = Boolean.TRUE.equals(config.get("scan"));
    final String qualityProperty = optionalString(config, "qualityProperty");
    final int minQuality = intOption(config, "minQuality", 50, 0);
    final int limit = intOption(config, "limit", 25, 0);
    final int maxKeys = intOption(config, "maxKeys", 100_000, 1);
    final int maxCandidates = intOption(config, "maxCandidates", 100_000, 1);

    final long[][] queries;
    try {
      queries = BitHash.parseAll(args[0]);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(getName() + "(): hash: " + e.getMessage(), e);
    }
    if (queries.length == 0)
      throw new IllegalArgumentException(getName() + "(): hash cannot be null or empty");
    final int words = queries[0].length;
    for (final long[] q : queries)
      if (q.length != words)
        throw new IllegalArgumentException(getName() + "(): the query hashes do not all have the same width");
    final int maxDistance = intOption(config, "maxDistance", defaultMaxDistance(words * 64), 0);

    if (!database.getSchema().existsType(typeName))
      throw new IllegalArgumentException(getName() + "(): type '" + typeName + "' does not exist");
    final DocumentType type = database.getSchema().getType(typeName);
    final WorkGuard guard = newWorkGuard(context);

    final Map<RID, Object[]> matches = new LinkedHashMap<>();
    if (bandsProperty != null) {
      if (!(config.get("bands") instanceof Number))
        throw new IllegalArgumentException(getName() + "(): bands is required with bandsProperty, the number the keys were made with");
      final int bands = intOption(config, "bands", 0, 1);
      final TypeIndex index = bandIndex(type, bandsProperty);
      final RidHashSet seen = new RidHashSet();
      int iteration = 0;
      for (final long[] q : queries) {
        final long[] keys;
        try {
          keys = HammingBands.probeKeys(q, bands, maxDistance, maxKeys);
        } catch (final IllegalArgumentException e) {
          throw new IllegalArgumentException(getName() + "(): " + e.getMessage(), e);
        }
        for (final long key : keys) {
          final IndexCursor cursor = index.get(new Object[] { key });
          while (cursor.hasNext()) {
            guard.checkPeriodically(++iteration);
            final RID rid = cursor.next().getIdentity();
            if (!seen.add(rid))
              continue;
            if (seen.size() > maxCandidates)
              throw new IllegalArgumentException(getName() + "(): more than maxCandidates " + maxCandidates
                  + " candidates: the bands are too narrow for this population, or the distance too large");
            final Record record;
            try {
              record = database.lookupByRID(rid, true);
            } catch (final RecordNotFoundException e) {
              continue;
            }
            verify((Document) record, property, queries, maxDistance, qualityProperty, minQuality, matches);
          }
        }
      }
    } else {
      if (!scan)
        throw new IllegalArgumentException(getName() + "(): give bandsProperty (a BY ITEM index over hamming.bandKeys) or scan: true");
      final Iterator<Record> it = database.iterateType(typeName, true);
      int iteration = 0;
      while (it.hasNext()) {
        guard.checkPeriodically(++iteration);
        verify((Document) it.next(), property, queries, maxDistance, qualityProperty, minQuality, matches);
      }
    }

    final List<Map.Entry<RID, Object[]>> sorted = new ArrayList<>(matches.entrySet());
    sorted.sort((x, y) -> {
      final int c = Integer.compare((Integer) x.getValue()[1], (Integer) y.getValue()[1]);
      return c != 0 ? c : x.getKey().compareTo(y.getKey());
    });
    final int bits = words * 64;
    return sorted.stream().limit(limit).map(e -> {
      final Map<String, Object> row = new LinkedHashMap<>();
      final int distance = (Integer) e.getValue()[1];
      row.put("node", e.getValue()[0]);
      row.put("distance", distance);
      row.put("similarity", 1.0 - (double) distance / bits);
      row.put("hash", e.getValue()[2]);
      return new ResultInternal(row);
    });
  }

  private void verify(final Document record, final String property, final long[][] queries, final int maxDistance,
      final String qualityProperty, final int minQuality, final Map<RID, Object[]> matches) {
    final long[][] hashes = readHashes(record, property, qualityProperty, minQuality);
    int best = Integer.MAX_VALUE;
    long[] bestHash = null;
    for (final long[] h : hashes)
      for (final long[] q : queries) {
        if (h.length != q.length)
          continue;
        final int d = BitHash.hamming(h, q);
        if (d < best) {
          best = d;
          bestHash = h;
        }
      }
    if (best <= maxDistance)
      matches.put(record.getIdentity(), new Object[] { record, best, BitHash.toHex(bestHash) });
  }

  private TypeIndex bandIndex(final DocumentType type, final String bandsProperty) {
    TypeIndex index = type.getPolymorphicIndexByProperties(bandsProperty + " by item");
    if (index == null)
      index = type.getPolymorphicIndexByProperties(bandsProperty);
    if (index == null)
      throw new IllegalArgumentException(getName() + "(): type '" + type.getName() + "' has no index on " + bandsProperty
          + ": CREATE INDEX ON " + type.getName() + " (" + bandsProperty + " BY ITEM) NOTUNIQUE");
    return index;
  }
}
