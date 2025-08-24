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
package org.apache.chemistry.opencmis.inmemory;

import java.io.File;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class DummyCallContext implements CallContext {
    private static final int FOUR_M = 4;
    private static final int SIZE_KB = 1024;
    private final Map<String, Object> fParameter = new HashMap<String, Object>();

    public DummyCallContext() {
        fParameter.put(USERNAME, "Admin");
        fParameter.put(PASSWORD, "secret");
        fParameter.put(LOCALE, "en");
    }

    public DummyCallContext(String principalId) {
        fParameter.put(USERNAME, principalId);
        fParameter.put(PASSWORD, "secret");
        fParameter.put(LOCALE, "en");
    }

    @Override
    public boolean isObjectInfoRequired() {
        return false;
    }

    @Override
    public Object get(String key) {
        return fParameter.get(key);
    }

    @Override
    public String getBinding() {
        return BINDING_ATOMPUB;
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
    public String getPassword() {
        return (String) get(PASSWORD);
    }

    @Override
    public String getUsername() {
        return (String) get(USERNAME);
    }

    public void put(String key, String value) {
        fParameter.put(key, value);
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
        return FOUR_M * SIZE_KB * SIZE_KB;
    }

    @Override
    public long getMaxContentSize() {
        return FOUR_M * SIZE_KB * SIZE_KB * SIZE_KB;
    }
}
