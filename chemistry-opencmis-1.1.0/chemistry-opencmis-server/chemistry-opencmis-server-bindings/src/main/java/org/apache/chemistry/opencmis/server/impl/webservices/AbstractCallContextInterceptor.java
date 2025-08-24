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

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.phase.Phase;

/**
 * Base class for all interceptors that add data to the call context.
 */
public abstract class AbstractCallContextInterceptor extends AbstractSoapInterceptor {

    public AbstractCallContextInterceptor() {
        super(Phase.PRE_INVOKE);
    }

    /**
     * Returns the current call context map.
     * 
     * @return the call context map or {@code null} if there isn't one
     */
    @SuppressWarnings("unchecked")
    protected Map<String, String> getCurrentCallContextMap(SoapMessage message) {
        Object callContextMapObject = message.getExchange().getInMessage().get(AbstractService.CALL_CONTEXT_MAP);

        if (callContextMapObject instanceof Map) {
            return (Map<String, String>) callContextMapObject;
        }

        return null;
    }

    /**
     * Sets a new call context map.
     */
    protected void setCallContextMap(SoapMessage message, Map<String, String> callContextMap) {
        message.getExchange().getInMessage().put(AbstractService.CALL_CONTEXT_MAP, callContextMap);
    }

    /**
     * Returns the current user.
     */
    protected String getCurrentUser(SoapMessage message) {
        Map<String, String> callContextMap = getCurrentCallContextMap(message);
        if (callContextMap != null) {
            return callContextMap.get(CallContext.USERNAME);
        }

        return null;
    }

    /**
     * Adds data to the current call context map.
     */
    protected void addToCurrentCallContextMap(SoapMessage message, Map<String, String> callContextMapAdditions) {
        Map<String, String> callContextMap = getCurrentCallContextMap(message);
        if (callContextMap == null) {
            callContextMap = new HashMap<String, String>();
        }

        callContextMap.putAll(callContextMapAdditions);

        setCallContextMap(message, callContextMap);
    }

    /**
     * Adds a user and a password to the current call context map.
     */
    protected void setUserAndPassword(SoapMessage message, String user, String password) {
        Map<String, String> callContextMap = getCurrentCallContextMap(message);
        if (callContextMap == null) {
            callContextMap = new HashMap<String, String>();
        }

        callContextMap.put(CallContext.USERNAME, user);
        callContextMap.put(CallContext.PASSWORD, password);

        setCallContextMap(message, callContextMap);
    }

    /**
     * Adds just a user to the current call context map.
     */
    protected void setUser(SoapMessage message, String user) {
        Map<String, String> callContextMap = getCurrentCallContextMap(message);
        if (callContextMap == null) {
            callContextMap = new HashMap<String, String>();
        }

        callContextMap.put(CallContext.USERNAME, user);

        setCallContextMap(message, callContextMap);
    }
}
