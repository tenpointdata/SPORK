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

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.audit.AuditChain;
import com.arcadedb.server.security.entry.EntryConfiguration;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plugin end to end over HTTP: the person is the Entry credential, the context is the unsigned header, the purpose gate
 * is the route table, hydrate resolves all three id forms across {@code recdir} and a {@code rec_<p>} partition, and every
 * release appends one entry to the chain before the response leaves.
 */
class SporkReconPluginIT extends SporkReconServerTestBase {
  // Entry account ids are UUIDs
  private static final String CONSOLE_USER   = "0b7c2f1e-5a4d-4c3b-9e8f-112233445566";
  private static final String UNMARKED_USER  = "0b7c2f1e-5a4d-4c3b-9e8f-112233445577";
  private static final String OPS_USER       = "0b7c2f1e-5a4d-4c3b-9e8f-112233445588";
  private static final String CONSOLE        = "Bearer entry-console-session.0123456789";
  private static final String UNMARKED       = "Bearer entry-unmarked-session.0123456789";
  private static final String OPS            = "Bearer entry-ops-session.0123456789";
  private static final String PARTITION      = "rec_1";
  private static final String DIRECTORY      = "recdir";
  private static final int    RECORDS        = 2_000;
  private static final int    MARKED         = RECORDS + 1;
  private static final int    IMPORTED       = RECORDS + 2;
  private static final int    OTHER_TENANT   = RECORDS + 3;
  private static final long   POSTED         = 1_793_707_200_000L; // 2026-11-03T12:00:00.000Z
  private static final String NETWORK        = "network_disruption";

  private StubEntry entry;

  @Override
  protected JSONObject pluginConfiguration() {
    return new JSONObject()//
        .put("tenants", new JSONArray().put(TENANT))//
        .put("auditDatabase", getDatabaseName())//
        .put("directoryDatabase", DIRECTORY)//
        .put("partitionPrefix", "rec_")//
        .put("markings", new JSONObject().put("spork.marking.minor", 0));
  }

