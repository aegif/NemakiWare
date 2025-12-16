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
package org.apache.chemistry.opencmis.client.bindings.spi;

import java.io.Serializable;
import java.util.Collection;

/**
 * CMIS provider session interface.
 */
public interface BindingSession extends Serializable {

    /**
     * Returns the ID of this session.
     */
    String getSessionId();

    /**
     * Returns all keys.
     */
    Collection<String> getKeys();

    /**
     * Gets a session value.
     */
    Object get(String key);

    /**
     * Returns a session value or the default value if the key doesn't exist.
     */
    Object get(String key, Object defValue);

    /**
     * Returns a session value or the default value if the key doesn't exist.
     */
    int get(String key, int defValue);

    /**
     * Returns a session value or the default value if the key doesn't exist.
     */
    boolean get(String key, boolean defValue);

    /**
     * Adds a non-transient session value.
     */
    void put(String key, Serializable object);

    /**
     * Adds a session value.
     */
    void put(String key, Object object, boolean isTransient);

    /**
     * Removes a session value.
     */
    void remove(String key);

    /**
     * Acquires a read lock.
     */
    void readLock();

    /**
     * Releases a read lock.
     */
    void readUnlock();

    /**
     * Acquires a write lock.
     */
    void writeLock();

    /**
     * Releases a write lock.
     */
    void writeUnlock();
}
