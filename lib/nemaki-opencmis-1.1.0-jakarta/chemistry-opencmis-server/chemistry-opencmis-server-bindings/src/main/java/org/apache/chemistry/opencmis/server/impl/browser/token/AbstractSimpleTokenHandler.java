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
package org.apache.chemistry.opencmis.server.impl.browser.token;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.URL;
import java.util.Locale;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.impl.json.JSONStreamAware;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CmisRepositoryContextListener;
import org.apache.chemistry.opencmis.server.impl.browser.AbstractBrowserServiceCall;

public abstract class AbstractSimpleTokenHandler implements TokenHandler, Serializable {

    private static final long serialVersionUID = 1L;

    // return 10 tokens per batch
    private static final int TOKEN_BATCH_SIZE = 10;

    private static final String ATTR_PREFIX = "org.apache.chemistry.opencmis.";

    private static final String JSP_PATH = "/WEB-INF/token/";
    private static final String JSP_SCRIPT = "cmis.js.jsp";
    private static final String JSP_IFRAME = "repository.jsp";
    private static final String JSP_LOGIN = "login.jsp";

    private static final String PARAM_LOGIN = "login";

    private static final String LOGIN_SCRIPT = "script";
    private static final String LOGIN_CONTROLLER = "controller";
    private static final String LOGIN_LOGIN = "login";
    private static final String LOGIN_LOGOUT = "logout";
    private static final String LOGIN_TOKEN = "token";

    private static final UrlServiceCall URL_SERVICE_CALL = new UrlServiceCall();

    @Override
    public void service(ServletContext servletContext, HttpServletRequest request, HttpServletResponse response) {

        String repositoryId = null; // TODO: determine repository id
        String login = request.getParameter(PARAM_LOGIN);
        try {
            if (LOGIN_TOKEN.equals(login)) {
                sendTokens(servletContext, request, response);
            } else if (LOGIN_SCRIPT.equals(login)) {
                sendJavaScript(servletContext, request, response, repositoryId);
            } else if (LOGIN_CONTROLLER.equals(login)) {
                sendControllerContent(servletContext, request, response, repositoryId);
            } else if (LOGIN_LOGIN.equals(login)) {
                login(servletContext, request, response, repositoryId);
            } else if (LOGIN_LOGOUT.equals(login)) {
                logout(servletContext, request, response);
            } else {
                throw new CmisObjectNotFoundException();
            }
        } catch (Exception e) {
            if (e instanceof CmisBaseException) {
                throw (CmisBaseException) e;
            } else {
                throw new CmisRuntimeException("Internal Error!", e);
            }
        }
    }

    /**
     * Sends a batch of new tokens.
     */
    protected void sendTokens(ServletContext servletContext, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        JSONArray result = new JSONArray();

        if (!SimpleTokenHandlerSessionHelper.checkApplicationKey(request)) {
            // PARANOIA: remove the application key if the presented key is
            // wrong, don't allow a second attempt
            String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(SimpleTokenHandlerSessionHelper
                    .getKey(request));
            if (appId != null) {
                SimpleTokenHandlerSessionHelper.removeApplicationKey(request, appId);
            }
        } else {
            // generate a batch of tokens
            String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(SimpleTokenHandlerSessionHelper
                    .getKey(request));

            for (int i = 0; i < TOKEN_BATCH_SIZE; i++) {
                // create token
                String token = SimpleTokenHandlerSessionHelper.generateKey(appId);

                // put token into session and it to the response
                SimpleTokenHandlerSessionHelper.addToken(request, token);
                result.add(token);
            }
        }

        printJSON(response, result);
    }

