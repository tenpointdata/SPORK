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

import com.arcadedb.server.http.handler.openapi.SpecBuilders;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The OpenAPI 3.0 contract of the plugin, built from {@link SporkRoutes} so the document and the dispatcher cannot drift. WS4
 * generates Recon's TypeScript client from it; it is served at {@code GET /api/spork/v1/openapi.json} and committed as
 * {@code docs/spork/api/spork-recon-openapi.json}, which a test keeps equal to this output.
 */
public final class SporkReconApiSpec {
  static final         String  ERROR   = "SporkError";
  private static final String  TAG     = "Recon";
  private static final String  VERSION = "1.0.0-slice1";
  private static volatile String json;

  private SporkReconApiSpec() {
  }

  /** The document as pretty-printed JSON, generated once. */
  public static String toJson() {
    String result = json;
    if (result == null) {
      try {
        result = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(generate()) + "\n";
      } catch (final JsonProcessingException e) {
        throw new IllegalStateException("The SPORK Recon OpenAPI document cannot be serialized", e);
      }
      json = result;
    }
    return result;
  }

  public static OpenAPI generate() {
    final OpenAPI openAPI = new OpenAPI();
    openAPI.setOpenapi("3.0.3");
    openAPI.setInfo(new Info().title("SPORK Recon API").version(VERSION).license(new License().name("Apache-2.0"))
        .description("""
            Recon's API on SPORK, served by the spork-recon ServerPlugin (docs/spork/01-architecture.md 7). Every route is \
            under /api/spork/v1/{tenant}/ and is called as the signed-in person: Authorization: Bearer <their Entry \
            credential>. The X-Spork-Context header carries the Operating Context (caseRef, purpose), never identity. \
            A route refuses with 428 contextRequired or contextInvalid when the context is absent or unreadable, 403 \
            purposeDenied when the declared purpose may not call it, and 503 auditUnavailable when the audit chain cannot \
            record the release; every refusal body is a SporkError with a stable code."""));
    openAPI.setComponents(components());
    openAPI.setSecurity(List.of(new SecurityRequirement().addList("entryBearer"), new SecurityRequirement().addList("basicAuth")));

    final Paths paths = new Paths();
    for (final SporkRoute route : SporkRoutes.all()) {
      final String path = SporkPath.PREFIX + "/{tenant}/" + route.path();
      PathItem item = paths.get(path);
      if (item == null) {
        item = new PathItem();
        paths.addPathItem(path, item);
      }
      item.operation(PathItem.HttpMethod.valueOf(route.method()), operation(route));
    }
    openAPI.setPaths(paths);
    return openAPI;
  }

  private static Operation operation(final SporkRoute route) {
    final List<String> purposes = new ArrayList<>();
    for (final Purpose purpose : route.purposes())
      purposes.add(purpose.wireName());
    final Operation operation = SpecBuilders.operation(route.operationId(), TAG, route.summary(), describe(route) + "\n\n"
        + "Purposes allowed: " + String.join(", ", purposes) + ". Audit: one '" + route.action()
        + "' entry on the chain, committed before the response is released.");
    operation.addParametersItem(SpecBuilders.pathParam("tenant", "The tenant, one of the installation's configured tenants"));
    operation.addParametersItem(SpecBuilders.headerParam(OperatingContext.HEADER,
        "base64url of the JSON Operating Context {caseRef?, purpose} (component SporkContext). Never identity, role or markings.",
        true));
    if (route.requestSchema() != null)
      operation.setRequestBody(SpecBuilders.jsonBody("The request", route.requestSchema(), true));

    final ApiResponses responses = new ApiResponses();
    responses.addApiResponse("200", SpecBuilders.jsonResponse("Released, and recorded on the audit chain", route.responseSchema()));
    responses.addApiResponse("400", error("The body is invalid: idsRequired, tooManyIds, projectionInvalid, searchInvalid, "
        + "filterUnsupported"));
    responses.addApiResponse("401", error("No credential, or one Entry rejects"));
    responses.addApiResponse("403", error("purposeDenied (the declared purpose may not call this route), analystMismatch (the "
        + "context names another account), or the person holds no grant on a database the request reads"));
    responses.addApiResponse("404", error("notFound or unknownTenant"));
    responses.addApiResponse("405", error("The path exists with another method; Allow lists it"));
    responses.addApiResponse("428", error("contextRequired (no " + OperatingContext.HEADER + ") or contextInvalid (unreadable)"));
    responses.addApiResponse("503", error("auditUnavailable: the release could not be recorded, so nothing was released"));
    operation.setResponses(responses);
    return operation;
  }

