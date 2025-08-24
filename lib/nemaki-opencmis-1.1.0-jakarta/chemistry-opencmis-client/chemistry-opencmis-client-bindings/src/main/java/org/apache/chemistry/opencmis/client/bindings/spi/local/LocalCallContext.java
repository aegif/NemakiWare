/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.chemistry.opencmis.client.bindings.spi.local;

import java.io.File;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.MutableCallContext;

/**
 * Simple {@link CallContext} implementation.
 */
public class LocalCallContext implements MutableCallContext {

    private final Map<String, Object> contextMap = new HashMap<String, Object>();

    public LocalCallContext(String repositoryId, String user, String password) {
        contextMap.put(REPOSITORY_ID, repositoryId);
        contextMap.put(USERNAME, user);
        contextMap.put(PASSWORD, password);
    }

    public LocalCallContext(String repositoryId, String user, String password, String language, String country) {
        this(repositoryId, user, password);

        if (language != null) {
            put(LOCALE_ISO639_LANGUAGE, language);
            put(LOCALE, language);
        }

        if (country != null) {
            put(LOCALE_ISO3166_COUNTRY, country);
            put(LOCALE, language + "-" + country);
        }
    }

    @Override
    public String getBinding() {
        return BINDING_LOCAL;
    }

    @Override
    public Object get(String key) {
        return contextMap.get(key);
    }

    @Override
    public CmisVersion getCmisVersion() {
        return CmisVersion.CMIS_1_1;
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
    public boolean isObjectInfoRequired() {
        return false;
    }

    @Override
    public File getTempDirectory() {
        return null;
    }

    @Override
    public boolean encryptTempFiles() {
        return false;
    }

    @Override
    public int getMemoryThreshold() {
        return 0;
    }

    @Override
    public long getMaxContentSize() {
        return -1;
    }

    @Override
    public void put(String key, Object value) {
        contextMap.put(key, value);
    }

    @Override
    public Object remove(String key) {
        return contextMap.remove(key);
    }
}