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

import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_FILTER;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

/**
 * Policy Service operations.
 */
public class PolicyService {

    /**
     * getAppliedPolicies.
     */
    public static class GetAppliedPolicies extends AbstractBrowserServiceCall {
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
            boolean succinct = getBooleanParameter(request, Constants.PARAM_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<ObjectData> policies = service.getAppliedPolicies(repositoryId, objectId, filter, null);

            if (stopAfterService(service)) {
                return;
            }

            JSONArray jsonPolicies = new JSONArray();
            if (policies != null) {
                TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
                for (ObjectData policy : policies) {
                    jsonPolicies.add(JSONConverter.convert(policy, typeCache, JSONConverter.PropertyMode.OBJECT,
                            succinct, dateTimeFormat));
                }
            }

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonPolicies, request, response);
        }
    }

    /**
     * applyPolicy.
     */
    public static class ApplyPolicy extends AbstractBrowserServiceCall {
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
            boolean succinct = getBooleanParameter(request, Constants.CONTROL_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            ControlParser cp = new ControlParser(request);
            String policyId = getPolicyId(cp);

            if (stopBeforeService(service)) {
                return;
            }

            service.applyPolicy(repositoryId, policyId, objectId, null);

            if (stopAfterService(service)) {
                return;
            }

            ObjectData object = getSimpleObject(service, repositoryId, objectId);
            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            // return object
            response.setStatus(HttpServletResponse.SC_OK);

            TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
            JSONObject jsonObject = JSONConverter.convert(object, typeCache, JSONConverter.PropertyMode.OBJECT,
                    succinct, dateTimeFormat);

            writeJSON(jsonObject, request, response);
        }
    }

    /**
     * removePolicy.
     */
    public static class RemovePolicy extends AbstractBrowserServiceCall {
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
            boolean succinct = getBooleanParameter(request, Constants.CONTROL_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            ControlParser cp = new ControlParser(request);
            String policyId = getPolicyId(cp);

            if (stopBeforeService(service)) {
                return;
            }

            service.removePolicy(repositoryId, policyId, objectId, null);

            if (stopAfterService(service)) {
                return;
            }

            ObjectData object = getSimpleObject(service, repositoryId, objectId);
            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            // return object
            response.setStatus(HttpServletResponse.SC_OK);

            TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
            JSONObject jsonObject = JSONConverter.convert(object, typeCache, JSONConverter.PropertyMode.OBJECT,
                    succinct, dateTimeFormat);

            writeJSON(jsonObject, request, response);
        }
    }
}
