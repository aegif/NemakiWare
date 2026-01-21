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
package org.apache.chemistry.opencmis.client.runtime.cache;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.SessionParameterDefaults;

/**
 * Synchronized cache implementation. The cache is limited to a specific size of
 * entries and works in a LRU mode.
 */
public class CacheImpl implements Cache {

    private static final long serialVersionUID = 1L;

    private static final float HASHTABLE_LOAD_FACTOR = 0.75f;

    private int cacheSize;
    private int cacheTtl;
    private int pathToIdSize;
    private int pathToIdTtl;

    private LinkedHashMap<String, CacheItem<Map<String, CmisObject>>> objectMap;
    private LinkedHashMap<String, CacheItem<String>> pathToIdMap;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Default constructor.
     */
    public CacheImpl() {
    }

    @Override
    public void initialize(Session session, Map<String, String> parameters) {
        assert parameters != null;

        lock.writeLock().lock();
        try {
            // cache size
            try {
                cacheSize = Integer.valueOf(parameters.get(SessionParameter.CACHE_SIZE_OBJECTS));
                if (cacheSize < 0) {
                    cacheSize = 0;
                }
            } catch (Exception e) {
                cacheSize = SessionParameterDefaults.CACHE_SIZE_OBJECTS;
            }

            // cache time-to-live
            try {
                cacheTtl = Integer.valueOf(parameters.get(SessionParameter.CACHE_TTL_OBJECTS));
                if (cacheTtl < 0) {
                    cacheTtl = SessionParameterDefaults.CACHE_TTL_OBJECTS;
                }
            } catch (Exception e) {
                cacheTtl = SessionParameterDefaults.CACHE_TTL_OBJECTS;
            }

            // path-to-id size
            try {
                pathToIdSize = Integer.valueOf(parameters.get(SessionParameter.CACHE_SIZE_PATHTOID));
                if (pathToIdSize < 0) {
                    pathToIdSize = 0;
                }
            } catch (Exception e) {
                pathToIdSize = SessionParameterDefaults.CACHE_SIZE_PATHTOID;
            }

            // path-to-id time-to-live
            try {
                pathToIdTtl = Integer.valueOf(parameters.get(SessionParameter.CACHE_TTL_PATHTOID));
                if (pathToIdTtl < 0) {
                    pathToIdTtl = SessionParameterDefaults.CACHE_TTL_PATHTOID;
                }
            } catch (Exception e) {
                pathToIdTtl = SessionParameterDefaults.CACHE_TTL_PATHTOID;
            }

            initializeInternals();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Sets up the internal objects.
     */
    private void initializeInternals() {
        lock.writeLock().lock();
        try {
            // object cache
            int cacheHashTableCapacity = (int) Math.ceil(cacheSize / HASHTABLE_LOAD_FACTOR) + 1;

            final int cs = cacheSize;

            objectMap = new LinkedHashMap<String, CacheItem<Map<String, CmisObject>>>(cacheHashTableCapacity,
                    HASHTABLE_LOAD_FACTOR) {

                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheItem<Map<String, CmisObject>>> eldest) {
                    return size() > cs;
                }
            };

            // path-to-id mapping
            int pathtoidHashTableCapacity = (int) Math.ceil(pathToIdSize / HASHTABLE_LOAD_FACTOR) + 1;

            final int ptis = pathToIdSize;

            pathToIdMap = new LinkedHashMap<String, CacheItem<String>>(pathtoidHashTableCapacity, HASHTABLE_LOAD_FACTOR) {

                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheItem<String>> eldest) {
                    return size() > ptis;
                }
            };
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        initializeInternals();
    }

    @Override
    public boolean containsId(String objectId, String cacheKey) {
        lock.writeLock().lock();
        try {
            if (!objectMap.containsKey(objectId)) {
                return false;
            }

            CacheItem<Map<String, CmisObject>> item = objectMap.get(objectId);
            if (item.isExpired()) {
                objectMap.remove(objectId);
                return false;
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean containsPath(String path, String cacheKey) {
        lock.writeLock().lock();
        try {
            if (!pathToIdMap.containsKey(path)) {
                return false;
            }

            CacheItem<String> item = pathToIdMap.get(path);
            if (item.isExpired() || !containsId(item.getItem(), cacheKey)) {
                pathToIdMap.remove(path);
                return false;
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CmisObject getById(String objectId, String cacheKey) {
        lock.writeLock().lock();
        try {
            if (!containsId(objectId, cacheKey)) {
                return null;
            }

            Map<String, CmisObject> item = objectMap.get(objectId).getItem();
            return item == null ? null : item.get(cacheKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CmisObject getByPath(String path, String cacheKey) {
        lock.writeLock().lock();
        try {
            if (!containsPath(path, cacheKey)) {
                return null;
            }

            CacheItem<String> item = pathToIdMap.get(path);
            return getById(item.getItem(), cacheKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String getObjectIdByPath(String path) {
        lock.writeLock().lock();
        try {
            CacheItem<String> item = pathToIdMap.get(path);
            if (item == null) {
                return null;
            }
            if (item.isExpired()) {
                pathToIdMap.remove(path);
                return null;
            }

            return item.getItem();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void put(CmisObject object, String cacheKey) {
        // no object, no cache key - no cache
        if ((object == null) || (cacheKey == null)) {
            return;
        }

        // no id - no cache
        if (object.getId() == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            // get cache key map
            CacheItem<Map<String, CmisObject>> cacheKeyMap = objectMap.get(object.getId());
            if (cacheKeyMap == null) {
                cacheKeyMap = new CacheItem<Map<String, CmisObject>>(new HashMap<String, CmisObject>(), cacheTtl);
                objectMap.put(object.getId(), cacheKeyMap);
            }

            // put into id cache
            Map<String, CmisObject> m = cacheKeyMap.getItem();
            if (m != null) {
                m.put(cacheKey, object);
            }

            // folders may have a path, use it!
            String path = object.getPropertyValue(PropertyIds.PATH);
            if (path != null) {
                pathToIdMap.put(path, new CacheItem<String>(object.getId(), pathToIdTtl));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void putPath(String path, CmisObject object, String cacheKey) {
        if (path == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            put(object, cacheKey);

            if ((object != null) && (object.getId() != null) && (cacheKey != null)) {
                pathToIdMap.put(path, new CacheItem<String>(object.getId(), pathToIdTtl));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String objectId) {
        if (objectId == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            objectMap.remove(objectId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removePath(String path) {
        if (path == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            pathToIdMap.remove(path);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int getCacheSize() {
        return this.cacheSize;
    }

    // --- cache item ---

    private static class CacheItem<T> implements Serializable {

        private static final long serialVersionUID = 1L;

        private SoftReference<T> item;
        private long timestamp;
        private int ttl;

        public CacheItem(T item, int ttl) {
            this.item = new SoftReference<T>(item);
            timestamp = System.currentTimeMillis();
            this.ttl = ttl;
        }

        public synchronized boolean isExpired() {
            if ((item == null) || (item.get() == null)) {
                return true;
            }

            return timestamp + ttl < System.currentTimeMillis();
        }

        public synchronized T getItem() {
            if (isExpired()) {
                item = null;
                return null;
            }

            return item.get();
        }

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeObject(isExpired() ? null : item.get());
            out.writeLong(timestamp);
            out.writeInt(ttl);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            @SuppressWarnings("unchecked")
            T object = (T) in.readObject();
            timestamp = in.readLong();
            ttl = in.readInt();

            if ((object != null) && (timestamp + ttl >= System.currentTimeMillis())) {
                this.item = new SoftReference<T>(object);
            }
        }
    }
}
