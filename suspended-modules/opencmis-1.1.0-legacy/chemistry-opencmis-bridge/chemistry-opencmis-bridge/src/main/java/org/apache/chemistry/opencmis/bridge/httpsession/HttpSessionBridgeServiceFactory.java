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
package org.apache.chemistry.opencmis.bridge.httpsession;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.bridge.AbstractBridgeServiceFactory;
import org.apache.chemistry.opencmis.bridge.FilterCmisService;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class HttpSessionBridgeServiceFactory extends AbstractBridgeServiceFactory {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    protected FilterCmisService createService(CallContext context) {
        HttpSessionCmisService service = null;
        try {
            service = (HttpSessionCmisService) getServiceClass().newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Could not create service instance: " + e, e);
        }

        service.init(getParameters(), lock);

        return service;
    }

}
