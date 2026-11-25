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
package com.arcadedb.index.ivfvector;

import com.arcadedb.TestHelper;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.index.Index;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.LSMIVFVectorIndexMetadata;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.Pair;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults of {@code LSM_IVF_VECTOR} that adapt to the data: the scan code chosen by the dimension, the probe count measured
 * by the training, and the paths they made faster (the INT8 scan on the page's array, the bounded selection of the probed lists).
 * The corpus that motivated them is the Recon style vector: 24-d unit vectors around 200 centroids with noise 0.35 per coordinate,
 * which is mostly noise, so the nearest neighbours of a query spread over many lists; with the previous defaults (24-bit BINARY scan,
 * 32 probes) recall@20 was 0.47 at 5M.
 */
class LSMIVFVectorIndexAdaptiveDefaultsTest extends TestHelper {
  private static final String TYPE = "Style";
  private static final int    DIMS = 24;
  private static final int    K    = 20;

  // ---------------------------------------------------------------- scan code by dimension

  @Test
  void aLowDimensionalIndexScansInt8CodesUnlessToldOtherwise() {
    createType();
    database.command("sql", "CREATE INDEX ON " + TYPE + " (v) LSM_IVF_VECTOR METADATA {dimensions: 24, similarity: 'COSINE'}");
    LSMIVFVectorIndexMetadata metadata = ivfIndex().getIVFMetadata();
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.INT8);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.NONE);
    assertThat(metadata.nprobe).isZero();
    // INT8 is its own rerank: live flag + position + 24 code bytes + the scale
    assertThat(ivfIndex().getEntrySize()).isEqualTo(9 + 24 + 4);

    // Chosen once and persisted as chosen: a reopen reads the codes back, it does not choose again
    reopenDatabase();
    metadata = ivfIndex().getIVFMetadata();
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.INT8);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.NONE);
    assertThat(metadata.nprobe).isZero();
  }

  @Test
  void aHighDimensionalIndexKeepsTheBinaryScan() {
    final LSMIVFVectorIndexMetadata metadata = resolved(new JSONObject().put("dimensions", 128));
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.BINARY);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.INT8);
    final LSMIVFVectorIndexMetadata below = resolved(new JSONObject().put("dimensions", 127));
    assertThat(below.scanCode).isEqualTo(IVFScanCode.INT8);
    assertThat(below.rerankCode).isEqualTo(IVFRerankCode.NONE);
  }

  @Test
  void explicitCodesKeepTheirMeaning() {
    // A low-dimensional index that asks for the BINARY scan gets it, with the INT8 rerank that was the default behind it
    LSMIVFVectorIndexMetadata metadata = resolved(new JSONObject().put("dimensions", 24).put("scanCode", "BINARY"));
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.BINARY);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.INT8);
    // An INT4 rerank needs a BINARY scan in front of it
    metadata = resolved(new JSONObject().put("dimensions", 24).put("rerankCode", "INT4"));
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.BINARY);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.INT4);
    // A high-dimensional index that asks for the INT8 scan gets it
    metadata = resolved(new JSONObject().put("dimensions", 512).put("scanCode", "INT8"));
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.INT8);
    // HAMMING scans its bits and reranks on nothing, without having to say so
    metadata = resolved(new JSONObject().put("dimensions", 64).put("similarity", "HAMMING"));
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.BINARY);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.NONE);
    // An explicit nprobe is the probe, whatever the training measures
    metadata = resolved(new JSONObject().put("dimensions", 24).put("nprobe", 32));
    assertThat(metadata.nprobe).isEqualTo(32);
  }

  @Test
  void aPersistedDefinitionKeepsItsCodes() {
    // A definition written before the codes adapted names BINARY + INT8 and nprobe 32 at 24-d: it is read as written
    final LSMIVFVectorIndexMetadata metadata = new LSMIVFVectorIndexMetadata(TYPE, new String[] { "v" }, -1);
    metadata.fromJSON(new JSONObject().put("dimensions", 24).put("similarity", "COSINE").put("scanCode", "BINARY")
        .put("rerankCode", "INT8").put("nprobe", 32));
    assertThat(metadata.scanCode).isEqualTo(IVFScanCode.BINARY);
    assertThat(metadata.rerankCode).isEqualTo(IVFRerankCode.INT8);
    assertThat(metadata.nprobe).isEqualTo(32);
  }

  // ---------------------------------------------------------------- probe count measured by the training

  /**
   * On the style corpus the training measures that 32 probes are not enough, the default probes what it measured, and recall@20
   * with every default is at least 0.9. The previous defaults, given explicitly, keep their meaning (and their recall).
   */
  @Test
  void theTrainingMeasuresTheProbeCountTheDataNeeds() {
    final int rows = 40_000;
    final float[][] corpus = new float[rows][];
    final float[][] queries = new float[50][];
    LSMIVFVectorIndexLowDimBenchmark.generate(corpus, queries);
    createType();
    final List<RID> rids = insert(corpus);
    final int[][] truth = LSMIVFVectorIndexLowDimBenchmark.groundTruth(corpus, queries, null);

    database.command("sql", "CREATE INDEX ON " + TYPE + " (v) LSM_IVF_VECTOR METADATA {dimensions: 24, similarity: 'COSINE'}");
    LSMIVFVectorIndex index = ivfIndex();
    final long lists = index.getStats().get("centroids");
    final long calibrated = index.getStats().get("calibratedProbes");
    assertThat(lists).isEqualTo(rows / LSMIVFVectorIndexMetadata.DEFAULT_TARGET_POSTING_SIZE);
    assertThat(calibrated).isGreaterThan(LSMIVFVectorIndexMetadata.DEFAULT_NPROBE).isLessThanOrEqualTo(lists);
    assertThat(index.getStats().get("defaultProbes")).isEqualTo(calibrated);
    final double recall = recall(index, queries, truth, rids, -1);
    assertThat(recall).as("recall@20 with every default, %d of %d lists probed", calibrated, lists).isGreaterThanOrEqualTo(0.9);

    // The measure is a property of the layout: it is persisted with it and read back by a reopen
    reopenDatabase();
    index = ivfIndex();
    // The centroids load with the first search after a reopen, from the pages
    final LSMIVFVectorIndex reopened = index;
    database.transaction(() -> reopened.findNeighbors(queries[0], K, -1, -1, null));
    assertThat(index.getStats().get("calibratedProbes")).isEqualTo(calibrated);
    assertThat(index.getStats().get("defaultProbes")).isEqualTo(calibrated);

    // The previous defaults, named: a 24-bit BINARY scan and 32 probes, the shape of the 0.47 at 5M
    database.command("sql", "DROP INDEX `" + ivfTypeIndex().getName() + "`");
    database.command("sql", "CREATE INDEX ON " + TYPE + " (v) LSM_IVF_VECTOR METADATA "
        + "{dimensions: 24, similarity: 'COSINE', scanCode: 'BINARY', rerankCode: 'INT8', nprobe: 32}");
    index = ivfIndex();
    assertThat(index.getStats().get("defaultProbes")).isEqualTo(32L);
    final double previous = recall(index, queries, truth, rids, -1);
    assertThat(previous).as("recall@20 with the previous defaults").isLessThan(recall);
  }

  /** A query that names its probe gets exactly that many lists, whatever the training measured. */
  @Test
  void aQueryProbeOverridesTheMeasuredOne() {
    final float[][] corpus = new float[10_000][];
    final float[][] queries = new float[5][];
    LSMIVFVectorIndexLowDimBenchmark.generate(corpus, queries);
    createType();
    insert(corpus);
    database.command("sql", "CREATE INDEX ON " + TYPE + " (v) LSM_IVF_VECTOR METADATA {dimensions: 24, similarity: 'COSINE'}");
    final LSMIVFVectorIndex index = ivfIndex();
    final long lists = index.getStats().get("centroids");
    database.transaction(() -> {
      final long before = index.getStats().get("scannedEntries");
      index.findNeighbors(queries[0], K, 1, -1, null);
      final long one = index.getStats().get("scannedEntries") - before;
      assertThat(one).isGreaterThan(0).isLessThan(10_000 / lists * 4);
    });
  }

  /** Where the neighbours sit in the query's own lists (tight clusters, many dimensions) the measure stays under the floor of 32. */
  @Test
  void tightClustersNeedFewProbes() {
    final Random random = new Random(3);
    final int dims = 128;
    final float[][] centers = new float[100][dims];
    for (final float[] c : centers)
      for (int d = 0; d < dims; d++)
        c[d] = (float) random.nextGaussian();
    final float[][] sample = new float[20_000][];
    for (int i = 0; i < sample.length; i++) {
      final float[] c = centers[random.nextInt(centers.length)];
      final float[] v = new float[dims];
      for (int d = 0; d < dims; d++)
        v[d] = c[d] + (float) random.nextGaussian() * 0.05f;
      IVFCodec.normalize(v);
      sample[i] = v;
    }
    final float[][][] split = IVFProbeCalibration.split(sample);
    assertThat(split[1].length).isEqualTo(IVFProbeCalibration.heldOut(sample.length));
    assertThat(split[0].length + split[1].length).isEqualTo(sample.length);
    final IVFCentroids centroids = IVFKMeans.train(split[0], dims, 100, 5, 1, 0, new Random(1));
    final int probes = IVFProbeCalibration.calibrate(split[0], split[1], sample.length, centroids, false);
    assertThat(probes).isBetween(1, LSMIVFVectorIndexMetadata.DEFAULT_NPROBE - 1);
  }

  @Test
  void nothingToMeasureOnOneListOrAnUntrainedSet() {
    final float[][] sample = new float[100][];
    final Random random = new Random(4);
    for (int i = 0; i < sample.length; i++) {
      sample[i] = new float[] { random.nextFloat(), random.nextFloat() };
    }
    final float[][] queries = { sample[0], sample[1] };
    assertThat(IVFProbeCalibration.calibrate(sample, queries, 100, IVFCentroids.untrained(2, 1, 0), false)).isZero();
    assertThat(IVFProbeCalibration.calibrate(sample, queries, 100, IVFKMeans.train(sample, 2, 1, 3, 1, 0, new Random(1)), false)).isZero();
    final IVFCentroids four = IVFKMeans.train(sample, 2, 4, 3, 1, 0, new Random(1));
    assertThat(IVFProbeCalibration.calibrate(sample, new float[0][], 100, four, false)).isZero();
    assertThat(IVFProbeCalibration.calibrate(new float[0][], queries, 100, four, false)).isZero();
    assertThat(IVFProbeCalibration.calibrate(sample, queries, 100, four, false)).isBetween(1, 4);
    // Too small a sample to hold queries out of
    assertThat(IVFProbeCalibration.heldOut(99)).isZero();
    assertThat(IVFProbeCalibration.split(sample)[1].length).isEqualTo(IVFProbeCalibration.heldOut(100));
  }

  /** A HAMMING index measures nothing (bit counts tie), so it probes the floor, as an index trained before the measure was kept. */
  @Test
  void anIndexWithoutAMeasureProbesTheFloor() {
    database.transaction(() -> database.getSchema().createDocumentType(TYPE).createProperty("v", Type.ARRAY_OF_FLOATS));
    final Random random = new Random(8);
    final float[][] corpus = new float[3_000][64];
    for (final float[] row : corpus)
      for (int d = 0; d < row.length; d++)
        row[d] = random.nextBoolean() ? 1f : 0f;
    insert(corpus);
    database.command("sql", "CREATE INDEX ON " + TYPE + " (v) LSM_IVF_VECTOR METADATA {dimensions: 64, similarity: 'HAMMING', nlist: 50}");
    final LSMIVFVectorIndex index = ivfIndex();
    assertThat(index.getStats().get("calibratedProbes")).isZero();
    assertThat(index.getStats().get("defaultProbes")).isEqualTo((long) LSMIVFVectorIndexMetadata.DEFAULT_NPROBE);
  }

  // ---------------------------------------------------------------- the paths made faster

  /** The probe set picked through the bounded heap is the exact nearest lists, nearest first, as the full sort picked them. */
  @Test
  void theBoundedSelectionPicksTheExactNearestLists() {
    final Random random = new Random(5);
    final int dims = 8;
    final int nlist = 200;
    final float[] fine = new float[nlist * dims];
    for (int i = 0; i < fine.length; i++)
      fine[i] = (float) random.nextGaussian();
    final IVFCentroids centroids = new IVFCentroids(dims, fine, new float[dims], new int[] { 0, nlist }, new float[dims], 1, true);
    for (int trial = 0; trial < 50; trial++) {
      final float[] query = new float[dims];
      for (int d = 0; d < dims; d++)
        query[d] = (float) random.nextGaussian();
      final float[] distances = new float[nlist];
      for (int c = 0; c < nlist; c++)
        distances[c] = IVFCodec.squaredDistance(query, fine, c * dims);
      final int[] order = IVFCentroids.sortedIndexes(distances);
      for (final int wanted : new int[] { 1, 7, 49, 50, 51, 120, 200 }) {
        final int[] selected = centroids.select(query, wanted);
        assertThat(selected).hasSize(wanted);
        for (int i = 0; i < wanted; i++)
          assertThat(distances[selected[i]]).as("wanted %d, rank %d", wanted, i).isEqualTo(distances[order[i]]);
      }
    }
  }

  /** The INT8 distance read off a heap page's array is the one read through the buffer, under every similarity. */
  @Test
  void theInt8DistanceOnThePageArrayIsTheBufferOne() {
    final Random random = new Random(6);
    for (final int dims : new int[] { 1, 7, 24, 100 }) {
      final float[] vector = new float[dims];
      final float[] query = new float[dims];
      for (int d = 0; d < dims; d++) {
        vector[d] = (float) random.nextGaussian();
        query[d] = (float) random.nextGaussian();
      }
      final float scale = IVFCodec.int8Scale(vector);
      final byte[] code = new byte[dims];
      IVFCodec.int8Code(vector, scale, code);
      final float queryScale = IVFCodec.int8Scale(query);
      final byte[] queryCode = new byte[dims];
      IVFCodec.int8Code(query, queryScale, queryCode);
      final int at = 13;
      final ByteBuffer heap = ByteBuffer.allocate(at + dims + 4);
      final ByteBuffer direct = ByteBuffer.allocateDirect(at + dims + 4);
      for (final ByteBuffer buffer : new ByteBuffer[] { heap, direct }) {
        buffer.put(at, code);
        buffer.putFloat(at + dims, scale);
      }
      for (final IVFSimilarity similarity : new IVFSimilarity[] { IVFSimilarity.COSINE, IVFSimilarity.DOT_PRODUCT, IVFSimilarity.EUCLIDEAN }) {
        final float norm = IVFCodec.squaredNorm(query);
        final float onArray = IVFCodec.int8Distance(similarity, queryCode, queryScale, norm, heap, at);
        final float onBuffer = IVFCodec.int8Distance(similarity, queryCode, queryScale, norm, direct, at);
        final float inMemory = IVFCodec.int8Distance(similarity, queryCode, queryScale, norm, code, scale);
        assertThat(onArray).as("%s at %d-d", similarity, dims).isEqualTo(onBuffer).isEqualTo(inMemory);
      }
    }
  }

  /**
   * The INT8 dot product the scan runs (on the Vector API when the JVM has it, as the test runs do) is the scalar one, for every
   * length shape (pairs of 8-byte chunks, a lone chunk, a scalar tail) and offset, extreme codes included.
   */
  @Test
  void theInt8DotIsTheScalarDotForEveryShape() {
    final IVFInt8Dot.Ops scalar = new IVFInt8Dot.Scalar();
    assertThat(IVFInt8Dot.select(false)).isInstanceOf(IVFInt8Dot.Scalar.class);
    final Random random = new Random(7);
    for (int dims = 1; dims <= 300; dims += dims < 40 ? 1 : 37) {
      final byte[] query = new byte[dims];
      for (int d = 0; d < dims; d++)
        query[d] = (byte) (random.nextInt(255) - 127);
      final short[] wide = IVFInt8Dot.widen(query);
      assertThat(wide.length % 8).isZero();
      final byte[] page = new byte[dims + 64];
      for (int i = 0; i < page.length; i++)
        page[i] = (byte) (random.nextInt(255) - 127);
      for (final int at : new int[] { 0, 1, 9, 63 }) {
        assertThat(IVFInt8Dot.dot(wide, page, at, dims)).as("%d dims at %d", dims, at).isEqualTo(scalar.dot(wide, page, at, dims));
        // Every product at its largest magnitude, two of them summed in a short lane
        final byte[] extreme = new byte[at + dims];
        final short[] extremeQuery = new short[wide.length];
        for (int d = 0; d < dims; d++) {
          extreme[at + d] = (byte) (d % 2 == 0 ? 127 : -127);
          extremeQuery[d] = (short) (d % 3 == 0 ? -127 : 127);
        }
        assertThat(IVFInt8Dot.dot(extremeQuery, extreme, at, dims)).isEqualTo(scalar.dot(extremeQuery, extreme, at, dims));
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  private static LSMIVFVectorIndexMetadata resolved(final JSONObject settings) {
    final LSMIVFVectorIndexMetadata metadata = new LSMIVFVectorIndexMetadata(TYPE, new String[] { "v" }, -1);
    if (!settings.has("similarity"))
      settings.put("similarity", "COSINE");
    metadata.fromUserMetadata(settings, Schema.INDEX_TYPE.LSM_IVF_VECTOR);
    metadata.resolveCodes();
    return metadata;
  }

  private double recall(final LSMIVFVectorIndex index, final float[][] queries, final int[][] truth, final List<RID> rids,
      final int nprobe) {
    final int[] hits = new int[1];
    database.transaction(() -> {
      for (int q = 0; q < queries.length; q++) {
        final Set<RID> expected = new HashSet<>();
        for (final int t : truth[q])
          expected.add(rids.get(t));
        for (final Pair<RID, Float> found : index.findNeighbors(queries[q], K, nprobe, -1, null))
          if (expected.contains(found.getFirst()))
            hits[0]++;
      }
    });
    return hits[0] / (double) (queries.length * K);
  }

  private void createType() {
    database.transaction(() -> {
      final DocumentType type = database.getSchema().createDocumentType(TYPE);
      type.createProperty("v", Type.ARRAY_OF_FLOATS);
    });
  }

  private List<RID> insert(final float[][] corpus) {
    final List<RID> rids = new ArrayList<>(corpus.length);
    for (int from = 0; from < corpus.length; from += 5_000) {
      final int first = from;
      database.transaction(() -> {
        for (int i = first; i < Math.min(first + 5_000, corpus.length); i++) {
          final MutableDocument doc = database.newDocument(TYPE);
          doc.set("v", corpus[i]);
          doc.save();
          rids.add(doc.getIdentity());
        }
      });
    }
    return rids;
  }

  private TypeIndex ivfTypeIndex() {
    return database.getSchema().getType(TYPE).getPolymorphicIndexByProperties("v");
  }

  private LSMIVFVectorIndex ivfIndex() {
    final Index[] buckets = ivfTypeIndex().getIndexesOnBuckets();
    assertThat(buckets).hasSize(1);
    return (LSMIVFVectorIndex) buckets[0];
  }
}
