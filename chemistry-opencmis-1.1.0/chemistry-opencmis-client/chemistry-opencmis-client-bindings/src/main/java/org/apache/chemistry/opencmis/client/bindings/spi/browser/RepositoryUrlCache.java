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
package org.apache.chemistry.opencmis.client.bindings.spi.browser;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;

/**
 * URL cache for repository and root URLs.
 */
public class RepositoryUrlCache implements Serializable {

    public static final String OBJECT_ID = "objectId";

    private static final long serialVersionUID = 1L;

    private final Map<String, String> repositoryUrls;
    private final Map<String, String> rootUrls;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public RepositoryUrlCache() {
        repositoryUrls = new HashMap<String, String>();
        rootUrls = new HashMap<String, String>();
    }

    /**
     * Adds the URLs of a repository to the cache.
     */
    public void addRepository(String repositoryId, String repositoryUrl, String rootUrl) {
        if (repositoryId == null || repositoryUrl == null || rootUrl == null) {
            throw new IllegalArgumentException("Repository Id or Repository URL or Root URL is not set!");
        }

        lock.writeLock().lock();
        try {
            repositoryUrls.put(repositoryId, repositoryUrl);
            rootUrls.put(repositoryId, rootUrl);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes the URLs of a repository from the cache.
     */
    public void removeRepository(String repositoryId) {
        lock.writeLock().lock();
        try {
            repositoryUrls.remove(repositoryId);
            rootUrls.remove(repositoryId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the base repository URL of a repository.
     */
    public String getRepositoryBaseUrl(String repositoryId) {
        lock.readLock().lock();
        try {
            return repositoryUrls.get(repositoryId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the root URL of a repository.
     */
    public String getRootUrl(String repositoryId) {
        lock.readLock().lock();
        try {
            return rootUrls.get(repositoryId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the repository URL.
     */
    public UrlBuilder getRepositoryUrl(String repositoryId) {
        String base = getRepositoryBaseUrl(repositoryId);
        if (base == null) {
            return null;
        }

        return new UrlBuilder(base);
    }

    /**
     * Returns the repository URL with the given selector.
     */
    public UrlBuilder getRepositoryUrl(String repositoryId, String selector) {
        UrlBuilder result = getRepositoryUrl(repositoryId);
        if (result == null) {
            return null;
        }

        result.addParameter(Constants.PARAM_SELECTOR, selector);

        return result;
    }

    /**
     * Returns an object URL with the given selector.
     */
    public UrlBuilder getObjectUrl(String repositoryId, String objectId) {
        String root = getRootUrl(repositoryId);
        if (root == null) {
            return null;
        }

        UrlBuilder result = new UrlBuilder(root);
        result.addParameter(OBJECT_ID, objectId);

        return result;
    }

    /**
     * Returns an object URL with the given selector.
     */
    public UrlBuilder getObjectUrl(String repositoryId, String objectId, String selector) {
        UrlBuilder result = getObjectUrl(repositoryId, objectId);
        if (result == null) {
            return null;
        }

        result.addParameter(Constants.PARAM_SELECTOR, selector);

        return result;
    }

    /**
     * Returns an object URL with the given selector.
     */
    public UrlBuilder getPathUrl(String repositoryId, String path) {
        String root = getRootUrl(repositoryId);
        if (root == null) {
            return null;
        }

        UrlBuilder result = new UrlBuilder(root);
        result.addPath(path);

        return result;
    }

    /**
     * Returns an object URL with the given selector.
     */
    public UrlBuilder getPathUrl(String repositoryId, String path, String selector) {
        UrlBuilder result = getPathUrl(repositoryId, path);
        if (result == null) {
            return null;
        }

        result.addParameter(Constants.PARAM_SELECTOR, selector);

        return result;
    }
}
