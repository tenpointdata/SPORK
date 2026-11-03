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
package com.arcadedb.database;

import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPORK retrieval-speed workstream S14 (docs/spork/02-retrieval-speed.md, 4.2): {@code LocalDatabase.checkPermissionsOnFile} is
 * asked once per record read, and the answer is the same for every record of the file as long as the same user is bound to the
 * thread. The GRANTED verdict of the last (file, access) pair is kept on the thread's database context, so a hydrate of a
 * thousand records of one bucket asks the user once. A denial is never cached, a different file or access asks again, binding
 * a user to the thread forgets the verdict, and so does any change of the user's permission state
 * ({@link SecurityDatabaseUser#getAccessVersion()}), with or without a rebind.
 */
class PermissionVerdictCacheTest {
  private static final String PATH = "./target/databases/permission-verdict-cache";

  private DatabaseFactory factory;
  private Database        database;
  private final List<RID> aRids = new ArrayList<>();
  private final List<RID> bRids = new ArrayList<>();

  /**
   * A user whose answers are counted per (file, access). Its permission state is published as one value, as
   * {@code ServerSecurityDatabaseUser}'s {@code AccessSnapshot} is: a revocation replaces it, the user object stays.
   */
  private static class CountingUser implements SecurityDatabaseUser {
    private final    String        name;
    private volatile boolean[]     readsAllowed;
    private final    AtomicInteger fileRequests = new AtomicInteger();

    private CountingUser(final String name, final boolean allowReads) {
      this.name = name;
      this.readsAllowed = new boolean[] { allowReads };
    }

    /** What a group refresh does to a cached database user: a new state, on the SAME user object. */
    void setReadsAllowed(final boolean allowReads) {
      this.readsAllowed = new boolean[] { allowReads };
    }

    @Override
    public Object getAccessVersion() {
      return readsAllowed;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean requestAccessOnDatabase(final DATABASE_ACCESS access) {
      return true;
    }

    @Override
    public boolean requestAccessOnFile(final int fileId, final ACCESS access) {
      fileRequests.incrementAndGet();
      return access != ACCESS.READ_RECORD || readsAllowed[0];
    }

    @Override
    public long getResultSetLimit() {
      return -1L;
    }

    @Override
    public long getReadTimeout() {
      return -1L;
    }
  }

  @BeforeEach
  void setUp() {
    FileUtils.deleteRecursively(new File(PATH));
    // a security manager, so the checks run: embedded databases without one skip them altogether
    factory = new DatabaseFactory(PATH).setSecurity(db -> {
    });
    database = factory.create();
    database.getSchema().buildDocumentType().withName("A").withTotalBuckets(1).create();
    database.getSchema().buildDocumentType().withName("B").withTotalBuckets(1).create();
    database.transaction(() -> {
      for (int i = 0; i < 100; i++) {
        aRids.add(database.newDocument("A").set("n", i).save().getIdentity());
        bRids.add(database.newDocument("B").set("n", i).save().getIdentity());
      }
    });
  }

  @AfterEach
  void tearDown() {
    bind(null);
    if (database != null && database.isOpen())
      database.drop();
    if (factory != null)
      factory.close();
    FileUtils.deleteRecursively(new File(PATH));
  }

  private void bind(final SecurityDatabaseUser user) {
    DatabaseContext.INSTANCE.getContext(database.getDatabasePath()).setCurrentUser(user);
  }

  @Test
  void readsOfOneFileAskTheUserOnce() {
    final CountingUser user = new CountingUser("reader", true);
    bind(user);

    for (final RID rid : aRids)
      assertThat(database.lookupByRID(rid, true).asDocument().getInteger("n")).isNotNull();
    assertThat(user.fileRequests.get()).as("one verdict for a hundred reads of the same file").isEqualTo(1);

    // another file asks again, then is cached in turn; going back to the first file asks once more
    for (final RID rid : bRids)
      database.lookupByRID(rid, true);
    assertThat(user.fileRequests.get()).isEqualTo(2);
    database.lookupByRID(aRids.getFirst(), true);
    assertThat(user.fileRequests.get()).isEqualTo(3);
    for (final RID rid : aRids)
      database.lookupByRID(rid, true);
    assertThat(user.fileRequests.get()).isEqualTo(3);
  }

  @Test
  void aDenialIsNeverCached() {
    final CountingUser user = new CountingUser("denied", false);
    bind(user);

    for (int i = 0; i < 5; i++)
      assertThatThrownBy(() -> database.lookupByRID(aRids.getFirst(), true)).isInstanceOf(SecurityException.class)
          .hasMessageContaining("read records");
    assertThat(user.fileRequests.get()).as("every refused read asked the user").isEqualTo(5);
  }

  @Test
  void bindingAUserForgetsTheVerdictAndAnotherUserIsAskedForItsOwn() {
    final CountingUser first = new CountingUser("first", true);
    final CountingUser second = new CountingUser("second", true);

    bind(first);
    database.lookupByRID(aRids.getFirst(), true);
    database.lookupByRID(aRids.get(1), true);
    assertThat(first.fileRequests.get()).isEqualTo(1);

    bind(second);
    database.lookupByRID(aRids.getFirst(), true);
    assertThat(second.fileRequests.get()).as("a cached verdict of another user is not reused").isEqualTo(1);
    assertThat(first.fileRequests.get()).isEqualTo(1);

    // the same user bound again, as every new request binds it: the verdict of the previous request is not carried over
    bind(second);
    database.lookupByRID(aRids.getFirst(), true);
    assertThat(second.fileRequests.get()).isEqualTo(2);
  }

  @Test
  void aDifferentAccessOnTheSameFileAsksAgain() {
    final CountingUser user = new CountingUser("writer", true);
    bind(user);

    database.lookupByRID(aRids.getFirst(), true);
    assertThat(user.fileRequests.get()).isEqualTo(1);
    // an update of a record of the same file is a different verdict
    database.transaction(() -> database.lookupByRID(aRids.getFirst(), true).asDocument().modify().set("n", 1_000).save());
    assertThat(user.fileRequests.get()).isGreaterThan(1);
  }

  /**
   * Review finding (major): the verdict was keyed on the user object only, but a group change does not replace the user: it
   * swaps the permission state inside it. Postgres binds the user once per connection, and a gRPC client-managed transaction
   * once per transaction, so a revocation never took effect on a thread that kept reading the same file. The verdict is now
   * kept with the access version it was given under.
   */
  @Test
  void aRevocationOnTheSameBoundUserRefusesTheNextReadWithoutARebind() {
    final CountingUser user = new CountingUser("pollster", true);
    bind(user);

    database.lookupByRID(aRids.getFirst(), true);
    database.lookupByRID(aRids.get(1), true);
    assertThat(user.fileRequests.get()).isEqualTo(1);

    // the admin revokes readRecord: the same user object, still bound to this thread, no new request in between
    user.setReadsAllowed(false);
    assertThatThrownBy(() -> database.lookupByRID(aRids.get(2), true)).as("the cached grant must not outlive the revocation")
        .isInstanceOf(SecurityException.class).hasMessageContaining("read records");

    // and granted again: asked again, then cached under the new state
    user.setReadsAllowed(true);
    database.lookupByRID(aRids.get(3), true);
    database.lookupByRID(aRids.get(4), true);
    assertThat(user.fileRequests.get()).isEqualTo(3);
  }

  /**
   * A grant answered from a state that is replaced later - the "file not yet registered, allowing by default" answer
   * {@code ServerSecurityDatabaseUser} gives before its map covers a new bucket is one - is forgotten with that state.
   */
  @Test
  void aGrantGivenBeforeTheStateWasRefreshedIsForgottenWithIt() {
    final CountingUser user = new CountingUser("early", true);
    bind(user);
    database.lookupByRID(bRids.getFirst(), true);

    user.setReadsAllowed(false);
    assertThatThrownBy(() -> database.lookupByRID(bRids.getFirst(), true)).isInstanceOf(SecurityException.class);
  }

  /** A user that publishes no access version is never cached: every check asks it. */
  @Test
  void aUserWithoutAnAccessVersionIsAskedEveryTime() {
    final CountingUser user = new CountingUser("unversioned", true) {
      @Override
      public Object getAccessVersion() {
        return null;
      }
    };
    bind(user);
    for (int i = 0; i < 10; i++)
      database.lookupByRID(aRids.get(i), true);
    assertThat(user.fileRequests.get()).isEqualTo(10);
  }

  @Test
  void aThreadWithoutAUserIsNotChecked() {
    bind(null);
    for (final RID rid : aRids)
      assertThat(database.lookupByRID(rid, true)).isNotNull();
  }
}
