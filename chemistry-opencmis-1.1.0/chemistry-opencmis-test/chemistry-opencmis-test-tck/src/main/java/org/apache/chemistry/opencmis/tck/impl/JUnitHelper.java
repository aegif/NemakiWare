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

import java.io.File;
import java.io.PrintWriter;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;
import org.junit.Assert;

public final class JUnitHelper {

    public static final String JUNIT_PARAMETERS = "org.apache.chemistry.opencmis.tck.junit.parameters";

    private JUnitHelper() {
    }

    public static void run(CmisTest test) throws Exception {
        run(new WrapperCmisTestGroup(test));
    }

    public static void run(CmisTestGroup group) throws Exception {
        JUnitRunner runner = new JUnitRunner();

        String parametersFile = System.getProperty(JUNIT_PARAMETERS);
        if (parametersFile == null) {
            runner.setParameters(null);
        } else {
            runner.loadParameters(new File(parametersFile));
        }

        runner.addGroup(group);
        runner.run(new JUnitProgressMonitor());

        CmisTestReport report = new TextReport();
        report.createReport(runner.getParameters(), runner.getGroups(), new PrintWriter(System.out));

        checkForFailures(runner);
    }

    private static void checkForFailures(JUnitRunner runner) {
        for (CmisTestGroup group : runner.getGroups()) {
            for (CmisTest test : group.getTests()) {
                for (CmisTestResult result : test.getResults()) {
                    if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                        Assert.fail(result.getMessage());
                    }
                }
            }
        }
    }

    private static class JUnitRunner extends AbstractRunner {
    }

    private static class JUnitProgressMonitor implements CmisTestProgressMonitor {

        @Override
        @SuppressWarnings("PMD.SystemPrintln")
        public void startGroup(CmisTestGroup group) {
            System.out.println(group.getName() + " (" + group.getTests().size() + " tests)");
        }

        @Override
        public void endGroup(CmisTestGroup group) {
        }

        @Override
        @SuppressWarnings("PMD.SystemPrintln")
        public void startTest(CmisTest test) {
            System.out.println("  " + test.getName());
        }

        @Override
        public void endTest(CmisTest test) {
        }

        @Override
        @SuppressWarnings("PMD.SystemPrintln")
        public void message(String msg) {
            System.out.println(msg);
        }
    }
}
