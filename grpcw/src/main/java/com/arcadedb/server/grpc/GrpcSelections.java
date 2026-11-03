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
package com.arcadedb.server.grpc;

import com.arcadedb.database.Database;
import com.arcadedb.query.selection.SelectionService;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.google.protobuf.ByteString;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The gRPC surface of saved selections (docs/spork/03-f1-selections-design.md): request message to the JSON arguments of
 * {@link SelectionService}, and its JSON answer back to a message. A pure translation, as {@link GrpcVectorSearch} is, so the
 * HTTP routes, the MCP tools and these RPCs accept and refuse exactly the same requests. A bitmap travels as raw bytes here
 * and as base64 in the JSON the service speaks.
 */
final class GrpcSelections {
  private GrpcSelections() {
  }

  static SelectionHandle save(final Database database, final SaveSelectionRequest request) {
    final JSONObject args = new JSONObject().put("name", request.getName());
    switch (request.getSourceCase()) {
    case MEMBERS -> args.put("members", new JSONArray(request.getMembers().getRidsList()));
    case QUERY -> args.put("query", request.getQuery());
    case BITMAP -> args.put("bitmap", Base64.getEncoder().encodeToString(request.getBitmap().toByteArray()));
    case SOURCE_NOT_SET -> {
      // SelectionService refuses a save without exactly one source, with the message every surface gives
    }
    }
    if (!request.getLanguage().isEmpty())
      args.put("language", request.getLanguage());
    if (request.getParamsCount() > 0) {
      final JSONObject params = new JSONObject();
      for (final Map.Entry<String, GrpcValue> param : request.getParamsMap().entrySet())
        params.put(param.getKey(), GrpcTypeConverter.fromGrpcValue(param.getValue()));
      args.put("params", params);
    }
    if (request.hasTtlSeconds())
      args.put("ttl", request.getTtlSeconds());
    if (request.getReplace())
      args.put("replace", true);
    return handle(SelectionService.save(database, args));
  }

  static SelectionHandle combine(final Database database, final CombineSelectionsRequest request) {
    final JSONObject args = new JSONObject().put("op", request.getOp()).put("inputs", refs(request.getInputsList()))
        .put("name", request.getName());
    if (request.hasTtlSeconds())
      args.put("ttl", request.getTtlSeconds());
    if (request.getReplace())
      args.put("replace", true);
    return handle(SelectionService.combine(database, args));
  }

  static CompareSelectionsResponse compare(final Database database, final CompareSelectionsRequest request) {
    final JSONObject args = expansion(new JSONObject().put("selections", refs(request.getSelectionsList())),
        request.getViaList(), request.getDirection());
    if (request.hasMinShared())
      args.put("minShared", request.getMinShared());
    if (request.hasLimit())
      args.put("limit", request.getLimit());
    if (request.hasMemberCap())
      args.put("memberCap", request.getMemberCap());

    final JSONObject result = SelectionService.compare(database, args);
    final CompareSelectionsResponse.Builder response = CompareSelectionsResponse.newBuilder().setCount(result.getInt("count", 0));
    final JSONArray rows = result.getJSONArray("rows");
    for (int i = 0; i < rows.length(); i++) {
      final JSONObject row = rows.getJSONObject(i);
      final SharedElement.Builder element = element(row).setMask(row.getInt("mask")).setSharedBy(row.getInt("sharedBy"));
      final JSONArray selections = row.getJSONArray("selections");
      for (int s = 0; s < selections.length(); s++)
        element.addSelections(selections.getInt(s));
      final JSONArray counts = row.getJSONArray("counts", null);
      if (counts != null) {
        for (int s = 0; s < counts.length(); s++)
          element.addCounts(counts.getLong(s));
        final JSONArray members = row.getJSONArray("members");
        for (int s = 0; s < members.length(); s++) {
          final JSONArray perSelection = members.getJSONArray(s);
          final RidList.Builder rids = RidList.newBuilder();
          for (int m = 0; m < perSelection.length(); m++)
            rids.addRids(perSelection.getString(m));
          element.addMembers(rids);
        }
      }
      response.addRows(element);
    }
    return response.build();
  }

