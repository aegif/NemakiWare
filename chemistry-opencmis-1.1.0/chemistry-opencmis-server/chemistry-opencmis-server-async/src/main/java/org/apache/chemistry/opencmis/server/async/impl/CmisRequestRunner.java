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

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runner that executes an asynchronous CMIS request.
 */
public class CmisRequestRunner implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(CmisRequestRunner.class);

    private final AsyncContext asyncContext;
    private final AsyncCmisServlet asyncServlet;

    public CmisRequestRunner(AsyncContext asyncContext, AsyncCmisServlet asyncServlet) {
        this.asyncContext = asyncContext;
        this.asyncServlet = asyncServlet;
    }

    @Override
    public void run() {
        try {
            asyncServlet.executeSync((HttpServletRequest) asyncContext.getRequest(),
                    (HttpServletResponse) asyncContext.getResponse());
        } catch (Exception e) {
            LOG.error("Async Excpetion: {}", e.toString(), e);

            try {
                asyncServlet.sendError(e, (HttpServletRequest) asyncContext.getRequest(),
                        (HttpServletResponse) asyncContext.getResponse());
            } catch (IOException ioe) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Failed to send error responds: {}", ioe.toString(), ioe);
                }
            }
        } finally {
            asyncContext.complete();
        }
    }
}
