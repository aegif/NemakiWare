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

import java.io.File;
import java.math.BigInteger;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.CallContextAwareCmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;

public abstract class AbstractBridgeServiceFactory extends AbstractServiceFactory {

    public static final String BRIDGE_TEMP_DIRECTORY = "bridge.tempDirectory";
    public static final String BRIDGE_MEMORY_THERESHOLD = "bridge.memoryThreshold";
    public static final String BRIDGE_MAX_CONTENT_SIZE = "bridge.maxContentSize";

    public static final String SERVICE_CLASS = "service.class";
    public static final String SERVICE_DEFAULT_MAX_ITEMS_OBJECTS = "service.defaultMaxItems";
    public static final String SERVICE_DEFAULT_DEPTH_OBJECTS = "service.defaultDepth";
    public static final String SERVICE_DEFAULT_MAX_ITEMS_TYPES = "service.defaultTypesMaxItems";
    public static final String SERVICE_DEFAULT_DEPTH_TYPES = "service.defaultTypesDepth";

    private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger.valueOf(100000);
    private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger.valueOf(100);
    private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger.valueOf(1000);
    private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger.valueOf(10);

    private ThreadLocal<CallContextAwareCmisService> threadLocalService = new ThreadLocal<CallContextAwareCmisService>();

    private Map<String, String> parameters;

    private Class<?> serviceClass;

    private BigInteger defaultMaxItems;
    private BigInteger defaultDepth;
    private BigInteger defaultTypesMaxItems;
    private BigInteger defaultTypesDepth;

    private File tempDirectory;
    private int memoryThreshold;
    private long maxContentSize;

    @Override
    public void init(Map<String, String> parameters) {
        this.parameters = parameters;

        // get bridge configuration
        String tempDirectoryStr = parameters.get(BRIDGE_TEMP_DIRECTORY);
        tempDirectory = tempDirectoryStr == null || tempDirectoryStr.trim().length() == 0 ? super.getTempDirectory()
                : new File(tempDirectoryStr.trim());

        try {
            String memoryThresholdStr = parameters.get(BRIDGE_MEMORY_THERESHOLD);
            memoryThreshold = memoryThresholdStr == null || memoryThresholdStr.trim().length() == 0 ? super
                    .getMemoryThreshold() : Integer.parseInt(memoryThresholdStr.trim());

            String maxContentSizeStr = parameters.get(BRIDGE_MAX_CONTENT_SIZE);
            maxContentSize = maxContentSizeStr == null || maxContentSizeStr.trim().length() == 0 ? super
                    .getMaxContentSize() : Long.parseLong(maxContentSizeStr.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("Could not parse bride configuration values: " + e.getMessage(), e);
        }

        // find service class
        String className = parameters.get(SERVICE_CLASS);
        if (className == null || className.trim().length() == 0) {
            throw new RuntimeException("Service class name is not set!");
        }

        try {
            serviceClass = ClassLoaderUtil.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Service class name cannot be found: " + e.getMessage(), e);
        }

        if (serviceClass.isAssignableFrom(FilterCmisService.class)) {
            throw new RuntimeException("Service class is not a sub class of FilterCmisService!");
        }

        // get service defaults
        try {
            defaultMaxItems = getBigIntegerParameter(SERVICE_DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_MAX_ITEMS_OBJECTS);
            defaultDepth = getBigIntegerParameter(SERVICE_DEFAULT_DEPTH_OBJECTS, DEFAULT_DEPTH_OBJECTS);
            defaultTypesMaxItems = getBigIntegerParameter(SERVICE_DEFAULT_MAX_ITEMS_TYPES, DEFAULT_MAX_ITEMS_TYPES);
            defaultTypesDepth = getBigIntegerParameter(SERVICE_DEFAULT_DEPTH_TYPES, DEFAULT_DEPTH_TYPES);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Could not parse service default values: " + e.getMessage(), e);
        }
    }

    @Override
    public CmisService getService(CallContext context) {
        CallContextAwareCmisService service = threadLocalService.get();
        if (service == null) {
            service = new ConformanceCmisServiceWrapper(createService(context), defaultTypesMaxItems,
                    defaultTypesDepth, defaultMaxItems, defaultDepth);
            threadLocalService.set(service);
        }

        service.setCallContext(context);

        return service;
    }

    /**
     * Creates a new service instance.
     */
    protected abstract FilterCmisService createService(CallContext context);

    protected Class<?> getServiceClass() {
        return serviceClass;
    }

    protected Map<String, String> getParameters() {
        return parameters;
    }

    @Override
    public File getTempDirectory() {
        return tempDirectory;
    }

    @Override
    public int getMemoryThreshold() {
        return memoryThreshold;
    }

    @Override
    public long getMaxContentSize() {
        return maxContentSize;
    }

    /**
     * Gets a BigInteger parameter from the parameters.
     */
    protected BigInteger getBigIntegerParameter(String key, BigInteger def) {
        String value = parameters.get(key);
        if (value == null || value.trim().length() == 0) {
            return def;
        }

        return new BigInteger(value);
    }
}
