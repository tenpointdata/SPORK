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

import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.similarity.HammingIndex;
import com.arcadedb.similarity.Stylometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Procedure: similarity.cluster(records, options)
 * <p>
 * Groups a set of records by near-duplicate images (Hamming distance between perceptual hashes) or by writing style
 * (cosine between style vectors, or between the styles of their texts), docs/spork/06-stylometry-perceptual-hash.md. A
 * record may hold several hashes (one per image): two records link when any pair of their hashes is within the distance.
 * {@code records} is a list of records or RIDs, or the name of a saved selection ({@code selection.save}). Two linkages:
 * </p>
 * <ul>
 *   <li>{@code single}: connected components of the links. The near-duplicate default: re-posts of re-posts chain into
 *   one group, which is what a reused photo is.</li>
 *   <li>{@code leader}: Recon's greedy authorship clustering ({@code analysis/style.ts}): in input order, a record not yet
 *   taken leads a group of every later untaken record linked to it. No chaining; the style default.</li>
 * </ul>
 * <p>
 * The groups are what identity resolution consumes as extra identifiers: write each group as a node ({@code ImageCluster},
 * {@code StyleCluster}) linked to its members, and {@code identity.compare}, {@code cohesion} and {@code candidates}
 * score it like any other kind, with its own m, frequency and hazard. {@code key} is stable for that: the smallest
 * hash of the group (hamming) or its smallest RID (cosine).
 * </p>
 * <p>
 * Example:
 * <pre>
 * MATCH (r:Record) WHERE r.tsPosted &gt; $since WITH collect(r) AS ads
 * CALL similarity.cluster(ads, {property: 'imagePhashes', metric: 'hamming', qualityProperty: 'imageQuality'})
 * YIELD key, size, members
 * </pre>
 * </p>
 */
public class SimilarityCluster extends AbstractSimilarityProcedure {
  public static final String NAME = "similarity.cluster";

  private static final Set<String> KEYS = Set.of("property", "metric", "threshold", "linkage", "styleMethod", "qualityProperty",
      "minQuality", "minSize", "limit", "memberLimit", "maxRecords");

