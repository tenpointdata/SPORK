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
package com.arcadedb.server.http.handler.openapi;

import com.arcadedb.query.selection.SelectionService;
import com.arcadedb.query.selection.SelectionStore;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.math.BigDecimal;
import java.util.List;

/**
 * Documents the {@code /api/v1/selection/{database}/*} routes: saved selections, their set algebra and the compare of 2 to 8
 * selections (docs/spork/03-f1-selections-design.md, 2.4). Defaults are read from {@link SelectionStore} and the bitmap
 * format name from {@link SelectionService}, so the document cannot drift from what the server applies.
 */
public class SelectionApiSpec implements OpenApiContributor {
  private static final String TAG = "Selection";

  @Override
  public void contribute(final OpenAPI openAPI) {
    add(openAPI, "save", "selectionSave", "Save a selection under a name", """
        Saves members under a name with an expiry. The members come from exactly one of 'members' (record ids), 'query' (run \
        read-only under the caller's user; every record identity in a row, or in a list inside a row, becomes a member) or \
        'bitmap' (the base64 envelope 'get' exports). Saving over a live selection needs 'replace'; an expired one is \
        overwritten.""", saveRequest(), handleResponse());
    add(openAPI, "combine", "selectionCombine", "Save the set algebra of 2 to 8 selections", """
        Saves 'and' (members of every input), 'or' (of any), 'andNot' (of the first and none of the others) or 'xor' (of an \
        odd number of inputs) of the inputs under a new name. Each input is a saved selection's name or a list of record \
        ids.""", combineRequest(), handleResponse());
    add(openAPI, "compare", "selectionCompare", "Elements 2 to 8 selections share", """
        One row per element in at least 'minShared' selections, most shared first. The elements are the members \
        themselves, or with 'via' the vertices the members reach over one hop of those edge types. Unless 'memberCap' is \
        0, each row says how many members of each selection reach it and names up to 'memberCap' of them.""",
        compareRequest(), compareResponse());
    add(openAPI, "venn", "selectionVenn", "Size of every Venn region of 2 to 8 selections", """
        The number of elements in exactly the selections of each non-empty region, computed with bitmap operations \
        rather than per element.""", vennRequest(), vennResponse());
    add(openAPI, "get", "selectionGet", "Metadata and provenance of a saved selection", """
        Refused when there is no selection of that name or it has expired. With 'bitmap', also returns the members in the \
        '""" + SelectionService.BITMAP_FORMAT + "' envelope, base64-encoded, for set algebra on the client.", nameRequest(true),
        getResponse());
    add(openAPI, "members", "selectionMembers", "Live members of a saved selection", """
        In record-id order. Members deleted since the selection was saved are skipped and do not count toward 'skip' or \
        'limit'.""", membersRequest(), membersResponse());
    add(openAPI, "drop", "selectionDrop", "Delete a saved selection", "Deletes it, expired or not. 'dropped' is false when there "
        + "was none.", nameRequest(false), dropResponse());
    add(openAPI, "purge", "selectionPurge", "Delete every expired saved selection", """
        Writes already delete expired selections a batch at a time; this reclaims all of them at once. Takes no body.""",
        null, purgeResponse());
  }

  private void add(final OpenAPI openAPI, final String operation, final String operationId, final String summary,
      final String description, final Schema<?> request, final Schema<?> response) {
    final String component = Character.toUpperCase(operationId.charAt(0)) + operationId.substring(1);
    final Operation post = SpecBuilders.operation(operationId, TAG, summary, description);
    post.addParametersItem(SpecBuilders.pathParam("database", "Database name"));
    post.addParametersItem(SpecBuilders.sessionHeaderParam());
    if (request != null) {
      openAPI.getComponents().addSchemas(component + "Request", request);
      post.setRequestBody(SpecBuilders.jsonBody(summary, component + "Request", true));
    }
    openAPI.getComponents().addSchemas(component + "Response", response);
    post.setResponses(responses(SpecBuilders.jsonResponse(summary, component + "Response")));

    final PathItem pathItem = new PathItem();
    pathItem.setPost(post);
    openAPI.getPaths().addPathItem("/api/v1/selection/{database}/" + operation, pathItem);
  }

  /** As the vector routes': these handlers degrade on a session header they cannot resolve rather than joining it. */
  private ApiResponses responses(final ApiResponse success) {
    success.addHeaderObject(SpecBuilders.SESSION_HEADER, SpecBuilders.sessionEchoHeader());
    success.addHeaderObject(SpecBuilders.SESSION_EXPIRED_HEADER, SpecBuilders.sessionExpiredHeader());
    final ApiResponses responses = SpecBuilders.standardResponses("200", success, "400", "401", "403", "404", "500");
    responses.addApiResponse("404", SpecBuilders.errorResponse(SpecBuilders.READ_STALE_SESSION_DESCRIPTION));
    return responses;
  }

  // ---- requests ----

