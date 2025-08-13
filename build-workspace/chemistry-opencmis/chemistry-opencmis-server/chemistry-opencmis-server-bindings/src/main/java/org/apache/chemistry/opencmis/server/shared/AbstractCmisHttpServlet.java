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

import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.impl.CmisRepositoryContextListener;
import org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl;

public abstract class AbstractCmisHttpServlet extends HttpServlet {

    public static final String PARAM_CALL_CONTEXT_HANDLER = "callContextHandler";
    public static final String PARAM_CMIS_VERSION = "cmisVersion";

    private static final long serialVersionUID = 1L;

    private CmisServiceFactory factory;
    private String binding;
    private CmisVersion cmisVersion;
    private CallContextHandler callContextHandler;
    private CsrfManager csrfManager;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        // initialize the call context handler
        callContextHandler = loadCallContextHandler(config);

        // get service factory
        factory = CmisRepositoryContextListener.getServiceFactory(config.getServletContext());

        if (factory == null) {
            throw new ServletException("Service factory not available! Configuration problem?");
        }

        // set up CSRF manager
        csrfManager = new CsrfManager(config);
    }

    /**
     * Loads a {@code CallContextHandler} if it is configured in for this
     * servlet.
     */
    public static CallContextHandler loadCallContextHandler(ServletConfig config) throws ServletException {
        String callContextHandlerClass = config.getInitParameter(PARAM_CALL_CONTEXT_HANDLER);
        if (callContextHandlerClass != null) {
            try {
                return (CallContextHandler) ClassLoaderUtil.loadClass(callContextHandlerClass).getDeclaredConstructor()
                        .newInstance();
            } catch (Exception e) {
                throw new ServletException("Could not load call context handler: " + e, e);
            }
        }

        return null;
    }

    /**
     * Sets the binding.
     */
    protected void setBinding(String binding) {
        this.binding = binding;
    }

    /**
     * Returns the CMIS version configured for this servlet.
     */
    protected CmisVersion getCmisVersion() {
        return cmisVersion;
    }

    protected void setCmisVersion(CmisVersion cmisVersion) {
        this.cmisVersion = cmisVersion;
    }

    /**
     * Returns the {@link CmisServiceFactory}.
     */
    protected CmisServiceFactory getServiceFactory() {
        return factory;
    }

    /**
     * Return the {@link CallContextHandler}
     */
    protected CallContextHandler getCallContextHandler() {
        return callContextHandler;
    }

    /**
     * Checks the CSRF if configured. Throws an
     * {@link CmisPermissionDeniedException} if something is wrong.
     */
    protected void checkCsrfToken(HttpServletRequest req, HttpServletResponse resp, boolean isRepositoryInfoRequest,
            boolean isContentRequest) {
        csrfManager.check(req, resp, isRepositoryInfoRequest, isContentRequest);
    }

    /**
     * Creates a {@link CallContext} object from a servlet request.
     */
    protected CallContext createContext(ServletContext servletContext, HttpServletRequest request,
            HttpServletResponse response, TempStoreOutputStreamFactory streamFactory) {
        String[] pathFragments = HttpUtils.splitPath(request);

        String repositoryId = null;
        if (pathFragments.length > 0) {
            repositoryId = pathFragments[0];
        }

        if (repositoryId == null && CallContext.BINDING_ATOMPUB.equals(binding)) {
            // it's a getRepositories or getRepositoryInfo call
            // getRepositoryInfo has the repository ID in the query parameters
            repositoryId = HttpUtils.getStringParameter(request, Constants.PARAM_REPOSITORY_ID);
        }

        CallContextImpl context = null;

        if (CallContext.BINDING_BROWSER.equals(binding)) {
            context = new BrowserCallContextImpl(binding, cmisVersion, repositoryId, servletContext, request, response,
                    factory, streamFactory);
        } else {
            context = new CallContextImpl(binding, cmisVersion, repositoryId, servletContext, request, response,
                    factory, streamFactory);
        }

        // decode range
        context.setRange(request.getHeader("Range"));

        // get locale
        context.setAcceptLanguage(request.getHeader("Accept-Language"));

        // call call context handler
        if (callContextHandler != null) {
            Map<String, String> callContextMap = callContextHandler.getCallContextMap(request);
            if (callContextMap != null) {
                for (Map.Entry<String, String> e : callContextMap.entrySet()) {
                    context.put(e.getKey(), e.getValue());
                }
            }
        }

        return context;
    }

    protected static String createLogMessage(Throwable t, HttpServletRequest request) {
        StringBuilder sb = new StringBuilder(256);

        sb.append(t.getMessage());

        sb.append(" (");
        sb.append(request.getMethod());
        sb.append(' ');
        sb.append(request.getRequestURL().toString());
        if (request.getQueryString() != null) {
            sb.append('?');
            sb.append(request.getQueryString());
        }
        sb.append(')');

        return sb.toString();
    }
}