  static final int DEFAULT_MAX_RECORDS_HAMMING = 1_000_000;
  static final int DEFAULT_MAX_RECORDS_COSINE  = 10_000;

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
    return "Groups records by near-duplicate images (Hamming distance of perceptual hashes) or by writing style (cosine)";
  }

  @Override
  public List<String> getYieldFields() {
    return List.of("clusterId", "key", "size", "members", "links", "cohesion");
  }

  @Override
  public Stream<Result> execute(final Object[] args, final Result inputRow, final CommandContext context) {
    validateArgs(args);
    final Map<String, Object> config = extractConfig(args[1], "options", KEYS);
    final String property = requiredString(config, "property");
    final String metric = requiredString(config, "metric");
    if (!metric.equals(HAMMING) && !metric.equals(COSINE))
      throw new IllegalArgumentException(getName() + "(): metric must be 'hamming' or 'cosine', got '" + metric + "'");
    final boolean hamming = metric.equals(HAMMING);
    final String linkage = config.get("linkage") == null ? (hamming ? "single" : "leader") : requiredString(config, "linkage");
    if (!linkage.equals("single") && !linkage.equals("leader"))
      throw new IllegalArgumentException(getName() + "(): linkage must be 'single' or 'leader', got '" + linkage + "'");
    final String qualityProperty = optionalString(config, "qualityProperty");
    if (qualityProperty != null && !hamming)
      throw new IllegalArgumentException(getName() + "(): qualityProperty applies to the hamming metric only");
    final int minQuality = intOption(config, "minQuality", 50, 0);
    final int minSize = intOption(config, "minSize", 2, 1);
    final int limit = intOption(config, "limit", Integer.MAX_VALUE, 0);
    final int memberLimit = intOption(config, "memberLimit", 1000, 0);
    final int maxRecords = intOption(config, "maxRecords", hamming ? DEFAULT_MAX_RECORDS_HAMMING : DEFAULT_MAX_RECORDS_COSINE, 1);
    final StyleMethod styleMethod = styleMethod(config);

    final List<Document> records = extractRecords(args[0], "records", context.getDatabase());
    if (records.size() > maxRecords)
      throw new IllegalArgumentException(
          getName() + "(): " + records.size() + " records is more than maxRecords " + maxRecords + (hamming ? "" :
              ": cosine compares every pair, use an LSM_VECTOR index for corpus-wide style search"));
    final WorkGuard guard = newWorkGuard(context);

    final Groups groups;
    if (hamming) {
      final List<long[]> hashes = new ArrayList<>();
      final List<Integer> owners = new ArrayList<>();
      for (int m = 0; m < records.size(); m++) {
        guard.checkPeriodically(m);
        for (final long[] h : readHashes(records.get(m), property, qualityProperty, minQuality)) {
          hashes.add(h);
          owners.add(m);
        }
      }
      final long[][] hashArray = hashes.toArray(new long[0][]);
      final int[] owner = owners.stream().mapToInt(Integer::intValue).toArray();
      final int bits = hashArray.length == 0 ? 64 : hashArray[0].length * 64;
      final Object thresholdOption = config.get("threshold");
      final int maxDistance = thresholdOption == null ? defaultMaxDistance(bits) : intOption(config, "threshold", 0, 0);
      try {
        groups = clusterHashes(records.size(), hashArray, owner, maxDistance, bits, linkage.equals("leader"), guard);
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(getName() + "(): " + e.getMessage(), e);
      }
    } else {
      final double threshold = doubleOption(config, "threshold", 0.95, -1, 1);
      final float[][] vectors = new float[records.size()][];
      for (int m = 0; m < records.size(); m++) {
        guard.checkPeriodically(m);
        vectors[m] = readStyle(records.get(m), property, styleMethod);
      }
      try {
        groups = clusterVectors(vectors, threshold, linkage.equals("leader"), guard);
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(getName() + "(): " + e.getMessage(), e);
      }
    }
    return rows(groups, records, minSize, limit, memberLimit).stream().map(ResultInternal::new);
  }

  /** The grouping of the records: {@code group[m]} is the group of record m, -1 for a record with no hash or vector. */
  private record Groups(int[] group, int count, int[] links, double[] similaritySum, String[] keys) {
  }

  private static Groups clusterHashes(final int records, final long[][] hashes, final int[] owner, final int maxDistance,
      final int bits, final boolean leader, final WorkGuard guard) {
    final HammingIndex index = new HammingIndex(hashes, maxDistance);
    final int[] parent = new int[records];
    Arrays.fill(parent, -1);
    for (final int m : owner)
      parent[m] = m;
    final List<int[]> linkPairs = new ArrayList<>();
    final int[] iteration = { 0 };

    if (leader) {
      final int[] firstHash = new int[records + 1];
      Arrays.fill(firstHash, hashes.length);
      for (int i = hashes.length - 1; i >= 0; i--)
        firstHash[owner[i]] = i;
      final boolean[] taken = new boolean[records];
      for (int m = 0; m < records; m++) {
        if (parent[m] < 0 || taken[m])
          continue;
        taken[m] = true;
        final int lead = m;
        for (int i = firstHash[m]; i < hashes.length && owner[i] == m; i++)
          index.forEachNeighbour(hashes[i], (j, d) -> {
            guard.checkPeriodically(++iteration[0]);
            final int other = owner[j];
            if (other > lead && !taken[other]) {
              taken[other] = true;
              parent[other] = lead;
              linkPairs.add(new int[] { lead, other, d });
            }
          });
      }
    } else
      index.forEachPair((i, j, d) -> {
        guard.checkPeriodically(++iteration[0]);
        if (owner[i] != owner[j]) {
          union(parent, owner[i], owner[j]);
          linkPairs.add(new int[] { owner[i], owner[j], d });
        }
      });

    final Groups groups = number(parent);
    for (final int[] p : linkPairs) {
      final int g = groups.group[p[0]];
      groups.links[g]++;
      groups.similaritySum[g] += 1.0 - (double) p[2] / bits;
    }
    for (int i = 0; i < hashes.length; i++) {
      final int g = groups.group[owner[i]];
      final String hex = BitHash.toHex(hashes[i]);
      if (groups.keys[g] == null || hex.compareTo(groups.keys[g]) < 0)
        groups.keys[g] = hex;
    }
    return groups;
  }

  private static Groups clusterVectors(final float[][] vectors, final double threshold, final boolean leader,
      final WorkGuard guard) {
    final int n = vectors.length;
    final int[] parent = new int[n];
    for (int m = 0; m < n; m++)
      parent[m] = vectors[m] == null ? -1 : m;
    final boolean[] taken = new boolean[n];
    final List<double[]> linkPairs = new ArrayList<>();
    int iteration = 0;
    for (int i = 0; i < n; i++) {
      if (vectors[i] == null || (leader && taken[i]))
        continue;
      taken[i] = true;
      for (int j = i + 1; j < n; j++) {
        guard.checkPeriodically(++iteration);
        if (vectors[j] == null || (leader && taken[j]))
          continue;
        final double c = Stylometry.cosine(vectors[i], vectors[j]);
        if (c < threshold)
          continue;
        if (leader) {
          taken[j] = true;
          parent[j] = i;
        } else
          union(parent, i, j);
        linkPairs.add(new double[] { i, c });
      }
    }
    final Groups groups = number(parent);
    for (final double[] p : linkPairs) {
      final int g = groups.group[(int) p[0]];
      groups.links[g]++;
      groups.similaritySum[g] += p[1];
    }
    return groups;
  }

  private static int find(final int[] parent, int x) {
    while (parent[x] != x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  }

  private static void union(final int[] parent, final int a, final int b) {
    final int ra = find(parent, a);
    final int rb = find(parent, b);
    if (ra != rb)
      parent[Math.max(ra, rb)] = Math.min(ra, rb);
  }

  /** Numbers the groups 0..count-1 by their smallest record. */
  private static Groups number(final int[] parent) {
    final int[] group = new int[parent.length];
    final int[] groupOfRoot = new int[parent.length];
    Arrays.fill(groupOfRoot, -1);
    int count = 0;
    for (int m = 0; m < parent.length; m++) {
      if (parent[m] < 0) {
        group[m] = -1;
        continue;
      }
      final int root = find(parent, m);
      if (groupOfRoot[root] < 0)
        groupOfRoot[root] = count++;
      group[m] = groupOfRoot[root];
    }
    return new Groups(group, count, new int[count], new double[count], new String[count]);
  }

  private static List<Map<String, Object>> rows(final Groups groups, final List<Document> records, final int minSize,
      final int limit, final int memberLimit) {
    final int[] sizes = new int[groups.count];
    for (final int g : groups.group)
      if (g >= 0)
        sizes[g]++;
    final List<List<RID>> members = new ArrayList<>(groups.count);
    for (int g = 0; g < groups.count; g++)
      members.add(new ArrayList<>());
    final RID[] smallest = new RID[groups.count];
    for (int m = 0; m < records.size(); m++) {
      final int g = groups.group[m];
      if (g < 0 || sizes[g] < minSize)
        continue;
      final RID rid = records.get(m).getIdentity();
      if (members.get(g).size() < memberLimit)
        members.get(g).add(rid);
      if (smallest[g] == null || rid.compareTo(smallest[g]) < 0)
        smallest[g] = rid;
    }

    final List<Integer> kept = new ArrayList<>();
    for (int g = 0; g < groups.count; g++)
      if (sizes[g] >= minSize)
        kept.add(g);
    kept.sort((x, y) -> sizes[x] != sizes[y] ? Integer.compare(sizes[y], sizes[x]) : key(groups, smallest, x).compareTo(key(groups, smallest, y)));

    final List<Map<String, Object>> rows = new ArrayList<>(Math.min(kept.size(), limit));
    for (int i = 0; i < kept.size() && rows.size() < limit; i++) {
      final int g = kept.get(i);
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("clusterId", rows.size());
      row.put("key", key(groups, smallest, g));
      row.put("size", sizes[g]);
      row.put("members", members.get(g));
      row.put("links", groups.links[g]);
      row.put("cohesion", groups.links[g] == 0 ? 1.0 : groups.similaritySum[g] / groups.links[g]);
      rows.add(row);
    }
    return rows;
  }

  private static String key(final Groups groups, final RID[] smallest, final int g) {
    return groups.keys[g] != null ? groups.keys[g] : smallest[g].toString();
  }
}
