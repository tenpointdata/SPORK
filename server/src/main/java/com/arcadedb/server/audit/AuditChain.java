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
package com.arcadedb.server.audit;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.function.LongSupplier;

/**
 * The per-database, hash-chained audit trail (docs/spork/07-entry-identity-and-audit.md 2.4, 01-architecture 5.2). Every entry
 * is Recon's {@code AuditEntry}: {@code hash = sha256(prevHash + canonicalJSON(entry without hash))} from a genesis of 64
 * zeros, so Recon's verifier checks it unchanged.
 * <p>
 * An entry is written inside the transaction of the change it records, never after it: {@link #append} refuses to run
 * outside a transaction, and a rollback takes the entry and the head with it. Two transactions appending at once both
 * update the single {@value #HEAD_TYPE} document and both insert the next {@code seq} into a unique index, so one of them
 * fails its commit and retries; MVCC serializes the chain without a lock.
 * <p>
 * The hashed body is stored verbatim as {@code body}, beside indexed copies of its query fields, so that verification
 * recomputes from exactly the bytes that were hashed and no property type coercion can break a hash.
 */
public final class AuditChain {
  public static final  String            GENESIS_HASH       = "0".repeat(64);
  public static final  String            ENTRY_TYPE         = "AuditEntry";
  public static final  String            HEAD_TYPE          = "ChainHead";
  private static final String            HEAD_NAME          = "audit";
  private static final int               MAX_SUBJECT        = 200;
  private static final int               MAX_DETAIL_KEYS    = 20;
  private static final int               MAX_DETAIL_STRING  = 200;
  private static final DateTimeFormatter AT                 = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private final LongSupplier clock;

  public AuditChain() {
    this(System::currentTimeMillis);
  }

  public AuditChain(final LongSupplier clock) {
    this.clock = clock;
  }

