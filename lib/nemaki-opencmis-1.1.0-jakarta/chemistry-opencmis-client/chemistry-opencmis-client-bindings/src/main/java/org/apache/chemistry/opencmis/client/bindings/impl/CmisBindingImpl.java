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
package org.apache.chemistry.opencmis.client.bindings.impl;

import java.io.Serializable;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.CmisSpi;
import org.apache.chemistry.opencmis.client.bindings.spi.SessionAwareAuthenticationProvider;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BindingsObjectFactoryImpl;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
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
 * CMIS binding implementation.
 */
public final class CmisBindingImpl implements CmisBinding, Serializable {

    private static final long serialVersionUID = 1L;

    private BindingSession session;
    private final BindingsObjectFactory objectFactory;
    private final RepositoryService repositoryServiceWrapper;

    /**
     * Constructor.
     * 
     * @param sessionParameters
     *            the session parameters
     */
    public CmisBindingImpl(Map<String, String> sessionParameters) {
        this(sessionParameters, null, null);
    }

    /**
     * Constructor.
     * 
     * @param sessionParameters
     *            the session parameters
     * @param authenticationProvider
     *            an authentication provider instance
     */
    public CmisBindingImpl(final Map<String, String> sessionParameters, AuthenticationProvider authenticationProvider,
            TypeDefinitionCache typeDefCache) {
        // some checks first
        if (sessionParameters == null) {
            throw new IllegalArgumentException("Session parameters must be set!");
        }
        if (!sessionParameters.containsKey(SessionParameter.BINDING_SPI_CLASS)) {
            throw new IllegalArgumentException("Session parameters do not contain a SPI class name!");
        }

        // initialize session
        session = new SessionImpl();
        for (Map.Entry<String, String> entry : sessionParameters.entrySet()) {
            session.put(entry.getKey(), entry.getValue());
        }

        if (authenticationProvider == null) {
            // create authentication provider and add it session
            String authProviderClassName = sessionParameters.get(SessionParameter.AUTHENTICATION_PROVIDER_CLASS);
            if (authProviderClassName != null) {
                Object authProviderObj = null;

                try {
                    authProviderObj = ClassLoaderUtil.loadClass(authProviderClassName).newInstance();
                } catch (Exception e) {
                    throw new IllegalArgumentException("Could not load authentication provider: " + e, e);
                }

                if (!(authProviderObj instanceof AuthenticationProvider)) {
                    throw new IllegalArgumentException(
                            "Authentication provider does not implement AuthenticationProvider!");
                }
                authenticationProvider = (AuthenticationProvider) authProviderObj;
            }
        }

        // locale
        String language = sessionParameters.get(SessionParameter.LOCALE_ISO639_LANGUAGE);
        if (language != null) {
            language = language.trim();
            if (language.length() > 0) {
                String country = sessionParameters.get(SessionParameter.LOCALE_ISO3166_COUNTRY);
                if (country != null) {
                    country = country.trim();
                    if (country.length() > 0) {
                        country = "-" + country;
                    }
                } else {
                    country = "";
                }

                String acceptLanguage = language + country;
                if ((acceptLanguage.indexOf('\n') == -1) && (acceptLanguage.indexOf('\r') == -1)) {
                    session.put(CmisBindingsHelper.ACCEPT_LANGUAGE, acceptLanguage);
                }
            }
        }

        // force CMIS version
        String forceCmisVersion = sessionParameters.get(SessionParameter.FORCE_CMIS_VERSION);
        if (forceCmisVersion != null) {
            try {
                session.put(CmisBindingsHelper.FORCE_CMIS_VERSION, CmisVersion.fromValue(forceCmisVersion));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid CMIS version value: " + forceCmisVersion, e);
            }
        }

        // add type definition cache to session
        if (typeDefCache != null) {
            session.put(CmisBindingsHelper.TYPE_DEFINTION_CACHE, typeDefCache);
            typeDefCache.initialize(session);
        }

        // set up caches
        clearAllCaches();

        // initialize the SPI
        CmisBindingsHelper.getSPI(session);

        // set up object factory
        objectFactory = new BindingsObjectFactoryImpl();

        // set up repository service
        repositoryServiceWrapper = new RepositoryServiceImpl(session);

        // add authentication provider to session
        if (authenticationProvider != null) {
            session.put(CmisBindingsHelper.AUTHENTICATION_PROVIDER_OBJECT, authenticationProvider);
            if (authenticationProvider instanceof SessionAwareAuthenticationProvider) {
                ((SessionAwareAuthenticationProvider) authenticationProvider).setSession(session);
            }
        }
    }

    @Override
    public String getSessionId() {
        return session.getSessionId();
    }

    @Override
    public BindingType getBindingType() {
        Object bindingType = session.get(SessionParameter.BINDING_TYPE);
        if (!(bindingType instanceof String)) {
            return BindingType.CUSTOM;
        }

        try {
            return BindingType.fromValue((String) bindingType);
        } catch (IllegalArgumentException e) {
            return BindingType.CUSTOM;
        }
    }

    @Override
    public RepositoryService getRepositoryService() {
        checkSession();
        return repositoryServiceWrapper;
    }

    @Override
    public NavigationService getNavigationService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getNavigationService();
    }

    @Override
    public ObjectService getObjectService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getObjectService();
    }

    @Override
    public DiscoveryService getDiscoveryService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getDiscoveryService();
    }

    @Override
    public RelationshipService getRelationshipService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getRelationshipService();
    }

    @Override
    public VersioningService getVersioningService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getVersioningService();
    }

    @Override
    public AclService getAclService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getAclService();
    }

    @Override
    public MultiFilingService getMultiFilingService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getMultiFilingService();
    }

    @Override
    public PolicyService getPolicyService() {
        checkSession();
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        return spi.getPolicyService();
    }

    @Override
    public BindingsObjectFactory getObjectFactory() {
        return objectFactory;
    }

    @Override
    public AuthenticationProvider getAuthenticationProvider() {
        return CmisBindingsHelper.getAuthenticationProvider(session);
    }

    @Override
    public void clearAllCaches() {
        checkSession();

        session.writeLock();
        try {
            session.put(CmisBindingsHelper.REPOSITORY_INFO_CACHE, new RepositoryInfoCache(session));
            TypeDefinitionCache typeDefCache = CmisBindingsHelper.getTypeDefinitionCache(session);
            typeDefCache.removeAll();

            CmisSpi spi = CmisBindingsHelper.getSPI(session);
            spi.clearAllCaches();
        } finally {
            session.writeUnlock();
        }
    }

    @Override
    public void clearRepositoryCache(String repositoryId) {
        checkSession();

        if (repositoryId == null) {
            return;
        }

        session.writeLock();
        try {
            RepositoryInfoCache repInfoCache = (RepositoryInfoCache) session
                    .get(CmisBindingsHelper.REPOSITORY_INFO_CACHE);
            repInfoCache.remove(repositoryId);

            TypeDefinitionCache typeDefCache = CmisBindingsHelper.getTypeDefinitionCache(session);
            typeDefCache.remove(repositoryId);

            CmisSpi spi = CmisBindingsHelper.getSPI(session);
            spi.clearRepositoryCache(repositoryId);
        } finally {
            session.writeUnlock();
        }
    }

    @Override
    public void close() {
        checkSession();

        session.writeLock();
        try {
            CmisSpi spi = CmisBindingsHelper.getSPI(session);
            spi.close();
        } finally {
            session.writeUnlock();
            session = null;
        }

    }

    private void checkSession() {
        if (session == null) {
            throw new IllegalStateException("Already closed.");
        }
    }
}
