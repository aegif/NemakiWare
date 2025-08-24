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

import org.apache.chemistry.opencmis.client.bindings.cache.Cache;
import org.apache.chemistry.opencmis.client.bindings.cache.impl.CacheImpl;
import org.apache.chemistry.opencmis.client.bindings.cache.impl.MapCacheLevelImpl;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.SessionParameterDefaults;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;

/**
 * A cache for repository info objects.
 */
public class RepositoryInfoCache implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Cache cache;

    /**
     * Constructor.
     * 
     * @param session
     *            the session object
     */
    public RepositoryInfoCache(BindingSession session) {
        assert session != null;

        int repCount = session.get(SessionParameter.CACHE_SIZE_REPOSITORIES,
                SessionParameterDefaults.CACHE_SIZE_REPOSITORIES);
        if (repCount < 1) {
            repCount = SessionParameterDefaults.CACHE_SIZE_REPOSITORIES;
        }

        cache = new CacheImpl("Repository Info Cache");
        cache.initialize(new String[] { MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "="
                + repCount });
    }

    /**
     * Adds a repository info object to the cache.
     * 
     * @param repositoryInfo
     *            the repository info object
     */
    public void put(RepositoryInfo repositoryInfo) {
        if ((repositoryInfo == null) || (repositoryInfo.getId() == null)) {
            return;
        }

        cache.put(repositoryInfo, repositoryInfo.getId());
    }

    /**
     * Retrieves a repository info object from the cache.
     * 
     * @param repositoryId
     *            the repository id
     * @return the repository info object or <code>null</code> if the object is
     *         not in the cache
     */
    public RepositoryInfo get(String repositoryId) {
        return (RepositoryInfo) cache.get(repositoryId);
    }

    /**
     * Removes a repository info object from the cache.
     * 
     * @param repositoryId
     *            the repository id
     */
    public void remove(String repositoryId) {
        cache.remove(repositoryId);
    }

    @Override
    public String toString() {
        return cache.toString();
    }
}
