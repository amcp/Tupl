/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.io;

import java.io.InterruptedIOException;
import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.EnumSet;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.cojen.tupl.util.Latch;
import org.cojen.tupl.util.RWLock;

import static org.cojen.tupl.io.Utils.rethrow;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@SuppressWarnings("restriction")
abstract class AbstractFileIO extends FileIO {
    private static final int PAGE_SIZE;

    private static final int MAPPING_SHIFT = 30;
    private static final int MAPPING_SIZE = 1 << MAPPING_SHIFT;

    // If sync is taking longer than 10 seconds, start slowing down access.
    private static final long SYNC_YIELD_THRESHOLD_NANOS = 10L * 1000 * 1000 * 1000;

    // Max amount of time to stall access if sync is taking longer than the threshold above.
    private static final long SYNC_YIELD_MAX_NANOS = 100L * 1000 * 1000;

    private static final AtomicLongFieldUpdater<AbstractFileIO> cSyncStartNanosUpdater =
        AtomicLongFieldUpdater.newUpdater(AbstractFileIO.class, "mSyncStartNanos");

    static {
        int pageSize = 4096;
        try {
            pageSize = UnsafeAccess.tryObtain().pageSize();
        } catch (Throwable e) {
            // Ignore. Use default value.
        }
        PAGE_SIZE = pageSize;
    }

    private final boolean mReadOnly;
    private final Latch mRemapLatch;
    protected final RWLock mAccessLock;
    private final Latch mSyncLatch;
    private Mapping[] mMappings;
    private int mLastMappingSize;
    protected volatile Throwable mCause;

    private volatile long mSyncStartNanos;

    AbstractFileIO(EnumSet<OpenOption> options) {
        mReadOnly = options.contains(OpenOption.READ_ONLY);
        mRemapLatch = new Latch();
        mAccessLock = new RWLock();
        mSyncLatch = new Latch();
    }

    @Override
    public final boolean isReadOnly() {
        return mReadOnly;
    }

    @Override
    public final long length() throws IOException {
        mAccessLock.acquireShared();
        try {
            return doLength();
        } catch (IOException e) {
            throw rethrow(e, mCause);
        } finally {
            mAccessLock.releaseShared();
        }
    }

    @Override
    public final void setLength(long length, LengthOption option) throws IOException {
        mRemapLatch.acquireExclusive();
        try {
            final long prevLength = length();

            // Length reduction screws up the mapping on Linux, causing a hard
            // process crash when accessing anything beyond the file length.
            boolean remap = mMappings != null && length < prevLength;

            // Windows will ignore the length reduction entirely to prevent the crash. Need to
            // explicitly unmap first.
            if (remap) {
                doUnmap(true);
            }

            try {
                Throwable ex = null;

                if (length > prevLength && shouldPreallocate(option)) {
                    // Increasing the file length. Assume that blocks up to the
                    // previous length have already been allocated, and try and 
                    // preallocate for the extended range from prevLength to new length.
                    try {
                        doPreallocate(prevLength, length - prevLength);
                    } catch (Throwable e) {
                        ex = e;
                        // Rollback any partial allocation.
                        length = prevLength;
                    }
                }

                mAccessLock.acquireShared();
                try {
                    doSetLength(length);
                } finally {
                    mAccessLock.releaseShared();
                }

                if (ex != null) {
                    throw Utils.rethrow(ex);
                }
            } catch (IOException e) {
                // Ignore.
            } finally {
                if (remap) {
                    doMap(true);
                }
            }
        } finally {
            mRemapLatch.releaseExclusive();
        }
    }

    @Override
    public final void read(long pos, byte[] buf, int offset, int length) throws IOException {
        access(true, pos, buf, offset, length);
    }

    @Override
    public final void read(long pos, ByteBuffer bb) throws IOException {
        access(true, pos, bb);
    }

    @Override
    public final void read(long pos, long ptr, int offset, int length) throws IOException {
        access(true, pos, ptr + offset, length);
    }

