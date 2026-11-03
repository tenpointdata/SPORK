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
package com.arcadedb.benchmarks.orientdb;

import com.arcadedb.benchmarks.retrieval.BenchProps;
import com.arcadedb.benchmarks.retrieval.ReconDomain;
import com.arcadedb.benchmarks.retrieval.ZipfSampler;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.ODatabaseType;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentAbstract;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.record.impl.ODocument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Loads the synthetic Recon dataset into OrientDB 3.2, record for record what {@code ReconDataGenerator} writes into ArcadeDB
 * for the same {@code records} and {@code seed}: the random draws are made in the same order from the same
 * {@link ReconDomain} tables, so every record, every edge and every query parameter is the same. Only the RIDs differ.
 * <p>
 * Modelling choices, all in OrientDB's favour where there was one: edges are lightweight by default (no edge record, like the
 * ArcadeDB dataset; {@code -Darcadedb.retrievalBench.orientLightweight=false} for regular edges), the style vector is a packed
 * {@code BINARY} rather than a list of boxed floats, and locations are the {@code OPoint} shape the Lucene spatial index needs.
 * Identifier keys use OrientDB's default {@code UNIQUE} index, the time and composite indexes {@code NOTUNIQUE}, text the
 * Lucene full-text engine.
 */
public final class OrientReconGenerator {
  private static final int SAMPLE_RIDS = 20_000;

  static final String RECORD = "Record";
  static final String[] IDENTIFIER_TYPES = { "Phone", "Email", "Handle", "ImageHash" };
  static final String[] EDGE_TYPES       = { "HAS_PHONE", "HAS_EMAIL", "HAS_HANDLE", "HAS_IMAGE" };

  private final Path    root;
  private final long    records;
  private final long    seed;
  private final int     txSize;
  private final boolean lightweight;

  public OrientReconGenerator(final Path root, final long records, final long seed, final int txSize, final boolean lightweight) {
    this.root = root;
    this.records = records;
    this.seed = seed;
    this.txSize = txSize;
    this.lightweight = lightweight;
  }

  public static void main(final String[] args) throws IOException {
    final Path root = OrientDataset.root();
    final long records = BenchProps.records();
    final long seed = BenchProps.seed();
    if (!BenchProps.bool("regenerate", false) && OrientDataset.exists(root, records, seed)) {
      System.out.println("OrientDB dataset already at " + root + " (" + records + " records, seed " + seed + "): generation skipped.");
      return;
    }
    final OrientDataset dataset = new OrientReconGenerator(root, records, seed, BenchProps.intValue("orientTxSize", 2_000),
        BenchProps.bool("orientLightweight", true)).generate();
    System.out.println("records " + dataset.records + ", identifiers " + dataset.identifierVertices + ", edges " + dataset.edges + ", tsMin "
        + dataset.tsMin + ", tsMax " + dataset.tsMax);
    System.out.println("timings (ms) " + dataset.timingsMs);
  }

  public OrientDataset generate() throws IOException {
    deleteRecursively(root);
    final OrientDataset manifest = new OrientDataset();
    manifest.records = records;
    manifest.seed = seed;
    manifest.lightweightEdges = lightweight;

    final long startAll = System.nanoTime();
    try (final OrientDB orientDB = OrientDataset.open(root)) {
      orientDB.execute("create database " + OrientDataset.DATABASE_NAME + " plocal users (" + OrientDataset.USER + " identified by '"
          + OrientDataset.PASSWORD + "' role admin)").close();
      try (final ODatabaseSession db = OrientDataset.session(orientDB)) {
        createTypes(db);
        db.declareIntent(new OIntentMassiveInsert());

        final long startLoad = System.nanoTime();
        final long[] phones = createIdentifiers(db, "Phone", ReconDomain.phonePool(records), ReconDomain::phoneKey);
        final long[] emails = createIdentifiers(db, "Email", ReconDomain.emailPool(records), ReconDomain::emailKey);
        final long[] handles = createIdentifiers(db, "Handle", ReconDomain.handlePool(records), ReconDomain::handleKey);
        final long[] images = createIdentifiers(db, "ImageHash", ReconDomain.imagePool(records), ReconDomain::imageKey);
        manifest.identifierVertices = (long) phones.length + emails.length + handles.length + images.length;
        manifest.timingsMs.put("identifiers", ms(startLoad));

        final long startRecords = System.nanoTime();
        manifest.edges = createRecords(db, phones, emails, handles, images, manifest);
        manifest.timingsMs.put("records+edges", ms(startRecords));
        db.declareIntent(null);

        final long startIndexes = System.nanoTime();
        createIndexes(db, manifest);
        manifest.timingsMs.put("indexes(total)", ms(startIndexes));
      }
    }
    manifest.timingsMs.put("total", ms(startAll));
    manifest.write(root);
    return manifest;
  }

