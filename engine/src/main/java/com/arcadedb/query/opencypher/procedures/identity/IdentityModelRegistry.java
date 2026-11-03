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
package com.arcadedb.query.opencypher.procedures.identity;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.Record;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Loads registered identity models (docs/spork/05-identity-model-mlops.md, section 4) for the {@code model} option.
 * <p>
 * A model is an {@code IdentityModel} document: {@code name} (STRING), {@code version} (INTEGER) and {@code settings}, a map
 * in exactly the shape of the procedures' options ({@code prior}, {@code kinds}, ...). Training metadata (metrics,
 * snapshot hash, code version, author, approver) can sit beside them; the engine reads only those three. A registered
 * version is immutable: a change is a new version. An {@code IdentityModelAlias} document ({@code name}, {@code model},
 * {@code version}) names the champion, and promotion moves it.
 * </p>
 * <p>
 * {@code 'name@version'} pins a version; a bare {@code 'name'} is an alias, read on every call so a promotion takes
 * effect at once. Model settings are cached per database instance and version, which immutability makes safe; the cache
 * is weak on the database, so a dropped and recreated database never sees another one's models.
 * </p>
 */
final class IdentityModelRegistry {
  static final String MODEL_TYPE = "IdentityModel";
  static final String ALIAS_TYPE = "IdentityModelAlias";

  private static final int MAX_CACHED_PER_DATABASE = 256;

  private static final Map<Object, Map<String, Map<String, Object>>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

  /** A resolved model: its {@code name@version} label and its settings. */
  record Resolved(String version, Map<String, Object> settings) {
  }

  private IdentityModelRegistry() {
  }

  static Resolved resolve(final Database database, final String reference, final String procedure) {
    final String name;
    final int version;
    final int at = reference.lastIndexOf('@');
    if (at >= 0) {
      name = reference.substring(0, at);
      try {
        version = Integer.parseInt(reference.substring(at + 1));
      } catch (final NumberFormatException e) {
        throw unknown(procedure, reference);
      }
    } else {
      final Document alias = findOne(database, ALIAS_TYPE, reference, null, procedure);
      if (alias == null || !(alias.get("model") instanceof String model) || !(alias.get("version") instanceof Number n))
        throw unknown(procedure, reference);
      name = model;
      version = n.intValue();
    }

    final String label = name + "@" + version;
    final Object cacheKey = database instanceof DatabaseInternal internal ? internal.getEmbedded() : null;
    if (cacheKey != null) {
      final Map<String, Map<String, Object>> cached = CACHE.get(cacheKey);
      if (cached != null) {
        final Map<String, Object> settings = cached.get(label);
        if (settings != null)
          return new Resolved(label, settings);
      }
    }

    final Document model = findOne(database, MODEL_TYPE, name, version, procedure);
    if (model == null)
      throw unknown(procedure, reference);
    if (!(model.get("settings") instanceof Map<?, ?> raw))
      throw new IllegalArgumentException(procedure + "(): model " + label + " has no settings map");
    final Map<String, Object> settings = new HashMap<>();
    for (final Map.Entry<?, ?> e : raw.entrySet())
      settings.put(e.getKey().toString(), e.getValue());
    final Map<String, Object> frozen = Collections.unmodifiableMap(settings);

    if (cacheKey != null)
      synchronized (CACHE) {
        final Map<String, Map<String, Object>> cached = CACHE.computeIfAbsent(cacheKey, k -> new HashMap<>());
        if (cached.size() >= MAX_CACHED_PER_DATABASE)
          cached.clear();
        cached.put(label, frozen);
      }
    return new Resolved(label, frozen);
  }

  /** The one document of the type with that name (and version, when not null); a second match is refused as ambiguous. */
  private static Document findOne(final Database database, final String type, final String name, final Integer version,
      final String procedure) {
    if (!database.getSchema().existsType(type))
      return null;
    Document found = null;
    final Iterator<Record> it = database.iterateType(type, true);
    while (it.hasNext()) {
      final Document doc = it.next().asDocument();
      if (!name.equals(doc.get("name")))
        continue;
      if (version != null && !(doc.get("version") instanceof Number n && n.intValue() == version))
        continue;
      if (found != null)
        throw new IllegalArgumentException(
            procedure + "(): " + type + " '" + name + (version == null ? "" : "@" + version) + "' is registered twice");
      found = doc;
    }
    return found;
  }

  private static IllegalArgumentException unknown(final String procedure, final String reference) {
    return new IllegalArgumentException(procedure + "(): unknown model '" + reference + "'");
  }
}
