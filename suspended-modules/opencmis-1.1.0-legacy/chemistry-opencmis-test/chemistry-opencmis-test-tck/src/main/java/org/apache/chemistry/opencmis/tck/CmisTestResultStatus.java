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

/**
 * CMIS TCK Test Result Status.
 */
public enum CmisTestResultStatus {

    INFO(0), // no check, just for reports
    SKIPPED(1), // check has been skipped
    OK(2), // check passed
    WARNING(3), // check failed but it is not specification violation
    FAILURE(4), // check failed and it is specification violation
    UNEXPECTED_EXCEPTION(5); // exception caught that is not handled by the test

    private final int level;

    CmisTestResultStatus(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public static CmisTestResultStatus fromLevel(int level) {
        for (CmisTestResultStatus c : CmisTestResultStatus.values()) {
            if (c.level == level) {
                return c;
            }
        }
        throw new IllegalArgumentException(String.valueOf(level));
    }
}
