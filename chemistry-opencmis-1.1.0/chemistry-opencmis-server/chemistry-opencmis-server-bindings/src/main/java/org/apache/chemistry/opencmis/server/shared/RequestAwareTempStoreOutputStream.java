/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.server.shared;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.TempStoreOutputStream;

/**
 * A {@link TempStoreOutputStream} that knows about the HTTP request
 */
public abstract class RequestAwareTempStoreOutputStream extends TempStoreOutputStream {

    /**
     * Sets the HTTP request object.
     * 
     * This method is usually be called right after creation of this object. It
     * might never be called if the HTTP request object is unknown or this
     * object is not used in the context of a HTTP request.
     * 
     * @param request
     *            the HTTP request object or {@code null} if the HTTP request
     *            object is unknown or this object is not used in the context of
     *            a HTTP request
     */
    public abstract void setHttpServletRequest(HttpServletRequest request);
}
