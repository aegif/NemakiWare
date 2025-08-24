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

import org.apache.chemistry.opencmis.client.api.AsyncSession;
import org.apache.chemistry.opencmis.client.api.AsyncSessionFactory;
import org.apache.chemistry.opencmis.client.api.Session;

/**
 * Factory for {@link AsyncSession} objects.
 */
public class AsyncSessionFactoryImpl implements AsyncSessionFactory {

    protected AsyncSessionFactoryImpl() {
    }

    public static AsyncSessionFactoryImpl newInstance() {
        return new AsyncSessionFactoryImpl();
    }

    @Override
    public AsyncSession createAsyncSession(Session session) {
        return createAsyncSession(session, 5);
    }

    @Override
    public AsyncSession createAsyncSession(Session session, int maxParallelRequests) {
        if (session == null) {
            throw new IllegalArgumentException("Session must be set!");
        }

        if (maxParallelRequests < 1) {
            throw new IllegalArgumentException("maxParallelRequests must be >0!");
        }

        return new ThreadPoolExecutorAsyncSession(session, maxParallelRequests);
    }
}
