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

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Provides a convenient implementation of the {@link CallContext} interface that can be
 * subclassed by developers wishing to change, add, or hide call context data.
 * 
 * This class implements the Wrapper or Decorator pattern. Methods default to
 * calling through to the wrapped request object.
 */
public class CallContextWrapper implements CallContext {

    private final CallContext context;

    public CallContextWrapper(CallContext context) {
        this.context = context;
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
    public Object get(String key) {
        return context.get(key);
    }

    @Override
    public CmisVersion getCmisVersion() {
        return context.getCmisVersion();
    }

    @Override
    public String getRepositoryId() {
        return context.getRepositoryId();
    }

    @Override
    public String getUsername() {
        return context.getUsername();
    }

    @Override
    public String getPassword() {
        return context.getPassword();
    }

    @Override
    public String getLocale() {
        return context.getLocale();
    }

    @Override
    public BigInteger getOffset() {
        return context.getOffset();
    }

    @Override
    public BigInteger getLength() {
        return context.getLength();
    }

    @Override
    public File getTempDirectory() {
        return context.getTempDirectory();
    }

    @Override
    public boolean encryptTempFiles() {
        return context.encryptTempFiles();
    }

    @Override
    public int getMemoryThreshold() {
        return context.getMemoryThreshold();
    }

    @Override
    public long getMaxContentSize() {
        return context.getMaxContentSize();
    }
}
