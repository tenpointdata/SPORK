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
package com.arcadedb.query.opencypher;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.opencypher.executor.operators.NodeIndexSeek;
import com.arcadedb.query.sql.executor.BasicCommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.security.SecurityDatabaseUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An openCypher index seek that hands its vertices out unloaded (SPORK retrieval speed, item S5) still asks the READ_RECORD
 * permission the load used to ask: a user that may not read a type must not learn the count or the RIDs of its records from a
 * seek whose rows are only counted or identified, where the eager load refused the statement.
 */
class NodeIndexSeekReadPermissionTest {
  private static final String DATABASE_PATH = "./target/databases/node-index-seek-read-permission";
  private static final int    ROWS          = 40;

  private DatabaseFactory factory;
  private Database        database;

  @BeforeEach
  void setUp() {
    factory = new DatabaseFactory(DATABASE_PATH).setSecurity(db -> {
    });
    if (factory.exists())
      factory.open().drop();
    database = factory.create();
    database.getSchema().createVertexType("Secret").createProperty("grp", Type.LONG);
    database.getSchema().getType("Secret").createProperty("id", Type.LONG);
    // A COMPOSITE KEY: A SEEK ON grp ALONE IS A PREFIX SEEK, WHICH STREAMS THE INDEX AND HANDS ITS VERTICES OUT UNLOADED
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "Secret", "grp", "id");
    database.transaction(() -> {
      for (int i = 0; i < ROWS; i++)
        database.newVertex("Secret").set("grp", (long) (i % 2)).set("id", (long) i).save();
    });
  }

  @AfterEach
  void tearDown() {
    DatabaseContext.INSTANCE.getContext(database.getDatabasePath()).setCurrentUser(null);
    database.drop();
    factory.close();
  }

  @Test
  void aUserThatMayNotReadTheTypeIsRefusedByAPrefixSeek() {
    runAs(EnumSet.complementOf(EnumSet.of(SecurityDatabaseUser.ACCESS.READ_RECORD)));
    final Throwable thrown = catchThrowable(() -> seek(1L));
    assertThat(thrown).as("the seek must ask the permission the load used to ask").isInstanceOf(SecurityException.class);
  }

  @Test
  void aUserThatMayReadTheTypeGetsTheRows() {
    runAs(EnumSet.allOf(SecurityDatabaseUser.ACCESS.class));
    final List<Result> rows = seek(1L);
    assertThat(rows).hasSize(ROWS / 2);
    for (final Result row : rows)
      assertThat(row.getIdentity()).isNotNull();
  }

  private void runAs(final Set<SecurityDatabaseUser.ACCESS> granted) {
    DatabaseContext.INSTANCE.getContext(database.getDatabasePath()).setCurrentUser(userGranting(granted));
  }

  private List<Result> seek(final long group) {
    final BasicCommandContext context = new BasicCommandContext();
    context.setDatabase(database);
    final NodeIndexSeek seek = new NodeIndexSeek("s", "Secret", "grp", group, "Secret[grp,id]", List.of("grp", "id"),
        List.<Object>of(group), 1.0, 1L);
    try (final ResultSet resultSet = seek.execute(context, -1)) {
      return resultSet.stream().toList();
    }
  }

  /** A principal that allows the database wholesale and exactly {@code granted} on every file. */
  private static SecurityDatabaseUser userGranting(final Set<SecurityDatabaseUser.ACCESS> granted) {
    return new SecurityDatabaseUser() {
      @Override
      public String getName() {
        return "restricted";
      }

      @Override
      public boolean requestAccessOnDatabase(final DATABASE_ACCESS access) {
        return true;
      }

      @Override
      public boolean requestAccessOnFile(final int fileId, final ACCESS access) {
        return granted.contains(access);
      }

      @Override
      public long getResultSetLimit() {
        return -1L;
      }

      @Override
      public long getReadTimeout() {
        return -1L;
      }
    };
  }
}
