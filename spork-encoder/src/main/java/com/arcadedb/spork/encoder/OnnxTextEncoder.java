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

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A text model on ONNX Runtime's CPU execution provider: tokenize, pad to the longest text of the batch, run, pool, keep
 * the first {@code dimensions} components (Matryoshka) and L2-normalize.
 * <p>
 * One {@link OrtSession} per model, shared by every worker ({@code OrtSession.run} is thread-safe). Each worker thread
 * reuses its own direct input buffers, so a batch allocates only the output copy and the rows it returns. Texts are
 * tokenized one by one: the tokenizer's batch call fans out over its own thread pool, which would escape the worker
 * sizing of the {@code encode} cgroup.
 */
public final class OnnxTextEncoder implements Encoder {
  private final ModelManifest         manifest;
  private final OrtEnvironment        environment;
  private final OrtSession            session;
  private final HuggingFaceTokenizer  tokenizer;
  private final Set<String>           outputs;
  private final ThreadLocal<Scratch>  scratch = ThreadLocal.withInitial(Scratch::new);

  private static final class Scratch {
    LongBuffer ids;
    LongBuffer mask;
    long[]     maskCopy = new long[0];

    void ensure(final int elements) {
      if (ids == null || ids.capacity() < elements) {
        final int capacity = Integer.highestOneBit(Math.max(64, elements - 1)) << 1;
        ids = ByteBuffer.allocateDirect(capacity * Long.BYTES).order(ByteOrder.nativeOrder()).asLongBuffer();
        mask = ByteBuffer.allocateDirect(capacity * Long.BYTES).order(ByteOrder.nativeOrder()).asLongBuffer();
      }
      if (maskCopy.length < elements)
        maskCopy = new long[ids.capacity()];
    }
  }

  /**
   * @param intraOpThreads ONNX Runtime threads inside one model call; 1 runs the call on the worker thread itself
   */
  public OnnxTextEncoder(final ModelManifest manifest, final Path modelDirectory, final int intraOpThreads) throws OrtException {
    if (manifest.tokenizer() == null)
      throw new IllegalArgumentException("Text model " + manifest.id() + " has no tokenizer");
    this.manifest = manifest;
    this.environment = OrtEnvironment.getEnvironment();
    try (final OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
      options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
      options.setIntraOpNumThreads(intraOpThreads);
      options.setInterOpNumThreads(1);
      // Shapes change with every batch, so precomputed memory patterns never get reused
      options.setMemoryPatternOptimization(false);
      // Idle intra-op threads sleep instead of spinning: the encode cgroup shares the node with the database
      options.addConfigEntry("session.intra_op.allow_spinning", "0");
      this.session = environment.createSession(modelDirectory.resolve(manifest.file()).toString(), options);
    }
    try {
      this.tokenizer = HuggingFaceTokenizer.newInstance(modelDirectory.resolve(manifest.tokenizer()),
          Map.of("addSpecialTokens", "true", "truncation", "true", "padding", "false", "maxLength", Integer.toString(manifest.maxTokens())));
    } catch (final Exception e) {
      session.close();
      throw new IllegalStateException("Cannot load the tokenizer of " + manifest.id() + ": " + e.getMessage(), e);
    }
    this.outputs = manifest.output() != null ? Set.of(manifest.output()) : Set.of(session.getOutputNames().iterator().next());
  }

  @Override
  public ModelManifest manifest() {
    return manifest;
  }

  /** @return the token ids the model sees for {@code text}, special tokens and truncation included */
  public long[] tokenize(final String text) {
    return tokenizer.encode(text).getIds();
  }

  @Override
  public float[] encode(final String[] texts) throws OrtException {
    final int batch = texts.length;
    final Encoding[] encodings = new Encoding[batch];
    int tokens = 1;
    for (int i = 0; i < batch; i++) {
      encodings[i] = tokenizer.encode(texts[i]);
      tokens = Math.max(tokens, encodings[i].getIds().length);
    }

    final int elements = batch * tokens;
    final Scratch s = scratch.get();
    s.ensure(elements);
    final LongBuffer ids = s.ids;
    final LongBuffer mask = s.mask;
    ids.clear();
    mask.clear();
    final long pad = manifest.padTokenId();
    for (int i = 0; i < batch; i++) {
      final long[] tokenIds = encodings[i].getIds();
      final long[] attention = encodings[i].getAttentionMask();
      ids.put(tokenIds);
      mask.put(attention);
      System.arraycopy(attention, 0, s.maskCopy, i * tokens, attention.length);
      for (int t = tokenIds.length; t < tokens; t++) {
        ids.put(pad);
        mask.put(0L);
        s.maskCopy[i * tokens + t] = 0L;
      }
    }
    ids.flip();
    mask.flip();

    final long[] shape = { batch, tokens };
    final int dims = manifest.dimensions();
    final float[] rows = new float[batch * dims];
    try (final OnnxTensor idsTensor = OnnxTensor.createTensor(environment, ids, shape);
        final OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask, shape);
        final OrtSession.Result result = session.run(Map.of(manifest.inputIds(), idsTensor, manifest.attentionMask(), maskTensor), outputs)) {
      final OnnxValue value = result.get(0);
      final OnnxTensor output = (OnnxTensor) value;
      final long[] outShape = output.getInfo().getShape();
      final int hidden = (int) outShape[outShape.length - 1];
      if (hidden != manifest.nativeDimensions())
        throw new IllegalStateException(manifest.id() + " returned " + hidden + " components, the manifest says " + manifest.nativeDimensions());
      final FloatBuffer out = output.getFloatBuffer();
      manifest.pooling().pool(out, batch, outShape.length == 3 ? (int) outShape[1] : 1, hidden, s.maskCopy, dims, rows);
    }
    if (manifest.normalize())
      Pooling.l2NormalizeRows(rows, batch, dims);
    return rows;
  }

  @Override
  public void close() {
    tokenizer.close();
    try {
      session.close();
    } catch (final OrtException e) {
      // Closing at shutdown: nothing to recover
    }
  }
}
