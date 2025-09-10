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
package org.apache.chemistry.opencmis.client.bindings.spi.browser;

import java.math.BigInteger;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;

/**
 * Relationship Service Browser Binding client.
 */
public class RelationshipServiceImpl extends AbstractBrowserBindingService implements RelationshipService {

    /**
     * Constructor.
     */
    public RelationshipServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public ObjectList getObjectRelationships(String repositoryId, String objectId, Boolean includeSubRelationshipTypes,
            RelationshipDirection relationshipDirection, String typeId, String filter, Boolean includeAllowableActions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_RELATIONSHIPS);
        url.addParameter(Constants.PARAM_SUB_RELATIONSHIP_TYPES, includeSubRelationshipTypes);
        url.addParameter(Constants.PARAM_RELATIONSHIP_DIRECTION, relationshipDirection);
        url.addParameter(Constants.PARAM_TYPE_ID, typeId);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObjectList(json, typeCache, false);
    }
}
