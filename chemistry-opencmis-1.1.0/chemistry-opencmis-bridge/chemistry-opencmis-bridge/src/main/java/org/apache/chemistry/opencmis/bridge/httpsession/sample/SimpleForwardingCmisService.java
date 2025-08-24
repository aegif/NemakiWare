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
package org.apache.chemistry.opencmis.bridge.httpsession.sample;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.bridge.FilterCmisService;
import org.apache.chemistry.opencmis.bridge.client.SimpleCmisBindingFactory;
import org.apache.chemistry.opencmis.bridge.httpsession.HttpSessionCmisService;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoImpl;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * Very simple example of a concrete {@link FilterCmisService} implementation.
 */
public class SimpleForwardingCmisService extends HttpSessionCmisService {

    private static final long serialVersionUID = 1L;

    private static final String BINDING_PARAMETERS_PREFIX = "forwarding.binding.";

    private Map<String, String> bindingParameters;

    @Override
    public void init(Map<String, String> parameters) {
        super.init(parameters);

        // gather binding parameters
        bindingParameters = new HashMap<String, String>();

        for (Map.Entry<String, String> p : parameters.entrySet()) {
            if (p.getKey().startsWith(BINDING_PARAMETERS_PREFIX)) {
                bindingParameters.put(p.getKey().substring(BINDING_PARAMETERS_PREFIX.length()), p.getValue());
            }
        }
    }

    @Override
    public CmisBinding createCmisBinding() {
        return SimpleCmisBindingFactory.createCmisBinding(getCallContext(), bindingParameters);
    }

    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {

        RepositoryInfo repInfo = getCmisBinding().getRepositoryService().getRepositoryInfo(repositoryId, extension);

        RepositoryInfoImpl newRepInfo = new RepositoryInfoImpl(repInfo);
        newRepInfo.setDescription(repInfo.getDescription() + " (forwarded by the OpenCMIS Bridge)");

        return newRepInfo;
    }

}
