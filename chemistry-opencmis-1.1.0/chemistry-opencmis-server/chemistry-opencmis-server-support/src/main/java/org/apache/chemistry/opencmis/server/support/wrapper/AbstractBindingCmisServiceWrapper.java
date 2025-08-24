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
package org.apache.chemistry.opencmis.server.support.wrapper;

import java.util.Date;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * This abstract service wrapper is intended for manipulating and replacing
 * server responses.
 */
public abstract class AbstractBindingCmisServiceWrapper extends AbstractCmisServiceWrapper {

    private Progress beforeCall = Progress.CONTINUE;
    private Progress afterCall = Progress.CONTINUE;

    public AbstractBindingCmisServiceWrapper(CmisService service) {
        super(service);
    }

    @Override
    public Progress beforeServiceCall() {
        return beforeCall;
    }

    @Override
    public Progress afterServiceCall() {
        return afterCall;
    }

    /**
     * Sets whether the server framework should continue before the service is
     * called.
     * 
     * @param progress
     *            {@link Progress#CONTINUE} if the server framework should
     *            continue, {@link Progress#STOP} if the server framework should
     *            stop
     */
    public void setBeforeServiceCall(Progress progress) {
        beforeCall = progress;
    }

    /**
     * Sets whether the server framework should continue after the service is
     * called.
     * 
     * @param progress
     *            {@link Progress#CONTINUE} if the server framework should
     *            continue, {@link Progress#STOP} if the server framework should
     *            stop
     */
    public void setAfterServiceCall(Progress progress) {
        afterCall = progress;
    }

    /**
     * Returns the binding type.
     * 
     * @return the binding type
     */
    public BindingType getBindingType() {
        String binding = getCallContext().getBinding();

        if (CallContext.BINDING_ATOMPUB.equals(binding)) {
            return BindingType.ATOMPUB;
        } else if (CallContext.BINDING_BROWSER.equals(binding)) {
            return BindingType.BROWSER;
        } else if (CallContext.BINDING_WEBSERVICES.equals(binding)) {
            return BindingType.WEBSERVICES;
        } else if (CallContext.BINDING_LOCAL.equals(binding)) {
            return BindingType.LOCAL;
        } else {
            return BindingType.CUSTOM;
        }
    }

    /**
     * Returns the {@link HttpServletRequest} object.
     * 
     * @return the {@link HttpServletRequest} object or {@code null} if the
     *         binding is a non-HTTP binding
     */
    public HttpServletRequest getHttpServletRequest() {
        return (HttpServletRequest) getCallContext().get(CallContext.HTTP_SERVLET_REQUEST);
    }

    /**
     * Returns the {@link HttpServletResponse} object.
     * 
     * @return the {@link HttpServletResponse} object or {@code null} if the
     *         binding is a non-HTTP binding
     */
    public HttpServletResponse getHttpServletResponse() {
        return (HttpServletResponse) getCallContext().get(CallContext.HTTP_SERVLET_RESPONSE);
    }

    /**
     * Gets a request header value as String.
     * 
     * @param name
     *            the header name
     * @return the header value or {@code null} if the header isn't set or if
     *         the binding is a non-HTTP binding
     */
    public String getRequestHeader(String name) {
        HttpServletRequest req = getHttpServletRequest();
        if (req == null) {
            return null;
        }

        return req.getHeader(name);
    }

    /**
     * Gets a request header value as Date.
     * 
     * @param name
     *            the header name
     * @return the header value or {@code null} if the header isn't set or if
     *         the binding is a non-HTTP binding or if the date cannot be parsed
     */
    public Date getDateRequestHeader(String name) {
        return DateTimeHelper.parseHttpDateTime(getRequestHeader(name));
    }

    /**
     * Sets a String response header.
     * 
     * If the binding is a non-HTTP binding, this method does nothing.
     * 
     * @param name
     *            the header name
     * @param value
     *            the header value
     */
    public void setResponseHeader(String name, String value) {
        HttpServletResponse resp = getHttpServletResponse();
        if (resp != null) {
            resp.setHeader(name, value);
        }
    }

    /**
     * Sets a Date response header.
     * 
     * If the binding is a non-HTTP binding, this method does nothing.
     * 
     * @param name
     *            the header name
     * @param value
     *            the header value
     */
    public void setResponseHeader(String name, Date value) {
        HttpServletResponse resp = getHttpServletResponse();
        if (resp != null) {
            resp.setHeader(name, DateTimeHelper.formatHttpDateTime(value));
        }
    }

    /**
     * Compares the provided date with the "If-Modified-Since" HTTP header (if
     * present) and returns whether the resource has been modified or not.
     * 
     * @param date
     *            date to compare the "If-Modified-Since" HTTP header to
     * 
     * @return {@code true} if the "If-Modified-Since" HTTP header is set and ,
     *         {@code false} otherwise
     */
    public boolean isNotModified(Date date) {
        if (date == null) {
            return false;
        }

        Date modifiedSince = getDateRequestHeader("If-Modified-Since");
        if (modifiedSince == null) {
            return false;
        }

        long dateSecs = (long) Math.floor((double) date.getTime() / 1000);
        long modifiedSinceSecs = (long) Math.floor((double) modifiedSince.getTime() / 1000);

        return dateSecs > modifiedSinceSecs;
    }
}
