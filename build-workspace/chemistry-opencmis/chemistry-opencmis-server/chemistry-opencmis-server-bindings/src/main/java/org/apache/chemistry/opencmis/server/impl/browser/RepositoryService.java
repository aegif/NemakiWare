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
package org.apache.chemistry.opencmis.server.impl.browser;

import static org.apache.chemistry.opencmis.commons.impl.Constants.CONTROL_TYPE;
import static org.apache.chemistry.opencmis.commons.impl.Constants.CONTROL_TYPE_ID;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_DEPTH;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_MAX_ITEMS;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_PROPERTY_DEFINITIONS;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_SKIP_COUNT;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_TOKEN;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_TYPE_ID;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.impl.json.JSONValue;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * Repository Service operations.
 */
public class RepositoryService {

    /**
     * getRepositories.
     */
    public static class GetRepositories extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert request != null;
            assert response != null;

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<RepositoryInfo> infoDataList = service.getRepositoryInfos(null);

            if (stopAfterService(service)) {
                return;
            }

            JSONObject result = new JSONObject();
            for (RepositoryInfo ri : infoDataList) {
                String repositoryUrl = compileRepositoryUrl(request, ri.getId()).toString();
                String rootUrl = compileRootUrl(request, ri.getId()).toString();

                result.put(ri.getId(), JSONConverter.convert(ri, repositoryUrl, rootUrl, true));
            }

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(result, request, response);
        }
    }

    /**
     * getRepositoryInfo.
     */
    public static class GetRepositoryInfo extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            RepositoryInfo ri = service.getRepositoryInfo(repositoryId, null);

            if (stopAfterService(service)) {
                return;
            }

            String repositoryUrl = compileRepositoryUrl(request, ri.getId()).toString();
            String rootUrl = compileRootUrl(request, ri.getId()).toString();

            JSONObject result = new JSONObject();
            result.put(ri.getId(), JSONConverter.convert(ri, repositoryUrl, rootUrl, true));

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(result, request, response);
        }
    }

    /**
     * getLastResult.
     */
    public static class GetLastResult extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            String token = getStringParameter(request, PARAM_TOKEN);
            String cookieName = getCookieName(token);
            String cookieValue = null;

            if (request.getCookies() != null) {
                for (Cookie cookie : request.getCookies()) {
                    if (cookieName.equals(cookie.getName())) {
                        cookieValue = IOUtils.decodeURL(cookie.getValue());
                        break;
                    }
                }
            }

            try {
                if (cookieValue == null) {
                    cookieValue = createCookieValue(0, null, CmisInvalidArgumentException.EXCEPTION_NAME,
                            "Unknown transaction!");
                } else {
                    if (!(JSONValue.parse(cookieValue) instanceof JSONObject)) {
                        cookieValue = createCookieValue(0, null, CmisInvalidArgumentException.EXCEPTION_NAME,
                                "Invalid cookie value!");
                    }
                }
            } catch (Exception pe) {
                cookieValue = createCookieValue(0, null, CmisRuntimeException.EXCEPTION_NAME, "Cookie pasring error!");
            }

            deleteCookie(request, response, repositoryId, token);

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON((JSONObject) JSONValue.parse(cookieValue), request, response);
        }
    }

    /**
     * getTypeChildren.
     */
    public static class GetTypeChildren extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String typeId = getStringParameter(request, PARAM_TYPE_ID);
            boolean includePropertyDefinitions = getBooleanParameter(request, PARAM_PROPERTY_DEFINITIONS, false);
            BigInteger maxItems = getBigIntegerParameter(request, PARAM_MAX_ITEMS);
            BigInteger skipCount = getBigIntegerParameter(request, PARAM_SKIP_COUNT);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            TypeDefinitionList typeList = service.getTypeChildren(repositoryId, typeId, includePropertyDefinitions,
                    maxItems, skipCount, null);

            if (stopAfterService(service)) {
                return;
            }

            JSONObject jsonTypeList = JSONConverter.convert(typeList, dateTimeFormat);

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonTypeList, request, response);
        }
    }

    public static class GetTypeDescendants extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String typeId = getStringParameter(request, PARAM_TYPE_ID);
            BigInteger depth = getBigIntegerParameter(request, PARAM_DEPTH);
            boolean includePropertyDefinitions = getBooleanParameter(request, PARAM_PROPERTY_DEFINITIONS, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<TypeDefinitionContainer> typeTree = service.getTypeDescendants(repositoryId, typeId, depth,
                    includePropertyDefinitions, null);

            if (stopAfterService(service)) {
                return;
            }

            if (typeTree == null) {
                throw new CmisRuntimeException("Type tree is null!");
            }

            JSONArray jsonTypeTree = new JSONArray();
            for (TypeDefinitionContainer container : typeTree) {
                jsonTypeTree.add(JSONConverter.convert(container, dateTimeFormat));
            }

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonTypeTree, request, response);
        }
    }

    /**
     * getTypeDefinition.
     */
    public static class GetTypeDefinition extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String typeId = getStringParameter(request, PARAM_TYPE_ID);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            TypeDefinition type = service.getTypeDefinition(repositoryId, typeId, null);

            if (stopAfterService(service)) {
                return;
            }

            JSONObject jsonType = JSONConverter.convert(type, dateTimeFormat);

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonType, request, response);
        }
    }

    /**
     * createType.
     */
    public static class CreateType extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String typeStr = getStringParameter(request, CONTROL_TYPE);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            if (typeStr == null) {
                throw new CmisInvalidArgumentException("Type definition missing!");
            }

            // convert type definition
            JSONParser parser = new JSONParser();
            Object typeJson = parser.parse(typeStr);
            if (!(typeJson instanceof Map)) {
                throw new CmisInvalidArgumentException("Invalid type definition!");
            }

            @SuppressWarnings("unchecked")
            TypeDefinition typeIn = JSONConverter.convertTypeDefinition((Map<String, Object>) typeJson);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            TypeDefinition typeOut = service.createType(repositoryId, typeIn, null);

            if (stopAfterService(service)) {
                return;
            }

            JSONObject jsonType = JSONConverter.convert(typeOut, dateTimeFormat);

            // set headers
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setHeader("Location", compileTypeLocationUrl(request, repositoryId, typeOut.getId()));

            writeJSON(jsonType, request, response);
        }
    }

    /**
     * updateType.
     */
    public static class UpdateType extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String typeStr = getStringParameter(request, CONTROL_TYPE);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            if (typeStr == null) {
                throw new CmisInvalidArgumentException("Type definition missing!");
            }

            // convert type definition
            JSONParser parser = new JSONParser();
            Object typeJson = parser.parse(typeStr);
            if (!(typeJson instanceof Map)) {
                throw new CmisInvalidArgumentException("Invalid type definition!");
            }

            @SuppressWarnings("unchecked")
            TypeDefinition typeIn = JSONConverter.convertTypeDefinition((Map<String, Object>) typeJson);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            TypeDefinition typeOut = service.updateType(repositoryId, typeIn, null);

            if (stopAfterService(service)) {
                return;
            }

            JSONObject jsonType = JSONConverter.convert(typeOut, dateTimeFormat);

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonType, request, response);
        }
    }

    /**
     * deleteType.
     */
    public static class DeleteType extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String typeId = getStringParameter(request, CONTROL_TYPE_ID);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            service.deleteType(repositoryId, typeId, null);

            if (stopAfterService(service)) {
                return;
            }

            response.setStatus(HttpServletResponse.SC_OK);
            writeEmpty(request, response);
        }
    }
}
