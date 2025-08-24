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

import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.cache.CacheLevel;

/**
 * Abstract Map cache.
 */
public abstract class AbstractMapCacheLevel implements CacheLevel {

    private static final long serialVersionUID = 1L;

    private Map<String, Object> fMap;
    private boolean fFallbackEnabled = false;
    private String fFallbackKey;
    private boolean fSingleValueEnabled = false;

    @Override
    public abstract void initialize(Map<String, String> parameters);

    @Override
    public Object get(String key) {
        Object value = fMap.get(key);

        if (value == null && fFallbackEnabled) {
            value = fMap.get(fFallbackKey);
        }

        if (value == null && fSingleValueEnabled) {
            if (fMap.size() == 1) {
                value = fMap.values().iterator().next();
            }
        }

        return value;
    }

    @Override
    public void put(Object value, String key) {
        fMap.put(key, value);
    }

    @Override
    public void remove(String key) {
        fMap.remove(key);
    }

    /**
     * Returns the internal map.
     */
    protected Map<String, Object> getMap() {
        return fMap;
    }

    /**
     * Sets the internal map.
     */
    protected void setMap(Map<String, Object> map) {
        fMap = map;
    }

    /**
     * Enables a fallback key if no value was found for a requested key.
     */
    protected void enableKeyFallback(String key) {
        fFallbackKey = key;
        fFallbackEnabled = true;
    }

    /**
     * Disables the fallback key.
     */
    protected void disableKeyFallback() {
        fFallbackEnabled = false;
    }

    /**
     * Enables the single value fallback.
     */
    protected void enableSingeValueFallback() {
        fSingleValueEnabled = true;
    }

    /**
     * Disables the single value fallback.
     */
    protected void disableSingeValueFallback() {
        fSingleValueEnabled = false;
    }

    /**
     * Extracts an integer parameter from the parameters.
     * 
     * @param parameters
     *            the parameter map
     * @param name
     *            the parameter name
     * @param defValue
     *            the default value if the parameter can't be found
     */
    protected int getIntParameter(Map<String, String> parameters, String name, int defValue) {
        if (parameters == null) {
            return defValue;
        }

        String value = parameters.get(name);
        if (value == null || value.trim().length() == 0) {
            return defValue;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /**
     * Extracts a float parameter from the parameters.
     * 
     * @param parameters
     *            the parameter map
     * @param name
     *            the parameter name
     * @param defValue
     *            the default value if the parameter can't be found
     */
    protected float getFloatParameter(Map<String, String> parameters, String name, float defValue) {
        if (parameters == null) {
            return defValue;
        }

        String value = parameters.get(name);
        if (value == null || value.trim().length() == 0) {
            return defValue;
        }

        try {
            return Float.valueOf(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /**
     * Extracts a boolean parameter from the parameters.
     * 
     * @param parameters
     *            the parameter map
     * @param name
     *            the parameter name
     * @param defValue
     *            the default value if the parameter can't be found
     */
    protected boolean getBooleanParameter(Map<String, String> parameters, String name, boolean defValue) {
        if (parameters == null) {
            return defValue;
        }

        String value = parameters.get(name);
        if (value == null || value.trim().length() == 0) {
            return defValue;
        }

        return Boolean.parseBoolean(value);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return fMap == null ? "[no map]" : fMap.toString();
    }
}
