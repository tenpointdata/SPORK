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

import com.arcadedb.TestHelper;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.security.entry.EntryPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditChainTest extends TestHelper {
  private static final long         AT    = 1_793_707_200_000L; // 2026-11-03T12:00:00.000Z
  private final        AuditChain   chain = new AuditChain(() -> AT);
  private final        EntryPrincipal analyst = new EntryPrincipal("prod", "0b7c2f1e-5a4d-4c3b-9e8f-112233445566", "idn-1",
      "a".repeat(64), 3, "analyst@example.org", new String[] { "spork.console" }, AT + 60_000);

  @BeforeEach
  void schema() {
    AuditChain.ensureSchema(database);
  }

  @Test
  void entriesLinkFromGenesisAndHashTheStoredBody() {
    final JSONObject[] entries = new JSONObject[2];
    database.transaction(() -> {
      entries[0] = chain.append(database, new AuditEvent(AuditActor.entry(analyst), "intake.run").caseRef("CASE-7")
          .purpose("investigative_lead").subject("555 0100").detail(new JSONObject().put("records", 12)));
      entries[1] = chain.append(database, new AuditEvent(AuditActor.system("system:watch-scan", analyst.sourceUserId()), "watch.scan"));
    });

    assertThat(entries[0].getLong("seq")).isEqualTo(1L);
    assertThat(entries[0].getString("prevHash")).isEqualTo(AuditChain.GENESIS_HASH);
    assertThat(entries[0].getString("at")).isEqualTo("2026-11-03T12:00:00.000Z");
    assertThat(entries[1].getLong("seq")).isEqualTo(2L);
    assertThat(entries[1].getString("prevHash")).isEqualTo(entries[0].getString("hash"));
    assertThat(entries[1].getString("onBehalfOf")).isEqualTo(analyst.sourceUserId());

    // The returned entry is Recon's AuditEntry: its hash is entryHash() over every other field.
    final JSONObject body = new JSONObject(entries[0].toString());
    final String hash = (String) body.remove("hash");
    assertThat(AuditChain.hash(body.getString("prevHash"), CanonicalJson.write(body))).isEqualTo(hash);

    final AuditVerification verification = AuditChain.verify(database);
    assertThat(verification.ok()).isTrue();
    assertThat(verification.entries()).isEqualTo(2L);
    assertThat(verification.headHash()).isEqualTo(entries[1].getString("hash"));
  }

  @Test
  void entryActorIsTheEntryAccountWithItsCredentialCorrelationInsideTheHash() {
    final JSONObject[] entry = new JSONObject[1];
    database.transaction(() -> entry[0] = chain.append(database, new AuditEvent(AuditActor.entry(analyst), "dossier.view")));

    assertThat(entry[0].getString("actor")).isEqualTo(analyst.sourceUserId());
    final JSONObject correlation = entry[0].getJSONObject("entry");
    assertThat(correlation.getString("env")).isEqualTo("prod");
    assertThat(correlation.getString("identity")).isEqualTo("idn-1");
    assertThat(correlation.getString("credential")).isEqualTo("a".repeat(64));
    assertThat(correlation.getLong("account")).isEqualTo(3L);
    assertThat(AuditActor.local("root").getActor()).isEqualTo("local:root");
  }

  @Test
  void appendOutsideATransactionFailsClosed() {
    assertThatThrownBy(() -> chain.append(database, new AuditEvent(AuditActor.local("root"), "context.declare")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuditChain.verify(database).entries()).isZero();
  }

  @Test
  void aRolledBackBusinessWriteTakesItsEntryAndTheHeadWithIt() {
    database.getSchema().createDocumentType("Note");
    database.begin();
    database.newDocument("Note").set("text", "draft").save();
    chain.append(database, new AuditEvent(AuditActor.local("root"), "board.note"));
    database.rollback();

    assertThat(AuditChain.verify(database).entries()).isZero();
    final JSONObject[] entry = new JSONObject[1];
    database.transaction(() -> entry[0] = chain.append(database, new AuditEvent(AuditActor.local("root"), "board.note")));
    assertThat(entry[0].getLong("seq")).isEqualTo(1L);
    assertThat(entry[0].getString("prevHash")).isEqualTo(AuditChain.GENESIS_HASH);
  }

  @Test
  void anEditedBodyBreaksTheChainAtThatEntry() {
    database.transaction(() -> {
      chain.append(database, new AuditEvent(AuditActor.local("root"), "board.add").subject("target-1"));
      chain.append(database, new AuditEvent(AuditActor.local("root"), "board.update").subject("target-1"));
    });
    database.transaction(() -> database.command("sql",
        "UPDATE AuditEntry SET body = body.replace('target-1', 'target-2') WHERE seq = 1"));

    final AuditVerification verification = AuditChain.verify(database);
    assertThat(verification.ok()).isFalse();
    assertThat(verification.brokenAt()).isEqualTo(1L);
  }

  @Test
  void aDeletedNewestEntryIsDetectedAgainstTheHead() {
    database.transaction(() -> {
      chain.append(database, new AuditEvent(AuditActor.local("root"), "board.add"));
      chain.append(database, new AuditEvent(AuditActor.local("root"), "board.update"));
    });
    database.transaction(() -> database.command("sql", "DELETE FROM AuditEntry WHERE seq = 2"));

    final AuditVerification verification = AuditChain.verify(database);
    assertThat(verification.ok()).isFalse();
    assertThat(verification.brokenAt()).isEqualTo(2L);
  }

  @Test
  void detailKeepsOnlyBoundedPrimitivesAndTheSubjectLosesControlCharacters() {
    final JSONObject detail = new JSONObject().put("nested", new JSONObject().put("x", 1)).put("long", "y".repeat(500));
    for (int i = 0; i < 30; i++)
      detail.put("k" + (char) ('a' + i % 26) + i, i);
    final JSONObject[] entry = new JSONObject[1];
    database.transaction(() -> entry[0] = chain.append(database,
        new AuditEvent(AuditActor.local("root"), "package.export").subject(" tab\there\n").detail(detail)));

    final JSONObject stored = entry[0].getJSONObject("detail");
    assertThat(stored.has("nested")).isFalse();
    assertThat(stored.length()).isEqualTo(20);
    assertThat(stored.getString("long")).hasSize(200);
    assertThat(entry[0].getString("subject")).isEqualTo("tab here");
  }
}
