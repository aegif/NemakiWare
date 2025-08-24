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
import java.util.Map;

/**
 * CMIS TCK Test Group.
 */
public interface CmisTestGroup {

    /**
     * Initializes the test group with test parameters.
     */
    void init(Map<String, String> parameters) throws Exception;

    /**
     * Sets the progress monitor that should be used during a run.
     */
    void setProgressMonitor(CmisTestProgressMonitor progressMonitor);

    /**
     * Returns the name of the test group.
     */
    String getName();
    
    /**
     * Returns the description of the test group.
     */
    String getDescription();

    /**
     * Returns the all tests in this group.
     */
    List<CmisTest> getTests();

    /**
     * Runs all enabled tests in this group.
     */
    void run() throws Exception;

    /**
     * Returns if the test group is enabled or not.
     */
    boolean isEnabled();

    /**
     * Enables or disables this test group.
     */
    void setEnabled(boolean enabled);
}
