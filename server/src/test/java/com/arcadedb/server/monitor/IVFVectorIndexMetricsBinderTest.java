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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.index.ivfvector.IVFVectorIndexMetrics;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IVFVectorIndexMetricsBinder}: every IVF counter is registered as a counter and follows the engine's totals when an
 * {@code LSM_IVF_VECTOR} index is built, searched and written to.
 */
class IVFVectorIndexMetricsBinderTest {
  private static final String DATABASE_PATH = "target/databases/ivf-metrics-binder";

  @Test
  void countersFollowTheIndex() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new IVFVectorIndexMetricsBinder().bindTo(registry);
    for (final String name : new String[] { "arcadedb.vector.ivf.searches", "arcadedb.vector.ivf.scanned.entries",
        "arcadedb.vector.ivf.reranked.candidates", "arcadedb.vector.ivf.parallel.searches", "arcadedb.vector.ivf.exact.filter.searches",
        "arcadedb.vector.ivf.exact.reranks", "arcadedb.vector.ivf.locator.deletes", "arcadedb.vector.ivf.walked.deletes",
        "arcadedb.vector.ivf.trainings", "arcadedb.vector.ivf.auto.trainings", "arcadedb.vector.ivf.auto.retrainings",
        "arcadedb.vector.ivf.replay.recodes",
        "arcadedb.vector.ivf.built.vectors" })
      assertThat(registry.find(name).functionCounter()).as(name).isNotNull();

    final double searches = count(registry, "arcadedb.vector.ivf.searches");
    final double built = count(registry, "arcadedb.vector.ivf.built.vectors");
    final double deletes = count(registry, "arcadedb.vector.ivf.locator.deletes");
    final double trainings = count(registry, "arcadedb.vector.ivf.trainings");
    FileUtils.deleteRecursively(new File(DATABASE_PATH));
    try (final DatabaseFactory factory = new DatabaseFactory(DATABASE_PATH); final Database database = factory.create()) {
      final Random random = new Random(1);
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Doc.embedding ARRAY_OF_FLOATS");
      final Object[] first = new Object[1];
      database.transaction(() -> {
        for (int i = 0; i < 500; i++) {
          final float[] v = new float[8];
          for (int d = 0; d < 8; d++)
            v[d] = (float) random.nextGaussian();
          final MutableDocument doc = database.newDocument("Doc").set("embedding", v).save();
          if (i == 0)
            first[0] = doc.getIdentity();
        }
      });
      database.command("sql", "CREATE INDEX ON Doc (embedding) LSM_IVF_VECTOR METADATA {dimensions: 8, nlist: 4}");
      database.transaction(() -> {
        try (final ResultSet rs = database.query("sql", "SELECT expand(vector.neighbors('Doc[embedding]', [1,0,0,0,0,0,0,0], 3))")) {
          assertThat(rs.hasNext()).isTrue();
        }
      });
      database.transaction(() -> database.lookupByRID((RID) first[0], true).asDocument().delete());
    } finally {
      FileUtils.deleteRecursively(new File(DATABASE_PATH));
    }
    assertThat(count(registry, "arcadedb.vector.ivf.searches")).isGreaterThan(searches);
    assertThat(count(registry, "arcadedb.vector.ivf.built.vectors")).isGreaterThanOrEqualTo(built + 500);
    assertThat(count(registry, "arcadedb.vector.ivf.locator.deletes")).isGreaterThan(deletes);
    assertThat(count(registry, "arcadedb.vector.ivf.trainings")).as("the build's committed training").isGreaterThan(trainings);
    assertThat(count(registry, "arcadedb.vector.ivf.searches")).isEqualTo((double) IVFVectorIndexMetrics.INSTANCE.searches.sum());
  }

  private static double count(final SimpleMeterRegistry registry, final String name) {
    final FunctionCounter counter = registry.find(name).functionCounter();
    return counter.count();
  }
}
