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
package com.arcadedb.exception;

/**
 * A {@code CREATE INDEX} the schema can read but cannot grant because of an index that already exists: the properties are
 * already indexed by an index of another kind or under another name, or the name is taken on other properties. The caller's to
 * resolve, never retryable.
 * <p>
 * An {@link IllegalArgumentException}, as these refusals always were, so every caller and wire layer that answers one (HTTP 400,
 * {@link ErrorCategory#VALIDATION}) keeps doing so. The narrower type exists so a layer that conceals free-form messages (HTTP in
 * production mode) can tell this one apart from an arbitrary {@link IllegalArgumentException}: its message is built only from
 * the names and kinds of the indexes, types and properties involved, so it is safe to show to the client.
 */
public class IndexDefinitionConflictException extends IllegalArgumentException {
  public IndexDefinitionConflictException(final String message) {
    super(message);
  }
}
