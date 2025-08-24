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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConstants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Discovery Service Browser Binding client.
 */
public class DiscoveryServiceImpl extends AbstractBrowserBindingService implements DiscoveryService {

    /**
     * Constructor.
     */
    public DiscoveryServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public ObjectList query(String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_QUERY);
        formData.addParameter(Constants.PARAM_STATEMENT, statement);
        formData.addParameter(Constants.PARAM_SEARCH_ALL_VERSIONS, searchAllVersions);
        formData.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        formData.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        formData.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        formData.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        formData.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);
        formData.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());
        // Important: No succinct flag here!!!

        // send and parse
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());
        return JSONConverter.convertObjectList(json, typeCache, true);
    }

    @Override
    public ObjectList getContentChanges(String repositoryId, Holder<String> changeLogToken, Boolean includeProperties,
            String filter, Boolean includePolicyIds, Boolean includeAcl, BigInteger maxItems, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId, Constants.SELECTOR_CONTENT_CHANGES);
        url.addParameter(Constants.PARAM_CHANGE_LOG_TOKEN, changeLogToken == null ? null : changeLogToken.getValue());
        url.addParameter(Constants.PARAM_PROPERTIES, includeProperties);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
        url.addParameter(Constants.PARAM_ACL, includeAcl);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        if (changeLogToken != null && json != null) {
            Object token = json.get(JSONConstants.JSON_OBJECTLIST_CHANGE_LOG_TOKEN);
            if (token instanceof String) {
                changeLogToken.setValue((String) token);
            }
        }

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObjectList(json, typeCache, false);
    }
}
