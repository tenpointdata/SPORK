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
package com.arcadedb.query.selection;

import com.arcadedb.TestHelper;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.RidBitmap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 3 (docs/spork/03-f1-selections-design.md, 2.4): the JSON contract behind {@code /api/v1/selection/{database}/*},
 * exercised without a server. The fixture: a1 -> p1, a2 -> p1 and p2, a3 -> p2.
 */
class SelectionServiceTest extends TestHelper {
  private final Map<String, RID> rids = new HashMap<>();

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("Ad");
    database.getSchema().createVertexType("Phone");
    database.getSchema().createEdgeType("HAS_PHONE");
    database.transaction(() -> {
      final Map<String, MutableVertex> v = new HashMap<>();
      for (final String name : List.of("a1", "a2", "a3"))
        v.put(name, database.newVertex("Ad").set("name", name).save());
      for (final String name : List.of("p1", "p2"))
        v.put(name, database.newVertex("Phone").set("name", name).save());
      v.get("a1").newEdge("HAS_PHONE", v.get("p1")).save();
      v.get("a2").newEdge("HAS_PHONE", v.get("p1")).save();
      v.get("a2").newEdge("HAS_PHONE", v.get("p2")).save();
      v.get("a3").newEdge("HAS_PHONE", v.get("p2")).save();
      for (final Map.Entry<String, MutableVertex> e : v.entrySet())
        rids.put(e.getKey(), e.getValue().getIdentity());
    });
  }

  @Test
  void saveGetAndMembers() {
    final JSONObject saved = SelectionService.save(database, new JSONObject().put("name", "g0").put("members", rids("a1", "a2")));
    assertThat(saved.getString("name")).isEqualTo("g0");
    assertThat(saved.getLong("cardinality")).isEqualTo(2L);
    assertThat(saved.getLong("expiresAt") - saved.getLong("createdAt")).isEqualTo(SelectionStore.DEFAULT_TTL_SECONDS * 1000L);

    final JSONObject handle = SelectionService.get(database, new JSONObject().put("name", "g0"));
    assertThat(handle.getString("source")).isEqualTo("members");
    assertThat(handle.has("bitmap")).isFalse();

    final JSONObject members = SelectionService.members(database, new JSONObject().put("name", "g0"));
    assertThat(members.getInt("count")).isEqualTo(2);
    final JSONObject first = members.getJSONArray("members").getJSONObject(0);
    assertThat(first.getString("rid")).isEqualTo(rids.get("a1").toString());
    assertThat(first.getJSONObject("properties").getString("name")).isEqualTo("a1");

    final JSONObject ridsOnly = SelectionService.members(database,
        new JSONObject().put("name", "g0").put("properties", false).put("skip", 1));
    assertThat(ridsOnly.getInt("count")).isEqualTo(1);
    assertThat(ridsOnly.getJSONArray("members").getJSONObject(0).has("properties")).isFalse();
  }

  @Test
  void aQueryIsASource() {
    final JSONObject saved = SelectionService.save(database, new JSONObject().put("name", "q")
        .put("query", "SELECT FROM Ad WHERE name <> :skip").put("language", "sql").put("params", new JSONObject().put("skip", "a3"))
        .put("ttl", 0));
    assertThat(saved.getLong("cardinality")).isEqualTo(2L);
    assertThat(saved.isNull("expiresAt")).isTrue();
  }

  @Test
  void theBitmapRoundTrips() {
    SelectionService.save(database, new JSONObject().put("name", "g0").put("members", rids("a1", "a3")));
    final JSONObject handle = SelectionService.get(database, new JSONObject().put("name", "g0").put("bitmap", true));

    final RidBitmap decoded = RidBitmap.fromBytes(Base64.getDecoder().decode(handle.getString("bitmap")));
    assertThat(decoded.cardinality()).isEqualTo(2L);
    assertThat(decoded.contains(rids.get("a1"))).isTrue();
    assertThat(handle.getString("bitmapFormat")).isEqualTo(SelectionService.BITMAP_FORMAT);

    final JSONObject copy = SelectionService.save(database,
        new JSONObject().put("name", "copy").put("bitmap", handle.getString("bitmap")));
    assertThat(copy.getLong("cardinality")).isEqualTo(2L);
  }

  @Test
  void compareAndVennByName() {
    SelectionService.save(database, new JSONObject().put("name", "g0").put("members", rids("a1")));
    SelectionService.save(database, new JSONObject().put("name", "g1").put("members", rids("a2", "a3")));

    final JSONObject compared = SelectionService.compare(database, new JSONObject()
        .put("selections", new JSONArray(List.of("g0", "g1"))).put("via", "HAS_PHONE").put("direction", "OUT"));
    assertThat(compared.getInt("count")).isEqualTo(1);
    final JSONObject row = compared.getJSONArray("rows").getJSONObject(0);
    assertThat(row.getString("rid")).isEqualTo(rids.get("p1").toString());
    assertThat(row.getJSONObject("properties").getString("name")).isEqualTo("p1");
    assertThat(row.getInt("sharedBy")).isEqualTo(2);
    assertThat(longs(row.getJSONArray("counts"))).containsExactly(1L, 1L);
    assertThat(row.getJSONArray("members").getJSONArray(1).toList()).containsExactly(rids.get("a2").toString());

    final JSONObject venn = SelectionService.venn(database, new JSONObject()
        .put("selections", new JSONArray().put("g0").put(rids("a2", "a3"))));
    final JSONArray regions = venn.getJSONArray("regions");
    assertThat(regions.length()).isEqualTo(2);
    assertThat(longs(regions.getJSONObject(0).getJSONArray("selections"))).containsExactly(0L);
    assertThat(regions.getJSONObject(0).getLong("count")).isEqualTo(1L);
  }

  @Test
  void combineDropAndPurge() {
    SelectionService.save(database, new JSONObject().put("name", "x").put("members", rids("a1", "a2")));
    SelectionService.save(database, new JSONObject().put("name", "y").put("members", rids("a2", "a3")));

    final JSONObject combined = SelectionService.combine(database, new JSONObject().put("op", "and")
        .put("inputs", new JSONArray(List.of("x", "y"))).put("name", "both"));
    assertThat(combined.getLong("cardinality")).isEqualTo(1L);

    assertThat(SelectionService.drop(database, new JSONObject().put("name", "both")).getBoolean("dropped")).isTrue();
    assertThat(SelectionService.drop(database, new JSONObject().put("name", "both")).getBoolean("dropped")).isFalse();

    database.transaction(() -> database.command("sql", "UPDATE " + SelectionStore.TYPE_NAME + " SET expiresAt = 1 WHERE name = 'x'"));
    assertThat(SelectionService.purge(database, new JSONObject()).getLong("purged")).isEqualTo(1L);
    assertThat(SelectionService.purge(database, null).getLong("purged")).isZero();
  }

  @Test
  void refusalsSurfaceAsIllegalArgumentWithTheProcedureMessage() {
    assertThatThrownBy(() -> SelectionService.get(database, new JSONObject().put("name", "nope")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no selection named 'nope'");
    assertThatThrownBy(() -> SelectionService.compare(database, new JSONObject().put("selections", new JSONArray().put("g0"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selections must hold 2 to 8 lists");
  }

  @Test
  void saveNeedsANameAndExactlyOneSource() {
    assertThatThrownBy(() -> SelectionService.save(database, new JSONObject().put("members", rids("a1"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'name' is required");
    assertThatThrownBy(() -> SelectionService.save(database, new JSONObject().put("name", "x")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one of 'members', 'query' or 'bitmap'");
    assertThatThrownBy(() -> SelectionService.save(database,
        new JSONObject().put("name", "x").put("members", rids("a1")).put("query", "SELECT FROM Ad")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one of 'members', 'query' or 'bitmap'");
    assertThatThrownBy(() -> SelectionService.save(database, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request body is required");
  }

  private static List<Long> longs(final JSONArray array) {
    final List<Long> values = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++)
      values.add(((Number) array.get(i)).longValue());
    return values;
  }

  private JSONArray rids(final String... names) {
    final List<String> list = new ArrayList<>();
    for (final String name : names)
      list.add(rids.get(name).toString());
    return new JSONArray(list);
  }
}
