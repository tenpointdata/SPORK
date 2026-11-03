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

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.index.IndexException;
import com.arcadedb.serializer.json.JSONObject;

/**
 * Builder for {@link Schema.INDEX_TYPE#LSM_IVF_VECTOR LSM_IVF_VECTOR} indexes. Every setting lives on the
 * {@link LSMIVFVectorIndexMetadata} the builder carries.
 */
public class TypeLSMIVFVectorIndexBuilder extends TypeIndexBuilder {

  protected TypeLSMIVFVectorIndexBuilder(final TypeIndexBuilder copyFrom) {
    super(copyFrom, Schema.INDEX_TYPE.LSM_IVF_VECTOR,
        new LSMIVFVectorIndexMetadata(copyFrom.metadata.typeName, copyFrom.metadata.propertyNames.toArray(new String[0]),
            copyFrom.metadata.associatedBucketId));
  }

  protected TypeLSMIVFVectorIndexBuilder(final DatabaseInternal database, final String typeName, final String[] propertyNames) {
    super(database, typeName, propertyNames);
    this.indexType = Schema.INDEX_TYPE.LSM_IVF_VECTOR;
    this.metadata = new LSMIVFVectorIndexMetadata(typeName, propertyNames, -1);
  }

  public TypeLSMIVFVectorIndexBuilder withDimensions(final int dimensions) {
    ivfMetadata().setDimensions(dimensions);
    return this;
  }

  /** COSINE (default), DOT_PRODUCT, EUCLIDEAN or HAMMING. */
  public TypeLSMIVFVectorIndexBuilder withSimilarity(final String similarity) {
    ivfMetadata().setSimilarity(similarity);
    return this;
  }

  /** Posting lists the build trains; 0 (default) derives them from {@link #withTargetPostingSize(int)}. */
  public TypeLSMIVFVectorIndexBuilder withNlist(final int nlist) {
    ivfMetadata().setNlist(nlist);
    return this;
  }

  public TypeLSMIVFVectorIndexBuilder withTargetPostingSize(final int targetPostingSize) {
    ivfMetadata().setTargetPostingSize(targetPostingSize);
    return this;
  }

  /** BINARY (default) or INT8. */
  public TypeLSMIVFVectorIndexBuilder withScanCode(final String scanCode) {
    ivfMetadata().setScanCode(scanCode);
    return this;
  }

  /** INT8 (default), INT4 (residual, a quarter of the bytes) or NONE. */
  public TypeLSMIVFVectorIndexBuilder withRerankCode(final String rerankCode) {
    ivfMetadata().setRerankCode(rerankCode);
    return this;
  }

  public TypeLSMIVFVectorIndexBuilder withNprobe(final int nprobe) {
    ivfMetadata().setNprobe(nprobe);
    return this;
  }

  public TypeLSMIVFVectorIndexBuilder withRerankOversample(final int rerankOversample) {
    ivfMetadata().setRerankOversample(rerankOversample);
    return this;
  }

  /** The absolute floor of the default rerank budget (default 2,000 candidates). */
  public TypeLSMIVFVectorIndexBuilder withRerankCandidates(final int rerankCandidates) {
    ivfMetadata().setRerankCandidates(rerankCandidates);
    return this;
  }

  /** Allow-lists of at most this many records are scored exactly from the records (default 5,000). */
  public TypeLSMIVFVectorIndexBuilder withExactFilterThreshold(final int exactFilterThreshold) {
    ivfMetadata().setExactFilterThreshold(exactFilterThreshold);
    return this;
  }

  /** Live vectors at which an untrained index trains by itself (default 10,000; 0 = only on REBUILD INDEX). */
  public TypeLSMIVFVectorIndexBuilder withAutoTrainThreshold(final int autoTrainThreshold) {
    ivfMetadata().setAutoTrainThreshold(autoTrainThreshold);
    return this;
  }

  /** Best reranked candidates rescored on their stored vectors (default 0, none). */
  public TypeLSMIVFVectorIndexBuilder withExactRerank(final int exactRerank) {
    ivfMetadata().setExactRerank(exactRerank);
    return this;
  }

  public TypeLSMIVFVectorIndexBuilder withTrainingSample(final int trainingSample) {
    ivfMetadata().setTrainingSample(trainingSample);
    return this;
  }

  public TypeLSMIVFVectorIndexBuilder withKmeansIterations(final int kmeansIterations) {
    ivfMetadata().setKmeansIterations(kmeansIterations);
    return this;
  }

  @Override
  public TypeLSMIVFVectorIndexBuilder withMetadata(final IndexMetadata metadata) {
    if (metadata != null && !(metadata instanceof LSMIVFVectorIndexMetadata))
      throw new IllegalArgumentException(
          "An LSM_IVF_VECTOR index requires LSMIVFVectorIndexMetadata but got " + metadata.getClass().getName());
    this.metadata = metadata;
    return this;
  }

  /** The {@code METADATA} clause of {@code CREATE INDEX}: unknown keys are refused (issue #5639). */
  public TypeLSMIVFVectorIndexBuilder withMetadata(final JSONObject json) {
    ivfMetadata().fromUserMetadata(json, Schema.INDEX_TYPE.LSM_IVF_VECTOR);
    return this;
  }

  /** A persisted definition, structural keys tolerated. */
  public TypeLSMIVFVectorIndexBuilder withPersistedMetadata(final JSONObject json) {
    if (json != null)
      ivfMetadata().fromJSON(json);
    return this;
  }

  private LSMIVFVectorIndexMetadata ivfMetadata() {
    if (metadata instanceof LSMIVFVectorIndexMetadata m)
      return m;
    throw new IndexException("IVF vector index metadata expected but was " + (metadata == null ? "null" : metadata.getClass().getSimpleName()));
  }
}
