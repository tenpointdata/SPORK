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
package com.arcadedb.schema;

import com.arcadedb.index.IndexException;
import com.arcadedb.index.ivfvector.IVFRerankCode;
import com.arcadedb.index.ivfvector.IVFScanCode;
import com.arcadedb.index.ivfvector.IVFSimilarity;
import com.arcadedb.serializer.json.JSONObject;

import java.util.Set;

/**
 * Metadata of an {@link Schema.INDEX_TYPE#LSM_IVF_VECTOR LSM_IVF_VECTOR} index: the one list of its settings, read by
 * the builders, the index and the persisted definition alike (the lesson of issue #5639, see
 * {@link LSMVectorIndexMetadata}).
 * <p>
 * An IVF index keeps nothing per vector on the heap: its centroids are trained once at build time and written to pages,
 * its posting lists are pages, and a search reads {@link #nprobe} of them. The settings here size the two:
 * {@link #nlist} or {@link #targetPostingSize} decide how many centroids the build trains, {@link #scanCode} and
 * {@link #rerankCode} decide what every posting entry carries.
 */
public class LSMIVFVectorIndexMetadata extends IndexMetadata {
  public static final int DEFAULT_TARGET_POSTING_SIZE = 200;
  public static final int DEFAULT_NPROBE              = 32;
  public static final int DEFAULT_RERANK_OVERSAMPLE   = 10;
  public static final int DEFAULT_TRAINING_SAMPLE     = 100_000;
  public static final int DEFAULT_KMEANS_ITERATIONS   = 10;
  /**
   * Candidates the scan keeps for the rerank when the query does not say, whatever {@code k}: an absolute budget, because what
   * it has to cover is the near-duplicate density of the corpus around a query, not the size of the answer (measured at 5M x
   * 512-d on 1,000-point clusters: 100 candidates recall 0.38, 2,000 recall 0.91 for +0.9 ms).
   */
  public static final int DEFAULT_RERANK_CANDIDATES   = 2_000;
  /**
   * Allowed records at or below which a filtered search scores every allowed record exactly instead of scanning the lists: an
   * absolute count, because its cost is one record read per allowed record whatever the corpus size (a 1% ratio was 50K
   * record reads, 109 ms, at 5M).
   */
  public static final int DEFAULT_EXACT_FILTER_THRESHOLD = 5_000;
  /** Live vectors at which an untrained index trains its centroids by itself (0 = never, wait for a REBUILD INDEX). */
  public static final int DEFAULT_AUTO_TRAIN_THRESHOLD   = 10_000;
  /**
   * Best reranked candidates a search scores again on their stored vectors (0 = none): the tier above the codes, one record read
   * per candidate, that lifts the ceiling the INT8 / INT4 resolution puts on recall.
   */
  public static final int DEFAULT_EXACT_RERANK           = 0;

  private static final Set<String> USER_METADATA_KEYS = Set.of("dimensions", "similarity", "nlist", "targetPostingSize",
      "scanCode", "rerankCode", "nprobe", "rerankOversample", "rerankCandidates", "exactFilterThreshold", "trainingSample",
      "kmeansIterations", "autoTrainThreshold", "exactRerank");

  public int           dimensions;
  public IVFSimilarity similarity        = IVFSimilarity.COSINE;
  /** Number of centroids (posting lists) the build trains; 0 (default) derives it from {@link #targetPostingSize}. */
  public int           nlist             = 0;
  /** Vectors per posting list the build aims at when {@link #nlist} is 0: {@code nlist = ceil(count / targetPostingSize)}. */
  public int           targetPostingSize = DEFAULT_TARGET_POSTING_SIZE;
  public IVFScanCode   scanCode          = IVFScanCode.BINARY;
  public IVFRerankCode rerankCode        = IVFRerankCode.INT8;
  /** Posting lists a search scans when the query does not say. */
  public int           nprobe            = DEFAULT_NPROBE;
  /**
   * Candidates per requested neighbour the scan keeps for the rerank; 0 reranks nothing. The default budget of a search is
   * {@code max(k * rerankOversample, rerankCandidates)}; a query that names its own oversample ({@code {rerank: n}}) gets
   * exactly {@code k * n}.
   */
  public int           rerankOversample  = DEFAULT_RERANK_OVERSAMPLE;
  /** The absolute floor of the default rerank budget, see {@link #DEFAULT_RERANK_CANDIDATES}. */
  public int           rerankCandidates  = DEFAULT_RERANK_CANDIDATES;
  /** Allow-lists of at most this many records are answered exactly from the records, see {@link #DEFAULT_EXACT_FILTER_THRESHOLD}. */
  public int           exactFilterThreshold = DEFAULT_EXACT_FILTER_THRESHOLD;
  /** Live vectors at which an untrained index trains by itself, see {@link #DEFAULT_AUTO_TRAIN_THRESHOLD}. */
  public int           autoTrainThreshold = DEFAULT_AUTO_TRAIN_THRESHOLD;
  /** Reranked candidates rescored on their stored vectors, see {@link #DEFAULT_EXACT_RERANK}. */
  public int           exactRerank       = DEFAULT_EXACT_RERANK;
  /** Vectors the build samples (reservoir) to train the centroids on. */
  public int           trainingSample    = DEFAULT_TRAINING_SAMPLE;
  /** Lloyd iterations of each k-means level. */
  public int           kmeansIterations  = DEFAULT_KMEANS_ITERATIONS;