  @Override
  protected void onBeforeStarting(final ArcadeDBServer server) {
    super.onBeforeStarting(server);
    try {
      entry = new StubEntry()//
          .person(CONSOLE.substring(7), CONSOLE_USER, "spork.console", "spork.marking.minor")//
          .person(UNMARKED.substring(7), UNMARKED_USER, "spork.console")//
          .person(OPS.substring(7), OPS_USER, "spork.ops");
      // spork.ops reaches the audit database and the directory, never the partition: the engine user binding refuses it
      writeConfig(server, EntryConfiguration.FILE_NAME, entry.configuration(getDatabaseName(), new JSONObject()//
          .put("spork.console", new JSONObject().put("*", new JSONArray().put("admin")))//
          .put("spork.ops", new JSONObject().put(getDatabaseName(), new JSONArray().put("admin"))
              .put(DIRECTORY, new JSONArray().put("admin")))));
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
    createCorpus(server.getConfiguration().getValueAsString(GlobalConfiguration.SERVER_DATABASE_DIRECTORY));
  }

  @AfterEach
  void stopEntry() {
    if (entry != null)
      entry.close();
    for (int i = 0; i < getServerCount(); i++)
      if (getServer(i) != null)
        new File(getServer(i).getConfigPath(), EntryConfiguration.FILE_NAME).delete();
  }

  static long recordId(final int n) {
    return (1L << 48) | n;
  }

  static String rid(final int n) {
    return "168:" + n;
  }

  /** {@code recdir} and one partition, with the types and indexes of schema v1 ({@code spork-keys} rec.sql and recdir.sql). */
  private static void createCorpus(final String databaseDirectory) {
    for (final String name : new String[] { DIRECTORY, PARTITION })
      FileUtils.deleteRecursively(new File(databaseDirectory, name));

    try (final Database recdir = new DatabaseFactory(databaseDirectory + File.separator + DIRECTORY).create()) {
      recdir.command("sql", "CREATE DOCUMENT TYPE RecordDir IF NOT EXISTS BUCKETS 1");
      recdir.command("sql", "CREATE PROPERTY RecordDir.upstreamRid IF NOT EXISTS LONG (mandatory true, notnull true)");
      recdir.command("sql", "CREATE PROPERTY RecordDir.period IF NOT EXISTS SHORT (mandatory true, notnull true)");
      recdir.command("sql", "CREATE INDEX IF NOT EXISTS ON RecordDir (upstreamRid) UNIQUE_HASH");
      recdir.transaction(() -> {
        for (int n = 1; n <= MARKED; n++)
          recdir.newDocument("RecordDir").set("upstreamRid", (168L << 40) | n).set("period", (short) 1).save();
      });
    }

    try (final Database partition = new DatabaseFactory(databaseDirectory + File.separator + PARTITION).create()) {
      for (final String ddl : new String[] { "CREATE VERTEX TYPE Record IF NOT EXISTS BUCKETS 1",
          "CREATE PROPERTY Record.record_id IF NOT EXISTS LONG (mandatory true, notnull true)",
          "CREATE PROPERTY Record.upstreamRid IF NOT EXISTS LONG",
          "CREATE PROPERTY Record.textHash IF NOT EXISTS BINARY",
          "CREATE PROPERTY Record.imageHashes IF NOT EXISTS LIST OF STRING",
          "CREATE PROPERTY Record.tsPosted IF NOT EXISTS LONG",
          "CREATE PROPERTY Record.views IF NOT EXISTS INTEGER",
          "CREATE PROPERTY Record.stateCode IF NOT EXISTS STRING",
          "CREATE PROPERTY Record.phones IF NOT EXISTS LIST OF STRING",
          "CREATE PROPERTY Record.tenantId IF NOT EXISTS STRING",
          "CREATE PROPERTY Record.importedId IF NOT EXISTS STRING",
          "CREATE PROPERTY Record.markings IF NOT EXISTS LONG",
          "CREATE INDEX IF NOT EXISTS ON Record (record_id) UNIQUE_HASH",
          "CREATE INDEX IF NOT EXISTS ON Record (upstreamRid) UNIQUE_HASH NULL_STRATEGY SKIP",
          "CREATE INDEX IF NOT EXISTS ON Record (tenantId, importedId) UNIQUE_HASH NULL_STRATEGY SKIP" })
        partition.command("sql", ddl);
      partition.transaction(() -> {
        for (int n = 1; n <= OTHER_TENANT; n++) {
          final MutableDocument record = partition.newVertex("Record")//
              .set("record_id", recordId(n))//
              .set("tsPosted", POSTED + n)//
              .set("views", n)//
              .set("stateCode", "TX")//
              .set("phones", List.of("555" + String.format("%07d", n)))//
              .set("textHash", new byte[] { 0x0a, (byte) 0xff, 0, (byte) n })//
              .set("markings", n == MARKED ? 1L : 0L);
          if (n <= MARKED)
            record.set("upstreamRid", (168L << 40) | n);
          else
            record.set("tenantId", n == IMPORTED ? TENANT : "acme").set("importedId", n == IMPORTED ? "my-own:42" : "theirs");
          record.save();
        }
      });
    }
  }

  private Database audit() {
    return getServerDatabase(0, getDatabaseName());
  }

  private long auditEntries() {
    return audit().getSchema().existsType(AuditChain.ENTRY_TYPE) ? audit().countType(AuditChain.ENTRY_TYPE, false) : 0L;
  }

  private JSONObject lastAuditBody() {
    final long seq = auditEntries();
    final IndexCursor cursor = audit().lookupByKey(AuditChain.ENTRY_TYPE, "seq", seq);
    final Document document = cursor.next().asDocument();
    return new JSONObject(document.getString("body"));
  }

  private static JSONObject hydrate(final Object... ids) {
    final JSONArray array = new JSONArray();
    for (final Object id : ids)
      array.put(id.toString());
    return new JSONObject().put("ids", array);
  }

  @Test
  void aSensitiveRouteWithoutAValidContextIs428AndReleasesNothing() throws Exception {
    final Answer absent = post("records:hydrate", CONSOLE, null, hydrate(rid(1)));
    assertThat(absent.status()).isEqualTo(428);
    assertThat(absent.json().getString("code")).isEqualTo("contextRequired");
    assertThat(absent.body()).doesNotContain("records\"");

    final Answer invalid = post("records:hydrate", CONSOLE, "%%%", hydrate(rid(1)));
    assertThat(invalid.status()).isEqualTo(428);
    assertThat(invalid.json().getString("code")).isEqualTo("contextInvalid");

    assertThat(auditEntries()).isZero();
  }

  @Test
  void thePurposeGateRefusesWithPurposeDenied() throws Exception {
    for (final String purpose : new String[] { "standing_monitoring", "training" }) {
      final Answer denied = post("records:hydrate", CONSOLE, context("CASE-1", purpose), hydrate(rid(1)));
      assertThat(denied.status()).as(purpose).isEqualTo(403);
      assertThat(denied.json().getString("code")).isEqualTo("purposeDenied");
    }
    assertThat(auditEntries()).isZero();
  }

  @Test
  void identityComesFromTheCredentialNeverFromTheHeader() throws Exception {
    final String claimsSomeoneElse = Base64.getUrlEncoder().withoutPadding().encodeToString(
        new JSONObject().put("purpose", NETWORK).put("analyst", OPS_USER).toString().getBytes());
    final Answer mismatch = post("records:hydrate", CONSOLE, claimsSomeoneElse, hydrate(rid(1)));
    assertThat(mismatch.status()).isEqualTo(403);
    assertThat(mismatch.json().getString("code")).isEqualTo("analystMismatch");

    assertThat(post("records:hydrate", null, context(null, NETWORK), hydrate(rid(1))).status()).isEqualTo(401);
    assertThat(post("records:hydrate", "Bearer entry-unknown-session.0123456789", context(null, NETWORK), hydrate(rid(1))).status())
        .isEqualTo(401);
    assertThat(auditEntries()).isZero();
  }

  @Test
  void onlyTheNamespaceAndItsDeclaredMethodsAnswer() throws Exception {
    final Answer unknownTenant = call("POST", SporkPath.PREFIX + "/acme/records:hydrate", CONSOLE, context(null, NETWORK), "{}");
    assertThat(unknownTenant.status()).isEqualTo(404);
    assertThat(unknownTenant.json().getString("code")).isEqualTo("unknownTenant");

    assertThat(post("records:delete", CONSOLE, context(null, NETWORK), null).status()).isEqualTo(404);

    final Answer wrongMethod = call("GET", SporkPath.PREFIX + "/" + TENANT + "/records:hydrate", CONSOLE, context(null, NETWORK), null);
    assertThat(wrongMethod.status()).isEqualTo(405);
    assertThat(wrongMethod.header("Allow")).isEqualTo("POST");
    assertThat(auditEntries()).isZero();
  }

  @Test
  void hydrateResolvesEveryIdFormAndAppendsOneEntryBeforeRelease() throws Exception {
    final Answer answer = post("records:hydrate", CONSOLE, context("CASE-2026-0012", NETWORK), hydrate(//
        rid(1), recordId(2), "imp:my-own:42", rid(MARKED), rid(999_999), "not-an-id", "imp:theirs", rid(1), recordId(3) | (7L << 48),
        // another tenant's import reached by its record_id rather than through the tenant-keyed import index
        recordId(OTHER_TENANT)));

    assertThat(answer.status()).as(answer.body()).isEqualTo(200);
    final JSONObject body = answer.json();
    final JSONArray records = body.getJSONArray("records");
    assertThat(records.length()).isEqualTo(4);

    final JSONObject first = records.getJSONObject(0);
    assertThat(first.getString("id")).isEqualTo(rid(1));
    assertThat(first.getString("recordId")).isEqualTo(Long.toString(recordId(1)));
    assertThat(first.getString("upstreamRid")).isEqualTo(rid(1));
    assertThat(first.getJSONArray("phones").getString(0)).isEqualTo("5550000001");
    assertThat(first.getString("postedAt")).isEqualTo("2026-11-03T12:00:00.001Z");
    assertThat(first.getString("state")).isEqualTo("TX");
    assertThat(first.getInt("viewCount")).isEqualTo(1);
    assertThat(first.getString("textHash")).isEqualTo("0aff0001");
    assertThat(first.has("styleVec")).as("not in the default projection").isFalse();

    assertThat(records.getJSONObject(1).getString("id")).isEqualTo(Long.toString(recordId(2)));
    assertThat(records.getJSONObject(1).getString("upstreamRid")).isEqualTo(rid(2));
    assertThat(records.getJSONObject(2).getString("id")).isEqualTo("imp:my-own:42");
    assertThat(records.getJSONObject(2).has("upstreamRid")).isFalse();
    assertThat(records.getJSONObject(3).getString("id")).as("the person holds the minor marking").isEqualTo(rid(MARKED));

    assertThat(body.getJSONArray("missing").toList()).containsExactly(rid(999_999), "imp:theirs",
        Long.toString(recordId(3) | (7L << 48)), Long.toString(recordId(OTHER_TENANT)));
    assertThat(body.getJSONArray("invalid").toList()).containsExactly("not-an-id");
    assertThat(body.getBoolean("partial")).as("period 7 is not on this node").isTrue();
    assertThat(body.getString("servedBy")).isEqualTo(getServer(0).getServerName());
    assertThat(body.has("consistencyBookmark")).isTrue();
    final String requestId = body.getString("requestId");
    assertThat(requestId).isNotBlank();

    // one entry, the person's own Entry account, the declared context, a commitment to exactly the released keys
    assertThat(auditEntries()).isEqualTo(1L);
    final JSONObject entryBody = lastAuditBody();
    assertThat(entryBody.getString("actor")).isEqualTo(CONSOLE_USER);
    assertThat(entryBody.getString("action")).isEqualTo("records.hydrate");
    assertThat(entryBody.getString("caseRef")).isEqualTo("CASE-2026-0012");
    assertThat(entryBody.getString("purpose")).isEqualTo(NETWORK);
    final JSONObject detail = entryBody.getJSONObject("detail");
    assertThat(detail.getString("tenant")).isEqualTo(TENANT);
    assertThat(detail.getString("requestId")).isEqualTo(requestId);
    assertThat(detail.getLong("records")).isEqualTo(4L);
    assertThat(detail.getLong("requested")).isEqualTo(9L);
    assertThat(detail.getString("keysRoot")).isEqualTo(MerkleRoot.of(List.of(Long.toString(recordId(1)), Long.toString(recordId(2)),
        Long.toString(recordId(IMPORTED)), Long.toString(recordId(MARKED)))));
    assertThat(body.getLong("auditSeq")).isEqualTo(entryBody.getLong("seq"));
    assertThat(AuditChain.verify(audit()).ok()).isTrue();
  }

  @Test
  void unheldMarkingsAreAbsentNotRedacted() throws Exception {
    final Answer answer = post("records:hydrate", UNMARKED, context(null, NETWORK), hydrate(rid(1), rid(MARKED)));

    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.json().getJSONArray("records").length()).isEqualTo(1);
    assertThat(answer.json().getJSONArray("missing").toList()).containsExactly(rid(MARKED));
    assertThat(lastAuditBody().getString("actor")).isEqualTo(UNMARKED_USER);
  }

