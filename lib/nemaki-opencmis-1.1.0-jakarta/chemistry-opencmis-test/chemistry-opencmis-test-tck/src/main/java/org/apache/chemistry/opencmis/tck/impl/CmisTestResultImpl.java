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
package org.apache.chemistry.opencmis.tck.impl;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;

/**
 * CmisTestResult implementation.
 */
public class CmisTestResultImpl implements CmisTestResult {
    private final String groupName;
    private final String testName;
    private final String message;
    private final CmisTestResultStatus status;
    private final Throwable exception;
    private StackTraceElement[] stackTrace;
    private String url;
    private String request;
    private String response;
    private final List<CmisTestResult> children = new ArrayList<CmisTestResult>();
    private final boolean isFatal;

    public CmisTestResultImpl(String groupName, String testName, String message, CmisTestResultStatus status,
            Throwable exception, boolean isFatal) {
        this.groupName = groupName;
        this.testName = testName;
        this.message = message;
        this.status = status;
        this.exception = exception;
        this.isFatal = isFatal;
    }

    @Override
    public String getGroupName() {
        return groupName;
    }

    @Override
    public String getTestName() {
        return testName;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public CmisTestResultStatus getStatus() {
        return status;
    }

    @Override
    public Throwable getException() {
        return exception;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        return stackTrace;
    }

    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    public void setStackTrace(StackTraceElement[] stackTrace) {
        this.stackTrace = stackTrace;
    }

    @Override
    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    @Override
    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    @Override
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public List<CmisTestResult> getChildren() {
        return children;
    }

    @Override
    public boolean isFatal() {
        return isFatal;
    }

    @Override
    public String toString() {
        return status + ": " + groupName + "/" + testName + ": " + message;
    }
}
