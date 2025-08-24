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
package org.apache.chemistry.opencmis.server.impl.webservices;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.server.shared.CallContextHandler;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.transport.http.AbstractHTTPDestination;

/**
 * Adds data form a {@link CallContextHandler} if one is configured.
 */
public class CallContextHandlerInterceptor extends AbstractCallContextInterceptor {

    private final CallContextHandler callContextHandler;

    public CallContextHandlerInterceptor(CallContextHandler callContextHandler) {
        super();
        this.callContextHandler = callContextHandler;
    }

    @Override
    public void handleMessage(SoapMessage message) {
        if (callContextHandler == null) {
            return;
        }

        HttpServletRequest request = (HttpServletRequest) message.get(AbstractHTTPDestination.HTTP_REQUEST);
        if (request == null) {
            return;
        }

        Map<String, String> callContextMap = callContextHandler.getCallContextMap(request);
        if (callContextMap == null || callContextMap.isEmpty()) {
            return;
        }

        addToCurrentCallContextMap(message, callContextMap);
    }
}
