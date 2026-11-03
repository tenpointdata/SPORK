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
package com.arcadedb.benchmarks.retrieval;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Prints the execution plan of every query of the mix against a dataset ({@code EXPLAIN}), and the row count and wall time
 * of one execution: the first thing to read when a number in the tables looks wrong, because it says which index the planner
 * chose and whether an ORDER BY or a predicate is applied after a fetch.
 */
public final class ExplainPlans {
  private ExplainPlans() {
  }

  public static void main(final String[] args) throws IOException {
    final Path root = BenchProps.root();
    final DatasetManifest manifest = DatasetManifest.read(root);
    final StringBuilder out = new StringBuilder();
    try (final Database database = new DatabaseFactory(DatasetManifest.databasePath(root).toString()).open()) {
      ReconSchema.warmVectorIndex(database);
      final QueryMix.ParamSampler sampler = new QueryMix.ParamSampler(new QueryMix.Tables(manifest), manifest.seed, database);
      for (final QueryMix.Query query : QueryMix.Query.values()) {
        if (!query.sqlServed())
          continue;
        final Map<String, Object> params = sampler.params(query);
        out.append("## ").append(query.id).append(": ").append(query.description).append("\n\n```sql\n").append(query.sql).append("\n```\n\n");
        out.append("params: ").append(describe(params)).append("\n\n```\n");
        try (final ResultSet rs = database.query("sql", "EXPLAIN " + query.sql, params)) {
          while (rs.hasNext()) {
            final Result r = rs.next();
            out.append(r.hasProperty("executionPlanAsString") ? r.<String>getProperty("executionPlanAsString") : r.toJSON()).append('\n');
          }
        } catch (final Exception e) {
          out.append("EXPLAIN failed: ").append(e).append('\n');
        }
        final long start = System.nanoTime();
        final int rows = QueryMix.execute(database, query, params);
        out.append("```\n\nfirst execution: ").append(rows).append(" rows in ").append((System.nanoTime() - start) / 1_000_000L).append(" ms\n\n");
      }
    }
    Files.createDirectories(BenchProps.results());
    Files.writeString(BenchProps.results().resolve("explain-" + manifest.records + ".md"), out.toString(), StandardCharsets.UTF_8);
    System.out.println(out);
  }

  private static String describe(final Map<String, Object> params) {
    final StringBuilder sb = new StringBuilder("{");
    for (final Map.Entry<String, Object> e : params.entrySet()) {
      final Object v = e.getValue();
      final String shown = v instanceof float[] f ? "float[" + f.length + "]" : v instanceof java.util.List<?> l ? "list[" + l.size() + "]" : String.valueOf(v);
      sb.append(sb.length() > 1 ? ", " : "").append(e.getKey()).append('=').append(shown);
    }
    return sb.append('}').toString();
  }
}
