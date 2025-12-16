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
/*
 * This class has been taken from Apache Harmony (http://harmony.apache.org/) 
 * and has been modified to work with OpenCMIS.
 */
package org.apache.chemistry.opencmis.client.bindings.spi.cookies;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cookie Manager.
 * 
 * This implementation conforms to RFC 2965, section 3.3 with some RFC 6265
 * extensions.
 */
public class CmisCookieManager implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(CmisCookieManager.class.getName());

    private static final String VERSION_ZERO_HEADER = "Set-cookie";
    private static final String VERSION_ONE_HEADER = "Set-cookie2";

    private final String sessionId;
    private final CmisCookieStoreImpl store;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Constructs a new cookie manager.
     */
    public CmisCookieManager() {
        this("<unknown>");
    }

    /**
     * Constructs a new cookie manager.
     */
    public CmisCookieManager(String sessionId) {
        this.sessionId = sessionId;
        store = new CmisCookieStoreImpl();
    }

    /**
     * Searches and gets all cookies in the cache by the specified URL in the
     * request header.
     * 
     * @param url
     *            the specified URL to search for
     * @param requestHeaders
     *            a list of request headers
     * @return a map that record all such cookies, the map is unchangeable
     */
    public Map<String, List<String>> get(String url, Map<String, List<String>> requestHeaders) {
        if (url == null || requestHeaders == null) {
            throw new IllegalArgumentException("URL or headers are null!");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new CmisConnectionException(e.getMessage(), e);
        }

        lock.writeLock().lock();
        try {
            List<CmisHttpCookie> cookies = store.get(uri);
            String uriPath = uri.getPath();
            for (int i = 0; i < cookies.size(); i++) {
                CmisHttpCookie cookie = cookies.get(i);
                String cookiePath = cookie.getPath();
                // if the uri's path does not path-match cookie's path, remove
                // cookies from the list
                if (cookiePath == null || uriPath.length() == 0 || !uriPath.startsWith(cookiePath)) {
                    cookies.remove(i);
                }
            }

            Map<String, List<String>> map = getCookieMap(cookies, requestHeaders);

            if (LOG.isDebugEnabled()) {
                if (map != null && !map.isEmpty()) {
                    LOG.debug("Session {}: Setting cookies for URL {}: {}", sessionId, url,
                            map.get("Cookie") == null ? "" : map.get("Cookie").toString());
                }
            }

            return map;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static Map<String, List<String>> getCookieMap(List<CmisHttpCookie> cookies,
            Map<String, List<String>> requestHeaders) {
        if (cookies.isEmpty()) {
            return Collections.emptyMap();
        }

        StringBuilder cookieHeaderStr = new StringBuilder(128);

        for (CmisHttpCookie cookie : cookies) {
            if (cookieHeaderStr.length() > 0) {
                cookieHeaderStr.append("; ");
            }
            cookieHeaderStr.append(cookie.getName());
            cookieHeaderStr.append('=');
            cookieHeaderStr.append(cookie.getValue());
        }

        return Collections.singletonMap("Cookie", Collections.singletonList(cookieHeaderStr.toString()));
    }

    /**
     * Sets cookies according to URL and responseHeaders
     * 
     * @param url
     *            the specified URL
     * @param responseHeaders
     *            a list of request headers
     */
    public void put(String url, Map<String, List<String>> responseHeaders) {
        if (url == null || responseHeaders == null) {
            throw new IllegalArgumentException("URL or headers are null!");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new CmisConnectionException(e.getMessage(), e);
        }

        lock.writeLock().lock();
        try {
            // parse and construct cookies according to the map
            List<CmisHttpCookie> cookies = parseCookie(responseHeaders);
            for (CmisHttpCookie cookie : cookies) {
                if (cookie.getDomain() == null) {
                    cookie.setDomain(uri.getHost());
                }
                if (cookie.getPath() == null) {
                    cookie.setPath("/");
                }
                store.add(uri, cookie);
            }

            if (LOG.isDebugEnabled()) {
                if (!cookies.isEmpty()) {
                    LOG.debug("Session {}: Retrieved cookies for URL {}: {}", sessionId, url, cookies.toString());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes all cookies.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            store.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static List<CmisHttpCookie> parseCookie(Map<String, List<String>> responseHeaders) {
        List<CmisHttpCookie> cookies = new ArrayList<CmisHttpCookie>();
        for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
            String key = entry.getKey();
            // Only "Set-cookie" and "Set-cookie2" pair will be parsed
            if (key != null && (key.equalsIgnoreCase(VERSION_ZERO_HEADER) || key.equalsIgnoreCase(VERSION_ONE_HEADER))) {
                // parse list elements one by one
                for (String cookieStr : entry.getValue()) {
                    try {
                        for (CmisHttpCookie cookie : CmisHttpCookie.parse(cookieStr)) {
                            cookies.add(cookie);
                        }
                    } catch (IllegalArgumentException e) {
                        // this string is invalid, jump to the next one.
                    }
                }
            }
        }

        return cookies;
    }

    /**
     * Gets current cookie store.
     * 
     * @return the cookie store currently used by cookie manager.
     */
    public CmisCookieStoreImpl getCookieStore() {
        return store;
    }
}
