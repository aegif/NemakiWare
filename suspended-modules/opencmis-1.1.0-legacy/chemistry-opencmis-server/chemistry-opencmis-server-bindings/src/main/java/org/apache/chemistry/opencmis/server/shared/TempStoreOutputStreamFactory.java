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
package org.apache.chemistry.opencmis.server.shared;

import java.io.File;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.server.TempStoreOutputStream;

/**
 * A factory for {@link TempStoreOutputStream} objects.
 */
public class TempStoreOutputStreamFactory {

    private CmisServiceFactory factory;
    private String repositoryId;
    private HttpServletRequest request;

    private File tempDir;
    private int memoryThreshold;
    private long maxContentSize;
    private boolean encrypt;

    protected TempStoreOutputStreamFactory(CmisServiceFactory factory, String repositoryId, HttpServletRequest request) {
        this.factory = factory;
        this.repositoryId = repositoryId;
        this.tempDir = factory.getTempDirectory();
        this.memoryThreshold = factory.getMemoryThreshold();
        this.maxContentSize = factory.getMaxContentSize();
        this.encrypt = factory.encryptTempFiles();
        this.request = request;
    }

    protected TempStoreOutputStreamFactory(File tempDir, int memoryThreshold, long maxContentSize, boolean encrypt) {
        this.factory = null;
        this.repositoryId = null;
        this.tempDir = tempDir;
        this.memoryThreshold = memoryThreshold;
        this.maxContentSize = maxContentSize;
        this.encrypt = encrypt;
    }

    /**
     * Creates a new factory. The parameters are used to create new
     * {@link TempStoreOutputStream} objects.
     * 
     * @param factory
     *            the CmisService object factory
     * @param repositoryId
     *            the repository ID
     * @param request
     *            the HTTP request object or {@code null} if such an object is
     *            not available
     */
    public static TempStoreOutputStreamFactory newInstance(CmisServiceFactory factory, String repositoryId,
            HttpServletRequest request) {
        return new TempStoreOutputStreamFactory(factory, repositoryId, request);
    }

    /**
     * Creates a new factory. The parameters are used to create new
     * {@link TempStoreOutputStream} objects.
     * 
     * @param tempDir
     *            temp directory or {@code null} for the default temp directory
     * @param memoryThreshold
     *            memory threshold in bytes
     * @param maxContentSize
     *            max size of the content in bytes (-1 to disable the check)
     * @param encrypt
     *            indicates if temporary files must be encrypted
     */
    public static TempStoreOutputStreamFactory newInstance(File tempDir, int memoryThreshold, long maxContentSize,
            boolean encrypt) {
        return new TempStoreOutputStreamFactory(tempDir, memoryThreshold, maxContentSize, encrypt);
    }

    /**
     * Creates a new {@link TempStoreOutputStream} object.
     */
    public TempStoreOutputStream newOutputStream() {
        TempStoreOutputStream stream = null;

        if (factory != null) {
            stream = factory.getTempFileOutputStream(repositoryId);
        }

        if (stream == null) {
            stream = new ThresholdOutputStream(tempDir, memoryThreshold, maxContentSize, encrypt);
        } else if (stream instanceof RequestAwareTempStoreOutputStream) {
            ((RequestAwareTempStoreOutputStream) stream).setHttpServletRequest(request);
        }

        return stream;
    }

    /**
     * Returns the temp directory or {@code null} for the default temp
     * directory.
     */
    public File getTempDir() {
        return tempDir;
    }

    /**
     * Returns the memory threshold in bytes.
     */
    public int getMemoryThreshold() {
        return memoryThreshold;
    }

    /**
     * Returns the max content size in bytes.
     */
    public long getMaxContentSize() {
        return maxContentSize;
    }

    /**
     * Indicates if temporary files are encrypted.
     */
    public boolean isEncrypted() {
        return encrypt;
    }
}
