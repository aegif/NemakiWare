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

import java.io.InputStream;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple CMIS Endpoints Document servlet.
 * 
 * It reads an endpoints document from a file replace some strings.
 */
public class SimpleCmisEndpointsDocumentServlet extends AbstractCmisEndpointsDocumentServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(SimpleCmisEndpointsDocumentServlet.class);

    private static final String PARAM_ENDPOINT_TEMPLATE = "template";

    private String endpointsDocument;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String template = config.getInitParameter(PARAM_ENDPOINT_TEMPLATE);
        if (template == null) {
            LOG.error("CMIS Endpoints Document template provided!");
            return;
        }

        // load template from file
        InputStream stream = null;
        try {
            stream = config.getServletContext().getResourceAsStream(template);
            if (stream != null) {
                endpointsDocument = IOUtils.readAllLines(stream);
            }
        } catch (Exception e) {
            LOG.error("Could not read CMIS Endpoints Document template from {}!", template, e);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    @Override
    public CmisEndpointsDocument getCmisEndpointsDocument(HttpServletRequest req, HttpServletResponse resp) {
        if (endpointsDocument == null) {
            // we don't have a template
            return null;
        }

        UrlBuilder url = new UrlBuilder(req.getScheme(), req.getServerName(), req.getServerPort(), null);
        url.addPath(req.getContextPath());

        try {
            return readCmisEndpointsDocument(endpointsDocument.replaceAll("\\{webapp\\}", url.toString()));
        } catch (JSONParseException e) {
            LOG.error("Invalid JSON!", e);
            return null;
        }
    }
}
