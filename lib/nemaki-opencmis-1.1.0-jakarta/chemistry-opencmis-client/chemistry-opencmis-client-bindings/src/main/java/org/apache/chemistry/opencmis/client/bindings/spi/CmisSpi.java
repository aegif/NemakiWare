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
package org.apache.chemistry.opencmis.client.bindings.spi;

import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.MultiFilingService;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.chemistry.opencmis.commons.spi.PolicyService;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

/**
 * CMIS SPI interface.
 */
public interface CmisSpi {
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
     * Clears all caches of the current session.
     */
    void clearAllCaches();

    /**
     * Clears all caches of the current session that are related to the given
     * repository.
     * 
     * @param repositoryId
     *            the repository id
     */
    void clearRepositoryCache(String repositoryId);

    /**
     * Releases all resources assigned to this SPI instance.
     */
    void close();
}