  private static String describe(final SporkRoute route) {
    if (route == SporkRoutes.SEARCH)
      return """
          Recon's structured search over every partition: the filters are ANDed, the page is up to 500 records (50 by \
          default) in posted_desc, posted_asc or views_desc order. Served on direct cursors over the (facet, tsPosted, views) \
          composites, the smallest filter driving; partial is true when a read cap stopped the page itself. \
          The total is an ESTIMATE by default, so the request stays inside its latency budget: a single filter with no posted \
          window, over records the person can all see, takes the total from index statistics (total is -1, nothing counted); \
          otherwise the count reads a bounded number of index entries and totalExact says whether it reached the end. When \
          totalExact is false, total is a lower bound ("N+") or -1, and totalEstimate, when present, the statistics' total or \
          the count scaled from the driving filter's size ("about N"); it is left out when a posted window makes the scaling \
          unreliable. exactTotal: true asks \
          for the exact count up to 10,000 (totalCapped when more match), at the cost of reading up to every match. \
          Records the person may not see are neither returned nor counted. phone, email, business and q (substring and full \
          text) are refused with filterUnsupported for now. A title in the projection is read from the record's text \
          partition through its textHash, and needs a grant on it.""";
    if (route == SporkRoutes.HYDRATE)
      return """
          Hydrates up to 5,000 records. ids are upstream RIDs ("168:8"), SPORK record_ids (unsigned decimal strings) or \
          imp:<importedId>; duplicates are answered once. Records come back in request order with the projection asked for \
          (every field but styleVec by default). An id that matches nothing, a record whose markings the person does not \
          hold and another tenant's import are all reported in missing, so a hidden record is indistinguishable from an \
          absent one. partial is true when an id belongs to a partition this node does not hold. Recon pages larger sets.""";
    return """
        Declares the Operating Context of the session and records it on the audit chain (context.declare). Echoes the \
        person the server authenticated, which is the analyst of every entry the context produces.""";
  }

  private static ApiResponse error(final String description) {
    return SpecBuilders.jsonResponse(description, ERROR);
  }

  private static Components components() {
    final Components components = new Components();
    components.addSecuritySchemes("entryBearer", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
        .description("The signed-in person's own Entry credential (session, PAT or MCP)"));
    components.addSecuritySchemes("basicAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")
        .description("Local break-glass users only; audited as local:<name>"));

    final List<String> purposes = new ArrayList<>();
    for (final Purpose purpose : Purpose.values())
      purposes.add(purpose.wireName());

    components.addSchemas(ERROR, SpecBuilders.object("A refusal")//
        .addProperty("error", SpecBuilders.string("For people"))//
        .addProperty("code", SpecBuilders.string("Stable, for clients: contextRequired, contextInvalid, purposeDenied, "
            + "analystMismatch, unknownTenant, notFound, idsRequired, tooManyIds, projectionInvalid, searchInvalid, filterUnsupported, "
            + "auditUnavailable"))//
        .required(List.of("error")));

    components.addSchemas("SporkContext", SpecBuilders.object("The decoded " + OperatingContext.HEADER + " header")//
        .addProperty("caseRef", SpecBuilders.string("The case the work is for, 1 to 200 printable characters").maxLength(200))//
        .addProperty("purpose", purposeSchema(purposes))//
        .addProperty("analyst", SpecBuilders.string("Optional; when present it must be the authenticated Entry account id"))//
        .required(List.of("purpose")));

    components.addSchemas("ContextAnswer", envelope(SpecBuilders.object("The declared context, as the server recorded it")//
        .addProperty("tenant", SpecBuilders.string("The tenant"))//
        .addProperty("analyst", SpecBuilders.string("The authenticated person: the Entry account id, or local:<name>"))//
        .addProperty("caseRef", SpecBuilders.string("The declared case").nullable(true))//
        .addProperty("purpose", purposeSchema(purposes))).required(List.of("tenant", "analyst", "purpose", "requestId", "auditSeq",
        "servedBy", "partial", "consistencyBookmark")));