  private void createTypes(final ODatabaseSession db) {
    final OSchema schema = db.getMetadata().getSchema();
    if (!schema.existsClass("OPoint"))
      throw new IllegalStateException("OPoint is missing: the OrientDB Lucene spatial module did not register on this database");
    for (final String identifier : IDENTIFIER_TYPES)
      schema.createClass(identifier, schema.getClass("V")).createProperty("key", OType.STRING);
    final OClass record = schema.createClass(RECORD, schema.getClass("V"));
    record.createProperty("recordId", OType.STRING);
    record.createProperty("source", OType.STRING);
    record.createProperty("tsPosted", OType.LONG);
    record.createProperty("tsCollected", OType.LONG);
    record.createProperty("city", OType.STRING);
    record.createProperty("state", OType.STRING);
    record.createProperty("location", OType.EMBEDDED, schema.getClass("OPoint"));
    record.createProperty("age", OType.INTEGER);
    record.createProperty("views", OType.INTEGER);
    record.createProperty("services", OType.EMBEDDEDLIST, OType.STRING);
    record.createProperty("text", OType.STRING);
    record.createProperty("styleVec", OType.BINARY);
    for (final String edge : EDGE_TYPES)
      schema.createClass(edge, schema.getClass("E"));
  }

  private long[] createIdentifiers(final ODatabaseSession db, final String type, final int pool, final IntFunction<String> keyOf) {
    final long[] packed = new long[pool];
    final List<OVertex> batch = new ArrayList<>(txSize);
    int done = 0;
    while (done < pool) {
      final int n = Math.min(txSize, pool - done);
      db.begin();
      batch.clear();
      for (int i = 0; i < n; i++) {
        final OVertex v = db.newVertex(type);
        v.setProperty("key", keyOf.apply(done + i));
        v.save();
        batch.add(v);
      }
      db.commit();
      for (int i = 0; i < n; i++)
        packed[done + i] = pack(batch.get(i).getIdentity());
      done += n;
    }
    return packed;
  }

