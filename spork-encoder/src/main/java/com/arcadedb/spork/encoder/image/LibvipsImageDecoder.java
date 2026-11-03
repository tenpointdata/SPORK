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

/**
 * STUB (WS13): image decode through libvips, the decoder 01-architecture.md 4c.4 names. Not wired yet: the first image
 * space that needs decoding in the sidecar (S-FACE, S-IMG) waits on P0d, and S-PDQ's hashes come from WS12.
 * <p>
 * <b>License decision</b> (ws13-encoder.md, "libvips"): libvips is LGPL-2.1-or-later, allowed by CLAUDE.md for
 * libraries only and unmodified. The sidecar will load it dynamically (JNA, already on the classpath through the
 * tokenizer, or the FFM API once the build moves past Java 21), from an unmodified upstream release installed on the
 * node, never vendored, patched or shipped inside a SPORK artifact, so the library stays replaceable as the LGPL
 * requires. Distribution packages are NOT acceptable as is: Debian's and Ubuntu's libvips link GPL loaders (poppler-glib
 * for PDF, libimagequant for palette PNG save) into the process. The node's libvips is built from the release tarball
 * with only permissively or LGPL-licensed loaders (libjpeg-turbo, libspng, libwebp, libheif, libtiff, cgif, libexif,
 * lcms2, glib/gobject), and {@link #available()} will refuse a library whose {@code vips_version} / module list shows
 * poppler, ImageMagick, libimagequant or cfitsio.
 * <p>
 * Decoding runs on the scheduler's workers like any model call, with {@code vips_thumbnail_buffer} for shrink-on-load
 * and {@code VIPS_CONCURRENCY=1} so libvips's own threads stay inside the worker sizing.
 */
public final class LibvipsImageDecoder implements ImageDecoder {
  /** @return whether a libvips that passes the license check is loadable; always false until the binding lands */
  public static boolean available() {
    return false;
  }

  @Override
  public DecodedImage decode(final byte[] encoded, final int maxEdge, final long maxPixels, final int channels) throws ImageDecodeException {
    throw new UnsupportedOperationException("libvips decode is not wired yet (WS13 stub; lands with the first image space after P0d)");
  }

  @Override
  public void close() {
  }
}
