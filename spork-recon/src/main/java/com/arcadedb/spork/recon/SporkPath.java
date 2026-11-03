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

import java.util.regex.Pattern;

/**
 * A request path inside the plugin's namespace, {@code /api/spork/v1/{tenant}/{route}}: exactly the shape Recon's gateway forwards
 * ({@code v1/{tenant}/} plus plain segments; no dots, no percent signs, no empty segments). Anything else is 404 before
 * authentication matters.
 *
 * @param tenant the tenant segment
 * @param route  the rest of the path, one or more segments joined by '/'
 */
public record SporkPath(String tenant, String route) {
  public static final  String  PREFIX       = "/api/spork/v1";
  /** The contract, served without a tenant: {@code GET /api/spork/v1/openapi.json}. */
  public static final  String  OPENAPI_PATH = PREFIX + "/openapi.json";
  static final         int     MAX_SEGMENTS = 6;
  private static final Pattern TENANT       = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
  private static final Pattern SEGMENT      = Pattern.compile("[A-Za-z0-9_:-]{1,64}");

  /** @throws SporkRefusal 404 for a path outside the grammar */
  public static SporkPath parse(final String requestPath) {
    if (requestPath == null || !requestPath.startsWith(PREFIX + "/"))
      throw notFound();
    final String[] segments = requestPath.substring(PREFIX.length() + 1).split("/", -1);
    if (segments.length < 2 || segments.length > MAX_SEGMENTS || !TENANT.matcher(segments[0]).matches())
      throw notFound();
    for (int i = 1; i < segments.length; i++)
      if (!SEGMENT.matcher(segments[i]).matches())
        throw notFound();
    return new SporkPath(segments[0], requestPath.substring(PREFIX.length() + 2 + segments[0].length()));
  }

  static SporkRefusal notFound() {
    return SporkRefusal.notFound("notFound", "No such route");
  }
}
