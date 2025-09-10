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
package org.apache.chemistry.opencmis.server.support.wrapper;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a list of CMIS service wrappers.
 */
public class CmisServiceWrapperManager {

    private static final Logger LOG = LoggerFactory.getLogger(CmisServiceWrapperManager.class);

    private static final String PARAMS_SERVICE_WRAPPER_PREFIX = "servicewrapper.";

    private WrapperDefinition outerMost;
    private WrapperDefinition innerMost;

    /**
     * Constructor.
     */
    public CmisServiceWrapperManager() {
        outerMost = null;
        innerMost = null;
    }

    /**
     * Adds an outer-most (called first) wrapper.
     * 
     * @param wrapperClass
     *            the wrapper class
     * @param params
     *            wrapper parameters
     */
    public void addOuterWrapper(Class<? extends AbstractCmisServiceWrapper> wrapperClass, Object... params) {
        WrapperDefinition wd = new WrapperDefinition(wrapperClass, params);
        if (outerMost == null) {
            outerMost = wd;
            innerMost = wd;
        } else {
            outerMost.setOuterWrapper(wd);
            outerMost = wd;
        }

        LOG.debug("Added outer service wrapper: {}", wrapperClass.getName());
    }

    /**
     * Adds an inner-most (called last) wrapper.
     * 
     * @param wrapperClass
     *            the wrapper class
     * @param params
     *            wrapper parameters
     */
    public void addInnerWrapper(Class<? extends AbstractCmisServiceWrapper> wrapperClass, Object... params) {
        WrapperDefinition wd = new WrapperDefinition(wrapperClass, params);
        if (innerMost == null) {
            outerMost = wd;
            innerMost = wd;
        } else {
            innerMost.setInnerWrapper(wd);
            innerMost = wd;
        }

        LOG.debug("Added inner service wrapper: {}", wrapperClass.getName());
    }

