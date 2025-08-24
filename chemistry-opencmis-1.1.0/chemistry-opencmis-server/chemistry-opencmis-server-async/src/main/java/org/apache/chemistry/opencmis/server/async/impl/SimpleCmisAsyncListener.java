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

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.annotation.WebListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple listener for asynchronous events (for debugging and error messages).
 */
@WebListener
public class SimpleCmisAsyncListener implements AsyncListener {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleCmisAsyncListener.class);

    @Override
    public void onComplete(AsyncEvent asyncEvent) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Request complete.");
        }
    }

    @Override
    public void onError(AsyncEvent asyncEvent) throws IOException {
        LOG.error("Request failed!", asyncEvent.getThrowable());
    }

    @Override
    public void onStartAsync(AsyncEvent asyncEvent) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Request starts.");
        }
    }

    @Override
    public void onTimeout(AsyncEvent asyncEvent) throws IOException {
        LOG.error("Request timed out!", asyncEvent.getThrowable());
    }
}
