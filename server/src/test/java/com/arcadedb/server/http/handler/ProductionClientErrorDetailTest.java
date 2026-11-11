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
package com.arcadedb.server.http.handler;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.exception.CommandSQLParsingException;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.exception.IndexDefinitionConflictException;
import com.arcadedb.exception.InvalidPropertyTypeException;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production mode concealed the {@code detail} of every error, so a statement that could never succeed as written answered only
 * "Cannot execute command" and the caller had to read the server source to learn why (an index the schema cannot add, a parse
 * error, a type error). The message of a client error is now kept in {@code detail}; the cause chain of anything else, and the
 * stored values a duplicated key names, stay concealed.
 */
class ProductionClientErrorDetailTest extends BaseGraphServerTest {

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    config.setValue(GlobalConfiguration.SERVER_MODE, "production");
  }

  @Test
  void aSecondIndexOfAnotherKindSaysWhy() throws Exception {
    command("sql", "CREATE VERTEX TYPE Address");
    command("sql", "CREATE PROPERTY Address.value STRING");
    assertThat(command("sql", "CREATE INDEX ON Address (value) UNIQUE").statusCode()).isEqualTo(200);

    final HttpResponse<String> unnamed = command("sql", "CREATE INDEX ON Address (value) FULL_TEXT");
    assertThat(unnamed.statusCode()).isEqualTo(409);
    final JSONObject unnamedBody = new JSONObject(unnamed.body());
    assertThat(unnamedBody.getString("exception")).isEqualTo(AlreadyExistsException.class.getName());
    assertThat(unnamedBody.getString("detail")).contains("one index per property set");

    final HttpResponse<String> named = command("sql", "CREATE INDEX `Address_value_fulltext` ON Address (value) FULL_TEXT");
    assertThat(named.statusCode()).isEqualTo(400);
    final JSONObject namedBody = new JSONObject(named.body());
    assertThat(namedBody.getString("exception")).isEqualTo(IndexDefinitionConflictException.class.getName());
    assertThat(namedBody.getString("detail")).contains("Address[value]").contains("one index per property set");
  }

  @Test
  void aParseErrorSaysWhere() throws Exception {
    final HttpResponse<String> response = command("sql", "SELEC FROM nothing");
    assertThat(response.statusCode()).isEqualTo(400);
    final JSONObject body = new JSONObject(response.body());
    assertThat(body.getString("exception")).isEqualTo(CommandSQLParsingException.class.getName());
    assertThat(body.has("detail")).isTrue();
  }

  @Test
  void aTypeErrorOfAnIncrementSaysWhichProperty() throws Exception {
    command("sql", "CREATE DOCUMENT TYPE Counter");
    assertThat(command("sql", "INSERT INTO Counter SET id = 1, s = 'text', n = 1").statusCode()).isEqualTo(200);

    final HttpResponse<String> response = command("sqlscript", "BEGIN; UPDATE Counter INCREMENT s = 1 WHERE id = 1; COMMIT;");
    assertThat(response.statusCode()).isEqualTo(400);
    final JSONObject body = new JSONObject(response.body());
    assertThat(body.getString("exception")).isEqualTo(InvalidPropertyTypeException.class.getName());
    assertThat(body.getString("detail")).contains("INCREMENT").contains("'s'");

    // and the statement the report found failing every time now runs
    assertThat(command("sql", "UPDATE Counter INCREMENT n = 1 WHERE id = 1").statusCode()).isEqualTo(200);
  }

  @Test
  void aDuplicatedKeyStillConcealsTheStoredValue() throws Exception {
    command("sql", "CREATE DOCUMENT TYPE Account");
    command("sql", "CREATE PROPERTY Account.email STRING");
    command("sql", "CREATE INDEX ON Account (email) UNIQUE");
    assertThat(command("sql", "INSERT INTO Account SET email = 'secret-address@example.com'").statusCode()).isEqualTo(200);

    final HttpResponse<String> response = command("sql", "INSERT INTO Account SET email = 'secret-address@example.com'");
    assertThat(response.statusCode()).isEqualTo(409);
    final JSONObject body = new JSONObject(response.body());
    assertThat(body.getString("exception")).isEqualTo(DuplicatedKeyException.class.getName());
    assertThat(body.has("detail")).isFalse();
    assertThat(response.body()).doesNotContain("secret-address");
  }

  @Test
  void anErrorThatIsNotTheCallersStaysConcealed() throws Exception {
    final HttpResponse<String> response = command("sql", "SELECT FROM NoSuchTypeAnywhere");
    assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
    final JSONObject body = new JSONObject(response.body());
    assertThat(body.has("detail")).isFalse();
  }

  private HttpResponse<String> command(final String language, final String command) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(getServerHttpUrl(0, "/api/v1/command/" + getDatabaseName())))
        .POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("language", language).put("command", command).toString()))
        .header("Authorization",
            "Basic " + Base64.getEncoder().encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes(StandardCharsets.UTF_8)))
        .header("Content-Type", "application/json").build();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
  }
}
