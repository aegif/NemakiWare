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
package org.apache.chemistry.opencmis.tck.report;

import java.io.IOException;
import java.io.Writer;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;

/**
 * HTML Report without header and footer.
 */
public class CoreHtmlReport extends AbstractCmisTestReport {

    private int stackTraceCounter;
    private String revision;

    public CoreHtmlReport() {
    }

    @Override
    public void createReport(Map<String, String> parameters, List<CmisTestGroup> groups, Writer writer)
            throws IOException {
        stackTraceCounter = 0;
        if (parameters != null) {
            revision = parameters.get(AbstractRunner.TCK_REVISION_PARAMETER);
        }

        writer.write("<h1>OpenCMIS TCK Report</h1>\n");
        writer.write((new Date()) + "\n");

        writer.write("\n<h2>Parameters</h2>\n");

        if (parameters != null) {
            writer.write("<table>\n");
            for (Map.Entry<String, String> p : (new TreeMap<String, String>(parameters)).entrySet()) {
                String value = p.getValue();
                if (SessionParameter.PASSWORD.endsWith(p.getKey())) {
                    value = "*****";
                }

                writer.write("<tr><td>" + escape(p.getKey()) + "</td><td>" + escape(value) + "</td></tr>\n");
            }
            writer.write("</table>\n");
        }

        writer.write("\n<h2>Groups</h2>\n");

        if (groups != null) {
            for (CmisTestGroup group : groups) {
                printGroupResults(group, writer);
            }
        }

        writer.flush();
    }

    public static void printStyle(Writer writer) throws IOException {
        writer.write("<style type=\"text/css\">\n");
        writer.write("body { font-family: sans-serif; }\n");
        writer.write(".tckResultINFO { margin-left: 10px; margin-right: 10px; padding: 2px; }\n");
        writer.write(".tckResultSKIPPED { margin-left: 10px; margin-right: 10px; padding: 2px; background-color: #FFFFFF; }\n");
        writer.write(".tckResultOK { margin-left: 10px; margin-right: 5px; padding: 2px; background-color: #00FF00; }\n");
        writer.write(".tckResultWARNING { margin-left: 10px; margin-right: 10px; padding: 2px; background-color: #FFFF00; }\n");
        writer.write(".tckResultFAILURE { margin-left: 10px; margin-right: 10px; padding: 2px; background-color: #FF6000; }\n");
        writer.write(".tckResultUNEXPECTED_EXCEPTION { margin-left: 10px; margin-right: 10px; padding: 2px; background-color: #FF0000; }\n");
        writer.write(".tckTraceLink { cursor: pointer; text-decoration: underline; }\n");
        writer.write(".tckTrace { margin-left: 10px; margin-right: 10px; padding: 2px; border:2px solid #777777; background-color: #DDDDDD; }\n");
        writer.write("</style>\n");
    }

    public static void printJavaScript(Writer writer) throws IOException {
        writer.write("<script type=\"text/javascript\">\n");
        writer.write("function tckToggleDisplay(id) {\n");
        writer.write("(function(style) { style.display = style.display === 'none' ? '' : 'none'; })(document.getElementById(id).style);\n");
        writer.write("}\n");
        writer.write("</script>\n");
    }

    private void printGroupResults(CmisTestGroup group, Writer writer) throws IOException {
        if (!group.isEnabled()) {
            return;
        }

        writer.write("\n<hr>\n<h3>" + escape(group.getName()) + "</h3>\n");

        if (group.getDescription() != null) {
            writer.write("\n<p><i>" + escape(group.getDescription()) + "</i></p>\n");
        }

        if (group.getTests() != null) {
            for (CmisTest test : group.getTests()) {
                printTestResults(test, writer);
            }
        }
    }

    private void printTestResults(CmisTest test, Writer writer) throws IOException {
        if (!test.isEnabled()) {
            return;
        }

        writer.write("\n<h4>" + escape(test.getName()) + " (" + test.getTime() + " ms)</h4>\n");

        if (test.getDescription() != null) {
            writer.write("\n<p><i>" + escape(test.getDescription()) + "</i></p>\n");
        }

        if (test.getResults() != null) {
            for (CmisTestResult result : test.getResults()) {
                writer.write("<div style=\"padding: 5px;\">\n");
                printResult(result, writer);
                writer.write("</div>\n");
            }
        }
    }

