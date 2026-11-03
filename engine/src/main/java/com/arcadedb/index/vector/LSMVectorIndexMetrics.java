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
package com.arcadedb.index.vector;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Holds all metrics and counters for LSMVectorIndex operations. The counters are {@code LongAdder}s (SPORK S10): a search
 * bumps two of them, and an {@code AtomicLong} made every search of every thread write the same two cache lines.
 * Provides thread-safe tracking of:
 * - Operation counts (search, insert, rebuild, compaction)
 * - Vector fetch sources (quantized, documents, graph)
 * - Latency tracking (search, insert)
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
class LSMVectorIndexMetrics {

  // Operation counters
  private final LongAdder searchOperations = new LongAdder();
  private final LongAdder insertOperations = new LongAdder();
  private final LongAdder graphRebuildCount = new LongAdder();
  private final LongAdder compactionCount = new LongAdder();
  // Queries the graph search could not fill, which then walked every ordinal. A full scan per query is the most
  // expensive thing this index does, and until now it was only a log line (issue #5558). Counts the plain k-NN path
  // only, which is the only one with a fallback to count: the grouped and PQ paths deliberately have none.
  private final LongAdder bruteForceScans = new LongAdder();
  // Queries answered by the pre-filter plan (issue #6502, extended to the groupBy and PQ-approximate paths by issue
  // #6514): the RID allow-list was narrow enough that scoring it directly was chosen up front, in place of the HNSW
  // graph walk. Unlike bruteForceScans this is not a fallback - it is the cheaper of the two plans available for a
  // given search path (exact on the plain and groupBy paths, PQ-approximate on the PQ path), picked before any
  // graph traversal ran. Shared across all three paths; a query on any of them can bump this counter.
  private final LongAdder preFilterSearches = new LongAdder();
  // Graph walks set up to score on the PQ codes fused into the nodes of the graph file (jvector FusedPQ, the fusedPQ index
  // setting), on the exact path and on the ungrouped PQ path that SQL and openCypher take alike (issue #10713; groupBy keeps
  // its exact walk). Counted when the walk's score provider is built, so a walk that then fails is still counted. Zero on an
  // index whose graph file carries no fused codes, so it says whether the bytes the setting costs on disk are read.
  private final LongAdder fusedPQWalks = new LongAdder();
  // Grouped searches (vector.neighbors with groupBy) that ran out of candidate budget before they could open `limit`
  // distinct groups, so the caller got a correct but short answer. Finding the limit-th nearest group costs however
  // many candidates the data puts between it and the query, which no fixed budget can guarantee (issue #5761), so
  // this is the signal to raise efSearch on the index or the query.
  //
  // Issue #6559: this counter is deliberately narrow - it fires ONLY when the walk stopped because the candidate
  // budget ran out while the graph still had more to give. It must NOT fire when the reachable graph (or, on the
  // pre-filter plan, the allow-list) was exhausted on its own: a corpus that genuinely holds fewer than `limit`
  // distinct groupBy values, or an allow-list that cannot reach `limit` groups, trips that on every query forever and
  // "raise efSearch" cannot manufacture a group that does not exist. See groupedSearchesGroupsUnavailable for that
  // case.
  private final LongAdder groupedSearchesShortOfLimit = new LongAdder();
  // Grouped searches that came back with fewer than `limit` distinct groups because the reachable graph (or the
  // allow-list, on the pre-filter plan) ran out on its own - not because a fixed candidate budget cut the walk short.
  // Raising efSearch cannot help here: there is nothing further to find. A steady, non-zero rate is expected and
  // requires no action when the groupBy cardinality is genuinely below `limit`. It is also the signal issue #6559
  // item 4 asked for: a grouped query that comes up short for a reason other than "the cap is doing its job" - e.g. a
  // degraded graph after a corrupted rebuild (issue #3722) - shows up here rather than nowhere at all, since a
  // grouped search deliberately skips the brute-force fallback the plain k-NN path uses to self-heal from that case.
  private final LongAdder groupedSearchesGroupsUnavailable = new LongAdder();
  // Grouped searches that merged at least one row out of the delta buffer into their answer (issue #6501). Before
  // that issue the grouped path skipped the buffer outright, so a groupBy query silently answered from the corpus as
  // of the last graph rebuild while the same query without groupBy returned the newer rows; the merge is what closed
  // that, and this counter is what makes it visible. A number that tracks the query rate means the graph is
  // persistently behind the write rate and every grouped query is paying a linear scan of the buffer for it -
  // deltaVectorsCount says how long that scan is, and a lower vectorIndex.mutationsBeforeRebuild or
  // rebuildGraphRatio is what shortens it.
  private final LongAdder groupedSearchesMergingDelta = new LongAdder();
  // Times a persisted graph was reused on the strength of its node count alone, because it carries no manifest
  // saying which records it was built over (issue #6106) - a graph written by a version older than the manifest, or
  // one restored from a backup, which does not carry the sidecar. Non-zero means this index is still being judged by
  // the weaker comparison; REBUILD INDEX, or any graph persist, writes a manifest and takes it off that path.
  private final LongAdder unverifiedGraphReuses = new LongAdder();
  // Online rebuild cycles declined because the estimated peak footprint did not fit the available heap (issue
  // #6503). Deferring is the intended outcome - the alternative is an OutOfMemoryError - but it is not free: the
  // graph stays as stale as the delta buffer is long, so every query pays a longer brute-force scan over it. A
  // number that keeps climbing is the signal to give the JVM more heap, lower graphBuildCacheMaxHeapPercent, or
  // split the index; one that climbed once and stopped is a transient the next trigger already recovered from.
  private final LongAdder rebuildsDeferredForMemory = new LongAdder();
  // Online rebuilds that were admitted only because the resident on-heap graph was swapped for its persisted
  // on-disk twin, so the build no longer had to fit beside a second full graph (issue #7260). Each one costs
  // searches a page read per visited node until the rebuild publishes its replacement, which is the price of not
  // deferring - and of a delta buffer that grows without bound.
  private final LongAdder residentGraphDemotions = new LongAdder();
  // Searches that had to build this index's graph from scratch and found no JVM-wide rebuild permit free, so they
  // queued for one instead of rebuilding alongside every other index at once (issue #7814). A query that waits is
  // the gate working: the alternative it replaces is N large builds on N request threads, which is what pinned a
  // 39 GB heap at its ceiling and had the node restarted under it. A number that climbs on every reopen says the
  // indexes of this database are all resolving their graphs at once - raising maxConcurrentRebuilds trades heap
  // for that latency, and persisting a graph (REBUILD INDEX before the restart) removes the build entirely.
  private final LongAdder searchRebuildsQueuedForPermit = new LongAdder();
  // Of those, the ones that gave up waiting after rebuildPermitTimeoutMs and built WITHOUT a permit, because a
  // search cannot be answered without a graph (issue #7814). This is the only remaining unbounded case, so a
  // non-zero value means some other index has been holding a permit for at least that timeout - almost always a
  // rebuild that is stuck rather than slow - and concurrent builds were unbounded for as long as it lasted.
  private final LongAdder searchRebuildsWithoutPermit = new LongAdder();
  // Searches whose from-scratch build was small enough to be exempt from the JVM-wide rebuild permit, but which had
  // to take one anyway because the aggregate of small builds ALREADY in flight had spent the process-wide budget
  // (issue #7930). The exemption is a per-build test - a thousand vectors cannot threaten the heap - and says
  // nothing about how many such builds run at once, which is what this bounds: a database of hundreds of small
  // vector indexes reopening together used to start hundreds of concurrent builds. A steady non-zero value means
  // exactly that shape of database, and persisting the graphs (REBUILD INDEX before the restart) removes the builds
  // entirely; a raised maxConcurrentRebuilds widens both the permit and this budget together.
  private final LongAdder smallRebuildsOverBudget = new LongAdder();
  // Times a stale persisted graph (more live vectors than it covers, no deletions) was reused as a prefix instead
  // of being discarded for a synchronous full rebuild on the calling search thread (issue #6655). Each one traded
  // a blocking rebuild sized to the whole index for an immediate answer plus a background rebuild; the gap
  // vectors stay searchable meanwhile through the delta buffer they were queued into.
  private final LongAdder stalePrefixGraphReuses = new LongAdder();
  // Times a persisted graph was reused although some of its nodes answer for vectors that have since been deleted,
  // instead of being discarded for the full rebuild any single tombstone used to force on reopen (issue #7842).
  // Each one traded an O(index size) rebuild on the calling search thread for a load plus a filtered walk; the dead
  // nodes are charged to mutationsSinceRebuild, so the ordinary threshold folds them out on its own schedule.
  private final LongAdder graphReusesWithTombstonedNodes = new LongAdder();
  private final LongAdder graphLoadsFromOrdinalMap = new LongAdder();

