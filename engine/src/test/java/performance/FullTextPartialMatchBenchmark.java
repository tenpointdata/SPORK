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
package performance;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.index.Index;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Partial-text search latency over synthetic phone numbers, handles and names: substring, suffix, prefix and fuzzy lookups.
 * <p>
 * The {@code baseline} rows answer each question the way an index without partial-match analyzers has to: a leading-wildcard
 * scan for a substring or a suffix, a prefix range scan, a fuzzy term. The {@code partial} rows answer the same question
 * through the analyzer built for it: an n-gram term, a range scan of reversed tokens, an edge n-gram term, a phone suffix
 * term. Both report the number of matches, so a row can be checked to answer the same thing. Run the baseline mode on the
 * code before the partial-match work to get the "before" numbers (it only uses what existed then).
 * <p>
 * Run explicitly with:
 * {@code JAVA_TOOL_OPTIONS=-Xmx8g mvn -o -pl engine test -Dtest=FullTextPartialMatchBenchmark -Dgroups=benchmark}
 * <br>
 * {@code -Darcadedb.partialTextBenchmark.records=1000000} (default) sets the size,
 * {@code -Darcadedb.partialTextBenchmark.mode=baseline} skips the partial-match indexes.
 */
@Tag("benchmark")
class FullTextPartialMatchBenchmark {
  private static final String   RECORDS_PROPERTY = "arcadedb.partialTextBenchmark.records";
  private static final String   MODE_PROPERTY    = "arcadedb.partialTextBenchmark.mode";
  private static final int      BATCH            = 20_000;
  private static final int      WARMUP           = 3;
  private static final int      RUNS             = 15;
  private static final String   DB_PATH          = "target/databases/FullTextPartialMatchBenchmark";
  private static final String[] FIRST_NAMES      = { "john", "maria", "james", "linda", "robert", "patricia", "michael", "jennifer",
      "william", "elizabeth", "david", "barbara", "richard", "susan", "joseph", "jessica", "thomas", "sarah", "carlos", "ana",
      "luis", "sofia", "ahmed", "fatima", "wei", "mei", "ivan", "olga", "kwame", "amara" };
  private static final String[] SYLLABLES        = { "ka", "ro", "mi", "son", "ber", "lin", "ga", "tor", "vel", "an", "do", "ri",
      "chen", "sky", "wal", "ton", "mar", "tin", "ez", "ov", "ski", "ham", "ley", "nor", "ri", "us", "ste", "phen", "gold", "man" };
  private static final String[] ADJECTIVES       = { "sexy", "sweet", "lovely", "hot", "pretty", "cute", "wild", "lucky", "shy",
      "candy", "honey", "baby", "little", "golden", "silver", "dark", "angel", "kinky", "naughty", "classy" };
  private static final String[] NOUNS            = { "kitty", "bunny", "doll", "angel", "rose", "star", "queen", "princess", "fox",
      "tiger", "peach", "cherry", "diamond", "pearl", "lily", "dream", "baby", "girl", "boy", "honey" };

