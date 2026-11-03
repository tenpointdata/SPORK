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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaJobStageTest {

  @Test
  void everyStageRequiresOnlyEarlierStages() {
    for (final MediaJobStage stage : MediaJobStage.values())
      for (final MediaJobStage required : stage.requires())
        assertThat(required.ordinal()).as("%s requires %s", stage, required).isLessThan(stage.ordinal());
  }

  @Test
  void noModelSeesAnImageBeforeTheCsamGate() {
    for (final MediaJobStage stage : MediaJobStage.values()) {
      if (stage == MediaJobStage.DECODE || stage == MediaJobStage.CSAM_GATE)
        continue;
      assertThat(reaches(stage, MediaJobStage.CSAM_GATE)).as(stage.name()).isTrue();
    }
  }

  @Test
  void libvipsDecoderIsAnUnavailableStub() {
    assertThat(LibvipsImageDecoder.available()).isFalse();
    try (final LibvipsImageDecoder decoder = new LibvipsImageDecoder()) {
      assertThatThrownBy(() -> decoder.decode(new byte[] { 1 }, 256, 1L << 26, 3)).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void decodedImageChecksItsBuffer() {
    assertThat(new DecodedImage(2, 2, 3, new byte[12], "jpeg").pixels()).hasSize(12);
    assertThatThrownBy(() -> new DecodedImage(2, 2, 3, new byte[11], "jpeg")).isInstanceOf(IllegalArgumentException.class);
  }

  private static boolean reaches(final MediaJobStage from, final MediaJobStage target) {
    for (final MediaJobStage required : from.requires())
      if (required == target || reaches(required, target))
        return true;
    return false;
  }
}
