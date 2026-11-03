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
package com.arcadedb.function.sql.history;

import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.function.sql.SQLFunctionAbstract;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.versioning.RecordVersion;
import com.arcadedb.versioning.RecordVersioning;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Shared argument handling of the {@code history.*} functions over native record versioning ({@link RecordVersioning}): a record
 * is a RID, a record, a query result holding one, or its RID as a string; an instant is a date, a java.time value or epoch
 * milliseconds.
 */
public abstract class SQLFunctionHistoryAbstract extends SQLFunctionAbstract {

  protected SQLFunctionHistoryAbstract(final String name) {
    super(name);
  }

  protected static RecordVersioning versioning(final CommandContext context) {
    return RecordVersioning.of(context.getDatabase());
  }

  protected RID toRid(final Object value) {
    if (value instanceof Identifiable identifiable)
      return identifiable.getIdentity();
    if (value instanceof Result result && result.getIdentity().isPresent())
      return result.getIdentity().get();
    if (value instanceof String string && RID.is(string))
      return new RID(string);
    throw new CommandExecutionException("Function '" + getName() + "' expects a record or a RID, found: " + value);
  }

  protected long toEpochMillis(final Object value) {
    return switch (value) {
      case Date date -> date.getTime();
      case Number number -> number.longValue();
      case Instant instant -> instant.toEpochMilli();
      case LocalDateTime dateTime -> dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
      case ZonedDateTime dateTime -> dateTime.toInstant().toEpochMilli();
      case LocalDate date -> date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
      case null, default -> throw new CommandExecutionException(
          "Function '" + getName() + "' expects a date or epoch milliseconds, found: " + value);
    };
  }

  protected static List<Map<String, Object>> toMaps(final List<RecordVersion> versions) {
    final List<Map<String, Object>> result = new ArrayList<>(versions.size());
    for (final RecordVersion version : versions)
      result.add(version.toMap());
    return result;
  }
}
