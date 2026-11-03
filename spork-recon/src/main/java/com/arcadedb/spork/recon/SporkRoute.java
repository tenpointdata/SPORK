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

import java.util.EnumSet;
import java.util.Set;

/**
 * One row of the declarative route table: where the route answers, what it releases, which purposes may call it and what it
 * writes to the audit chain. The dispatcher, the purpose gate and the OpenAPI document all read these rows and nothing else.
 *
 * @param method          the one HTTP method the route answers
 * @param path            the path after {@code /api/spork/v1/{tenant}/}
 * @param operationId     the OpenAPI operation id, which names the generated client method
 * @param action          the audit action of the entry the route appends
 * @param dataClass       what the response carries
 * @param purposes        the purposes allowed to call it, a subset of the data class's
 * @param summary         one line for the contract
 * @param requestSchema   the OpenAPI component of the request body, or null
 * @param responseSchema  the OpenAPI component of the 200 body
 */
public record SporkRoute(String method, String path, String operationId, String action, DataClass dataClass, Set<Purpose> purposes,
                         String summary, String requestSchema, String responseSchema) {
  public SporkRoute {
    purposes = EnumSet.copyOf(purposes);
    if (!dataClass.purposes().containsAll(purposes))
      throw new IllegalArgumentException("Route " + path + " allows a purpose its data class " + dataClass + " refuses");
  }

  @Override
  public Set<Purpose> purposes() {
    return EnumSet.copyOf(purposes);
  }

  public boolean allows(final Purpose purpose) {
    return purposes.contains(purpose);
  }
}
