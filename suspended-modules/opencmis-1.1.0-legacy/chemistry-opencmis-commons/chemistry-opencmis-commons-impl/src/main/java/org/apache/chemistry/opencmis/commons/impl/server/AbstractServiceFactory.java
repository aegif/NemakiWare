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
package org.apache.chemistry.opencmis.commons.impl.server;

import java.io.File;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.server.TempStoreOutputStream;

public abstract class AbstractServiceFactory implements CmisServiceFactory {

    @Override
    public void init(Map<String, String> parameters) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public abstract CmisService getService(CallContext context);

    /**
     * Returns the Java temp directory.
     */
    @Override
    public File getTempDirectory() {
        String tempDir = System.getProperty("java.io.tmpdir");
        return new File(tempDir);
    }

    /**
     * Returns {@code false}, do not encrypt temporary files.
     */
    @Override
    public boolean encryptTempFiles() {
        return false;
    }

    /**
     * Returns a threshold of 4 MiB.
     */
    @Override
    public int getMemoryThreshold() {
        return 4 * 1024 * 1024;
    }

    /**
     * Returns a max size of 4 GiB.
     */
    @Override
    public long getMaxContentSize() {
        return (long) 4 * 1024 * 1024 * 1024;
    }

    /**
     * Returns {@code null} to indicate that the default, temp files based
     * implementation should be used.
     */
    @Override
    public TempStoreOutputStream getTempFileOutputStream(String repositoryId) {
        return null;
    }
}
