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
import java.util.Map;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.junit.Test;

/**
 * Base class for test groups.
 */
public abstract class AbstractCmisTestGroup implements CmisTestGroup {

    private Map<String, String> parameters;
    private String name;
    private String description;
    private final List<CmisTest> tests = new ArrayList<CmisTest>();
    private boolean isEnabled = true;
    private CmisTestProgressMonitor progressMonitor;

    @Override
    public void init(Map<String, String> parameters) throws Exception {
        this.parameters = parameters;
    }

    protected Map<String, String> getParameters() {
        return parameters;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public List<CmisTest> getTests() {
        return tests;
    }

    public void addTest(CmisTest test) throws Exception {
        if (test != null) {
            tests.add(test);
            if (test instanceof AbstractCmisTest) {
                ((AbstractCmisTest) test).setGroup(this);
            }
            test.init(parameters);
        }
    }

    @Override
    public void setProgressMonitor(CmisTestProgressMonitor progressMonitor) {
        this.progressMonitor = progressMonitor;
    }

    protected CmisTestProgressMonitor getProgressMonitor() {
        return this.progressMonitor;
    }

    @Override
    public void run() throws Exception {
        if (progressMonitor != null) {
            progressMonitor.startGroup(this);
        }

        try {
            preRun();
            for (CmisTest test : tests) {
                if (test == null || !test.isEnabled()) {
                    continue;
                }

                try {
                    if (progressMonitor != null) {
                        progressMonitor.startTest(test);
                    }

                    preTest(test);

                    long start = System.currentTimeMillis();

                    test.run();

                    long end = System.currentTimeMillis();
                    if (test instanceof AbstractCmisTest) {
                        ((AbstractCmisTest) test).setTime(end - start);
                    }
                } catch (Exception e) {
                    if (!(e instanceof FatalTestException)) {
                        throw e;
                    }
                } finally {
                    postTest(test);

                    if (progressMonitor != null) {
                        progressMonitor.endTest(test);
                    }
                }
            }
        } finally {
            postRun();
        }

        if (progressMonitor != null) {
            progressMonitor.endGroup(this);
        }
    }

    @Test
    public void junit() throws Exception {
        JUnitHelper.run(this);
    }

    protected void preRun() {
    }

    protected void postRun() {
    }

    protected void preTest(CmisTest test) {
    }

    protected void postTest(CmisTest test) {
    }

    @Override
    public boolean isEnabled() {
        return isEnabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }
}
