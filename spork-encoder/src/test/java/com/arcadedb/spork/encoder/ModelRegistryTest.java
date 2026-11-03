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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRegistryTest {
  private static final EncoderLoader FAKE_LOADER = (manifest, dir) -> new FakeEncoder(manifest);

  @TempDir
  Path models;

  @Test
  void loadsEveryManifestAndResolvesBySpaceAndById() throws Exception {
    install("gte-multilingual-base@1", "S-TEXT", true);
    install("pdq@1", "S-PDQ", true);

    try (final ModelRegistry registry = ModelRegistry.load(models, FAKE_LOADER)) {
      assertThat(registry.ids()).containsExactlyInAnyOrder("gte-multilingual-base@1", "pdq@1");
      assertThat(registry.resolve("S-TEXT", null).id().toString()).isEqualTo("gte-multilingual-base@1");
      assertThat(registry.resolve(null, "pdq@1").manifest().space()).isEqualTo("S-PDQ");
      assertThatThrownBy(() -> registry.resolve("S-IMG", null)).isInstanceOf(UnknownModelException.class);
      assertThatThrownBy(() -> registry.resolve(null, "pdq@2")).isInstanceOf(UnknownModelException.class);
      // A pinned id must belong to the space it is asked for
      assertThatThrownBy(() -> registry.resolve("S-TEXT", "pdq@1")).isInstanceOf(UnknownModelException.class);
    }
  }

  @Test
  void twoVersionsOfASpaceNeedSpacesJsonToPickTheActiveOne() throws Exception {
    install("gte-multilingual-base@1", "S-TEXT", true);
    install("gte-multilingual-base@2", "S-TEXT", true);
    assertThatThrownBy(() -> ModelRegistry.load(models, FAKE_LOADER)).isInstanceOf(IOException.class).hasMessageContaining("S-TEXT");

    Files.writeString(models.resolve("spaces.json"), new JSONObject().put("S-TEXT", "gte-multilingual-base@1").toString());
    try (final ModelRegistry registry = ModelRegistry.load(models, FAKE_LOADER)) {
      assertThat(registry.resolve("S-TEXT", null).id().toString()).isEqualTo("gte-multilingual-base@1");
      // Pinning the other version keeps working, which is what a backfill under a codebook version needs
      assertThat(registry.resolve("S-TEXT", "gte-multilingual-base@2").id().ver()).isEqualTo("2");
    }
  }

  @Test
  void refusesAModelFileWhoseHashDoesNotMatchTheManifest() throws Exception {
    install("gte-multilingual-base@1", "S-TEXT", false);
    assertThatThrownBy(() -> ModelRegistry.load(models, FAKE_LOADER)).isInstanceOf(IOException.class).hasMessageContaining("sha256");
  }

  @Test
  void refusesAManifestWhoseDirectoryDoesNotMatchItsId() throws Exception {
    final Path dir = install("gte-multilingual-base@1", "S-TEXT", true);
    final JSONObject json = new JSONObject(Files.readString(dir.resolve("model.json")));
    json.put("ver", "7");
    Files.writeString(dir.resolve("model.json"), json.toString());
    assertThatThrownBy(() -> ModelRegistry.load(models, FAKE_LOADER)).isInstanceOf(IOException.class).hasMessageContaining("@7");
  }

  @Test
  void refusesFileNamesThatLeaveTheModelDirectory() throws Exception {
    final Path dir = install("gte-multilingual-base@1", "S-TEXT", true);
    final JSONObject json = new JSONObject(Files.readString(dir.resolve("model.json")));
    json.put("file", "../../etc/passwd");
    Files.writeString(dir.resolve("model.json"), json.toString());
    assertThatThrownBy(() -> ModelRegistry.load(models, FAKE_LOADER)).isInstanceOf(IOException.class).hasMessageContaining("file name");
  }

  private Path install(final String id, final String space, final boolean rightHash) throws Exception {
    final ModelId modelId = ModelId.parse(id);
    final Path dir = Files.createDirectories(models.resolve(modelId.algo()).resolve(modelId.ver()));
    final byte[] weights = ("weights of " + id).getBytes(StandardCharsets.UTF_8);
    Files.write(dir.resolve("model.onnx"), weights);
    final JSONObject manifest = FakeEncoder.manifestJSON(id, space, 4)
        .put("sha256", rightHash ? ModelRegistry.sha256(dir.resolve("model.onnx")) : "00".repeat(32));
    Files.writeString(dir.resolve("model.json"), manifest.toString());
    return dir;
  }
}
