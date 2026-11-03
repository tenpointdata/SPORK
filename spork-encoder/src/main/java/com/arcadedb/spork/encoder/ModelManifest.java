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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.spork.encoder;

import com.arcadedb.serializer.json.JSONObject;

import java.util.Locale;

/**
 * One model of the registry, read from {@code models/<algo>/<ver>/model.json} (written by
 * {@code scripts/export_gte.py}). File names are plain names inside the model's directory; hashes are hex SHA-256 and,
 * when present, are checked before the model loads.
 */
public record ModelManifest(ModelId id, String space, String modality, String file, String sha256, String tokenizer,
                            String tokenizerSha256, String inputIds, String attentionMask, String output, long padTokenId,
                            int maxTokens, int nativeDimensions, int dimensions, Pooling pooling, boolean normalize,
                            String license, JSONObject source) {

  public static ModelManifest fromJSON(final JSONObject json) {
    final int dimensions = json.getInt("dimensions");
    final int nativeDimensions = json.getInt("nativeDimensions", dimensions);
    if (dimensions <= 0 || dimensions > nativeDimensions)
      throw new IllegalArgumentException("dimensions must be in 1.." + nativeDimensions + ", was " + dimensions);
    final int maxTokens = json.getInt("maxTokens", 512);
    if (maxTokens <= 2)
      throw new IllegalArgumentException("maxTokens must leave room for the special tokens, was " + maxTokens);
    return new ModelManifest(new ModelId(json.getString("algo"), json.getString("ver")), json.getString("space"),
        json.getString("modality", "text"), json.getString("file"), json.getString("sha256", null), json.getString("tokenizer", null),
        json.getString("tokenizerSha256", null), json.getString("inputIds", "input_ids"), json.getString("attentionMask", "attention_mask"),
        json.getString("output", null), json.getLong("padTokenId", 0L), maxTokens, nativeDimensions, dimensions,
        Pooling.parse(json.getString("pooling", "cls")), json.getBoolean("normalize", true), json.getString("license", null),
        json.getJSONObject("source", new JSONObject()));
  }

  /** The public description served by {@code /v1/models}: no file names, nothing about the host. */
  public JSONObject describe() {
    return new JSONObject().put("id", id.toString()).put("algo", id.algo()).put("ver", id.ver()).put("space", space)
        .put("modality", modality).put("dimensions", dimensions).put("nativeDimensions", nativeDimensions).put("maxTokens", maxTokens)
        .put("pooling", pooling.name().toLowerCase(Locale.ROOT)).put("normalize", normalize).put("license", license)
        .put("sha256", sha256).put("source", source);
  }
}
