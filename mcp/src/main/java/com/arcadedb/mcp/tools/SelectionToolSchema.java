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

import com.arcadedb.mcp.MCPToolArgumentException;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.List;

/** The schema pieces and argument handling the two selection tools share. */
final class SelectionToolSchema {
  private SelectionToolSchema() {
  }

  static JSONObject string(final String description) {
    return new JSONObject().put("type", "string").put("description", description);
  }

  static JSONObject integer(final String description) {
    return new JSONObject().put("type", "integer").put("description", description);
  }

  static JSONObject bool(final String description) {
    return new JSONObject().put("type", "boolean").put("description", description);
  }

  static JSONObject operation(final List<String> values, final String description) {
    return string(description).put("enum", new JSONArray(values));
  }

  /** The 'selections', 'via' and 'direction' arguments of compare and venn. */
  static void addSelectionsAndExpansion(final JSONObject properties) {
    properties
        .put("selections", new JSONObject().put("type", "array").put("minItems", 2).put("maxItems", 8)
            .put("description", "For compare and venn: 2 to 8 selections, each a saved selection's name or a list of record ids"))
        .put("via", new JSONObject().put("description", """
            For compare and venn: edge types to expand through (a name, a list, or '*' for every type) to compare the \
            vertices the members link to. Absent compares the members themselves"""))
        .put("direction", operation(List.of("OUT", "IN", "BOTH"), "For compare and venn: direction of the expansion (default BOTH)"));
  }

  static String requireOperation(final JSONObject args, final List<String> operations) {
    final String operation = MCPToolUtils.requireString(args, "operation");
    if (!operations.contains(operation))
      throw new MCPToolArgumentException("'operation' must be one of " + String.join(", ", operations) + ", got '" + operation + "'");
    return operation;
  }

  /** The request {@code SelectionService} reads: the tool's arguments without the two the tool itself consumes. */
  static JSONObject body(final JSONObject args) {
    final JSONObject body = new JSONObject(args.toString());
    body.remove("database");
    body.remove("operation");
    return body;
  }
}