  @Test
  void partialTextLatency() {
    final int records = Integer.getInteger(RECORDS_PROPERTY, 1_000_000);
    final boolean partial = !"baseline".equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "all"));

    FileUtils.deleteRecursively(new File(DB_PATH));
    try (final DatabaseFactory factory = new DatabaseFactory(DB_PATH); final Database database = factory.create()) {
      createSchema(database, partial);

      final long loadStart = System.nanoTime();
      final String[] target = load(database, records, partial);
      final long loadMs = (System.nanoTime() - loadStart) / 1_000_000;

      final long compactStart = System.nanoTime();
      compactAll(database);
      final long compactMs = (System.nanoTime() - compactStart) / 1_000_000;

      final String name = target[0];
      final String handle = target[1];
      final String phone = target[2];
      final String surname = name.substring(name.indexOf(' ') + 1);
      final int underscore = handle.indexOf('_');
      final String substring = handle.substring(underscore + 2, Math.min(handle.length(), underscore + 8));
      final String suffix = handle.substring(handle.length() - 6);
      final String prefix = handle.substring(0, 6);
      final String last4 = phone.substring(phone.length() - 4);
      final String typo1 = surname.substring(0, 1) + (surname.charAt(1) == 'x' ? 'y' : 'x') + surname.substring(2);
      // two substitutions: the same distance with or without transpositions, so the old and the new code agree on the answer
      final String typo2 = typo1.substring(0, typo1.length() - 1) + (typo1.charAt(typo1.length() - 1) == 'q' ? 'z' : 'q');

      System.out.printf(Locale.ENGLISH, "%nPartial-text benchmark: %,d records, load %,d ms, compaction %,d ms%n", records, loadMs,
          compactMs);
      System.out.printf(Locale.ENGLISH, "targets: name='%s' handle='%s' phone='%s'%n", name, handle, phone);
      System.out.printf(Locale.ENGLISH, "%-9s %-8s %-44s %9s %10s %10s%n", "question", "variant", "query", "matches",
          "median ms", "p90 ms");

      row(database, "substring", "baseline", "Base", "Base[handle]", "*" + substring + "*");
      if (partial)
        row(database, "substring", "partial", "Partial", "Partial[hNgram]", substring);

      row(database, "suffix", "baseline", "Base", "Base[handle]", "*" + suffix);
      if (partial)
        row(database, "suffix", "partial", "Partial", "Partial[hReverse]", "*" + suffix);

      row(database, "prefix", "baseline", "Base", "Base[handle]", prefix + "*");
      if (partial)
        row(database, "prefix", "partial", "Partial", "Partial[hEdge]", prefix);

      row(database, "phone4", "baseline", "Base", "Base[phone]", "*" + last4);
      if (partial)
        row(database, "phone4", "partial", "Partial", "Partial[phone]", last4);

      row(database, "fuzzy~1", "-", "Base", "Base[name]", typo1 + "~1");
      row(database, "fuzzy~2", "-", "Base", "Base[name]", typo2 + "~2");

      database.drop();
    }
  }

  private static void createSchema(final Database database, final boolean partial) {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Base");
      database.command("sql", "CREATE PROPERTY Base.name STRING");
      database.command("sql", "CREATE PROPERTY Base.handle STRING");
      database.command("sql", "CREATE PROPERTY Base.phone STRING");
      database.command("sql", "CREATE INDEX ON Base (name) FULL_TEXT");
      database.command("sql", "CREATE INDEX ON Base (handle) FULL_TEXT METADATA {"
          + "\"analyzer\": \"org.apache.lucene.analysis.core.WhitespaceAnalyzer\", \"allowLeadingWildcard\": true}");
      database.command("sql", "CREATE INDEX ON Base (phone) FULL_TEXT METADATA {"
          + "\"analyzer\": \"org.apache.lucene.analysis.core.WhitespaceAnalyzer\", \"allowLeadingWildcard\": true}");

      if (partial) {
        database.command("sql", "CREATE DOCUMENT TYPE Partial");
        for (final String property : new String[] { "hNgram", "hReverse", "hEdge", "phone" })
          database.command("sql", "CREATE PROPERTY Partial." + property + " STRING");
        database.command("sql", "CREATE INDEX ON Partial (hNgram) FULL_TEXT METADATA {\"analyzer\": \"ngram\"}");
        database.command("sql", "CREATE INDEX ON Partial (hReverse) FULL_TEXT METADATA {\"analyzer\": \"reverse\"}");
        database.command("sql", "CREATE INDEX ON Partial (hEdge) FULL_TEXT METADATA {\"analyzer\": \"edge_ngram\"}");
        database.command("sql", "CREATE INDEX ON Partial (phone) FULL_TEXT METADATA {\"analyzer\": \"phone\"}");
      }
    });
  }

  /**
   * Loads the synthetic records and answers the name, handle and phone of one of them, the target of every query.
   */
  private static String[] load(final Database database, final int records, final boolean partial) {
    final Random random = new Random(7);
    final String[] target = new String[3];
    final int targetIndex = records / 3;
    for (int start = 0; start < records; start += BATCH) {
      final int from = start;
      database.transaction(() -> {
        for (int i = from; i < Math.min(records, from + BATCH); i++) {
          final String name = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)] + " " + surname(random);
          final String handle = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + "_" + NOUNS[random.nextInt(NOUNS.length)] + "_"
              + (random.nextInt(9000) + 100);
          final String phone = phone(random);
          if (i == targetIndex) {
            target[0] = name;
            target[1] = handle;
            target[2] = phone;
          }

          final MutableDocument base = database.newDocument("Base");
          base.set("name", name).set("handle", handle).set("phone", phone).save();
          if (partial) {
            final MutableDocument doc = database.newDocument("Partial");
            doc.set("hNgram", handle).set("hReverse", handle).set("hEdge", handle).set("phone", phone).save();
          }
        }
      });
    }
    return target;
  }

  private static String surname(final Random random) {
    final StringBuilder surname = new StringBuilder();
    final int syllables = 2 + random.nextInt(2);
    for (int s = 0; s < syllables; s++)
      surname.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
    return surname.toString();
  }

  private static String phone(final Random random) {
    final int area = 200 + random.nextInt(800);
    final int exchange = 200 + random.nextInt(800);
    final int line = random.nextInt(10_000);
    return switch (random.nextInt(3)) {
      case 0 -> String.format(Locale.ENGLISH, "(%03d) %03d-%04d", area, exchange, line);
      case 1 -> String.format(Locale.ENGLISH, "%03d-%03d-%04d", area, exchange, line);
      default -> String.format(Locale.ENGLISH, "+1 %03d.%03d.%04d", area, exchange, line);
    };
  }

  private static void compactAll(final Database database) {
    for (final Index index : database.getSchema().getIndexes())
      if (index instanceof TypeIndex typeIndex)
        for (final IndexInternal bucketIndex : typeIndex.getIndexesOnBuckets())
          try {
            bucketIndex.scheduleCompaction();
            bucketIndex.compact();
          } catch (final Exception e) {
            throw new RuntimeException("Cannot compact " + bucketIndex.getName(), e);
          }
  }

  private static void row(final Database database, final String question, final String variant, final String type,
      final String index, final String query) {
    final String sql = "SELECT FROM " + type + " WHERE SEARCH_INDEX('" + index + "', ?) = true";
    long matches = 0;
    for (int i = 0; i < WARMUP; i++)
      matches = count(database, sql, query);

    final long[] times = new long[RUNS];
    for (int i = 0; i < RUNS; i++) {
      final long start = System.nanoTime();
      matches = count(database, sql, query);
      times[i] = System.nanoTime() - start;
    }
    Arrays.sort(times);
    System.out.printf(Locale.ENGLISH, "%-9s %-8s %-44s %9d %10.2f %10.2f%n", question, variant, index + " '" + query + "'", matches,
        times[RUNS / 2] / 1e6, times[(int) (RUNS * 0.9)] / 1e6);
  }

  private static long count(final Database database, final String sql, final String query) {
    long count = 0;
    try (final ResultSet rs = database.query("sql", sql, query)) {
      while (rs.hasNext()) {
        rs.next();
        ++count;
      }
    }
    return count;
  }
}