  public LSMIVFVectorIndexMetadata(final String typeName, final String[] propertyNames, final int bucketId) {
    super(typeName, propertyNames, bucketId);
  }

  @Override
  public LSMIVFVectorIndexMetadata copy(final String typeName, final String[] propertyNames, final int bucketId) {
    final LSMIVFVectorIndexMetadata copy = copyCommonTo(new LSMIVFVectorIndexMetadata(typeName, propertyNames, bucketId));
    copy.dimensions = dimensions;
    copy.similarity = similarity;
    copy.nlist = nlist;
    copy.targetPostingSize = targetPostingSize;
    copy.scanCode = scanCode;
    copy.rerankCode = rerankCode;
    copy.nprobe = nprobe;
    copy.rerankOversample = rerankOversample;
    copy.rerankCandidates = rerankCandidates;
    copy.exactFilterThreshold = exactFilterThreshold;
    copy.autoTrainThreshold = autoTrainThreshold;
    copy.exactRerank = exactRerank;
    copy.trainingSample = trainingSample;
    copy.kmeansIterations = kmeansIterations;
    return copy;
  }

  @Override
  public Set<String> getUserMetadataKeys() {
    return USER_METADATA_KEYS;
  }

  @Override
  protected void applyUserMetadata(final JSONObject json) {
    // Key by key, as the other vector families do: an absent key leaves the builder's value alone.
    if (json.has("dimensions"))
      setDimensions(metadataInt(json, "dimensions"));
    if (json.has("similarity"))
      setSimilarity(json.getString("similarity"));
    if (json.has("nlist"))
      setNlist(metadataInt(json, "nlist"));
    if (json.has("targetPostingSize"))
      setTargetPostingSize(metadataInt(json, "targetPostingSize"));
    if (json.has("scanCode"))
      setScanCode(json.getString("scanCode"));
    if (json.has("rerankCode"))
      setRerankCode(json.getString("rerankCode"));
    if (json.has("nprobe"))
      setNprobe(metadataInt(json, "nprobe"));
    if (json.has("rerankOversample"))
      setRerankOversample(metadataInt(json, "rerankOversample"));
    if (json.has("rerankCandidates"))
      setRerankCandidates(metadataInt(json, "rerankCandidates"));
    if (json.has("exactFilterThreshold"))
      setExactFilterThreshold(metadataInt(json, "exactFilterThreshold"));
    if (json.has("autoTrainThreshold"))
      setAutoTrainThreshold(metadataInt(json, "autoTrainThreshold"));
    if (json.has("exactRerank"))
      setExactRerank(metadataInt(json, "exactRerank"));
    if (json.has("trainingSample"))
      setTrainingSample(metadataInt(json, "trainingSample"));
    if (json.has("kmeansIterations"))
      setKmeansIterations(metadataInt(json, "kmeansIterations"));
  }

  @Override
  protected Object getUserMetadataValue(final String key) {
    return switch (key) {
      case "dimensions" -> dimensions;
      case "similarity" -> similarity;
      case "nlist" -> nlist;
      case "targetPostingSize" -> targetPostingSize;
      case "scanCode" -> scanCode;
      case "rerankCode" -> rerankCode;
      case "nprobe" -> nprobe;
      case "rerankOversample" -> rerankOversample;
      case "rerankCandidates" -> rerankCandidates;
      case "exactFilterThreshold" -> exactFilterThreshold;
      case "autoTrainThreshold" -> autoTrainThreshold;
      case "exactRerank" -> exactRerank;
      case "trainingSample" -> trainingSample;
      case "kmeansIterations" -> kmeansIterations;
      default -> null;
    };
  }

