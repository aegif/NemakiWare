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

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A filter that corrects server name, server port and scheme if OpenCMIS is
 * running behind a proxy or load balancer.
 */
public class ProxyFilter implements Filter {

    public static final String PARAM_BASE_PATH = "basePath";
    public static final String PARAM_TRUSTED_PROXIES = "trustedProxies";

    private String basePath;
    private Pattern trustedProxies;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        basePath = filterConfig.getInitParameter(PARAM_BASE_PATH);

        trustedProxies = null;
        String trustedProxiesString = filterConfig.getInitParameter(PARAM_TRUSTED_PROXIES);
        if (trustedProxiesString != null) {
            try {
                trustedProxies = Pattern.compile(trustedProxiesString);
            } catch (Exception e) {
                throw new ServletException("Could not compile trustedProxies parameter: " + e, e);
            }
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, final FilterChain chain) throws IOException,
            ServletException {

        // check for trusted proxy
        if (trustedProxies != null && (request instanceof HttpServletRequest)
                && trustedProxies.matcher(request.getRemoteAddr()).matches()) {
            request = new ProxyHttpServletRequestWrapper((HttpServletRequest) request, basePath);
        }

        // call next
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
