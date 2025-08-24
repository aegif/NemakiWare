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
package org.apache.chemistry.opencmis.commons.server;

import java.io.File;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;

/**
 * Factory for {@link CmisService} objects.
 */
public interface CmisServiceFactory {

    /**
     * Initializes the factory instance.
     */
    void init(Map<String, String> parameters);

    /**
     * Cleans up the the factory instance.
     */
    void destroy();

    /**
     * Returns a {@link CmisService} object for the given {@link CallContext}.
     * 
     * When the {@link CmisService} object is not longer needed
     * {@link CmisService#close()} will be called.
     * 
     * @param context
     *            the call context
     * 
     * @return a {@link CmisService} instance, never {@code null}
     */
    CmisService getService(CallContext context);

    /**
     * Returns the absolute path of the directory that should be used for
     * temporary files.
     * 
     * @return absolute path of temp directory
     */
    File getTempDirectory();

    /**
     * Indicates if temporary files should be encrypted.
     * 
     * @return {@code true} if temporary files should be encrypted,
     *         {@code false} otherwise
     * 
     * @see CmisServiceFactory#getTempDirectory()
     */
    boolean encryptTempFiles();

    /**
     * Returns up to which size content should be kept in memory. Documents
     * bigger than this threshold will be cached in a temporary directory.
     * 
     * @return the threshold in bytes
     * 
     * @see CmisServiceFactory#getTempDirectory()
     */
    int getMemoryThreshold();

    /**
     * Returns the maximal content size in bytes. If a client provides content
     * bigger than that, a {@link CmisConstraintException} is thrown.
     * 
     * @return the max size in bytes or -1 to disable the size check
     */
    long getMaxContentSize();

    /**
     * Returns a {@link TempStoreOutputStream} object for the given repository
     * ID.
     * 
     * This method is only called for the AtomPub and the Browser binding
     * requests. The Web Services binding always used temporary files (see
     * {@link #getTempDirectory()}).
     * 
     * If {@code null} is returned, a default implementation that stores the
     * document content in temporary files is used (see
     * {@link #getTempDirectory()}).
     * 
     * @param repositoryId
     *            the repository ID or {@code null} if the repository ID is
     *            unknown
     * 
     * @return a {@link TempStoreOutputStream} instance or {@code null} to use
     *         the default implementation
     */
    TempStoreOutputStream getTempFileOutputStream(String repositoryId);
}
