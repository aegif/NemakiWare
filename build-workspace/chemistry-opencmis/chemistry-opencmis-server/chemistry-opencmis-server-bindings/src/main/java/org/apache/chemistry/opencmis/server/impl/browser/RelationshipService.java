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
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_MAX_ITEMS;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_RELATIONSHIP_DIRECTION;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_RENDITION_FILTER;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_SKIP_COUNT;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_SUB_RELATIONSHIP_TYPES;
import static org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_TYPE_ID;

import java.math.BigInteger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;

public class RelationshipService {

    /**
     * getObjectRelationships.
     */
    public static class GetObjectRelationships extends AbstractBrowserServiceCall {
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
            Boolean includeSubRelationshipTypes = getBooleanParameter(request, PARAM_SUB_RELATIONSHIP_TYPES);
            RelationshipDirection relationshipDirection = getEnumParameter(request, PARAM_RELATIONSHIP_DIRECTION,
                    RelationshipDirection.class);
            String typeId = getStringParameter(request, PARAM_TYPE_ID);
            String renditionFilter = getStringParameter(request, PARAM_RENDITION_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, PARAM_ALLOWABLE_ACTIONS);
            BigInteger maxItems = getBigIntegerParameter(request, PARAM_MAX_ITEMS);
            BigInteger skipCount = getBigIntegerParameter(request, PARAM_SKIP_COUNT);
            boolean succinct = getBooleanParameter(request, Constants.PARAM_SUCCINCT, false);
            DateTimeFormat dateTimeFormat = getDateTimeFormatParameter(request);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectList relationships = service.getObjectRelationships(repositoryId, objectId,
                    includeSubRelationshipTypes, relationshipDirection, typeId, renditionFilter,
                    includeAllowableActions, maxItems, skipCount, null);

            if (stopAfterService(service)) {
                return;
            }

            if (relationships == null) {
                throw new CmisRuntimeException("Relationships are null!");
            }

            TypeCache typeCache = new ServerTypeCacheImpl(repositoryId, service);
            JSONObject jsonChildren = JSONConverter.convert(relationships, typeCache,
                    JSONConverter.PropertyMode.OBJECT, succinct, dateTimeFormat);

            response.setStatus(HttpServletResponse.SC_OK);
            writeJSON(jsonChildren, request, response);
        }
    }
}