  private long createRecords(final ODatabaseSession db, final long[] phones, final long[] emails, final long[] handles, final long[] images,
      final OrientDataset manifest) {
    final SplittableRandom rnd = new SplittableRandom(seed);
    final ZipfSampler phoneZipf = new ZipfSampler(phones.length, ReconDomain.PHONE_ZIPF);
    final ZipfSampler emailZipf = new ZipfSampler(emails.length, ReconDomain.EMAIL_ZIPF);
    final ZipfSampler handleZipf = new ZipfSampler(handles.length, ReconDomain.HANDLE_ZIPF);
    final ZipfSampler imageZipf = new ZipfSampler(images.length, ReconDomain.IMAGE_ZIPF);
    final ZipfSampler cityZipf = new ZipfSampler(ReconDomain.CITIES, ReconDomain.CITY_ZIPF);
    final ZipfSampler serviceZipf = new ZipfSampler(ReconDomain.SERVICES, ReconDomain.SERVICE_ZIPF);
    final ZipfSampler sourceZipf = new ZipfSampler(ReconDomain.SOURCES, ReconDomain.SOURCE_ZIPF);
    final ZipfSampler wordZipf = new ZipfSampler(ReconDomain.VOCABULARY_SIZE, ReconDomain.WORD_ZIPF);
    final ZipfSampler styleZipf = new ZipfSampler(ReconDomain.STYLE_CLUSTERS, ReconDomain.STYLE_ZIPF);

    final long sampleEvery = Math.max(1L, records / SAMPLE_RIDS);
    final List<String> sampledRids = new ArrayList<>(SAMPLE_RIDS);
    final int[] scratchPhone = new int[2];
    final int[] scratchImage = new int[4];
    final ODatabaseDocumentAbstract internal = (ODatabaseDocumentAbstract) db;
    final StringBuilder text = new StringBuilder(512);
    long tsMin = Long.MAX_VALUE, tsMax = Long.MIN_VALUE, edges = 0;

    long done = 0;
    while (done < records) {
      final int n = (int) Math.min(txSize, records - done);
      final OVertex[] created = new OVertex[n];
      db.begin();
      for (int i = 0; i < n; i++) {
        final long recordNo = done + i;
        // The draws below are made in exactly the order ReconDataGenerator makes them: do not reorder
        final ReconDomain.City city = ReconDomain.city(cityZipf.sample(rnd.nextDouble()));
        final long tsPosted = ReconDomain.postedAt(rnd.nextDouble());
        final long tsCollected = tsPosted + 60_000L + (long) (rnd.nextDouble() * 3 * ReconDomain.DAY_MS);
        if (tsPosted < tsMin)
          tsMin = tsPosted;
        if (tsPosted > tsMax)
          tsMax = tsPosted;
        final boolean located = rnd.nextDouble() < 0.85;
        final int age = Math.min(60, 18 + (int) (Math.abs(rnd.nextGaussian()) * 6.0));
        final int views = (int) Math.min(1_000_000.0, Math.exp(rnd.nextGaussian() * 1.2 + 5.0));

        final int serviceCount = 1 + rnd.nextInt(4);
        final List<String> services = new ArrayList<>(serviceCount);
        for (int s = 0; s < serviceCount; s++) {
          final String service = ReconDomain.service(serviceZipf.sample(rnd.nextDouble()));
          if (!services.contains(service))
            services.add(service);
        }

        text.setLength(0);
        final int words = ReconDomain.MIN_WORDS + rnd.nextInt(ReconDomain.MAX_WORDS - ReconDomain.MIN_WORDS + 1);
        for (int w = 0; w < words; w++) {
          if (w > 0)
            text.append(' ');
          text.append(ReconDomain.word(wordZipf.sample(rnd.nextDouble())));
        }

        final String source = ReconDomain.source(sourceZipf.sample(rnd.nextDouble()));
        ODocument location = null;
        if (located) {
          final double lon = city.lon() + (rnd.nextDouble() - 0.5) * 0.6;
          final double lat = city.lat() + (rnd.nextDouble() - 0.5) * 0.6;
          location = new ODocument("OPoint");
          location.field("coordinates", List.of(lon, lat));
        }
        final float[] style = ReconDomain.styleVector(styleZipf.sample(rnd.nextDouble()), rnd);

        final OVertex v = db.newVertex(RECORD);
        v.setProperty("recordId", "r" + recordNo);
        v.setProperty("source", source);
        v.setProperty("tsPosted", tsPosted);
        v.setProperty("tsCollected", tsCollected);
        v.setProperty("city", city.name());
        v.setProperty("state", city.state());
        if (location != null)
          v.setProperty("location", location, OType.EMBEDDED);
        v.setProperty("age", age);
        v.setProperty("views", views);
        v.setProperty("services", services, OType.EMBEDDEDLIST);
        v.setProperty("text", text.toString());
        v.setProperty("styleVec", encode(style));
        v.save();
        created[i] = v;

        // identifiers: 1 phone (80%) or 2 (20%); email 30%; handle 40%; 0 (20%) or 1-4 image hashes
        final int[] phoneRanks = distinctRanks(phoneZipf, rnd, rnd.nextDouble() < 0.8 ? 1 : 2, scratchPhone);
        final int emailRank = rnd.nextDouble() < 0.3 ? emailZipf.sample(rnd.nextDouble()) : -1;
        final int handleRank = rnd.nextDouble() < 0.4 ? handleZipf.sample(rnd.nextDouble()) : -1;
        final int imageCount = rnd.nextDouble() < 0.2 ? 0 : 1 + rnd.nextInt(4);
        final int[] imageRanks = imageCount == 0 ? new int[0] : distinctRanks(imageZipf, rnd, imageCount, scratchImage);

        for (final int rank : phoneRanks)
          link(db, internal, v, "HAS_PHONE", phones[rank]);
        if (emailRank >= 0)
          link(db, internal, v, "HAS_EMAIL", emails[emailRank]);
        if (handleRank >= 0)
          link(db, internal, v, "HAS_HANDLE", handles[handleRank]);
        for (final int rank : imageRanks)
          link(db, internal, v, "HAS_IMAGE", images[rank]);
        edges += phoneRanks.length + (emailRank >= 0 ? 1 : 0) + (handleRank >= 0 ? 1 : 0) + imageRanks.length;
      }
      db.commit();
      for (int i = 0; i < n; i++)
        if ((done + i) % sampleEvery == 0 && sampledRids.size() < SAMPLE_RIDS)
          sampledRids.add(created[i].getIdentity().toString());
      done += n;
      if (done % 100_000L == 0 || done == records)
        System.out.printf("  records %,d / %,d (%,d edges)%n", done, records, edges);
    }

    manifest.tsMin = tsMin;
    manifest.tsMax = tsMax;
    manifest.sampleRecordRids = sampledRids.toArray(new String[0]);
    return edges;
  }

