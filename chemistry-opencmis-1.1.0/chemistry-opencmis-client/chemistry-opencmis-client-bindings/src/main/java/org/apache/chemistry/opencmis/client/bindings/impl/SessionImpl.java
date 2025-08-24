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
package org.apache.chemistry.opencmis.client.bindings.impl;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;

/**
 * CMIS binding session implementation.
 */
public class SessionImpl implements BindingSession {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final Map<String, Object> data;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Constructor.
     */
    public SessionImpl() {
        id = UUID.randomUUID().toString();
        data = new HashMap<String, Object>();
    }

    @Override
    public String getSessionId() {
        return id;
    }

    @Override
    public Collection<String> getKeys() {
        return data.keySet();
    }

    @Override
    public Object get(String key) {
        Object value = null;

        lock.readLock().lock();
        try {
            value = data.get(key);
        } finally {
            lock.readLock().unlock();
        }

        if (value instanceof TransientWrapper) {
            return ((TransientWrapper) value).getObject();
        }

        return value;
    }

    @Override
    public Object get(String key, Object defValue) {
        Object value = get(key);
        return value == null ? defValue : value;
    }

    @Override
    public int get(String key, int defValue) {
        Object value = get(key);
        int intValue = defValue;

        if (value instanceof Integer) {
            intValue = ((Integer) value).intValue();
        } else if (value instanceof String) {
            try {
                intValue = Integer.valueOf((String) value);
            } catch (NumberFormatException e) {
                // invalid number -> return default value
            }
        }

        return intValue;
    }

    @Override
    public boolean get(String key, boolean defValue) {
        Object value = get(key);

        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }

        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }

        return defValue;
    }

    @Override
    public void put(String key, Serializable obj) {
        lock.writeLock().lock();
        try {
            data.put(key, obj);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void put(String key, Object obj, boolean isTransient) {
        Object value = isTransient ? new TransientWrapper(obj) : obj;
        if (!(value instanceof Serializable)) {
            throw new IllegalArgumentException("Object must be serializable!");
        }

        lock.writeLock().lock();
        try {
            data.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String key) {
        lock.writeLock().lock();
        try {
            data.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void readLock() {
        lock.readLock().lock();
    }

    @Override
    public void readUnlock() {
        lock.readLock().unlock();
    }

    @Override
    public void writeLock() {
        lock.writeLock().lock();
    }

    @Override
    public void writeUnlock() {
        lock.writeLock().unlock();
    }

    @Override
    public String toString() {
        return "Session " + id + ": " + data.toString();
    }
}