  @Test
  void theEngineUserIsThePersonSoAPartitionTheyHoldNoGrantOnIsRefused() throws Exception {
    final Answer refused = post("records:hydrate", OPS, context(null, NETWORK), hydrate(rid(1)));

    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.body()).doesNotContain("5550000001");
    assertThat(auditEntries()).isZero();
  }

  @Test
  void theProjectionIsAnAllowlist() throws Exception {
    final JSONObject request = hydrate(rid(1)).put("projection", new JSONArray().put("phones").put("postedAt"));
    final JSONObject only = post("records:hydrate", CONSOLE, context(null, NETWORK), request).json().getJSONArray("records")
        .getJSONObject(0);
    assertThat(only.keySet()).containsExactlyInAnyOrder("id", "recordId", "upstreamRid", "phones", "postedAt");

    // ad title and text live in txt_<p> and are not served by this slice
    final Answer title = post("records:hydrate", CONSOLE, context(null, NETWORK),
        hydrate(rid(1)).put("projection", new JSONArray().put("title")));
    assertThat(title.status()).isEqualTo(400);
    assertThat(title.json().getString("code")).isEqualTo("projectionInvalid");
    assertThat(auditEntries()).isEqualTo(1L);
  }

  @Test
  void hydrateTakesAtMost5000Ids() throws Exception {
    final List<Object> ids = new ArrayList<>();
    for (int n = 0; n <= HydrateService.MAX_IDS; n++)
      ids.add(recordId(n + 1));
    final Answer refused = post("records:hydrate", CONSOLE, context(null, NETWORK), hydrate(ids.toArray()));

    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.json().getString("code")).isEqualTo("tooManyIds");
    assertThat(auditEntries()).isZero();

    final Answer notAList = post("records:hydrate", CONSOLE, context(null, NETWORK), new JSONObject().put("ids", "168:1"));
    assertThat(notAList.status()).isEqualTo(400);
  }

  @Test
  void aBulkHydrateFansOutAndStillReleasesOneEntryInRequestOrder() throws Exception {
    final Object[] ids = new Object[RECORDS];
    for (int n = 0; n < RECORDS; n++)
      ids[n] = n % 2 == 0 ? recordId(RECORDS - n) : rid(RECORDS - n);

    final Answer answer = post("records:hydrate", CONSOLE, context(null, NETWORK), hydrate(ids));

    assertThat(answer.status()).isEqualTo(200);
    final JSONArray records = answer.json().getJSONArray("records");
    assertThat(records.length()).isEqualTo(RECORDS);
    for (int n = 0; n < RECORDS; n++)
      assertThat(records.getJSONObject(n).getString("recordId")).isEqualTo(Long.toString(recordId(RECORDS - n)));
    assertThat(auditEntries()).isEqualTo(1L);
    assertThat(lastAuditBody().getJSONObject("detail").getLong("records")).isEqualTo((long) RECORDS);
  }

  @Test
  void declaringAContextIsAuditedForEveryPurpose() throws Exception {
    final Answer answer = post("context", CONSOLE, context("CASE-9", "training"), null);

    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.json().getString("analyst")).isEqualTo(CONSOLE_USER);
    assertThat(answer.json().getString("purpose")).isEqualTo("training");
    assertThat(answer.json().getString("caseRef")).isEqualTo("CASE-9");
    final JSONObject entryBody = lastAuditBody();
    assertThat(entryBody.getString("action")).isEqualTo("context.declare");
    assertThat(entryBody.getString("purpose")).isEqualTo("training");
  }

  @Test
  void aLocalBreakGlassUserIsAuditedAsLocal() throws Exception {
    final Answer answer = post("records:hydrate", basicRoot(), context(null, NETWORK), hydrate(rid(1), rid(MARKED)));

    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.json().getJSONArray("missing").toList()).as("local users hold no markings by default").containsExactly(rid(MARKED));
    assertThat(lastAuditBody().getString("actor")).isEqualTo("local:root");
  }

  @Test
  void servesItsOwnOpenApiContract() throws Exception {
    final Answer answer = call("GET", SporkPath.PREFIX + "/openapi.json", CONSOLE, null, null);

    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.body()).isEqualTo(SporkReconApiSpec.toJson());
    assertThat(call("GET", SporkPath.PREFIX + "/openapi.json", null, null, null).status()).isEqualTo(401);
  }
}
