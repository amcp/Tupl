/*
 *  Copyright 2012-2015 Cojen.org
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

import java.io.IOException;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Mapping of keys to values, in no particular order. Subclasses and
 * implementations may specify an explicit ordering.
 *
 * @author Brian S O'Neill
 * @see Database
 */
public interface View {
    /**
     * Returns the key ordering for this view.
     */
    public Ordering getOrdering();

    /**
     * Returns {@link java.util.Spliterator Spliterator} characteristics for this view.
     */
    public default int characteristics() {
        return 0;
    }

    /**
     * Returns a comparator for the ordering of this view, never null.
     *
     * @throws IllegalStateException if view is unordered
     */
    public default Comparator<byte[]> getComparator() {
        throw new IllegalStateException();
    }

    /**
     * @param txn optional transaction for Cursor to {@link Cursor#link link} to
     * @return a new unpositioned cursor
     * @throws IllegalArgumentException if transaction belongs to another database instance
     */
    public Cursor newCursor(Transaction txn);

    /**
     * Return a new scanner which receives keys and values.
     *
     * @param txn optional transaction for Scanner to use
     * @return a new scanner positioned at the first entry in the view
     * @throws IllegalArgumentException if transaction belongs to another database instance
     */
    public default Scanner newScanner(Transaction txn) throws IOException {
        return new ViewScanner(this, newCursor(txn));
    }

    /**
     * Return a new scanner which receives keys only. The value portion given to entry
     * consumers is always {@link Cursor#NOT_LOADED NOT_LOADED}.
     *
     * @param txn optional transaction for Scanner to use
     * @return a new scanner positioned at the first entry in the view
     * @throws IllegalArgumentException if transaction belongs to another database instance
     */
    public default Scanner newScannerNoValues(Transaction txn) throws IOException {
        Cursor c = newCursor(txn);
        c.autoload(false);
        return new ViewScanner(this, c);
    }

    /**
     * Return a new updater which receives keys and values.
     *
     * @param txn optional transaction for Updater to use
     * @return a new updater positioned at the first entry in the view
     * @throws IllegalArgumentException if transaction belongs to another database instance
     */
    public default Updater newUpdater(Transaction txn) throws IOException {
        return new ViewUpdater(this, newCursor(txn));
    }

    /**
     * Return a new updater which receives keys only. The value portion given to entry
     * functions is always {@link Cursor#NOT_LOADED NOT_LOADED}.
     *
     * @param txn optional transaction for Updater to use
     * @return a new updater positioned at the first entry in the view
     * @throws IllegalArgumentException if transaction belongs to another database instance
     */
    public default Updater newUpdaterNoValues(Transaction txn) throws IOException {
        Cursor c = newCursor(txn);
        c.autoload(false);
        return new ViewUpdater(this, c);
    }

    /**
     * Returns a new transaction which is compatible with this view. If the provided durability
     * mode is null, a default mode is selected.
     *
     * @throws UnsupportedOperationException if not supported
     */
    public default Transaction newTransaction(DurabilityMode durabilityMode) {
        throw new UnsupportedOperationException();
    }

    /**
     * Non-transactionally counts the number of entries within the given range. Implementations
     * of this method typically scan over the entries, and so it shouldn't be expected to run
     * in constant time.
     *
     * @param lowKey inclusive lowest key in the counted range; pass null for open range
     * @param highKey exclusive highest key in the counted range; pass null for open range
     */
    public default long count(byte[] lowKey, byte[] highKey) throws IOException {
        return ViewUtils.count(this, false, lowKey, highKey);
    }

    /**
     * Non-transactionally estimates of the number of entries within the given range. Returns
     * MAX_VALUE if infinite, unknown, or too expensive to compute.
     *
     * @param lowKey inclusive lowest key in the size range; pass null for open range
     * @param highKey exclusive highest key in the size range; pass null for open range
     * @param quality &le; 1 for lowest quality, 2+ for higher quality, etc.
     */
    public default long estimateSize(byte[] lowKey, byte[] highKey, int quality)
        throws IOException
    {
        return Long.MAX_VALUE;
    }

