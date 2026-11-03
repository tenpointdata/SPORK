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
package com.arcadedb.function.sql.geo;

/**
 * SQL function geo.dWithinMeters: true if geometry g is within the given distance of shape, in METRES of great-circle
 * distance on the sphere {@code geo.distance()} measures on (mean radius 6,371 km). The same predicate as
 * {@code geo.dWithin}, whose distance is in degrees of arc, a unit easy to get wrong by a factor of 111,000.
 *
 * <p>Usage: {@code geo.dWithinMeters(g, shape, distanceMeters)}</p>
 * <p>Returns: Boolean. Answered from a GEOSPATIAL index on {@code g} when there is one.</p>
 */
public class SQLFunctionGeoDWithinMeters extends SQLFunctionGeoDWithin {
  public static final String NAME = "geo.dWithinMeters";

  public SQLFunctionGeoDWithinMeters() {
    super(NAME);
  }

  @Override
  protected double toDegrees(final double distance) {
    return GeoUtils.metresToDegrees(distance);
  }

  @Override
  public String getSyntax() {
    return "geo.dWithinMeters(<geometry>, <shape>, <distanceMeters>)";
  }
}
