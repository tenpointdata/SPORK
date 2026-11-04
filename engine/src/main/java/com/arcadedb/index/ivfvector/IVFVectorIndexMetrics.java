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
package com.arcadedb.index.ivfvector;

import java.util.concurrent.atomic.LongAdder;

/**
 * JVM-wide totals of every {@code LSM_IVF_VECTOR} index, the numbers the server exports to Micrometer
 * ({@code IVFVectorIndexMetricsBinder}): the engine has no Micrometer dependency, so it counts here and the server reads.
 * Every counter is monotonic (a {@code FunctionCounter} on the server side), so a {@code rate()} over it is meaningful; the
 * per-index numbers are in {@link LSMIVFVectorIndex#getStats()}.
 */
public final class IVFVectorIndexMetrics {
  public static final IVFVectorIndexMetrics INSTANCE = new IVFVectorIndexMetrics();

  /** Searches answered, every path. */
  public final LongAdder searches            = new LongAdder();
  /** Posting entries the scans read: divided by {@link #searches} it is the work per query. */
  public final LongAdder scannedEntries      = new LongAdder();
  /** Candidates scored again on the rerank code. */
  public final LongAdder rerankedCandidates  = new LongAdder();
  /** Searches whose posting lists were read on several threads of the query pool. */
  public final LongAdder parallelSearches    = new LongAdder();
  /** Filtered searches answered from the records because the allow-list was under {@code exactFilterThreshold}. */
  public final LongAdder exactFilterSearches = new LongAdder();
  /** Candidates scored on their stored vectors by the exact rerank tier ({@code exactRerank}). */
  public final LongAdder exactReranks        = new LongAdder();
  /** Deletes resolved through the position locator, and the ones that had to walk their list (should stay 0). */
  public final LongAdder locatorDeletes      = new LongAdder();
  public final LongAdder walkedDeletes       = new LongAdder();
  /** Trainings published (committed): builds, rebuilds, and the automatic trainings. */
  public final LongAdder trainings           = new LongAdder();
  /** Automatic first trainings of an index created empty, committed. */
  public final LongAdder autoTrainings       = new LongAdder();
  /** Automatic trainings of an index whose corpus outgrew its last training, committed. */
  public final LongAdder autoRetrainings     = new LongAdder();
  /** Commit replays that had to assign and code an entry again because a training landed after the write. */
  public final LongAdder replayRecodes       = new LongAdder();
  /** Vectors indexed by builds (pass 2), so vectors per second is this rate. */
  public final LongAdder builtVectors        = new LongAdder();

  private IVFVectorIndexMetrics() {
  }
}
