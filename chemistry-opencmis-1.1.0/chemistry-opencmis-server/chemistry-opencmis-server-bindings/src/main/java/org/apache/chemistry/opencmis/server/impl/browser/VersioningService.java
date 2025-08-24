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

import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_ALLOWABLE_ACTIONS;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_CHECKIN_COMMENT;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_FILTER;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_MAJOR;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_TOKEN;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Versioning Service operations.
 */
public class VersioningService {

    /**
     * checkOut.
     */
    public static class CheckOut extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = ((BrowserCallContextImpl) context).getObjectId();
            String token = getStringParameter(request, PARAM_TOKEN);
            boolean succinct = getBooleanParameter(request, Constants.CONTROL_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            Holder<String> checkOutId = new Holder<String>(objectId);
            service.checkOut(repositoryId, checkOutId, null, null);

            if (stopAfterService(service)) {
                return;
            }

            ObjectData object = getSimpleObject(service, repositoryId, checkOutId.getValue());
            if (object == null) {
                throw new CmisRuntimeException("PWC is null!");
            }

            // return object
            TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
            JSONObject jsonObject = JSONConverter.convert(object, typeCache, JSONConverter.PropertyMode.OBJECT,
                    succinct, dateTimeFormat);

            // set headers
            setStatus(request, response, HttpServletResponse.SC_CREATED);
            response.setHeader("Location", compileObjectLocationUrl(request, repositoryId, object.getId()));

            setCookie(request, response, repositoryId, token,
                    createCookieValue(HttpServletResponse.SC_CREATED, object.getId(), null, null));

            writeJSON(jsonObject, request, response);
        }
    }

    /**
     * checkOut.
     */
    public static class CancelCheckOut extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = ((BrowserCallContextImpl) context).getObjectId();

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            service.cancelCheckOut(repositoryId, objectId, null);

            if (stopAfterService(service)) {
                return;
            }

            response.setStatus(HttpServletResponse.SC_OK);
            writeEmpty(request, response);
        }
    }

    /**
     * checkIn.
     */
    public static class CheckIn extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = ((BrowserCallContextImpl) context).getObjectId();
            String typeId = ((BrowserCallContextImpl) context).getTypeId();
            Boolean major = getBooleanParameter(request, PARAM_MAJOR);
            String checkinComment = getStringParameter(request, PARAM_CHECKIN_COMMENT);
            String token = getStringParameter(request, PARAM_TOKEN);
            boolean succinct = getBooleanParameter(request, Constants.CONTROL_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            ControlParser cp = new ControlParser(request);
            TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
            Holder<String> objectIdHolder = new Holder<String>(objectId);
            ContentStream contentStream = createContentStream(request);

            if (stopBeforeService(service)) {
                return;
            }

            service.checkIn(repositoryId, objectIdHolder, major,
                    createUpdateProperties(cp, typeId, null, Collections.singletonList(objectId), typeCache),
                    contentStream, checkinComment, createPolicies(cp), createAddAcl(cp), createRemoveAcl(cp), null);

            if (stopAfterService(service)) {
                return;
            }

            String newObjectId = (objectIdHolder.getValue() == null ? objectId : objectIdHolder.getValue());

            ObjectData object = getSimpleObject(service, repositoryId, newObjectId);
            if (object == null) {
                throw new CmisRuntimeException("New version is null!");
            }

            // return object
            JSONObject jsonObject = JSONConverter.convert(object, typeCache, JSONConverter.PropertyMode.OBJECT,
                    succinct, dateTimeFormat);

            // set headers
            setStatus(request, response, HttpServletResponse.SC_CREATED);
            response.setHeader("Location", compileObjectLocationUrl(request, repositoryId, object.getId()));

            setCookie(request, response, repositoryId, token,
                    createCookieValue(HttpServletResponse.SC_CREATED, object.getId(), null, null));

            writeJSON(jsonObject, request, response);
        }
    }

    /**
     * getAllVersions.
     */
    public static class GetAllVersions extends AbstractBrowserServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = ((BrowserCallContextImpl) context).getObjectId();
            String filter = getStringParameter(request, PARAM_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, PARAM_ALLOWABLE_ACTIONS);
            boolean succinct = getBooleanParameter(request, Constants.PARAM_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<ObjectData> versions = service.getAllVersions(repositoryId, objectId, null, filter,
                    includeAllowableActions, null);

            if (stopAfterService(service)) {
                return;
            }

            if (versions == null) {
                throw new CmisRuntimeException("Versions are null!");
            }

            TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
            JSONArray jsonVersions = new JSONArray();
            for (ObjectData version : versions) {
                jsonVersions.add(JSONConverter.convert(version, typeCache, JSONConverter.PropertyMode.OBJECT, succinct,
                        dateTimeFormat));
            }

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonVersions, request, response);
        }
    }
}
