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
package com.arcadedb.server.audit;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chain is only verifiable by Recon if Java writes the exact bytes Recon's {@code canonicalJSON} writes. The golden
 * values below were produced by running Recon's {@code canonicalJSON} and {@code entryHash} (recon-next
 * {@code src/ops/audit.ts}) under Node on the same body.
 */
class CanonicalJsonTest {

  static JSONObject reconGoldenBody() {
    return new JSONObject()//
        .put("seq", 1)//
        .put("at", "2026-11-03T12:00:00.000Z")//
        .put("actor", "0b7c2f1e-5a4d-4c3b-9e8f-112233445566")//
        .put("action", "intake.run")//
        .put("caseRef", "CASE-7")//
        .put("purpose", "investigative_lead")//
        .put("subject", "tab\tquote\" back\\ é")//
        .put("detail", new JSONObject().put("records", 12).put("ok", true).put("ref", "r/1"))//
        .put("entry", new JSONObject().put("env", "prod").put("identity", "idn-1").put("credential", "a".repeat(64)).put("account", 3))//
        .put("prevHash", AuditChain.GENESIS_HASH);
  }

  @Test
  void matchesReconCanonicalJsonByteForByte() {
    assertThat(CanonicalJson.write(reconGoldenBody())).isEqualTo(
        "{\"action\":\"intake.run\",\"actor\":\"0b7c2f1e-5a4d-4c3b-9e8f-112233445566\",\"at\":\"2026-11-03T12:00:00.000Z\","
            + "\"caseRef\":\"CASE-7\",\"detail\":{\"ok\":true,\"records\":12,\"ref\":\"r/1\"},"
            + "\"entry\":{\"account\":3,\"credential\":\"" + "a".repeat(64) + "\",\"env\":\"prod\",\"identity\":\"idn-1\"},"
            + "\"prevHash\":\"" + AuditChain.GENESIS_HASH + "\",\"purpose\":\"investigative_lead\",\"seq\":1,"
            + "\"subject\":\"tab\\tquote\\\" back\\\\ é\"}");
  }

  @Test
  void hashMatchesReconEntryHash() {
    assertThat(AuditChain.hash(AuditChain.GENESIS_HASH, CanonicalJson.write(reconGoldenBody())))
        .isEqualTo("109354eff1171335cbace38cad2411cb3231438c563c9e222d434e36023949c1");
  }

  @Test
  void escapesControlCharactersAsJavaScriptDoes() {
    assertThat(CanonicalJson.write("a\u0001b\bc\fd\ne\rf/g ")).isEqualTo("\"a\\u0001b\\bc\\fd\\ne\\rf/g \"");
  }

  @Test
  void omitsNullMembersAndKeepsNullArrayItems() {
    final JSONObject object = new JSONObject().put("b", 2).put("a", JSONObject.NULL);
    assertThat(CanonicalJson.write(object)).isEqualTo("{\"b\":2}");
    assertThat(CanonicalJson.write(new JSONArray().put("x").put(JSONObject.NULL).put(3))).isEqualTo("[\"x\",null,3]");
  }

  @Test
  void writesIntegralNumbersWithoutFractionAndRefusesOthers() {
    assertThat(CanonicalJson.write(3.0d)).isEqualTo("3");
    assertThat(CanonicalJson.write(-7L)).isEqualTo("-7");
    assertThatThrownBy(() -> CanonicalJson.write(1.5d)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
  }
}
