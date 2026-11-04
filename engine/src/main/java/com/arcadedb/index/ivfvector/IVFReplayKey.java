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

/**
 * What an {@code LSM_IVF_VECTOR} write queues on its transaction and replays at commit: the posting list the vector was
 * assigned to under centroid {@code version}, the record position inside the index's bucket, the entry bytes (scan code,
 * scale, rerank code) ready to be appended to a posting page, and the prepared vector itself. The assignment and the coding
 * are done on the writing thread, so the replay under the commit lock only copies bytes - unless a training landed between
 * the write and its commit (an automatic first training, see {@code LSMIVFVectorIndex}), in which case the replay assigns
 * and codes {@code vector} again against the centroids the pages now hold. A REMOVE carries no entry.
 * <p>
 * {@code build} marks an entry a build staged for a record it scanned: the record's state at the scan, not at the commit of the
 * build's chunk, so the replay drops it when a write to the same record committed in between ({@code LSMIVFVectorIndex#applyAdd}).
 */
public record IVFReplayKey(int centroid, int version, long position, byte[] entry, float[] vector, boolean build) {

  /** The key of a live write. */
  public IVFReplayKey(final int centroid, final int version, final long position, final byte[] entry, final float[] vector) {
    this(centroid, version, position, entry, vector, false);
  }
}
