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
package com.arcadedb.mcp.tools;

import com.arcadedb.database.Database;
import com.arcadedb.mcp.MCPConfiguration;
import com.arcadedb.mcp.MCPToolArgumentException;
import com.arcadedb.query.selection.SelectionService;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.security.ServerSecurityUser;

import java.util.List;

/**
 * MCP {@code selection_query} tool: the read operations of saved selections (docs/spork/03-f1-selections-design.md, 2.4) -
 * {@code get}, {@code members}, {@code compare} and {@code venn} - as the MCP-shaped wrapper around {@link SelectionService},
 * which the {@code /api/v1/selection} routes call too, so both surfaces accept and refuse the same requests.
 */
public class SelectionQueryTool {
  public static final String NAME = "selection_query";

  static final List<String> OPERATIONS = List.of("get", "members", "compare", "venn");

  public static JSONObject getDefinition() {
    final JSONObject properties = new JSONObject()
        .put("database", SelectionToolSchema.string("The name of the database"))
        .put("operation", SelectionToolSchema.operation(OPERATIONS, """
            'get': a saved selection's size and provenance. 'members': its live members, in record-id order. 'compare': the \
            elements 2 to 8 selections share, most shared first. 'venn': the size of every overlap region of 2 to 8 selections"""))
        .put("name", SelectionToolSchema.string("For get and members: the saved selection"))
        .put("bitmap", SelectionToolSchema.bool("For get: also return the members as a base64 bitmap"))
        .put("skip", SelectionToolSchema.integer("For members: live members to skip (default 0)"))
        .put("limit", SelectionToolSchema.integer("For members: the maximum members (default 1000); for compare: the maximum rows "
            + "(default 1000)"))
        .put("properties", SelectionToolSchema.bool("For members: include each member's properties (default true)"));
    SelectionToolSchema.addSelectionsAndExpansion(properties);
    properties
        .put("minShared", SelectionToolSchema.integer("For compare: the minimum number of selections an element must be in "
            + "(default 2; 1 also returns distinct elements)"))
        .put("memberCap", SelectionToolSchema.integer("For compare: members named per selection per row (default 10; 0 skips "
            + "the evidence)"));

    return new JSONObject()
        .put("name", NAME)
        .put("description", """
            Read saved selections - named sets of records - and compare them. Compare 2 to 8 selections (saved names or lists \
            of record ids) over their records, or with 'via' over the vertices their records link to, to find the identifiers \
            (phones, emails, images...) they share. Save selections with selection_update; any search tool takes a \
            'selection' argument to search only inside one.""")
        .put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", new JSONArray().put("database").put("operation")));
  }

  public static JSONObject execute(final ArcadeDBServer server, final ServerSecurityUser user, final JSONObject args,
      final MCPConfiguration config) {
    final String databaseName = MCPToolUtils.requireString(args, "database");
    final String operation = SelectionToolSchema.requireOperation(args, OPERATIONS);

    final MCPToolUtils.DatabaseAccess access = MCPToolUtils.resolveDatabase(server, user, databaseName, config,
        MCPToolUtils.RequiredAccess.READ);
    final Database database = access.database();
    final JSONObject body = SelectionToolSchema.body(args);

    return switch (operation) {
      case "get" -> SelectionService.get(database, body);
      case "members" -> SelectionService.members(database, body);
      case "compare" -> SelectionService.compare(database, body);
      case "venn" -> SelectionService.venn(database, body);
      default -> throw new MCPToolArgumentException("Unknown operation: " + operation);
    };
  }
}
