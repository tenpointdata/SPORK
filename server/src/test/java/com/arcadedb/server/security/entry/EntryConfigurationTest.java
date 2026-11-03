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
package com.arcadedb.server.security.entry;

import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryConfigurationTest {

  @Test
  void absentFileMeansEntryIsOff(@TempDir final File dir) {
    assertThat(EntryConfiguration.load(dir.getAbsolutePath())).isNull();
  }

  @Test
  void loadsTheFileAndAppliesDefaults(@TempDir final File dir) throws Exception {
    final JSONObject json = EntryFixtures.configJson();
    json.remove("serviceId");
    json.remove("database");
    Files.writeString(new File(dir, EntryConfiguration.FILE_NAME).toPath(), json.toString(), StandardCharsets.UTF_8);

    final EntryConfiguration config = EntryConfiguration.load(dir.getAbsolutePath());
    assertThat(config).isNotNull();
    assertThat(config.getOrigin()).isEqualTo("http://localhost:3002");
    assertThat(config.getServiceId()).isEqualTo("spork");
    assertThat(config.getDatabase()).isEqualTo("identity");
    assertThat(config.getVerdictCacheMillis()).isEqualTo(30_000L);
    assertThat(config.getMaxConcurrentLookups()).isEqualTo(32);
  }

  @Test
  void refusesCleartextOutsideLocalhostAndAnOriginWithAPath() {
    assertThatThrownBy(() -> EntryConfiguration.parse(EntryFixtures.configJson().put("origin", "http://entry.example.org")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EntryConfiguration.parse(EntryFixtures.configJson().put("origin", "https://entry.example.org/api")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(EntryConfiguration.parse(EntryFixtures.configJson().put("origin", "https://entry.example.org")).getOrigin())
        .isEqualTo("https://entry.example.org");
  }

  @Test
  void refusesAConfigurationThatGrantsNothing() {
    assertThatThrownBy(() -> EntryConfiguration.parse(EntryFixtures.configJson().put("grants", new JSONObject())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mapsHeldPermissionsToTheUnionOfTheirGroups() {
    final EntryConfiguration config = EntryFixtures.config();

    final JSONObject both = config.databasesFor(new String[] { "spork.console", "spork.ops" });
    assertThat(both.getJSONArray("*").toList()).containsExactly("console");
    assertThat(both.getJSONArray("identity").toList()).containsExactly("ops");
    assertThat(both.getJSONArray("recdir").toList()).containsExactly("ops");

    // Holding the parent permission covers every configured child, as Recon's gateway reads Entry permissions.
    assertThat(config.databasesFor(new String[] { "spork" }).keySet()).containsExactlyInAnyOrder("*", "identity", "recdir");
    assertThat(config.databasesFor(new String[] { "spork.console.extra", "sporkx", "" }).length()).isZero();
  }

  @Test
  void lifecycleCredentialIsComparedByDigestOnly() {
    final EntryConfiguration config = EntryFixtures.config();
    assertThat(config.acceptsLifecycleCredential(EntryFixtures.LIFECYCLE_SECRET)).isTrue();
    assertThat(config.acceptsLifecycleCredential(EntryFixtures.LIFECYCLE_SECRET + "x")).isFalse();
    assertThat(config.acceptsLifecycleCredential(null)).isFalse();

    final JSONObject json = EntryFixtures.configJson();
    json.remove("lifecycleCredentialSha256");
    assertThat(EntryConfiguration.parse(json).acceptsLifecycleCredential(EntryFixtures.LIFECYCLE_SECRET)).isFalse();
  }

  @Test
  void permissionHierarchyMatchesRecon() {
    assertThat(EntryPermissions.holds(new String[] { "spork.console" }, "spork.console")).isTrue();
    assertThat(EntryPermissions.holds(new String[] { "spork" }, "spork.console")).isTrue();
    assertThat(EntryPermissions.holds(new String[] { "spork.console" }, "spork")).isFalse();
    assertThat(EntryPermissions.holds(new String[] { "spor" }, "spork.console")).isFalse();
    assertThat(EntryPermissions.holds(new String[] { "", null }, "spork.console")).isFalse();
  }
}
