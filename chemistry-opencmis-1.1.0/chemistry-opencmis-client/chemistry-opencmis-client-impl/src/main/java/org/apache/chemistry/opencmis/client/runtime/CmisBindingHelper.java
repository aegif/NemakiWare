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

import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.CmisBindingFactory;
import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Helper methods for binding handling.
 */
public final class CmisBindingHelper {

    private CmisBindingHelper() {
    }

    /**
     * Creates a {@link CmisBinding} object.
     */
    public static CmisBinding createBinding(Map<String, String> parameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        if (parameters == null || parameters.isEmpty()) {
            throw new CmisRuntimeException("Session parameter not set!");
        }

        if (!parameters.containsKey(SessionParameter.BINDING_TYPE)) {
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.CUSTOM.value());
        }

        BindingType bt = null;
        try {
            bt = BindingType.fromValue(parameters.get(SessionParameter.BINDING_TYPE));
        } catch (IllegalArgumentException iae) {
            throw new CmisRuntimeException("Invalid binding type!");
        }

        switch (bt) {
        case ATOMPUB:
            return createAtomPubBinding(parameters, authenticationProvider, typeDefCache);
        case WEBSERVICES:
            return createWebServiceBinding(parameters, authenticationProvider, typeDefCache);
        case BROWSER:
            return createCmisBrowserBinding(parameters, authenticationProvider, typeDefCache);
        case LOCAL:
            return createLocalBinding(parameters, typeDefCache);
        case CUSTOM:
            return createCustomBinding(parameters, authenticationProvider, typeDefCache);
        default:
            throw new CmisRuntimeException("Invalid binding type!");
        }
    }

    /**
     * Creates a binding with custom parameters.
     */
    private static CmisBinding createCustomBinding(Map<String, String> parameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        CmisBindingFactory factory = CmisBindingFactory.newInstance();
        CmisBinding binding = factory.createCmisBinding(parameters, authenticationProvider, typeDefCache);

        return binding;
    }

    /**
     * Creates a Web Services binding.
     */
    private static CmisBinding createWebServiceBinding(Map<String, String> parameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        CmisBindingFactory factory = CmisBindingFactory.newInstance();
        CmisBinding binding = factory.createCmisWebServicesBinding(parameters, authenticationProvider, typeDefCache);

        return binding;
    }

    /**
     * Creates an AtomPub binding.
     */
    private static CmisBinding createAtomPubBinding(Map<String, String> parameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        CmisBindingFactory factory = CmisBindingFactory.newInstance();
        CmisBinding binding = factory.createCmisAtomPubBinding(parameters, authenticationProvider, typeDefCache);

        return binding;
    }

    /**
     * Creates an Browser binding.
     */
    private static CmisBinding createCmisBrowserBinding(Map<String, String> parameters,
            AuthenticationProvider authenticationProvider, TypeDefinitionCache typeDefCache) {
        CmisBindingFactory factory = CmisBindingFactory.newInstance();
        CmisBinding binding = factory.createCmisBrowserBinding(parameters, authenticationProvider, typeDefCache);

        return binding;
    }

    /**
     * Creates a local binding.
     */
    private static CmisBinding createLocalBinding(Map<String, String> parameters, TypeDefinitionCache typeDefCache) {
        CmisBindingFactory factory = CmisBindingFactory.newInstance();
        CmisBinding binding = factory.createCmisLocalBinding(parameters, typeDefCache);

        return binding;
    }
}
