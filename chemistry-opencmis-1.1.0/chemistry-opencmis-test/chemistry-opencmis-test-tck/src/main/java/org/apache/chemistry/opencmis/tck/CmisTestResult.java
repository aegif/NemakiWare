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
package org.apache.chemistry.opencmis.tck;

import java.util.List;

/**
 * CMIS TCK Test Result.
 */
public interface CmisTestResult {

    /**
     * Returns the group name.
     */
    String getGroupName();

    /**
     * Returns the test name.
     */
    String getTestName();

    /**
     * Returns the check status.
     */
    CmisTestResultStatus getStatus();

    /**
     * Returns the check message.
     */
    String getMessage();

    /**
     * Returns the exception if available.
     */
    Throwable getException();

    /**
     * Returns the stack trace. The first element should point to check.
     */
    StackTraceElement[] getStackTrace();

    /**
     * Returns the URL of the request if available.
     */
    String getUrl();

    /**
     * Returns the request body if available.
     */
    String getRequest();

    /**
     * Returns the response body if available.
     */
    String getResponse();

    /**
     * Returns children of the result that contain more details.
     */
    List<CmisTestResult> getChildren();

    /**
     * Returns if the result was fatal for the test.
     */
    boolean isFatal();
}
