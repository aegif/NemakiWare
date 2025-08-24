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
package org.apache.chemistry.opencmis.client.bindings.spi.atompub;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.client.bindings.cache.Cache;
import org.apache.chemistry.opencmis.client.bindings.cache.impl.CacheImpl;
import org.apache.chemistry.opencmis.client.bindings.cache.impl.ContentTypeCacheLevelImpl;
import org.apache.chemistry.opencmis.client.bindings.cache.impl.LruCacheLevelImpl;
import org.apache.chemistry.opencmis.client.bindings.cache.impl.MapCacheLevelImpl;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.SessionParameterDefaults;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;

/**
 * Link cache.
 */
public class LinkCache implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Set<String> KNOWN_LINKS = new HashSet<String>();

    static {
        KNOWN_LINKS.add(Constants.REL_ACL);
        KNOWN_LINKS.add(Constants.REL_DOWN);
        KNOWN_LINKS.add(Constants.REL_UP);
        KNOWN_LINKS.add(Constants.REL_FOLDERTREE);
        KNOWN_LINKS.add(Constants.REL_RELATIONSHIPS);
        KNOWN_LINKS.add(Constants.REL_SELF);
        KNOWN_LINKS.add(Constants.REL_ALLOWABLEACTIONS);
        KNOWN_LINKS.add(Constants.REL_EDITMEDIA);
        KNOWN_LINKS.add(Constants.REL_POLICIES);
        KNOWN_LINKS.add(Constants.REL_VERSIONHISTORY);
        KNOWN_LINKS.add(Constants.REL_WORKINGCOPY);
        KNOWN_LINKS.add(AtomPubParser.LINK_REL_CONTENT);
    }

    private final Cache linkCache;
    private final Cache typeLinkCache;
    private final Cache collectionLinkCache;
    private final Cache templateCache;
    private final Cache repositoryLinkCache;

    /**
     * Constructor.
     */
    public LinkCache(BindingSession session) {
        int repCount = session.get(SessionParameter.CACHE_SIZE_REPOSITORIES,
                SessionParameterDefaults.CACHE_SIZE_REPOSITORIES);
        if (repCount < 1) {
            repCount = SessionParameterDefaults.CACHE_SIZE_REPOSITORIES;
        }

        int typeCount = session.get(SessionParameter.CACHE_SIZE_TYPES, SessionParameterDefaults.CACHE_SIZE_TYPES);
        if (typeCount < 1) {
            typeCount = SessionParameterDefaults.CACHE_SIZE_TYPES;
        }

        int objCount = session.get(SessionParameter.CACHE_SIZE_LINKS, SessionParameterDefaults.CACHE_SIZE_LINKS);
        if (objCount < 1) {
            objCount = SessionParameterDefaults.CACHE_SIZE_LINKS;
        }

        linkCache = new CacheImpl("Link Cache");
        linkCache.initialize(new String[] {
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=" + repCount, // repository
                LruCacheLevelImpl.class.getName() + " " + LruCacheLevelImpl.MAX_ENTRIES + "=" + objCount, // id
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=12", // rel
                ContentTypeCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=3,"
                        + MapCacheLevelImpl.SINGLE_VALUE + "=true" // type
        });

        typeLinkCache = new CacheImpl("Type Link Cache");
        typeLinkCache.initialize(new String[] {
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=" + repCount, // repository
                LruCacheLevelImpl.class.getName() + " " + LruCacheLevelImpl.MAX_ENTRIES + "=" + typeCount, // id
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=12", // rel
                ContentTypeCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=3,"
                        + MapCacheLevelImpl.SINGLE_VALUE + "=true"// type
        });

        collectionLinkCache = new CacheImpl("Collection Link Cache");
        collectionLinkCache.initialize(new String[] {
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=" + repCount, // repository
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=8" // collection
        });

        templateCache = new CacheImpl("URI Template Cache");
        templateCache.initialize(new String[] {
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=" + repCount, // repository
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=6" // type
        });

        repositoryLinkCache = new CacheImpl("Repository Link Cache");
        repositoryLinkCache.initialize(new String[] {
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=" + repCount, // repository
                MapCacheLevelImpl.class.getName() + " " + MapCacheLevelImpl.CAPACITY + "=6" // rel
        });
    }

    /**
     * Adds a link.
     */
    public void addLink(String repositoryId, String id, String rel, String type, String link) {
        if (KNOWN_LINKS.contains(rel)) {
            linkCache.put(link, repositoryId, id, rel, type);
        } else if (Constants.REL_ALTERNATE.equals(rel)) {
            // use streamId instead of type as discriminating parameter
            String streamId = extractStreamId(link);
            if (streamId != null) {
                linkCache.put(link, repositoryId, id, rel, streamId);
            }
        }
    }

    /**
     * Tries to extract a streamId from an alternate link.
     */
    // this is not strictly in the spec
    protected String extractStreamId(String link) {
        int i = link.lastIndexOf('?');
        if (i > 0) {
            String[] params = link.substring(i + 1).split("&");
            for (String param : params) {
                String[] parts = param.split("=", 2);
                if (parts[0].equals(Constants.PARAM_STREAM_ID) && parts.length == 2) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    /**
     * Removes all links of an object.
     */
    public void removeLinks(String repositoryId, String id) {
        linkCache.remove(repositoryId, id);
    }

    /**
     * Gets a link.
     */
    public String getLink(String repositoryId, String id, String rel, String type) {
        return (String) linkCache.get(repositoryId, id, rel, type);
    }

    /**
     * Gets a link.
     */
    public String getLink(String repositoryId, String id, String rel) {
        return getLink(repositoryId, id, rel, null);
    }

    /**
     * Checks a link.
     */
    public int checkLink(String repositoryId, String id, String rel, String type) {
        return linkCache.check(repositoryId, id, rel, type);
    }

    /**
     * Locks the link cache.
     */
    public void lockLinks() {
        linkCache.writeLock();
    }

    /**
     * Unlocks the link cache.
     */
    public void unlockLinks() {
        linkCache.writeUnlock();
    }

    /**
     * Adds a type link.
     */
    public void addTypeLink(String repositoryId, String id, String rel, String type, String link) {
        if (KNOWN_LINKS.contains(rel)) {
            typeLinkCache.put(link, repositoryId, id, rel, type);
        }
    }

    /**
     * Removes all links of a type.
     */
    public void removeTypeLinks(String repositoryId, String id) {
        typeLinkCache.remove(repositoryId, id);
    }

    /**
     * Gets a type link.
     */
    public String getTypeLink(String repositoryId, String id, String rel, String type) {
        return (String) typeLinkCache.get(repositoryId, id, rel, type);
    }

    /**
     * Locks the type link cache.
     */
    public void lockTypeLinks() {
        typeLinkCache.writeLock();
    }

    /**
     * Unlocks the type link cache.
     */
    public void unlockTypeLinks() {
        typeLinkCache.writeUnlock();
    }

    /**
     * Adds a collection.
     */
    public void addCollection(String repositoryId, String collection, String link) {
        collectionLinkCache.put(link, repositoryId, collection);
    }

    /**
     * Gets a collection.
     */
    public String getCollection(String repositoryId, String collection) {
        return (String) collectionLinkCache.get(repositoryId, collection);
    }

    /**
     * Adds an URI template.
     */
    public void addTemplate(String repositoryId, String type, String link) {
        templateCache.put(link, repositoryId, type);
    }

    /**
     * Gets an URI template and replaces place holders with the given
     * parameters.
     */
    public String getTemplateLink(String repositoryId, String type, Map<String, Object> parameters) {
        String template = (String) templateCache.get(repositoryId, type);
        if (template == null) {
            return null;
        }

        StringBuilder result = new StringBuilder(128);
        StringBuilder param = new StringBuilder(32);

        boolean paramMode = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);

            if (paramMode) {
                if (c == '}') {
                    paramMode = false;

                    String paramValue = UrlBuilder.normalizeParameter(parameters.get(param.toString()));
                    if (paramValue != null) {
                        result.append(IOUtils.encodeURL(paramValue));
                    }

                    param.setLength(0);
                } else {
                    param.append(c);
                }
            } else {
                if (c == '{') {
                    paramMode = true;
                } else {
                    result.append(c);
                }
            }
        }

        return result.toString();
    }

    /**
     * Adds a collection.
     */
    public void addRepositoryLink(String repositoryId, String rel, String link) {
        repositoryLinkCache.put(link, repositoryId, rel);
    }

    /**
     * Gets a collection.
     */
    public String getRepositoryLink(String repositoryId, String rel) {
        return (String) repositoryLinkCache.get(repositoryId, rel);
    }

    /**
     * Removes all entries of the given repository from the caches.
     */
    public void clearRepository(String repositoryId) {
        linkCache.remove(repositoryId);
        typeLinkCache.remove(repositoryId);
        collectionLinkCache.remove(repositoryId);
        templateCache.remove(repositoryId);
        repositoryLinkCache.remove(repositoryId);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Link Cache [link cache=" + linkCache + ", type link cache=" + typeLinkCache
                + ", collection link cache=" + collectionLinkCache + ", repository link cache=" + repositoryLinkCache
                + ",  template cache=" + templateCache + "]";
    }
}
