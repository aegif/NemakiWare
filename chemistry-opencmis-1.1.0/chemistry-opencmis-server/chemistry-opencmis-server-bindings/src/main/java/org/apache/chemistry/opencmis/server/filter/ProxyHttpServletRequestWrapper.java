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
package org.apache.chemistry.opencmis.server.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

public class ProxyHttpServletRequestWrapper extends HttpServletRequestWrapper {

    public static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";
    public static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
    public static final String HTTPS_SCHEME = "https";
    public static final String HTTP_SCHEME = "http";

    private String scheme;
    private String serverName;
    private int serverPort;
    private final String contextPath;
    private final String servletPath;
    private final String requestURI;

    public ProxyHttpServletRequestWrapper(HttpServletRequest request, String basePath) {
        super(request);

        scheme = request.getHeader(FORWARDED_PROTO_HEADER);

        if (!HTTP_SCHEME.equalsIgnoreCase(scheme) && !HTTPS_SCHEME.equalsIgnoreCase(scheme)) {
            scheme = request.getScheme();
        }

        serverName = request.getServerName();
        serverPort = request.getServerPort();

        String host = request.getHeader(FORWARDED_HOST_HEADER);
        if ((host != null) && (host.length() > 0)) {
            int index = host.indexOf(':');
            if (index < 0) {
                serverName = host;
                serverPort = getDefaultPort(scheme);
            } else {
                serverName = host.substring(0, index);
                try {
                    serverPort = Integer.parseInt(host.substring(index + 1));
                } catch (NumberFormatException e) {
                    serverPort = getDefaultPort(scheme);
                }
            }
        }

        servletPath = request.getServletPath();

        if (basePath != null && basePath.length() > 0) {
            final String path = request.getRequestURI().substring(
                    request.getContextPath().length() + request.getServletPath().length());

            contextPath = (basePath.charAt(0) == '/' ? basePath : "/" + basePath);
            requestURI = contextPath + servletPath + path;
        } else {
            contextPath = request.getContextPath();
            requestURI = request.getRequestURI();
        }
    }

    private int getDefaultPort(String scheme) {
        if (HTTPS_SCHEME.equalsIgnoreCase(scheme)) {
            return 443;
        }

        return 80;
    }

    @Override
    public String getScheme() {
        return scheme;
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    @Override
    public int getServerPort() {
        return serverPort;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String getServletPath() {
        return servletPath;
    }

    @Override
    public String getRequestURI() {
        return requestURI;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();
        sb.append(scheme);
        sb.append("://");
        sb.append(serverName);
        sb.append(':');
        sb.append(serverPort);
        sb.append(getRequestURI());

        return sb;
    }
}
