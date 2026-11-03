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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Query-time encodes, cached (01-architecture.md 4c.3 step 2): an analyst re-running or paging a search, or several
 * legs sharing a probe, pays the 30 to 60 ms CPU encode once. Only the probe lane uses it; ingest and backfill texts are
 * almost all unique and would only evict probes.
 * <p>
 * Keyed by the first 128 bits of SHA-256 over the model id and the text, so a 4 KB ad costs 16 bytes of key, and a
 * crafted text cannot collide with another to poison a cached vector the way it could with a non-cryptographic hash.
 * Striped LRU: 16 shards, each an access-ordered map under its own lock, so concurrent probes rarely contend. Cached
 * arrays are shared and must not be modified.
 */
public final class ProbeCache {
  private static final int SHARDS = 16;

  private record Key(long hi, long lo) {
  }

  private final Shard[]   shards;
  private final LongAdder hits   = new LongAdder();
  private final LongAdder misses = new LongAdder();

  private static final class Shard extends LinkedHashMap<Key, float[]> {
    private final int capacity;

    Shard(final int capacity) {
      super(16, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<Key, float[]> eldest) {
      return size() > capacity;
    }
  }

  /** @param maxEntries the bound over all shards; 0 disables the cache */
  public ProbeCache(final int maxEntries) {
    if (maxEntries <= 0) {
      shards = null;
      return;
    }
    shards = new Shard[SHARDS];
    final int perShard = Math.max(1, maxEntries / SHARDS);
    for (int i = 0; i < SHARDS; i++)
      shards[i] = new Shard(perShard);
  }

  public float[] get(final String modelId, final String text) {
    if (shards == null)
      return null;
    final Key key = key(modelId, text);
    final Shard shard = shards[(int) (key.lo & (SHARDS - 1))];
    final float[] vector;
    synchronized (shard) {
      vector = shard.get(key);
    }
    if (vector != null)
      hits.increment();
    else
      misses.increment();
    return vector;
  }

  public void put(final String modelId, final String text, final float[] vector) {
    if (shards == null)
      return;
    final Key key = key(modelId, text);
    final Shard shard = shards[(int) (key.lo & (SHARDS - 1))];
    synchronized (shard) {
      shard.put(key, vector);
    }
  }

  public int size() {
    if (shards == null)
      return 0;
    int size = 0;
    for (final Shard shard : shards)
      synchronized (shard) {
        size += shard.size();
      }
    return size;
  }

  public long hits() {
    return hits.sum();
  }

  public long misses() {
    return misses.sum();
  }

  private static Key key(final String modelId, final String text) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    final byte[] id = modelId.getBytes(StandardCharsets.UTF_8);
    // Length-prefixed, so ("a@1", "bc") and ("a@1b", "c") hash different bytes
    digest.update((byte) (id.length >>> 8));
    digest.update((byte) id.length);
    digest.update(id);
    digest.update(text.getBytes(StandardCharsets.UTF_8));
    final byte[] h = digest.digest();
    long hi = 0;
    long lo = 0;
    for (int i = 0; i < 8; i++) {
      hi = (hi << 8) | (h[i] & 0xFF);
      lo = (lo << 8) | (h[i + 8] & 0xFF);
    }
    return new Key(hi, lo);
  }
}
