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

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.query.structured.Facet;
import com.arcadedb.query.structured.StructuredQuery;
import com.arcadedb.query.structured.StructuredSearch;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.security.ServerSecurityUser;
import com.arcadedb.spork.keys.RecordId;
import com.arcadedb.spork.keys.SporkKeys;
import com.arcadedb.spork.keys.UpstreamRid;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code POST /search} (01-architecture 7.2; P4, P4b, P4v of 02-retrieval-speed.md 2.4): Recon's structured filters on the
 * engine's structured-search core ({@link StructuredSearch}), one walk per partition this node holds, with the person bound as
 * engine user and their visibility ({@link RecordVisibility}) as the core's record filter, so hidden records are neither rows nor
 * counted. This class adds only what is Recon's: the request, the partitions, the merge across them, and titles.
 * <p>
 * Partitions are walked newest period first ({@code posted_desc}, {@code views_desc}) or oldest first ({@code posted_asc}).
 * Each answers its own top {@code limit}, the pages merge in the sort order, and the counts add up to the cap, each partition
 * counting only what the cap has left.
 */
final class SearchService {
  static final int         DEFAULT_LIMIT = 50;
  static final int         MAX_LIMIT     = 500;
  static final int         COUNT_CAP     = 10_000;
  static final String      TITLE         = "title";
  static final Set<String> FILTERS       = Set.of("state", "country", "city", "areaCode", "service", "hasImages", "website",
      "aggregator", "ageMin", "ageMax", "postedFrom", "postedTo");
  /** Recon's substring filters and full text: the P4s and P7 arms, not this slice. */
  static final Set<String> UNSUPPORTED   = Set.of("phone", "email", "business", "businessName", "q", "imageHash");
  private static final Set<String> OPTIONS     = Set.of("sort", "limit", "projection");
  private static final int         MAX_AGE     = 200;
  private static final String      SORT_PROPERTY = "tsPosted";
  private static final String      RANK_PROPERTY = "views";
  private static final String      TEXT_TYPE     = "TextBody";

  /** The core of one open partition: it keeps that partition's cardinality tables, so it outlives requests. */
  private record PartitionCore(Database database, StructuredSearch search) {
  }

  private final ArcadeDBServer                             server;
  private final SporkReconConfiguration                    config;
  private final ConcurrentHashMap<String, PartitionCore>   cores = new ConcurrentHashMap<>();

  /**
   * A parsed search.
   *
   * @param query   the core query, without the count and the person's filter, which are per partition
   * @param fields  the Record fields of each row
   * @param title   whether each row carries its ad title from {@code txt_<p>}
   * @param filters the request's filter names, for the audit entry
   * @param sort    the sort's wire name
   */
  record Request(StructuredQuery query, RecordProjection[] fields, boolean title, List<String> filters, String sort) {
  }

  /** What a search released, before it is audited and answered. */
  record Result(JSONArray records, long[] recordIds, long total, boolean totalCapped, boolean partial, long totalEstimate) {
  }

  SearchService(final ArcadeDBServer server, final SporkReconConfiguration config) {
    this.server = server;
    this.config = config;
  }

