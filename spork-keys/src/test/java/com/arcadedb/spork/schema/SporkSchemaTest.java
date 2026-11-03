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
package com.arcadedb.spork.schema;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.engine.Bucket;
import com.arcadedb.engine.PaginatedComponent;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.keys.SporkKeys;
import com.arcadedb.spork.keys.UpstreamRid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SporkSchemaTest {
  private static final Path DOC = Path.of("..", "docs", "spork", "config", "schema-ddl.sql");

  @ParameterizedTest
  @EnumSource(SporkSchema.Kind.class)
  void installsOnAFreshDatabaseTwiceWithTheSameResult(final SporkSchema.Kind kind) {
    withDatabase(kind, database -> {
      SporkSchema.install(database, kind);
      final String first = snapshot(database);
      SporkSchema.install(database, kind);
      assertThat(snapshot(database)).isEqualTo(first);

      assertThat(database.query("sql", "SELECT version FROM SporkSchemaInfo").stream().toList()).hasSize(1)
          .allSatisfy(r -> assertThat(r.<Integer>getProperty("version")).isEqualTo(SporkSchema.VERSION));
      assertThat(database.getSchema().existsType("Prov")).isTrue();
      assertThat(database.getSchema().existsType("AuditEntry")).isTrue();
    });
  }

  @Test
  void identityVersionsIdentityAndMembershipsAndHashesItsKeys() {
    withDatabase(SporkSchema.Kind.IDENTITY, database -> {
      SporkSchema.install(database, SporkSchema.Kind.IDENTITY);
      final Schema schema = database.getSchema();
      assertThat(schema.getType("Identity").isVersioned()).isTrue();
      assertThat(schema.getType("MEMBER_OF").isVersioned()).isTrue();
      assertThat(schema.getType("Assertion").isVersioned()).isTrue();
      assertThat(schema.getType("Phone").isVersioned()).isFalse();

      for (final String type : List.of("Phone", "Email", "Handle", "Name", "Business", "ImageHash", "Url")) {
        assertThat(schema.getType(type).isSubTypeOf("Identifier")).as(type).isTrue();
        assertThat(pageSize(schema.getType(type))).as(type).isEqualTo(16384);
        assertThat(index(schema.getType(type), "key").getType()).as(type).isEqualTo(Schema.INDEX_TYPE.HASH);
        assertThat(index(schema.getType(type), "key").isUnique()).as(type).isTrue();
      }
      assertThat(schema.getType("Identifier").getBuckets(false)).isEmpty();
      assertThat(index(schema.getType("Place"), "placeId").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(index(schema.getType("Place"), "point").getType()).isEqualTo(Schema.INDEX_TYPE.GEOSPATIAL);
      assertThat(index(schema.getType("Place"), "point").getPageSize()).isEqualTo(SporkSchema.JAVA_INDEX_PAGE_SIZE);
      assertThat(index(schema.getType("Evidence"), "body").getType()).isEqualTo(Schema.INDEX_TYPE.FULL_TEXT);
      assertThat(index(schema.getType("Target"), "identifierKeys by item").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(schema.getType("SIMILAR_STYLE").isSubTypeOf("SoftLink")).isTrue();
      assertThat(schema.getType("Identity").getPolymorphicProperty("_prov").getType().name()).isEqualTo("EMBEDDED");

      // A membership round trip: identifier keyed by spork-keys, versioned edge stamped by the engine
      database.transaction(() -> {
        final MutableVertex phone = database.newVertex("Phone").set("key", SporkKeys.phone("(713) 555-0142")).save();
        final MutableVertex identity = database.newVertex("Identity").set("key", "idn_01").set("kind", "person").save();
        phone.newEdge("MEMBER_OF", identity, "decisionId", "rd_01").save();
      });
      try (final ResultSet rs = database.query("sql", "SELECT expand(out('MEMBER_OF')) FROM Phone WHERE key = ?", "ph:7135550142")) {
        assertThat(rs.next().<String>getProperty("key")).isEqualTo("idn_01");
      }
      assertThatThrownBy(() -> database.transaction(() -> database.newVertex("Phone").set("key", "ph:7135550142").save()))
          .isInstanceOf(DuplicatedKeyException.class);
    });
  }

  @Test
  void recordCarriesProvenanceMarkingsAndPointLookups() {
    withDatabase(SporkSchema.Kind.REC, database -> {
      SporkSchema.install(database, SporkSchema.Kind.REC);
      final DocumentType record = database.getSchema().getType("Record");
      assertThat(pageSize(record)).isEqualTo(32768);
      assertThat(index(record, "record_id").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(index(record, "upstreamRid").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(index(record, "tenantId", "importedId").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(index(record, "textHash").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(record.getPolymorphicProperty("styleVec").getType().name()).isEqualTo("ARRAY_OF_FLOATS");
      assertThat(database.getSchema().getType("HAS_PHONE")).isNotNull();

      final long rid = UpstreamRid.parse("168:8");
      database.transaction(() -> {
        final MutableVertex r = database.newVertex("Record").set("record_id", RecordId.of(3, 1)).set("upstreamRid", rid)
            .set("markings", 0b101L).set("styleVec", new float[24]);
        final MutableDocument prov = r.newEmbeddedDocument("Prov", "_prov");
        prov.set("sourceId", "redlight").set("assertedBy", "system:ingest");
        r.save();
        // An analyst import has no upstreamRid: the null-skipping UNIQUE HASH takes many of them
        database.newVertex("Record").set("record_id", RecordId.of(3, 2)).set("tenantId", "t1").set("importedId", "a").save();
        database.newVertex("Record").set("record_id", RecordId.of(3, 3)).set("tenantId", "t1").set("importedId", "b").save();
      });
      try (final ResultSet rs = database.query("sql", "SELECT _prov.assertedBy AS assertedBy, markings FROM Record WHERE upstreamRid = ?", rid)) {
        final var row = rs.next();
        assertThat(row.<String>getProperty("assertedBy")).isEqualTo("system:ingest");
        assertThat(row.<Long>getProperty("markings")).isEqualTo(5L);
      }
      assertThatThrownBy(() -> database.transaction(
          () -> database.newVertex("Record").set("record_id", RecordId.of(3, 4)).set("tenantId", "t1").set("importedId", "a").save()))
          .isInstanceOf(DuplicatedKeyException.class);
    });
  }

  @Test
  void recdirMapsAnUpstreamRidToItsPeriod() {
    withDatabase(SporkSchema.Kind.RECDIR, database -> {
      SporkSchema.install(database);
      final DocumentType dir = database.getSchema().getType("RecordDir");
      assertThat(pageSize(dir)).isEqualTo(16384);
      final TypeIndex byRid = index(dir, "upstreamRid");
      assertThat(byRid.getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(byRid.isUnique()).isTrue();
      assertThat(byRid.getPageSize()).isEqualTo(4096);   // LONG key: the engine's 4 KB HASH default (round 6)

      final long rid = UpstreamRid.parse("#168:8");
      database.transaction(() -> database.newDocument("RecordDir").set("upstreamRid", rid).set("period", (short) 3).save());
      try (final ResultSet rs = database.query("sql", "SELECT period FROM RecordDir WHERE upstreamRid = ?", rid)) {
        assertThat(rs.next().<Short>getProperty("period")).isEqualTo((short) 3);
      }
      assertThatThrownBy(
          () -> database.transaction(() -> database.newDocument("RecordDir").set("upstreamRid", rid).set("period", (short) 4).save()))
          .isInstanceOf(DuplicatedKeyException.class);
      assertThatThrownBy(() -> database.transaction(() -> database.newDocument("RecordDir").set("upstreamRid", rid + 1).save()))
          .hasMessageContaining("period");
    });
  }

  @Test
  void textBodyIsKeyedByItsExactByteHashWithAFullTextIndex() {
    withDatabase(SporkSchema.Kind.TXT, database -> {
      SporkSchema.install(database, SporkSchema.Kind.TXT);
      final DocumentType text = database.getSchema().getType("TextBody");
      assertThat(pageSize(text)).isEqualTo(32768);
      assertThat(index(text, "textHash").getType()).isEqualTo(Schema.INDEX_TYPE.HASH);
      assertThat(index(text, "text").getType()).isEqualTo(Schema.INDEX_TYPE.FULL_TEXT);
      assertThat(index(text, "text").getPageSize()).isEqualTo(SporkSchema.JAVA_INDEX_PAGE_SIZE);
      assertThat(text.getPolymorphicProperty("text").isExternal()).isTrue();
    });
  }

  @Test
  void kindFollowsTheDatabaseName() {
    assertThat(SporkSchema.Kind.forDatabase("identity")).isEqualTo(SporkSchema.Kind.IDENTITY);
    assertThat(SporkSchema.Kind.forDatabase("recdir")).isEqualTo(SporkSchema.Kind.RECDIR);
    assertThat(SporkSchema.Kind.forDatabase("rec_2026q4")).isEqualTo(SporkSchema.Kind.REC);
    assertThat(SporkSchema.Kind.forDatabase("txt_3_w1")).isEqualTo(SporkSchema.Kind.TXT);
    assertThat(SporkSchema.Kind.forDatabase("media_3")).isEqualTo(SporkSchema.Kind.MEDIA);
    assertThatThrownBy(() -> SporkSchema.Kind.forDatabase("ops")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void splitKeepsQuotedSemicolonsAndDropsComments() {
    assertThat(SporkSchema.split("A 'x;--y'; -- c;\nB `a--b`;\n-- only\n")).containsExactly("A 'x;--y'", "B `a--b`");
  }

  /** docs/spork/config/schema-ddl.sql is generated from the scripts; -Dspork.schema.regenerate=true rewrites it. */
  @Test
  void documentedDdlMatchesTheInstalledScripts() throws IOException {
    final String expected = SporkSchema.documentation();
    if (Boolean.getBoolean("spork.schema.regenerate"))
      Files.writeString(DOC, expected, StandardCharsets.UTF_8);
    assertThat(Files.readString(DOC, StandardCharsets.UTF_8)).isEqualTo(expected);
  }

  private static TypeIndex index(final DocumentType type, final String... properties) {
    final List<TypeIndex> found = type.getIndexesByProperties(properties[0], Arrays.copyOfRange(properties, 1, properties.length));
    assertThat(found).as(type.getName() + " " + String.join(",", properties)).isNotEmpty();
    return found.getFirst();
  }

  private static int pageSize(final DocumentType type) {
    final List<Bucket> buckets = type.getBuckets(false);
    assertThat(buckets).hasSize(1);
    return ((PaginatedComponent) buckets.getFirst()).getPageSize();
  }

  /** Types, bucket page sizes, versioning, properties and indexes, sorted, as one string. */
  private static String snapshot(final Database database) {
    final TreeMap<String, String> types = new TreeMap<>();
    for (final DocumentType t : database.getSchema().getTypes()) {
      final StringBuilder s = new StringBuilder();
      s.append(t.getType()).append(" super=").append(t.getSuperTypes().stream().map(DocumentType::getName).toList());
      s.append(" buckets=").append(t.getBuckets(false).stream().map(b -> ((PaginatedComponent) b).getPageSize()).toList());
      s.append(" versioned=").append(t.isVersioned());
      final TreeSet<String> props = new TreeSet<>();
      for (final Property p : t.getProperties())
        props.add(p.getName() + ":" + p.getType() + (p.isMandatory() ? "!" : ""));
      s.append(" props=").append(props);
      final TreeSet<String> idx = new TreeSet<>();
      for (final TypeIndex i : t.getAllIndexes(false))
        idx.add(i.getName() + ":" + i.getType() + (i.isUnique() ? ":U" : "") + ":" + i.getPageSize());
      s.append(" indexes=").append(idx);
      types.put(t.getName(), s.toString());
    }
    return types.toString();
  }

  private static void withDatabase(final SporkSchema.Kind kind, final Consumer<Database> test) {
    final String name = switch (kind) {
      case IDENTITY -> "identity";
      case RECDIR -> "recdir";
      case REC -> "rec_1";
      case TXT -> "txt_1";
      case MEDIA -> "media_1";
    };
    try (final DatabaseFactory factory = new DatabaseFactory("./target/databases/spork-schema/" + name)) {
      if (factory.exists())
        factory.open().drop();
      final Database database = factory.create();
      try {
        test.accept(database);
      } finally {
        database.drop();
      }
    }
  }
}
