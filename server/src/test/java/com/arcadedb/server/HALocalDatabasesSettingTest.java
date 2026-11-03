/*
 * Copyright 2021-present Arcade Data Ltd (info@arcadedata.com)
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
package com.arcadedb.server;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.exception.DatabaseIsReadOnlyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GlobalConfiguration#HA_LOCAL_DATABASES} on the server side (SPORK E26): which names it lists, exact and by
 * prefix, and that a listed database is opened READ_ONLY and cannot be created through the server. The HA side is
 * {@code NodeLocalDatabaseBesideRaftGroupIT} in ha-raft.
 */
class HALocalDatabasesSettingTest extends StaticBaseServerTest {
  private static final String DATABASES = "./target/databases";

  private ArcadeDBServer server;

  @BeforeEach
  public void beginTest() {
    super.beginTest();
    for (final String name : new String[] { "warm", "w_rec_1", "hot" })
      try (final Database db = new DatabaseFactory(DATABASES + File.separator + name).create()) {
        db.transaction(() -> db.getSchema().createDocumentType("Ad"));
      }

    final ContextConfiguration config = new ContextConfiguration();
    config.setValue(GlobalConfiguration.SERVER_ROOT_PATH, "./target");
    config.setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, DEFAULT_PASSWORD_FOR_TESTS);
    config.setValue(GlobalConfiguration.HA_LOCAL_DATABASES, " warm , w_* ,");
    server = new ArcadeDBServer(config);
    server.start();
  }

  @AfterEach
  public void endTest() {
    if (server != null && server.isStarted())
      server.stop();
    super.endTest();
  }

  @Test
  void listsExactNamesAndPrefixes() {
    assertThat(server.isHALocalDatabase("warm")).isTrue();
    assertThat(server.isHALocalDatabase("w_rec_1")).isTrue();
    assertThat(server.isHALocalDatabase("w_")).isTrue();
    assertThat(server.isHALocalDatabase("warm2")).as("an exact entry is not a prefix").isFalse();
    assertThat(server.isHALocalDatabase("hot")).isFalse();
    assertThat(server.isHALocalDatabase("")).isFalse();
    assertThat(server.isHALocalDatabase(null)).isFalse();
    assertThat(ArcadeDBServer.parseHALocalDatabases(null)).isEmpty();
    assertThat(ArcadeDBServer.parseHALocalDatabases(" ")).isEmpty();
  }

  @Test
  void listedDatabasesOpenReadOnlyAndTheOthersReadWrite() {
    for (final String name : new String[] { "warm", "w_rec_1" }) {
      final ServerDatabase db = server.getDatabaseForOperator(name);
      assertThat(db.getMode()).as(name).isEqualTo(ComponentFile.MODE.READ_ONLY);
      assertThatThrownBy(() -> db.transaction(() -> db.newDocument("Ad").set("x", 1).save()))
          .isInstanceOf(DatabaseIsReadOnlyException.class);
    }

    final ServerDatabase hot = server.getDatabaseForOperator("hot");
    assertThat(hot.getMode()).isEqualTo(ComponentFile.MODE.READ_WRITE);
    hot.transaction(() -> hot.newDocument("Ad").set("x", 1).save());
    assertThat(hot.countType("Ad", true)).isEqualTo(1);
  }

  @Test
  void listedNamesAreNeverCreatedThroughTheServer() {
    assertThatThrownBy(() -> server.createDatabase("w_new", ComponentFile.MODE.READ_WRITE))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(GlobalConfiguration.HA_LOCAL_DATABASES.getKey());
    assertThatThrownBy(() -> server.getDatabase("w_new", true, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new File(DATABASES, "w_new")).doesNotExist();

    assertThat(server.createDatabase("other", ComponentFile.MODE.READ_WRITE).getMode()).isEqualTo(ComponentFile.MODE.READ_WRITE);
  }

  /** The old copy is dropped once the rewritten one is serving: a READ_ONLY database refuses drop(), the server closes and deletes it. */
  @Test
  void aListedDatabaseIsDroppedDespiteBeingReadOnly() {
    assertThat(server.getDatabaseForOperator("warm").getMode()).isEqualTo(ComponentFile.MODE.READ_ONLY);
    new ServerControlPlane(server).dropDatabase("warm");
    assertThat(server.existsDatabase("warm")).isFalse();
    assertThat(new File(DATABASES, "warm")).doesNotExist();
  }
}
