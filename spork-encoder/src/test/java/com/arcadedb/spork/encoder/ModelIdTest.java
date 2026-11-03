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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelIdTest {

  @Test
  void parsesAlgoAtVer() {
    final ModelId id = ModelId.parse("gte-multilingual-base@1");
    assertThat(id.algo()).isEqualTo("gte-multilingual-base");
    assertThat(id.ver()).isEqualTo("1");
    assertThat(id.toString()).isEqualTo("gte-multilingual-base@1");
  }

  @Test
  void rejectsMissingPartsAndPathCharacters() {
    for (final String bad : new String[] { "gte", "@1", "gte@", "a@b@c", "../x@1", "x@1/2", "x y@1", "" })
      assertThatThrownBy(() -> ModelId.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
  }
}