    /**
     * Gets wrapper settings from the service factory parameters and adds them
     * to the wrappers.
     * <p>
     * The factory parameters properties file should look like this:
     * 
     * <pre>
     * servicewrapper.1=com.example.my.SimpleWrapper
     * servicewrapper.2=com.example.my.AdvancedWrapper,1,cmis:documents
     * servicewrapper.3=com.example.my.DebuggingWrapper,testRepositoryId
     * </pre>
     * 
     * Syntax:
     * {@code servicewrapper.&lt;position>=&lt;classname>[,parameter1[,parameter2[...]]]}
     * 
     * @param parameters
     *            service factory parameters
     */
    @SuppressWarnings("unchecked")
    public void addWrappersFromServiceFactoryParameters(Map<String, String> parameters) {
        if (parameters == null) {
            return;
        }

        TreeMap<Integer, WrapperDefinition> wrappers = new TreeMap<Integer, WrapperDefinition>();

        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey().trim().toLowerCase(Locale.ENGLISH);
            if (key.startsWith(PARAMS_SERVICE_WRAPPER_PREFIX) && entry.getKey() != null) {
                int index = 0;
                try {
                    index = Integer.valueOf(key.substring(PARAMS_SERVICE_WRAPPER_PREFIX.length()));
                } catch (NumberFormatException e) {
                    throw new CmisRuntimeException("Invalid service wrapper configuration: " + key, e);
                }

                String[] value = entry.getValue().trim().split(",");
                if (value.length > 0) {
                    Class<?> wrapperClass = null;
                    try {
                        wrapperClass = ClassLoaderUtil.loadClass(value[0]);
                    } catch (ClassNotFoundException e) {
                        throw new CmisRuntimeException("Service wrapper class not found: " + value[0], e);
                    }

                    if (!AbstractCmisServiceWrapper.class.isAssignableFrom(wrapperClass)) {
                        throw new CmisRuntimeException("Class is not a service wrapper: " + value[0]);
                    }

                    Object[] params = null;
                    if (value.length > 1) {
                        params = new Object[value.length - 1];
                        System.arraycopy(value, 1, params, 0, params.length);
                    }

                    if (wrappers.containsKey(index)) {
                        throw new CmisRuntimeException("More than one service wrapper at the same position: " + index);
                    }

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Found wrapper at index {}: {}{}", index, wrapperClass.getName(), params == null ? ""
                                : Arrays.asList(params).toString());
                    }

                    wrappers.put(index, new WrapperDefinition(
                            (Class<? extends AbstractCmisServiceWrapper>) wrapperClass, params));
                }
            }
        }

        if (!wrappers.isEmpty()) {
            WrapperDefinition first = null;
            WrapperDefinition prev = null;
            for (WrapperDefinition def : wrappers.values()) {
                def.setOuterWrapper(prev);
                prev = def;
                if (first == null) {
                    first = def;
                }

                LOG.debug("Added service wrapper: {}", def.getWrapperClass().getName());
            }

            if (outerMost == null) {
                outerMost = first;
                innerMost = prev;
            } else {
                outerMost.setOuterWrapper(prev);
                outerMost = first;
            }
        }
    }

    /**
     * Removes the outer-most wrapper.
     */
    public void removeOuterWrapper() {
        if (outerMost != null) {
            outerMost = outerMost.getInnerWrapper();
            if (outerMost == null) {
                innerMost = null;
            } else {
                outerMost.setOuterWrapper(null);
            }
        }
    }

    /**
     * Removes the inner-most wrapper.
     */
    public void removeInnerWrapper() {
        if (innerMost != null) {
            innerMost = innerMost.getOuterWrapper();
            if (innerMost == null) {
                outerMost = null;
            } else {
                innerMost.setInnerWrapper(null);
            }
        }
    }

    /**
     * Wraps a service with all configured wrappers.
     * 
     * @param service
     *            the CMIS service object
     * @return the wrapped service
     */
    public CmisService wrap(CmisService service) {
        CmisService result = service;

        WrapperDefinition def = innerMost;
        while (def != null) {
            result = def.createWrapperObject(result);
            def = def.getOuterWrapper();
        }

        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);

        WrapperDefinition def = outerMost;
        while (def != null) {

            sb.append('[');
            sb.append(def.toString());
            sb.append(']');

            def = def.getInnerWrapper();
        }

        return sb.toString();
    }

    /**
     * Wrapper Definition.
     */
    private static class WrapperDefinition {

        private static final Class<?>[] CONSTRUCTOR_PARAMETERS = new Class<?>[] { CmisService.class };

        private final Class<? extends AbstractCmisServiceWrapper> wrapperClass;
        private final Constructor<? extends AbstractCmisServiceWrapper> wrapperConstructor;
        private final Object[] params;

        private WrapperDefinition outer;
        private WrapperDefinition inner;

        public WrapperDefinition(Class<? extends AbstractCmisServiceWrapper> wrapperClass, Object... params) {
            this.wrapperClass = wrapperClass;
            this.params = params;

            if (wrapperClass == null) {
                throw new CmisRuntimeException("Wrapper class must be set!");
            }

            try {
                wrapperConstructor = wrapperClass.getConstructor(CONSTRUCTOR_PARAMETERS);
            } catch (Exception e) {
                throw new CmisRuntimeException("Could not access constructor of service wrapper "
                        + wrapperClass.getName() + ": " + e.toString(), e);
            }
        }

        public Class<? extends AbstractCmisServiceWrapper> getWrapperClass() {
            return wrapperClass;
        }

        public AbstractCmisServiceWrapper createWrapperObject(CmisService service) {
            try {
                AbstractCmisServiceWrapper wrapper = wrapperConstructor.newInstance(service);
                wrapper.initialize(params);

                return wrapper;
            } catch (Exception e) {
                throw new CmisRuntimeException("Could not instantiate service wrapper " + wrapperClass.getName() + ": "
                        + e.toString(), e);
            }
        }

        public void setOuterWrapper(WrapperDefinition wrapper) {
            outer = wrapper;
            if (wrapper != null) {
                wrapper.inner = this;
            }
        }

        public WrapperDefinition getOuterWrapper() {
            return outer;
        }

        public void setInnerWrapper(WrapperDefinition wrapper) {
            inner = wrapper;
            if (wrapper != null) {
                wrapper.outer = this;
            }
        }

        public WrapperDefinition getInnerWrapper() {
            return inner;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);

            sb.append(wrapperClass.getName());

            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    sb.append(',');
                    sb.append(params[i]);
                }
            }

            return sb.toString();
        }
    }
}
