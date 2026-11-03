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

import com.arcadedb.serializer.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The Operating Context of one request: what the analyst says they are doing, never who they are
 * (07-entry-identity-and-audit.md 2.5). Recon's gateway sends it as {@code X-Spork-Context: base64url(JSON {caseRef, purpose})},
 * unsigned, because the analyst could send the same declaration directly with the same Entry credential. The person is the
 * authenticated Entry account; an {@code analyst} field, when present, must name that account. {@code role} and {@code markings}
 * are refused rather than ignored: the person's Entry permissions decide both, and a client that sends them believes something
 * is enforced that is not.
 *
 * @param caseRef the case the work is for, or null
 * @param purpose the declared purpose, never null
 * @param analyst the account the client believes it is acting as, or null
 */
public record OperatingContext(String caseRef, Purpose purpose, String analyst) {
  public static final String HEADER            = "X-Spork-Context";
  /** Far above any real declaration (a case reference and a purpose), far below a header that could cost anything to parse. */
  public static final int    MAX_HEADER_LENGTH = 2_048;
  static final        int    MAX_FIELD_LENGTH  = 200;

  /**
   * @throws SporkRefusal 428 {@code contextRequired} when the header is absent, 428 {@code contextInvalid} when it cannot be read
   */
  public static OperatingContext parse(final String header) {
    if (header == null || header.isBlank())
      throw new SporkRefusal(428, "contextRequired", "This route needs an Operating Context in " + HEADER);
    if (header.length() > MAX_HEADER_LENGTH)
      throw invalid("the header is longer than " + MAX_HEADER_LENGTH + " characters");

    final JSONObject json;
    try {
      final String text = new String(Base64.getUrlDecoder().decode(header.trim()), StandardCharsets.UTF_8);
      if (text.isEmpty() || text.charAt(0) != '{')
        throw invalid("the header is not base64url of a JSON object");
      json = new JSONObject(text);
    } catch (final SporkRefusal e) {
      throw e;
    } catch (final RuntimeException e) {
      throw invalid("the header is not base64url of a JSON object");
    }

    for (final String key : json.keySet())
      if (!"caseRef".equals(key) && !"purpose".equals(key) && !"analyst".equals(key))
        throw invalid("'" + key + "' is not a context field: the person's Entry permissions decide identity, role and markings");

    final Object purposeValue = json.has("purpose") ? json.get("purpose") : null;
    final Purpose purpose = purposeValue instanceof String s ? Purpose.fromWire(s) : null;
    if (purpose == null)
      throw invalid("'purpose' must be one of victim_identification, investigative_lead, network_disruption, "
          + "standing_monitoring, training");

    return new OperatingContext(text(json, "caseRef"), purpose, text(json, "analyst"));
  }

  private static String text(final JSONObject json, final String key) {
    if (!json.has(key) || json.isNull(key))
      return null;
    if (!(json.get(key) instanceof String value))
      throw invalid("'" + key + "' must be a string");
    if (value.isEmpty() || value.length() > MAX_FIELD_LENGTH)
      throw invalid("'" + key + "' must be 1 to " + MAX_FIELD_LENGTH + " characters");
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c < 0x20 || c == 0x7f)
        throw invalid("'" + key + "' contains a control character");
    }
    return value;
  }

  private static SporkRefusal invalid(final String why) {
    return new SporkRefusal(428, "contextInvalid", "The Operating Context in " + HEADER + " is invalid: " + why);
  }
}
