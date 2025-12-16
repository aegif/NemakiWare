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
package org.apache.chemistry.opencmis.client.bindings.spi.browser;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.CmisSpi;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.MultiFilingService;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.chemistry.opencmis.commons.spi.PolicyService;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CmisBrowserBindingSpi implements CmisSpi {

    private static final Logger LOG = LoggerFactory.getLogger(CmisBrowserBindingSpi.class);

    private final BindingSession session;

    private final RepositoryService repositoryService;
    private final NavigationService navigationService;
    private final ObjectService objectService;
    private final VersioningService versioningService;
    private final DiscoveryService discoveryService;
    private final MultiFilingService multiFilingService;
    private final RelationshipService relationshipService;
    private final PolicyService policyService;
    private final AclService aclService;

    /**
     * Constructor.
     */
    public CmisBrowserBindingSpi(BindingSession session) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session {}: Initializing Browser Binding SPI...", session.getSessionId());
        }

        this.session = session;

        repositoryService = new RepositoryServiceImpl(session);
        navigationService = new NavigationServiceImpl(session);
        objectService = new ObjectServiceImpl(session);
        versioningService = new VersioningServiceImpl(session);
        discoveryService = new DiscoveryServiceImpl(session);
        multiFilingService = new MultiFilingServiceImpl(session);
        relationshipService = new RelationshipServiceImpl(session);
        policyService = new PolicyServiceImpl(session);
        aclService = new AclServiceImpl(session);
    }

    @Override
    public RepositoryService getRepositoryService() {
        return repositoryService;
    }

    @Override
    public NavigationService getNavigationService() {
        return navigationService;
    }

    @Override
    public ObjectService getObjectService() {
        return objectService;
    }

    @Override
    public VersioningService getVersioningService() {
        return versioningService;
    }

    @Override
    public RelationshipService getRelationshipService() {
        return relationshipService;
    }

    @Override
    public DiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    @Override
    public MultiFilingService getMultiFilingService() {
        return multiFilingService;
    }

    @Override
    public AclService getAclService() {
        return aclService;
    }

    @Override
    public PolicyService getPolicyService() {
        return policyService;
    }

    @Override
    public void clearAllCaches() {
        session.remove(SpiSessionParameter.REPOSITORY_URL_CACHE);
    }

    @Override
    public void clearRepositoryCache(String repositoryId) {
        RepositoryUrlCache repUrlCache = (RepositoryUrlCache) session.get(SpiSessionParameter.REPOSITORY_URL_CACHE);
        if (repUrlCache != null) {
            repUrlCache.removeRepository(repositoryId);
        }
    }

    @Override
    public void close() {
        // no-op for Browser Binding
    }
}
