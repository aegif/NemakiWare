/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.client.bindings.cache;

import java.io.Serializable;

/**
 * An interface for an hierarchical cache.
 * 
 * <p>
 * Each level of the hierarchy could use a different caching strategy. The cache
 * is initialize by defining the classes that handle the caching for one level.
 * These classes must implement the {@link CacheLevel} interface.<br>
 * <br>
 * Level configuration string format: "
 * {@code <class name> [param1=value1,param2=value2,...]}".<br>
 * For example:
 * {@code org.apache.opencmis.client.bindings.cache.impl.MapCacheLevelImpl capacity=10}
 * </p>
 * 
 * @see CacheLevel
 */
public interface Cache extends Serializable {

    /**
     * Initializes the cache.
     * 
     * @param cacheLevelConfig
     *            the level configuration strings from the root to the leafs
     */
    void initialize(String[] cacheLevelConfig);

    /**
     * Adds an object to the cache.
     * 
     * @param value
     *            the object
     * @param keys
     *            the keys for this object
     */
    void put(Object value, String... keys);

    /**
     * Retrieves an object from the cache.
     * 
     * @param keys
     *            the keys
     * @return the object or <code>null</code> if the branch or leaf doesn't
     *         exist
     */
    Object get(String... keys);

    /**
     * Removes a branch or leaf from the cache.
     * 
     * @param keys
     *            the keys of the branch or leaf
     */
    void remove(String... keys);

    /**
     * Removes all entries from the cache.
     */
    void removeAll();

    /**
     * Checks if a given key is in the cache.
     * 
     * @param keys
     *            the keys of the branch or leaf
     * 
     * @return the index of the first key part that is not in the cache or
     *         {@code keys.length} if the object is in the cache
     */
    int check(String... keys);

    /**
     * Applies a write lock.
     */
    void writeLock();

    /**
     * Releases a write lock.
     */
    void writeUnlock();
}
