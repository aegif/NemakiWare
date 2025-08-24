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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Call Context handler that handles basic authentication.
 * 
 * This handler assumes that the user credentials have either already checked
 * (for example by a Servlet filter) or will be checked later in the CMIS
 * implementation.
 * 
 * Checking the credentials before the request reaches the CMIS implementation
 * is the preferred option because it prevents malicious clients early from
 * flooding the server with useless data.
 */
public class BasicAuthCallContextHandler implements CallContextHandler, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public BasicAuthCallContextHandler() {
    }

    @Override
    public Map<String, String> getCallContextMap(HttpServletRequest request) {
        assert request != null;

        Map<String, String> result = null;

        String authHeader = request.getHeader("Authorization");
        if ((authHeader != null) && (authHeader.trim().toLowerCase(Locale.ENGLISH).startsWith("basic "))) {
            int x = authHeader.lastIndexOf(' ');
            if (x == -1) {
                return result;
            }

            String credentials = null;
            try {
                credentials = new String(Base64.decode(authHeader.substring(x + 1).getBytes(IOUtils.ISO_8859_1)),
                        IOUtils.UTF8);
            } catch (Exception e) {
                return result;
            }

            x = credentials.indexOf(':');
            if (x == -1) {
                return result;
            }

            // extract user and password and add them to map
            result = new HashMap<String, String>(2);
            result.put(CallContext.USERNAME, credentials.substring(0, x));
            result.put(CallContext.PASSWORD, credentials.substring(x + 1));
        }

        return result;
    }
}
