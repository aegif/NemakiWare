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
package org.apache.chemistry.opencmis.client.bindings.spi.http;

import java.math.BigInteger;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A HTTP Invoker that delays HTTP requests and thereby throttles the client.
 * 
 * This HTTP Invoker is only a wrapper that delegates the work to another HTTP
 * Invoker, which is defined with the session parameter
 * {@link DELEGTAE_HTTP_INVOKER_CLASS}.
 * 
 * The session parameter {@link DELAY_TIME} defines the delay in milliseconds.
 */
public class DelayingHttpInvoker implements HttpInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(DelayingHttpInvoker.class);

    /**
     * Session parameter: class name of the HTTP Invoker doing the the real
     * work. (Optional. Default is the {@link DefaultHttpInvoker}.)
     */
    public static final String DELEGTAE_HTTP_INVOKER_CLASS = "org.apache.chemistry.opencmis.binding.httpinvoker.delay.delegate.classname";
    /**
     * Session parameter: Delay time in milliseconds. (Required.)
     */
    public static final String DELAY_TIME = "org.apache.chemistry.opencmis.binding.httpinvoker.delay.delaytime";

    protected static final String DELEGTAE_HTTP_INVOKER = "org.apache.chemistry.opencmis.client.bindings.spi.http.limiter.httpInvoker";
    protected static final String LAST_EXECUTION = "org.apache.chemistry.opencmis.client.bindings.spi.http.limiter.lastExecution";

    public DelayingHttpInvoker() {
    }

    @Override
    public Response invokeGET(UrlBuilder url, BindingSession session) {
        delay(session);
        return getHttpInvoker(session).invokeGET(url, session);
    }

    @Override
    public Response invokeGET(UrlBuilder url, BindingSession session, BigInteger offset, BigInteger length) {
        delay(session);
        return getHttpInvoker(session).invokeGET(url, session, offset, length);
    }

    @Override
    public Response invokePOST(UrlBuilder url, String contentType, Output writer, BindingSession session) {
        delay(session);
        return getHttpInvoker(session).invokePOST(url, contentType, writer, session);
    }

    @Override
    public Response invokePUT(UrlBuilder url, String contentType, Map<String, String> headers, Output writer,
            BindingSession session) {
        delay(session);
        return getHttpInvoker(session).invokePUT(url, contentType, headers, writer, session);
    }

    @Override
    public Response invokeDELETE(UrlBuilder url, BindingSession session) {
        delay(session);
        return getHttpInvoker(session).invokeDELETE(url, session);
    }

    protected void delay(BindingSession session) {
        session.writeLock();
        try {
            int delayTime = session.get(DELAY_TIME, -1);
            if (delayTime < 0) {
                if (LOG.isInfoEnabled()) {
                    LOG.info("No delay time configured.");
                }
                return;
            }

            Object lastExcution = session.get(LAST_EXECUTION);
            if (lastExcution instanceof Long) {
                long lastExcutionLong = (Long) lastExcution;
                long now = System.currentTimeMillis();
                if (now - lastExcutionLong < delayTime) {
                    try {
                        Thread.sleep(delayTime - (now - lastExcutionLong));
                    } catch (InterruptedException e) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Interrupted sleep", e);
                        }
                    }
                }
            }

            session.put(LAST_EXECUTION, System.currentTimeMillis());

        } finally {
            session.writeUnlock();
        }
    }

    protected HttpInvoker getHttpInvoker(BindingSession session) {
        HttpInvoker invoker = (HttpInvoker) session.get(DELEGTAE_HTTP_INVOKER);

        if (invoker != null) {
            return invoker;
        }

        session.writeLock();
        try {
            // try again
            invoker = (HttpInvoker) session.get(DELEGTAE_HTTP_INVOKER);
            if (invoker != null) {
                return invoker;
            }

            // ok, we have to create it...
            try {
                String invokerName = (String) session.get(DELEGTAE_HTTP_INVOKER_CLASS);
                if (invokerName == null) {
                    invoker = new DefaultHttpInvoker();
                } else {
                    invoker = (HttpInvoker) ClassLoaderUtil.loadClass(invokerName).newInstance();
                }
            } catch (CmisBaseException e) {
                throw e;
            } catch (Exception e) {
                throw new CmisRuntimeException("Delegate HTTP invoker cannot be initialized: " + e.getMessage(), e);
            }

            // we have an Invoker object -> put it into the session
            session.put(DELEGTAE_HTTP_INVOKER, invoker, true);
        } finally {
            session.writeUnlock();
        }

        return invoker;
    }
}