    final List<String> fields = new ArrayList<>();
    for (final RecordProjection field : RecordProjection.values())
      fields.add(field.apiName());
    final Schema<?> ids = SpecBuilders.arrayOf(SpecBuilders.string("An upstream RID, a record_id or imp:<importedId>"),
        "The records to hydrate").maxItems(HydrateService.MAX_IDS);
    final Schema<?> projection = SpecBuilders.arrayOf(new Schema<String>().type("string")._enum(fields),
        "The fields to return; every field but styleVec when absent");
    components.addSchemas("HydrateRequest", SpecBuilders.object("POST records:hydrate")//
        .addProperty("ids", ids)//
        .addProperty("projection", projection)//
        .required(List.of("ids")));

    final Schema<Object> record = SpecBuilders.object("One record; a field the record lacks is absent")//
        .addProperty("id", SpecBuilders.string("The id as requested"))//
        .addProperty("recordId", SpecBuilders.string("The SPORK record_id, unsigned decimal"))//
        .addProperty("upstreamRid", SpecBuilders.string("The upstream RID cluster:position; absent for imports"));
    for (final RecordProjection field : RecordProjection.values())
      record.addProperty(field.apiName(), fieldSchema(field));
    record.required(List.of("id", "recordId"));
    components.addSchemas("HydratedRecord", record);

    final List<String> searchFields = new ArrayList<>(fields);
    searchFields.add(SearchService.TITLE);
    final Schema<?> searchProjection = SpecBuilders.arrayOf(new Schema<String>().type("string")._enum(searchFields),
        "The fields to return; every field but styleVec and title when absent. title is read from the text partition and needs "
            + "a grant on it (403 otherwise)");
    components.addSchemas("SearchRequest", SpecBuilders.object("POST search: every filter is optional and they are ANDed")//
        .addProperty("state", SpecBuilders.string("Two-letter state code, matched on stateCode"))//
        .addProperty("country", SpecBuilders.string("Country code, matched on countryCode"))//
        .addProperty("city", SpecBuilders.string("City, matched on its normalized form (cityNorm)"))//
        .addProperty("areaCode", SpecBuilders.string("One of the record's area codes"))//
        .addProperty("service", SpecBuilders.string("One of the record's services"))//
        .addProperty("hasImages", SpecBuilders.bool("Only records with (or without) images"))//
        .addProperty("website", SpecBuilders.string("The source website"))//
        .addProperty("aggregator", SpecBuilders.string("The aggregator"))//
        .addProperty("ageMin", SpecBuilders.integer("Inclusive lower bound of age"))//
        .addProperty("ageMax", SpecBuilders.integer("Inclusive upper bound of age"))//
        .addProperty("postedFrom", SpecBuilders.string("Inclusive start of the posted window, ISO-8601 (epoch ms accepted)"))//
        .addProperty("postedTo", SpecBuilders.string("Inclusive end of the posted window, ISO-8601 (epoch ms accepted)"))//
        .addProperty("sort", new Schema<String>().type("string")._enum(List.of("posted_desc", "posted_asc", "views_desc"))
            .description("posted_desc by default"))//
        .addProperty("limit", SpecBuilders.integer("Rows on the page, 1 to 500, 50 by default").minimum(BigDecimal.ONE)
            .maximum(BigDecimal.valueOf(SearchService.MAX_LIMIT)))//
        .addProperty("projection", searchProjection)//
        .addProperty("exactTotal", SpecBuilders.bool("Count every match up to 10,000 instead of estimating the total; false by "
            + "default")));

    final Schema<Object> searched = SpecBuilders.object("One record of the page; a field the record lacks is absent")//
        .addProperty("recordId", SpecBuilders.string("The SPORK record_id, unsigned decimal"))//
        .addProperty("upstreamRid", SpecBuilders.string("The upstream RID cluster:position; absent for imports"));
    for (final RecordProjection field : RecordProjection.values())
      searched.addProperty(field.apiName(), fieldSchema(field));
    searched.addProperty(SearchService.TITLE, SpecBuilders.string("The ad title, when asked for and the text is on this node"));
    searched.required(List.of("recordId"));
    components.addSchemas("SearchedRecord", searched);

