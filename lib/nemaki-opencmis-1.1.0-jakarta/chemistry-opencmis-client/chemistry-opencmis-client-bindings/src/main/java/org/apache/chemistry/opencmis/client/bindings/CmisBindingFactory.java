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
package org.apache.chemistry.opencmis.client.bindings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.SessionParameterDefaults;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Default factory for a CMIS binding instance.
 */
public class CmisBindingFactory {

    /** Default CMIS AtomPub binding SPI implementation. */
    public static final String BINDING_SPI_ATOMPUB = "org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubSpi";
    /** Default CMIS Web Services binding SPI implementation. */
    public static final String BINDING_SPI_WEBSERVICES = "org.apache.chemistry.opencmis.client.bindings.spi.webservices.CmisWebServicesSpi";
    /** Default CMIS Browser binding SPI implementation. */
    public static final String BINDING_SPI_BROWSER = "org.apache.chemistry.opencmis.client.bindings.spi.browser.CmisBrowserBindingSpi";
    /** Default CMIS local binding SPI implementation. */
    public static final String BINDING_SPI_LOCAL = "org.apache.chemistry.opencmis.client.bindings.spi.local.CmisLocalSpi";

    /** Default type definition cache class */
    public static final String DEFAULT_TYPE_DEFINITION_CACHE_CLASS = "org.apache.chemistry.opencmis.client.bindings.impl.TypeDefinitionCacheImpl";
    /** Default HTTP invoker class */
    public static final String DEFAULT_HTTP_INVOKER = "org.apache.chemistry.opencmis.client.bindings.spi.http.DefaultHttpInvoker";
    /** Standard authentication provider class. */
    public static final String STANDARD_AUTHENTICATION_PROVIDER = "org.apache.chemistry.opencmis.client.bindings.spi.StandardAuthenticationProvider";
    /** NTLM authentication provider class. */
    public static final String NTLM_AUTHENTICATION_PROVIDER = "org.apache.chemistry.opencmis.client.bindings.spi.NTLMAuthenticationProvider";

    private Map<String, String> defaults;

    /**
     * Constructor.
     */
    public CmisBindingFactory() {
        defaults = createNewDefaultParameters();
    }

    /**
     * Creates a new factory instance.
     * 
     * @return a new factory instance
     */
    public static CmisBindingFactory newInstance() {
        return new CmisBindingFactory();
    }

    /**
     * Returns the default session parameters.
     * 
     * @return the default session parameters
     */
    public Map<String, String> getDefaultSessionParameters() {
        return defaults;
    }

    /**
     * Sets the default session parameters.
     * 
     * @param sessionParameters
     *            the session parameters
     */
    public void setDefaultSessionParameters(Map<String, String> sessionParameters) {
        if (sessionParameters == null) {
            defaults = createNewDefaultParameters();
        } else {
            defaults = sessionParameters;
        }
    }

