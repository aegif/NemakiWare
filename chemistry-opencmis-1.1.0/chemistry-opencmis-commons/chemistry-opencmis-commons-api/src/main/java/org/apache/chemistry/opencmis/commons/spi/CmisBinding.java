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
package org.apache.chemistry.opencmis.commons.spi;

import java.io.Serializable;

import org.apache.chemistry.opencmis.commons.enums.BindingType;

/**
 * Entry point for all CMIS binding related operations. It provides access to
 * the service interface objects which are very similar to the CMIS 1.0 domain
 * model.
 * 
 * <p>
 * Each instance of this class represents a session. A session comprises of a
 * connection to one CMIS endpoint over one binding for one particular user and
 * a set of caches. All repositories that are exposed by this CMIS endpoint are
 * accessible in this session. All CMIS operations and extension points are
 * provided if they are supported by the underlying binding.
 * </p>
 */
public interface CmisBinding extends Serializable {

    /**
     * Returns the client session id.
     */
    String getSessionId();

    /**
     * Returns the binding type.
     */
    BindingType getBindingType();

    /**
     * Gets a Repository Service interface object.
     */
    RepositoryService getRepositoryService();

    /**
     * Gets a Navigation Service interface object.
     */
    NavigationService getNavigationService();

    /**
     * Gets an Object Service interface object.
     */
    ObjectService getObjectService();

    /**
     * Gets a Versioning Service interface object.
     */
    VersioningService getVersioningService();

    /**
     * Gets a Relationship Service interface object.
     */
    RelationshipService getRelationshipService();

    /**
     * Gets a Discovery Service interface object.
     */
    DiscoveryService getDiscoveryService();

    /**
     * Gets a Multifiling Service interface object.
     */
    MultiFilingService getMultiFilingService();

    /**
     * Gets an ACL Service interface object.
     */
    AclService getAclService();

    /**
     * Gets a Policy Service interface object.
     */
    PolicyService getPolicyService();

    /**
     * Gets a factory for CMIS binding specific objects.
     */
    BindingsObjectFactory getObjectFactory();

    /**
     * Gets the authentication provider.
     */
    AuthenticationProvider getAuthenticationProvider();

    /**
     * Clears all caches of the current CMIS binding session.
     */
    void clearAllCaches();

    /**
     * Clears all caches of the current CMIS binding session that are related to
     * the given repository.
     * 
     * @param repositoryId
     *            the repository id
     */
    void clearRepositoryCache(String repositoryId);

    /**
     * Releases all resources assigned to this binding instance.
     */
    void close();
}