    /**
     * Returns a copy of the value for the given key, or null if no matching entry exists.
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for {@link
     * LockMode#READ_COMMITTED READ_COMMITTED} locking behavior
     * @param key non-null key
     * @return copy of value, or null if entry doesn't exist
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     */
    public default byte[] load(Transaction txn, byte[] key) throws IOException {
        Cursor c = newCursor(txn);
        try {
            c.find(key);
            return c.value();
        } finally {
            c.reset();
        }
    }

    /**
     * Unconditionally associates a value with the given key.
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @param value value to store; pass null to delete
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if entry is not permitted
     */
    public default void store(Transaction txn, byte[] key, byte[] value) throws IOException {
        Cursor c = newCursor(txn);
        try {
            c.autoload(false);
            c.find(key);
            c.store(value);
        } finally {
            c.reset();
        }
    }

    /**
     * Unconditionally associates a value with the given key, returning the previous value.
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @param value value to store; pass null to delete
     * @return copy of previous value, or null if none
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if entry is not permitted
     */
    public default byte[] exchange(Transaction txn, byte[] key, byte[] value) throws IOException {
        Cursor c = newCursor(txn);
        try {
            c.find(key);
            byte[] old = c.value();
            c.store(value);
            return old;
        } finally {
            c.reset();
        }
    }

    /**
     * Associates a value with the given key, unless a corresponding value already
     * exists. Equivalent to: <code>update(txn, key, null, value)</code>
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @param value value to insert, which can be null
     * @return false if entry already exists
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if entry is not permitted
     */
    public default boolean insert(Transaction txn, byte[] key, byte[] value) throws IOException {
        return update(txn, key, null, value);
    }

    /**
     * Associates a value with the given key, but only if a corresponding value already exists.
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @param value value to insert; pass null to delete
     * @return false if no existing entry
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if entry is not permitted
     */
    public default boolean replace(Transaction txn, byte[] key, byte[] value) throws IOException {
        Cursor c = newCursor(txn);
        try {
            c.autoload(false);
            c.find(key);
            if (c.value() == null) {
                return false;
            }
            c.store(value);
            return true;
        } finally {
            c.reset();
        }
    }

    /**
     * Associates a value with the given key, but only if the given old value matches.
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @param oldValue expected existing value, which can be null
     * @param newValue new value to update to; pass null to delete
     * @return false if existing value doesn't match
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if entry is not permitted
     */
    public default boolean update(Transaction txn, byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        Cursor c = newCursor(txn);
        try {
            c.autoload(oldValue != null);
            c.find(key);
            if (!Arrays.equals(c.value(), oldValue)) {
                return false;
            }
            c.store(newValue);
            return true;
        } finally {
            c.reset();
        }
    }

    /**
     * Unconditionally removes the entry associated with the given key. Equivalent to:
     * <code>replace(txn, key, null)</code>
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @return false if no existing entry
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if remove is not permitted
     */
    public default boolean delete(Transaction txn, byte[] key) throws IOException {
        return replace(txn, key, null);
    }

    /**
     * Removes the entry associated with the given key, but only if the given value
     * matches. Equivalent to: <code>update(txn, key, value, null)</code>
     *
     * <p>If the entry must be locked, ownership of the key instance is transferred. The key
     * must not be modified after calling this method.
     *
     * @param txn optional transaction; pass null for auto-commit mode
     * @param key non-null key
     * @param value expected existing value, which can be null
     * @return false if existing value doesn't match
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if transaction belongs to another database instance
     * @throws ViewConstraintException if remove is not permitted
     */
    public default boolean remove(Transaction txn, byte[] key, byte[] value) throws IOException {
        return update(txn, key, value, null);
    }

