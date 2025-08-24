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
package org.apache.chemistry.opencmis.client.runtime.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.chemistry.opencmis.client.api.AsyncSession;
import org.apache.chemistry.opencmis.client.api.Session;

/**
 * An implementation of the {@link AsyncSession} interface that uses an
 * {@link ThreadPoolExecutor} object for running asynchronous tasks.
 */
public class ThreadPoolExecutorAsyncSession extends AbstractExecutorServiceAsyncSession<ThreadPoolExecutor> {

    private ThreadPoolExecutor executor;

    public ThreadPoolExecutorAsyncSession(Session session) {
        this(session, 5);
    }

    public ThreadPoolExecutorAsyncSession(Session session, int maxThreads) {
        super(session);
        executor = new ThreadPoolExecutor(maxThreads, maxThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
    }

    @Override
    public ThreadPoolExecutor getExecutorService() {
        return executor;
    }
}
