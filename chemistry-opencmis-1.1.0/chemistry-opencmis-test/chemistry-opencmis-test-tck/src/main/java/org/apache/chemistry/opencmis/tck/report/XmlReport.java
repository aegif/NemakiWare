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

import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestResult;

/**
 * XML Report.
 */
public class XmlReport extends AbstractCmisTestReport {
    private static final String TAG_REPORT = "report";
    private static final String TAG_PARAMETERS = "parameters";
    private static final String TAG_PARAMETER = "parameter";
    private static final String TAG_GROUP = "group";
    private static final String TAG_TEST = "test";
    private static final String TAG_RESULT = "result";

    private static final String ATTR_KEY = "key";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_TIME = "time";
    private static final String ATTR_STATUS = "status";
    private static final String ATTR_MESSAGE = "message";

    public XmlReport() {
    }

    @Override
    public void createReport(Map<String, String> parameters, List<CmisTestGroup> groups, Writer writer)
            throws Exception {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        XMLStreamWriter xml = factory.createXMLStreamWriter(writer);

        // start doc
        xml.writeStartDocument();

        xml.writeStartElement(TAG_REPORT);

        if (parameters != null) {
            xml.writeStartElement(TAG_PARAMETERS);

            for (Map.Entry<String, String> p : (new TreeMap<String, String>(parameters)).entrySet()) {
                xml.writeStartElement(TAG_PARAMETER);
                xml.writeAttribute(ATTR_KEY, p.getKey());

                String value = p.getValue();
                if (SessionParameter.PASSWORD.endsWith(p.getKey())) {
                    value = "*****";
                }

                if (value != null) {
                    xml.writeAttribute(ATTR_VALUE, value);
                }
                xml.writeEndElement();
            }

            xml.writeEndElement();
        }

        if (groups != null) {
            for (CmisTestGroup group : groups) {
                printGroupResults(group, xml);
            }
        }

        xml.writeEndElement();

        // end document
        xml.writeEndDocument();
        xml.flush();
    }

    private void printGroupResults(CmisTestGroup group, XMLStreamWriter xml) throws Exception {
        if (!group.isEnabled()) {
            return;
        }

        xml.writeStartElement(TAG_GROUP);
        xml.writeAttribute(ATTR_NAME, group.getName());

        if (group.getTests() != null) {
            for (CmisTest test : group.getTests()) {
                printTestResults(test, xml);
            }
        }

        xml.writeEndElement();
    }

    private void printTestResults(CmisTest test, XMLStreamWriter xml) throws Exception {
        if (!test.isEnabled()) {
            return;
        }

        xml.writeStartElement(TAG_TEST);
        xml.writeAttribute(ATTR_NAME, test.getName());
        xml.writeAttribute(ATTR_TIME, String.valueOf(test.getTime()));

        if (test.getResults() != null) {
            for (CmisTestResult result : test.getResults()) {
                printResult(result, xml);
            }
        }

        xml.writeEndElement();
    }

    private void printResult(CmisTestResult result, XMLStreamWriter xml) throws Exception {
        xml.writeStartElement(TAG_RESULT);
        xml.writeAttribute(ATTR_STATUS, result.getStatus().toString());
        xml.writeAttribute(ATTR_MESSAGE, result.getMessage());

        for (CmisTestResult child : result.getChildren()) {
            printResult(child, xml);
        }

        xml.writeEndElement();
    }
}