    /**
     * Explicitly acquire a shared lock for the given key, denying exclusive locks. Lock is
     * retained until the end of the transaction or scope.
     *
     * <p>Transactions acquire locks automatically, and so use of this method is not
     * required. Ownership of the key instance is transferred, and so the key must not be
     * modified after calling this method.
     *
     * @param key non-null key to lock; instance is not cloned
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#INTERRUPTED INTERRUPTED}, {@link
     * LockResult#TIMED_OUT_LOCK TIMED_OUT_LOCK}, {@link LockResult#ACQUIRED
     * ACQUIRED}, {@link LockResult#OWNED_SHARED OWNED_SHARED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws IllegalStateException if too many shared locks
     * @throws DeadlockException if deadlock was detected after waiting full timeout
     * @throws ViewConstraintException if key is not allowed
     */
    public default LockResult tryLockShared(Transaction txn, byte[] key, long nanosTimeout)
        throws DeadlockException, ViewConstraintException
    {
        return ViewUtils.tryLock(txn, key, nanosTimeout, this::lockShared);
    }

    /**
     * Explicitly acquire a shared lock for the given key, denying exclusive locks. Lock is
     * retained until the end of the transaction or scope.
     *
     * <p>Transactions acquire locks automatically, and so use of this method is not
     * required. Ownership of the key instance is transferred, and so the key must not be
     * modified after calling this method.
     *
     * @param key non-null key to lock; instance is not cloned
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link LockResult#OWNED_SHARED
     * OWNED_SHARED}, {@link LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws IllegalStateException if too many shared locks
     * @throws LockFailureException if interrupted or timed out
     * @throws DeadlockException if deadlock was detected after waiting full timeout
     * @throws ViewConstraintException if key is not allowed
     */
    public LockResult lockShared(Transaction txn, byte[] key)
        throws LockFailureException, ViewConstraintException;

    /**
     * Explicitly acquire an upgradable lock for the given key, denying exclusive and
     * additional upgradable locks. Lock is retained until the end of the transaction or scope.
     *
     * <p>Transactions acquire locks automatically, and so use of this method is not
     * required. Ownership of the key instance is transferred, and so the key must not be
     * modified after calling this method.
     *
     * @param key non-null key to lock; instance is not cloned
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ILLEGAL ILLEGAL}, {@link
     * LockResult#INTERRUPTED INTERRUPTED}, {@link LockResult#TIMED_OUT_LOCK
     * TIMED_OUT_LOCK}, {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws DeadlockException if deadlock was detected after waiting full timeout
     * @throws ViewConstraintException if key is not allowed
     */
    public default LockResult tryLockUpgradable(Transaction txn, byte[] key, long nanosTimeout)
        throws DeadlockException, ViewConstraintException
    {
        return ViewUtils.tryLock(txn, key, nanosTimeout, this::lockUpgradable);
    }

    /**
     * Explicitly acquire an upgradable lock for the given key, denying exclusive and
     * additional upgradable locks. Lock is retained until the end of the transaction or scope.
     *
     * <p>Transactions acquire locks automatically, and so use of this method is not
     * required. Ownership of the key instance is transferred, and so the key must not be
     * modified after calling this method.
     *
     * @param key non-null key to lock; instance is not cloned
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link LockResult#OWNED_UPGRADABLE
     * OWNED_UPGRADABLE}, or {@link LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws LockFailureException if interrupted, timed out, or illegal upgrade
     * @throws DeadlockException if deadlock was detected after waiting full timeout
     * @throws ViewConstraintException if key is not allowed
     */
    public LockResult lockUpgradable(Transaction txn, byte[] key)
        throws LockFailureException, ViewConstraintException;

