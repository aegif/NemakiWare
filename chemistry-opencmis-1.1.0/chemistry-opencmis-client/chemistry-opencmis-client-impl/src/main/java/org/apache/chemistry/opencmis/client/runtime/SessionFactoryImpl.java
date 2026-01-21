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
package org.apache.chemistry.opencmis.client.runtime;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.ObjectFactory;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.client.runtime.cache.Cache;
import org.apache.chemistry.opencmis.client.runtime.repository.RepositoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Default implementation of a session factory. Used by unit tests or
 * applications that depend directly on runtime implementation.
 * 
 * <pre>
 * {@code
 * SessionFactory sf = new SessionFactoryImpl();<br>
 * Session s = sf.createSession(...);
 * }
 * </pre>
 * <p>
 * Alternative factory lookup methods:
 * 
 * <pre>
 * {@code
 * Context ctx = new DefaultContext();<
 * SessionFactory sf = ctx.lookup(jndi_key);
 * }
 * </pre>
 */
public class SessionFactoryImpl implements SessionFactory, Serializable {

    private static final long serialVersionUID = 1L;

    protected SessionFactoryImpl() {
    }

    public static SessionFactoryImpl newInstance() {
        return new SessionFactoryImpl();
    }

    @Override
    public Session createSession(Map<String, String> parameters) {
        return createSession(parameters, null, null, null, null);
    }

    /**
     * Creates a new session. The provided object factory, authentication
     * provider and cache instance override the values in the session parameters
     * if they are not <code>null</code>.
     * 
     * @param parameters
     *            a {@code Map} of name/value pairs with parameters for the
     *            session
     * @param objectFactory
     *            an object factory instance
     * @param authenticationProvider
     *            an authentication provider instance
     * @param cache
     *            a cache instance
     * @param typeDefCache
     *            a type definition cache instance
     * @return a {@link Session} connected to the CMIS repository
     * @throws CmisBaseException
     *             if the connection could not be established
     * 
     * @see SessionParameter
     */
    public Session createSession(Map<String, String> parameters, ObjectFactory objectFactory,
            AuthenticationProvider authenticationProvider, Cache cache, TypeDefinitionCache typeDefCache) {
        SessionImpl session = new SessionImpl(parameters, objectFactory, authenticationProvider, cache, typeDefCache);
        session.connect();

        return session;
    }

    @Override
    public List<Repository> getRepositories(Map<String, String> parameters) {
        return getRepositories(parameters, null, null, null, null);
    }

    /**
     * Returns all repositories that are available at the endpoint. See
     * {@link #createSession(Map, ObjectFactory, AuthenticationProvider, Cache, TypeDefinitionCache)}
     * for parameter details. The parameter
     * {@code SessionParameter#REPOSITORY_ID} should not be set.
     */
    public List<Repository> getRepositories(Map<String, String> parameters, ObjectFactory objectFactory,
            AuthenticationProvider authenticationProvider, Cache cache, TypeDefinitionCache typeDefCache) {
        CmisBinding binding = CmisBindingHelper.createBinding(parameters, authenticationProvider, typeDefCache);

        List<RepositoryInfo> repositoryInfos = binding.getRepositoryService().getRepositoryInfos(null);

        List<Repository> result = new ArrayList<Repository>();
        for (RepositoryInfo data : repositoryInfos) {
            result.add(new RepositoryImpl(data, parameters, this, objectFactory, binding.getAuthenticationProvider(),
                    cache, typeDefCache));
        }

        return result;
    }
}