  private Schema<?> saveRequest() {
    final Schema<Object> schema = SpecBuilders.object("Save a selection");
    schema.addProperty("name", name());
    schema.addProperty("members", rids("Record ids of the members, such as '#12:5'"));
    schema.addProperty("query", SpecBuilders.string("A query whose rows' record identities become the members, run read-only"));
    schema.addProperty("language", SpecBuilders.string("Language of 'query', default 'opencypher'"));
    schema.addProperty("params", SpecBuilders.freeFormObject("Parameters of 'query'"));
    schema.addProperty("bitmap", SpecBuilders.string("Members in the '" + SelectionService.BITMAP_FORMAT + "' envelope, base64"));
    addWriteOptions(schema);
    schema.setRequired(List.of("name"));
    return schema;
  }

  private Schema<?> combineRequest() {
    final Schema<Object> schema = SpecBuilders.object("Save the set algebra of 2 to 8 selections");
    final Schema<String> op = SpecBuilders.string("The operation");
    op.setEnum(List.of("and", "or", "andNot", "xor"));
    schema.addProperty("op", op);
    schema.addProperty("inputs", selections("The inputs: names of saved selections, or lists of record ids"));
    schema.addProperty("name", name());
    addWriteOptions(schema);
    schema.setRequired(List.of("op", "inputs", "name"));
    return schema;
  }

  private Schema<?> compareRequest() {
    final Schema<Object> schema = vennRequest();
    schema.setDescription("Compare 2 to 8 selections");
    schema.addProperty("minShared", bounded("Minimum number of selections an element must be in; 1 also returns distinct ones",
        1, 8, 2));
    schema.addProperty("limit", bounded("Maximum rows", 1, Integer.MAX_VALUE, 1000));
    schema.addProperty("memberCap", bounded("Members named per selection per row; 0 skips the evidence pass", 0, 10_000, 10));
    return schema;
  }

  private Schema<Object> vennRequest() {
    final Schema<Object> schema = SpecBuilders.object("Venn regions of 2 to 8 selections");
    schema.addProperty("selections", selections("The selections: names of saved selections, or lists of record ids"));
    schema.addProperty("via", SpecBuilders.anyValue("""
        Edge types to expand through (a name, a list, or '*' for every type). Absent compares the members themselves"""));
    final Schema<String> direction = SpecBuilders.string("Direction of the expansion, default BOTH");
    direction.setEnum(List.of("OUT", "IN", "BOTH"));
    schema.addProperty("direction", direction);
    schema.setRequired(List.of("selections"));
    return schema;
  }

  private Schema<?> nameRequest(final boolean withBitmap) {
    final Schema<Object> schema = SpecBuilders.object("Names a saved selection");
    schema.addProperty("name", name());
    if (withBitmap)
      schema.addProperty("bitmap", SpecBuilders.bool("Also return the members as a base64 bitmap"));
    schema.setRequired(List.of("name"));
    return schema;
  }

  private Schema<?> membersRequest() {
    final Schema<Object> schema = SpecBuilders.object("Page through the live members of a saved selection");
    schema.addProperty("name", name());
    schema.addProperty("skip", bounded("Live members to skip", 0, Integer.MAX_VALUE, 0));
    schema.addProperty("limit", bounded("Maximum members", 1, Integer.MAX_VALUE, 1000));
    schema.addProperty("properties", SpecBuilders.bool("Include each member's properties, default true"));
    schema.setRequired(List.of("name"));
    return schema;
  }

  // ---- responses ----

  private Schema<?> handleResponse() {
    final Schema<Object> schema = SpecBuilders.object("The saved selection");
    addHandleFields(schema);
    schema.setRequired(List.of("name", "cardinality", "createdAt"));
    return schema;
  }

  private Schema<?> getResponse() {
    final Schema<Object> schema = SpecBuilders.object("A saved selection's metadata and provenance");
    addHandleFields(schema);
    schema.addProperty("source", SpecBuilders.string("The query, 'members', 'bitmap' or the 'combine:<op>(...)' that made it"));
    schema.addProperty("language", SpecBuilders.string("Language of a query source"));
    schema.addProperty("params", SpecBuilders.freeFormObject("Parameters of a query source"));
    schema.addProperty("createdBy", SpecBuilders.string("Database user who saved it"));
    schema.addProperty("bitmapFormat", SpecBuilders.string("'" + SelectionService.BITMAP_FORMAT + "', when 'bitmap' was asked"));
    schema.addProperty("bitmap", SpecBuilders.string("The members, base64, when 'bitmap' was asked"));
    schema.setRequired(List.of("name", "cardinality", "createdAt"));
    return schema;
  }