  /** Recon's request to a core query; every refusal is a 400 that reads nothing. */
  static Request parse(final JSONObject payload) {
    final JSONObject body = payload != null ? payload : new JSONObject();
    for (final String key : body.keySet()) {
      if (UNSUPPORTED.contains(key))
        throw SporkRefusal.badRequest("filterUnsupported", "'" + key + "' is not served by this search yet (substring and full text)");
      if (!FILTERS.contains(key) && !OPTIONS.contains(key))
        throw SporkRefusal.badRequest("searchInvalid", "'" + key + "' is not a search field");
    }

    final String sort = body.getString("sort", "posted_desc");
    final int limit = integer(body, "limit", DEFAULT_LIMIT);
    if (limit < 1 || limit > MAX_LIMIT)
      throw SporkRefusal.badRequest("searchInvalid", "'limit' must be 1 to " + MAX_LIMIT);
    StructuredQuery query = switch (sort) {
      case "posted_desc" -> StructuredQuery.newestFirst(limit);
      case "posted_asc" -> StructuredQuery.oldestFirst(limit);
      case "views_desc" -> StructuredQuery.newestFirst(limit).rankBy(RANK_PROPERTY);
      default -> throw SporkRefusal.badRequest("searchInvalid", "'sort' must be posted_desc, posted_asc or views_desc");
    };

    final String state = text(body, "state");
    if (state != null)
      query = query.where(Facet.eq("stateCode", state.trim().toUpperCase(Locale.ROOT)));
    final String country = text(body, "country");
    if (country != null)
      query = query.where(Facet.eq("countryCode", country.trim().toUpperCase(Locale.ROOT)));
    final String city = text(body, "city");
    if (city != null)
      query = query.where(Facet.eq("cityNorm", SporkKeys.cityNorm(city)));
    final String areaCode = text(body, "areaCode");
    if (areaCode != null)
      query = query.where(Facet.eq("areaCodes", areaCode.trim()));
    final String service = text(body, "service");
    if (service != null)
      query = query.where(Facet.eq("services", service));
    final String website = text(body, "website");
    if (website != null)
      query = query.where(Facet.eq("website", website));
    final String aggregator = text(body, "aggregator");
    if (aggregator != null)
      query = query.where(Facet.eq("aggregator", aggregator));
    if (body.has("hasImages") && !body.isNull("hasImages")) {
      if (!(body.get("hasImages") instanceof Boolean hasImages))
        throw SporkRefusal.badRequest("searchInvalid", "'hasImages' must be a boolean");
      query = query.where(Facet.eq("hasImages", hasImages));
    }
    if (body.has("ageMin") || body.has("ageMax")) {
      final int min = integer(body, "ageMin", 0);
      final int max = integer(body, "ageMax", MAX_AGE);
      if (min < 0 || min > max || max > MAX_AGE)
        throw SporkRefusal.badRequest("searchInvalid", "'ageMin' and 'ageMax' must be 0 to " + MAX_AGE + ", the minimum first");
      query = query.where(Facet.intRange("age", min, max));
    }
    final long from = instant(body, "postedFrom", Long.MIN_VALUE);
    final long to = instant(body, "postedTo", Long.MAX_VALUE);
    if (from > to)
      throw SporkRefusal.badRequest("searchInvalid", "'postedFrom' is after 'postedTo'");
    if (from != Long.MIN_VALUE || to != Long.MAX_VALUE)
      query = query.window(from, to);

    // the projection takes the ad title on top of the Record fields hydrate serves
    final JSONObject projectionBody = new JSONObject();
    boolean title = false;
    if (body.has("projection") && body.get("projection") instanceof JSONArray requested) {
      final JSONArray fields = new JSONArray();
      for (int i = 0; i < requested.length(); i++)
        if (TITLE.equals(requested.get(i)))
          title = true;
        else
          fields.put(requested.get(i));
      projectionBody.put("projection", fields);
    } else if (body.has("projection"))
      projectionBody.put("projection", body.get("projection"));

    final List<String> filters = new ArrayList<>();
    for (final String key : body.keySet())
      if (FILTERS.contains(key))
        filters.add(key);
    return new Request(query, HydrateService.projection(projectionBody), title, filters, sort);
  }

  Result search(final ServerSecurityUser user, final long heldMarkings, final String tenant, final Request request) {
    final RecordVisibility rule = new RecordVisibility(tenant, heldMarkings);
    final StructuredQuery query = request.query();
    final List<Document> hits = new ArrayList<>();
    long total = 0;
    long estimate = 0;
    boolean capped = false;
    boolean partial = false;
    final EngineUserBinding binding = new EngineUserBinding(user);
    try {
      for (final String name : partitions(!query.descending())) {
        final Database partition = binding.bind(server.getDatabaseForClient(name));
        final long capLeft = COUNT_CAP - total;
        // once the cap is reached a later partition still answers its page, and a count of 1 says whether more match
        final StructuredSearch.Result page = core(name, partition).search(
            query.filter(rule.forPartition(partition)).count(capLeft > 0 ? (int) capLeft : 1));
        hits.addAll(page.rows());
        partial |= page.partial() || page.totalPartial();
        if (capLeft > 0) {
          total += page.total();
          estimate += page.totalPartial() ? page.totalEstimate() : page.total();
          capped |= page.totalCapped();
        } else
          capped |= page.total() > 0;
      }

      hits.sort(order(query));
      final int rows = Math.min(query.limit(), hits.size());
      final JSONArray records = new JSONArray(rows);
      final long[] recordIds = new long[rows];
      for (int i = 0; i < rows; i++) {
        final Document record = hits.get(i);
        recordIds[i] = record.getLong("record_id");
        final JSONObject row = row(record, request.fields());
        if (request.title())
          title(record, row, binding);
        records.put(row);
      }
      return new Result(records, recordIds, Math.min(total, COUNT_CAP), capped, partial, Math.min(COUNT_CAP, estimate));
    } finally {
      binding.release();
    }
  }

