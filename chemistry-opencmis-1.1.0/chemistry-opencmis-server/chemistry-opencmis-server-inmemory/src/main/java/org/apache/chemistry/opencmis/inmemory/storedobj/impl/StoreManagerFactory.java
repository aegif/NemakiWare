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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create objects that are stored in a persistent store.
 */
public final class StoreManagerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(StoreManagerFactory.class);

    private StoreManagerFactory() {
    }

    public static StoreManager createInstance(String className) {

        Class<?> clazz = null;
        try {
            clazz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            String msg = "Failed to create StoredObjectCreator, class " + className + " does not exist.";
            LOG.error(msg, e);
            throw new CmisRuntimeException(msg, e);
        }

        Object obj = null;
        try {
            obj = clazz.newInstance();
        } catch (InstantiationException e) {
            LOG.error("Failed to create StoredObjectCreator from class " + className, e);
        } catch (IllegalAccessException e) {
            LOG.error("Failed to create StoredObjectCreator from class " + className, e);
        }

        if (obj instanceof StoreManager) {
            return (StoreManager) obj;
        } else {
            LOG.error("Failed to create StoredObjectCreator, class " + className
                    + " does not implement interface StoredObjectCreator");
            return null;
        }
    }

}
