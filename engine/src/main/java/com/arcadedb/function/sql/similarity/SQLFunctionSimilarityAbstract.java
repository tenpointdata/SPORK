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
package com.arcadedb.function.sql.similarity;

import com.arcadedb.exception.CommandSQLParsingException;
import com.arcadedb.function.sql.SQLFunctionAbstract;
import com.arcadedb.similarity.BitHash;
import com.arcadedb.utility.NumberUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Shared argument handling of the stylometry ({@code style.*}), image hash ({@code image.*}) and Hamming
 * ({@code hamming.*}) functions (docs/spork/06-stylometry-perceptual-hash.md). Arguments that are not what the function
 * takes are refused as a {@link CommandSQLParsingException} naming the function, never answered with a guess.
 */
abstract class SQLFunctionSimilarityAbstract extends SQLFunctionAbstract {
  /** Larger image arguments are refused before decoding: 32 MB is far above any ad photo. */
  static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

  SQLFunctionSimilarityAbstract(final String name) {
    super(name);
  }

  void checkArgs(final Object[] params, final int min, final int max) {
    if (params == null || params.length < min || params.length > max)
      throw new CommandSQLParsingException(getSyntax());
  }

  String text(final Object value, final String argument) {
    if (value instanceof String s)
      return s;
    throw new CommandSQLParsingException(getName() + "(): " + argument + " must be a string, got " + typeOf(value));
  }

  /** Image bytes: a BINARY value, or a base64 string (a {@code data:} URI prefix is skipped). */
  byte[] imageBytes(final Object value) {
    final byte[] bytes = switch (value) {
      case byte[] b -> b;
      case String s -> {
        final int comma = s.startsWith("data:") ? s.indexOf(',') : -1;
        try {
          yield Base64.getMimeDecoder().decode(comma < 0 ? s : s.substring(comma + 1));
        } catch (final IllegalArgumentException e) {
          throw new CommandSQLParsingException(getName() + "(): the image string is not base64: " + e.getMessage());
        }
      }
      default -> throw new CommandSQLParsingException(
          getName() + "(): the image must be bytes or a base64 string, got " + typeOf(value));
    };
    if (bytes.length > MAX_IMAGE_BYTES)
      throw new CommandSQLParsingException(
          getName() + "(): the image is " + bytes.length + " bytes, more than the " + MAX_IMAGE_BYTES + " allowed");
    return bytes;
  }

  long[] hash(final Object value, final String argument) {
    try {
      return BitHash.parse(value);
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(getName() + "(): " + argument + ": " + e.getMessage());
    }
  }

  long[][] hashes(final Object value, final String argument) {
    try {
      return BitHash.parseAll(value);
    } catch (final IllegalArgumentException e) {
      throw new CommandSQLParsingException(getName() + "(): " + argument + ": " + e.getMessage());
    }
  }

  int intArg(final Object value, final String argument, final int min, final int max) {
    if (value instanceof Number n)
      try {
        final int v = NumberUtils.toIntWhole(n);
        if (v >= min && v <= max)
          return v;
      } catch (final ArithmeticException e) {
        // NOT A WHOLE NUMBER IN THE INT RANGE: FALL THROUGH TO THE ERROR BELOW
      }
    throw new CommandSQLParsingException(
        getName() + "(): " + argument + " must be an integer between " + min + " and " + max + ", got " + value);
  }

  static List<Long> toList(final long[] values) {
    final List<Long> list = new ArrayList<>(values.length);
    for (final long v : values)
      list.add(v);
    return list;
  }

  static String typeOf(final Object value) {
    return value == null ? "null" : value.getClass().getSimpleName();
  }
}
