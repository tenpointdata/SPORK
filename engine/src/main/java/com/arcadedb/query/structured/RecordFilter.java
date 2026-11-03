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
package com.arcadedb.query.structured;

import com.arcadedb.database.Document;
import com.arcadedb.database.RID;

/**
 * A per-record condition of a structured search that no index answers, such as a caller's visibility rule: it applies to the
 * rows of the page and to every record the count counts. {@link #mustRead} is what keeps a count on the index when the filter
 * holds for almost every record: an entry it answers false for is counted without reading its record.
 */
@FunctionalInterface
public interface RecordFilter {
  boolean accepts(Document record);

  /**
   * False when the record behind {@code rid} certainly passes {@link #accepts}, so a count may take its index entry as is. The
   * default reads every counted record, which is always correct and costs one record read per entry.
   */
  default boolean mustRead(final RID rid) {
    return true;
  }
}
