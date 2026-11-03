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

/** No chain to write to means nothing is released (01-architecture 5.2: "same transaction, fails closed"). */
class SporkReconAuditFailsClosedIT extends SporkReconServerTestBase {

  @Override
  protected JSONObject pluginConfiguration() {
    return new JSONObject().put("tenants", new JSONArray().put(TENANT)).put("auditDatabase", "no_such_audit_database");
  }

  @Test
  void aReleaseThatCannotBeAuditedIs503() throws Exception {
    final Answer hydrate = post("records:hydrate", basicRoot(), context(null, "network_disruption"),
        new JSONObject().put("ids", new JSONArray().put("168:1")));
    assertThat(hydrate.status()).isEqualTo(503);
    assertThat(hydrate.json().getString("code")).isEqualTo("auditUnavailable");
    assertThat(hydrate.json().has("records")).isFalse();

    final Answer declare = post("context", basicRoot(), context(null, "training"), null);
    assertThat(declare.status()).isEqualTo(503);
  }
}