    /**
     * Explicitly acquire an exclusive lock for the given key, denying any additional
     * locks. Lock is retained until the end of the transaction or scope.
     *
     * <p>Transactions acquire locks automatically, and so use of this method is not
     * required. Ownership of the key instance is transferred, and so the key must not be
     * modified after calling this method.
     *
     * @param key non-null key to lock; instance is not cloned
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ILLEGAL ILLEGAL}, {@link
     * LockResult#INTERRUPTED INTERRUPTED}, {@link LockResult#TIMED_OUT_LOCK
     * TIMED_OUT_LOCK}, {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#UPGRADED UPGRADED}, or {@link LockResult#OWNED_EXCLUSIVE
     * OWNED_EXCLUSIVE}
     * @throws DeadlockException if deadlock was detected after waiting full timeout
     * @throws ViewConstraintException if key is not allowed
     */
    public default LockResult tryLockExclusive(Transaction txn, byte[] key, long nanosTimeout)
        throws DeadlockException, ViewConstraintException
    {
        return ViewUtils.tryLock(txn, key, nanosTimeout, this::lockExclusive);
    }

    /**
     * Explicitly acquire an exclusive lock for the given key, denying any additional
     * locks. Lock is retained until the end of the transaction or scope.
     *
     * <p>Transactions acquire locks automatically, and so use of this method is not
     * required. Ownership of the key instance is transferred, and so the key must not be
     * modified after calling this method.
     *
     * @param key non-null key to lock; instance is not cloned
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link LockResult#UPGRADED UPGRADED}, or
     * {@link LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws LockFailureException if interrupted, timed out, or illegal upgrade
     * @throws DeadlockException if deadlock was detected after waiting full timeout
     * @throws ViewConstraintException if key is not allowed
     */
    public LockResult lockExclusive(Transaction txn, byte[] key)
        throws LockFailureException, ViewConstraintException;

    /**
     * Checks the lock ownership for the given key.
     *
     * @return {@link LockResult#UNOWNED UNOWNED}, {@link LockResult#OWNED_SHARED
     * OWNED_SHARED}, {@link LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws ViewConstraintException if key is not allowed
     */
    public LockResult lockCheck(Transaction txn, byte[] key) throws ViewConstraintException;

    /**
     * Returns an unopened stream for accessing values in this view.
     */
    /*
    public default Stream newStream() {
        throw new UnsupportedOperationException();
    }
    */

    /**
     * Returns a sub-view, backed by this one, whose keys are greater than or
     * equal to the given key. Ownership of the key instance is transferred,
     * and so it must not be modified after calling this method.
     *
     * <p>The returned view will throw a {@link ViewConstraintException} on an attempt to
     * insert a key outside its range.
     *
     * @throws UnsupportedOperationException if view is unordered
     * @throws NullPointerException if key is null
     */
    public default View viewGe(byte[] key) {
        Ordering ordering = getOrdering();
        if (ordering == Ordering.ASCENDING) {
            return BoundedView.viewGe(this, key);
        } else if (ordering == Ordering.DESCENDING) {
            return BoundedView.viewGe(viewReverse(), key).viewReverse();
        } else {
            throw new UnsupportedOperationException("Unsupported ordering: " + ordering);
        }
    }

    /**
     * Returns a sub-view, backed by this one, whose keys are greater than the
     * given key. Ownership of the key instance is transferred, and so it must
     * not be modified after calling this method.
     *
     * <p>The returned view will throw a {@link ViewConstraintException} on an attempt to
     * insert a key outside its range.
     *
     * @throws UnsupportedOperationException if view is unordered
     * @throws NullPointerException if key is null
     */
    public default View viewGt(byte[] key) {
        Ordering ordering = getOrdering();
        if (ordering == Ordering.ASCENDING) {
            return BoundedView.viewGt(this, key);
        } else if (ordering == Ordering.DESCENDING) {
            return BoundedView.viewGt(viewReverse(), key).viewReverse();
        } else {
            throw new UnsupportedOperationException("Unsupported ordering: " + ordering);
        }
    }