    /**
     * Creates a CMIS binding instance. A binding class has to be provided in
     * the session parameters.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisBinding(Map<String, String> sessionParameters) {
        return createCmisBinding(sessionParameters, null, null);
    }

    /**
     * Creates a CMIS binding instance. A binding class has to be provided in
     * the session parameters.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisBinding(Map<String, String> sessionParameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        checkSessionParameters(sessionParameters, true);

        addDefaultParameters(sessionParameters);

        return new CmisBindingImpl(sessionParameters, authenticationProvider, typeDefCache);
    }

    /**
     * Creates a default CMIS AtomPub binding instance.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisAtomPubBinding(Map<String, String> sessionParameters) {
        return createCmisAtomPubBinding(sessionParameters, null, null);
    }

    /**
     * Creates a default CMIS AtomPub binding instance with a custom
     * authentication provider.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisAtomPubBinding(Map<String, String> sessionParameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        checkSessionParameters(sessionParameters, false);

        sessionParameters.put(SessionParameter.BINDING_SPI_CLASS, BINDING_SPI_ATOMPUB);
        if (!sessionParameters.containsKey(SessionParameter.HTTP_INVOKER_CLASS)) {
            sessionParameters.put(SessionParameter.HTTP_INVOKER_CLASS, DEFAULT_HTTP_INVOKER);
        }
        if (authenticationProvider == null) {
            if (!sessionParameters.containsKey(SessionParameter.AUTHENTICATION_PROVIDER_CLASS)) {
                sessionParameters.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, STANDARD_AUTHENTICATION_PROVIDER);
            }
        }
        if (typeDefCache == null) {
            if (!sessionParameters.containsKey(SessionParameter.TYPE_DEFINITION_CACHE_CLASS)) {
                sessionParameters
                        .put(SessionParameter.TYPE_DEFINITION_CACHE_CLASS, DEFAULT_TYPE_DEFINITION_CACHE_CLASS);
            }
        }
        if (!sessionParameters.containsKey(SessionParameter.AUTH_HTTP_BASIC)) {
            sessionParameters.put(SessionParameter.AUTH_HTTP_BASIC, "true");
        }
        sessionParameters.put(SessionParameter.AUTH_SOAP_USERNAMETOKEN, "false");
        addDefaultParameters(sessionParameters);

        check(sessionParameters, SessionParameter.ATOMPUB_URL);

        return new CmisBindingImpl(sessionParameters, authenticationProvider, typeDefCache);
    }

    /**
     * Creates a default CMIS Web Services binding instance.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisWebServicesBinding(Map<String, String> sessionParameters) {
        return createCmisWebServicesBinding(sessionParameters, null, null);
    }

    /**
     * Creates a default CMIS Web Services binding instance with a custom
     * authentication provider.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisWebServicesBinding(Map<String, String> sessionParameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        checkSessionParameters(sessionParameters, false);

        sessionParameters.put(SessionParameter.BINDING_SPI_CLASS, BINDING_SPI_WEBSERVICES);
        if (!sessionParameters.containsKey(SessionParameter.HTTP_INVOKER_CLASS)) {
            sessionParameters.put(SessionParameter.HTTP_INVOKER_CLASS, DEFAULT_HTTP_INVOKER);
        }
        if (authenticationProvider == null) {
            if (!sessionParameters.containsKey(SessionParameter.AUTHENTICATION_PROVIDER_CLASS)) {
                sessionParameters.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, STANDARD_AUTHENTICATION_PROVIDER);
            }
        }
        if (typeDefCache == null) {
            if (!sessionParameters.containsKey(SessionParameter.TYPE_DEFINITION_CACHE_CLASS)) {
                sessionParameters
                        .put(SessionParameter.TYPE_DEFINITION_CACHE_CLASS, DEFAULT_TYPE_DEFINITION_CACHE_CLASS);
            }
        }
        if (!sessionParameters.containsKey(SessionParameter.AUTH_SOAP_USERNAMETOKEN)) {
            sessionParameters.put(SessionParameter.AUTH_SOAP_USERNAMETOKEN, "true");
        }
        if (!sessionParameters.containsKey(SessionParameter.AUTH_HTTP_BASIC)) {
            sessionParameters.put(SessionParameter.AUTH_HTTP_BASIC, "true");
        }
        addDefaultParameters(sessionParameters);

        check(sessionParameters, SessionParameter.WEBSERVICES_ACL_SERVICE,
                SessionParameter.WEBSERVICES_ACL_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_DISCOVERY_SERVICE,
                SessionParameter.WEBSERVICES_DISCOVERY_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_MULTIFILING_SERVICE,
                SessionParameter.WEBSERVICES_MULTIFILING_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_NAVIGATION_SERVICE,
                SessionParameter.WEBSERVICES_NAVIGATION_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_OBJECT_SERVICE,
                SessionParameter.WEBSERVICES_OBJECT_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_POLICY_SERVICE,
                SessionParameter.WEBSERVICES_POLICY_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE,
                SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_REPOSITORY_SERVICE,
                SessionParameter.WEBSERVICES_REPOSITORY_SERVICE_ENDPOINT);
        check(sessionParameters, SessionParameter.WEBSERVICES_VERSIONING_SERVICE,
                SessionParameter.WEBSERVICES_VERSIONING_SERVICE_ENDPOINT);

        return new CmisBindingImpl(sessionParameters, authenticationProvider, typeDefCache);
    }

    /**
     * Creates a default CMIS Browser binding instance.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisBrowserBinding(Map<String, String> sessionParameters) {
        return createCmisBrowserBinding(sessionParameters, null, null);
    }

    /**
     * Creates a default CMIS Browser binding instance with a custom
     * authentication provider.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisBrowserBinding(Map<String, String> sessionParameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        checkSessionParameters(sessionParameters, false);

        sessionParameters.put(SessionParameter.BINDING_SPI_CLASS, BINDING_SPI_BROWSER);
        if (!sessionParameters.containsKey(SessionParameter.HTTP_INVOKER_CLASS)) {
            sessionParameters.put(SessionParameter.HTTP_INVOKER_CLASS, DEFAULT_HTTP_INVOKER);
        }
        if (authenticationProvider == null) {
            if (!sessionParameters.containsKey(SessionParameter.AUTHENTICATION_PROVIDER_CLASS)) {
                sessionParameters.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, STANDARD_AUTHENTICATION_PROVIDER);
            }
        }
        if (typeDefCache == null) {
            if (!sessionParameters.containsKey(SessionParameter.TYPE_DEFINITION_CACHE_CLASS)) {
                sessionParameters
                        .put(SessionParameter.TYPE_DEFINITION_CACHE_CLASS, DEFAULT_TYPE_DEFINITION_CACHE_CLASS);
            }
        }
        if (!sessionParameters.containsKey(SessionParameter.BROWSER_SUCCINCT)) {
            sessionParameters.put(SessionParameter.BROWSER_SUCCINCT, "true");
        }
        if (!sessionParameters.containsKey(SessionParameter.AUTH_HTTP_BASIC)) {
            sessionParameters.put(SessionParameter.AUTH_HTTP_BASIC, "true");
        }
        sessionParameters.put(SessionParameter.AUTH_SOAP_USERNAMETOKEN, "false");
        addDefaultParameters(sessionParameters);

        check(sessionParameters, SessionParameter.BROWSER_URL);

        return new CmisBindingImpl(sessionParameters, authenticationProvider, typeDefCache);
    }

    /**
     * Creates a default CMIS local binding instance.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisLocalBinding(Map<String, String> sessionParameters) {
        return createCmisLocalBinding(sessionParameters, null);
    }

    /**
     * Creates a default CMIS local binding instance.
     * 
     * @param sessionParameters
     *            the session parameters
     * @return the binding object
     */
    public CmisBinding createCmisLocalBinding(Map<String, String> sessionParameters, TypeDefinitionCache typeDefCache) {
        checkSessionParameters(sessionParameters, false);

        sessionParameters.put(SessionParameter.BINDING_SPI_CLASS, BINDING_SPI_LOCAL);
        if (typeDefCache == null) {
            if (!sessionParameters.containsKey(SessionParameter.TYPE_DEFINITION_CACHE_CLASS)) {
                sessionParameters
                        .put(SessionParameter.TYPE_DEFINITION_CACHE_CLASS, DEFAULT_TYPE_DEFINITION_CACHE_CLASS);
            }
        }
        addDefaultParameters(sessionParameters);

        check(sessionParameters, SessionParameter.LOCAL_FACTORY);

        return new CmisBindingImpl(sessionParameters);
    }

