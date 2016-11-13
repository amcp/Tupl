/*
 *  Copyright 2011-2016 Cojen.org
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

import sun.misc.Unsafe;

import java.lang.reflect.Field;

import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.locks.LockSupport;

/**
 * Alternative latch implementation which maintains less state, but doesn't yet support
 * timeouts or interrupts. Two fixes must be applied for supporting timeouts and interrupts.
 * First, proper queue removal is required to prevent consuming too much memory. Second, a
 * timed out exclusive request might leave the state in xshared when it shouldn't, which can
 * cause the state to later become exclusive without any actual owner.
 *
 * @author Brian S O'Neill
 */
class AltLatch {
    public static final int UNLATCHED = 0, EXCLUSIVE = 0x80000000, SHARED = 1;

    static final int SPIN_LIMIT = Runtime.getRuntime().availableProcessors();

    // TODO: Switch to VarHandle when available and utilize specialized operations. 

    static final Unsafe UNSAFE = Hasher.getUnsafe();

    static final long STATE_OFFSET, FIRST_OFFSET, LAST_OFFSET;
    static final long WAITER_OFFSET;

    static {
        try {
            Class clazz = AltLatch.class;
            STATE_OFFSET = UNSAFE.objectFieldOffset(clazz.getDeclaredField("mLatchState"));
            FIRST_OFFSET = UNSAFE.objectFieldOffset(clazz.getDeclaredField("mLatchFirst"));
            LAST_OFFSET = UNSAFE.objectFieldOffset(clazz.getDeclaredField("mLatchLast"));

            clazz = WaitNode.class;
            WAITER_OFFSET = UNSAFE.objectFieldOffset(clazz.getDeclaredField("mWaiter"));
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    /*
      unlatched:           0               latch is available
      shared:              1..0x7fffffff   latch is held shared
      exclusive:  0x80000000               latch is held exclusively
      xshared:    0x80000001..0xffffffff   latch is held shared, and exclusive is requested
     */ 
    volatile int mLatchState;

    // Queue of waiting threads.
    private transient volatile WaitNode mLatchFirst;
    private transient volatile WaitNode mLatchLast;

    AltLatch() {
    }

    /**
     * @param initialState UNLATCHED, EXCLUSIVE, or SHARED
     */
    AltLatch(int initialState) {
        // Assume that this latch instance is published to other threads safely, and so a
        // volatile store isn't required.
        UNSAFE.putInt(this, STATE_OFFSET, initialState);
    }

    /**
     * Try to acquire the exclusive latch, barging ahead of any waiting threads if possible.
     */
    public boolean tryAcquireExclusive() {
        return mLatchState == 0 && UNSAFE.compareAndSwapInt(this, STATE_OFFSET, 0, EXCLUSIVE);
    }

    /**
     * Attempt to acquire the exclusive latch, aborting if interrupted.
     *
     * @param nanosTimeout pass -1 for infinite timeout
     */
    /*
    public boolean tryAcquireExclusiveNanos(long nanosTimeout) throws InterruptedException {
        return doTryAcquireExclusiveNanos(nanosTimeout);
    }

    private boolean doTryAcquireExclusiveNanos(long nanosTimeout) throws InterruptedException {
        int trials = 0;
        while (true) {
            int state = mLatchState;

            if (state == 0) {
                if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, 0, EXCLUSIVE)) {
                    return true;
                }
            } else {
                if (nanosTimeout == 0) {
                    return false;
                }

                // Shared latches prevent an exclusive latch from being immediately acquired,
                // but no new shared latches can be granted once the exclusive bit is set.
                if (state > 0 &&
                    !UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state | EXCLUSIVE))
                {
                    trials = spin(trials);
                    continue;
                }
            }

            return acquire(new Timed(nanosTimeout));
        }
    }
    */

    /**
     * Acquire the exclusive latch, barging ahead of any waiting threads if possible.
     */
    public void acquireExclusive() {
        int trials = 0;
        while (true) {
            int state = mLatchState;

            if (state == 0) {
                if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, 0, EXCLUSIVE)) {
                    return;
                }
            } else {
                // Shared latches prevent an exclusive latch from being immediately acquired,
                // but no new shared latches can be granted once the exclusive bit is set.
                if (state > 0 &&
                    !UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state | EXCLUSIVE))
                {
                    trials = spin(trials);
                    continue;
                }
            }

            acquire(new WaitNode());
            return;
        }
    }

    /**
     * Acquire the exclusive latch, aborting if interrupted.
     */
    /*
    public void acquireExclusiveInterruptibly() throws InterruptedException {
        doTryAcquireExclusiveNanos(-1);
    }
    */

    /**
     * Downgrade the held exclusive latch into a shared latch. Caller must later call
     * releaseShared instead of releaseExclusive.
     */
    public final void downgrade() {
        mLatchState = 1;

        while (true) {
            // Sweep through the queue, waking up a contiguous run of shared waiters.
            final WaitNode first = first();
            if (first == null) {
                return;
            }

            WaitNode node = first;
            while (true) {
                Thread waiter = node.mWaiter;
                if (waiter != null) {
                    if (node instanceof Shared) {
                        UNSAFE.getAndAddInt(this, STATE_OFFSET, 1);
                        if (UNSAFE.compareAndSwapObject(node, WAITER_OFFSET, waiter, null)) {
                            LockSupport.unpark(waiter);
                        } else {
                            // Already unparked, so fix the share count.
                            UNSAFE.getAndAddInt(this, STATE_OFFSET, -1);
                        }
                    } else {
                        // An exclusive waiter is in the queue, so disallow new shared latches.
                        // This is indicated by setting the exclusive bit, along with a
                        // non-zero shared latch count.
                        int state;
                        do {
                            state = mLatchState;
                        } while (state >= 0 &&
                                 !UNSAFE.compareAndSwapInt
                                 (this, STATE_OFFSET, state, state | EXCLUSIVE));

                        if (node != first) {
                            // Advance the queue.
                            mLatchFirst = node;
                        }

                        return;
                    }
                }

                WaitNode next = node.get();

                if (next == null) {
                    // Queue is now empty, unless an enqueue is in progress.
                    if (UNSAFE.compareAndSwapObject(this, LAST_OFFSET, node, null)) {
                        UNSAFE.compareAndSwapObject(this, FIRST_OFFSET, first, null);
                        return;
                    }
                    // Sweep from the start again.
                    break;
                }

                node = next;
            }
        }
    }

    /**
     * Release the held exclusive latch.
     */
    public final void releaseExclusive() {
        int trials = 0;
        while (true) {
            WaitNode last = mLatchLast;

            if (last == null) {
                // No waiters, so release the latch.
                mLatchState = 0;

                // Need to check if any waiters again, due to race with enqueue. If cannot
                // immediately re-acquire the latch, then let the new owner (which barged in)
                // unpark the waiters when it releases the latch.
                last = mLatchLast;
                if (last == null || !UNSAFE.compareAndSwapInt(this, STATE_OFFSET, 0, EXCLUSIVE)) {
                    return;
                }
            }

            // Although the last waiter has been observed to exist, the first waiter field
            // might not be set yet.
            WaitNode first = mLatchFirst;

            unpark: if (first != null) {
                Thread waiter = first.mWaiter;

                if (waiter != null) {
                    if (first instanceof Shared) {
                        // TODO: can this be combined into one downgrade step?
                        downgrade();
                        doReleaseShared(mLatchState);
                        return;
                    }

                    if (!first.mDenied) {
                        // Unpark the waiter, but allow another thread to barge in.
                        mLatchState = 0;
                        LockSupport.unpark(waiter);
                        return;
                    }
                }

                // Remove first from the queue.
                {
                    WaitNode next = first.get();
                    if (next != null) {
                        mLatchFirst = next;
                    } else {
                        // Queue is now empty, unless an enqueue is in progress.
                        if (last != first ||
                            !UNSAFE.compareAndSwapObject(this, LAST_OFFSET, last, null))
                        {
                            break unpark;
                        }
                        UNSAFE.compareAndSwapObject(this, FIRST_OFFSET, last, null);
                    }
                }

                if (waiter != null &&
                    UNSAFE.compareAndSwapObject(first, WAITER_OFFSET, waiter, null))
                {
                    // Fair handoff to waiting thread.
                    LockSupport.unpark(waiter);
                    return;
                }
            }

            trials = spin(trials);
        }
    }

    /**
     * Convenience method, which releases the held exclusive or shared latch.
     *
     * @param exclusive call releaseExclusive if true, else call releaseShared
     */
    public final void release(boolean exclusive) {
        if (exclusive) {
            releaseExclusive();
        } else {
            doReleaseShared(mLatchState);
        }
    }

    /**
     * Releases an exclusive or shared latch.
     */
    public final void releaseEither() {
        int state = mLatchState;
        if (state == EXCLUSIVE) {
            releaseExclusive();
        } else {
            doReleaseShared(state);
        }
    }

    /**
     * Try to acquire the shared latch, barging ahead of any waiting threads if possible.
     */
    public boolean tryAcquireShared() {
        int state = mLatchState;
        return state >= 0 && UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state + 1);
    }

    /**
     * Attempt to acquire a shared latch, aborting if interrupted.
     *
     * @param nanosTimeout pass -1 for infinite timeout
     */
    /*
    public boolean tryAcquireSharedNanos(long nanosTimeout) throws InterruptedException {
        return doTryAcquireSharedNanos(nanosTimeout);
    }

    private boolean doTryAcquireSharedNanos(long nanosTimeout) throws InterruptedException {
        int trials = 0;
        while (true) {
            int state = mLatchState;
            if (state < 0) {
                return acquire(new TimedShared(nanosTimeout));
            }
            if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state + 1)) {
                return true;
            }
            trials = spin(trials);
        }
    }
    */

    /**
     * Like tryAcquireShared, except it might block if an exclusive latch is held.
     */
    public boolean weakAcquireShared() {
        int state = mLatchState;
        if (state < 0) {
            acquire(new Shared());
            return true;
        }
        if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state + 1)) {
            return true;
        }
        return false;
    }

    /**
     * Acquire the shared latch, barging ahead of any waiting threads if possible.
     */
    public void acquireShared() {
        int trials = 0;
        while (true) {
            int state = mLatchState;
            if (state < 0) {
                acquire(new Shared());
                return;
            }
            if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state + 1)) {
                return;
            }
            trials = spin(trials);
        }
    }

    /**
     * Acquire a shared latch, aborting if interrupted.
     */
    /*
    public void acquireSharedInterruptibly() throws InterruptedException {
        doTryAcquireSharedNanos(-1);
    }
    */

    /**
     * Attempt to upgrade a held shared latch into an exclusive latch. Upgrade fails if shared
     * latch is held by more than one thread. If successful, caller must later call
     * releaseExclusive instead of releaseShared.
     */
    public boolean tryUpgrade() {
        return doTryUpgrade();
    }

    private boolean doTryUpgrade() {
        while (true) {
            int state = mLatchState;
            if ((state & ~EXCLUSIVE) != 1) {
                return false;
            }
            if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, EXCLUSIVE)) {
                return true;
            }
            // Try again if exclusive bit flipped. Don't bother with spin yielding, because the
            // exclusive bit usually switches to 1, not 0.
        }
    }

    /**
     * Release a held shared latch.
     */
    public void releaseShared() {
        doReleaseShared(mLatchState);
    }

    private void doReleaseShared(int state) {
        int trials = 0;
        while (true) {
            if (state < 0) {
                // An exclusive latch is waiting in the queue.
                if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, --state)) {
                    if (state == EXCLUSIVE) {
                        // This thread just released the last shared latch, and now it owns the
                        // exclusive latch. Release it for the next in the queue.
                        releaseExclusive();
                    }
                    return;
                }
            } else {
                WaitNode last = mLatchLast;
                if (last == null) {
                    // No waiters, so release the latch.
                    if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, --state)) {
                        if (state == 0) {
                            // Need to check if any waiters again, due to race with enqueue. If
                            // cannot immediately re-acquire the latch, then let the new owner
                            // (which barged in) unpark the waiters when it releases the latch.
                            last = mLatchLast;
                            if (last != null &&
                                UNSAFE.compareAndSwapInt(this, STATE_OFFSET, 0, EXCLUSIVE))
                            {
                                releaseExclusive();
                            }
                        }
                        return;
                    }
                } else if (state == 1) {
                    // Try to switch to exclusive, and then let releaseExclusive deal with
                    // unparking the waiters.
                    if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, 1, EXCLUSIVE)
                        || doTryUpgrade())
                    {
                        releaseExclusive();
                        return;
                    }
                } else if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, --state)) {
                    return;
                }
            }

            trials = spin(trials);
            state = mLatchState;
        }
    }

    private boolean acquire(final WaitNode node) {
        // Enqueue the node.
        WaitNode prev;
        {
            node.mWaiter = Thread.currentThread();
            prev = (WaitNode) UNSAFE.getAndSetObject(this, LAST_OFFSET, node);
            if (prev == null) {
                mLatchFirst = node;
            } else {
                prev.set(node);
                WaitNode pp = prev.mPrev;
                if (pp != null) {
                    // The old last node was intended to be removed, but the last node cannot
                    // be removed unless it's also the first. Bypass it now that a new last
                    // node has been enqueued.
                    pp.lazySet(node);
                }
            }
        }

        int acquireResult = node.acquire(this);

        if (acquireResult < 0) {
            while (true) {
                boolean parkAbort = node.park(this);

                if (node.mWaiter == null) {
                    // Fair handoff, and so node is no longer in the queue.
                    return true;
                }

                acquireResult = node.acquire(this);

                if (acquireResult >= 0) {
                    // Latch acquired after parking.
                    break;
                }

                if (parkAbort) {
                    UNSAFE.putOrderedObject(node, WAITER_OFFSET, null);
                    // FIXME: if xshared state, clear it?
                    // FIXME: remove from queue

                    if (Thread.interrupted()) {
                        Utils.rethrow(new InterruptedException());
                    }

                    return false;
                }

                // Lost the race. Request fair handoff.
                node.mDenied = true;
            }
        }

        if (acquireResult != 0) {
            // Only one thread is allowed to remove nodes.
            return true;
        }

        // Remove the node now, releasing memory. Because the latch is held, no other dequeues
        // are in progress, but enqueues still are.

        if (mLatchFirst == node) {
            while (true) {
                WaitNode next = node.get();
                if (next != null) {
                    mLatchFirst = next;
                    return true;
                } else {
                    // Queue is now empty, unless an enqueue is in progress.
                    WaitNode last = mLatchLast;
                    if (last == node &&
                        UNSAFE.compareAndSwapObject(this, LAST_OFFSET, last, null))
                    {
                        UNSAFE.compareAndSwapObject(this, FIRST_OFFSET, last, null);
                        return true;
                    }
                }
            }
        } else {
            WaitNode next = node.get();
            if (next == null) {
                // Removing the last node creates race conditions with enqueues. Instead, stash
                // a reference to the previous node and let the enqueue deal with it after a
                // new node has been enqueued.
                node.mPrev = prev;
                next = node.get();
                // Double check in case an enqueue just occurred that may have failed to notice
                // the previous node assignment.
                if (next == null) {
                    return true;
                }
            }
            // Bypass the removed node, allowing it to be released.
            prev.lazySet(next);
            return true;
        }
    }

    private WaitNode first() {
        int trials = 0;
        while (true) {
            WaitNode last = mLatchLast;
            if (last == null) {
                return null;
            }
            // Although the last waiter has been observed to exist, the first waiter field
            // might not be set yet.
            WaitNode first = mLatchFirst;
            if (first != null) {
                return first;
            }
            trials = spin(trials);
        }
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        Utils.appendMiniString(b, this);
        b.append(" {state=");

        int state = mLatchState;
        if (state == 0) {
            b.append("unlatched");
        } else if (state == EXCLUSIVE) {
            b.append("exclusive");
        } else if (state >= 0) {
            b.append("shared:").append(state);
        } else {
            b.append("xshared:").append(state & ~EXCLUSIVE);
        }

        WaitNode last = mLatchLast;

        if (last != null) {
            b.append(", ");
            WaitNode first = mLatchFirst;
            if (first == last) {
                b.append("firstQueued=").append(last);
            } else if (first == null) {
                b.append("lastQueued=").append(last);
            } else {
                b.append("firstQueued=").append(first)
                    .append(", lastQueued=").append(last);
            }
        }

        return b.append('}').toString();
    }

    /**
     * @return new trials value
     */
    static int spin(int trials) {
        trials++;
        if (trials >= SPIN_LIMIT) {
            Thread.yield();
            trials = 0;
        }
        return trials;
    }

    /**
     * Atomic reference is to the next node in the chain.
     */
    static class WaitNode extends AtomicReference<WaitNode> {
        volatile Thread mWaiter;
        volatile boolean mDenied;

        // Only set if node was deleted and must be bypassed when a new node is enqueued.
        volatile WaitNode mPrev;

        /**
         * @return true if timed out or interrupted
         */
        boolean park(AltLatch latch) {
            LockSupport.park(latch);
            return false;
        }

        /**
         * @return <0 if thread should park; 0 if acquired and node should also be removed; >0
         * if acquired and node should not be removed
         */
        int acquire(AltLatch latch) {
            int trials = 0;
            while (true) {
                int state = latch.mLatchState;
                if (state < 0) {
                    return state;
                }

                // Try to acquire exclusive latch, or at least deny new shared latches.
                if (UNSAFE.compareAndSwapInt(latch, STATE_OFFSET, state, state | EXCLUSIVE)) {
                    if (state == 0) {
                        // Acquired, so no need to reference the thread anymore.
                        UNSAFE.putOrderedObject(this, WAITER_OFFSET, null);
                        return state;
                    } else {
                        return -1;
                    }
                }

                trials = spin(trials);
            }
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            Utils.appendMiniString(b, this);
            b.append(" {waiter=").append(mWaiter);
            b.append(", denied=").append(mDenied);
            b.append(", next="); Utils.appendMiniString(b, get());
            return b.append('}').toString();
        }
    }

    /*
    static class Timed extends WaitNode {
        private long mNanosTimeout;
        private long mEndNanos;

        Timed(long nanosTimeout) {
            mNanosTimeout = nanosTimeout;
            if (nanosTimeout >= 0) {
                mEndNanos = System.nanoTime() + nanosTimeout;
            }
        }

        @Override
        final boolean park(AltLatch latch) {
            if (mNanosTimeout < 0) {
                LockSupport.park(latch);
                return Thread.currentThread().isInterrupted();
            } else {
                LockSupport.parkNanos(latch, mNanosTimeout);
                if (Thread.currentThread().isInterrupted()) {
                    return true;
                }
                return (mNanosTimeout = mEndNanos - System.nanoTime()) <= 0;
            }
        }
    }
    */

    static class Shared extends WaitNode {
        @Override
        final int acquire(AltLatch latch) {
            int trials = 0;
            while (true) {
                int state = latch.mLatchState;
                if (state < 0) {
                    return state;
                }

                if (UNSAFE.compareAndSwapInt(latch, STATE_OFFSET, state, state + 1)) {
                    // Acquired, so no need to reference the thread anymore.
                    Thread waiter = mWaiter;
                    if (waiter == null ||
                        !UNSAFE.compareAndSwapObject(this, WAITER_OFFSET, waiter, null))
                    {
                        // Handoff was actually fair, and now an extra shared latch must be
                        // released.
                        if (state < 1) {
                            throw new AssertionError(state);
                        }
                        if (!UNSAFE.compareAndSwapInt(latch, STATE_OFFSET, state + 1, state)) {
                            UNSAFE.getAndAddInt(latch, STATE_OFFSET, -1);
                        }
                        // Already removed from the queue.
                        return 1;
                    }

                    // Only remove node if this thread is the first shared latch owner. This
                    // guarantees that no other thread will be concurrently removing nodes.
                    // Nodes for other threads will have their nodes removed later, as latches
                    // are released. Early removal is a garbage collection optimization.
                    return state;
                }

                trials = spin(trials);
            }
        }
    }

    /*
    static class TimedShared extends Shared {
        private long mNanosTimeout;
        private long mEndNanos;

        TimedShared(long nanosTimeout) {
            mNanosTimeout = nanosTimeout;
            if (nanosTimeout >= 0) {
                mEndNanos = System.nanoTime() + nanosTimeout;
            }
        }

        @Override
        final boolean park(AltLatch latch) {
            if (mNanosTimeout < 0) {
                LockSupport.park(latch);
                return Thread.currentThread().isInterrupted();
            } else {
                LockSupport.parkNanos(latch, mNanosTimeout);
                if (Thread.currentThread().isInterrupted()) {
                    return true;
                }
                return (mNanosTimeout = mEndNanos - System.nanoTime()) <= 0;
            }
        }
    }
    */
}
