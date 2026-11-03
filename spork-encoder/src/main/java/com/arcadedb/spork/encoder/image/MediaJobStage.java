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
package com.arcadedb.spork.encoder.image;

import java.util.Set;

/**
 * The encoding stages of a MediaJob (01-architecture.md 4c.3 step 2, 4c.4, 6.2), in dependency order: a stage only
 * consumes what earlier stages produced. The ingest saga claims a MediaJob, runs the stages its image needs, and
 * commits the codes through the MediaJob path in one transaction per batch. Only {@link #DECODE} and {@link #PDQ} are
 * in train 1 (S-PDQ, with WS12's hash); the rest wait on P0d (face, scene models) or stay off the daily stream (OCR).
 */
public enum MediaJobStage {
  /** Bytes to pixels, shrink-on-load ({@link ImageDecoder}). */
  DECODE(null, Set.of()),
  /** Known-CSAM hash match before any model sees the image (5.7): a match stops the job and is reported, never encoded. */
  CSAM_GATE(null, Set.of(DECODE)),
  /** PDQ 256-bit hash (WS12) into S-PDQ, plus the 8 Hamming band keys on hot-window partitions. */
  PDQ("S-PDQ", Set.of(CSAM_GATE)),
  /** YuNet face detection and 5-point alignment, crops for S-FACE. */
  FACE_DETECT(null, Set.of(CSAM_GATE)),
  /** AuraFace-v1 512-d on representative faces into S-FACE (P0d). */
  FACE_EMBED("S-FACE", Set.of(FACE_DETECT)),
  /** SigLIP 2 or PE-Core into S-IMG on PDQ-cluster representatives (P0d; hot window on CPU, 0.8). */
  SCENE_EMBED("S-IMG", Set.of(CSAM_GATE, PDQ)),
  /** Text presence, the gate in front of OCR. */
  TEXT_PRESENCE(null, Set.of(CSAM_GATE)),
  /** PP-OCRv5 on text-bearing images; its text goes to the identifier normalizers and to S-TEXT as a second field. */
  OCR("S-TEXT", Set.of(TEXT_PRESENCE));

  private final String             space;
  private final Set<MediaJobStage> requires;

  MediaJobStage(final String space, final Set<MediaJobStage> requires) {
    this.space = space;
    // Not EnumSet: it reads the enum's constants, which do not exist yet while they are being constructed
    this.requires = Set.copyOf(requires);
  }

  /** @return the embedding space this stage writes, or null for a stage that only feeds others */
  public String space() {
    return space;
  }

  public Set<MediaJobStage> requires() {
    return requires;
  }
}
