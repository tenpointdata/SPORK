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

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The declarative route table: paths, purpose gates by data class, and the path grammar the gateway forwards. */
class SporkRoutesTest {

  @Test
  void hydrateCarriesRecordProjectionsAndIsGatedLikeIntake() {
    final SporkRoute hydrate = SporkRoutes.find("POST", "records:hydrate");

    assertThat(hydrate).isNotNull();
    assertThat(hydrate.action()).isEqualTo("records.hydrate");
    assertThat(hydrate.dataClass()).isEqualTo(DataClass.RECORD_PROJECTION);
    assertThat(hydrate.allows(Purpose.NETWORK_DISRUPTION)).isTrue();
    assertThat(hydrate.allows(Purpose.INVESTIGATIVE_LEAD)).isTrue();
    assertThat(hydrate.allows(Purpose.VICTIM_IDENTIFICATION)).isTrue();
    // 01-architecture 5.1: standing_monitoring is watches and detectors only, training is synthetic or minimized views only
    assertThat(hydrate.allows(Purpose.STANDING_MONITORING)).isFalse();
    assertThat(hydrate.allows(Purpose.TRAINING)).isFalse();
  }

  @Test
  void declaringAContextIsOpenToEveryPurpose() {
    final SporkRoute context = SporkRoutes.find("POST", "context");

    assertThat(context.action()).isEqualTo("context.declare");
    assertThat(context.purposes()).isEqualTo(EnumSet.allOf(Purpose.class));
  }

  @Test
  void everyRouteIsDeclaredOnceWithAnAuditActionAndAtLeastOnePurpose() {
    final Set<String> seen = new HashSet<>();
    for (final SporkRoute route : SporkRoutes.all()) {
      assertThat(seen.add(route.method() + " " + route.path())).as(route.path()).isTrue();
      assertThat(route.purposes()).as(route.path()).isNotEmpty();
      assertThat(route.action()).as(route.path()).matches("[a-z][a-z0-9_.-]{0,63}");
      assertThat(route.operationId()).as(route.path()).isNotBlank();
      // a route gate never allows a purpose its data class refuses
      assertThat(route.dataClass().purposes()).as(route.path()).containsAll(route.purposes());
    }
  }

  @Test
  void aKnownPathWithAnotherMethodListsTheAllowedOnes() {
    assertThat(SporkRoutes.find("GET", "records:hydrate")).isNull();
    assertThat(SporkRoutes.allowedMethods("records:hydrate")).containsExactly("POST");
    assertThat(SporkRoutes.allowedMethods("records:delete")).isEmpty();
  }

  @Test
  void parsesTheNamespaceThatTheGatewayForwards() {
    final SporkPath path = SporkPath.parse("/api/spork/v1/deliverfund/records:hydrate");

    assertThat(path.tenant()).isEqualTo("deliverfund");
    assertThat(path.route()).isEqualTo("records:hydrate");
    assertThat(SporkPath.parse("/api/spork/v1/acme-2/context").tenant()).isEqualTo("acme-2");
  }

  @Test
  void refusesPathsOutsideTheGrammar() {
    for (final String path : new String[] { "/api/spork/v1", "/api/spork/v1/", "/api/spork/v1/deliverfund", "/api/spork/v1/deliverfund/",
        "/api/spork/v1//context", "/api/spork/v1/Deliverfund/context", "/api/spork/v1/../context", "/api/spork/v1/a.b/context",
        "/api/spork/v1/deliverfund/%2e%2e", "/api/spork/v1/deliverfund/records:hydrate/", "/api/spork/v1/" + "t".repeat(65) + "/context",
        "/api/v1/query/x" })
      assertThatThrownBy(() -> SporkPath.parse(path)).as(path).isInstanceOfSatisfying(SporkRefusal.class,
          refusal -> assertThat(refusal.status()).isEqualTo(404));
  }
}
