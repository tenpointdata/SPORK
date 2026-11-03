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
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.schema.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Installs SPORK schema v1 (docs/spork/01-architecture.md 1.2, 2.1, 2.2, 2.5) on one database. The DDL is the SQL scripts
 * under {@code spork/schema/v1/} on the classpath, the same text {@code docs/spork/config/schema-ddl.sql} carries
 * ({@link #documentation()}): {@code common.sql} on every database, then the script of the database's {@link Kind}. Every
 * statement is idempotent ({@code IF NOT EXISTS}, or an ALTER that sets a value), so {@link #install} runs on a fresh
 * database and again on an installed one, which is how a new period database is created and how a node re-checks its
 * schema at start.
 * <p>
 * Three indexes need the Java builder because SQL cannot give them 64 KB pages ({@code CREATE INDEX} has no PAGESIZE and
 * FULL_TEXT / GEOSPATIAL ignore {@code indexDefaultPageSize}): {@code Place.point} GEOSPATIAL and {@code Evidence.body}
 * FULL_TEXT on identity, {@code TextBody.text} FULL_TEXT on txt_&lt;p&gt;.
 */
public final class SporkSchema {
  public static final int    VERSION         = 1;
  public static final int    JAVA_INDEX_PAGE_SIZE = 65536;
  static final        String RESOURCE_ROOT   = "/spork/schema/v1/";
  static final        String COMMON          = "common.sql";

  /** The SPORK databases (1.2) that schema v1 covers. {@code ops} and {@code audit} are not in v1. */
  public enum Kind {
    IDENTITY("identity.sql"), RECDIR("recdir.sql"), REC("rec.sql"), TXT("txt.sql"), MEDIA("media.sql");

    private final String script;

    Kind(final String script) {
      this.script = script;
    }

    public String script() {
      return script;
    }

    /** The kind of a database by its name: identity, recdir, rec_&lt;p&gt;, txt_&lt;p&gt; (and its warm txt_&lt;p&gt;_w&lt;n&gt;), media_&lt;p&gt;. */
    public static Kind forDatabase(final String name) {
      if ("identity".equals(name))
        return IDENTITY;
      if ("recdir".equals(name))
        return RECDIR;
      if (name.startsWith("rec_"))
        return REC;
      if (name.startsWith("txt_"))
        return TXT;
      if (name.startsWith("media_"))
        return MEDIA;
      throw new IllegalArgumentException("Not a SPORK schema v1 database: " + name);
    }
  }

  private SporkSchema() {
  }

  /** Installs (or re-checks) schema v1 on a database named after its kind ({@link Kind#forDatabase}). */
  public static void install(final Database database) {
    install(database, Kind.forDatabase(database.getName()));
  }

  public static void install(final Database database, final Kind kind) {
    if (database.isTransactionActive())
      throw new IllegalStateException("Install the SPORK schema outside a transaction: schema changes are not transactional");

    run(database, COMMON);
    run(database, kind.script());

    switch (kind) {
    case IDENTITY -> {
      javaIndex(database, "Place", "point", Schema.INDEX_TYPE.GEOSPATIAL);
      javaIndex(database, "Evidence", "body", Schema.INDEX_TYPE.FULL_TEXT);
    }
    case TXT -> javaIndex(database, "TextBody", "text", Schema.INDEX_TYPE.FULL_TEXT);
    default -> {
    }
    }

    database.transaction(() -> database.command("sql",
        "UPDATE SporkSchemaInfo SET version = ?, installedAt = ? UPSERT WHERE name = ?", VERSION, System.currentTimeMillis(),
        kind.name().toLowerCase(Locale.ROOT)));
  }

  /** The statements of one script, comments removed, in order. */
  public static List<String> statements(final String script) {
    return split(resource(script));
  }

  /** The text of {@code docs/spork/config/schema-ddl.sql}: a header, then every script in install order. */
  public static String documentation() {
    final StringBuilder doc = new StringBuilder();
    doc.append("-- SPORK schema v").append(VERSION).append(" (01-architecture.md 1.2, 2.1, 2.2, 2.5; 02-retrieval-speed.md C8, C9).\n");
    doc.append("--\n");
    doc.append("-- GENERATED, do not edit: this file is the concatenation of the scripts SporkSchema installs, from\n");
    doc.append("-- spork-keys/src/main/resources/spork/schema/v1/. Edit those and regenerate with\n");
    doc.append("--   mvn -o -pl spork-keys -am test -Dtest=SporkSchemaTest -Dsurefire.failIfNoSpecifiedTests=false -Dspork.schema.regenerate=true\n");
    doc.append("-- SporkSchemaTest fails while the two differ. Install with com.arcadedb.spork.schema.SporkSchema.install(database):\n");
    doc.append("-- common.sql runs on every database, then the script of the database's kind (identity, recdir, rec_<p>, txt_<p>,\n");
    doc.append("-- media_<p>), then the indexes marked JAVA (64 KB FULL_TEXT and GEOSPATIAL, which SQL cannot size).\n");
    doc.append("-- Every statement is idempotent: the installer runs on a fresh database and again on an installed one.\n");
    appendScript(doc, COMMON);
    for (final Kind kind : Kind.values())
      appendScript(doc, kind.script());
    return doc.toString();
  }

  private static void appendScript(final StringBuilder doc, final String script) {
    doc.append("\n-- >>> ").append(script).append('\n').append(resource(script));
  }

  private static void run(final Database database, final String script) {
    for (final String statement : statements(script))
      try {
        database.command("sql", statement);
      } catch (final RuntimeException e) {
        throw new CommandExecutionException("SPORK schema v" + VERSION + " " + script + " failed at: " + statement, e);
      }
  }

  private static void javaIndex(final Database database, final String type, final String property, final Schema.INDEX_TYPE indexType) {
    database.getSchema().buildTypeIndex(type, new String[] { property }).withType(indexType).withPageSize(JAVA_INDEX_PAGE_SIZE)
        .withIgnoreIfExists(true).create();
  }

  static String resource(final String script) {
    try (final InputStream in = SporkSchema.class.getResourceAsStream(RESOURCE_ROOT + script)) {
      if (in == null)
        throw new IllegalStateException("Missing schema resource " + RESOURCE_ROOT + script);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Splits a script on ';' and drops '--' comments, both only outside quotes ('...', "..." and `...`), so a ';' or a "--"
   * inside a literal or a quoted setting name survives.
   */
  static List<String> split(final String script) {
    final List<String> out = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < script.length(); i++) {
      final char c = script.charAt(i);
      if (quote != 0) {
        current.append(c);
        if (c == quote)
          quote = 0;
      } else if (c == '\'' || c == '"' || c == '`') {
        quote = c;
        current.append(c);
      } else if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
        while (i < script.length() && script.charAt(i) != '\n')
          i++;
        current.append('\n');
      } else if (c == ';') {
        addStatement(out, current);
      } else
        current.append(c);
    }
    addStatement(out, current);
    return out;
  }

  private static void addStatement(final List<String> out, final StringBuilder current) {
    final String s = current.toString().strip();
    if (!s.isEmpty())
      out.add(s);
    current.setLength(0);
  }
}
