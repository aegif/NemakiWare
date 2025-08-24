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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provides an in-memory cookie store.
 */
public class CmisCookieStoreImpl implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final String IP_ADDRESS_PATTERN_STR = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(IP_ADDRESS_PATTERN_STR);

    private final int maxUrls;
    private final ArrayDeque<CmisHttpCookie> storeList;

    public CmisCookieStoreImpl() {
        this(300);
    }

    public CmisCookieStoreImpl(final int maxUrls) {
        this.maxUrls = maxUrls;
        storeList = new ArrayDeque<CmisHttpCookie>(64);
    }

    public void add(final URI uri, final CmisHttpCookie cookie) {
        if (uri == null || cookie == null) {
            throw new IllegalArgumentException("URI and cookie must be set!");
        }

        if (cookie.hasExpired()) {
            storeList.remove(cookie);
            return;
        }

        Iterator<CmisHttpCookie> iter = storeList.iterator();
        while (iter.hasNext()) {
            CmisHttpCookie storeCookie = iter.next();

            if (storeCookie.equals(cookie) || storeCookie.hasExpired()) {
                iter.remove();
            }
        }

        storeList.addFirst(cookie);

        if (storeList.size() > maxUrls) {
            storeList.removeLast();
        }
    }

    public List<CmisHttpCookie> get(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI is null!");
        }

        final String uriHost = uri.getHost().toLowerCase(Locale.ENGLISH);

        boolean isSecure = false;
        String scheme = uri.getScheme();
        if (scheme != null) {
            isSecure = scheme.toLowerCase(Locale.ENGLISH).startsWith("https");
        }

        List<CmisHttpCookie> cookies = new ArrayList<CmisHttpCookie>();

        Iterator<CmisHttpCookie> iter = storeList.iterator();
        while (iter.hasNext()) {
            CmisHttpCookie cookie = iter.next();

            if (cookie.hasExpired()) {
                iter.remove();
            } else if ((!cookie.getSecure() || isSecure) && cookie.getDomain() != null) {
                String cookieDomain = cookie.getDomain().toLowerCase(Locale.ENGLISH);

                if (isIPAddress(uriHost) && uriHost.equals(cookieDomain)) {
                    cookies.add(cookie);
                } else {
                    if (cookie.getVersion() == 0) {
                        // Netscape, RFC 2109, RFC 6265
                        if (uriHost.endsWith(cookieDomain)
                                && (uriHost.length() == cookieDomain.length() || cookieDomain.charAt(0) == '.')) {
                            cookies.add(cookie);
                        }
                    } else if (cookie.getVersion() == 1) {
                        // RFC 2965
                        if (CmisHttpCookie.domainMatches(cookieDomain, uriHost)) {
                            cookies.add(cookie);
                        }
                    }
                }
            }
        }

        return cookies;
    }

    public void clear() {
        storeList.clear();
    }

    private boolean isIPAddress(String s) {
        if (s.charAt(0) == '[') {
            // IPv6
            return true;
        }

        if (IP_ADDRESS_PATTERN.matcher(s).matches()) {
            // IPv4
            return true;
        }

        return false;
    }
}
