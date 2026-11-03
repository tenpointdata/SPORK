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
package com.arcadedb.mcp;

import com.arcadedb.database.Database;
import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.mcp.tools.FullTextSearchTool;
import com.arcadedb.mcp.tools.SelectionQueryTool;
import com.arcadedb.mcp.tools.SelectionUpdateTool;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import com.arcadedb.server.security.ServerSecurityUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 4b (docs/spork/03-f1-selections-design.md, 2.4 and 2.5): the MCP surface of saved selections. Two tools over
 * {@code SelectionService}: {@code selection_query} (get, members, compare, venn; read access) and {@code selection_update}
 * (save, combine, drop, purge; gated by the MCP insert/update/delete/schema permissions like the other write tools). And the
 * search tools take a {@code selection}. Fixture: M1 -> P1, M2 -> P1 and P2, M3 -> P2.
 */
public class SelectionMcpIT extends BaseGraphServerTest {
  private final List<String> ads = new ArrayList<>();

  private MCPConfiguration   config;
  private ServerSecurityUser user;

  @Override
  protected void populateDatabase() {
    super.populateDatabase();

    final Database db = getDatabase(0);
    db.getSchema().createVertexType("McpAd");
    db.getSchema().createVertexType("McpPhone");
    db.getSchema().createEdgeType("MCP_PHONE");
    db.command("sql", "CREATE PROPERTY McpAd.text STRING");
    db.command("sql", "CREATE INDEX ON McpAd (text) FULL_TEXT");
    db.transaction(() -> {
      final MutableVertex p1 = db.newVertex("McpPhone").set("name", "P1").save();
      final MutableVertex p2 = db.newVertex("McpPhone").set("name", "P2").save();
      for (int i = 1; i <= 3; i++) {
        final MutableVertex ad = db.newVertex("McpAd").set("name", "M" + i).set("text", "escort ad " + i).save();
        if (i <= 2)
          ad.newEdge("MCP_PHONE", p1).save();
        if (i >= 2)
          ad.newEdge("MCP_PHONE", p2).save();
        ads.add(ad.getIdentity().toString());
      }
    });
  }

  @BeforeEach
  void setupMCP() {
    config = MCPPlugin.of(getServer(0)).getConfiguration();
    config.setEnabled(true);
    config.setAllowReads(true);
    config.setAllowInsert(true);
    config.setAllowUpdate(true);
    config.setAllowDelete(true);
    config.setAllowSchemaChange(true);
    config.setAllowedUsers(List.of("root"));
    final JSONObject clearOverrides = new JSONObject();
    clearOverrides.put("databases", (Object) null);
    config.updateFrom(clearOverrides);
    user = getServer(0).getSecurity().authenticate("root", DEFAULT_PASSWORD_FOR_TESTS, null);
  }

  @Test
  void saveCompareAndReadThroughTheTools() {
    assertThat(update(new JSONObject().put("operation", "save").put("name", "left").put("members", new JSONArray(ads.subList(0, 1))))
        .getLong("cardinality")).isEqualTo(1L);
    update(new JSONObject().put("operation", "save").put("name", "right").put("members", new JSONArray(ads.subList(1, 3))));

    final JSONObject compared = query(new JSONObject().put("operation", "compare")
        .put("selections", new JSONArray(List.of("left", "right"))).put("via", "MCP_PHONE").put("direction", "OUT"));
    assertThat(compared.getInt("count")).isEqualTo(1);
    assertThat(compared.getJSONArray("rows").getJSONObject(0).getJSONObject("properties").getString("name")).isEqualTo("P1");

    assertThat(query(new JSONObject().put("operation", "venn").put("selections", new JSONArray(List.of("left", "right"))))
        .getJSONArray("regions").length()).isEqualTo(2);
    assertThat(query(new JSONObject().put("operation", "get").put("name", "right")).getLong("cardinality")).isEqualTo(2L);
    assertThat(query(new JSONObject().put("operation", "members").put("name", "right")).getInt("count")).isEqualTo(2);

    assertThat(update(new JSONObject().put("operation", "combine").put("op", "or")
        .put("inputs", new JSONArray(List.of("left", "right"))).put("name", "all")).getLong("cardinality")).isEqualTo(3L);
    assertThat(update(new JSONObject().put("operation", "drop").put("name", "all")).getBoolean("dropped")).isTrue();
    assertThat(update(new JSONObject().put("operation", "purge")).getLong("purged")).isZero();
  }

