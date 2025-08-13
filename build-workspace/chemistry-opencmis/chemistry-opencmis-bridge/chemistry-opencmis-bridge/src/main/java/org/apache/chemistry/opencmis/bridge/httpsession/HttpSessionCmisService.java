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
package org.apache.chemistry.opencmis.bridge.httpsession;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.http.HttpSession;

import org.apache.chemistry.opencmis.bridge.CachedBindingCmisService;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Uses HTTP sessions to cache {@link CmisBinding} objects.
 */
public abstract class HttpSessionCmisService extends CachedBindingCmisService {

    private static final long serialVersionUID = 1L;

    /** Key in the HTTP session. **/
    public static final String CMIS_BINDING = "org.apache.chemistry.opencmis.bridge.binding";

    private ReentrantReadWriteLock lock;

    public void init(Map<String, String> parameters, ReentrantReadWriteLock lock) {
        init(parameters);
        this.lock = lock;
    }

    @Override
    public CmisBinding getCmisBindingFromCache() {
        HttpSession httpSession = getHttpSession(false);
        if (httpSession == null) {
            return null;
        }

        lock.readLock().lock();
        try {
            return (CmisBinding) httpSession.getAttribute(CMIS_BINDING);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CmisBinding putCmisBindingIntoCache(CmisBinding binding) {
        HttpSession httpSession = getHttpSession(true);

        lock.writeLock().lock();
        try {
            CmisBinding existingBinding = (CmisBinding) httpSession.getAttribute(CMIS_BINDING);
            if (existingBinding == null) {
                httpSession.setAttribute(CMIS_BINDING, binding);
            } else {
                binding = existingBinding;
            }

            return binding;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the current {@link HttpSession}.
     * 
     * @param create
     *            <code>true</code> to create a new session, <code>false</code>
     *            to return <code>null</code> if there is no current session
     */
    public HttpSession getHttpSession(boolean create) {
        return getHttpServletRequest().getSession(create);
    }
}