    /**
     * Sends the JavaScript file for web clients.
     */
    protected void sendJavaScript(ServletContext servletContext, HttpServletRequest request,
            HttpServletResponse response, String repositoryId) throws IOException {

        UrlBuilder baseUrl = URL_SERVICE_CALL.compileBaseUrl(request, repositoryId);
        URL url = new URL(baseUrl.toString());

        request.setAttribute(ATTR_PREFIX + "domain", encodeJavaScriptString(url.getProtocol() + "://" + url.getHost()
                + (url.getPort() > -1 ? ":" + url.getPort() : "")));
        request.setAttribute(ATTR_PREFIX + "serviceUrl", encodeJavaScriptString(baseUrl.toString()));
        request.setAttribute(ATTR_PREFIX + "iframeUrl",
                encodeJavaScriptString(baseUrl.addParameter(PARAM_LOGIN, LOGIN_CONTROLLER).toString()));

        response.setContentType("application/json; charset=UTF-8");

        RequestDispatcher dispatcher = servletContext.getRequestDispatcher(JSP_PATH + JSP_SCRIPT);
        try {
            dispatcher.include(request, response);
        } catch (Exception e) {
            throw new CmisRuntimeException("Internal error!", e);
        }
    }

    /**
     * Sends the IFrame content.
     */
    protected void sendControllerContent(ServletContext servletContext, HttpServletRequest request,
            HttpServletResponse response, String repositoryId) throws IOException {

        response.setContentType("text/html; charset=UTF-8");

        request.setAttribute(
                ATTR_PREFIX + "loginUrl",
                encodeJavaScriptString(URL_SERVICE_CALL.compileBaseUrl(request, repositoryId)
                        .addParameter(PARAM_LOGIN, "").toString()));

        RequestDispatcher dispatcher = servletContext.getRequestDispatcher(JSP_PATH + JSP_IFRAME);
        try {
            dispatcher.include(request, response);
        } catch (Exception e) {
            throw new CmisRuntimeException("Internal error!", e);
        }
    }

