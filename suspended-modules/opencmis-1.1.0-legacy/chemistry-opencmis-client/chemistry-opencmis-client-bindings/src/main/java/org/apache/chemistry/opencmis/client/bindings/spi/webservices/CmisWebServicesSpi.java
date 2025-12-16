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
package org.apache.chemistry.opencmis.client.bindings.spi.webservices;

import jakarta.xml.ws.spi.Provider;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.CmisSpi;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
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

/**
 * CMIS Web Services SPI implementation.
 */
public class CmisWebServicesSpi implements CmisSpi {

    public static final String JAXWS_IMPL_RI = "sunri";
    public static final String JAXWS_IMPL_JRE = "sunjre";
    public static final String JAXWS_IMPL_CXF = "cxf";
    public static final String JAXWS_IMPL_WEBSPHERE = "websphere";
    public static final String JAXWS_IMPL_AXIS2 = "axis2";

    private static final Logger LOG = LoggerFactory.getLogger(CmisWebServicesSpi.class);

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
    public CmisWebServicesSpi(BindingSession session) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session {}: Initializing Web Services SPI...", session.getSessionId());
        }

        AbstractPortProvider portProvider = null;

        String portProviderClass = (String) session.get(SessionParameter.WEBSERVICES_PORT_PROVIDER_CLASS);
        if (portProviderClass == null) {
            String jaxwsImpl = (String) session.get(SessionParameter.WEBSERVICES_JAXWS_IMPL);

            if (jaxwsImpl == null) {
                jaxwsImpl = System.getProperty("org.apache.chemistry.opencmis.binding.webservices.jaxws.impl");
            }

            if (jaxwsImpl == null) {
                Provider provider = Provider.provider();
                if (provider == null) {
                    throw new CmisRuntimeException("No JAX-WS implementation found!");
                }

                String providerPackage = provider.getClass().getPackage().getName();

                if (providerPackage.startsWith("com.sun.xml.internal.ws.spi")) {
                    throw new CmisRuntimeException(
                            "JRE JAX-WS implementation not supported anymore. Please use Apache CXF.");
                } else if (providerPackage.startsWith("com.sun.xml.ws.spi")) {
                    throw new CmisRuntimeException("JAX-WS RI not supported anymore. Please use Apache CXF.");
                } else if (providerPackage.startsWith("org.apache.cxf.jaxws")) {
                    portProvider = new CXFPortProvider();
                } else if (providerPackage.startsWith("org.apache.axis2.jaxws.spi")) {
                    throw new CmisRuntimeException("Axis2 not supported anymore. Please use Apache CXF.");
                } else {
                    throw new CmisRuntimeException("Could not detect JAX-WS implementation! Use session parameter "
                            + SessionParameter.WEBSERVICES_JAXWS_IMPL + " to specify one.");
                }
            } else if (JAXWS_IMPL_JRE.equals(jaxwsImpl)) {
                throw new CmisRuntimeException(
                        "JRE JAX-WS implementation not supported anymore. Please use Apache CXF.");
            } else if (JAXWS_IMPL_RI.equals(jaxwsImpl)) {
                throw new CmisRuntimeException("JAX-WS RI not supported anymore. Please use Apache CXF.");
            } else if (JAXWS_IMPL_CXF.equals(jaxwsImpl)) {
                portProvider = new CXFPortProvider();
            } else if (JAXWS_IMPL_WEBSPHERE.equals(jaxwsImpl)) {
                portProvider = new WebSpherePortProvider();
            } else if (JAXWS_IMPL_AXIS2.equals(jaxwsImpl)) {
                throw new CmisRuntimeException("Axis2 not supported anymore. Please use Apache CXF.");
            } else {
                throw new CmisRuntimeException("Unknown JAX-WS implementation specified!");
            }
        } else {
            Object portProviderObj = null;

            try {
                portProviderObj = ClassLoaderUtil.loadClass(portProviderClass).newInstance();
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not load port provider: " + e, e);
            }

            if (!(portProviderObj instanceof AbstractPortProvider)) {
                throw new IllegalArgumentException("Port provider does not implement AbstractPortProvider!");
            }
            portProvider = (AbstractPortProvider) portProviderObj;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Session {}: Port provider class: {}", session.getSessionId(), portProvider.getClass().getName());
        }

        portProvider.setSession(session);

        repositoryService = new RepositoryServiceImpl(session, portProvider);
        navigationService = new NavigationServiceImpl(session, portProvider);
        objectService = new ObjectServiceImpl(session, portProvider);
        versioningService = new VersioningServiceImpl(session, portProvider);
        discoveryService = new DiscoveryServiceImpl(session, portProvider);
        multiFilingService = new MultiFilingServiceImpl(session, portProvider);
        relationshipService = new RelationshipServiceImpl(session, portProvider);
        policyService = new PolicyServiceImpl(session, portProvider);
        aclService = new AclServiceImpl(session, portProvider);
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
    public DiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    @Override
    public VersioningService getVersioningService() {
        return versioningService;
    }

    @Override
    public MultiFilingService getMultiFilingService() {
        return multiFilingService;
    }

    @Override
    public RelationshipService getRelationshipService() {
        return relationshipService;
    }

    @Override
    public PolicyService getPolicyService() {
        return policyService;
    }

    @Override
    public AclService getAclService() {
        return aclService;
    }

    @Override
    public void clearAllCaches() {
    }

    @Override
    public void clearRepositoryCache(String repositoryId) {
    }

    @Override
    public void close() {
        // no-op for Web Services
    }
}