  // Vector fetch source tracking
  private final LongAdder vectorFetchFromQuantized = new LongAdder();
  private final LongAdder vectorFetchFromDocuments = new LongAdder();
  private final LongAdder vectorFetchFromGraph = new LongAdder();

  // How many vectors the LAST graph build kept as quantized rows in memory instead of re-reading them from the index
  // pages (issue #10543). 0 when the build held float vectors in its cache, or read everything from the pages. A gauge,
  // not a counter: it describes one build, and the next one replaces it.
  private volatile long graphBuildQuantizedResident = 0L;

  // Latency tracking (cumulative)
  private final LongAdder searchLatencyMs = new LongAdder();
  private final LongAdder insertLatencyMs = new LongAdder();

  // Operation tracking methods

  void incrementSearchOperations() {
    searchOperations.increment();
  }

  void incrementInsertOperations() {
    insertOperations.increment();
  }

  void incrementInsertOperations(final int count) {
    insertOperations.add(count);
  }

  void incrementGraphRebuildCount() {
    graphRebuildCount.increment();
  }

  void incrementCompactionCount() {
    compactionCount.increment();
  }

  void incrementBruteForceScans() {
    bruteForceScans.increment();
  }

  void incrementPreFilterSearches() {
    preFilterSearches.increment();
  }