    @Override
    public final void write(long pos, byte[] buf, int offset, int length) throws IOException {
        access(false, pos, buf, offset, length);
    }

    @Override
    public final void write(long pos, ByteBuffer bb) throws IOException {
        access(false, pos, bb);
    }

    @Override
    public final void write(long pos, long ptr, int offset, int length) throws IOException {
        access(false, pos, ptr + offset, length);
    }

    private void access(boolean read, long pos, byte[] buf, int offset, int length)
        throws IOException
    {
        syncWait();

        try {
            mAccessLock.acquireShared();
            try {
                Mapping[] mappings = mMappings;
                if (mappings != null) {
                    while (true) {
                        int mi = (int) (pos >> MAPPING_SHIFT);
                        int mlen = mappings.length;
                        if (mi >= mlen) {
                            break;
                        }

                        Mapping mapping = mappings[mi];
                        int mpos = (int) (pos & (MAPPING_SIZE - 1));
                        int mavail;

                        if (mi == (mlen - 1)) {
                            mavail = mLastMappingSize - mpos;
                            if (mavail <= 0) {
                                break;
                            }
                        } else {
                            mavail = MAPPING_SIZE - mpos;
                        }

                        if (mavail > length) {
                            mavail = length;
                        }

                        if (read) {
                            mapping.read(mpos, buf, offset, mavail);
                        } else {
                            mapping.write(mpos, buf, offset, mavail);
                        }

                        length -= mavail;
                        if (length <= 0) {
                            return;
                        }

                        pos += mavail;
                        offset += mavail;
                    }
                }

                if (read) {
                    doRead(pos, buf, offset, length);
                } else {
                    doWrite(pos, buf, offset, length);
                }
            } finally {
                mAccessLock.releaseShared();
            }
        } catch (IOException e) {
            throw rethrow(e, mCause);
        }
    }

    private void access(boolean read, long pos, ByteBuffer bb) throws IOException {
        if (bb.remaining() <= 0) {
            return;
        }

        syncWait();

        try {
            mAccessLock.acquireShared();
            try {
                Mapping[] mappings = mMappings;
                if (mappings != null) {
                    while (true) {
                        int mi = (int) (pos >> MAPPING_SHIFT);
                        int mlen = mappings.length;
                        if (mi >= mlen) {
                            break;
                        }

                        Mapping mapping = mappings[mi];
                        int mpos = (int) (pos & (MAPPING_SIZE - 1));
                        int mavail;

                        if (mi == (mlen - 1)) {
                            mavail = mLastMappingSize - mpos;
                            if (mavail <= 0) {
                                break;
                            }
                        } else {
                            mavail = MAPPING_SIZE - mpos;
                        }

                        if (mavail >= bb.remaining()) {
                            if (read) {
                                mapping.read(mpos, bb);
                            } else {
                                mapping.write(mpos, bb);
                            }
                            return;
                        }

                        int limit = bb.limit();
                        bb.limit(bb.position() + mavail);
                        try {
                            if (read) {
                                mapping.read(mpos, bb);
                            } else {
                                mapping.write(mpos, bb);
                            }
                        } finally {
                            bb.limit(limit);
                        }

                        pos += mavail;
                    }
                }

                if (read) {
                    doRead(pos, bb);
                } else {
                    doWrite(pos, bb);
                }
            } finally {
                mAccessLock.releaseShared();
            }

        } catch (IOException e) {
            throw rethrow(e, mCause);
        }
    }

    private void access(boolean read, long pos, long ptr, int length) throws IOException {
        if (length > 0) {
            access(read, pos, DirectAccess.ref(ptr, length));
        }
    }