    private void printResult(CmisTestResult result, Writer writer) throws IOException {
        stackTraceCounter++;
        String stackTraceId = "tckTrace" + stackTraceCounter;
        String exceptionId = "tckException" + stackTraceCounter;

        boolean hasStackTrace = result.getStackTrace() != null && result.getStackTrace().length > 0;
        boolean hasException = result.getStatus() == CmisTestResultStatus.UNEXPECTED_EXCEPTION
                && result.getException() != null;

        writer.write("<div class=\"tckResult" + result.getStatus().name() + "\">\n");

        writer.write("<b>" + result.getStatus() + "</b>: " + escape(result.getMessage()));

        if (hasStackTrace) {
            writer.write(" (" + getSourceCodeLink(result.getStackTrace()[0], revision) + ")");
            writer.write(" [<span class=\"tckTraceLink\" onClick=\"tckToggleDisplay('" + stackTraceId
                    + "');\">stacktrace</span>]");
        }

        if (hasException) {
            writer.write(" [<span class=\"tckTraceLink\" onClick=\"tckToggleDisplay('" + exceptionId
                    + "');\">exception details</span>]");
        }

        writer.write("<br/>\n");

        if (hasStackTrace) {
            writer.write("<div class=\"tckTrace\" id=\"" + stackTraceId + "\" style=\"display:none\">\n");

            for (StackTraceElement ste : result.getStackTrace()) {
                if (AbstractRunner.class.getName().equals(ste.getClassName())) {
                    break;
                }
                writer.write(ste.getClassName() + "." + ste.getMethodName() + "(" + getSourceCodeLink(ste, revision)
                        + ")<br/>\n");
            }

            writer.write("</div>\n");
        }

        if (hasException) {
            writer.write("<div class=\"tckTrace\" id=\"" + exceptionId + "\" style=\"display:none\">\n");
            writer.write("<b>Exception stack trace:</b><br/><br/>\n");

            for (StackTraceElement ste : result.getException().getStackTrace()) {
                if (AbstractRunner.class.getName().equals(ste.getClassName())) {
                    break;
                }
                writer.write(ste.getClassName() + "." + ste.getMethodName() + "(" + getSourceCodeLink(ste, revision)
                        + ")<br/>\n");
            }

            if (result.getException() instanceof CmisBaseException) {
                CmisBaseException cbe = (CmisBaseException) result.getException();
                if (cbe.getErrorContent() != null) {
                    writer.write("<br/>\n<b>Error content:</b><br/><br/><pre>\n");
                    writer.write(escape(cbe.getErrorContent()) + "</pre>\n");
                }
            }

            writer.write("</div>\n");
        }

        for (CmisTestResult child : result.getChildren()) {
            printResult(child, writer);
        }

        writer.write("</div>\n");
    }

    protected String getSourceCodeLink(StackTraceElement ste, String revision) {
        StringBuilder result = new StringBuilder(1024);

        if (!ste.getClassName().startsWith("org.apache.chemistry.opencmis.tck.")) {
            result.append(escape(ste.getFileName()));
            if (ste.getLineNumber() > 0) {
                result.append(':');
                result.append(ste.getLineNumber());
            }
        } else {
            result.append("<a href=\"https://svn.apache.org/viewvc/chemistry/opencmis/trunk/"
                    + "chemistry-opencmis-test/chemistry-opencmis-test-tck/src/main/java/");
            result.append(ste.getClassName().replaceAll("\\.", "/"));
            result.append(".java?view=markup");
            if (revision != null) {
                result.append("&revision=");
                result.append(revision);
            }
            if (ste.getLineNumber() > 0) {
                result.append("#l");
                result.append(ste.getLineNumber());
            }

            result.append("\" target=\"_blank\">");

            result.append(escape(ste.getFileName()));
            if (ste.getLineNumber() > 0) {
                result.append(':');
                result.append(ste.getLineNumber());
            }

            result.append("</a>");
        }
        return result.toString();
    }

    protected String escape(String s) {
        if (s == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(s.length() + 32);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '\"':
                sb.append("&quot;");
                break;
            case '&':
                sb.append("&amp;");
                break;
            case '<':
                sb.append("&lt;");
                break;
            case '>':
                sb.append("&gt;");
                break;
            default:
                sb.append(c);
            }
        }

        return sb.toString();
    }
}