  void incrementFusedPQWalks() {
    fusedPQWalks.increment();
  }

  void incrementGroupedSearchesShortOfLimit() {
    groupedSearchesShortOfLimit.increment();
  }

  void incrementGroupedSearchesGroupsUnavailable() {
    groupedSearchesGroupsUnavailable.increment();
  }

  void incrementGroupedSearchesMergingDelta() {
    groupedSearchesMergingDelta.increment();
  }

  void incrementUnverifiedGraphReuses() {
    unverifiedGraphReuses.increment();
  }

  void incrementRebuildsDeferredForMemory() {
    rebuildsDeferredForMemory.increment();
  }

  void incrementResidentGraphDemotions() {
    residentGraphDemotions.increment();
  }

  void incrementSearchRebuildsQueuedForPermit() {
    searchRebuildsQueuedForPermit.increment();
  }

  void incrementSearchRebuildsWithoutPermit() {
    searchRebuildsWithoutPermit.increment();
  }

  void incrementSmallRebuildsOverBudget() {
    smallRebuildsOverBudget.increment();
  }

  void incrementStalePrefixGraphReuses() {
    stalePrefixGraphReuses.increment();
  }

  void incrementGraphLoadsFromOrdinalMap() {
    graphLoadsFromOrdinalMap.increment();
  }

  void incrementGraphReusesWithTombstonedNodes() {
    graphReusesWithTombstonedNodes.increment();
  }

  // Vector fetch source tracking methods

  void incrementVectorFetchFromQuantized() {
    vectorFetchFromQuantized.increment();
  }

  void incrementVectorFetchFromDocuments() {
    vectorFetchFromDocuments.increment();
  }

  void incrementVectorFetchFromGraph() {
    vectorFetchFromGraph.increment();
  }

  void setGraphBuildQuantizedResident(final long vectors) {
    graphBuildQuantizedResident = vectors;
  }

  // Latency tracking methods

  void addSearchLatency(final long latencyMs) {
    searchLatencyMs.add(latencyMs);
  }

  void addInsertLatency(final long latencyMs) {
    insertLatencyMs.add(latencyMs);
  }

  // Getters for statistics

  long getSearchOperations() {
    return searchOperations.sum();
  }

  long getInsertOperations() {
    return insertOperations.sum();
  }

  long getGraphRebuildCount() {
    return graphRebuildCount.sum();
  }

  long getCompactionCount() {
    return compactionCount.sum();
  }

  long getBruteForceScans() {
    return bruteForceScans.sum();
  }

  long getPreFilterSearches() {
    return preFilterSearches.sum();
  }

  long getFusedPQWalks() {
    return fusedPQWalks.sum();
  }

  long getGroupedSearchesShortOfLimit() {
    return groupedSearchesShortOfLimit.sum();
  }

  long getGroupedSearchesGroupsUnavailable() {
    return groupedSearchesGroupsUnavailable.sum();
  }

  long getGroupedSearchesMergingDelta() {
    return groupedSearchesMergingDelta.sum();
  }

