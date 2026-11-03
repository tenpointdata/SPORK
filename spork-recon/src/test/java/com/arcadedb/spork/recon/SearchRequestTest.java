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

import com.arcadedb.query.structured.Facet;
import com.arcadedb.query.structured.StructuredQuery;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.spork.keys.SporkKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Recon's search body to a structured-search core query in schema v1 terms; every refusal is a 400 before anything is read. */
class SearchRequestTest {

  private static Facet facet(final StructuredQuery query, final String property) {
    return query.facets().stream().filter(f -> f.property().equals(property)).findFirst().orElseThrow();
  }

  @Test
  void mapsReconsFiltersOntoTheV1Properties() {
    final SearchService.Request request = SearchService.parse(new JSONObject().put("state", " tx").put("city", "San Antonio")
        .put("service", "gfe").put("areaCode", "210").put("hasImages", true).put("ageMin", 21).put("ageMax", 25)
        .put("postedFrom", "2026-11-01T00:00:00.000Z").put("postedTo", 1_793_707_200_000L).put("sort", "views_desc").put("limit", 120)
        .put("projection", new JSONArray().put("phones").put("title")));
    final StructuredQuery query = request.query();

    assertThat(facet(query, "stateCode").values()).containsExactly("TX");
    assertThat(facet(query, "cityNorm").values()).containsExactly(SporkKeys.cityNorm("San Antonio"));
    assertThat(facet(query, "services").values()).containsExactly("gfe");
    assertThat(facet(query, "areaCodes").values()).containsExactly("210");
    assertThat(facet(query, "hasImages").values()).containsExactly(true);
    assertThat(facet(query, "age").values()).containsExactly(21, 22, 23, 24, 25);
    assertThat(query.windowFrom()).isEqualTo(1_793_491_200_000L);
    assertThat(query.windowTo()).isEqualTo(1_793_707_200_000L);
    assertThat(query.rankProperty()).isEqualTo("views");
    assertThat(query.limit()).isEqualTo(120);
    assertThat(request.title()).isTrue();
    assertThat(request.fields()).containsExactly(RecordProjection.PHONES);
    assertThat(request.sort()).isEqualTo("views_desc");
    assertThat(request.filters()).containsExactlyInAnyOrder("state", "city", "service", "areaCode", "hasImages", "ageMin", "ageMax",
        "postedFrom", "postedTo");
  }

  @Test
  void anEmptyBodyIsTheNewestFiftyOfEverything() {
    final SearchService.Request request = SearchService.parse(new JSONObject());

    assertThat(request.query().facets()).isEmpty();
    assertThat(request.query().windowFrom()).isEqualTo(Long.MIN_VALUE);
    assertThat(request.query().limit()).isEqualTo(50);
    assertThat(request.query().descending()).isTrue();
    assertThat(request.query().rankProperty()).isNull();
    assertThat(request.title()).isFalse();
    assertThat(SearchService.parse(new JSONObject().put("sort", "posted_asc")).query().descending()).isFalse();
    assertThat(facet(SearchService.parse(new JSONObject().put("ageMin", 30)).query(), "age").values()).as("an open upper bound")
        .hasSize(200 - 30 + 1);
  }

  @Test
  void substringAndFullTextArmsAreRefusedNotIgnored() {
    for (final String key : new String[] { "phone", "email", "business", "q" })
      assertThatThrownBy(() -> SearchService.parse(new JSONObject().put(key, "555"))).isInstanceOfSatisfying(SporkRefusal.class,
          refusal -> {
            assertThat(refusal.status()).isEqualTo(400);
            assertThat(refusal.code()).isEqualTo("filterUnsupported");
          });
  }

  @Test
  void refusesWhatItCannotServe() {
    final JSONObject[] invalid = { new JSONObject().put("colour", "red"), new JSONObject().put("limit", 501),
        new JSONObject().put("limit", 0), new JSONObject().put("sort", "relevance"), new JSONObject().put("ageMin", 30).put("ageMax", 20),
        new JSONObject().put("ageMin", "thirty"), new JSONObject().put("ageMax", 900), new JSONObject().put("hasImages", "yes"),
        new JSONObject().put("state", ""), new JSONObject().put("postedFrom", "yesterday"),
        new JSONObject().put("postedFrom", 20L).put("postedTo", 10L), new JSONObject().put("state", 7) };
    for (final JSONObject body : invalid)
      assertThatThrownBy(() -> SearchService.parse(body)).as(body.toString()).isInstanceOfSatisfying(SporkRefusal.class, refusal -> {
        assertThat(refusal.status()).isEqualTo(400);
        assertThat(refusal.code()).isEqualTo("searchInvalid");
      });
    assertThatThrownBy(() -> SearchService.parse(new JSONObject().put("projection", List.of("nope")))).isInstanceOfSatisfying(
        SporkRefusal.class, refusal -> assertThat(refusal.code()).isEqualTo("projectionInvalid"));
  }
}
