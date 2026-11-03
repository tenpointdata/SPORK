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
import com.arcadedb.query.OperationType;
import com.arcadedb.query.selection.SelectionService;
import com.arcadedb.query.selection.SelectionStore;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.security.ServerSecurityUser;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * MCP {@code selection_update} tool: the write operations of saved selections (docs/spork/03-f1-selections-design.md, 2.4) -
 * {@code save}, {@code combine}, {@code drop} and {@code purge} - over {@link SelectionService}. Gated by the MCP permissions as
 * the other write tools are: saving creates or overwrites a record (insert and update), and the first save also creates the
 * {@value SelectionStore#TYPE_NAME} type (a schema change); dropping and purging delete.
 */
public class SelectionUpdateTool {
  public static final String NAME = "selection_update";

  static final List<String> OPERATIONS = List.of("save", "combine", "drop", "purge");

  public static JSONObject getDefinition() {
    final JSONObject properties = new JSONObject()
        .put("database", SelectionToolSchema.string("The name of the database"))
        .put("operation", SelectionToolSchema.operation(OPERATIONS, """
            'save': save records under a name. 'combine': save the and/or/andNot/xor of 2 to 8 selections under a name. \
            'drop': delete a saved selection. 'purge': delete every expired one"""))
        .put("name", SelectionToolSchema.string("For save, combine and drop: the selection's name"))
        .put("members", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
            .put("description", "For save: the record ids of the members, such as '#12:5'"))
        .put("query", SelectionToolSchema.string("For save: a read-only query whose rows' records become the members"))
        .put("language", SelectionToolSchema.string("For save with a query: its language (default 'opencypher')"))
        .put("params", new JSONObject().put("type", "object").put("description", "For save with a query: its parameters"))
        .put("bitmap", SelectionToolSchema.string("For save: the members as the base64 bitmap selection_query get exports"))
        .put("op", SelectionToolSchema.operation(List.of("and", "or", "andNot", "xor"), "For combine: the set operation"))
        .put("inputs", new JSONObject().put("type", "array").put("minItems", 2).put("maxItems", 8)
            .put("description", "For combine: saved selection names, or lists of record ids"))
        .put("ttl", SelectionToolSchema.integer("For save and combine: seconds until it expires (default "
            + SelectionStore.DEFAULT_TTL_SECONDS + "; 0 never expires)"))
        .put("replace", SelectionToolSchema.bool("For save and combine: overwrite a live selection of the same name"));

    return new JSONObject()
        .put("name", NAME)
        .put("description", """
            Save, combine and delete saved selections - named sets of records that searches, compares and pivots can work \
            inside. Save from record ids or from a read-only query; read them back and compare them with selection_query.""")
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
        MCPToolUtils.RequiredAccess.ACCESS);
    final Database database = access.database();
    ExecuteCommandTool.checkPermission(operationTypes(operation, database), access.permissions());
    final JSONObject body = SelectionToolSchema.body(args);

    return switch (operation) {
      case "save" -> SelectionService.save(database, body);
      case "combine" -> SelectionService.combine(database, body);
      case "drop" -> SelectionService.drop(database, body);
      case "purge" -> SelectionService.purge(database, body);
      default -> throw new MCPToolArgumentException("Unknown operation: " + operation);
    };
  }

  private static Set<OperationType> operationTypes(final String operation, final Database database) {
    if (operation.equals("drop") || operation.equals("purge"))
      return EnumSet.of(OperationType.DELETE);
    final Set<OperationType> types = EnumSet.of(OperationType.CREATE, OperationType.UPDATE);
    if (!database.getSchema().existsType(SelectionStore.TYPE_NAME))
      types.add(OperationType.SCHEMA);
    return types;
  }
}
