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
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Call Context handler that takes the user from a request attribute.
 * 
 * If the current user is determined in a filter, the filter can set the
 * following attribute to pass down the user name to OpenCMIS with this
 * CallContextHandler.
 * 
 * <pre>
 * {@code
 *  request.setAttribute(AttributeCallContextHandler.USERNAME_ATTRIBUTE, user);
 * }
 * </pre>
 */
public class AttributeCallContextHandler implements CallContextHandler, Serializable {

    private static final long serialVersionUID = 1L;

    public static final String USERNAME_ATTRIBUTE = "org.apache.chemistry.opencmis.server.username";

    /**
     * Constructor.
     */
    public AttributeCallContextHandler() {
    }

    @Override
    public Map<String, String> getCallContextMap(HttpServletRequest request) {
        assert request != null;

        Map<String, String> result = null;

        if (request.getAttribute(USERNAME_ATTRIBUTE) != null) {
            result = new HashMap<String, String>(2);
            result.put(CallContext.USERNAME, request.getAttribute(USERNAME_ATTRIBUTE).toString());
        }

        return result;
    }
}
