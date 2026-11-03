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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code <server>/config/server-spork-recon.json}. */
class SporkReconConfigurationTest {

  static JSONObject minimal() {
    return new JSONObject().put("tenants", new JSONArray().put("deliverfund"));
  }

  @Test
  void defaultsFollowTheArchitecture() {
    final SporkReconConfiguration config = SporkReconConfiguration.parse(minimal());

    assertThat(config.isTenant("deliverfund")).isTrue();
    assertThat(config.isTenant("acme")).isFalse();
    assertThat(config.auditDatabase()).isEqualTo("identity");
    assertThat(config.directoryDatabase()).isEqualTo("recdir");
    assertThat(config.partitionPrefix()).isEqualTo("rec_");
    assertThat(config.hydrateThreads()).isEqualTo(32);
    assertThat(config.searchEstimateBudget()).as("sized for P4's 5 ms p50 with the default estimated total").isEqualTo(2_000);
    assertThat(config.textPrefix()).isEqualTo("txt_");
    assertThat(config.heldMarkings(new String[] { "spork.console" })).isZero();
    assertThat(config.localMarkings()).isZero();
  }

  @Test
  void markingsComeFromEntryPermissionsNeverFromTheRequest() {
    final SporkReconConfiguration config = SporkReconConfiguration.parse(minimal()//
        .put("markings", new JSONObject().put("spork.marking.minor", 0).put("spork.marking.le", 3)));

    assertThat(config.heldMarkings(new String[] { "spork.marking.minor" })).isEqualTo(1L);
    assertThat(config.heldMarkings(new String[] { "spork.marking" })).as("a parent permission covers its children")
        .isEqualTo(1L | (1L << 3));
    assertThat(config.heldMarkings(new String[] { "other" })).isZero();
    assertThat(config.heldMarkings(null)).isZero();
  }

  @Test
  void refusesAConfigurationThatCannotBeEnforced() {
    final JSONObject[] invalid = {
        new JSONObject(),                                                                    // no tenants
        new JSONObject().put("tenants", new JSONArray()),                                    // empty
        new JSONObject().put("tenants", new JSONArray().put("Bad Tenant")),                  // grammar
        minimal().put("markings", new JSONObject().put("spork.marking.x", 64)),             // bit out of range
        minimal().put("hydrateThreads", 0),
        minimal().put("searchEstimateBudget", 0),
        minimal().put("auditDatabase", "") };
    for (final JSONObject config : invalid)
      assertThatThrownBy(() -> SporkReconConfiguration.parse(config)).as(config.toString())
          .isInstanceOf(IllegalArgumentException.class);
  }
}
