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
package com.arcadedb.benchmarks.retrieval;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.RID;
import com.arcadedb.graph.GraphBatch;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Generates the synthetic Recon-shaped dataset ({@link ReconDomain}) at an adjustable scale, through {@link GraphBatch}, then
 * builds the indexes ({@link ReconSchema#createIndexes}) and writes the {@link DatasetManifest}. Deterministic for a given
 * (records, seed): the identifier pools are created first, in rank order, then the records in sequence, each drawing its
 * identifiers from the pools with the Zipf skews of the domain.
 * <p>
 * Run it with {@code -Darcadedb.retrievalBench.records=5000000} (the default) or {@code 50000000}; see {@link BenchProps}.
 */
public final class ReconDataGenerator {
  private static final int SAMPLE_RIDS = 20_000;

  private final Path root;
  private final long records;
  private final long seed;
  private final int  recordBuckets;
  private final int  txSize;

  public ReconDataGenerator(final Path root, final long records, final long seed, final int recordBuckets, final int txSize) {
    this.root = root;
    this.records = records;
    this.seed = seed;
    this.recordBuckets = recordBuckets;
    this.txSize = txSize;
  }

  public static void main(final String[] args) throws IOException {
    final Path root = BenchProps.root();
    final long records = BenchProps.records();
    final long seed = BenchProps.seed();
    if (!BenchProps.bool("regenerate", false)) {
      final DatasetManifest existing = existingDataset(root, records, seed);
      if (existing != null) {
        System.out.println("Dataset already at " + root + " (" + records + " records, seed " + seed + "): generation skipped. Pass -D"
            + BenchProps.PREFIX + "regenerate=true to rebuild it.");
        System.out.println(summary(existing));
        return;
      }
    }
    final ReconDataGenerator generator = new ReconDataGenerator(root, records, seed, BenchProps.intValue("buckets", 1),
        BenchProps.intValue("txSize", 20_000));
    final DatasetManifest manifest = generator.generate();
    System.out.println(summary(manifest));
  }

  /** The manifest without its 20,000 sample RIDs, which are for the parameter sampler and not for a console. */
  private static String summary(final DatasetManifest manifest) {
    final JSONObject json = manifest.toJson();
    json.remove("sampleRecordRids");
    return json.toString(2);
  }

  /**
   * The manifest of a dataset already generated at {@code root} for the same records and seed, else {@code null}: what lets
   * {@code retrieval-bench.sh all} be re-run without paying the generation again. A manifest that cannot be read counts as
   * absent, so a half-written dataset is regenerated rather than benchmarked.
   */
  static DatasetManifest existingDataset(final Path root, final long records, final long seed) {
    if (!Files.isRegularFile(root.resolve(DatasetManifest.FILE_NAME)))
      return null;
    try {
      final DatasetManifest manifest = DatasetManifest.read(root);
      return manifest.records == records && manifest.seed == seed ? manifest : null;
    } catch (final IOException | RuntimeException e) {
      return null;
    }
  }

  /** Deletes whatever is at the root, generates, indexes, writes the manifest and closes the database. */
  public DatasetManifest generate() throws IOException {
    FileUtils.deleteRecursively(root.toFile());
    final Path dbPath = DatasetManifest.databasePath(root);
    final DatasetManifest manifest = new DatasetManifest();
    manifest.records = records;
    manifest.seed = seed;
    manifest.buckets = recordBuckets;

    final long startAll = System.nanoTime();
    try (final Database database = new DatabaseFactory(dbPath.toString()).create()) {
      ReconSchema.createTypes(database, recordBuckets);

      final SplittableRandom rnd = new SplittableRandom(seed);
      final long startLoad = System.nanoTime();
      try (final GraphBatch batch = database.batch().withBatchSize(txSize * 4).withLightEdges(true).withWAL(BenchProps.bool("wal", true))
          .withBidirectional(true).build()) {
        final long[] phones = createIdentifiers(batch, ReconSchema.PHONE, ReconDomain.phonePool(records), ReconDomain::phoneKey);
        final long[] emails = createIdentifiers(batch, ReconSchema.EMAIL, ReconDomain.emailPool(records), ReconDomain::emailKey);
        final long[] handles = createIdentifiers(batch, ReconSchema.HANDLE, ReconDomain.handlePool(records), ReconDomain::handleKey);
        final long[] images = createIdentifiers(batch, ReconSchema.IMAGE_HASH, ReconDomain.imagePool(records), ReconDomain::imageKey);
        manifest.identifierVertices = (long) phones.length + emails.length + handles.length + images.length;
        manifest.timingsMs.put("identifiers", (System.nanoTime() - startLoad) / 1_000_000L);

        final long startRecords = System.nanoTime();
        manifest.edges = createRecords(database, batch, rnd, phones, emails, handles, images, manifest);
        manifest.recordVertices = records;
        manifest.timingsMs.put("records+outEdges", (System.nanoTime() - startRecords) / 1_000_000L);
      }
      manifest.timingsMs.put("load(total incl. incoming edges)", (System.nanoTime() - startLoad) / 1_000_000L);

      final long startIndexes = System.nanoTime();
      final Map<String, Long> indexTimings = ReconSchema.createIndexes(database);
      for (final Map.Entry<String, Long> e : indexTimings.entrySet())
        manifest.timingsMs.put("index " + e.getKey(), e.getValue());
      manifest.timingsMs.put("indexes(total)", (System.nanoTime() - startIndexes) / 1_000_000L);

      final long startWarm = System.nanoTime();
      ReconSchema.warmVectorIndex(database);
      manifest.timingsMs.put("vectorGraphWarm", (System.nanoTime() - startWarm) / 1_000_000L);

      if (records <= 200_000L) {
        // countEntries() walks the whole index: only affordable on a small dataset, where it documents the shape
        for (final String indexName : ReconSchema.ALL_INDEXES)
          manifest.indexEntries.put(indexName, database.getSchema().getIndexByName(indexName).countEntries());
      }
    }
    manifest.timingsMs.put("total", (System.nanoTime() - startAll) / 1_000_000L);
    manifest.write(root);
    return manifest;
  }

  private interface KeyFunction {
    String key(int rank);
  }

  /** Creates the pool of one identifier type in rank order and returns the RIDs packed as longs (bucket << 40 | position). */
  private long[] createIdentifiers(final GraphBatch batch, final String type, final int pool, final KeyFunction keyOf) {
    final long[] packed = new long[pool];
    int done = 0;
    while (done < pool) {
      final int n = Math.min(txSize, pool - done);
      final Object[][] props = new Object[n][];
      for (int i = 0; i < n; i++)
        props[i] = new Object[] { "key", keyOf.key(done + i) };
      final RID[] rids = batch.createVertices(type, props);
      for (int i = 0; i < n; i++)
        packed[done + i] = pack(rids[i]);
      done += n;
    }
    return packed;
  }

  private long createRecords(final Database database, final GraphBatch batch, final SplittableRandom rnd, final long[] phones, final long[] emails,
      final long[] handles, final long[] images, final DatasetManifest manifest) {
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
    final int[] linkTargetsPhone = new int[2];
    final int[] linkTargetsImage = new int[4];
    long tsMin = Long.MAX_VALUE, tsMax = Long.MIN_VALUE, edges = 0;
    final StringBuilder text = new StringBuilder(512);

    long done = 0;
    while (done < records) {
      final int n = (int) Math.min(txSize, records - done);
      final Object[][] props = new Object[n][];
      // identifier ranks drawn per record, kept to buffer the edges once the RIDs are known
      final int[] phoneCount = new int[n], imageCount = new int[n], emailRank = new int[n], handleRank = new int[n];
      final int[][] phoneRanks = new int[n][], imageRanks = new int[n][];

      for (int i = 0; i < n; i++) {
        final long recordNo = done + i;
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

        props[i] = new Object[] {
            "recordId", "r" + recordNo,
            "source", ReconDomain.source(sourceZipf.sample(rnd.nextDouble())),
            "tsPosted", tsPosted,
            "tsCollected", tsCollected,
            "city", city.name(),
            "state", city.state(),
            "location", located ? ReconDomain.wktPoint(city.lon() + (rnd.nextDouble() - 0.5) * 0.6, city.lat() + (rnd.nextDouble() - 0.5) * 0.6) : null,
            "age", age,
            "views", views,
            "services", services,
            "text", text.toString(),
            "styleVec", ReconDomain.styleVector(styleZipf.sample(rnd.nextDouble()), rnd) };

        // identifiers: 1 phone (80%) or 2 (20%); email 30%; handle 40%; 0 (20%) or 1-4 image hashes
        phoneCount[i] = rnd.nextDouble() < 0.8 ? 1 : 2;
        phoneRanks[i] = distinctRanks(phoneZipf, rnd, phoneCount[i], linkTargetsPhone);
        phoneCount[i] = phoneRanks[i].length;
        emailRank[i] = rnd.nextDouble() < 0.3 ? emailZipf.sample(rnd.nextDouble()) : -1;
        handleRank[i] = rnd.nextDouble() < 0.4 ? handleZipf.sample(rnd.nextDouble()) : -1;
        imageCount[i] = rnd.nextDouble() < 0.2 ? 0 : 1 + rnd.nextInt(4);
        imageRanks[i] = imageCount[i] == 0 ? new int[0] : distinctRanks(imageZipf, rnd, imageCount[i], linkTargetsImage);
        imageCount[i] = imageRanks[i].length;
      }

      final RID[] rids = batch.createVertices(ReconSchema.RECORD, props);

      for (int i = 0; i < n; i++) {
        final RID record = rids[i];
        if ((done + i) % sampleEvery == 0 && sampledRids.size() < SAMPLE_RIDS)
          sampledRids.add(record.toString());
        for (int p = 0; p < phoneCount[i]; p++)
          batch.newEdge(record, ReconSchema.HAS_PHONE, unpack(database, phones[phoneRanks[i][p]]));
        edges += phoneCount[i];
        if (emailRank[i] >= 0) {
          batch.newEdge(record, ReconSchema.HAS_EMAIL, unpack(database, emails[emailRank[i]]));
          edges++;
        }
        if (handleRank[i] >= 0) {
          batch.newEdge(record, ReconSchema.HAS_HANDLE, unpack(database, handles[handleRank[i]]));
          edges++;
        }
        for (int m = 0; m < imageCount[i]; m++)
          batch.newEdge(record, ReconSchema.HAS_IMAGE, unpack(database, images[imageRanks[i][m]]));
        edges += imageCount[i];
      }
      done += n;
      if (done % 500_000L == 0 || done == records)
        System.out.printf("  records %,d / %,d (%,d edges buffered or written)%n", done, records, edges);
    }

    manifest.tsMin = tsMin;
    manifest.tsMax = tsMax;
    manifest.sampleRecordRids = sampledRids.toArray(new String[0]);
    return edges;
  }

  /** {@code count} Zipf draws without repeats (a record does not list the same phone twice), fewer when draws collide. */
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

  static long pack(final RID rid) {
    return ((long) rid.getBucketId() << 40) | rid.getPosition();
  }

  static RID unpack(final Database database, final long packed) {
    return RID.create(database, (int) (packed >>> 40), packed & ((1L << 40) - 1));
  }
}
