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
package org.apache.chemistry.opencmis.server.impl.browser.token;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.shared.BasicAuthCallContextHandler;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;

public class TokenCallContextHandler extends BasicAuthCallContextHandler implements TokenHandler {

    private static final long serialVersionUID = 1L;

    private final TokenHandler tokenHandler;

    /**
     * Constructor.
     */
    public TokenCallContextHandler() {
        tokenHandler = new SimpleTokenHandler();
    }

    @Override
    public Map<String, String> getCallContextMap(HttpServletRequest request) {
        Map<String, String> result = new HashMap<String, String>();

        Map<String, String> basicAuthMap = super.getCallContextMap(request);
        if (basicAuthMap != null && !basicAuthMap.isEmpty()) {
            result.putAll(basicAuthMap);
        }

        // lastResult must always provide an old token
        // -> don't check the token
        boolean isLastResultRequest = "lastresult".equalsIgnoreCase(HttpUtils.getStringParameter(request,
                Constants.PARAM_SELECTOR));

        if (!isLastResultRequest) {
            // if a token is provided, check it
            if (request.getParameter(Constants.PARAM_TOKEN) != null) {
                if (SimpleTokenHandlerSessionHelper.testAndInvalidateToken(request)) {
                    String token = SimpleTokenHandlerSessionHelper.getToken(request);
                    String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(token);
                    result.put(CallContext.USERNAME, SimpleTokenHandlerSessionHelper.getUser(request, appId));
                    result.put(CallContext.PASSWORD, null);
                } else {
                    throw new CmisPermissionDeniedException("Invalid token!");
                }
            }

            if (!result.containsKey(CallContext.USERNAME)) {
                // neither basic authentication nor token authentication have
                // returned a username -> reject request
                throw new CmisPermissionDeniedException("No authentication!");
            }
        }

        return result;
    }

    @Override
    public void service(ServletContext servletContext, HttpServletRequest request, HttpServletResponse response) {
        tokenHandler.service(servletContext, request, response);
    }
}
