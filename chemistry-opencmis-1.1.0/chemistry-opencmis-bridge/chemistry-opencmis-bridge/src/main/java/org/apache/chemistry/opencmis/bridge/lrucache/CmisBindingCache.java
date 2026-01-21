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
package org.apache.chemistry.opencmis.bridge.lrucache;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Simple LRU cache for {@link CmisBinding} objects. The cache key is consists
 * of the repository id and the user.
 */
public class CmisBindingCache implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final LinkedHashMap<String, CmisBinding> cache;

    public CmisBindingCache(final int size) {
        cache = new LinkedHashMap<String, CmisBinding>(size + 1, 0.70f, true) {

            private static final long serialVersionUID = 1L;

            @Override
            public boolean removeEldestEntry(Map.Entry<String, CmisBinding> eldest) {
                return size() > size;
            }
        };
    }

    public CmisBinding getCmisBinding(CallContext context) {
        lock.writeLock().lock();
        try {
            return cache.get(getCacheKey(context));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CmisBinding putCmisBinding(CallContext context, CmisBinding binding) {
        lock.writeLock().lock();
        try {
            String key = getCacheKey(context);
            CmisBinding extistingBinding = cache.get(getCacheKey(context));
            if (extistingBinding == null) {
                cache.put(key, binding);
            } else {
                binding = extistingBinding;
            }

            return binding;
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected String getCacheKey(CallContext context) {
        String repositoryId = context.getRepositoryId();
        String user = context.getUsername();

        String key = repositoryId == null ? "" : repositoryId;
        if (user != null) {
            key = key + "\n" + user;
        }

        return key;
    }
}
