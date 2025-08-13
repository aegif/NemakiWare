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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.report.CoreHtmlReport;

/**
 * Web Runner.
 */
public class WebRunnerServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html; charset=UTF-8");

        PrintWriter pw = resp.getWriter();

        printHeader(pw);

        pw.println("<h1>OpenCMIS TCK</h1>");

        pw.println("<form action=\"" + req.getRequestURI() + "\" method=\"POST\">");
        pw.println("<table>");
        pw.println("<tr><td>AtomPub URL:</td><td><input type=\"text\" name=\"org.apache.chemistry.opencmis.binding.atompub.url\" size=\"50\"></td></tr>");
        pw.println("<tr><td>Username:</td><td><input type=\"text\" name=\"org.apache.chemistry.opencmis.user\" size=\"50\"></td></tr>");
        pw.println("<tr><td>Password:</td><td><input type=\"password\" name=\"org.apache.chemistry.opencmis.password\" size=\"50\"></td></tr>");
        pw.println("<tr><td>Repository Id:</td><td><input type=\"text\" name=\"org.apache.chemistry.opencmis.session.repository.id\" size=\"50\"></td></tr>");
        pw.println("<tr><td></td><td><input type=\"submit\" value=\"Start TCK\"></td></tr>");
        pw.println("<input type=\"hidden\" name=\"org.apache.chemistry.opencmis.binding.spi.type\" value=\"atompub\">");
        pw.println("</form>");

        printFooter(pw);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setBufferSize(0);
        resp.setContentType("text/html; charset=UTF-8");

        PrintWriter pw = resp.getWriter();

        printHeader(pw);

        Map<String, String> parameters = new HashMap<String, String>();
        Map<String, String[]> parameterMap = req.getParameterMap();

        if (parameterMap != null) {
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                if (entry.getValue() == null || entry.getValue().length < 1) {
                    continue;
                }
                parameters.put(entry.getKey(), entry.getValue()[0]);
            }
        }

        try {
            WebRunner runner = new WebRunner();
            runner.setParameters(parameters);
            runner.loadDefaultTckGroups();

            pw.println("<div id=\"progress\">");
            pw.println("<h1>Running OpenCMIS TCK</h1>");

            runner.run(new WebProgressMonitor(pw));

            pw.println("</div>");

            // let progress div disappear
            pw.println("<script language=\"javascript\">");
            pw.println("document.getElementById(\"progress\").style.display = \"none\";");
            pw.println("</script>");

            (new CoreHtmlReport()).createReport(runner.getParameters(), runner.getGroups(), pw);
        } catch (Exception e) {
            pw.println("<h2>Exception</h2>");

            pw.println("\n<pre>");
            e.printStackTrace(pw);
            pw.println("\n</pre>");
        }

        printFooter(pw);
    }

    protected void printHeader(PrintWriter pw) throws IOException {
        pw.println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\" \"http://www.w3.org/TR/html4/strict.dtd\">");
        pw.println("<html><head>\n<title>OpenCMIS TCK</title>");
        CoreHtmlReport.printStyle(pw);
        pw.println("</head><body>");
    }

    protected void printFooter(PrintWriter pw) throws IOException {
        pw.println("\n</body></html>");
        pw.flush();
    }

    private static class WebRunner extends AbstractRunner {
    }

    private static class WebProgressMonitor implements CmisTestProgressMonitor {
        private final PrintWriter pw;

        public WebProgressMonitor(PrintWriter pw) {
            this.pw = pw;
        }

        @Override
        public void startGroup(CmisTestGroup group) {
            pw.println("<h3>" + group.getName() + " (" + group.getTests().size() + " tests)</h3>");
            pw.flush();
        }

        @Override
        public void endGroup(CmisTestGroup group) {
            pw.println("<br>");
            pw.flush();
        }

        @Override
        public void startTest(CmisTest test) {
            pw.print("&nbsp;&nbsp;&nbsp;" + test.getName() + " ... ");
            pw.flush();
        }

        @Override
        public void endTest(CmisTest test) {
            pw.println("completed<br>");
            pw.flush();
        }

        @Override
        public void message(String msg) {
            pw.println(msg);
            pw.flush();
        }
    }
}
