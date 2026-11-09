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
package com.arcadedb.query.opencypher.procedures.identity;

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.Evidence;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.EvidenceType;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.MatchResult;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An evidence row has to name the records behind it (HANDOFF open work 34, {@code docs/spork/04-identity-resolution-design.md}
 * 2.5). Before this, a row said "Phone p1, 2 ads on side A, 1 on side B, nearest sighting a day apart" and the analyst had
 * to go and find those three ads and their dates, which is the reconstruction the procedures exist to remove.
 * <p>
 * Over the ad graph of {@link IdentityFixture}: A = [a1, a2] and B = [b1, b2] are one person. p1 is reached by a1 (day 0),
 * a2 (day 2) and b1 (day 3); e1 by a1 (day 0) and b2 (day 5).
 * </p>
 */
class IdentityEvidenceRecordsTest extends TestHelper {
  private static final long DAY = IdentityFixture.DAY;
  private static final long T0  = IdentityFixture.T0;

  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    rids.putAll(IdentityFixture.build(database));
  }

  // ---- the model carries the members and the windows ----

  @Test
  void theModelReportsTheMemberIndicesAndTheWindowOfEachSide() {
    final IdentityMatchModel model = new IdentityMatchModel().population(10_000_000L);
    final RID phone = rids.get("p1");
    final RID email = rids.get("e1");
    final int phoneKind = model.kindIndex("Phone");
    final int emailKind = model.kindIndex("Email");

    // A = a1 (day 0, phone + email), a2 (day 2, phone); B = b1 (day 3, phone), b2 (day 5, email)
    final ClusterProfile a = new ClusterProfile.Builder()
        .addMember(T0, Double.NaN, Double.NaN, new RID[] { phone, email }, new int[] { phoneKind, emailKind })
        .addMember(T0 + 2 * DAY, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind })
        .build();
    final ClusterProfile b = new ClusterProfile.Builder()
        .addMember(T0 + 3 * DAY, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind })
        .addMember(T0 + 5 * DAY, Double.NaN, Double.NaN, new RID[] { email }, new int[] { emailKind })
        .build();

    final MatchResult r = model.compare(a, b, null);
    final Evidence phoneRow = sharedRow(r, phone);
    assertThat(phoneRow.a().members()).containsExactly(0, 1);
    assertThat(phoneRow.a().first()).isEqualTo(T0);
    assertThat(phoneRow.a().last()).isEqualTo(T0 + 2 * DAY);
    assertThat(phoneRow.b().members()).containsExactly(0);
    assertThat(phoneRow.b().first()).isEqualTo(T0 + 3 * DAY);
    assertThat(phoneRow.b().last()).isEqualTo(T0 + 3 * DAY);

    // e1 is a1 on one side and b2 on the other, so each side's window is that one member's time
    final Evidence emailRow = sharedRow(r, email);
    assertThat(emailRow.a().members()).containsExactly(0);
    assertThat(emailRow.a().last()).isEqualTo(T0);
    assertThat(emailRow.b().members()).containsExactly(1);
    assertThat(emailRow.b().first()).isEqualTo(T0 + 5 * DAY);
  }

  @Test
  void aLeaveOneOutViewDropsTheExcludedMemberFromItsEvidence() {
    final IdentityMatchModel model = new IdentityMatchModel().population(10_000_000L);
    final RID phone = rids.get("p1");
    final int phoneKind = model.kindIndex("Phone");
    final ClusterProfile full = new ClusterProfile.Builder()
        .addMember(T0, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind })
        .addMember(T0 + 2 * DAY, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind })
        .addMember(T0 + 3 * DAY, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind })
        .build();

    // Member 1 against the rest: the rest is members 0 and 2, and its window skips day 2
    final Evidence row = sharedRow(model.compare(full.only(1), full.without(1), null), phone);
    assertThat(row.a().members()).containsExactly(0);
    assertThat(row.a().first()).isEqualTo(T0 + 2 * DAY);
    assertThat(row.b().members()).containsExactly(0, 2);
    assertThat(row.b().first()).isEqualTo(T0);
    assertThat(row.b().last()).isEqualTo(T0 + 3 * DAY);
  }

  @Test
  void aTimeBlindProfileReportsNoWindow() {
    final IdentityMatchModel model = new IdentityMatchModel().population(10_000_000L);
    final RID phone = rids.get("p1");
    final int phoneKind = model.kindIndex("Phone");
    final ClusterProfile a = new ClusterProfile.Builder()
        .addMember(ClusterProfile.NO_TIME, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind }).build();
    final ClusterProfile b = new ClusterProfile.Builder()
        .addMember(ClusterProfile.NO_TIME, Double.NaN, Double.NaN, new RID[] { phone }, new int[] { phoneKind }).build();

    final Evidence row = sharedRow(model.compare(a, b, null), phone);
    assertThat(row.a().members()).containsExactly(0);
    assertThat(row.a().first()).isEqualTo(ClusterProfile.NO_TIME);
    assertThat(row.a().last()).isEqualTo(ClusterProfile.NO_TIME);
  }

  // ---- identity.compare renders them as records ----

  @Test
  void compareNamesTheAdsBehindASharedIdentifier() {
    final Map<String, Object> phone = sharedEvidence(compare(sel("a1", "a2"), sel("b1", "b2"), options()), "p1");

    assertThat(phone.get("countA")).isEqualTo(2);
    assertThat(records(phone, "recordsA")).containsExactly(
        Map.entry(rids.get("a1"), T0),
        Map.entry(rids.get("a2"), T0 + 2 * DAY));
    assertThat(records(phone, "recordsB")).containsExactly(Map.entry(rids.get("b1"), T0 + 3 * DAY));
    assertThat(phone.get("firstA")).isEqualTo(T0);
    assertThat(phone.get("lastA")).isEqualTo(T0 + 2 * DAY);
    assertThat(phone.get("firstB")).isEqualTo(T0 + 3 * DAY);
    assertThat(phone.get("lastB")).isEqualTo(T0 + 3 * DAY);
  }

  @Test
  void aSharedIdentifierOnOneAdPerSideNamesThatAd() {
    final Map<String, Object> email = sharedEvidence(compare(sel("a1", "a2"), sel("b1", "b2"), options()), "e1");

    assertThat(records(email, "recordsA")).containsExactly(Map.entry(rids.get("a1"), T0));
    assertThat(records(email, "recordsB")).containsExactly(Map.entry(rids.get("b2"), T0 + 5 * DAY));
  }

  @Test
  void theRecordListIsCappedWhileTheCountStaysTrue() {
    final Map<String, Object> opts = options();
    opts.put("evidenceRecords", 1);
    final Map<String, Object> phone = sharedEvidence(compare(sel("a1", "a2"), sel("b1", "b2"), opts), "p1");

    // The cap is on what is shown, never on what is counted or on the window
    assertThat(records(phone, "recordsA")).containsExactly(Map.entry(rids.get("a1"), T0));
    assertThat(phone.get("countA")).isEqualTo(2);
    assertThat(phone.get("lastA")).isEqualTo(T0 + 2 * DAY);
  }

  @Test
  void zeroRecordsLeavesTheWindowsAndDropsTheLists() {
    final Map<String, Object> opts = options();
    opts.put("evidenceRecords", 0);
    final Map<String, Object> phone = sharedEvidence(compare(sel("a1", "a2"), sel("b1", "b2"), opts), "p1");

    assertThat(phone).doesNotContainKeys("recordsA", "recordsB");
    assertThat(phone.get("firstA")).isEqualTo(T0);
    assertThat(phone.get("countA")).isEqualTo(2);
  }

  @Test
  void aNegativeRecordLimitIsRefused() {
    final Map<String, Object> opts = options();
    opts.put("evidenceRecords", -1);
    assertThatThrownBy(() -> compare(sel("a1"), sel("b1"), opts))
        .hasMessageContaining("evidenceRecords");
  }

  @Test
  void withoutATimePropertyTheAdsAreNamedWithoutTheirDates() {
    final Map<String, Object> opts = options();
    opts.remove("timeProperty");
    final Map<String, Object> phone = sharedEvidence(compare(sel("a1", "a2"), sel("b1"), opts), "p1");

    assertThat(phone.get("firstA")).isNull();
    assertThat(phone.get("lastB")).isNull();
    final List<Map<String, Object>> shown = evidenceRecords(phone, "recordsA");
    assertThat(shown).extracting(m -> m.get("record")).containsExactly(rids.get("a1"), rids.get("a2"));
    assertThat(shown).allSatisfy(m -> assertThat(m.get("at")).isNull());
  }

  @Test
  void aDisjointRowCarriesTheKindWindowAndNamesNoRecord() {
    // A and C share no identifier at all, so every kind both use is a DISJOINT row
    final Result r = compare(sel("a1", "a2"), sel("c1", "c2"), options());
    final Map<String, Object> disjoint = evidence(r).stream()
        .filter(e -> "DISJOINT".equals(e.get("type")) && "Phone".equals(e.get("kind")))
        .findFirst().orElseThrow();

    assertThat(disjoint).doesNotContainKeys("recordsA", "recordsB");
    assertThat(disjoint.get("firstA")).isEqualTo(T0);
    assertThat(disjoint.get("lastA")).isEqualTo(T0 + 2 * DAY);
    assertThat(disjoint.get("firstB")).isEqualTo(T0 + IdentityFixture.HOUR);
  }

  // ---- cohesion and candidates map their own sides ----

  @Test
  void cohesionNamesTheMemberOnOneSideAndTheRestOnTheOther() {
    final Map<String, Object> params = new HashMap<>();
    params.put("c", sel("a1", "a2", "b1"));
    params.put("opts", options());
    final List<Result> rows = rows("CALL identity.cohesion($c, $opts) YIELD member, evidence RETURN member, evidence", params);

    final Result a1 = rows.stream().filter(x -> rids.get("a1").equals(x.getProperty("member"))).findFirst().orElseThrow();
    final Map<String, Object> phone = sharedEvidence(a1, "p1");
    // Side A is the member being scored, side B the rest of the cluster
    assertThat(records(phone, "recordsA")).containsExactly(Map.entry(rids.get("a1"), T0));
    assertThat(records(phone, "recordsB")).containsExactly(
        Map.entry(rids.get("a2"), T0 + 2 * DAY),
        Map.entry(rids.get("b1"), T0 + 3 * DAY));
  }

  @Test
  void candidatesNameTheAdsOfTheGroupTheyFound() {
    final Map<String, Object> params = new HashMap<>();
    params.put("c", sel("a1", "a2"));
    params.put("opts", options());
    final List<Result> rows = rows("CALL identity.candidates($c, $opts) YIELD candidate, evidence RETURN candidate, evidence",
        params);

    final Result b1 = rows.stream().filter(x -> rids.get("b1").equals(x.getProperty("candidate"))).findFirst().orElseThrow();
    final Map<String, Object> phone = sharedEvidence(b1, "p1");
    assertThat(records(phone, "recordsA")).containsExactly(
        Map.entry(rids.get("a1"), T0),
        Map.entry(rids.get("a2"), T0 + 2 * DAY));
    assertThat(records(phone, "recordsB")).containsExactly(Map.entry(rids.get("b1"), T0 + 3 * DAY));
  }

  @Test
  void featuresExportsTheSameRecordsAndWindows() {
    final Map<String, Object> params = new HashMap<>();
    params.put("a", sel("a1", "a2"));
    params.put("b", sel("b1", "b2"));
    params.put("opts", options());
    final List<Result> rows = rows("CALL identity.features($a, $b, $opts) YIELD shared RETURN shared", params);
    assertThat(rows).hasSize(1);

    final List<Map<String, Object>> shared = rows.getFirst().getProperty("shared");
    final Map<String, Object> phone = shared.stream().filter(s -> rids.get("p1").equals(s.get("identifier"))).findFirst()
        .orElseThrow();
    assertThat(records(phone, "recordsA")).containsExactly(
        Map.entry(rids.get("a1"), T0),
        Map.entry(rids.get("a2"), T0 + 2 * DAY));
    assertThat(phone.get("lastB")).isEqualTo(T0 + 3 * DAY);
  }

  // ---- helpers ----

  private static Map<String, Object> options() {
    return IdentityFixture.options();
  }

  private static Evidence sharedRow(final MatchResult r, final RID identifier) {
    return r.evidence().stream().filter(e -> e.type() == EvidenceType.SHARED && identifier.equals(e.identifier())).findFirst()
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> evidence(final Result r) {
    return (List<Map<String, Object>>) r.<List<?>>getProperty("evidence");
  }

  private Map<String, Object> sharedEvidence(final Result r, final String identifier) {
    final RID rid = rids.get(identifier);
    return evidence(r).stream().filter(e -> rid.equals(e.get("identifier"))).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> evidenceRecords(final Map<String, Object> row, final String key) {
    return (List<Map<String, Object>>) row.get(key);
  }

  /** The {@code (record, at)} pairs of one side, so a test reads them as one list. */
  private static List<Map.Entry<RID, Long>> records(final Map<String, Object> row, final String key) {
    final List<Map.Entry<RID, Long>> pairs = new ArrayList<>();
    for (final Map<String, Object> m : evidenceRecords(row, key))
      pairs.add(Map.entry((RID) m.get("record"), (Long) m.get("at")));
    return pairs;
  }

  private Result compare(final List<Object> a, final List<Object> b, final Map<String, Object> options) {
    final Map<String, Object> params = new HashMap<>();
    params.put("a", a);
    params.put("b", b);
    params.put("opts", options);
    final List<Result> rows = rows("CALL identity.compare($a, $b, $opts) YIELD evidence, verdict RETURN evidence, verdict",
        params);
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private List<Object> sel(final String... names) {
    final List<Object> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return list;
  }

  private List<Result> rows(final String query, final Map<String, Object> params) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        rows.add(rs.next());
    }
    return rows;
  }
}
