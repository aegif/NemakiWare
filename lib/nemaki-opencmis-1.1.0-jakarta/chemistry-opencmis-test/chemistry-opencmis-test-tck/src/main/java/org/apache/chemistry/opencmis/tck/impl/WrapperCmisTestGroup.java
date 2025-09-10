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

import java.util.Map;

import org.apache.chemistry.opencmis.tck.CmisTest;

/**
 * Helper group implementation that just hold one test.
 */
public class WrapperCmisTestGroup extends AbstractCmisTestGroup {
    private final CmisTest test;

    public WrapperCmisTestGroup(CmisTest test) {
        if (test == null) {
            throw new IllegalArgumentException("Test is null!");
        }

        this.test = test;
    }

    @Override
    public void init(Map<String, String> parameters) throws Exception {
        super.init(parameters);

        addTest(test);
        setName("Wrapper Group: " + test.getName());
    }
}
