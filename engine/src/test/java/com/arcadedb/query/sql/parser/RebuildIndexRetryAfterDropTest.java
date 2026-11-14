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
package com.arcadedb.query.sql.parser;

import com.arcadedb.TestHelper;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rebuild attempt that fails with a NeedRetryException AFTER it dropped the index (a writer's commit conflicting with a build
 * chunk, a lock timeout inside the build) leaves the schema without the index: the failed build cleans up after itself. The next
 * attempt used to start again from the index object it had dropped, and the first thing it asked of it (isCompacting) refused with
 * "Index '...' is not valid. Probably has been drop or rebuilt", so a rebuild meant to ride out contention left the type with no
 * index at all. Seen as the intermittent failure of
 * LSMIVFVectorIndexReviewTest#createAndRebuildIndexCompleteUnderLiveIngestWithoutTheDatabaseLock.
 */
class RebuildIndexRetryAfterDropTest extends TestHelper {

  @Test
  void aRetryAfterTheDropRecreatesTheIndexFromItsDefinition() {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType("Doc", 3);
      type.createProperty("name", String.class);
      database.getSchema().buildTypeIndex("Doc", new String[] { "name" }).withType(Schema.INDEX_TYPE.LSM_TREE).withUnique(false)
          .withIndexName("byName").create();
      for (int i = 0; i < 300; i++)
        database.newDocument("Doc").set("name", "n" + i).save();
    });

    final Index idx = database.getSchema().getIndexByName("byName");
    final AtomicInteger calls = new AtomicInteger();
    // Fails the first attempt's build once it has started, which is after the drop. maxAttempts = 1 so the builder does not
    // absorb the failure itself and it reaches the retry loop of the statement
    final Index.BuildIndexCallback failOnce = (document, totalIndexed) -> {
      if (calls.incrementAndGet() == 1)
        throw new ConcurrentModificationException("simulated conflict of a build chunk with a writer");
    };

    final Index rebuilt = RebuildIndexStatement.buildIndex(1, database, failOnce, idx, 100);

    assertThat(calls.get()).isGreaterThan(300);
    assertThat(rebuilt).isInstanceOf(TypeIndex.class);
    assertThat(rebuilt.getName()).isEqualTo("byName");
    assertThat(database.getSchema().getIndexByName("byName")).isSameAs(rebuilt);
    assertThat(((TypeIndex) rebuilt).getIndexesOnBuckets()).hasSize(3);
    assertThat(rebuilt.countEntries()).isEqualTo(300);
    database.transaction(() -> {
      try (final ResultSet rs = database.query("sql", "SELECT FROM Doc WHERE name = 'n42'")) {
        assertThat(rs.stream().count()).isEqualTo(1);
      }
    });
  }
}
