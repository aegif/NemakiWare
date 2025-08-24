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
package org.apache.chemistry.opencmis.server.support;

import java.io.File;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.MutableCallContext;

/**
 * Provides a convenient implementation of the {@link MutableCallContext}
 * interface that can be subclassed by developers wishing to change, add, or
 * hide call context data.
 * 
 * If the provided {@link CallContext} object implements the
 * {@link MutableCallContext} interface, all {@link #get(String)},
 * {@link #put(String, Object)}, and {@link #remove(String)} calls are forwarded
 * to this call context object. If this {@link CallContext} object does not
 * implement the {@link MutableCallContext} interface, the key-value pairs are
 * stored here and hide the values of the provided {@link CallContext} object.
 * That is, the first {@link MutableCallContext} object in the chain of
 * {@link MutableCallContextWrapper} objects manages the data.
 */
public class MutableCallContextWrapper implements MutableCallContext {

    private final CallContext context;
    private final Map<String, Object> values;

    public MutableCallContextWrapper(CallContext context) {
        this.context = context;
        if (context instanceof MutableCallContext) {
            values = null;
        } else {
            values = new HashMap<String, Object>();
        }
    }

    @Override
    public Object get(String key) {
        if (values == null) {
            return context.get(key);
        } else {
            if (values.containsKey(key)) {
                return values.get(key);
            } else {
                return context.get(key);
            }
        }
    }

    @Override
    public void put(String key, Object value) {
        if (values == null) {
            ((MutableCallContext) context).put(key, value);
        } else {
            values.put(key, value);
        }
    }

    @Override
    public Object remove(String key) {
        if (values == null) {
            return ((MutableCallContext) context).remove(key);
        } else {
            Object value = context.get(key);
            if (value != null) {
                // hide value of origin call context
                values.put(key, null);
                return value;
            } else {
                return values.remove(key);
            }
        }
    }

    @Override
    public String getBinding() {
        return context.getBinding();
    }

    @Override
    public boolean isObjectInfoRequired() {
        return context.isObjectInfoRequired();
    }

    @Override
    public CmisVersion getCmisVersion() {
        return (CmisVersion) get(CMIS_VERSION);
    }

    @Override
    public String getRepositoryId() {
        return (String) get(REPOSITORY_ID);
    }

    @Override
    public String getUsername() {
        return (String) get(USERNAME);
    }

    @Override
    public String getPassword() {
        return (String) get(PASSWORD);
    }

    @Override
    public String getLocale() {
        return (String) get(LOCALE);
    }

    @Override
    public BigInteger getOffset() {
        return (BigInteger) get(OFFSET);
    }

    @Override
    public BigInteger getLength() {
        return (BigInteger) get(LENGTH);
    }

    @Override
    public File getTempDirectory() {
        return (File) get(TEMP_DIR);
    }

    @Override
    public boolean encryptTempFiles() {
        return Boolean.TRUE.equals(get(ENCRYPT_TEMP_FILE));
    }

    @Override
    public int getMemoryThreshold() {
        return (Integer) get(MEMORY_THRESHOLD);
    }

    @Override
    public long getMaxContentSize() {
        return (Long) get(MAX_CONTENT_SIZE);
    }
}