  @Test
  void theSearchToolsTakeASelection() {
    update(new JSONObject().put("operation", "save").put("name", "two").put("members", new JSONArray(ads.subList(0, 2))));
    final JSONObject hits = FullTextSearchTool.execute(getServer(0), user, new JSONObject().put("database", getDatabaseName())
        .put("indexName", "McpAd[text]").put("queryText", "escort").put("selection", "two"), config);
    assertThat(hits.getInt("count")).isEqualTo(2);
  }

  @Test
  void writesFollowTheMcpPermissions() {
    // The first save creates the SelectionHandle type: a schema change
    config.setAllowSchemaChange(false);
    assertThatThrownBy(() -> update(new JSONObject().put("operation", "save").put("name", "x").put("members", new JSONArray(ads))))
        .isInstanceOf(SecurityException.class).hasMessageContaining("Schema");
    config.setAllowSchemaChange(true);
    update(new JSONObject().put("operation", "save").put("name", "x").put("members", new JSONArray(ads)));

    config.setAllowInsert(false);
    assertThatThrownBy(() -> update(new JSONObject().put("operation", "save").put("name", "y").put("members", new JSONArray(ads))))
        .isInstanceOf(SecurityException.class).hasMessageContaining("Insert");
    config.setAllowInsert(true);

    config.setAllowDelete(false);
    assertThatThrownBy(() -> update(new JSONObject().put("operation", "drop").put("name", "x")))
        .isInstanceOf(SecurityException.class).hasMessageContaining("Delete");
    assertThatThrownBy(() -> update(new JSONObject().put("operation", "purge")))
        .isInstanceOf(SecurityException.class).hasMessageContaining("Delete");

    // Reads need nothing beyond read access
    config.setAllowInsert(false);
    config.setAllowUpdate(false);
    assertThat(query(new JSONObject().put("operation", "get").put("name", "x")).getLong("cardinality")).isEqualTo(3L);
  }

  @Test
  void aTakenNameIsAnAlreadyExistsConflict() {
    update(new JSONObject().put("operation", "save").put("name", "taken").put("members", new JSONArray(ads.subList(0, 1))));
    assertThatThrownBy(() -> update(new JSONObject().put("operation", "save").put("name", "taken")
        .put("members", new JSONArray(ads.subList(1, 2)))))
        .isInstanceOf(AlreadyExistsException.class).hasMessageContaining("a selection named 'taken' already exists");
  }

  @Test
  void anUnknownOperationIsAnArgumentFault() {
    assertThatThrownBy(() -> query(new JSONObject().put("operation", "save").put("name", "x")))
        .isInstanceOf(MCPToolArgumentException.class).hasMessageContaining("get, members, compare, venn");
    assertThatThrownBy(() -> update(new JSONObject().put("operation", "get").put("name", "x")))
        .isInstanceOf(MCPToolArgumentException.class).hasMessageContaining("save, combine, drop, purge");
  }

  @Test
  void theToolsAreRegisteredAdmittedListedAndInTheRagProfile() {
    for (final String tool : List.of(SelectionQueryTool.NAME, SelectionUpdateTool.NAME)) {
      assertThat(MCPDispatcher.REGISTERED_TOOL_NAMES).contains(tool);
      assertThat(MCPDispatcher.isAdmittedTool(tool)).as(tool).isTrue();
      assertThat(MCPDispatcher.isListedTool(tool)).as(tool).isTrue();
      assertThat(MCPDispatcher.isToolAllowed(MCPConfiguration.ToolProfile.RAG, tool)).as(tool).isTrue();
    }
  }

  private JSONObject query(final JSONObject args) {
    return SelectionQueryTool.execute(getServer(0), user, args.put("database", getDatabaseName()), config);
  }

  private JSONObject update(final JSONObject args) {
    return SelectionUpdateTool.execute(getServer(0), user, args.put("database", getDatabaseName()), config);
  }
}
