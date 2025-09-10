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
package org.apache.chemistry.opencmis.server.shared;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class ExceptionHelper {

    /**
     * System property to enable stack traces in CMIS exceptions.
     */
    public static final String ENABLE_STACK_TRACE_PROPERTY = "org.apache.chemistry.opencmis.stacktrace.enable";

    private static final boolean SEND_STACK_TRACE;

    static {
        SEND_STACK_TRACE = Boolean.parseBoolean(System.getProperty(ENABLE_STACK_TRACE_PROPERTY, "false"));
    }

    private ExceptionHelper() {
    }

    /**
     * Returns the stack trace as string.
     */
    public static String getStacktraceAsString(Throwable t) {
        if (!SEND_STACK_TRACE || t == null) {
            return null;
        }

        StringWriter sw = new StringWriter(512);
        PrintWriter pw = new PrintWriter(sw);

        t.printStackTrace(pw);

        return sw.toString();
    }

    /**
     * Returns the stack trace as DOM node.
     */
    public static Node getStacktraceAsNode(Throwable t) {
        try {
            String st = getStacktraceAsString(t);
            if (st != null) {
                Document doc = XMLUtils.newDomDocument();

                Element node = doc.createElementNS("http://chemistry.apache.org/opencmis/exception", "stacktrace");
                doc.appendChild(node);

                node.appendChild(doc.createTextNode(st));

                return node;
            }
        } catch (Exception e) {
            assert false;
        }

        return null;
    }
}
