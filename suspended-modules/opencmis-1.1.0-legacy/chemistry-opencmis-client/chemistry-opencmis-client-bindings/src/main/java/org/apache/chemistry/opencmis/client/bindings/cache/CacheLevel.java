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
import java.util.Map;

/**
 * Interface for a level of an hierarchical cache.
 * 
 * @see Cache
 */
public interface CacheLevel extends Serializable {

    /**
     * Initialize the cache level.
     * 
     * @param parameters
     *            level parameters
     */
    void initialize(Map<String, String> parameters);

    /**
     * Adds an object to the cache level.
     * 
     * @param value
     *            the object
     * @param key
     *            the key at this level
     */
    void put(Object value, String key);

    /**
     * Retrieves an object from the cache level.
     * 
     * @param key
     *            the key at this cache level
     * @return the object or <code>null</code> if the object doesn't exist
     */
    Object get(String key);

    /**
     * Removes an object from this cache level.
     * 
     * @param key
     *            the key at this cache level
     */
    void remove(String key);
}
