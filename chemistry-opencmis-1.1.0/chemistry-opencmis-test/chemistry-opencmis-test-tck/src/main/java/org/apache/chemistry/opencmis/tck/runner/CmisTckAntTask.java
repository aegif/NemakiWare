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
import java.util.Locale;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.report.HtmlReport;
import org.apache.chemistry.opencmis.tck.report.JsonReport;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.report.XmlReport;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * CMIS TCK Ant Task.
 */
public class CmisTckAntTask extends Task {

    private static final String REPORT_TEXT = "text";
    private static final String REPORT_XML = "xml";
    private static final String REPORT_HTML = "html";
    private static final String REPORT_JSON = "json";

    private static final String DEFAULT_REPORT_NAME = "cmis-tck-report";

    private File parameters;
    private File groups;
    private File output;
    private String format;

    @Override
    public void init() {
        super.init();
        parameters = null;
        groups = null;
        output = null;
        format = REPORT_TEXT;
    }

    public void setParameters(File parameters) {
        this.parameters = parameters;
    }

    public void setGroups(File groups) {
        this.groups = groups;
    }

    public void setOutput(File output) {
        this.output = output;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    @Override
    public void execute() {
        try {
            AntRunner runner = new AntRunner();

            if (parameters == null) {
                runner.setParameters(null);
            } else {
                runner.loadParameters(parameters);
            }

            if (groups == null) {
                runner.loadDefaultTckGroups();
            } else {
                runner.loadGroups(groups);
            }

            CmisTestReport report = null;

            if (format == null) {
                report = new TextReport();
                if (output == null) {
                    output = new File(DEFAULT_REPORT_NAME + ".txt");
                }
            } else {
                format = format.trim().toLowerCase(Locale.ENGLISH);
                if (REPORT_TEXT.equals(format)) {
                    report = new TextReport();
                    if (output == null) {
                        output = new File(DEFAULT_REPORT_NAME + ".txt");
                    }
                } else if (REPORT_XML.equals(format)) {
                    report = new XmlReport();
                    if (output == null) {
                        output = new File(DEFAULT_REPORT_NAME + ".xml");
                    }
                } else if (REPORT_JSON.equals(format)) {
                    report = new JsonReport();
                    if (output == null) {
                        output = new File(DEFAULT_REPORT_NAME + ".json");
                    }
                } else if (REPORT_HTML.equals(format)) {
                    report = new HtmlReport();
                    if (output == null) {
                        output = new File(DEFAULT_REPORT_NAME + ".html");
                    }
                } else {
                    throw new BuildException("Unknown format!");
                }
            }

            runner.run(new AntProgressMonitor());

            log("CMIS TCK Report: " + output.getAbsolutePath());
            report.createReport(runner.getParameters(), runner.getGroups(), output);
        } catch (Exception e) {
            throw new BuildException("OpenCMIS TCK run failed!", e);
        }
    }

    private static class AntRunner extends AbstractRunner {

    }

    private class AntProgressMonitor implements CmisTestProgressMonitor {
        @Override
        public void startGroup(CmisTestGroup group) {
            log(group.getName() + " (" + group.getTests().size() + " tests)");
        }

        @Override
        public void endGroup(CmisTestGroup group) {
        }

        @Override
        public void startTest(CmisTest test) {
            log("  " + test.getName());
        }

        @Override
        public void endTest(CmisTest test) {
        }

        @Override
        public void message(String msg) {
            log(msg);
        }
    }
}
