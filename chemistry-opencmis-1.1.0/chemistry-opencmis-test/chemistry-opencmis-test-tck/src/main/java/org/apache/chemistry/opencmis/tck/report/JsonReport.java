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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;

/**
 * JSON Report.
 */
public class JsonReport extends AbstractCmisTestReport {

    public JsonReport() {
    }

    @Override
    public void createReport(Map<String, String> parameters, List<CmisTestGroup> groups, Writer writer)
            throws IOException {

        JSONObject jsonReport = new JSONObject();

        JSONObject jsonParameters = new JSONObject();
        jsonReport.put("parameters", jsonParameters);

        if (parameters != null) {
            for (Map.Entry<String, String> p : (new TreeMap<String, String>(parameters)).entrySet()) {
                String value = p.getValue();
                if (SessionParameter.PASSWORD.endsWith(p.getKey())) {
                    value = "*****";
                }

                jsonParameters.put(p.getKey(), value);
            }
        }

        if (groups != null) {
            JSONArray jsonGroups = new JSONArray();
            jsonReport.put("groups", jsonGroups);

            for (CmisTestGroup group : groups) {
                printGroupResults(group, jsonGroups);
            }
        }

        jsonReport.writeJSONString(writer);
        writer.flush();
    }

    private void printGroupResults(CmisTestGroup group, JSONArray jsonGroups) throws IOException {
        if (!group.isEnabled()) {
            return;
        }

        JSONObject jsonGroup = new JSONObject();
        jsonGroups.add(jsonGroup);

        jsonGroup.put("name", group.getName());

        if (group.getTests() != null && !group.getTests().isEmpty()) {
            JSONArray jsonTests = new JSONArray();
            jsonGroup.put("tests", jsonTests);

            for (CmisTest test : group.getTests()) {
                printTestResults(test, jsonTests);
            }
        }
    }

    private void printTestResults(CmisTest test, JSONArray jsonTests) throws IOException {
        if (!test.isEnabled()) {
            return;
        }

        JSONObject jsonTest = new JSONObject();
        jsonTests.add(jsonTest);

        jsonTest.put("name", test.getName());
        jsonTest.put("time", test.getTime());

        if (test.getResults() != null && !test.getResults().isEmpty()) {
            JSONArray jsonResults = new JSONArray();
            jsonTest.put("results", jsonResults);

            for (CmisTestResult result : test.getResults()) {
                printResult(result, jsonResults);
            }
        }
    }

    private void printResult(CmisTestResult result, JSONArray results) throws IOException {
        JSONObject jsonResult = new JSONObject();
        results.add(jsonResult);

        jsonResult.put("status", result.getStatus().toString());
        jsonResult.put("message", result.getMessage());

        if (result.getStackTrace() != null && result.getStackTrace().length > 0) {
            jsonResult.put("file",
                    result.getStackTrace()[0].getFileName() + ":" + result.getStackTrace()[0].getLineNumber());
        }

        if (result.getStatus() == CmisTestResultStatus.UNEXPECTED_EXCEPTION && result.getException() != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            result.getException().printStackTrace(pw);

            jsonResult.put("stacktrace", sw.toString());

            if (result.getException() instanceof CmisBaseException) {
                CmisBaseException cbe = (CmisBaseException) result.getException();
                if (cbe.getErrorContent() != null) {
                    jsonResult.put("errorcontent", cbe.getErrorContent());
                }
            }
        }

        if (result.getException() != null) {
            jsonResult.put("exception", result.getException().getMessage());
        }

        if (result.getRequest() != null) {
            jsonResult.put("request", result.getRequest());
        }

        if (result.getRequest() != null) {
            jsonResult.put("response", result.getResponse());
        }

        if (!result.getChildren().isEmpty()) {
            JSONArray nextLevel = new JSONArray();
            jsonResult.put("results", nextLevel);

            for (CmisTestResult child : result.getChildren()) {
                printResult(child, nextLevel);
            }
        }
    }
}
