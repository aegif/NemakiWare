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
package org.apache.chemistry.opencmis.commons.impl;

import java.net.URI;

import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;

/**
 * Utility class that helps building URLs.
 */
public class UrlBuilder {

    private final StringBuilder urlPart;
    private final StringBuilder queryPart;

    /**
     * Constructor.
     * 
     * @param url
     *            initial URL
     */
    public UrlBuilder(String url) {
        if (url == null) {
            throw new IllegalArgumentException("URL must be set");
        }

        urlPart = new StringBuilder(128);
        queryPart = new StringBuilder(128);

        int qm = url.indexOf('?');
        if (qm == -1) {
            urlPart.append(url);
        } else {
            urlPart.append(url.substring(0, qm));
            if (qm < url.length()) {
                queryPart.append(url.substring(qm + 1));
            }
        }
    }

    /**
     * Constructor.
     * 
     * @param scheme
     *            scheme
     * @param host
     *            host
     * @param port
     *            port
     * @param path
     *            path
     */
    public UrlBuilder(String scheme, String host, int port, String path) {

        if ("http".equalsIgnoreCase(scheme) && (port == 80)) {
            port = -1;
        }
        if ("https".equalsIgnoreCase(scheme) && (port == 443)) {
            port = -1;
        }

        urlPart = new StringBuilder(128);
        queryPart = new StringBuilder(128);

        urlPart.append(scheme);
        urlPart.append("://");
        urlPart.append(host);
        if (port > 0) {
            urlPart.append(':').append(port);
        }
        if (path != null && path.length() > 0) {
            if (urlPart.charAt(urlPart.length() - 1) != '/') {
                urlPart.append('/');
            }
            if (path.charAt(0) == '/') {
                path = path.substring(1);
            }

            urlPart.append(quoteURIPathComponent(path, true));
        }
    }

    /**
     * Copy constructor.
     */
    public UrlBuilder(UrlBuilder urlBuilder) {
        if (urlBuilder == null) {
            throw new IllegalArgumentException("UrlBuilder must be set");
        }

        urlPart = new StringBuilder(urlBuilder.urlPart);
        queryPart = new StringBuilder(urlBuilder.queryPart);
    }

    /**
     * Adds a parameter to the URL.
     * 
     * @param name
     *            parameter name
     * @param value
     *            parameter value
     */
    public UrlBuilder addParameter(String name, Object value) {
        if ((name == null) || (value == null)) {
            return this;
        }

        String valueStr = normalizeParameter(value);

        if (queryPart.length() > 0) {
            queryPart.append('&');
        }
        queryPart.append(name);
        queryPart.append('=');
        queryPart.append(IOUtils.encodeURL(valueStr));

        return this;
    }

    /**
     * Adds a parameter without value to the URL.
     * 
     * @param name
     *            parameter name
     */
    public UrlBuilder addParameter(String name) {
        if (name == null) {
            return this;
        }

        if (queryPart.length() > 0) {
            queryPart.append('&');
        }
        queryPart.append(name);

        return this;
    }

    /**
     * Adds a path segment to the URL.
     * 
     * @param pathSegment
     *            the path segment.
     */
    public UrlBuilder addPathSegment(String pathSegment) {
        return addPathPart(pathSegment, true);
    }

    /**
     * Adds a path to the URL.
     * 
     * @param path
     *            the path
     */
    public UrlBuilder addPath(String path) {
        return addPathPart(path, false);
    }

    protected UrlBuilder addPathPart(String part, boolean quoteSlash) {
        if (part == null || part.length() == 0) {
            return this;
        }
        if (urlPart.charAt(urlPart.length() - 1) != '/') {
            urlPart.append('/');
        }
        if (part.charAt(0) == '/') {
            part = part.substring(1);
        }
        urlPart.append(quoteURIPathComponent(part, quoteSlash));

        return this;
    }

    private static final char[] RFC7232_RESERVED = ";?:@&=+$,[]".toCharArray();

    public static String quoteURIPathComponent(String s, boolean quoteSlash) {
        if (s.length() == 0) {
            return s;
        }
        // reuse the URI class which knows a lot about escaping
        URI uri;
        try {
            // fake scheme so that a colon is not mistaken as a scheme
            uri = new URI("x", s, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Illegal characters in: " + s, e);
        }
        String r = uri.toASCIIString().substring(2); // remove x:
        // quote some additional reserved characters to be safe
        for (char c : RFC7232_RESERVED) {
            if (r.indexOf(c) >= 0) {
                r = r.replace(String.valueOf(c), "%" + Integer.toHexString(c));
            }
        }
        if (quoteSlash && r.indexOf('/') >= 0) {
            r = r.replace("/", "%2F");
        }
        return r;
    }

    /**
     * Converts an object to a String that can be used as a parameter value.
     */
    public static String normalizeParameter(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof IncludeRelationships) {
            return ((IncludeRelationships) value).value();
        } else if (value instanceof VersioningState) {
            return ((VersioningState) value).value();
        } else if (value instanceof UnfileObject) {
            return ((UnfileObject) value).value();
        } else if (value instanceof RelationshipDirection) {
            return ((RelationshipDirection) value).value();
        } else if (value instanceof ReturnVersion) {
            return ((ReturnVersion) value).value();
        } else if (value instanceof AclPropagation) {
            return ((AclPropagation) value).value();
        }

        return value.toString();
    }

    @Override
    public String toString() {
        return urlPart.toString() + (queryPart.length() == 0 ? "" : "?" + queryPart.toString());
    }
}