  private Schema<?> compareResponse() {
    final Schema<Object> row = SpecBuilders.object("An element in at least 'minShared' selections");
    addRecordFields(row);
    row.addProperty("mask", SpecBuilders.integer("Bit i set when the element is in selection i"));
    row.addProperty("selections", SpecBuilders.arrayOf(SpecBuilders.integer(null), "Indexes of the selections it is in"));
    row.addProperty("sharedBy", SpecBuilders.integer("How many selections it is in"));
    row.addProperty("counts", SpecBuilders.arrayOf(SpecBuilders.integer(null),
        "Per selection, how many members reach it; absent when memberCap is 0"));
    row.addProperty("members", SpecBuilders.arrayOf(rids(null),
        "Per selection, up to memberCap of those members; absent when memberCap is 0"));
    row.setRequired(List.of("rid", "mask", "selections", "sharedBy"));

    final Schema<Object> schema = SpecBuilders.object("Shared elements, most shared first");
    schema.addProperty("count", SpecBuilders.integer("Rows returned"));
    schema.addProperty("rows", SpecBuilders.arrayOf(row, "The rows"));
    schema.setRequired(List.of("count", "rows"));
    return schema;
  }

  private Schema<?> vennResponse() {
    final Schema<Object> region = SpecBuilders.object("A non-empty Venn region");
    region.addProperty("mask", SpecBuilders.integer("Bit i set when the region lies in selection i"));
    region.addProperty("selections", SpecBuilders.arrayOf(SpecBuilders.integer(null), "Indexes of the region's selections"));
    region.addProperty("count", SpecBuilders.integer("Elements in exactly those selections"));
    region.setRequired(List.of("mask", "selections", "count"));

    final Schema<Object> schema = SpecBuilders.object("Venn regions, most selections first");
    schema.addProperty("regions", SpecBuilders.arrayOf(region, "The non-empty regions"));
    schema.setRequired(List.of("regions"));
    return schema;
  }

  private Schema<?> membersResponse() {
    final Schema<Object> member = SpecBuilders.object("A live member");
    addRecordFields(member);
    member.setRequired(List.of("rid"));

    final Schema<Object> schema = SpecBuilders.object("A page of live members");
    schema.addProperty("count", SpecBuilders.integer("Members returned"));
    schema.addProperty("members", SpecBuilders.arrayOf(member, "The members, in record-id order"));
    schema.setRequired(List.of("count", "members"));
    return schema;
  }

  private Schema<?> dropResponse() {
    final Schema<Object> schema = SpecBuilders.object("The outcome of a drop");
    schema.addProperty("name", name());
    schema.addProperty("dropped", SpecBuilders.bool("False when there was no selection of that name"));
    schema.setRequired(List.of("name", "dropped"));
    return schema;
  }

  private Schema<?> purgeResponse() {
    final Schema<Object> schema = SpecBuilders.object("The outcome of a purge");
    schema.addProperty("purged", SpecBuilders.integer("Expired selections deleted"));
    schema.setRequired(List.of("purged"));
    return schema;
  }

  // ---- pieces ----

  private static void addHandleFields(final Schema<Object> schema) {
    schema.addProperty("name", name());
    schema.addProperty("cardinality", SpecBuilders.integer("Members when saved"));
    schema.addProperty("createdAt", SpecBuilders.integer("Epoch milliseconds"));
    schema.addProperty("expiresAt", SpecBuilders.integer("Epoch milliseconds; null when it never expires"));
  }

  private static void addWriteOptions(final Schema<Object> schema) {
    schema.addProperty("ttl", bounded("Seconds until it expires; 0 never expires", 0, Integer.MAX_VALUE,
        (int) SelectionStore.DEFAULT_TTL_SECONDS));
    schema.addProperty("replace", SpecBuilders.bool("Overwrite a live selection of the same name, default false"));
  }

  private static void addRecordFields(final Schema<Object> schema) {
    schema.addProperty("rid", SpecBuilders.string("Record id"));
    schema.addProperty("properties", SpecBuilders.freeFormObject("The record's properties, with its '@rid' and '@type'"));
  }

  private static Schema<?> name() {
    final Schema<String> schema = SpecBuilders.string("Name of the saved selection");
    schema.setMinLength(1);
    schema.setMaxLength(SelectionStore.MAX_NAME_LENGTH);
    return schema;
  }

  private static Schema<?> rids(final String description) {
    return SpecBuilders.arrayOf(SpecBuilders.string(null), description);
  }

  private static Schema<?> selections(final String description) {
    final Schema<?> schema = SpecBuilders.arrayOf(SpecBuilders.anyValue("A saved selection's name, or a list of record ids"),
        description);
    schema.setMinItems(2);
    schema.setMaxItems(8);
    return schema;
  }

  private static Schema<?> bounded(final String description, final int minimum, final int maximum, final Integer defaultValue) {
    final Schema<Number> schema = SpecBuilders.integer(description);
    schema.setMinimum(BigDecimal.valueOf(minimum));
    schema.setMaximum(BigDecimal.valueOf(maximum));
    if (defaultValue != null)
      schema.setDefault(defaultValue);
    return schema;
  }
}