  static SelectionVennResponse venn(final Database database, final SelectionVennRequest request) {
    final JSONObject args = expansion(new JSONObject().put("selections", refs(request.getSelectionsList())),
        request.getViaList(), request.getDirection());
    final JSONArray regions = SelectionService.venn(database, args).getJSONArray("regions");

    final SelectionVennResponse.Builder response = SelectionVennResponse.newBuilder();
    for (int i = 0; i < regions.length(); i++) {
      final JSONObject region = regions.getJSONObject(i);
      final VennRegion.Builder converted = VennRegion.newBuilder().setMask(region.getInt("mask")).setCount(region.getLong("count"));
      final JSONArray selections = region.getJSONArray("selections");
      for (int s = 0; s < selections.length(); s++)
        converted.addSelections(selections.getInt(s));
      response.addRegions(converted);
    }
    return response.build();
  }

  static SelectionHandle get(final Database database, final GetSelectionRequest request) {
    return handle(SelectionService.get(database, new JSONObject().put("name", request.getName()).put("bitmap", request.getBitmap())));
  }

  static SelectionMembersResponse members(final Database database, final SelectionMembersRequest request) {
    final JSONObject args = new JSONObject().put("name", request.getName()).put("skip", request.getSkip());
    if (request.hasLimit())
      args.put("limit", request.getLimit());
    if (request.hasProperties())
      args.put("properties", request.getProperties());

    final JSONObject result = SelectionService.members(database, args);
    final SelectionMembersResponse.Builder response = SelectionMembersResponse.newBuilder().setCount(result.getInt("count", 0));
    final JSONArray members = result.getJSONArray("members");
    for (int i = 0; i < members.length(); i++)
      response.addMembers(element(members.getJSONObject(i)));
    return response.build();
  }

  static DropSelectionResponse drop(final Database database, final DropSelectionRequest request) {
    final JSONObject result = SelectionService.drop(database, new JSONObject().put("name", request.getName()));
    return DropSelectionResponse.newBuilder().setName(result.getString("name", "")).setDropped(result.getBoolean("dropped", false))
        .build();
  }

  static PurgeSelectionsResponse purge(final Database database, final PurgeSelectionsRequest request) {
    return PurgeSelectionsResponse.newBuilder().setPurged(SelectionService.purge(database, null).getLong("purged", 0L)).build();
  }

  // ---- translation ----

  /** Each selection as the service reads it: a saved selection's name as a string, or the members' record ids as a list. */
  private static JSONArray refs(final List<SelectionRef> refs) {
    final JSONArray array = new JSONArray();
    for (final SelectionRef ref : refs)
      array.put(ref.getRefCase() == SelectionRef.RefCase.NAME ? ref.getName() : new JSONArray(ref.getMembers().getRidsList()));
    return array;
  }

  /** {@code via} as the service reads it: {@code ["*"]} is every edge type, the string {@code '*'}; empty is no expansion. */
  private static JSONObject expansion(final JSONObject args, final List<String> via, final String direction) {
    if (via.size() == 1 && "*".equals(via.getFirst()))
      args.put("via", "*");
    else if (!via.isEmpty())
      args.put("via", new JSONArray(via));
    if (!direction.isEmpty())
      args.put("direction", direction);
    return args;
  }

  private static SharedElement.Builder element(final JSONObject json) {
    final String rid = json.getString("rid", "");
    final SharedElement.Builder element = SharedElement.newBuilder().setRid(rid);
    final JSONObject properties = json.getJSONObject("properties", null);
    if (properties != null)
      element.setRecord(GrpcVectorSearch.record(rid, properties));
    return element;
  }

  private static SelectionHandle handle(final JSONObject json) {
    final SelectionHandle.Builder handle = SelectionHandle.newBuilder()
        .setName(json.getString("name", ""))
        .setCardinality(json.getLong("cardinality", 0L))
        .setCreatedAt(json.getLong("createdAt", 0L));
    if (json.has("expiresAt") && !json.isNull("expiresAt"))
      handle.setExpiresAt(json.getLong("expiresAt"));
    if (json.has("source") && !json.isNull("source"))
      handle.setSource(json.getString("source"));
    if (json.has("language") && !json.isNull("language"))
      handle.setLanguage(json.getString("language"));
    if (json.has("createdBy") && !json.isNull("createdBy"))
      handle.setCreatedBy(json.getString("createdBy"));
    if (json.has("params") && !json.isNull("params")) {
      final JSONObject params = json.getJSONObject("params");
      for (final String key : params.keySet())
        handle.putParams(key, GrpcTypeConverter.toGrpcValue(params.get(key)));
    }
    if (json.has("bitmap") && !json.isNull("bitmap"))
      handle.setBitmap(ByteString.copyFrom(Base64.getDecoder().decode(json.getString("bitmap"))));
    return handle.build();
  }
}
