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
package org.apache.chemistry.opencmis.server.async.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;

import org.apache.chemistry.opencmis.server.async.AsyncCmisExecutor;

/**
 * A simple {@link AsyncCmisExecutor} implementation that uses a
 * {@code ThreadPoolExecutor} for executing asynchronous CMIS requests.
 */
public class SimpleAsyncCmisExecutor implements AsyncCmisExecutor {

    private static int cmisThreadInitNumber = 0;

    private ExecutorService executorService;
    private long timeout;

    public SimpleAsyncCmisExecutor() {
        executorService = createExecutorService();
        timeout = 24 * 60 * 60 * 1000; // 24 hours
    }

    private static synchronized int nextThreadNum() {
        return cmisThreadInitNumber++;
    }

    /**
     * Creates an ExecutorService instance.
     */
    private ExecutorService createExecutorService() {
        final ThreadGroup threadGroup = new ThreadGroup("cmis-thread-group");
        final ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(threadGroup, r, "cmis-" + nextThreadNum());
            }
        };

        int processors = Runtime.getRuntime().availableProcessors();
        int maximumPoolSize = processors * 100;
        int corePoolSize = processors;
        int queueSize = maximumPoolSize / 2;

        ThreadPoolExecutor threadPoolexecutor = new ThreadPoolExecutor(corePoolSize, maximumPoolSize, 60L,
                TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(queueSize), threadFactory);

        return threadPoolexecutor;
    }

    /**
     * Waits until all running threads are stopped.
     */
    public void destroy() {
        executorService.shutdown();
    }

    /**
     * Gets the timeout for the AsyncContext.
     * 
     * @return the timeout in milliseconds
     */
    public long getTimeout() {
        return timeout;
    }

    /**
     * Sets the timeout for the AsyncContext.
     * 
     * @param timeout
     *            the timeout in milliseconds
     * 
     * @see AsyncContext#setTimeout(long)
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    @Override
    public void execute(AsyncContext asyncContext, Runnable runable) {
        asyncContext.setTimeout(timeout);
        asyncContext.addListener(new SimpleCmisAsyncListener());

        executorService.submit(runable);
    }
}