    @Override
    public final void sync(boolean metadata) throws IOException {
        if (mReadOnly) {
            return;
        }

        // Set the start time if there's not already an ongoing sync. Ignore
        // cas fails; first writer wins.
        long startNs = mSyncStartNanos;
        boolean shouldReset = startNs == 0 && 
            cSyncStartNanosUpdater.compareAndSet(this, startNs, System.nanoTime());
        try {
            mSyncLatch.acquireShared();
            try {
                mAccessLock.acquireShared();
                try {
                    Mapping[] mappings = mMappings;
                    if (mappings != null) {
                        for (Mapping m : mappings) {
                            // Save metadata sync for last.
                            m.sync(false);
                        }
                    }

                    doSync(metadata);
                } finally {
                    mAccessLock.releaseShared();
                }
            } catch (IOException e) {
                throw rethrow(e, mCause);
            } finally {
                mSyncLatch.releaseShared();
            }
        } finally {
            // Reset sync state to unblock read/write ops.
            if (shouldReset) {
                cSyncStartNanosUpdater.set(this, 0);
            }
        }
    }

    @Override
    public final void map() throws IOException {
        mRemapLatch.acquireExclusive();
        try {
            doMap(false);
        } finally {
            mRemapLatch.releaseExclusive();
        }
    }

    @Override
    public final void remap() throws IOException {
        mRemapLatch.acquireExclusive();
        try {
            doMap(true);
        } finally {
            mRemapLatch.releaseExclusive();
        }
    }

    @Override
    public final void unmap() throws IOException {
        unmap(true);
    }

    protected void unmap(boolean reopen) throws IOException {
        mRemapLatch.acquireExclusive();
        try {
            doUnmap(reopen);
        } finally {
            mRemapLatch.releaseExclusive();
        }
    }

    // Caller must hold mRemapLatch exclusively.
    private void doUnmap(boolean reopen) throws IOException {
        boolean contended = mAccessLock.isContended();
        mAccessLock.acquireExclusive();
        try {
            Mapping[] mappings = mMappings;
            if (mappings == null) {
                return;
            }

            mMappings = null;
            mLastMappingSize = 0;

            IOException ex = null;
            for (Mapping m : mappings) {
                ex = Utils.closeQuietly(ex, m);
            }

            // Need to replace all the open files. There's otherwise no guarantee that any
            // changes to the mapped files will be visible.

            if (reopen) {
                try {
                    reopen();
                } catch (IOException e) {
                    if (ex == null) {
                        ex = e;
                    }
                }
            }

            if (ex != null) {
                throw ex;
            }
        } finally {
            mAccessLock.releaseExclusive(contended);
        }
    }

    // Caller must hold mRemapLatch exclusively.
    private void doMap(boolean remap) throws IOException {
        Mapping[] oldMappings;
        int oldMappingDiscardPos;
        Mapping[] newMappings;
        int newLastSize;

        mAccessLock.acquireShared();
        try {
            oldMappings = mMappings;
            if (oldMappings == null && remap) {
                // Don't map unless already mapped.
                return;
            }

            long length = length();

            if (oldMappings != null) {
                long oldMappedLength = oldMappings.length == 0 ? 0 :
                    (oldMappings.length - 1) * (long) MAPPING_SIZE + mLastMappingSize;
                if (length == oldMappedLength) {
                    return;
                }
            }

            long count = (length + (MAPPING_SIZE - 1)) / MAPPING_SIZE;

            if (count > Integer.MAX_VALUE) {
                throw new IOException("Mapping is too large");
            }

            try {
                newMappings = new Mapping[(int) count];
            } catch (OutOfMemoryError e) {
                throw new IOException("Mapping is too large");
            }

            oldMappings = mMappings;
            oldMappingDiscardPos = 0;

            int i = 0;
            long pos = 0;

            if (oldMappings != null && oldMappings.length > 0) {
                i = oldMappings.length;
                if (mLastMappingSize != MAPPING_SIZE) {
                    i--;
                    oldMappingDiscardPos = i;
                }
                System.arraycopy(oldMappings, 0, newMappings, 0, i);
                pos = i * (long) MAPPING_SIZE;
            }

            while (i < count - 1) {
                newMappings[i++] = openMapping(mReadOnly, pos, MAPPING_SIZE);
                pos += MAPPING_SIZE;
            }

            if (count == 0) {
                newLastSize = 0;
            } else {
                newLastSize = (int) (MAPPING_SIZE - (count * MAPPING_SIZE - length));
                newMappings[i] = openMapping(mReadOnly, pos, newLastSize);
            }
        } finally {
            mAccessLock.releaseShared();
        }

        boolean contended = mAccessLock.isContended();
        mAccessLock.acquireExclusive();
        mMappings = newMappings;
        mLastMappingSize = newLastSize;
        mAccessLock.releaseExclusive(contended);

        if (oldMappings != null) {
            IOException ex = null;
            while (oldMappingDiscardPos < oldMappings.length) {
                ex = Utils.closeQuietly(ex, oldMappings[oldMappingDiscardPos++]);
            }
            if (ex != null) {
                throw ex;
            }
        }
    }
 