    components.addSchemas("SearchAnswer", envelope(SpecBuilders.object("A page and its total")//
        .addProperty("records", SpecBuilders.arrayOf(SpecBuilders.ref("SearchedRecord"), "In the requested order"))//
        .addProperty("total", SpecBuilders.integer("Matches counted, at most 10,000. Exact only when totalExact; otherwise an "
            + "ESTIMATE's lower bound (\"N+\"), or -1 when the total was read from index statistics and nothing was counted: "
            + "show totalEstimate (\"about N\")").format("int64"))//
        .addProperty("totalExact", SpecBuilders.bool("total counts every match (up to the cap). False by default once the count's "
            + "read budget runs out: total is then a lower bound"))//
        .addProperty("totalCapped", SpecBuilders.bool("More than 10,000 match: counted, or by the estimate when totalExact is "
            + "false (\"10,000+\")"))//
        .addProperty("totalEstimate", SpecBuilders.integer("ESTIMATE, only when totalExact is false and no posted window applies: "
            + "from index statistics (when total is -1) or the count scaled from the driving filter's size (\"about N\"), at most "
            + "10,000").format("int64")))//
        .required(List.of("records", "total", "totalExact", "totalCapped", "requestId", "auditSeq", "servedBy", "partial",
            "consistencyBookmark")));

    components.addSchemas("HydrateAnswer", envelope(SpecBuilders.object("The released records")//
        .addProperty("records", SpecBuilders.arrayOf(SpecBuilders.ref("HydratedRecord"), "In request order"))//
        .addProperty("missing", SpecBuilders.arrayOf(SpecBuilders.string(null), "Ids that matched nothing the person may see"))//
        .addProperty("invalid", SpecBuilders.arrayOf(SpecBuilders.string(null), "Ids in none of the three forms")))//
        .required(List.of("records", "missing", "invalid", "requestId", "auditSeq", "servedBy", "partial", "consistencyBookmark")));
    return components;
  }

  /** The members every answer carries (01-architecture 7.1). */
  private static Schema<Object> envelope(final Schema<Object> schema) {
    return schema//
        .addProperty("requestId", SpecBuilders.string("This request; also in the audit entry's detail"))//
        .addProperty("auditSeq", SpecBuilders.integer("The seq of the audit entry that recorded the release").format("int64"))//
        .addProperty("servedBy", SpecBuilders.string("The server that answered"))//
        .addProperty("partial", SpecBuilders.bool("True when part of the answer lives on databases this node does not hold"))//
        .addProperty("consistencyBookmark",
            SpecBuilders.string("The commit index to pass as X-ArcadeDB-Read-After for read-your-writes; null outside HA").nullable(true));
  }

  private static Schema<?> purposeSchema(final List<String> purposes) {
    return new Schema<String>().type("string")._enum(purposes).description("The declared purpose");
  }

  private static Schema<?> fieldSchema(final RecordProjection field) {
    return switch (field) {
      case PHONES, EMAILS, SOCIALS, BUSINESS_NAMES, IMAGE_HASHES, SERVICES, PAYMENT_TYPES, CLIENTELE ->
          SpecBuilders.arrayOf(SpecBuilders.string(null), field.property());
      case LAT, LON, PRIORITY_SCORE -> SpecBuilders.number(field.property());
      case AGE, VIEW_COUNT -> SpecBuilders.integer(field.property());
      case HAS_IMAGES -> SpecBuilders.bool(field.property());
      case POSTED_AT, COLLECTED_AT -> SpecBuilders.string(field.property() + ", ISO-8601 UTC with milliseconds").format("date-time");
      case STYLE_VEC -> SpecBuilders.arrayOf(SpecBuilders.number(null), "24 float32 values");
      case TEXT_HASH -> SpecBuilders.string("SHA-256 (32 bytes) of the 4-byte title length, title and body, lowercase hex");
      case MARKINGS -> SpecBuilders.arrayOf(SpecBuilders.integer(null), "The marking bits the record carries");
      case CITY, STATE, COUNTRY, SOURCE, STYLE -> SpecBuilders.string(field.property());
    };
  }
}
