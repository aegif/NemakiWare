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
package org.apache.chemistry.opencmis.server.async;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;

/**
 * Factory for {@link CmisService} objects that support asynchronous execution.
 */
public interface AsyncCmisServiceFactory extends CmisServiceFactory {

    /**
     * Returns an {@link AsyncCmisExecutor} instance that handles the
     * asynchronous execution of this request.
     *
     * If this method returns {@code null} the request is executed
     * synchronously.
     * 
     * @param request
     *            the request object
     * @param response
     *            the response object
     * 
     * @return the {@link AsyncCmisExecutor} instance or {@code null}
     */
    AsyncCmisExecutor getAsyncCmisExecutor(HttpServletRequest request, HttpServletResponse response);
}
