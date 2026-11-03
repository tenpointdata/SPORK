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

import java.util.ArrayList;
import java.util.List;

/**
 * The purpose gate as data (01-architecture 5.1): every route the plugin serves, one row each. Changing who may call what is an
 * edit of this table, and the OpenAPI document Recon's client is generated from changes with it.
 * <p>
 * The gates are the architecture's proposal, pending owner question 10. Routes of later slices (intake, search, pivot, partial,
 * group-by, timeline, feed, board, deconfliction) join here with their own rows.
 */
public final class SporkRoutes {
  public static final SporkRoute CONTEXT = new SporkRoute("POST", "context", "declareContext", "context.declare", DataClass.NONE,
      DataClass.NONE.purposes(), "Declare an Operating Context", null, "ContextAnswer");

  public static final SporkRoute HYDRATE = new SporkRoute("POST", "records:hydrate", "hydrateRecords", "records.hydrate",
      DataClass.RECORD_PROJECTION, DataClass.RECORD_PROJECTION.purposes(), "Hydrate up to 5,000 records by id", "HydrateRequest",
      "HydrateAnswer");

  private static final List<SporkRoute> ALL = List.of(CONTEXT, HYDRATE);

  private SporkRoutes() {
  }

  public static List<SporkRoute> all() {
    return ALL;
  }

  /** The route at {@code path} answering {@code method}, or null. */
  public static SporkRoute find(final String method, final String path) {
    for (final SporkRoute route : ALL)
      if (route.path().equals(path) && route.method().equals(method))
        return route;
    return null;
  }

  /** The methods declared at {@code path}, empty for a path no route has: the 405 versus 404 decision. */
  public static List<String> allowedMethods(final String path) {
    final List<String> methods = new ArrayList<>(2);
    for (final SporkRoute route : ALL)
      if (route.path().equals(path))
        methods.add(route.method());
    return methods;
  }
}
