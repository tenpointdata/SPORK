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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The models this sidecar serves, keyed {@code algo@ver}, and the active model of every space.
 * <p>
 * Layout: {@code <models>/<algo>/<ver>/model.json} plus the files it names. A space with one model uses it; a space with
 * several needs {@code <models>/spaces.json} ({@code {"S-TEXT": "gte-multilingual-base@1"}}) to name the active one, so
 * a new version can be installed and pinned by a backfill before it serves probes. Every version stays addressable by id:
 * a backfill pins the id its codebook was trained for and never drifts to a newer model mid-run.
 * <p>
 * Every model file is checked against the SHA-256 in its manifest before it loads: a truncated copy or a swapped file
 * fails the start instead of silently producing vectors from other weights under the old tag.
 */
public final class ModelRegistry implements AutoCloseable {
  private static final Pattern PLAIN_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final Map<String, Encoder> byId;
  private final Map<String, Encoder> activeBySpace;

  private ModelRegistry(final Map<String, Encoder> byId, final Map<String, Encoder> activeBySpace) {
    this.byId = Collections.unmodifiableMap(byId);
    this.activeBySpace = Collections.unmodifiableMap(activeBySpace);
  }

  /** An in-memory registry, one model per space. */
  public static ModelRegistry of(final Encoder... encoders) {
    final Map<String, Encoder> byId = new LinkedHashMap<>();
    final Map<String, Encoder> bySpace = new LinkedHashMap<>();
    for (final Encoder encoder : encoders) {
      if (byId.putIfAbsent(encoder.id().toString(), encoder) != null)
        throw new IllegalArgumentException("Duplicate model " + encoder.id());
      if (bySpace.putIfAbsent(encoder.manifest().space(), encoder) != null)
        throw new IllegalArgumentException("Two models for space " + encoder.manifest().space());
    }
    return new ModelRegistry(byId, bySpace);
  }

  public static ModelRegistry load(final Path modelsDirectory, final EncoderLoader loader) throws IOException {
    final List<Path> manifests = new ArrayList<>();
    try (final DirectoryStream<Path> algos = Files.newDirectoryStream(modelsDirectory, Files::isDirectory)) {
      for (final Path algo : algos)
        try (final DirectoryStream<Path> vers = Files.newDirectoryStream(algo, Files::isDirectory)) {
          for (final Path ver : vers)
            if (Files.isRegularFile(ver.resolve("model.json")))
              manifests.add(ver.resolve("model.json"));
        }
    }
    Collections.sort(manifests);

    final Map<String, Encoder> byId = new LinkedHashMap<>();
    try {
      for (final Path manifestFile : manifests) {
        final Path dir = manifestFile.getParent();
        final ModelManifest manifest;
        try {
          manifest = ModelManifest.fromJSON(new JSONObject(Files.readString(manifestFile)));
        } catch (final RuntimeException e) {
          throw new IOException("Invalid manifest " + manifestFile + ": " + e.getMessage(), e);
        }
        final String expectedId = dir.getParent().getFileName() + "@" + dir.getFileName();
        if (!manifest.id().toString().equals(expectedId))
          throw new IOException("Manifest " + manifestFile + " declares " + manifest.id() + " but lives in the directory of " + expectedId);

        verify(dir, manifest.file(), manifest.sha256(), manifest.id());
        if (manifest.tokenizer() != null)
          verify(dir, manifest.tokenizer(), manifest.tokenizerSha256(), manifest.id());

        try {
          byId.put(manifest.id().toString(), loader.load(manifest, dir));
        } catch (final IOException e) {
          throw e;
        } catch (final Exception e) {
          throw new IOException("Cannot load model " + manifest.id() + ": " + e.getMessage(), e);
        }
      }
      return new ModelRegistry(byId, activeModels(modelsDirectory, byId));
    } catch (final IOException | RuntimeException e) {
      for (final Encoder encoder : byId.values())
        encoder.close();
      throw e;
    }
  }

  /**
   * @param space the space whose active model to use, or null when {@code id} is given
   * @param id    a pinned {@code algo@ver}, or null for the space's active model; when both are given the model must
   *              belong to the space
   */
  public Encoder resolve(final String space, final String id) {
    if (id != null) {
      final Encoder encoder = byId.get(id);
      if (encoder == null)
        throw new UnknownModelException("Unknown model '" + id + "'");
      if (space != null && !space.equals(encoder.manifest().space()))
        throw new UnknownModelException("Model '" + id + "' does not belong to space '" + space + "'");
      return encoder;
    }
    if (space == null)
      throw new UnknownModelException("Neither a space nor a model was given");
    final Encoder encoder = activeBySpace.get(space);
    if (encoder == null)
      throw new UnknownModelException("No model serves space '" + space + "'");
    return encoder;
  }

  public Collection<Encoder> encoders() {
    return byId.values();
  }

  public Set<String> ids() {
    return byId.keySet();
  }

  public boolean isActive(final Encoder encoder) {
    return activeBySpace.get(encoder.manifest().space()) == encoder;
  }

  @Override
  public void close() {
    for (final Encoder encoder : byId.values())
      encoder.close();
  }

  public static String sha256(final Path file) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    final byte[] buffer = new byte[1 << 20];
    try (final InputStream in = Files.newInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) > 0)
        digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void verify(final Path dir, final String name, final String expectedSha256, final ModelId id) throws IOException {
    if (name == null || !PLAIN_FILE_NAME.matcher(name).matches())
      throw new IOException("Model " + id + " names an invalid file name '" + name + "': only a plain name inside its directory is allowed");
    if (expectedSha256 == null)
      throw new IOException("Model " + id + " has no sha256 for '" + name + "'");
    final Path file = dir.resolve(name);
    if (!Files.isRegularFile(file))
      throw new IOException("Model " + id + " is missing its file '" + name + "'");
    final String actual = sha256(file);
    if (!actual.equalsIgnoreCase(expectedSha256))
      throw new IOException("Model " + id + ": sha256 of '" + name + "' is " + actual + ", the manifest says " + expectedSha256);
  }

  private static Map<String, Encoder> activeModels(final Path modelsDirectory, final Map<String, Encoder> byId) throws IOException {
    final Map<String, List<Encoder>> candidates = new TreeMap<>();
    for (final Encoder encoder : byId.values())
      candidates.computeIfAbsent(encoder.manifest().space(), s -> new ArrayList<>()).add(encoder);

    final JSONObject pinned = Files.isRegularFile(modelsDirectory.resolve("spaces.json")) ?
        new JSONObject(Files.readString(modelsDirectory.resolve("spaces.json"))) :
        new JSONObject();

    final Map<String, Encoder> active = new LinkedHashMap<>();
    for (final Map.Entry<String, List<Encoder>> entry : candidates.entrySet()) {
      final String space = entry.getKey();
      final String id = pinned.getString(space, null);
      if (id != null) {
        final Encoder encoder = byId.get(id);
        if (encoder == null || !space.equals(encoder.manifest().space()))
          throw new IOException("spaces.json names " + id + " for " + space + ", which is not an installed model of that space");
        active.put(space, encoder);
      } else if (entry.getValue().size() == 1)
        active.put(space, entry.getValue().get(0));
      else
        throw new IOException("Space " + space + " has " + entry.getValue().size() + " models installed: name the active one in spaces.json");
    }
    return active;
  }
}