    protected void syncWait() throws InterruptedIOException {
        long syncStartNanos;
        if ((syncStartNanos = mSyncStartNanos) != 0) {
            long syncTimeNanos = System.nanoTime() - syncStartNanos;
            if (syncTimeNanos > SYNC_YIELD_THRESHOLD_NANOS) {
                // Yield 1ms for each second that sync has been running. Use a latch instead
                // of a sleep, preventing prolonged sleep after sync finishes.
                long sleepNanos = Math.min(syncTimeNanos / 1000L, SYNC_YIELD_MAX_NANOS);
                try {
                    if (mSyncLatch.tryAcquireExclusiveNanos(sleepNanos)) {
                        mSyncLatch.releaseExclusive();
                    }
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        }
    }

    protected boolean shouldPreallocate(LengthOption option) {
        return option == LengthOption.PREALLOCATE_ALWAYS;
    }

    /**
     * Preallocates blocks to the file. This call ensures that disk space is allocated 
     * for this file for the bytes in the range starting at offset and continuing for 
     * length bytes.  Subsequent writes to the specified range are guaranteed not to 
     * fail because of lack of disk space.
     *
     * @param pos zero-based position in file.
     * @param length amount of bytes to preallocate starting at pos.
     * @throws IllegalArgumentException
     */
    protected void doPreallocate(long pos, long length) throws IOException {
        mAccessLock.acquireExclusive();
        try {
            // Expecting block size to be >= page size. If block size is smaller than page 
            // size then this will not touch all the necessary blocks.
            final long currLength = doLength();
            byte[] buf = new byte[1];
            for (long endPos = pos + length; pos < endPos; pos += PAGE_SIZE) {
                // In order not to be destructive to existing data we read the byte
                // at the given offset. If it is non-zero then assume the block 
                // must have been allocated already.
                if (pos < currLength) {
                    doRead(pos, buf, 0, 1);

                    if (buf[0] != 0) {
                        continue;
                    }
                }

                // Found zero byte. Either data at pos is really zero, or the block has not been 
                // allocated yet. Overwrite with zero again to force any block allocation. 
                doWrite(pos, buf, 0, buf.length);
            }
        } finally {
            mAccessLock.releaseExclusive();
        }
    }

    protected abstract long doLength() throws IOException;

    protected abstract void doSetLength(long length) throws IOException;

    protected abstract void doRead(long pos, byte[] buf, int offset, int length)
        throws IOException;

    protected abstract void doRead(long pos, ByteBuffer bb)
        throws IOException;

    protected abstract void doRead(long pos, long ptr, int length)
        throws IOException;

    protected abstract void doWrite(long pos, byte[] buf, int offset, int length)
        throws IOException;

    protected abstract void doWrite(long pos, ByteBuffer bb)
        throws IOException;

    protected abstract void doWrite(long pos, long ptr, int length)
        throws IOException;

    protected abstract Mapping openMapping(boolean readOnly, long pos, int size)
        throws IOException;

    protected abstract void reopen() throws IOException;

    protected abstract void doSync(boolean metadata) throws IOException;
}
