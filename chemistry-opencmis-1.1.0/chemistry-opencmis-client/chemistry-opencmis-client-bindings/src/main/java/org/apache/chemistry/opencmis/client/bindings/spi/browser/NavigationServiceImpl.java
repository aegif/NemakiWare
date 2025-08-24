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
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;

/**
 * Navigation Service Browser Binding client.
 */
public class NavigationServiceImpl extends AbstractBrowserBindingService implements NavigationService {

    /**
     * Constructor.
     */
    public NavigationServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public ObjectInFolderList getChildren(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, folderId, Constants.SELECTOR_CHILDREN);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ORDER_BY, orderBy);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObjectInFolderList(json, typeCache);
    }

    @Override
    public List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, folderId, Constants.SELECTOR_DESCENDANTS);
        url.addParameter(Constants.PARAM_DEPTH, depth);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertDescendants(json, typeCache);
    }

    @Override
    public List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, folderId, Constants.SELECTOR_FOLDER_TREE);
        url.addParameter(Constants.PARAM_DEPTH, depth);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertDescendants(json, typeCache);
    }

    @Override
    public List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includeRelativePathSegment, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_PARENTS);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_RELATIVE_PATH_SEGMENT, includeRelativePathSegment);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObjectParents(json, typeCache);
    }

    @Override
    public ObjectData getFolderParent(String repositoryId, String folderId, String filter, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, folderId, Constants.SELECTOR_PARENT);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObject(json, typeCache);
    }

    @Override
    public ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        // build URL
        UrlBuilder url = (folderId != null ? getObjectUrl(repositoryId, folderId, Constants.SELECTOR_CHECKEDOUT)
                : getRepositoryUrl(repositoryId, Constants.SELECTOR_CHECKEDOUT));
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ORDER_BY, orderBy);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
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
