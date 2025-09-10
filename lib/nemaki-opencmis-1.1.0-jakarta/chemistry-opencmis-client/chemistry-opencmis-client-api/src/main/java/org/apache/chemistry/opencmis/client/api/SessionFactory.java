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
package org.apache.chemistry.opencmis.client.api;

import java.util.List;
import java.util.Map;

/**
 * Entry point into the OpenCMIS Client API.
 * <p>
 * There might be different ways to get a {@code SessionFactory} instance. For
 * example it could be retrieved via a J2EE JNDI lookup or an OSGi service
 * lookup. Clients outside a container might use the
 * {@link org.apache.chemistry.opencmis.client.SessionFactoryFinder} class.
 * <p>
 * The entries of the parameter map are defined by
 * {@link org.apache.chemistry.opencmis.commons.SessionParameter} class which is
 * part of the commons package. Parameters specify connection settings (user
 * name, authentication, connection URL, binding type, etc.).
 * </p>
 * <p>
 * The {@link Session} class which is constructed is either the {@code session}
 * base class which is the default implementation or it can be derived from that
 * implementing special behavior for the session.
 * </p>
 * <p>
 * Sample code:
 * </p>
 * 
 * <pre>
 * SessionFactory factory = ...
 * 
 * Map&lt;String, String&gt; parameter = new HashMap&lt;String, String&gt;();
 * 
 * parameter.put(SessionParameter.USER, "Otto");
 * parameter.put(SessionParameter.PASSWORD, "****");
 * 
 * parameter.put(SessionParameter.ATOMPUB_URL, "http://localhost/cmis/atom");
 * parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
 * parameter.put(SessionParameter.REPOSITORY_ID, "myRepository");
 * ...
 * Session session = factory.createSession(parameter);
 * </pre>
 * 
 * @see org.apache.chemistry.opencmis.client.SessionFactoryFinder
 * @see org.apache.chemistry.opencmis.commons.SessionParameter
 * @see org.apache.chemistry.opencmis.client.SessionParameterMap
 * @see Session
 */
public interface SessionFactory {

    /**
     * Creates a new session.
     * 
     * @param parameters
     *            a map of name/value pairs with parameters for the session, see
     *            {@link org.apache.chemistry.opencmis.commons.SessionParameter}
     *            for parameters supported by OpenCMIS
     * 
     * 
     * @return a {@link Session} connected to the CMIS repository, never
     *         {@code null}
     * 
     * @see org.apache.chemistry.opencmis.commons.SessionParameter
     */
    Session createSession(Map<String, String> parameters);

    /**
     * Returns all repositories that are available at the endpoint.
     * 
     * 
     * @param parameters
     *            a map of name/value pairs with parameters for the session, see
     *            {@link org.apache.chemistry.opencmis.commons.SessionParameter}
     *            for parameters supported by OpenCMIS, the parameter
     *            {@link org.apache.chemistry.opencmis.commons.SessionParameter#REPOSITORY_ID}
     *            should not be set
     * 
     * @return a list of all available repositories, never {@code null}
     * 
     * @see org.apache.chemistry.opencmis.commons.SessionParameter
     */
    List<Repository> getRepositories(Map<String, String> parameters);
}