    // ---- internal ----

    /**
     * Checks the passed session parameters.
     */
    private static void checkSessionParameters(Map<String, String> sessionParameters, boolean mustContainSPI) {
        // don't accept null
        if (sessionParameters == null) {
            throw new IllegalArgumentException("Session parameter map not set!");
        }

        // check binding entry
        final String spiClass = sessionParameters.get(SessionParameter.BINDING_SPI_CLASS);
        if (mustContainSPI) {
            if ((spiClass == null) || (spiClass.trim().length() == 0)) {
                throw new IllegalArgumentException("SPI class entry (" + SessionParameter.BINDING_SPI_CLASS
                        + ") is missing!");
            }
        }
    }

    /**
     * Checks if the given parameter is present. If not, throw an
     * <code>IllegalArgumentException</code>.
     */
    private static void check(Map<String, String> sessionParameters, String... parameters) {
        for (String parameter : parameters) {
            if (sessionParameters.containsKey(parameter)) {
                return;
            }
        }

        if (parameters.length == 1) {
            throw new IllegalArgumentException("Parameter '" + parameters[0] + "' is missing!");
        } else {
            throw new IllegalArgumentException("One of the following parameters must be set: "
                    + Arrays.asList(parameters).toString());
        }
    }

    /**
     * Add the default session parameters to the given map without override
     * existing entries.
     */
    private void addDefaultParameters(Map<String, String> sessionParameters) {
        for (String key : defaults.keySet()) {
            if (!sessionParameters.containsKey(key)) {
                sessionParameters.put(key, defaults.get(key));
            }
        }
    }

    /**
     * Creates a default session parameters map with some reasonable defaults.
     */
    private static Map<String, String> createNewDefaultParameters() {
        Map<String, String> result = new HashMap<String, String>();

        result.put(SessionParameter.CACHE_SIZE_REPOSITORIES,
                String.valueOf(SessionParameterDefaults.CACHE_SIZE_REPOSITORIES));
        result.put(SessionParameter.CACHE_SIZE_TYPES, String.valueOf(SessionParameterDefaults.CACHE_SIZE_TYPES));
        result.put(SessionParameter.CACHE_SIZE_LINKS, String.valueOf(SessionParameterDefaults.CACHE_SIZE_LINKS));

        return result;
    }
}