  long getVectorFetchFromQuantized() {
    return vectorFetchFromQuantized.sum();
  }

  long getVectorFetchFromDocuments() {
    return vectorFetchFromDocuments.sum();
  }

  long getVectorFetchFromGraph() {
    return vectorFetchFromGraph.sum();
  }

  long getGraphBuildQuantizedResident() {
    return graphBuildQuantizedResident;
  }

  long getSearchLatencyMs() {
    return searchLatencyMs.sum();
  }

  long getInsertLatencyMs() {
    return insertLatencyMs.sum();
  }

  /**
   * Get average search latency in milliseconds.
   */
  long getAvgSearchLatencyMs() {
    final long ops = searchOperations.sum();
    return ops > 0 ? searchLatencyMs.sum() / ops : 0L;
  }

  /**
   * Get average insert latency in milliseconds.
   */
  long getAvgInsertLatencyMs() {
    final long ops = insertOperations.sum();
    return ops > 0 ? insertLatencyMs.sum() / ops : 0L;
  }

  /**
   * Populate a map with all metrics for getStats().
   */
  void populateStats(final Map<String, Long> stats) {
    stats.put("searchOperations", searchOperations.sum());
    stats.put("insertOperations", insertOperations.sum());
    stats.put("graphRebuildCount", graphRebuildCount.sum());
    stats.put("bruteForceScans", bruteForceScans.sum());
    stats.put("preFilterSearches", preFilterSearches.sum());
    stats.put("fusedPQWalks", fusedPQWalks.sum());
    stats.put("groupedSearchesShortOfLimit", groupedSearchesShortOfLimit.sum());
    stats.put("groupedSearchesGroupsUnavailable", groupedSearchesGroupsUnavailable.sum());
    stats.put("groupedSearchesMergingDelta", groupedSearchesMergingDelta.sum());
    stats.put("unverifiedGraphReuses", unverifiedGraphReuses.sum());
    stats.put("rebuildsDeferredForMemory", rebuildsDeferredForMemory.sum());
    stats.put("residentGraphDemotions", residentGraphDemotions.sum());
    stats.put("searchRebuildsQueuedForPermit", searchRebuildsQueuedForPermit.sum());
    stats.put("searchRebuildsWithoutPermit", searchRebuildsWithoutPermit.sum());
    stats.put("smallRebuildsOverBudget", smallRebuildsOverBudget.sum());
    stats.put("stalePrefixGraphReuses", stalePrefixGraphReuses.sum());
    stats.put("graphReusesWithTombstonedNodes", graphReusesWithTombstonedNodes.sum());
    stats.put("graphLoadsFromOrdinalMap", graphLoadsFromOrdinalMap.sum());
    stats.put("compactionCount", compactionCount.sum());

    stats.put("vectorFetchFromQuantized", vectorFetchFromQuantized.sum());
    stats.put("vectorFetchFromDocuments", vectorFetchFromDocuments.sum());
    stats.put("vectorFetchFromGraph", vectorFetchFromGraph.sum());
    stats.put("graphBuildQuantizedResident", graphBuildQuantizedResident);

    stats.put("avgSearchLatencyMs", getAvgSearchLatencyMs());
    stats.put("avgInsertLatencyMs", getAvgInsertLatencyMs());
  }

  /**
   * Reset all metrics to zero.
   */
  void reset() {
    searchOperations.reset();
    insertOperations.reset();
    graphRebuildCount.reset();
    bruteForceScans.reset();
    preFilterSearches.reset();
    fusedPQWalks.reset();
    groupedSearchesShortOfLimit.reset();
    groupedSearchesGroupsUnavailable.reset();
    groupedSearchesMergingDelta.reset();
    unverifiedGraphReuses.reset();
    rebuildsDeferredForMemory.reset();
    residentGraphDemotions.reset();
    searchRebuildsQueuedForPermit.reset();
    searchRebuildsWithoutPermit.reset();
    smallRebuildsOverBudget.reset();
    stalePrefixGraphReuses.reset();
    graphReusesWithTombstonedNodes.reset();
    graphLoadsFromOrdinalMap.reset();
    compactionCount.reset();
    vectorFetchFromQuantized.reset();
    vectorFetchFromDocuments.reset();
    vectorFetchFromGraph.reset();
    graphBuildQuantizedResident = 0L;
    searchLatencyMs.reset();
    insertLatencyMs.reset();
  }
}
