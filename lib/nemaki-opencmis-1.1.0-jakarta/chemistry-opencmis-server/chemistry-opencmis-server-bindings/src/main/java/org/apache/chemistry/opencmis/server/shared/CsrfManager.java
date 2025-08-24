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
package org.apache.chemistry.opencmis.server.shared;

import java.security.SecureRandom;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;

public class CsrfManager {

    public static final String CSRF_ATTR = "org.apache.chemistry.opencmis.csrftoken";

    private static final String CSRF_HEADER = "csrfHeader";
    private static final String CSRF_PARAMETER = "csrfParameter";
    private static final String FETCH_VALUE = "fetch";

    private static char[][] hexArrays = new char[][] { "0123456789ABCDEF".toCharArray(), //
            "0123456789abcdef".toCharArray(), //
            "ABCDEFGHIJKLMNOP".toCharArray(), //
            "abcdefghijklmnop".toCharArray() };

    private String csrfHeader;
    private String csrfParameter;

    private SecureRandom random = new SecureRandom();

    public CsrfManager(String csrfHeader, String csrfParameter) {
        if (csrfHeader != null) {
            this.csrfHeader = csrfHeader.trim();
            if (this.csrfHeader.length() == 0) {
                throw new IllegalArgumentException("Invalid CSRF header!");
            }
            if (csrfParameter != null) {
                this.csrfParameter = csrfParameter.trim();
                if (this.csrfParameter.length() == 0) {
                    throw new IllegalArgumentException("Invalid CSRF parameter!");
                }
            }
        }
    }

    public CsrfManager(ServletConfig config) throws ServletException {
        csrfHeader = config.getInitParameter(CSRF_HEADER);
        if (csrfHeader != null) {
            this.csrfHeader = csrfHeader.trim();
            if (this.csrfHeader.length() == 0) {
                throw new ServletException("Invalid CSRF header!");
            }

            // get parameter
            csrfParameter = config.getInitParameter(CSRF_PARAMETER);
            if (csrfParameter != null) {
                this.csrfParameter = csrfParameter.trim();
                if (this.csrfParameter.length() == 0) {
                    throw new ServletException("Invalid CSRF parameter!");
                }
            }
        }
    }

    public void check(HttpServletRequest req, HttpServletResponse resp, boolean isRepositoryInfoRequest,
            boolean isContentRequest) {
        if (csrfHeader == null) {
            // no CSRF protection
            return;
        }

        HttpSession httpSession = req.getSession(true);
        String token = (String) httpSession.getAttribute(CSRF_ATTR);
        String headerValue = req.getHeader(csrfHeader);

        // check parameter if the header is not set and this is a content
        // request
        if (headerValue == null || headerValue.isEmpty()) {
            if (isContentRequest && csrfParameter != null) {
                String paramValue = req.getParameter(csrfParameter);
                if (paramValue != null && paramValue.equals(token)) {
                    return;
                }
            }

            throw new CmisPermissionDeniedException("Invalid CSRF token!");
        }

        // check if a new token is requested
        if (isRepositoryInfoRequest && FETCH_VALUE.equals(headerValue) && token == null) {
            token = generateNewToken();
            httpSession.setAttribute(CSRF_ATTR, token);
            resp.addHeader(csrfHeader, token);
            return;
        }

        // check if there is a token
        if (token == null) {
            throw new CmisPermissionDeniedException("Invalid CSRF token!");
        }

        // finally, check the token
        if (!token.equals(headerValue)) {
            throw new CmisPermissionDeniedException("Invalid CSRF token!");
        }
    }

    private String generateNewToken() {
        byte[] tokenBytes = new byte[16];
        random.nextBytes(tokenBytes);

        int ary = random.nextInt(hexArrays.length);

        char[] token = new char[tokenBytes.length * 2];
        for (int i = 0; i < tokenBytes.length; i++) {
            int v = tokenBytes[i] & 0xFF;
            token[i * 2] = hexArrays[ary][v >>> 4];
            token[i * 2 + 1] = hexArrays[ary][v & 0x0F];
        }

        return new String(token);
    }
}