  /**
   * Reads a persisted definition, the JSON {@code LSMIVFVectorIndex.toJSON()} writes. The bucket-level entry carries no
   * {@code typeName} (see {@link LSMSparseVectorIndexMetadata#fromJSON} for why the base read is guarded).
   */
  @Override
  public void fromJSON(final JSONObject metadata) {
    if (metadata.has("typeName"))
      super.fromJSON(metadata);
    this.dimensions = metadata.getInt("dimensions", 0);
    if (metadata.has("similarity"))
      setSimilarity(metadata.getString("similarity"));
    this.nlist = metadata.getInt("nlist", 0);
    this.targetPostingSize = metadata.getInt("targetPostingSize", DEFAULT_TARGET_POSTING_SIZE);
    if (metadata.has("scanCode"))
      setScanCode(metadata.getString("scanCode"));
    if (metadata.has("rerankCode"))
      setRerankCode(metadata.getString("rerankCode"));
    this.nprobe = metadata.getInt("nprobe", DEFAULT_NPROBE);
    this.rerankOversample = metadata.getInt("rerankOversample", DEFAULT_RERANK_OVERSAMPLE);
    this.rerankCandidates = metadata.getInt("rerankCandidates", DEFAULT_RERANK_CANDIDATES);
    this.exactFilterThreshold = metadata.getInt("exactFilterThreshold", DEFAULT_EXACT_FILTER_THRESHOLD);
    this.autoTrainThreshold = metadata.getInt("autoTrainThreshold", DEFAULT_AUTO_TRAIN_THRESHOLD);
    this.exactRerank = metadata.getInt("exactRerank", DEFAULT_EXACT_RERANK);
    this.trainingSample = metadata.getInt("trainingSample", DEFAULT_TRAINING_SAMPLE);
    this.kmeansIterations = metadata.getInt("kmeansIterations", DEFAULT_KMEANS_ITERATIONS);
  }

  /** Writes every setting into {@code json}, the persisted shape {@link #fromJSON(JSONObject)} reads back. */
  public void toJSON(final JSONObject json) {
    json.put("dimensions", dimensions);
    json.put("similarity", similarity.name());
    json.put("nlist", nlist);
    json.put("targetPostingSize", targetPostingSize);
    json.put("scanCode", scanCode.name());
    json.put("rerankCode", rerankCode.name());
    json.put("nprobe", nprobe);
    json.put("rerankOversample", rerankOversample);
    json.put("rerankCandidates", rerankCandidates);
    json.put("exactFilterThreshold", exactFilterThreshold);
    json.put("autoTrainThreshold", autoTrainThreshold);
    json.put("exactRerank", exactRerank);
    json.put("trainingSample", trainingSample);
    json.put("kmeansIterations", kmeansIterations);
  }

  public void setDimensions(final int dimensions) {
    if (dimensions < 1)
      throw new IndexException("dimensions must be at least 1");
    this.dimensions = dimensions;
  }

  public void setSimilarity(final String similarity) {
    try {
      this.similarity = IVFSimilarity.parse(similarity);
    } catch (final IllegalArgumentException e) {
      throw new IndexException(e.getMessage(), e);
    }
  }

  public void setNlist(final int nlist) {
    if (nlist < 0)
      throw new IndexException("nlist must be >= 0 (0 = derived from targetPostingSize)");
    this.nlist = nlist;
  }

  public void setTargetPostingSize(final int targetPostingSize) {
    if (targetPostingSize < 1)
      throw new IndexException("targetPostingSize must be at least 1");
    this.targetPostingSize = targetPostingSize;
  }

  public void setScanCode(final String scanCode) {
    try {
      this.scanCode = IVFScanCode.parse(scanCode);
    } catch (final IllegalArgumentException e) {
      throw new IndexException(e.getMessage(), e);
    }
  }

  public void setRerankCode(final String rerankCode) {
    try {
      this.rerankCode = IVFRerankCode.parse(rerankCode);
    } catch (final IllegalArgumentException e) {
      throw new IndexException(e.getMessage(), e);
    }
  }

  public void setNprobe(final int nprobe) {
    if (nprobe < 1)
      throw new IndexException("nprobe must be at least 1");
    this.nprobe = nprobe;
  }

  public void setRerankOversample(final int rerankOversample) {
    if (rerankOversample < 0)
      throw new IndexException("rerankOversample must be >= 0 (0 = the scan distances alone)");
    this.rerankOversample = rerankOversample;
  }

  public void setRerankCandidates(final int rerankCandidates) {
    if (rerankCandidates < 0)
      throw new IndexException("rerankCandidates must be >= 0 (0 = k * rerankOversample alone)");
    this.rerankCandidates = rerankCandidates;
  }

  public void setExactFilterThreshold(final int exactFilterThreshold) {
    if (exactFilterThreshold < 0)
      throw new IndexException("exactFilterThreshold must be >= 0 (0 = never score an allow-list exactly)");
    this.exactFilterThreshold = exactFilterThreshold;
  }

  public void setAutoTrainThreshold(final int autoTrainThreshold) {
    if (autoTrainThreshold < 0)
      throw new IndexException("autoTrainThreshold must be >= 0 (0 = train only on REBUILD INDEX)");
    this.autoTrainThreshold = autoTrainThreshold;
  }

  public void setExactRerank(final int exactRerank) {
    if (exactRerank < 0)
      throw new IndexException("exactRerank must be >= 0 (0 = answer on the rerank code)");
    this.exactRerank = exactRerank;
  }

  public void setTrainingSample(final int trainingSample) {
    if (trainingSample < 1)
      throw new IndexException("trainingSample must be at least 1");
    this.trainingSample = trainingSample;
  }

  public void setKmeansIterations(final int kmeansIterations) {
    if (kmeansIterations < 1)
      throw new IndexException("kmeansIterations must be at least 1");
    this.kmeansIterations = kmeansIterations;
  }
}
