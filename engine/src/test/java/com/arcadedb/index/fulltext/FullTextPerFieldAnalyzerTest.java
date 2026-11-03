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

import com.arcadedb.TestHelper;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.engine.Bucket;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.FullTextIndexMetadata;
import com.arcadedb.schema.Schema;
import com.arcadedb.serializer.json.JSONObject;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the per-field analyzers ({@code <field>_analyzer}) that were parsed and persisted but never used: every
 * property was analyzed with the index-wide analyzer at write time and at query time.
 */
class FullTextPerFieldAnalyzerTest extends TestHelper {

  private void createArticleIndex() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Article");
      database.command("sql", "CREATE PROPERTY Article.title STRING");
      database.command("sql", "CREATE PROPERTY Article.body STRING");
      // title stems (English), body does not (Standard)
      database.command("sql", """
          CREATE INDEX ON Article (title, body) FULL_TEXT METADATA {
          "title_analyzer": "org.apache.lucene.analysis.en.EnglishAnalyzer"}""");
      database.command("sql", "INSERT INTO Article SET title = 'Running Fast', body = 'running slow'");
    });
  }

  private Set<String> titles(final String sql) {
    final Set<String> result = new HashSet<>();
    try (final ResultSet rs = database.query("sql", sql)) {
      while (rs.hasNext())
        result.add(rs.next().getProperty("title"));
    }
    return result;
  }

  @Test
  void perFieldAnalyzerIsUsedAtIndexAndQueryTime() {
    createArticleIndex();

    database.transaction(() -> {
      final LSMTreeFullTextIndex ftIndex = (LSMTreeFullTextIndex) ((TypeIndex) database.getSchema()
          .getIndexByName("Article[title,body]")).getIndexesOnBuckets()[0];
      assertThat(ftIndex.getIndexAnalyzer("title")).isInstanceOf(EnglishAnalyzer.class);
      assertThat(ftIndex.getIndexAnalyzer("body")).isInstanceOf(StandardAnalyzer.class);
      assertThat(ftIndex.getQueryAnalyzer("title")).isInstanceOf(EnglishAnalyzer.class);

      // 'runs' only matches through the English stemmer, which is configured for title alone
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:runs') = true"))
          .containsExactly("Running Fast");
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'body:runs') = true")).isEmpty();
      // body keeps the unstemmed token
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'body:running') = true"))
          .containsExactly("Running Fast");
    });
  }

  @Test
  void perFieldAnalyzerIsUsedByContainsText() {
    createArticleIndex();

    database.transaction(() -> {
      assertThat(titles("SELECT FROM Article WHERE title CONTAINSTEXT 'runs'")).containsExactly("Running Fast");
      assertThat(titles("SELECT FROM Article WHERE body CONTAINSTEXT 'runs'")).isEmpty();
    });
  }

  @Test
  void perFieldAnalyzerSurvivesReopen() {
    createArticleIndex();
    reopenDatabase();

    database.transaction(() -> {
      database.command("sql", "INSERT INTO Article SET title = 'Jumping High', body = 'jumping low'");
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:jumps') = true"))
          .containsExactly("Jumping High");
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:runs') = true"))
          .containsExactly("Running Fast");
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'body:jumps') = true")).isEmpty();
    });
  }

  @Test
  void perFieldAnalyzerOnSinglePropertyIndex() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Item");
      database.command("sql", "CREATE PROPERTY Item.title STRING");
      database.command("sql", """
          CREATE INDEX ON Item (title) FULL_TEXT METADATA {
          "title_analyzer": "org.apache.lucene.analysis.core.KeywordAnalyzer"}""");
      database.command("sql", "INSERT INTO Item SET title = 'ABC-123 widget'");
    });

    database.transaction(() -> {
      final LSMTreeFullTextIndex ftIndex = (LSMTreeFullTextIndex) ((TypeIndex) database.getSchema()
          .getIndexByName("Item[title]")).getIndexesOnBuckets()[0];
      assertThat(ftIndex.getIndexAnalyzer("title")).isInstanceOf(KeywordAnalyzer.class);
      // The keyword analyzer keeps the whole value as one token: a single word of it no longer matches
      assertThat(titles("SELECT FROM Item WHERE title CONTAINSTEXT 'widget'")).isEmpty();
      assertThat(titles("SELECT FROM Item WHERE SEARCH_INDEX('Item[title]', '\"ABC-123 widget\"') = true"))
          .containsExactly("ABC-123 widget");
    });
  }

  @Test
  void removalUsesThePerFieldAnalyzer() {
    createArticleIndex();

    database.transaction(() -> database.command("sql", "UPDATE Article SET title = 'Walking Slowly' WHERE title = 'Running Fast'"));

    database.transaction(() -> {
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:runs') = true")).isEmpty();
      assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:walks') = true"))
          .containsExactly("Walking Slowly");
    });
  }

  private LSMTreeFullTextIndex articleBucketIndex() {
    return (LSMTreeFullTextIndex) ((TypeIndex) database.getSchema().getIndexByName("Article[title,body]")).getIndexesOnBuckets()[0];
  }

  private long livePostings(final String storedKey) {
    long live = 0;
    try (final IndexCursor cursor = articleBucketIndex().getPostings(storedKey)) {
      while (cursor.hasNext()) {
        final Identifiable posting = cursor.next();
        if (posting != null && posting.getIdentity().getBucketId() >= 0)
          ++live;
      }
    }
    return live;
  }

  /**
   * An index written before the per-field analyzers were applied holds postings built with the index-wide analyzer. Until REBUILD
   * INDEX writes it again, it must keep indexing with that analyzer: an update analyzed with the field's analyzer removed tokens
   * that were never stored ('run') and left the real ones ('running'), so the old value kept matching.
   */
  @Test
  void anIndexWrittenBeforePerFieldAnalyzersKeepsTheIndexWideAnalyzerUntilRebuilt() throws IOException {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Article");
      database.command("sql", "CREATE PROPERTY Article.title STRING");
      database.command("sql", "CREATE PROPERTY Article.body STRING");
      database.command("sql", "CREATE INDEX ON Article (title, body) FULL_TEXT");
      database.command("sql", "INSERT INTO Article SET title = 'Running Fast', body = 'running slow'");
    });
    assertThat(livePostings("title:running")).isEqualTo(1);

    // The definition as an index of the previous format carried it: the field analyzer configured, no format version
    database.close();
    final File schemaFile = new File(getDatabasePath(), "schema.json");
    final JSONObject schema = new JSONObject(Files.readString(schemaFile.toPath()));
    final JSONObject indexes = schema.getJSONObject("types").getJSONObject("Article").getJSONObject("indexes");
    for (final String name : indexes.keySet()) {
      final JSONObject index = indexes.getJSONObject(name);
      index.remove("ft_formatVersion");
      index.put("title_analyzer", EnglishAnalyzer.class.getName());
    }
    Files.writeString(schemaFile.toPath(), schema.toString());
    final File previous = new File(getDatabasePath(), "schema.prev.json");
    if (previous.exists())
      Files.delete(previous.toPath());
    reopenDatabase();

    final FullTextIndexMetadata legacy = LSMTreeFullTextIndex.metadataOf(
        (TypeIndex) database.getSchema().getIndexByName("Article[title,body]"));
    assertThat(legacy.getFormatVersion()).isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_LSM);
    assertThat(legacy.getFieldAnalyzers()).containsKey("title");
    assertThat(articleBucketIndex().getIndexAnalyzer("title")).isInstanceOf(StandardAnalyzer.class);

    database.transaction(() -> database.command("sql", "UPDATE Article SET title = 'Walking Slowly' WHERE title = 'Running Fast'"));
    assertThat(livePostings("title:running")).as("the old token of the updated title").isZero();
    assertThat(livePostings("title:walking")).isEqualTo(1);
    assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:running') = true")).isEmpty();
    assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:walking') = true"))
        .containsExactly("Walking Slowly");

    // REBUILD INDEX writes it in the current format, with the field analyzer
    database.command("sql", "REBUILD INDEX `Article[title,body]`");
    assertThat(articleBucketIndex().getIndexAnalyzer("title")).isInstanceOf(EnglishAnalyzer.class);
    assertThat(titles("SELECT FROM Article WHERE SEARCH_INDEX('Article[title,body]', 'title:walks') = true"))
        .containsExactly("Walking Slowly");
  }

  /** Every title in the index matching {@code query}, through {@link FullTextSearch#search} with a limit (top-k) and without. */
  private Set<String> searchTitles(final String query, final int limit) {
    final Set<String> result = new HashSet<>();
    for (final RID rid : FullTextSearch.search((TypeIndex) database.getSchema().getIndexByName("Article[title,body]"), query, limit)
        .keySet())
      result.add(rid.asDocument().getString("title"));
    return result;
  }

  /** Writes {@code count} documents titled {@code title} into every bucket of Article listed. */
  private void insertIntoBuckets(final List<String> bucketNames, final String title, final int count) {
    database.transaction(() -> {
      for (final String bucketName : bucketNames)
        for (int i = 0; i < count; i++)
          database.newDocument("Article").set("title", title + " " + bucketName + " " + i).set("body", "text").save(bucketName);
    });
  }

  private List<String> articleBucketNames() {
    final List<String> names = new ArrayList<>();
    for (final Bucket bucket : database.getSchema().getType("Article").getBuckets(false))
      names.add(bucket.getName());
    return names;
  }

  /** Rewrites schema.json, applying {@code edit} to the definition of every Article bucket index, before a reopen. */
  private void editArticleIndexDefinitions(final Consumer<JSONObject> edit) throws IOException {
    database.close();
    final File schemaFile = new File(getDatabasePath(), "schema.json");
    final JSONObject schema = new JSONObject(Files.readString(schemaFile.toPath()));
    final JSONObject indexes = schema.getJSONObject("types").getJSONObject("Article").getJSONObject("indexes");
    for (final String name : indexes.keySet())
      edit.accept(indexes.getJSONObject(name));
    Files.writeString(schemaFile.toPath(), schema.toString());
    final File previous = new File(getDatabasePath(), "schema.prev.json");
    if (previous.exists())
      Files.delete(previous.toPath());
    reopenDatabase();
  }

  /**
   * REBUILD INDEX of ONE bucket sub-index (or CHECK DATABASE FIX of a corrupted one) re-created it from a copy of the definition,
   * which is written in the current format: that bucket applied the per-field analyzers while its siblings, still in the older
   * format, did not. One logical index then stored 'title:run' in one bucket and 'title:running' in the others. A single-bucket
   * rebuild now keeps the format of its siblings.
   */
  @Test
  void aSingleBucketRebuildKeepsTheFormatOfItsSiblings() throws IOException {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Article BUCKETS 3");
      database.command("sql", "CREATE PROPERTY Article.title STRING");
      database.command("sql", "CREATE PROPERTY Article.body STRING");
      database.command("sql", "CREATE INDEX ON Article (title, body) FULL_TEXT");
    });
    insertIntoBuckets(articleBucketNames(), "Running", 3);
    // An index of the previous format with a field analyzer configured
    editArticleIndexDefinitions(index -> {
      index.remove("ft_formatVersion");
      index.put("title_analyzer", EnglishAnalyzer.class.getName());
    });

    final TypeIndex typeIndex = (TypeIndex) database.getSchema().getIndexByName("Article[title,body]");
    database.command("sql", "REBUILD INDEX `" + typeIndex.getIndexesOnBuckets()[1].getName() + "`");

    final TypeIndex rebuilt = (TypeIndex) database.getSchema().getIndexByName("Article[title,body]");
    assertThat(rebuilt.getIndexesOnBuckets()).hasSize(3);
    for (final IndexInternal bucketIndex : rebuilt.getIndexesOnBuckets()) {
      assertThat(((LSMTreeFullTextIndex) bucketIndex).getFullTextMetadata().getFormatVersion()).as(bucketIndex.getName())
          .isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_LSM);
      assertThat(((LSMTreeFullTextIndex) bucketIndex).getIndexAnalyzer("title")).isInstanceOf(StandardAnalyzer.class);
    }
    assertThat(searchTitles("title:running", 50)).hasSize(9).isEqualTo(searchTitles("title:running", -1));

    // REBUILD INDEX of the whole index still migrates every bucket at once
    database.command("sql", "REBUILD INDEX `Article[title,body]`");
    for (final IndexInternal bucketIndex : ((TypeIndex) database.getSchema().getIndexByName("Article[title,body]"))
        .getIndexesOnBuckets())
      assertThat(((LSMTreeFullTextIndex) bucketIndex).getFullTextMetadata().getFormatVersion())
          .isEqualTo(FullTextIndexMetadata.CURRENT_FORMAT_VERSION);
    assertThat(searchTitles("title:runs", 50)).hasSize(9).isEqualTo(searchTitles("title:runs", -1));
  }

  /**
   * The top-k path analyzed the query once, with the first bucket's analyzers, and looked the keys up in every bucket. Buckets
   * that analyze it differently (a bucket sub-index re-created from a definition in another format, here through the schema API
   * as an embedded application can) were silently missed with a LIMIT and found without one. The top-k path now runs only when
   * every bucket analyzes the query the same way.
   */
  @Test
  void topKAnswersLikeTheGeneralPathWhenBucketsAnalyzeTheQueryDifferently() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Article BUCKETS 2");
      database.command("sql", "CREATE PROPERTY Article.title STRING");
      database.command("sql", "CREATE PROPERTY Article.body STRING");
      database.command("sql", """
          CREATE INDEX ON Article (title, body) FULL_TEXT METADATA {
          "title_analyzer": "org.apache.lucene.analysis.en.EnglishAnalyzer"}""");
    });
    // Stored with the English analyzer: 'title:run'
    insertIntoBuckets(articleBucketNames(), "Running", 5);

    // One bucket's sub-index re-created from a definition of the previous format: it indexes 'title' with the index-wide
    // analyzer, so its documents are stored as 'title:running'
    final IndexInternal legacyBucketIndex = ((TypeIndex) database.getSchema().getIndexByName("Article[title,body]"))
        .getIndexesOnBuckets()[0];
    final String legacyBucket = database.getSchema().getBucketById(legacyBucketIndex.getAssociatedBucketId()).getName();
    final FullTextIndexMetadata legacy = new FullTextIndexMetadata("Article", new String[] { "title", "body" }, -1);
    legacy.fromJSON(new JSONObject().put("similarity", "BM25").put("title_analyzer", EnglishAnalyzer.class.getName()));
    assertThat(legacy.getFormatVersion()).isEqualTo(FullTextIndexMetadata.FORMAT_VERSION_LSM);
    database.getSchema().dropIndex(legacyBucketIndex.getName());
    database.getSchema().buildBucketIndex("Article", legacyBucket, new String[] { "title", "body" })
        .withType(Schema.INDEX_TYPE.FULL_TEXT).withMetadata(legacy).withIndexName("Article[title,body]").create();

    final Set<Class<?>> titleAnalyzers = new HashSet<>();
    for (final IndexInternal bucketIndex : ((TypeIndex) database.getSchema().getIndexByName("Article[title,body]"))
        .getIndexesOnBuckets())
      titleAnalyzers.add(((LSMTreeFullTextIndex) bucketIndex).getIndexAnalyzer("title").getClass());
    assertThat(titleAnalyzers).containsExactlyInAnyOrder(StandardAnalyzer.class, EnglishAnalyzer.class);

    assertThat(searchTitles("title:running", -1)).hasSize(10);
    assertThat(searchTitles("title:running", 20)).isEqualTo(searchTitles("title:running", -1));
  }
}
