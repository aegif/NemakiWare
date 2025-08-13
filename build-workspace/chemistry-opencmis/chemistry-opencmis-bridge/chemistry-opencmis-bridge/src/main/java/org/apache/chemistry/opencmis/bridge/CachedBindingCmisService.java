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
package org.apache.chemistry.opencmis.bridge;

import javax.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.MultiFilingService;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.chemistry.opencmis.commons.spi.PolicyService;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

/**
 * Provides a framework to cache a {@link CmisBinding} object for a
 * {@link FilterCmisService}.
 */
public abstract class CachedBindingCmisService extends FilterCmisService {

    private static final long serialVersionUID = 1L;

    private CmisBinding clientBinding;

    @Override
    public void setCallContext(CallContext context) {
        super.setCallContext(context);

        clientBinding = getCmisBindingFromCache();
        if (clientBinding == null) {
            clientBinding = putCmisBindingIntoCache(createCmisBinding());
        }
    }

    /**
     * Returns a cached {@link CmisBinding} object or <code>null</code> if no
     * appropriate object can be found in the cache.
     */
    public abstract CmisBinding getCmisBindingFromCache();

    /**
     * Puts the provided {@link CmisBinding} object into the cache and
     * associates it somehow with the current {@link CallContext}.
     * 
     * The implementation may return another {@link CmisBinding} object if
     * another thread has already added an object for the current
     * {@link CallContext}.
     */
    public abstract CmisBinding putCmisBindingIntoCache(CmisBinding binding);

    /**
     * Creates a new {@link CmisBinding} object based on the current
     * {@link CallContext}.
     */
    public abstract CmisBinding createCmisBinding();

    /**
     * Returns the current {@link CmisBinding} object.
     */
    public CmisBinding getCmisBinding() {
        return clientBinding;
    }

    /**
     * Returns the current {@link HttpServletRequest}.
     */
    public HttpServletRequest getHttpServletRequest() {
        return (HttpServletRequest) getCallContext().get(CallContext.HTTP_SERVLET_REQUEST);
    }

    @Override
    public RepositoryService getRepositoryService() {
        return clientBinding.getRepositoryService();
    }

    @Override
    public NavigationService getNavigationService() {
        return clientBinding.getNavigationService();
    }

    @Override
    public ObjectService getObjectService() {
        return clientBinding.getObjectService();
    }

    @Override
    public VersioningService getVersioningService() {
        return clientBinding.getVersioningService();
    }

    @Override
    public DiscoveryService getDiscoveryService() {
        return clientBinding.getDiscoveryService();
    }

    @Override
    public MultiFilingService getMultiFilingService() {
        return clientBinding.getMultiFilingService();
    }

    @Override
    public RelationshipService getRelationshipService() {
        return clientBinding.getRelationshipService();
    }

    @Override
    public AclService getAclService() {
        return clientBinding.getAclService();
    }

    @Override
    public PolicyService getPolicyService() {
        return clientBinding.getPolicyService();
    }

    @Override
    public void close() {
        super.close();
        clientBinding = null;
    }
}
