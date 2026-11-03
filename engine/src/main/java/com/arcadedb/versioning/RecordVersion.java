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
package com.arcadedb.versioning;

import com.arcadedb.database.Document;
import com.arcadedb.database.RID;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.arcadedb.schema.Property.IN_PROPERTY;
import static com.arcadedb.schema.Property.OUT_PROPERTY;
import static com.arcadedb.schema.Property.RID_PROPERTY;
import static com.arcadedb.schema.Property.TYPE_PROPERTY;

/**
 * One version of a record of a versioned type: either the current record, or a superseded image kept by the engine's history
 * store. Valid on {@code [txFrom, txTo)} in epoch microseconds; the current version ends at {@link Long#MAX_VALUE}. Read-only:
 * a version is a value, not a record, so it can never be saved back by mistake over the current record. The properties of a
 * superseded image are decoded on first access, so listing a long history to pick one version decodes only that one.
 */
public class RecordVersion {
  public static final String VERSION_PROPERTY = "@version";
  public static final String TX_FROM_PROPERTY = "@txFrom";
  public static final String TX_TO_PROPERTY   = "@txTo";
  public static final String CURRENT_PROPERTY = "@current";

  private final RID                           identity;
  private final String                        typeName;
  private final long                          txFrom;
  private final long                          txTo;
  private final RID                           out;
  private final RID                           in;
  private       Supplier<Document>            image;
  private       Map<String, Object>           properties;
  private       int                           version;

  RecordVersion(final RID identity, final String typeName, final long txFrom, final long txTo, final RID out, final RID in,
      final Supplier<Document> image) {
    this.identity = identity;
    this.typeName = typeName;
    this.txFrom = txFrom;
    this.txTo = txTo;
    this.out = out;
    this.in = in;
    this.image = image;
  }

  public RID getIdentity() {
    return identity;
  }

  public String getTypeName() {
    return typeName;
  }

  /**
   * 1-based position in the record's history, oldest first. 0 when the version was found without listing the whole history
   * (the edges of a vertex as of an instant).
   */
  public int getVersion() {
    return version;
  }

  void setVersion(final int version) {
    this.version = version;
  }

  /** When this version became current, epoch microseconds. 0 for a record written before its type was versioned. */
  public long getTxFromMicros() {
    return txFrom;
  }

  /** When this version was superseded or deleted, epoch microseconds; {@link Long#MAX_VALUE} for the current version. */
  public long getTxToMicros() {
    return txTo;
  }

  public boolean isCurrent() {
    return txTo == Long.MAX_VALUE;
  }

  boolean isValidAt(final long micros) {
    return txFrom <= micros && micros < txTo;
  }

  /** The source vertex, for an edge; null otherwise. */
  public RID getOut() {
    return out;
  }

  /** The destination vertex, for an edge; null otherwise. */
  public RID getIn() {
    return in;
  }

  public Object get(final String name) {
    return getProperties().get(name);
  }

  /** The record's own properties as they were in this version, without the engine's stamp. */
  public Map<String, Object> getProperties() {
    if (properties == null) {
      final Map<String, Object> map = new LinkedHashMap<>(image.get().propertiesAsMap());
      map.remove(RecordVersioning.TX_FROM_PROPERTY);
      properties = map;
      image = null;
    }
    return properties;
  }

  /**
   * The version as a map: its properties plus {@code @rid}, {@code @type}, {@code @version}, {@code @txFrom}, {@code @txTo}
   * (dates, {@code @txTo} null for the current version), {@code @current}, and {@code @out}/{@code @in} for an edge.
   */
  public Map<String, Object> toMap() {
    final Map<String, Object> map = new LinkedHashMap<>(getProperties());
    map.put(RID_PROPERTY, identity);
    map.put(TYPE_PROPERTY, typeName);
    if (version > 0)
      map.put(VERSION_PROPERTY, version);
    map.put(TX_FROM_PROPERTY, new Date(txFrom / 1000));
    map.put(TX_TO_PROPERTY, isCurrent() ? null : new Date(txTo / 1000));
    map.put(CURRENT_PROPERTY, isCurrent());
    if (out != null) {
      map.put(OUT_PROPERTY, out);
      map.put(IN_PROPERTY, in);
    }
    return map;
  }

  @Override
  public String toString() {
    return identity + "@v" + version + "[" + txFrom + "," + (isCurrent() ? "now" : txTo) + ")";
  }
}
