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

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;

public final class HttpUtils {

    private HttpUtils() {
    }

    /**
     * Extracts a string parameter.
     */
    public static String getStringParameter(final HttpServletRequest request, final String name) {
        assert request != null;

        if (name == null) {
            return null;
        }

        Map<String, String[]> parameters = request.getParameterMap();

        if (parameters != null) {
            for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
                if (name.equalsIgnoreCase(parameter.getKey())) {
                    if (parameter.getValue() == null) {
                        return null;
                    }
                    return parameter.getValue()[0];
                }
            }
        }

        return null;
    }

    /**
     * Splits the path into its fragments.
     */
    public static String[] splitPath(final HttpServletRequest request) {
        assert request != null;

        int prefixLength = request.getContextPath().length() + request.getServletPath().length();
        String p = request.getRequestURI().substring(prefixLength);

        if (p.length() == 0) {
            return new String[0];
        }

        String[] result = p.substring(1).split("/");
        for (int i = 0; i < result.length; i++) {
            result[i] = IOUtils.decodeURL(result[i]);

            // check for malicious characters
            for (int j = 0; j < result[i].length(); j++) {
                char c = result[i].charAt(j);
                if (c == '\n' || c == '\r' || c == '\b' || c == 0) {
                    throw new CmisInvalidArgumentException("Invalid path!");
                }
            }
        }

        return result;
    }
}
