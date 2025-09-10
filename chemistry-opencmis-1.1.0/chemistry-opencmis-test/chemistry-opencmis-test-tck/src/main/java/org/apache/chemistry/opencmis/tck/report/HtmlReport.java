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
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.tck.CmisTestGroup;

/**
 * HTML Report.
 */
public class HtmlReport extends CoreHtmlReport {
    public HtmlReport() {
    }

    @Override
    public void createReport(Map<String, String> parameters, List<CmisTestGroup> groups, Writer writer)
            throws IOException {
        writer.write("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\" \"http://www.w3.org/TR/html4/strict.dtd\">\n");
        writer.write("<html><head>\n<title>OpenCMIS TCK Report</title>\n");
        printStyle(writer);
        printJavaScript(writer);
        writer.write("</head><body>\n");

        super.createReport(parameters, groups, writer);

        writer.write("\n</body></html>\n");
        writer.flush();
    }
}
