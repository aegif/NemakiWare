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
package org.apache.chemistry.opencmis.bridge.lrucache;

import java.util.Map;

import org.apache.chemistry.opencmis.bridge.AbstractBridgeServiceFactory;
import org.apache.chemistry.opencmis.bridge.FilterCmisService;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class LruCacheBridgeServiceFactory extends AbstractBridgeServiceFactory {

    public static final String CACHE_SIZE = "cache.size";
    public static final int DEFAULT_CACHE_SIZE = 1000;

    private CmisBindingCache cache;

    @Override
    public void init(Map<String, String> parameters) {
        super.init(parameters);

        // initialize LRU cache
        int size = 0;
        try {
            String sizeStr = parameters.get(CACHE_SIZE);
            size = sizeStr == null || sizeStr.trim().length() == 0 ? DEFAULT_CACHE_SIZE : Integer.parseInt(sizeStr
                    .trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("Could not parse cache configuration values: " + e.getMessage(), e);
        }

        cache = new CmisBindingCache(size);
    }

    @Override
    protected FilterCmisService createService(CallContext context) {
        LruCacheCmisService service = null;
        try {
            service = (LruCacheCmisService) getServiceClass().newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Could not create service instance: " + e, e);
        }

        service.init(getParameters(), cache);

        return service;
    }
}