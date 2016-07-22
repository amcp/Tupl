/*
 *  Copyright 2016 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.InterruptedIOException;

import java.lang.ref.WeakReference;

import java.util.concurrent.atomic.LongAdder;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;

import java.util.concurrent.TimeUnit;

import org.cojen.tupl.util.Latch;

/**
 * Lock implementation which supports highly concurrent shared requests, but exclusive requests
 * are a little more expensive. Shared lock acquisition is reentrant, but exclusive is not.
 *
 * @author Brian S O'Neill
 */
final class CommitLock implements Lock {
    // See: "Using LongAdder to make a Reader-Writer Lock" by Concurrency Freaks, and also
    // "NUMA-Aware Reader Writer Locks".

    private final LongAdder mSharedAcquire = new LongAdder();
    private final LongAdder mSharedRelease = new LongAdder();

    private final Latch mExclusiveLatch = new Latch();

    private volatile Thread mExclusiveThread;

    static final class Reentrant extends WeakReference<Thread> {
        int count;

        Reentrant() {
            super(Thread.currentThread());
        }
    }

    private final ThreadLocal<Reentrant> mReentant = ThreadLocal.withInitial(Reentrant::new);
    private Reentrant mRecentReentant;

    private Reentrant reentrant() {
        Reentrant reentrant = mRecentReentant;
        if (reentrant == null || reentrant.get() != Thread.currentThread()) {
            reentrant = mReentant.get();
            mRecentReentant = reentrant;
        }
        return reentrant;
    }

    /**
     * Acquire shared lock.
     */
    @Override
    public boolean tryLock() {
        Reentrant reentrant = reentrant();
        if (mExclusiveThread == null || reentrant.count > 0) {
            mSharedAcquire.increment();
            reentrant.count++;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Acquire shared lock.
     */
    @Override
    public void lock() {
        Reentrant reentrant = reentrant();
        if (mExclusiveThread == null || reentrant.count > 0) {
            mSharedAcquire.increment();
        } else {
            mExclusiveLatch.acquireShared();
            try {
                mSharedAcquire.increment();
            } finally {
                mExclusiveLatch.releaseShared();
            }
        }
        reentrant.count++;
    }

    /**
     * Acquire shared lock.
     */
    @Override
    public void lockInterruptibly() throws InterruptedException {
        Reentrant reentrant = reentrant();
        if (mExclusiveThread == null || reentrant.count > 0) {
            mSharedAcquire.increment();
        } else {
            mExclusiveLatch.acquireSharedInterruptibly();
            try {
                mSharedAcquire.increment();
            } finally {
                mExclusiveLatch.releaseShared();
            }
        }
        reentrant.count++;
    }

    /**
     * Acquire shared lock.
     */
    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        Reentrant reentrant = reentrant();
        if (mExclusiveThread == null || reentrant.count > 0) {
            mSharedAcquire.increment();
        } else {
            if (time < 0) {
                mExclusiveLatch.acquireShared();
            } else if (time == 0 || !mExclusiveLatch.tryAcquireSharedNanos(unit.toNanos(time))) {
                return false;
            }
            try {
                mSharedAcquire.increment();
            } finally {
                mExclusiveLatch.releaseShared();
            }
        }
        reentrant.count++;
        return true;
    }

    /**
     * Release shared lock.
     */
    @Override
    public void unlock() {
        mSharedRelease.increment();
        Thread t = mExclusiveThread;
        if (t != null && !hasSharedLockers()) {
            LockSupport.unpark(t);
        }
        reentrant().count--;
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

    void acquireExclusive() throws InterruptedIOException {
        // Only one thread can obtain exclusive lock.
        try {
            mExclusiveLatch.acquireExclusiveInterruptibly();
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }

        // If full exclusive lock cannot be immediately obtained, it's due to a shared lock
        // being held for a long time. While waiting for the exclusive lock, all other shared
        // requests are queued. By waiting a timed amount and giving up, the exclusive lock
        // request is effectively de-prioritized. For each retry, the timeout is doubled, to
        // ensure that the exclusive request is not starved.

        try {
            long nanosTimeout = 1000; // 1 microsecond
            while (!finishAcquireExclusive(nanosTimeout)) {
                nanosTimeout <<= 1;
            }
        } catch (Throwable e) {
            mExclusiveThread = null;
            mExclusiveLatch.releaseExclusive();
            throw e;
        }
    }

    private boolean finishAcquireExclusive(long nanosTimeout) throws InterruptedIOException {
        // Signal that shared locks cannot be granted anymore.
        mExclusiveThread = Thread.currentThread();

        if (hasSharedLockers()) {
            // Wait for shared locks to be released.

            long nanosEnd = nanosTimeout <= 0 ? 0 : System.nanoTime() + nanosTimeout;

            while (true) {
                if (nanosTimeout < 0) {
                    LockSupport.park();
                } else {
                    LockSupport.parkNanos(nanosTimeout);
                }

                if (Thread.interrupted()) {
                    throw new InterruptedIOException();
                }

                if (!hasSharedLockers()) {
                    break;
                }

                if (nanosTimeout >= 0 &&
                    (nanosTimeout == 0 || (nanosTimeout = nanosEnd - System.nanoTime()) <= 0))
                {
                    mExclusiveThread = null;
                    return false;
                }
            }
        }

        reentrant().count++;
        return true;
    }

    void releaseExclusive() {
        mExclusiveThread = null;
        mExclusiveLatch.releaseExclusive();
        reentrant().count--;
    }

    boolean hasQueuedThreads() {
        return mExclusiveLatch.hasQueuedThreads();
    }

    private boolean hasSharedLockers() {
        // Ordering is important here. It prevents observing a release too soon.
        return mSharedRelease.sum() != mSharedAcquire.sum();
    }
}
