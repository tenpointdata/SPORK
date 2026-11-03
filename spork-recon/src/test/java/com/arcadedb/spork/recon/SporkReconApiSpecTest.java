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

import com.arcadedb.server.http.PluginRoutes;
import com.arcadedb.server.http.RecordingPathHandler;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI contract WS4 generates Recon's TypeScript client from. It is built from the same route table the dispatcher
 * serves, so the two cannot drift; the copy committed under {@code docs/spork/api} must equal what the code generates.
 */
class SporkReconApiSpecTest {
  /** Regenerate the committed copy with {@code -Dspork.openapi.write=true}. */
  private static final Path COMMITTED = Path.of("..", "docs", "spork", "api", "spork-recon-openapi.json");

  private static SwaggerParseResult parse(final String json) {
    final ParseOptions options = new ParseOptions();
    options.setResolve(true);
    return new OpenAPIV3Parser().readContents(json, null, options);
  }

  @Test
  void isAValidOpenApiDocument() {
    final SwaggerParseResult result = parse(SporkReconApiSpec.toJson());

    assertThat(result.getMessages()).isEmpty();
    assertThat(result.getOpenAPI().getInfo().getTitle()).isEqualTo("SPORK Recon API");
  }

  @Test
  void documentsEveryRouteTheDispatcherServes() {
    final OpenAPI openAPI = parse(SporkReconApiSpec.toJson()).getOpenAPI();

    for (final SporkRoute route : SporkRoutes.all()) {
      final PathItem item = openAPI.getPaths().get(SporkPath.PREFIX + "/{tenant}/" + route.path());
      assertThat(item).as(route.path()).isNotNull();
      final Operation operation = item.readOperationsMap().get(PathItem.HttpMethod.valueOf(route.method()));
      assertThat(operation).as(route.method() + " " + route.path()).isNotNull();
      assertThat(operation.getOperationId()).isEqualTo(route.operationId());
      // the Operating Context refusals Recon's ApiError handles (01-architecture 5.1)
      assertThat(operation.getResponses()).containsKeys("200", "400", "401", "403", "404", "428", "503");
      assertThat(operation.getParameters()).extracting(Parameter::getName).contains("tenant", OperatingContext.HEADER);
      assertThat(operation.getDescription()).as("the purpose gate is part of the contract")
          .contains(route.action());
      for (final Purpose purpose : route.purposes())
        assertThat(operation.getDescription()).contains(purpose.wireName());
    }
    assertThat(openAPI.getPaths()).hasSize((int) SporkRoutes.all().stream().map(SporkRoute::path).distinct().count());
  }

  @Test
  void theHydrateCapIsInTheContract() {
    final OpenAPI openAPI = parse(SporkReconApiSpec.toJson()).getOpenAPI();
    final Schema<?> ids = (Schema<?>) openAPI.getComponents().getSchemas().get("HydrateRequest").getProperties().get("ids");

    assertThat(ids.getMaxItems()).isEqualTo(HydrateService.MAX_IDS);
    assertThat(HydrateService.MAX_IDS).isEqualTo(5_000);
    final Schema<?> projection = (Schema<?>) openAPI.getComponents().getSchemas().get("HydrateRequest").getProperties()
        .get("projection");
    assertThat(projection.getItems().getEnum()).hasSize(RecordProjection.values().length);
  }

  /** Issue #7578's rule for the server's own document: a schema with no required list types every field optional in a client. */
  @Test
  void everyComponentSchemaSaysWhatItAlwaysSends() {
    final OpenAPI openAPI = parse(SporkReconApiSpec.toJson()).getOpenAPI();
    for (final Map.Entry<String, Schema> entry : openAPI.getComponents().getSchemas().entrySet()) {
      if (entry.getKey().equals("SearchRequest"))
        continue; // an empty body is a valid search: the newest 50 of everything
      assertThat(entry.getValue().getRequired()).as(entry.getKey()).isNotEmpty();
    }
  }

  @Test
  void theSearchLimitIsInTheContract() {
    final OpenAPI openAPI = parse(SporkReconApiSpec.toJson()).getOpenAPI();
    final Schema<?> limit = (Schema<?>) openAPI.getComponents().getSchemas().get("SearchRequest").getProperties().get("limit");

    assertThat(limit.getMaximum().intValue()).isEqualTo(SearchService.MAX_LIMIT).isEqualTo(500);
  }

  @Test
  void theCommittedCopyIsCurrent() throws IOException {
    final String generated = SporkReconApiSpec.toJson();
    if (Boolean.getBoolean("spork.openapi.write")) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
    }
    assertThat(COMMITTED).as("regenerate with mvn -pl spork-recon test -Dtest=SporkReconApiSpecTest -Dspork.openapi.write=true")
        .exists();
    assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8)).isEqualTo(generated);
  }

  @Test
  void thePluginRegistersOnlyItsNamespaceAndOnlyTheMethodsItServes() {
    final SporkReconPlugin plugin = new SporkReconPlugin(SporkReconConfiguration.parse(SporkReconConfigurationTest.minimal()));
    final RecordingPathHandler paths = new RecordingPathHandler();
    try {
      plugin.registerAPI(null, new PluginRoutes(paths));

      assertThat(paths.getRegisteredOperations()).containsExactlyInAnyOrder("GET " + SporkPath.PREFIX, "POST " + SporkPath.PREFIX);
    } finally {
      plugin.stopService();
    }
  }
}
