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
package org.apache.chemistry.opencmis.server.impl.dummy;

import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a repository factory without back-end for test purposes.
 */
public class DummyServicesFactory extends AbstractServiceFactory {

    private static final String REPOSITORY_ID = "repository.id";
    private static final String REPOSITORY_ID_DEFAULT = "test-rep";

    private static final String REPOSITORY_NAME = "repository.name";
    private static final String REPOSITORY_NAME_DEFAULT = "Test Repository";

    private static final Logger LOG = LoggerFactory.getLogger(DummyServicesFactory.class.getName());

    private DummyService service;
    private String id;
    private String name;

    @Override
    public void init(Map<String, String> parameters) {
        // get the id
        id = parameters.get(REPOSITORY_ID);
        if ((id == null) || (id.trim().length() == 0)) {
            id = REPOSITORY_ID_DEFAULT;
        }

        // get the name
        name = parameters.get(REPOSITORY_NAME);
        if ((name == null) || (name.trim().length() == 0)) {
            name = REPOSITORY_NAME_DEFAULT;
        }

        // create a repository service
        service = new DummyService(id, name);

        LOG.info("Initialized dummy repository '{}' ({})", name, id);
    }

    @Override
    public void destroy() {
        LOG.info("Destroyed dummy repository '{}' ({})", name, id);
    }

    @Override
    public CmisService getService(CallContext context) {
        return service;
    }
}