  /** Creates the chain types and indexes. Schema changes cannot run inside a transaction, so call this before one. */
  public static void ensureSchema(final Database database) {
    final Schema schema = database.getSchema();
    if (schema.existsType(ENTRY_TYPE) && schema.existsType(HEAD_TYPE))
      return;

    final DocumentType entry = schema.getOrCreateDocumentType(ENTRY_TYPE);
    entry.getOrCreateProperty("seq", Type.LONG);
    for (final String name : new String[] { "at", "actor", "action", "caseRef", "subject", "body", "hash" })
      entry.getOrCreateProperty(name, Type.STRING);
    schema.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, ENTRY_TYPE, "seq");
    schema.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, ENTRY_TYPE, "actor");
    schema.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, ENTRY_TYPE, "action");

    final DocumentType head = schema.getOrCreateDocumentType(HEAD_TYPE);
    head.getOrCreateProperty("name", Type.STRING);
    head.getOrCreateProperty("seq", Type.LONG);
    head.getOrCreateProperty("hash", Type.STRING);
    schema.getOrCreateTypeIndex(Schema.INDEX_TYPE.HASH, true, HEAD_TYPE, "name");
  }

  /**
   * Appends one entry in the caller's transaction and returns it in Recon's {@code AuditEntry} shape.
   *
   * @throws IllegalStateException when no transaction is active or the chain schema is missing
   */
  public JSONObject append(final Database database, final AuditEvent event) {
    if (!database.isTransactionActive())
      throw new IllegalStateException("An audit entry is written inside the transaction of the change it records");
    if (!database.getSchema().existsType(ENTRY_TYPE) || !database.getSchema().existsType(HEAD_TYPE))
      throw new IllegalStateException("The audit chain schema is missing in database '" + database.getName() + "'");

    final Document current = head(database);
    final long seq = current != null ? current.getLong("seq") + 1 : 1L;
    final String prevHash = current != null ? current.getString("hash") : GENESIS_HASH;

    final JSONObject body = new JSONObject();
    body.put("seq", seq);
    body.put("at", AT.format(Instant.ofEpochMilli(clock.getAsLong())));
    event.actor.writeTo(body);
    body.put("action", event.action);
    if (event.caseRef != null && !event.caseRef.isEmpty())
      body.put("caseRef", clean(event.caseRef, MAX_SUBJECT));
    if (event.purpose != null && !event.purpose.isEmpty())
      body.put("purpose", clean(event.purpose, MAX_SUBJECT));
    final String subject = event.subject != null ? clean(event.subject, MAX_SUBJECT) : "";
    if (!subject.isEmpty())
      body.put("subject", subject);
    final JSONObject detail = sanitizeDetail(event.detail);
    if (detail != null)
      body.put("detail", detail);
    body.put("prevHash", prevHash);

    final String canonical = CanonicalJson.write(body);
    final String hash = hash(prevHash, canonical);

    final MutableDocument entry = database.newDocument(ENTRY_TYPE);
    entry.set("seq", seq);
    entry.set("at", body.getString("at"));
    entry.set("actor", body.getString("actor"));
    entry.set("action", event.action);
    if (body.has("caseRef"))
      entry.set("caseRef", body.getString("caseRef"));
    if (!subject.isEmpty())
      entry.set("subject", subject);
    entry.set("body", canonical);
    entry.set("hash", hash);
    entry.save();

    final MutableDocument nextHead = current != null ? current.modify() : database.newDocument(HEAD_TYPE).set("name", HEAD_NAME);
    nextHead.set("seq", seq);
    nextHead.set("hash", hash);
    nextHead.save();

    body.put("hash", hash);
    return body;
  }

  /**
   * Walks the chain from genesis to the head: every entry must exist, link to its predecessor, sit at its own position, be
   * stored canonically and hash to its recorded hash. A missing newest entry breaks against the head.
   */
  public static AuditVerification verify(final Database database) {
    if (!database.getSchema().existsType(ENTRY_TYPE) || !database.getSchema().existsType(HEAD_TYPE))
      return new AuditVerification(true, 0, -1, GENESIS_HASH);

    final Document head = head(database);
    final long headSeq = head != null ? head.getLong("seq") : 0L;
    String prevHash = GENESIS_HASH;
    for (long seq = 1; seq <= headSeq; seq++) {
      final IndexCursor cursor = database.lookupByKey(ENTRY_TYPE, "seq", seq);
      if (!cursor.hasNext())
        return new AuditVerification(false, seq - 1, seq, prevHash);
      final Document entry = cursor.next().asDocument();
      final String body = entry.getString("body");
      final String hash = entry.getString("hash");
      if (body == null || hash == null || !hash.equals(hash(prevHash, body)) || !linksAt(body, seq, prevHash))
        return new AuditVerification(false, seq - 1, seq, prevHash);
      prevHash = hash;
    }
    if (head != null && !prevHash.equals(head.getString("hash")) || database.countType(ENTRY_TYPE, false) != headSeq)
      return new AuditVerification(false, headSeq, headSeq + 1, prevHash);
    return new AuditVerification(true, headSeq, -1, prevHash);
  }

  public static String hash(final String prevHash, final String canonicalBody) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(prevHash.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest(canonicalBody.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static boolean linksAt(final String body, final long seq, final String prevHash) {
    try {
      final JSONObject parsed = new JSONObject(body);
      return parsed.getLong("seq", -1L) == seq && prevHash.equals(parsed.getString("prevHash", null))
          && body.equals(CanonicalJson.write(parsed));
    } catch (final RuntimeException e) {
      return false;
    }
  }

  private static Document head(final Database database) {
    final IndexCursor cursor = database.lookupByKey(HEAD_TYPE, "name", HEAD_NAME);
    return cursor.hasNext() ? cursor.next().asDocument() : null;
  }

  /** Recon's {@code sanitizeDetail}: up to 20 string, boolean or integral values; strings cleaned and cut to 200. */
  private static JSONObject sanitizeDetail(final JSONObject detail) {
    if (detail == null)
      return null;
    final JSONObject safe = new JSONObject();
    for (final String key : detail.keySet()) {
      if (safe.length() >= MAX_DETAIL_KEYS)
        break;
      final Object value = detail.get(key);
      if (value instanceof String s)
        safe.put(key, clean(s, MAX_DETAIL_STRING));
      else if (value instanceof Boolean b)
        safe.put(key, b);
      else if (value instanceof Number n && isIntegral(n))
        safe.put(key, n.longValue());
    }
    return safe.length() > 0 ? safe : null;
  }

  private static boolean isIntegral(final Number n) {
    final double d = n.doubleValue();
    return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) <= 9_007_199_254_740_991d;
  }

  private static String clean(final String value, final int max) {
    final StringBuilder out = new StringBuilder(Math.min(value.length(), max + 16));
    for (int i = 0, n = value.length(); i < n; i++) {
      final char c = value.charAt(i);
      out.append(c < 0x20 || c == 0x7f ? ' ' : c);
    }
    final String trimmed = out.toString().trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }
}