  private void link(final ODatabaseSession db, final ODatabaseDocumentAbstract internal, final OVertex record, final String type,
      final long packedIdentifier) {
    final OVertex identifier = ((OElement) db.load(unpack(packedIdentifier))).asVertex().orElseThrow();
    if (lightweight)
      internal.newLightweightEdge(type, record, identifier);
    else
      record.addEdge(identifier, type).save();
    identifier.save();
    record.save();
  }

  private static void createIndexes(final ODatabaseSession db, final OrientDataset manifest) {
    for (final String identifier : IDENTIFIER_TYPES)
      timed(db, manifest, identifier + ".key", "CREATE INDEX " + identifier + ".key ON " + identifier + " (key) UNIQUE");
    timed(db, manifest, "Record.tsPosted", "CREATE INDEX Record.tsPosted ON Record (tsPosted) NOTUNIQUE");
    timed(db, manifest, "Record_state_tsPosted", "CREATE INDEX Record_state_tsPosted ON Record (state, tsPosted) NOTUNIQUE");
    timed(db, manifest, "Record_age_tsPosted", "CREATE INDEX Record_age_tsPosted ON Record (age, tsPosted) NOTUNIQUE");
    timed(db, manifest, "Record.services", "CREATE INDEX Record.services ON Record (services) NOTUNIQUE");
    timed(db, manifest, "Record.text", "CREATE INDEX Record.text ON Record (text) FULLTEXT ENGINE LUCENE");
    timed(db, manifest, "Record.location", "CREATE INDEX Record.location ON Record (location) SPATIAL ENGINE LUCENE");
  }

  private static void timed(final ODatabaseSession db, final OrientDataset manifest, final String name, final String sql) {
    final long start = System.nanoTime();
    db.command(sql).close();
    manifest.timingsMs.put("index " + name, ms(start));
  }

  /** The style vector as 24 little-endian floats: the cheapest shape to scan, which is OrientDB's only kNN path. */
  static byte[] encode(final float[] vector) {
    final ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (final float f : vector)
      buffer.putFloat(f);
    return buffer.array();
  }

  /** Same as ReconDataGenerator.distinctRanks: {@code count} Zipf draws without repeats. */
  private static int[] distinctRanks(final ZipfSampler zipf, final SplittableRandom rnd, final int count, final int[] scratch) {
    int size = 0;
    for (int i = 0; i < count; i++) {
      final int rank = zipf.sample(rnd.nextDouble());
      boolean dup = false;
      for (int j = 0; j < size; j++)
        if (scratch[j] == rank) {
          dup = true;
          break;
        }
      if (!dup)
        scratch[size++] = rank;
    }
    final int[] result = new int[size];
    System.arraycopy(scratch, 0, result, 0, size);
    return result;
  }

  private static long pack(final ORID rid) {
    return ((long) rid.getClusterId() << 40) | rid.getClusterPosition();
  }

  private static ORID unpack(final long packed) {
    return new ORecordId((int) (packed >>> 40), packed & ((1L << 40) - 1));
  }

  private static long ms(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static void deleteRecursively(final Path path) throws IOException {
    if (!Files.exists(path))
      return;
    try (final Stream<Path> walk = Files.walk(path)) {
      for (final Path p : walk.sorted(Comparator.reverseOrder()).toList())
        Files.delete(p);
    }
  }
}
