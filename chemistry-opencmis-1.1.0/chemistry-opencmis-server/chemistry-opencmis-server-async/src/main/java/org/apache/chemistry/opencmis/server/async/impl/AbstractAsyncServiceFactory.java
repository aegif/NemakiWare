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
package org.apache.chemistry.opencmis.server.async.impl;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.server.async.AsyncCmisExecutor;
import org.apache.chemistry.opencmis.server.async.AsyncCmisServiceFactory;

/**
 * An {@link AsyncCmisServiceFactory} implementation that sets up one simple
 * {@code ThreadPoolExecutor} for executing asynchronous all CMIS requests.
 */
public abstract class AbstractAsyncServiceFactory extends AbstractServiceFactory implements AsyncCmisServiceFactory {

    private SimpleAsyncCmisExecutor executor;

    @Override
    public void init(Map<String, String> parameters) {
        super.init(parameters);
        executor = new SimpleAsyncCmisExecutor();
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.destroy();
        }

        super.destroy();
    }

    @Override
    public AsyncCmisExecutor getAsyncCmisExecutor(HttpServletRequest request, HttpServletResponse response) {
        // all requests share one thread pool
        return executor;
    }
}
