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
package org.apache.chemistry.opencmis.client.bindings.cache.impl;

import java.util.HashMap;
import java.util.Map;

/**
 * Map cache.
 */
public class MapCacheLevelImpl extends AbstractMapCacheLevel {

    private static final long serialVersionUID = 1L;

    public static final String CAPACITY = "capacity";
    public static final String LOAD_FACTOR = "loadFactor";
    public static final String SINGLE_VALUE = "singleValue";

    /**
     * Constructor.
     */
    public MapCacheLevelImpl() {
    }

    @Override
    public void initialize(Map<String, String> parameters) {
        int initialCapacity = getIntParameter(parameters, CAPACITY, 32);
        float loadFactor = getFloatParameter(parameters, LOAD_FACTOR, 0.75f);
        boolean singleValue = getBooleanParameter(parameters, SINGLE_VALUE, false);

        setMap(new HashMap<String, Object>(initialCapacity, loadFactor));
        disableKeyFallback();
        if (singleValue) {
            enableSingeValueFallback();
        }
    }
}
