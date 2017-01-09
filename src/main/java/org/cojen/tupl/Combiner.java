/*
 *  Copyright 2017 Cojen.org
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

/**
 * Represents an operation which combines two values which map to the same key.
 *
 * @author Brian S O'Neill
 * @see View#viewUnion
 */
@FunctionalInterface
public interface Combiner {
    /**
     * Returns a Combiner that chooses the first value and discards the second value.
     */
    public static Combiner first() {
        return (k, v1, v2) -> v1;
    }

    /**
     * Returns a Combiner that chooses the second value and discards the first value.
     */
    public static Combiner second() {
        return (k, v1, v2) -> v2;
    }

    /**
     * Returns a Combiner that discards both values (always returns null). When used with a
     * union, this causes it to compute the symmetric difference.
     */
    public static Combiner neither() {
        return (k, v1, v2) -> null;
    }

    /**
     * Return a combined value derived from the given key and value pair. Null can be returned
     * to completely filter out both values.
     *
     * @param key associated key
     * @param v1 first value in the pair
     * @param v2 second value in the pair
     */
    public byte[] combine(byte[] key, byte[] v1, byte[] v2) throws IOException;
}
