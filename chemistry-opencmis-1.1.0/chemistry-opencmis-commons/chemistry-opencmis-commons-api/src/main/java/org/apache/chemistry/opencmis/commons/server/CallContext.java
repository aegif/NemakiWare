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
package org.apache.chemistry.opencmis.commons.server;

import java.io.File;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;

/**
 * An object implementing this interface holds context data of the current call.
 */
public interface CallContext {

    /** Binding: AtomPub */
    String BINDING_ATOMPUB = "atompub";
    /** Binding: Web Services */
    String BINDING_WEBSERVICES = "webservices";
    /** Binding: Browser */
    String BINDING_BROWSER = "browser";
    /** Binding: Local */
    String BINDING_LOCAL = "local";

    /** Key: CMIS version (value is a CmisVersion) */
    String CMIS_VERSION = "cmisVersion";

    /** Key: repository id */
    String REPOSITORY_ID = "repositoryId";
    /** Key: username */
    String USERNAME = "username";
    /** Key: password */
    String PASSWORD = "password";
    /** Key: local */
    String LOCALE = "locale";
    /** Key: offset (value is a BigInteger) */
    String OFFSET = "offset";
    /** Key: length (value is a BigInteger) */
    String LENGTH = "length";
    String LOCALE_ISO639_LANGUAGE = "language";
    String LOCALE_ISO3166_COUNTRY = "country";

    /** Key: servlet context (value is a ServletContext) */
    String SERVLET_CONTEXT = "servletContext";
    /** Key: servlet request (value is a HttpServletRequest) */
    String HTTP_SERVLET_REQUEST = "httpServletRequest";
    /** Key: servlet response (value is a HttpServletResponse) */
    String HTTP_SERVLET_RESPONSE = "httpServletResponse";

    /** Key: temp directory */
    String TEMP_DIR = "tempDir";
    /** Key: memory threshold (values is an Integer) */
    String MEMORY_THRESHOLD = "memoryThreshold";
    /** Key: max content size (values is a Long) */
    String MAX_CONTENT_SIZE = "maxContentSize";
    /** Key: encrypt temp files (values is a Boolean) */
    String ENCRYPT_TEMP_FILE = "encryptTempFiles";
    /**
     * Key: factory for threshold streams (value is a
     * TempStoreOutputStreamFactory)
     */
    String STREAM_FACTORY = "streamFactory";

    /**
     * Returns the binding. Usually it returns
     * {@link CallContext#BINDING_ATOMPUB},
     * {@link CallContext#BINDING_WEBSERVICES},
     * {@link CallContext#BINDING_BROWSER} or {@link CallContext#BINDING_LOCAL}.
     * 
     * @return the binding
     */
    String getBinding();

    /**
     * Returns if {@code true} object infos can improve the performance.
     * 
     * @return if object infos are required
     */
    boolean isObjectInfoRequired();

    /**
     * Returns context data by key.
     * 
     * @param key
     *            the key
     * 
     * @return the data if the key is valid, {@code null} otherwise
     */
    Object get(String key);

    /**
     * Returns the CMIS version.
     * 
     * @return the CMIS version, not {@code null}
     */
    CmisVersion getCmisVersion();

    /**
     * Returns the repository ID.
     * 
     * @return the repository ID or {@code null} if it's a repository info call
     */
    String getRepositoryId();

    /**
     * Returns the user name.
     * 
     * @return the user name if provided
     */
    String getUsername();

    /**
     * Returns the password.
     * 
     * @return the password if provided
     */
    String getPassword();

    /**
     * Returns the locale.
     * 
     * @return the locale if set
     */
    String getLocale();

    /**
     * Returns the content offset.
     * 
     * @return the content offset if set, {@code null} otherwise
     */
    BigInteger getOffset();

    /**
     * Returns the content length.
     * 
     * @return the content length if set, {@code null} otherwise
     */
    BigInteger getLength();

    /**
     * Returns the temp directory.
     * 
     * @return the temp directory
     */
    File getTempDirectory();

    /**
     * Returns if temp files should be encrypted.
     * 
     * @return {@code true} if temp files should be encrypted, {@code false}
     *         otherwise
     */
    boolean encryptTempFiles();

    /**
     * Returns the memory threshold.
     * 
     * @return the memory threshold in bytes
     */
    int getMemoryThreshold();

    /**
     * Returns the max size of content.
     * 
     * @return the max content size in bytes
     */
    long getMaxContentSize();
}
