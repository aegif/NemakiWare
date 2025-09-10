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
package org.apache.chemistry.opencmis.bridge.client;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.CmisBindingFactory;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Creates a {@link CmisBinding} object for a set of parameters and a
 * {@link CallContext}.
 */
public class SimpleCmisBindingFactory {

    private static final CmisBindingFactory BINDING_FACTORY = CmisBindingFactory.newInstance();

    public static CmisBinding createCmisBinding(CallContext context, Map<String, String> bindingParameters) {
        Map<String, String> parameters = new HashMap<String, String>(bindingParameters);

        // forward user name and password
        parameters.put(SessionParameter.USER, context.getUsername());
        parameters.put(SessionParameter.PASSWORD, context.getPassword());

        // create the binding object
        String bindingTypeStr = parameters.get(SessionParameter.BINDING_TYPE);
        BindingType bindingType = BindingType.fromValue(bindingTypeStr);

        CmisBinding binding = null;
        switch (bindingType) {
        case WEBSERVICES:
            binding = BINDING_FACTORY.createCmisWebServicesBinding(parameters);
            break;
        case BROWSER:
            binding = BINDING_FACTORY.createCmisBrowserBinding(parameters);
            break;
        default:
            binding = BINDING_FACTORY.createCmisAtomPubBinding(parameters);
            break;
        }

        return binding;
    }
}
