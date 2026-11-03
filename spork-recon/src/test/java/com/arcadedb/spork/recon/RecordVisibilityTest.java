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
package com.arcadedb.spork.recon;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.query.structured.RecordFilter;
import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.schema.SporkSchema;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hidden-RID set a count consults instead of reading records: built from v1's sparse {@code (markings)} and
 * {@code (tenantId)} indexes, it must name exactly the records the person cannot see, so an entry outside it is counted unread.
 */
class RecordVisibilityTest {
  private static final String PATH = "target/databases-visibility/rec_1";

  private Database database;
  private RID      plain;
  private RID      zeroMarked;
  private RID      held;
  private RID      unheld;
  private RID      ownImport;
  private RID      otherImport;

  @BeforeEach
  void createPartition() {
    FileUtils.deleteRecursively(new File(PATH));
    database = new DatabaseFactory(PATH).create();
    SporkSchema.install(database);
    database.transaction(() -> {
      plain = record(1).save().getIdentity();
      zeroMarked = record(2).set("markings", 0L).save().getIdentity();
      held = record(3).set("markings", 0b01L).save().getIdentity();
      unheld = record(4).set("markings", 0b11L).save().getIdentity();
      ownImport = record(5).set("tenantId", "deliverfund").set("importedId", "a").save().getIdentity();
      otherImport = record(6).set("tenantId", "acme").set("importedId", "b").save().getIdentity();
    });
  }

  private MutableDocument record(final int n) {
    return database.newVertex("Record").set("record_id", RecordId.of(1, n));
  }

  @AfterEach
  void dropPartition() {
    if (database != null)
      database.drop();
  }

  @Test
  void theHiddenSetIsExactlyWhatThePersonCannotSee() {
    final RecordFilter visibility = new RecordVisibility("deliverfund", 0b01L).forPartition(database);

    assertThat(visibility.mustRead(unheld)).isTrue();
    assertThat(visibility.mustRead(otherImport)).isTrue();
    for (final RID rid : new RID[] { plain, zeroMarked, held, ownImport })
      assertThat(visibility.mustRead(rid)).as(rid.toString()).isFalse();
  }

  @Test
  void theRecordRuleAgreesWithTheSet() {
    final RecordVisibility rule = new RecordVisibility("deliverfund", 0b01L);

    assertThat(rule.accepts(unheld.asDocument())).isFalse();
    assertThat(rule.accepts(otherImport.asDocument())).isFalse();
    assertThat(rule.accepts(held.asDocument())).isTrue();
    assertThat(rule.accepts(ownImport.asDocument())).isTrue();
  }
}
