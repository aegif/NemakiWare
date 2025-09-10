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
package org.apache.chemistry.opencmis.tck.runner;

import java.io.File;
import java.io.PrintWriter;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.report.TextReport;

/**
 * Console Runner.
 * 
 * This runner can be started for a console and accepts two parameters: OpenCMIS
 * Session parameters file name and group list file name.
 */
public class ConsoleRunner extends AbstractRunner {

    public ConsoleRunner(String[] args) throws Exception {
        if (args.length < 1) {
            setParameters(null);
        } else {
            loadParameters(new File(args[0]));
        }

        if (args.length < 2) {
            loadDefaultTckGroups();
        } else {
            loadGroups(new File(args[1]));
        }

        run(new ConsoleProgressMonitor());

        CmisTestReport report = new TextReport();
        report.createReport(getParameters(), getGroups(), new PrintWriter(System.out));
    }

    private static class ConsoleProgressMonitor implements CmisTestProgressMonitor {
        
        @Override
        @SuppressWarnings("PMD.SystemPrintln")
        public void startGroup(CmisTestGroup group) {
            System.out.println(group.getName() + " (" + group.getTests().size() + " tests)");
        }

        @Override
        @SuppressWarnings("PMD.SystemPrintln")
        public void endGroup(CmisTestGroup group) {
            System.out.println();
        }

        @Override
        @SuppressWarnings("PMD.SystemPrintln")
        public void startTest(CmisTest test) {
            System.out.print('.');
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

    public static void main(String[] args) throws Exception {
        new ConsoleRunner(args);
    }
}
