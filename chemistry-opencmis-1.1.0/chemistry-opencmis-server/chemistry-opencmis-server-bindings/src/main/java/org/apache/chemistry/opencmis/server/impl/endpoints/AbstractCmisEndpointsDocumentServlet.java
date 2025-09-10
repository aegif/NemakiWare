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
package org.apache.chemistry.opencmis.server.impl.endpoints;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URL;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;
import org.apache.chemistry.opencmis.commons.impl.endpoints.CmisEndpointsDocumentHelper;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;

/**
 * Serves the CMIS Endpoints Document.
 */
public abstract class AbstractCmisEndpointsDocumentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        CmisEndpointsDocument doc = getCmisEndpointsDocument(req, resp);
        if (doc == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "CMIS Endpoints Document is not available!");
            return;
        }

        resp.setContentType("application/json; charset=UTF-8");

        PrintWriter pw = resp.getWriter();
        CmisEndpointsDocumentHelper.write(doc, pw);
    }

    /**
     * Returns a CMIS Endpoints Document.
     */
    public abstract CmisEndpointsDocument getCmisEndpointsDocument(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException;

    public CmisEndpointsDocument readCmisEndpointsDocument(URL url) throws IOException, JSONParseException {
        return CmisEndpointsDocumentHelper.read(url);
    }

    public CmisEndpointsDocument readCmisEndpointsDocument(File file) throws IOException, JSONParseException {
        return CmisEndpointsDocumentHelper.read(file);
    }

    public CmisEndpointsDocument readCmisEndpointsDocument(InputStream in) throws IOException, JSONParseException {
        return CmisEndpointsDocumentHelper.read(in);
    }

    public CmisEndpointsDocument readCmisEndpointsDocument(Reader in) throws IOException, JSONParseException {
        return CmisEndpointsDocumentHelper.read(in);
    }

    public CmisEndpointsDocument readCmisEndpointsDocument(String in) throws JSONParseException {
        return CmisEndpointsDocumentHelper.read(in);
    }
}
