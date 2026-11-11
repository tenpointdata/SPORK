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
package com.arcadedb.query.sql.executor;

import com.arcadedb.exception.CommandSemanticException;
import com.arcadedb.exception.TimeoutException;
import com.arcadedb.query.sql.parser.UpdateIncrementItem;
import com.arcadedb.query.sql.parser.UpdateItem;
import com.arcadedb.query.sql.parser.UpdateOperations;
import com.arcadedb.query.sql.parser.UpdatePutItem;

import java.util.Arrays;
import java.util.List;

/**
 * Runs {@code UPDATE ... INCREMENT}, {@code ADD} and {@code PUT} (and the same operations of {@code MOVE VERTEX}) with OrientDB
 * semantics: see {@link UpdateItem#applyIncrement}, {@link UpdateItem#applyAdd} and {@link UpdatePutItem#applyUpdate}. As for
 * {@code SET} (issue #10194), every right-hand side reads the row as it was before the statement; the items then apply in order,
 * so two items on the same property both take effect.
 */
public class UpdateIncrementAddPutStep extends AbstractExecutionStep {
  private final int             type;
  private final UpdateItem[]    items;
  private final UpdatePutItem[] putItems;

  public UpdateIncrementAddPutStep(final UpdateOperations op, final CommandContext context) {
    super(context);
    this.type = op.getType();
    switch (type) {
    case UpdateOperations.TYPE_INCREMENT -> {
      final List<UpdateIncrementItem> incrementItems = op.getUpdateIncrementItems();
      items = new UpdateItem[incrementItems.size()];
      for (int i = 0; i < items.length; i++)
        items[i] = incrementItems.get(i).toUpdateItem();
      putItems = null;
    }
    case UpdateOperations.TYPE_ADD -> {
      items = op.getUpdateItems().toArray(new UpdateItem[0]);
      for (final UpdateItem item : items)
        if (item.getOperator() != UpdateItem.OPERATOR_EQ)
          throw new CommandSemanticException("UPDATE ... ADD takes 'property = value', not a compound operator: " + item);
      putItems = null;
    }
    case UpdateOperations.TYPE_PUT -> {
      items = null;
      putItems = op.getUpdatePutItems().toArray(new UpdatePutItem[0]);
    }
    default -> throw new IllegalArgumentException("Not an INCREMENT, ADD or PUT operation: " + op);
    }
  }

  @Override
  public ResultSet syncPull(final CommandContext context, final int nRecords) throws TimeoutException {
    final ResultSet upstream = getPrev().syncPull(context, nRecords);
    // reused across the records of the statement: a ResultSet is pulled by one thread and each execution builds its own step
    final Object[] rightValues = new Object[items != null ? items.length : putItems.length * 2];
    return new ResultSet() {
      @Override
      public boolean hasNext() {
        return upstream.hasNext();
      }

      @Override
      public Result next() {
        final Result result = upstream.next();
        if (result instanceof ResultInternal internal) {
          try {
            if (items != null) {
              for (int i = 0; i < items.length; i++)
                rightValues[i] = items[i].evaluateRight(internal, context);
              for (int i = 0; i < items.length; i++)
                if (type == UpdateOperations.TYPE_INCREMENT)
                  items[i].applyIncrement(internal, context, rightValues[i]);
                else
                  items[i].applyAdd(internal, context, rightValues[i]);
            } else {
              for (int i = 0; i < putItems.length; i++) {
                rightValues[i * 2] = putItems[i].getKey().execute(internal, context);
                rightValues[i * 2 + 1] = putItems[i].getValue().execute(internal, context);
              }
              for (int i = 0; i < putItems.length; i++)
                putItems[i].applyUpdate(internal, rightValues[i * 2], rightValues[i * 2 + 1]);
            }
          } finally {
            Arrays.fill(rightValues, null);
          }
        }
        return result;
      }

      @Override
      public void close() {
        upstream.close();
      }
    };
  }

  @Override
  public String prettyPrint(final int depth, final int indent) {
    final String spaces = ExecutionStepInternal.getIndent(depth, indent);
    final StringBuilder result = new StringBuilder();
    result.append(spaces);
    result.append(switch (type) {
      case UpdateOperations.TYPE_INCREMENT -> "+ UPDATE INCREMENT";
      case UpdateOperations.TYPE_ADD -> "+ UPDATE ADD";
      default -> "+ UPDATE PUT";
    });
    final Object[] all = items != null ? items : putItems;
    for (final Object item : all) {
      result.append("\n");
      result.append(spaces);
      result.append("  ");
      result.append(item);
    }
    return result.toString();
  }
}
