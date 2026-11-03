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

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The small ad graph shared by the identity procedure tests. Times are days from T0, places are New York (NY), Los Angeles
 * (LA) and Chicago (CH):
 * <pre>
 *   a1 day 0 NY  -HAS_PHONE-> p1, -HAS_EMAIL-> e1
 *   a2 day 2 NY  -HAS_PHONE-> p1
 *   b1 day 3 NY  -HAS_PHONE-> p1
 *   b2 day 5 NY  -HAS_EMAIL-> e1
 *   c1 day 0 +1h LA -HAS_PHONE-> p9, -HAS_EMAIL-> e9
 *   c2 day 2 +1h LA -HAS_PHONE-> p9
 *   x  day 400 CH -HAS_PHONE-> p7, -HAS_EMAIL-> e7
 *   gone (deleted)
 * </pre>
 * A = [a1, a2] and B = [b1, b2] are one person; C = [c1, c2] posts from Los Angeles an hour after A posts from New York.
 * Phones carry {@code recordCount} 3 and emails 2. For the soft signals, {@code style} is a 3-d style vector (A and B
 * write alike, C does not, x has none) and {@code img} a 64-bit image hash: a1 and b1 post the same photo one bit apart,
 * c1 a different one.
 */
final class IdentityFixture {
  static final long DAY  = 86_400_000L;
  static final long HOUR = 3_600_000L;
  static final long T0   = 1_700_000_000_000L;

  static final long PHOTO       = 0x00FF00FF00FF00FFL;
  static final long OTHER_PHOTO = 0x7E7E7E7E7E7E7E7EL;

  private IdentityFixture() {
  }

  static Map<String, RID> build(final Database database) {
    database.getSchema().createVertexType("Ad");
    database.getSchema().createVertexType("Phone");
    database.getSchema().createVertexType("Email");
    database.getSchema().createEdgeType("HAS_PHONE");
    database.getSchema().createEdgeType("HAS_EMAIL");

    final Map<String, RID> rids = new HashMap<>();
    database.transaction(() -> {
      final Map<String, MutableVertex> v = new HashMap<>();
      ad(database, v, "a1", T0, 40.71, -74.00);
      ad(database, v, "a2", T0 + 2 * DAY, 40.71, -74.00);
      ad(database, v, "b1", T0 + 3 * DAY, 40.72, -73.99);
      ad(database, v, "b2", T0 + 5 * DAY, 40.72, -73.99);
      ad(database, v, "c1", T0 + HOUR, 34.05, -118.24);
      ad(database, v, "c2", T0 + 2 * DAY + HOUR, 34.05, -118.24);
      ad(database, v, "x", T0 + 400 * DAY, 41.88, -87.63);
      ad(database, v, "gone", T0, 40.71, -74.00);
      for (final String name : List.of("p1", "p7", "p9"))
        v.put(name, database.newVertex("Phone").set("name", name).set("recordCount", 3).save());
      for (final String name : List.of("e1", "e7", "e9"))
        v.put(name, database.newVertex("Email").set("name", name).set("recordCount", 2).save());

      soft(v, "a1", new float[] { 1, 0.1f, 0 }, PHOTO);
      soft(v, "a2", new float[] { 1, 0.2f, 0 }, null);
      soft(v, "b1", new float[] { 0.98f, 0.12f, 0.01f }, PHOTO ^ 1);
      soft(v, "b2", new float[] { 1, 0.15f, 0 }, null);
      soft(v, "c1", new float[] { 0, 1, 0.2f }, OTHER_PHOTO);
      soft(v, "c2", new float[] { 0.1f, 1, 0 }, null);

      link(v, "a1", "HAS_PHONE", "p1");
      link(v, "a1", "HAS_EMAIL", "e1");
      link(v, "a2", "HAS_PHONE", "p1");
      link(v, "b1", "HAS_PHONE", "p1");
      link(v, "b2", "HAS_EMAIL", "e1");
      link(v, "c1", "HAS_PHONE", "p9");
      link(v, "c1", "HAS_EMAIL", "e9");
      link(v, "c2", "HAS_PHONE", "p9");
      link(v, "x", "HAS_PHONE", "p7");
      link(v, "x", "HAS_EMAIL", "e7");

      for (final Map.Entry<String, MutableVertex> e : v.entrySet())
        rids.put(e.getKey(), e.getValue().getIdentity());
    });

    database.transaction(() -> rids.get("gone").asVertex().delete());
    return rids;
  }

  /** The options every test starts from: the identifier edges, time, place and a 10M-record population. */
  static Map<String, Object> options() {
    final Map<String, Object> opts = new HashMap<>();
    opts.put("via", List.of("HAS_PHONE", "HAS_EMAIL"));
    opts.put("direction", "OUT");
    opts.put("timeProperty", "ts");
    opts.put("latProperty", "lat");
    opts.put("lonProperty", "lon");
    opts.put("population", 10_000_000);
    return opts;
  }

  private static void ad(final Database database, final Map<String, MutableVertex> v, final String name, final long ts,
      final double lat, final double lon) {
    v.put(name, database.newVertex("Ad").set("name", name).set("ts", ts).set("lat", lat).set("lon", lon).save());
  }

  private static void soft(final Map<String, MutableVertex> v, final String name, final float[] style, final Long image) {
    final MutableVertex vertex = v.get(name).set("style", style);
    if (image != null)
      vertex.set("img", image);
    vertex.save();
  }

  private static void link(final Map<String, MutableVertex> v, final String from, final String type, final String to) {
    v.get(from).newEdge(type, v.get(to));
  }
}