  private StructuredSearch core(final String name, final Database partition) {
    return cores.compute(name, (n, cached) -> cached != null && cached.database() == partition ? cached :
        new PartitionCore(partition, new StructuredSearch(partition, RecordVisibility.RECORD_TYPE, SORT_PROPERTY))).search();
  }

  /**
   * The ad title from {@code txt_<period>} through the record's {@code textHash}: one HASH probe and one TextBody page. The
   * person is bound on the text database first, so a person without a grant on it gets 403, the console/ops text boundary.
   */
  private void title(final Document record, final JSONObject row, final EngineUserBinding binding) {
    if (!(record.get("textHash") instanceof byte[] textHash))
      return;
    final String name = config.textPrefix() + RecordId.period(record.getLong("record_id"));
    if (!server.existsDatabase(name))
      return;
    final Database text = binding.bind(server.getDatabaseForClient(name));
    final IndexCursor cursor = text.lookupByKey(TEXT_TYPE, "textHash", textHash);
    if (cursor.hasNext() && cursor.next().asDocument().get(TITLE) instanceof String title)
      row.put(TITLE, title);
  }

  private static JSONObject row(final Document record, final RecordProjection[] fields) {
    final JSONObject out = new JSONObject();
    out.put("recordId", Long.toUnsignedString(record.getLong("record_id")));
    if (record.get("upstreamRid") instanceof Number upstreamRid)
      out.put("upstreamRid", UpstreamRid.format(upstreamRid.longValue()));
    for (final RecordProjection field : fields)
      field.copy(record, out);
    return out;
  }

  /** The core's order across partitions: time then RID, or rank then time then RID; newest (or highest) first unless oldest first. */
  private static Comparator<Document> order(final StructuredQuery query) {
    final Comparator<Document> rid = Comparator.comparing(Document::getIdentity);
    final Comparator<Document> time = Comparator.comparingLong(d -> number(d.get(SORT_PROPERTY)));
    if (query.rankProperty() != null)
      return Comparator.<Document>comparingLong(d -> number(d.get(query.rankProperty()))).thenComparing(time).thenComparing(rid)
          .reversed();
    final Comparator<Document> byTime = time.thenComparing(rid);
    return query.descending() ? byTime.reversed() : byTime;
  }

  private static long number(final Object value) {
    return value instanceof Number n ? n.longValue() : Long.MIN_VALUE;
  }

  /** The partitions this node holds, newest period first, or oldest first. */
  private List<String> partitions(final boolean oldestFirst) {
    final String prefix = config.partitionPrefix();
    final List<String> names = new ArrayList<>();
    for (final String name : server.getDatabaseNames())
      if (name.length() > prefix.length() && name.startsWith(prefix) && name.substring(prefix.length()).chars()
          .allMatch(Character::isDigit))
        names.add(name);
    final Comparator<String> byPeriod = Comparator.comparingLong(n -> Long.parseLong(n.substring(prefix.length())));
    names.sort(oldestFirst ? byPeriod : byPeriod.reversed());
    return names;
  }

  private static String text(final JSONObject body, final String key) {
    if (!body.has(key) || body.isNull(key))
      return null;
    if (!(body.get(key) instanceof String value) || value.isBlank() || value.length() > OperatingContext.MAX_FIELD_LENGTH)
      throw SporkRefusal.badRequest("searchInvalid", "'" + key + "' must be a string of 1 to " + OperatingContext.MAX_FIELD_LENGTH
          + " characters");
    return value;
  }

  private static int integer(final JSONObject body, final String key, final int defaultValue) {
    if (!body.has(key) || body.isNull(key))
      return defaultValue;
    if (!(body.get(key) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || Math.abs(n.doubleValue()) > 1e9)
      throw SporkRefusal.badRequest("searchInvalid", "'" + key + "' must be an integer");
    return n.intValue();
  }

  /** ISO-8601 instant (Recon's postedAt) or epoch milliseconds. */
  private static long instant(final JSONObject body, final String key, final long defaultValue) {
    if (!body.has(key) || body.isNull(key))
      return defaultValue;
    final Object value = body.get(key);
    if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()))
      return n.longValue();
    if (value instanceof String s)
      try {
        return Instant.parse(s).toEpochMilli();
      } catch (final DateTimeParseException e) {
        // refused below
      }
    throw SporkRefusal.badRequest("searchInvalid", "'" + key + "' must be an ISO-8601 instant or epoch milliseconds");
  }
}
