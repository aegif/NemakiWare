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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * ACL Service operations.
 */
public class AclService {

    /**
     * getACL.
     */
    public static class GetACL extends AbstractBrowserServiceCall {
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
            Boolean onlyBasicPermissions = getBooleanParameter(request, Constants.PARAM_ONLY_BASIC_PERMISSIONS);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            Acl acl = service.getAcl(repositoryId, objectId, onlyBasicPermissions, null);

            if (stopAfterService(service)) {
                return;
            }

            // return ACL
            response.setStatus(HttpServletResponse.SC_OK);

            JSONObject jsonObject = JSONConverter.convert(acl);
            if (jsonObject == null) {
                jsonObject = new JSONObject();
            }

            writeJSON(jsonObject, request, response);
        }
    }

    /**
     * applyACL.
     */
    public static class ApplyACL extends AbstractBrowserServiceCall {
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
            AclPropagation aclPropagation = getEnumParameter(request, Constants.PARAM_ACL_PROPAGATION,
                    AclPropagation.class);

            // execute
            ControlParser cp = new ControlParser(request);

            if (stopBeforeService(service)) {
                return;
            }

            Acl acl = service.applyAcl(repositoryId, objectId, createAddAcl(cp), createRemoveAcl(cp), aclPropagation,
                    null);

            if (stopAfterService(service)) {
                return;
            }

            // return ACL
            setStatus(request, response, HttpServletResponse.SC_CREATED);

            JSONObject jsonObject = JSONConverter.convert(acl);
            if (jsonObject == null) {
                jsonObject = new JSONObject();
            }

            writeJSON(jsonObject, request, response);
        }
    }
}
