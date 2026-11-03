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
package com.arcadedb.utility;

import com.arcadedb.exception.ArcadeDBException;
import com.arcadedb.exception.DatabaseOperationException;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Reader/writer lock context. A single {@link ReentrantReadWriteLock} makes every reader write the same shared state word
 * (plus a per-thread hold counter) on acquire and release, so concurrent readers - which never wait on each other - still
 * slow down as threads are added (#8838). This context stripes the lock instead: a reader locks only the stripe picked by its
 * thread id, so readers on different stripes touch different memory, while a writer takes every stripe, always in the same
 * order, and so excludes every reader. Readers vastly outnumber writers (close, drop, schema changes), so the writer pays
 * the cost of the stripes. Semantics per thread are those of {@link ReentrantReadWriteLock}: reads and writes are reentrant,
 * a writer may take the read lock (downgrade), a reader must not ask for the write lock - {@link #writeLock()} refuses it
 * with an exception instead of letting the thread wait for itself forever (#10012).
 */
public class RWLockContext {
  private static final int MAX_STRIPES       = 64;
  /**
   * Stripe cap of a per-INDEX lock ({@link #indexStripeCount()}). A database has one lock, but an index lock exists per index
   * object - a type index holds one sub-index per bucket - and each stripe is a {@link ReentrantReadWriteLock} with its sync,
   * its two views and its hold counter (110 to 150 bytes), so a 64-core host would keep 7 to 10 KB per index. Measured lookup
   * scaling stops at the machine's performance cores well before 8 stripes stop spreading the readers (see the SPORK S17
   * notes), so 8 stripes (about 1.2 KB per index) keep the gain at an eighth of the footprint.
   */
  public static final int  MAX_INDEX_STRIPES = 8;

  private final ReentrantReadWriteLock[] stripes;
  private final int                      stripeMask;
  private       boolean                  enableLocking = true;

  /**
   * Single lock, the cheapest choice for components whose readers are not hot (HTTP sessions, remote clients).
   */
  public RWLockContext() {
    this(1);
  }

  /**
   * @param stripeCount number of lock stripes, rounded up to a power of two and capped at {@link #MAX_STRIPES}
   */
  protected RWLockContext(final int stripeCount) {
    this(stripeCount, true);
  }

  /**
   * @param stripeCount number of lock stripes, rounded up to a power of two and capped at {@link #MAX_STRIPES}
   * @param fair        whether each stripe hands the lock out in arrival order. A FAIR lock makes every reader queue behind a
   *                    reader that arrived before it and is still being handed the lock, so a stream of readers on one stripe
   *                    convoys even though none of them excludes another; NON-fair lets a reader barge in while the lock is
   *                    shared, which is what a lookup path with rare writers (an index: writers are close, drop and the
   *                    compaction's file swap) wants. A non-fair {@link ReentrantReadWriteLock} still refuses new readers once a
   *                    writer is first in its queue, so the writer is not starved either
   */
  public RWLockContext(final int stripeCount, final boolean fair) {
    int n = 1;
    while (n < stripeCount && n < MAX_STRIPES)
      n <<= 1;
    stripes = new ReentrantReadWriteLock[n];
    for (int i = 0; i < n; i++)
      stripes[i] = new ReentrantReadWriteLock(fair);
    stripeMask = n - 1;
  }

  /**
   * Stripe count that fits the CPUs of this JVM, fixed when the lock is built.
   */
  public static int defaultStripeCount() {
    return Math.max(4, Runtime.getRuntime().availableProcessors());
  }

  /**
   * Stripe count of a per-index lock: {@link #defaultStripeCount()} capped at {@link #MAX_INDEX_STRIPES}, since a server keeps
   * one such lock per index of every open database.
   */
  public static int indexStripeCount() {
    return Math.min(MAX_INDEX_STRIPES, defaultStripeCount());
  }

  /** The number of stripes this lock was built with: a power of two. */
  public int getStripeCount() {
    return stripes.length;
  }

  protected ReentrantReadWriteLock.ReadLock readLock() {
    if (!enableLocking)
      return null;

    final ReentrantReadWriteLock.ReadLock rl = stripes[(int) Thread.currentThread().threadId() & stripeMask].readLock();
    rl.lock();
    return rl;
  }

  protected void readUnlock(final ReentrantReadWriteLock.ReadLock rl) {
    if (rl != null)
      rl.unlock();
  }

  protected ReentrantReadWriteLock.WriteLock[] writeLock() {
    if (!enableLocking)
      return null;

    // A READER ASKING FOR THE WRITE LOCK WAITS FOR ITSELF FOREVER: A ReentrantReadWriteLock NEVER UPGRADES. THE ONLY
    // READ LOCK THIS THREAD CAN HOLD IS ITS OWN STRIPE, SO THAT IS THE ONE TO ASK. A WRITER THAT TOOK THE READ LOCK
    // TOO (A DOWNGRADE) STILL OWNS THE WRITE LOCK AND RE-ENTERS IT NORMALLY. REFUSED RATHER THAN PARKED: A DDL ISSUED
    // FROM A RECORD READ LISTENER, OR FROM ANYTHING ELSE RUNNING UNDER THE READ LOCK, USED TO HANG THE THREAD (#10012)
    final ReentrantReadWriteLock own = stripes[(int) Thread.currentThread().threadId() & stripeMask];
    if (own.getReadHoldCount() > 0 && !own.isWriteLockedByCurrentThread())
      throw new DatabaseOperationException("Cannot acquire the exclusive lock while this thread holds the shared lock: "
          + "a read lock cannot be upgraded. The operation (e.g. a schema change) was issued from code running under the "
          + "shared lock, such as a record read listener: run it outside of it");

    final ReentrantReadWriteLock.WriteLock[] wl = new ReentrantReadWriteLock.WriteLock[stripes.length];
    // ALWAYS IN THE SAME ORDER, SO TWO WRITERS CANNOT DEADLOCK EACH OTHER
    int i = 0;
    try {
      for (; i < stripes.length; i++) {
        wl[i] = stripes[i].writeLock();
        wl[i].lock();
      }
    } catch (final Throwable t) {
      // NEVER LEAK THE STRIPES ALREADY TAKEN: THEIR READERS WOULD BLOCK FOREVER
      for (int k = i - 1; k >= 0; k--)
        wl[k].unlock();
      throw t;
    }
    return wl;
  }

  protected void writeUnlock(final ReentrantReadWriteLock.WriteLock[] wl) {
    if (wl != null)
      for (int i = wl.length - 1; i >= 0; i--)
        wl[i].unlock();
  }

  /**
   * Executes a callback in an shared lock.
   */
  public <RET> RET executeInReadLock(final Callable<RET> callable) {
    final ReentrantReadWriteLock.ReadLock rl = readLock();
    try {

      return callable.call();

    } catch (final RuntimeException e) {
      throw e;

    } catch (final Throwable e) {
      throw new ArcadeDBException("Error in execution in lock", e);

    } finally {
      readUnlock(rl);
    }
  }

  /**
   * Executes a callback in an exclusive lock.
   */
  public <RET> RET executeInWriteLock(final Callable<RET> callable) {
    final ReentrantReadWriteLock.WriteLock[] wl = writeLock();
    try {

      return callable.call();

    } catch (final RuntimeException e) {
      throw e;

    } catch (final Throwable e) {
      throw new ArcadeDBException("Error in execution in lock", e);

    } finally {
      writeUnlock(wl);
    }
  }

  protected void setLockingEnabled(final boolean enabled) {
    this.enableLocking = enabled;
  }

  protected boolean isLockingEnabled() {
    return enableLocking;
  }
}
