/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.servlet;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CallContextHandler;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl;
import org.apache.chemistry.opencmis.server.impl.browser.CmisBrowserBindingServlet;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;

/**
 * NemakiWare Browser Binding servlet with a minimal set of customisations.
 */
public class NemakiBrowserBindingServlet extends CmisBrowserBindingServlet {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(NemakiBrowserBindingServlet.class);

    private static final BrowserBindingSupport BROWSER_SUPPORT = new BrowserBindingSupport();

    @Override
    public void init() throws ServletException {
        super.init();
        log.info("NemakiBrowserBindingServlet initialized");
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (log.isDebugEnabled()) {
            log.debug("Browser binding request: " + request.getMethod() + " " + request.getRequestURI());
        }

        if (handleCustomRequest(request, response)) {
            return;
        }

        super.service(request, response);
    }

    private boolean handleCustomRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        if (handleTypeRequestByPath(request, response)) {
            return true;
        }

        return handleTypeSelector(request, response);
    }

    private boolean handleTypeSelector(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String selector = BROWSER_SUPPORT.getStringParameter(request, Constants.PARAM_SELECTOR);
        if (selector == null) {
            return false;
        }

        String[] pathFragments = HttpUtils.splitPath(request);
        if (pathFragments.length == 0) {
            return false;
        }

        String repositoryId = pathFragments[0];

        switch (selector) {
        case Constants.SELECTOR_TYPE_DEFINITION:
            handleTypeDefinitionSelector(request, response, repositoryId);
            return true;
        case Constants.SELECTOR_TYPE_CHILDREN:
            handleTypeChildrenSelector(request, response, repositoryId);
            return true;
        case Constants.SELECTOR_TYPE_DESCENDANTS:
            handleTypeDescendantsSelector(request, response, repositoryId);
            return true;
        default:
            return false;
        }
    }

    private boolean handleTypeRequestByPath(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String[] pathFragments = HttpUtils.splitPath(request);
        if (pathFragments.length < 3) {
            return false;
        }

        if (!"types".equals(pathFragments[1])) {
            return false;
        }

        String repositoryId = pathFragments[0];
        String typeId = pathFragments[2];
        if (typeId == null || typeId.isEmpty()) {
            return false;
        }

        checkCsrfToken(request, response, false, false);

        CallContext context = createContext(getServletContext(), request, response, null);
        CmisService service = null;
        try {
            service = getServiceFactory().getService(context);
            TypeDefinition typeDefinition = resolveTypeDefinition(service, repositoryId, typeId);
            if (typeDefinition == null) {
                throw new CmisObjectNotFoundException("Type not found: " + typeId);
            }

            DateTimeFormat format = BROWSER_SUPPORT.getDateTimeFormat(request);
            JSONObject jsonType = JSONConverter.convert(typeDefinition, format);
            BROWSER_SUPPORT.applyStatus(request, response, HttpServletResponse.SC_OK);
            BROWSER_SUPPORT.writeJson(jsonType, request, response);
        } catch (Exception ex) {
            log.error("Failed to process type request for path segment '" + typeId + "'", ex);
            handleException(context, ex, request, response);
        } finally {
            closeService(service);
        }

        return true;
    }

    private void handleTypeDefinitionSelector(HttpServletRequest request, HttpServletResponse response,
            String repositoryId) throws ServletException, IOException {
        checkCsrfToken(request, response, false, false);

        CallContext context = createContext(getServletContext(), request, response, null);
        CmisService service = null;
        try {
            service = getServiceFactory().getService(context);
            String typeId = BROWSER_SUPPORT.getStringParameter(request, Constants.PARAM_TYPE_ID);
            TypeDefinition typeDefinition = resolveTypeDefinition(service, repositoryId, typeId);
            if (typeDefinition == null) {
                throw new CmisObjectNotFoundException("Type not found: " + typeId);
            }

            JSONObject jsonType = JSONConverter.convert(typeDefinition, BROWSER_SUPPORT.getDateTimeFormat(request));
            BROWSER_SUPPORT.applyStatus(request, response, HttpServletResponse.SC_OK);
            BROWSER_SUPPORT.writeJson(jsonType, request, response);
        } catch (Exception ex) {
            log.error("Failed to resolve type definition", ex);
            handleException(context, ex, request, response);
        } finally {
            closeService(service);
        }
    }

    private void handleTypeChildrenSelector(HttpServletRequest request, HttpServletResponse response,
            String repositoryId) throws ServletException, IOException {
        checkCsrfToken(request, response, false, false);

        CallContext context = createContext(getServletContext(), request, response, null);
        CmisService service = null;
        try {
            service = getServiceFactory().getService(context);
            String typeId = BROWSER_SUPPORT.getStringParameter(request, Constants.PARAM_TYPE_ID);
            Boolean includePropertyDefinitions = BROWSER_SUPPORT.getBooleanObject(request,
                    Constants.PARAM_PROPERTY_DEFINITIONS);
            BigInteger maxItems = BROWSER_SUPPORT.getBigInteger(request, Constants.PARAM_MAX_ITEMS);
            BigInteger skipCount = BROWSER_SUPPORT.getBigInteger(request, Constants.PARAM_SKIP_COUNT);

            TypeDefinitionList list = service.getTypeChildren(repositoryId, typeId, includePropertyDefinitions,
                    maxItems, skipCount, null);
            JSONObject json = JSONConverter.convert(list, BROWSER_SUPPORT.getDateTimeFormat(request));
            BROWSER_SUPPORT.applyStatus(request, response, HttpServletResponse.SC_OK);
            BROWSER_SUPPORT.writeJson(json, request, response);
        } catch (Exception ex) {
            log.error("Failed to resolve type children", ex);
            handleException(context, ex, request, response);
        } finally {
            closeService(service);
        }
    }

    private void handleTypeDescendantsSelector(HttpServletRequest request, HttpServletResponse response,
            String repositoryId) throws ServletException, IOException {
        checkCsrfToken(request, response, false, false);

        CallContext context = createContext(getServletContext(), request, response, null);
        CmisService service = null;
        try {
            service = getServiceFactory().getService(context);
            String typeId = BROWSER_SUPPORT.getStringParameter(request, Constants.PARAM_TYPE_ID);
            BigInteger depth = BROWSER_SUPPORT.getBigInteger(request, Constants.PARAM_DEPTH);
            boolean includePropertyDefinitions = BROWSER_SUPPORT.getBoolean(request,
                    Constants.PARAM_PROPERTY_DEFINITIONS, false);

            List<TypeDefinitionContainer> descendants = service.getTypeDescendants(repositoryId, typeId, depth,
                    includePropertyDefinitions, null);
            JSONArray jsonDescendants = new JSONArray();
            if (descendants != null) {
                DateTimeFormat format = BROWSER_SUPPORT.getDateTimeFormat(request);
                for (TypeDefinitionContainer container : descendants) {
                    jsonDescendants.add(JSONConverter.convert(container, format));
                }
            }

            BROWSER_SUPPORT.applyStatus(request, response, HttpServletResponse.SC_OK);
            BROWSER_SUPPORT.writeJson(jsonDescendants, request, response);
        } catch (Exception ex) {
            log.error("Failed to resolve type descendants", ex);
            handleException(context, ex, request, response);
        } finally {
            closeService(service);
        }
    }

    private TypeDefinition resolveTypeDefinition(CmisService service, String repositoryId, String typeId) {
        TypeDefinition typeDefinition = null;

        TypeManager typeManager = locateTypeManager();
        if (typeManager != null) {
            typeDefinition = typeManager.getTypeDefinition(repositoryId, typeId);
        }

        if (typeDefinition == null) {
            typeDefinition = service.getTypeDefinition(repositoryId, typeId, null);
        }

        return typeDefinition;
    }

    private TypeManager locateTypeManager() {
        WebApplicationContext applicationContext = WebApplicationContextUtils
                .getWebApplicationContext(getServletContext());
        if (applicationContext == null) {
            return null;
        }

        try {
            return applicationContext.getBean("typeManager", TypeManager.class);
        } catch (BeansException ex) {
            if (log.isDebugEnabled()) {
                log.debug("TypeManager bean not available", ex);
            }
            return null;
        }
    }

    private void handleException(CallContext context, Exception ex, HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        if (ex instanceof IOException) {
            throw (IOException) ex;
        }
        if (ex instanceof ServletException) {
            throw (ServletException) ex;
        }

        Exception toReport = ex;
        if (!(ex instanceof CmisBaseException)) {
            toReport = new CmisRuntimeException(ex.getMessage(), ex);
        }

        if (toReport instanceof CmisUnauthorizedException) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"CMIS\", charset=\"UTF-8\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization Required");
        } else if (toReport instanceof CmisPermissionDeniedException) {
            if (context == null || context.getUsername() == null) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"CMIS\", charset=\"UTF-8\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization Required");
            } else {
                printError(context, toReport, request, response);
            }
        } else {
            printError(context, toReport, request, response);
        }
    }

    private void closeService(CmisService service) {
        if (service != null) {
            service.close();
        }
    }

    @Override
    protected CallContext createContext(ServletContext servletContext, HttpServletRequest request,
            HttpServletResponse response, TempStoreOutputStreamFactory streamFactory) {
        String[] pathFragments = HttpUtils.splitPath(request);
        String repositoryId = pathFragments.length > 0 ? pathFragments[0] : null;

        NemakiBrowserCallContext context = new NemakiBrowserCallContext(CallContext.BINDING_BROWSER, getCmisVersion(),
                repositoryId, servletContext, request, response, getServiceFactory(), streamFactory);

        context.setRange(request.getHeader("Range"));
        context.setAcceptLanguage(request.getHeader("Accept-Language"));

        CallContextHandler handler = getCallContextHandler();
        if (handler != null) {
            Map<String, String> callContextMap = handler.getCallContextMap(request);
            if (callContextMap != null) {
                for (Map.Entry<String, String> entry : callContextMap.entrySet()) {
                    context.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return context;
    }

    private static final class BrowserBindingSupport extends org.apache.chemistry.opencmis.server.impl.browser.AbstractBrowserServiceCall {

        DateTimeFormat getDateTimeFormat(HttpServletRequest request) {
            return getDateTimeFormatParameter(request);
        }

        String getStringParameter(HttpServletRequest request, String name) {
            return super.getStringParameter(request, name);
        }

        Boolean getBooleanObject(HttpServletRequest request, String name) {
            return super.getBooleanParameter(request, name);
        }

        boolean getBoolean(HttpServletRequest request, String name, boolean defaultValue) {
            return super.getBooleanParameter(request, name, defaultValue);
        }

        BigInteger getBigInteger(HttpServletRequest request, String name) {
            return super.getBigIntegerParameter(request, name);
        }

        void writeJson(Object json, HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (json instanceof JSONObject) {
                writeJSON((JSONObject) json, request, response);
            } else if (json instanceof JSONArray) {
                writeJSON((JSONArray) json, request, response);
            } else {
                throw new IllegalArgumentException("Unsupported JSON payload type: " + json.getClass());
            }
        }

        void applyStatus(HttpServletRequest request, HttpServletResponse response, int status) {
            setStatus(request, response, status);
        }
    }

    private static final class NemakiBrowserCallContext extends BrowserCallContextImpl {

        private static final long serialVersionUID = 1L;

        NemakiBrowserCallContext(String binding, CmisVersion cmisVersion, String repositoryId,
                ServletContext servletContext, HttpServletRequest request, HttpServletResponse response,
                CmisServiceFactory factory, TempStoreOutputStreamFactory streamFactory) {
            super(binding, cmisVersion, repositoryId, servletContext, request, response, factory, streamFactory);
        }

        @Override
        public void setCallDetails(CmisService service, String objectId, String[] pathFragments, String token) {
            if (objectId == null) {
                HttpServletRequest request = (HttpServletRequest) get(CallContext.HTTP_SERVLET_REQUEST);
                objectId = HttpUtils.getStringParameter(request, Constants.PARAM_FOLDER_ID);
                if (objectId == null) {
                    objectId = HttpUtils.getStringParameter(request, Constants.PARAM_OBJECT_ID);
                }
            }
            super.setCallDetails(service, objectId, pathFragments, token);
        }
    }
}
