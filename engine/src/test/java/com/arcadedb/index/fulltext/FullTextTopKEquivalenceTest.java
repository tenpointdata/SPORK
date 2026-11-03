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
package com.arcadedb.index.fulltext;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test of the BM25 top-k scorer: on random corpora and random queries it must answer exactly what the general path
 * answers (same documents, same float scores), for one bucket and several, single- and multi-property indexes, with deleted
 * and updated documents, and every limit. Queries the top-k scorer does not handle are answered by the general path with the
 * switch on or off, so they are compared too.
 */
class FullTextTopKEquivalenceTest extends TestHelper {
  private static final int VOCABULARY = 80;

  private static String word(final Random random) {
    // Zipf-like: low indexes are much more frequent
    final double u = random.nextDouble();
    return "w" + (int) Math.floor(Math.pow(VOCABULARY, u) - 1);
  }

  private static String text(final Random random) {
    final StringBuilder text = new StringBuilder();
    final int length = 1 + random.nextInt(20);
    for (int i = 0; i < length; i++)
      text.append(i > 0 ? " " : "").append(word(random));
    return text.toString();
  }

  private void createCorpus(final Random random) {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Doc BUCKETS 4");
      database.command("sql", "CREATE PROPERTY Doc.text STRING");
      database.command("sql", "CREATE INDEX ON Doc (text) FULL_TEXT");

      database.command("sql", "CREATE DOCUMENT TYPE Single BUCKETS 1");
      database.command("sql", "CREATE PROPERTY Single.text STRING");
      database.command("sql", "CREATE INDEX ON Single (text) FULL_TEXT");

      database.command("sql", "CREATE DOCUMENT TYPE Article BUCKETS 3");
      database.command("sql", "CREATE PROPERTY Article.title STRING");
      database.command("sql", "CREATE PROPERTY Article.body STRING");
      database.command("sql", "CREATE INDEX ON Article (title, body) FULL_TEXT METADATA {\"title_boost\": 2.5}");

      database.command("sql", "CREATE DOCUMENT TYPE Strict BUCKETS 2");
      database.command("sql", "CREATE PROPERTY Strict.text STRING");
      database.command("sql", "CREATE INDEX ON Strict (text) FULL_TEXT METADATA {\"defaultOperator\": \"AND\"}");
    });

    for (int batch = 0; batch < 4; batch++)
      database.transaction(() -> {
        for (int i = 0; i < 400; i++) {
          database.newDocument("Doc").set("text", text(random)).save();
          database.newDocument("Single").set("text", text(random)).save();
          database.newDocument("Strict").set("text", text(random)).save();
          database.newDocument("Article").set("title", text(random)).set("body", text(random)).save();
        }
      });

    // Tombstones and re-indexed values in separate transactions, so the postings the scorers read carry deletions
    database.transaction(() -> {
      for (final String type : new String[] { "Doc", "Single", "Strict" }) {
        database.command("sql", "DELETE FROM " + type + " WHERE text LIKE '%w3 %' LIMIT 40");
        database.command("sql", "UPDATE " + type + " SET text = 'w1 w1 w2 w7' WHERE text LIKE '%w5%' LIMIT 30");
      }
      database.command("sql", "DELETE FROM Article WHERE title LIKE '%w2%' LIMIT 40");
    });
  }

  private static String query(final Random random, final boolean multiProperty) {
    final String a = (multiProperty && random.nextBoolean() ? (random.nextBoolean() ? "title:" : "body:") : "") + word(random);
    final String b = (multiProperty && random.nextBoolean() ? (random.nextBoolean() ? "title:" : "body:") : "") + word(random);
    final String c = word(random);
    return switch (random.nextInt(16)) {
      case 0, 1 -> a;
      case 2 -> a + " " + b;
      case 3 -> a + " " + b + " " + c;
      case 4 -> "+" + a + " +" + b;
      case 5 -> "+" + a + " " + b;
      case 6 -> a + "^3 " + b;
      case 7 -> a + " AND " + b;
      case 8 -> a + " OR " + b + " OR " + c;
      case 9 -> a + " " + a;
      case 10 -> a + " zzzunknown";
      case 11 -> "+" + a + " +zzzunknown";
      case 12 -> "(" + a + " " + b + ") " + c;
      // forms the top-k scorer leaves to the general path
      case 13 -> a + " -" + b;
      case 14 -> "\"" + a + " " + b + "\"";
      default -> "w1*";
    };
  }

  private Map<RID, Float> search(final String indexName, final String query, final int limit, final boolean topK) {
    database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, topK);
    try {
      return FullTextSearch.search((TypeIndex) database.getSchema().getIndexByName(indexName), query, limit);
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, true);
    }
  }

  @Test
  void topKAnswersExactlyWhatTheGeneralPathAnswers() {
    final Random random = new Random(20261102);
    createCorpus(random);

    final long before = FullTextTopK.getInvocationsForTesting();
    final String[][] indexes = { { "Doc[text]", "false" }, { "Single[text]", "false" }, { "Strict[text]", "false" },
        { "Article[title,body]", "true" } };
    int compared = 0;
    for (int round = 0; round < 400; round++) {
      final String[] index = indexes[round % indexes.length];
      final String query = query(random, Boolean.parseBoolean(index[1]));
      final int limit = 1 + random.nextInt(round % 5 == 0 ? 200 : 25);

      final Map<RID, Float> expected = search(index[0], query, limit, false);
      final Map<RID, Float> actual = search(index[0], query, limit, true);
      assertThat(actual).as("index=%s query='%s' limit=%d", index[0], query, limit).isEqualTo(expected);
      ++compared;
    }
    assertThat(compared).isEqualTo(400);
    // most of the queries are plain terms: the top-k scorer answered them
    assertThat(FullTextTopK.getInvocationsForTesting() - before).isGreaterThan(200);
  }

  @Test
  void sqlOrderByScoreLimitIsUnchanged() {
    createCorpus(new Random(7));
    for (final String query : new String[] { "w1", "w2 w9", "+w1 +w4", "+w0 w12^2" }) {
      final String sql = "SELECT @rid AS rid, $score AS score FROM Doc WHERE SEARCH_INDEX('Doc[text]', ?) = true ORDER BY $score DESC, @rid LIMIT 15";
      final List<String> withTopK = rows(sql, query, true);
      final List<String> without = rows(sql, query, false);
      assertThat(withTopK).as(query).isNotEmpty().isEqualTo(without);
    }
  }

  private List<String> rows(final String sql, final String query, final boolean topK) {
    database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, topK);
    try (final ResultSet rs = database.query("sql", sql, query)) {
      final List<String> rows = new ArrayList<>();
      while (rs.hasNext()) {
        final Result row = rs.next();
        rows.add(row.getProperty("rid") + "=" + row.getProperty("score"));
      }
      return rows;
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.FULL_TEXT_TOP_K, true);
    }
  }
}
