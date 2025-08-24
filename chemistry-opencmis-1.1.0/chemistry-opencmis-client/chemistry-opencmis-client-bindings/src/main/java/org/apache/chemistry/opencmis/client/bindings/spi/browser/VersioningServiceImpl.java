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
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.ReturnVersion;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

/**
 * Versioning Service Browser Binding client.
 */
public class VersioningServiceImpl extends AbstractBrowserBindingService implements VersioningService {

    /**
     * Constructor.
     */
    public VersioningServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public void checkOut(String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object id must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CHECK_OUT);
        formData.addSuccinctFlag(getSuccinct());

        // send and parse
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        ObjectData newObj = JSONConverter.convertObject(json, typeCache);

        objectId.setValue(newObj == null ? null : newObj.getId());
    }

    @Override
    public void cancelCheckOut(String repositoryId, String objectId, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CANCEL_CHECK_OUT);

        // send
        postAndConsume(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });
    }

    @Override
    public void checkIn(String repositoryId, Holder<String> objectId, Boolean major, Properties properties,
            ContentStream contentStream, String checkinComment, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object id must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CHECK_IN, contentStream);
        formData.addParameter(Constants.PARAM_MAJOR, major);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
        formData.addParameter(Constants.PARAM_CHECKIN_COMMENT, checkinComment);
        formData.addPoliciesParameters(policies);
        formData.addAddAcesParameters(addAces);
        formData.addRemoveAcesParameters(removeAces);
        formData.addSuccinctFlag(getSuccinct());

        // send and parse
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        ObjectData newObj = JSONConverter.convertObject(json, typeCache);

        objectId.setValue(newObj == null ? null : newObj.getId());
    }

    @Override
    public ObjectData getObjectOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_OBJECT);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
        url.addParameter(Constants.PARAM_ACL, includeAcl);
        url.addParameter(Constants.PARAM_RETURN_VERSION,
                (major == null || Boolean.FALSE.equals(major) ? ReturnVersion.LATEST : ReturnVersion.LASTESTMAJOR));
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObject(json, typeCache);
    }

    @Override
    public Properties getPropertiesOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_PROPERTIES);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_RETURN_VERSION,
                (major == null || Boolean.FALSE.equals(major) ? ReturnVersion.LATEST : ReturnVersion.LASTESTMAJOR));
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        if (getSuccinct()) {
            TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);
            return JSONConverter.convertSuccinctProperties(json, null, typeCache);
        } else {
            return JSONConverter.convertProperties(json, null);
        }
    }

    @Override
    public List<ObjectData> getAllVersions(String repositoryId, String objectId, String versionSeriesId, String filter,
            Boolean includeAllowableActions, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_VERSIONS);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObjects(json, typeCache);
    }
}
