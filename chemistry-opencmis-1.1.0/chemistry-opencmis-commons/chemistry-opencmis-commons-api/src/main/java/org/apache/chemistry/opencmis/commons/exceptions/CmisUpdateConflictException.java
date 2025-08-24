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
package org.apache.chemistry.opencmis.commons.exceptions;

import java.math.BigInteger;
import java.util.Map;

/**
 * CMIS UpdateConflict Exception.
 * <p>
 * Intent: The operation is attempting to update an object that is no longer
 * current (as determined by the repository).
 */
public class CmisUpdateConflictException extends CmisBaseException {

    private static final long serialVersionUID = 1L;
    public static final String EXCEPTION_NAME = "updateConflict";

    /**
     * Default constructor.
     */
    public CmisUpdateConflictException() {
        super();
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param code
     *            error code
     * @param cause
     *            the cause
     */
    public CmisUpdateConflictException(String message, BigInteger code, Throwable cause) {
        super(message, code, cause);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param errorContent
     *            error page content
     */
    public CmisUpdateConflictException(String message, String errorContent) {
        super(message, errorContent);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param code
     *            error code
     */
    public CmisUpdateConflictException(String message, BigInteger code) {
        super(message, code);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param code
     *            error code
     * @param errorContent
     *            error page content
     */
    public CmisUpdateConflictException(String message, BigInteger code, String errorContent) {
        super(message, code, errorContent);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param code
     *            error code
     * @param errorContent
     *            error page content
     * @param additionalData
     *            additional data
     */
    public CmisUpdateConflictException(String message, BigInteger code, String errorContent,
            Map<String, String> additionalData) {
        super(message, code, errorContent, additionalData);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param errorContent
     *            error page content
     * @param additionalData
     *            additional data
     * @param cause
     *            the cause
     */
    public CmisUpdateConflictException(String message, String errorContent, Map<String, String> additionalData,
            Throwable cause) {
        super(message, errorContent, additionalData, cause);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param errorContent
     *            error page content
     * @param cause
     *            the cause
     */
    public CmisUpdateConflictException(String message, String errorContent, Throwable cause) {
        super(message, errorContent, cause);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     * @param cause
     *            the cause
     */
    public CmisUpdateConflictException(String message, Throwable cause) {
        super(message, BigInteger.ZERO, cause);
    }

    /**
     * Constructor.
     * 
     * @param message
     *            error message
     */
    public CmisUpdateConflictException(String message) {
        super(message, BigInteger.ZERO);
    }

    @Override
    public final String getExceptionName() {
        return EXCEPTION_NAME;
    }
}