    /**
     * Returns a sub-view, backed by this one, whose keys are less than or
     * equal to the given key. Ownership of the key instance is transferred,
     * and so it must not be modified after calling this method.
     *
     * <p>The returned view will throw a {@link ViewConstraintException} on an attempt to
     * insert a key outside its range.
     *
     * @throws UnsupportedOperationException if view is unordered
     * @throws NullPointerException if key is null
     */
    public default View viewLe(byte[] key) {
        Ordering ordering = getOrdering();
        if (ordering == Ordering.ASCENDING) {
            return BoundedView.viewLe(this, key);
        } else if (ordering == Ordering.DESCENDING) {
            return BoundedView.viewLe(viewReverse(), key).viewReverse();
        } else {
            throw new UnsupportedOperationException("Unsupported ordering: " + ordering);
        }
    }

    /**
     * Returns a sub-view, backed by this one, whose keys are less than the
     * given key. Ownership of the key instance is transferred, and so it must
     * not be modified after calling this method.
     *
     * <p>The returned view will throw a {@link ViewConstraintException} on an attempt to
     * insert a key outside its range.
     *
     * @throws UnsupportedOperationException if view is unordered
     * @throws NullPointerException if key is null
     */
    public default View viewLt(byte[] key) {
        Ordering ordering = getOrdering();
        if (ordering == Ordering.ASCENDING) {
            return BoundedView.viewLt(this, key);
        } else if (ordering == Ordering.DESCENDING) {
            return BoundedView.viewLt(viewReverse(), key).viewReverse();
        } else {
            throw new UnsupportedOperationException("Unsupported ordering: " + ordering);
        }
    }

    /**
     * Returns a sub-view, backed by this one, whose keys start with the given prefix.
     * Ownership of the prefix instance is transferred, and so it must not be modified after
     * calling this method.
     *
     * <p>The returned view will throw a {@link ViewConstraintException} on an attempt to
     * insert a key outside its range.
     *
     * @param trim amount of prefix length to trim from all keys in the view
     * @throws UnsupportedOperationException if view is unordered
     * @throws NullPointerException if prefix is null
     * @throws IllegalArgumentException if trim is longer than prefix
     */
    public default View viewPrefix(byte[] prefix, int trim) {
        Ordering ordering = getOrdering();
        if (ordering == Ordering.ASCENDING) {
            return BoundedView.viewPrefix(this, prefix, trim);
        } else if (ordering == Ordering.DESCENDING) {
            return BoundedView.viewPrefix(viewReverse(), prefix, trim).viewReverse();
        } else {
            throw new UnsupportedOperationException("Unsupported ordering: " + ordering);
        }
    }

    /**
     * Returns a sub-view, backed by this one, whose entries have been filtered out and
     * transformed.
     *
     * <p>The returned view will throw a {@link ViewConstraintException} on an attempt to
     * insert an entry not supported by the transformer.
     *
     * @throws NullPointerException if transformer is null
     */
    public default View viewTransformed(Transformer transformer) {
        return TransformedView.apply(this, transformer);
    }

    /**
     * Returns a view which combines the entries of this view, with the others that are given.
     * A union eliminates duplicate keys, relying on a combiner to decide how to deal with
     * them. If the set of views in the union don't follow a consistent ordering, then
     * elimination of duplicates doesn't work correctly. The actual behavior is undefined.
     *
     * <p>Storing entries in the union is permitted, but final entries are only stored in the
     * first view (this view). As a side-effect of any successful store operation, the
     * corresponding entries in the other views are always deleted. The order in which the
     * underlying operations are performed permits a union to be composed of views which can
     * map to the same underlying source index. The entire operation is transactional, and so
     * stores to unions don't support the {@link Transaction#BOGUS "bogus"} transaction.
     *
     * <p>Unions don't support cursors. For this reason, unions are typically applied last when
     * building a view processing pipeline. Also, avoiding creating unions which are composed
     * of other unions. Such a pipeline might be less efficient than one composed with a single
     * union step.
     *
     * @param combiner combines values together; pass null to always favor the first
     */
    // FIXME: Storing into the union is very odd, especially considering that the combiner can
    // do anything. Only support delete, but only if the key is in the union result set. --
    // then delete the key from all sources.
    public default View viewUnion(Combiner combiner, View... others) {
        if (others.length == 0) {
            return this;
        }
        if (combiner == null) {
            combiner = Combiner.first();
        }
        View[] sources = new View[1 + others.length];
        sources[0] = this;
        System.arraycopy(others, 0, sources, 1, others.length);
        return new UnionView(combiner, sources);
    }

