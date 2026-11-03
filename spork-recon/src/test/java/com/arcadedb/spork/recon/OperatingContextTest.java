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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code X-Spork-Context} header exactly as Recon's gateway sends it ({@code recon-next/src/gateway/spork.ts}):
 * {@code base64url(JSON {caseRef, purpose})}, unsigned, context only and never identity.
 */
class OperatingContextTest {

  static String encode(final JSONObject context) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(context.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parsesTheGatewayShape() {
    final OperatingContext context = OperatingContext.parse(
        encode(new JSONObject().put("caseRef", "CASE-2026-0012").put("purpose", "network_disruption")));

    assertThat(context.caseRef()).isEqualTo("CASE-2026-0012");
    assertThat(context.purpose()).isEqualTo(Purpose.NETWORK_DISRUPTION);
    assertThat(context.analyst()).isNull();
  }

  @Test
  void caseRefIsOptionalAndPaddingIsAccepted() {
    final String padded = Base64.getUrlEncoder()
        .encodeToString(new JSONObject().put("purpose", "training").toString().getBytes(StandardCharsets.UTF_8));
    final OperatingContext context = OperatingContext.parse(padded);

    assertThat(context.caseRef()).isNull();
    assertThat(context.purpose()).isEqualTo(Purpose.TRAINING);
  }

  @Test
  void everyProposedPurposeHasItsWireName() {
    for (final Purpose purpose : Purpose.values())
      assertThat(OperatingContext.parse(encode(new JSONObject().put("purpose", purpose.wireName()))).purpose()).isEqualTo(purpose);
    assertThat(Purpose.fromWire("victim_identification")).isEqualTo(Purpose.VICTIM_IDENTIFICATION);
    assertThat(Purpose.fromWire("VICTIM_IDENTIFICATION")).isNull();
  }

  @Test
  void anAbsentContextIs428ContextRequired() {
    for (final String absent : new String[] { null, "", "   " })
      assertThatThrownBy(() -> OperatingContext.parse(absent)).isInstanceOfSatisfying(SporkRefusal.class, refusal -> {
        assertThat(refusal.status()).isEqualTo(428);
        assertThat(refusal.code()).isEqualTo("contextRequired");
      });
  }

  @Test
  void anUnreadableContextIs428ContextInvalid() {
    final String[] invalid = {
        "not base64 at all!",                                                              // alphabet
        "eyJwdXJw+/9zZSI6",                                                                // standard alphabet, not url
        encode(new JSONObject()),                                                          // no purpose
        encode(new JSONObject().put("purpose", "fishing")),                                // unknown purpose
        encode(new JSONObject().put("purpose", 3)),                                        // wrong type
        encode(new JSONObject().put("purpose", "training").put("caseRef", 12)),            // wrong type
        encode(new JSONObject().put("purpose", "training").put("caseRef", "")),            // empty case
        encode(new JSONObject().put("purpose", "training").put("caseRef", "a\nb")),        // control character
        encode(new JSONObject().put("purpose", "training").put("caseRef", "x".repeat(201))), // too long
        // Identity and markings are never a request field (07-entry-identity-and-audit.md 2.5): refused, not ignored
        encode(new JSONObject().put("purpose", "training").put("role", "console")),
        encode(new JSONObject().put("purpose", "training").put("markings", new JSONArray().put("minor"))),
        Base64.getUrlEncoder().withoutPadding().encodeToString("[\"training\"]".getBytes(StandardCharsets.UTF_8)),
        "A".repeat(OperatingContext.MAX_HEADER_LENGTH + 1) };
    for (final String header : invalid)
      assertThatThrownBy(() -> OperatingContext.parse(header)).as(header).isInstanceOfSatisfying(SporkRefusal.class, refusal -> {
        assertThat(refusal.status()).isEqualTo(428);
        assertThat(refusal.code()).isEqualTo("contextInvalid");
      });
  }

  @Test
  void aDeclaredAnalystIsKeptForTheIdentityCheck() {
    final OperatingContext context = OperatingContext.parse(
        encode(new JSONObject().put("purpose", "investigative_lead").put("analyst", "0b7c2f1e")));

    assertThat(context.analyst()).isEqualTo("0b7c2f1e");
  }
}