    /**
     * Handles logins.
     */
    protected void login(ServletContext servletContext, HttpServletRequest request, HttpServletResponse response,
            String repositoryId) throws IOException {
        if ("GET".equals(request.getMethod())) {
            showLoginForm(servletContext, request, response, null);
        } else if ("POST".equals(request.getMethod())) {
            authenticate(servletContext, request, response, repositoryId);
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    protected void showLoginForm(ServletContext servletContext, HttpServletRequest request,
            HttpServletResponse response, String errorMessage) throws IOException {

        String formKey = SimpleTokenHandlerSessionHelper.getKey(request);
        String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(formKey);
        URL appUrl = SimpleTokenHandlerSessionHelper.getApplicationURL(request, appId);

        request.setAttribute(ATTR_PREFIX + "formkey", formKey);
        request.setAttribute(ATTR_PREFIX + "error", errorMessage);
        request.setAttribute(ATTR_PREFIX + "appurl", encodeHTMLString(appUrl.toString()));

        response.setContentType("text/html; charset=UTF-8");

        RequestDispatcher dispatcher = servletContext.getRequestDispatcher(JSP_PATH + JSP_LOGIN);
        try {
            dispatcher.include(request, response);
        } catch (Exception e) {
            throw new CmisRuntimeException("Internal error!", e);
        }
    }

    protected void authenticate(ServletContext servletContext, HttpServletRequest request,
            HttpServletResponse response, String repositoryId) throws IOException {
        if (SimpleTokenHandlerSessionHelper.checkFormKey(request)) {
            // login form returns

            String trustapp = request.getParameter(SimpleTokenHandlerSessionHelper.PARAM_TRUSTAPP);
            String user = request.getParameter(SimpleTokenHandlerSessionHelper.PARAM_USER);
            String password = request.getParameter(SimpleTokenHandlerSessionHelper.PARAM_PASSWORD);

            if (!"1".equals(trustapp)) {
                String error = "Please confirm that you trust the application!";
                showLoginForm(servletContext, request, response, error);
                return;
            }

            if (!authenticate(servletContext, request, response, user, password)) {
                String error = "Invalid credentials!";
                showLoginForm(servletContext, request, response, error);
                return;
            }

            // authentication successful -> create application key and
            // forward to application
            String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(SimpleTokenHandlerSessionHelper
                    .getKey(request));
            String appKey = SimpleTokenHandlerSessionHelper.generateKey(appId);
            SimpleTokenHandlerSessionHelper.setApplicationKey(request, appKey);
            SimpleTokenHandlerSessionHelper.setUser(request, appId, user);
            SimpleTokenHandlerSessionHelper.removeFormKey(request, appId);

            URL appURL = SimpleTokenHandlerSessionHelper.getApplicationURL(request, appId);

            response.sendRedirect(response.encodeRedirectURL(appURL.toString()));

            return;
        }

        // check URL
        String url = request.getParameter(SimpleTokenHandlerSessionHelper.PARAM_URL);

        if (url == null || url.trim().length() < 8 || !url.toLowerCase(Locale.ENGLISH).startsWith("http")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        URL appURL = null;
        try {
            appURL = new URL(url);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // check login key
        String loginKey = request.getParameter(SimpleTokenHandlerSessionHelper.PARAM_KEY);

        if (loginKey == null || loginKey.trim().length() == 0) {
            // no login key set -> generate one and return it

            String appId = SimpleTokenHandlerSessionHelper.generateAppId();
            loginKey = SimpleTokenHandlerSessionHelper.generateKey(appId);
            String formKey = SimpleTokenHandlerSessionHelper.generateKey(appId);

            SimpleTokenHandlerSessionHelper.setLoginKey(request, loginKey, formKey, appURL);

            String formURL = encodeJavaScriptString(URL_SERVICE_CALL.compileBaseUrl(request, repositoryId)
                    .addParameter(PARAM_LOGIN, LOGIN_LOGIN)
                    .addParameter(SimpleTokenHandlerSessionHelper.PARAM_KEY, formKey).toString());

            JSONObject result = new JSONObject();
            result.put("ok", 0);
            result.put("key", loginKey);
            result.put("url", formURL);

            printJSON(response, result);

            return;
        }

        String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(SimpleTokenHandlerSessionHelper
                .getKey(request));
        String appKey = SimpleTokenHandlerSessionHelper.getApplicationKey(request, appId);

        // check if login key fits
        if (appKey == null || !SimpleTokenHandlerSessionHelper.checkLoginKey(request)) {
            // PARANOIA: remove keys, don't allow a second attempt
            SimpleTokenHandlerSessionHelper.removeLoginKey(request, appId);
            SimpleTokenHandlerSessionHelper.removeApplicationKey(request, appId);
            response.sendError(400);
            return;
        }

        // remove login key - it should not be reused
        SimpleTokenHandlerSessionHelper.removeLoginKey(request, appId);

        // return application key
        JSONObject result = new JSONObject();
        result.put("ok", 1);
        result.put("key", appKey);

        printJSON(response, result);
    }

    protected abstract boolean authenticate(final ServletContext servletContext, final HttpServletRequest request,
            final HttpServletResponse response, String user, String password);

    /**
     * Handles logouts.
     */
    protected void logout(final ServletContext servletContext, final HttpServletRequest request,
            final HttpServletResponse response) throws IOException {
        String appId = SimpleTokenHandlerSessionHelper.getApplicationIdFromKey(SimpleTokenHandlerSessionHelper
                .getKey(request));
        if (appId != null) {
            SimpleTokenHandlerSessionHelper.removeApplicationKey(request, appId);
        }

        JSONObject result = new JSONObject();
        result.put("ok", 1);

        printJSON(response, result);
    }

    protected CmisServiceFactory getCmisServiceFactory(final ServletContext servletContext) {
        CmisServiceFactory factory = CmisRepositoryContextListener.getServiceFactory(servletContext);

        if (factory == null) {
            throw new CmisRuntimeException("Service factory not available! Configuration problem?");
        }

        return factory;
    }

    protected void printJSON(final HttpServletResponse response, final JSONStreamAware json) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=utf-8");
        response.addHeader("Cache-Control", "private, max-age=0");

        PrintWriter pw = response.getWriter();
        json.writeJSONString(pw);
        pw.flush();
    }

    protected String encodeJavaScriptString(final String s) {
        if (s == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(s.length() + 16);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("\\'");
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    protected String encodeHTMLString(final String s) {
        if (s == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(s.length() + 64);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '\t' || c == '\n' || c == '\r' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == ' ' || c == '.' || c == ',' || c == '-' || c == '_') {
                sb.append(c);
            } else if (c <= 0x1f || (c >= 0x7f && c <= 0x9f)) {
                sb.append(' ');
            } else if (c == '&') {
                sb.append("&amp;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append("&#x" + Integer.toHexString(c) + ";");
            }
        }

        return sb.toString();
    }

    static class UrlServiceCall extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            // no implementation

        }
    }
}