    /**
     * Returns a view consisting of entries that exist this view and all the others that are
     * given. An intersection only selects duplicate keys, relying on a combiner to decide how
     * to deal with them. If the set of views in the intersection don't follow a consistent
     * ordering, then selection of duplicates doesn't work correctly. The actual behavior is
     * undefined.
     *
     * <p>Storing entries in the intersection is permitted, by storing into all the views. The
     * order in which the underlying operations are performed permits a intersection to be
     * composed of views which can map to the same underlying source index. The entire
     * operation is transactional, and so stores to intersections don't support the {@link
     * Transaction#BOGUS "bogus"} transaction.
     *
     * <p>Intersections don't support cursors. For this reason, intersections are typically
     * applied last when building a view processing pipeline. Also, avoiding creating
     * intersections which are composed of other intersections. Such a pipeline might be less
     * efficient than one composed with a single intersection step.
     *
     * @param combiner combines values together; pass null to always favor the first
     */
    // FIXME: Storing into the intersection is very odd, especially considering that the
    // combiner can do anything. Only support delete, but only if the key is in the
    // intersection result set. -- then delete the key from all sources.
    public default View viewIntersection(Combiner combiner, View... others) {
        if (others.length == 0) {
            return this;
        }
        if (combiner == null) {
            combiner = Combiner.first();
        }
        View[] sources = new View[1 + others.length];
        sources[0] = this;
        System.arraycopy(others, 0, sources, 1, others.length);
        // FIXME: Implement. Also consider stores to the same underlying view. Read existing
        // value first and store only if it's different.
        throw null;
        //return new IntersectionView(combiner, sources);
    }

    /**
     * Returns a view consisting of entries that exist this view but not in any of the others
     * that are given. If the set of views in the difference don't follow a consistent
     * ordering, then entry selection doesn't work correctly. The actual behavior is undefined.
     *
     * <p>Storing entries in the difference is permitted, but final entries are only stored in
     * the first view (this view). As a side-effect of any successful store operation, the
     * corresponding entries in the other views are always deleted. The order in which the
     * underlying operations are performed permits a difference to be composed of views which
     * can map to the same underlying source index. The entire operation is transactional, and
     * so stores to differences don't support the {@link Transaction#BOGUS "bogus"} transaction.
     *
     * <p>Differences don't support cursors. For this reason, differences are typically applied
     * last when building a view processing pipeline. Also, avoiding creating differences which
     * are composed of other differences. Such a pipeline might be less efficient than one
     * composed with a single difference step.
     */
    // FIXME: Consider that all stores apply just to the first view, but only when necessary to
    // change the observed outcome.
    public default View viewDifference(View... others) {
        if (others.length == 0) {
            return this;
        }
        View[] sources = new View[1 + others.length];
        sources[0] = this;
        System.arraycopy(others, 0, sources, 1, others.length);
        // FIXME: Implement.
        throw null;
        //return new DifferenceView(sources);
    }

    /**
     * Returns a view, backed by this one, whose natural order is reversed.
     */
    public default View viewReverse() {
        return new ReverseView(this);
    }

    /**
     * Returns a view, backed by this one, whose entries cannot be modified. Any attempt to do
     * so causes an {@link UnmodifiableViewException} to be thrown.
     */
    public default View viewUnmodifiable() {
        return UnmodifiableView.apply(this);
    }

    /**
     * Returns true if any attempt to modify this view causes an {@link
     * UnmodifiableViewException} to be thrown.
     */
    public boolean isUnmodifiable();
}
