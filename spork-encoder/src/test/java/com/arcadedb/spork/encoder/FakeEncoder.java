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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic stand-in for an ONNX model: row i of a batch is a unit vector derived from the text alone, so tests can
 * check order, batching and caching without native libraries.
 */
final class FakeEncoder implements Encoder {
  final List<String[]>    batches = new CopyOnWriteArrayList<>();
  final AtomicInteger     encoded = new AtomicInteger();
  private final ModelManifest manifest;
  volatile CountDownLatch gate;
  volatile RuntimeException failure;

  FakeEncoder(final String id, final String space, final int dimensions) {
    this(ModelManifest.fromJSON(manifestJSON(id, space, dimensions)));
  }

  FakeEncoder(final ModelManifest manifest) {
    this.manifest = manifest;
  }

  static JSONObject manifestJSON(final String id, final String space, final int dimensions) {
    final ModelId modelId = ModelId.parse(id);
    return new JSONObject()//
        .put("algo", modelId.algo()).put("ver", modelId.ver()).put("space", space).put("modality", "text")//
        .put("file", "model.onnx").put("dimensions", dimensions).put("nativeDimensions", dimensions)//
        .put("pooling", "none").put("maxTokens", 16).put("license", "Apache-2.0");
  }

  static float[] expected(final String text, final int dimensions) {
    final float[] row = new float[dimensions];
    int h = text.hashCode();
    double norm = 0;
    for (int i = 0; i < dimensions; i++) {
      h = h * 31 + i + 7;
      row[i] = (h % 1000) / 1000f + (i == 0 ? text.length() : 0);
      norm += row[i] * row[i];
    }
    final float inv = (float) (1 / Math.sqrt(norm));
    for (int i = 0; i < dimensions; i++)
      row[i] *= inv;
    return row;
  }

  @Override
  public ModelManifest manifest() {
    return manifest;
  }

  @Override
  public float[] encode(final String[] texts) {
    final CountDownLatch g = gate;
    if (g != null) {
      gate = null;
      try {
        g.await(10, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    batches.add(texts.clone());
    if (failure != null)
      throw failure;
    final int dims = manifest.dimensions();
    final float[] out = new float[texts.length * dims];
    for (int i = 0; i < texts.length; i++)
      System.arraycopy(expected(texts[i], dims), 0, out, i * dims, dims);
    encoded.addAndGet(texts.length);
    return out;
  }

  @Override
  public void close() {
  }
}
