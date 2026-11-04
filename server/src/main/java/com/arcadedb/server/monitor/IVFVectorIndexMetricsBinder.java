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
package com.arcadedb.server.monitor;

import com.arcadedb.index.ivfvector.IVFVectorIndexMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.concurrent.atomic.LongAdder;

/**
 * Exposes the JVM-wide totals of every {@code LSM_IVF_VECTOR} index ({@link IVFVectorIndexMetrics}) to Micrometer as
 * {@code arcadedb.vector.ivf.*} counters. All of them are monotonic, hence {@link FunctionCounter}s (see
 * {@link EngineMetricsBinder} on #5636): {@code rate(scanned.entries) / rate(searches)} is the work per query, a
 * {@code walked.deletes} rate above zero says the position locator missed, a non-zero {@code replay.recodes} rate outside an
 * automatic training says writers commit against stale centroids.
 * <p>
 * The meters read the engine's singleton, which is strongly reachable for the JVM's lifetime, so the weak reference
 * Micrometer keeps to it never lets a meter go silent.
 */
public final class IVFVectorIndexMetricsBinder implements MeterBinder {

  @Override
  public void bindTo(final MeterRegistry registry) {
    final IVFVectorIndexMetrics m = IVFVectorIndexMetrics.INSTANCE;
    counter(registry, "arcadedb.vector.ivf.searches", "IVF vector searches", m.searches);
    counter(registry, "arcadedb.vector.ivf.scanned.entries", "Posting entries read by IVF scans", m.scannedEntries);
    counter(registry, "arcadedb.vector.ivf.reranked.candidates", "Candidates scored again on the IVF rerank code",
        m.rerankedCandidates);
    counter(registry, "arcadedb.vector.ivf.parallel.searches", "IVF searches whose posting lists were read on the query pool",
        m.parallelSearches);
    counter(registry, "arcadedb.vector.ivf.exact.filter.searches",
        "Filtered IVF searches answered from the records (allow-list under exactFilterThreshold)", m.exactFilterSearches);
    counter(registry, "arcadedb.vector.ivf.exact.reranks", "IVF candidates rescored on their stored vectors (exactRerank)",
        m.exactReranks);
    counter(registry, "arcadedb.vector.ivf.locator.deletes", "IVF deletes resolved through the position locator", m.locatorDeletes);
    counter(registry, "arcadedb.vector.ivf.walked.deletes", "IVF deletes that had to walk their posting list", m.walkedDeletes);
    counter(registry, "arcadedb.vector.ivf.trainings", "IVF centroid trainings committed (builds, rebuilds and automatic)",
        m.trainings);
    counter(registry, "arcadedb.vector.ivf.auto.trainings", "Automatic first trainings of IVF indexes created empty, committed",
        m.autoTrainings);
    counter(registry, "arcadedb.vector.ivf.auto.retrainings",
        "Automatic trainings of IVF indexes whose corpus outgrew their last training, committed", m.autoRetrainings);
    counter(registry, "arcadedb.vector.ivf.replay.recodes",
        "IVF entries assigned and coded again at commit because a training landed after the write", m.replayRecodes);
    counter(registry, "arcadedb.vector.ivf.built.vectors", "Vectors indexed by IVF builds", m.builtVectors);
  }

  private static void counter(final MeterRegistry registry, final String name, final String description, final LongAdder adder) {
    FunctionCounter.builder(name, adder, LongAdder::sum).description(description).register(registry);
  }
}
