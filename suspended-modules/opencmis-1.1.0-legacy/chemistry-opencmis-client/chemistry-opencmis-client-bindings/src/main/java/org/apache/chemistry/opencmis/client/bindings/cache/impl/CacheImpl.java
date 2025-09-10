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
package org.apache.chemistry.opencmis.client.bindings.cache.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.client.bindings.cache.Cache;
import org.apache.chemistry.opencmis.client.bindings.cache.CacheLevel;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default cache implementation.
 */
public class CacheImpl implements Cache {

    private static final Logger LOG = LoggerFactory.getLogger(CacheImpl.class);

    private static final long serialVersionUID = 1L;

    private List<Class<?>> levels;
    private List<Map<String, String>> levelParameters;

    private final String name;

    private CacheLevel root;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Constructor.
     */
    public CacheImpl() {
        this.name = "Cache";
    }

    /**
     * Constructor.
     */
    public CacheImpl(String name) {
        this.name = name;
    }

    @Override
    public void initialize(String[] cacheLevelConfig) {
        if (levels != null) {
            throw new IllegalStateException("Cache already initialize!");
        }

        if (cacheLevelConfig == null || cacheLevelConfig.length == 0) {
            throw new IllegalArgumentException("Cache config must not be empty!");
        }

        lock.writeLock().lock();
        try {
            levels = new ArrayList<Class<?>>(cacheLevelConfig.length);
            levelParameters = new ArrayList<Map<String, String>>();

            // build level lists
            for (String config : cacheLevelConfig) {
                int x = config.indexOf(' ');
                if (x == -1) {
                    addLevel(config, null);
                } else {
                    addLevel(config.substring(0, x), config.substring(x + 1));
                }
            }

            // create root
            root = createCacheLevel(0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void addLevel(String className, String parameters) {
        // get the class
        Class<?> clazz;
        try {
            clazz = ClassLoaderUtil.loadClass(className, this.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Class '" + className + "' not found!", e);
        }

        // check the class
        if (!CacheLevel.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class '" + className + "' does not implement the CacheLevel interface!");
        }

        levels.add(clazz);

        // process parameters
        if (parameters == null) {
            levelParameters.add(null);
        } else {
            Map<String, String> parameterMap = new HashMap<String, String>();
            levelParameters.add(parameterMap);

            for (String pair : parameters.split(",")) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 1) {
                    parameterMap.put(keyValue[0], "");
                } else {
                    parameterMap.put(keyValue[0], keyValue[1]);
                }
            }
        }
    }

    @Override
    public Object get(String... keys) {
        // check keys
        if (keys == null) {
            return null;
        }

        // check level depth
        if (levels.size() != keys.length) {
            throw new IllegalArgumentException("Wrong number of keys!");
        }

        Object result = null;

        lock.readLock().lock();
        try {
            CacheLevel cacheLevel = root;

            // follow the branch
            for (int i = 0; i < keys.length - 1; i++) {
                Object level = cacheLevel.get(keys[i]);

                // does the branch exist?
                if (level == null) {
                    return null;
                }

                // next level
                cacheLevel = (CacheLevel) level;
            }

            // get the value
            result = cacheLevel.get(keys[keys.length - 1]);
        } finally {
            lock.readLock().unlock();
        }

        return result;
    }

    @Override
    public void put(Object value, String... keys) {
        // check keys
        if (keys == null) {
            return;
        }

        // check level depth
        if (levels.size() != keys.length) {
            throw new IllegalArgumentException("Wrong number of keys!");
        }

        lock.writeLock().lock();
        try {
            CacheLevel cacheLevel = root;

            // follow the branch
            for (int i = 0; i < keys.length - 1; i++) {
                Object level = cacheLevel.get(keys[i]);

                // does the branch exist?
                if (level == null) {
                    level = createCacheLevel(i + 1);
                    cacheLevel.put(level, keys[i]);
                }

                // next level
                cacheLevel = (CacheLevel) level;
            }

            cacheLevel.put(value, keys[keys.length - 1]);

            if (LOG.isTraceEnabled()) {
                LOG.trace("{}: put [{}] = {}", name, getFormattedKeys(keys), value);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String... keys) {
        if (keys == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            CacheLevel cacheLevel = root;

            // follow the branch
            for (int i = 0; i < keys.length - 1; i++) {
                Object level = cacheLevel.get(keys[i]);

                // does the branch exist?
                if (level == null) {
                    return;
                }

                // next level
                cacheLevel = (CacheLevel) level;
            }

            cacheLevel.remove(keys[keys.length - 1]);

            if (LOG.isTraceEnabled()) {
                LOG.trace("{}: removed [{}]", name, getFormattedKeys(keys));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeAll() {
        lock.writeLock().lock();
        try {
            root = createCacheLevel(0);

            if (LOG.isTraceEnabled()) {
                LOG.trace("{}: removed all", name);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int check(String... keys) {
        if (keys == null) {
            return -1;
        }

        lock.readLock().lock();
        try {
            CacheLevel cacheLevel = root;

            // follow the branch
            for (int i = 0; i < keys.length - 1; i++) {
                Object level = cacheLevel.get(keys[i]);

                // does the branch exist?
                if (level == null) {
                    return i;
                }

                // next level
                cacheLevel = (CacheLevel) level;
            }
        } finally {
            lock.readLock().unlock();
        }

        return keys.length;
    }

    @Override
    public void writeLock() {
        lock.writeLock().lock();
    }

    @Override
    public void writeUnlock() {
        lock.writeLock().unlock();
    }

    // ---- internal ----

    /**
     * Creates a cache level object.
     */
    private CacheLevel createCacheLevel(int level) {
        if (level < 0 || level >= levels.size()) {
            throw new IllegalArgumentException("Cache level doesn't fit the configuration!");
        }

        // get the class and create an instance
        Class<?> clazz = levels.get(level);
        CacheLevel cacheLevel = null;
        try {
            cacheLevel = (CacheLevel) clazz.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cache level problem?!", e);
        }

        // initialize it
        cacheLevel.initialize(levelParameters.get(level));

        return cacheLevel;
    }

    @Override
    public String toString() {
        return root == null ? "(no cache root)" : root.toString();
    }

    // ---- internal ----

    private static String getFormattedKeys(String[] keys) {
        assert keys != null;

        StringBuilder sb = new StringBuilder(32);
        for (String k : keys) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k);
        }

        return sb.toString();
    }
}
