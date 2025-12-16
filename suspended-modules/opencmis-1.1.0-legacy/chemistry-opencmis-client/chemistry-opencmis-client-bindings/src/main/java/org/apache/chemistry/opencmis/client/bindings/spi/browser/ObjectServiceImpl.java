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

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.MimeHelper;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FailedToDeleteDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PartialContentStreamImpl;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;

/**
 * Object Service Browser Binding client.
 */
public class ObjectServiceImpl extends AbstractBrowserBindingService implements ObjectService {

    /**
     * Constructor.
     */
    public ObjectServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public String createDocument(String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        // build URL
        UrlBuilder url = folderId != null ? getObjectUrl(repositoryId, folderId) : getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_DOCUMENT, contentStream);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
        formData.addParameter(Constants.PARAM_VERSIONIG_STATE, versioningState);
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

        return newObj == null ? null : newObj.getId();
    }

    @Override
    public String createDocumentFromSource(String repositoryId, String sourceId, Properties properties,
            String folderId, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        // build URL
        UrlBuilder url = folderId != null ? getObjectUrl(repositoryId, folderId) : getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_DOCUMENT_FROM_SOURCE);
        formData.addParameter(Constants.PARAM_SOURCE_ID, sourceId);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
        formData.addParameter(Constants.PARAM_VERSIONIG_STATE, versioningState);
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

        return newObj == null ? null : newObj.getId();
    }

    @Override
    public String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, folderId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_FOLDER);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
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

        return newObj == null ? null : newObj.getId();
    }

    @Override
    public String createRelationship(String repositoryId, Properties properties, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_RELATIONSHIP);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
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

        return newObj == null ? null : newObj.getId();
    }

    @Override
    public String createPolicy(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        // build URL
        UrlBuilder url = folderId != null ? getObjectUrl(repositoryId, folderId) : getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_POLICY);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
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

        return newObj == null ? null : newObj.getId();
    }

    @Override
    public String createItem(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        // build URL
        UrlBuilder url = folderId != null ? getObjectUrl(repositoryId, folderId) : getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_ITEM);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
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

        return newObj == null ? null : newObj.getId();
    }

    @Override
    public AllowableActions getAllowableActions(String repositoryId, String objectId, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_ALLOWABLEACTIONS);

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        return JSONConverter.convertAllowableActions(json);
    }

    @Override
    public ObjectData getObject(String repositoryId, String objectId, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_OBJECT);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
        url.addParameter(Constants.PARAM_ACL, includeAcl);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObject(json, typeCache);
    }

    @Override
    public ObjectData getObjectByPath(String repositoryId, String path, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getPathUrl(repositoryId, path, Constants.SELECTOR_OBJECT);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
        url.addParameter(Constants.PARAM_ACL, includeAcl);
        url.addParameter(Constants.PARAM_SUCCINCT, getSuccinctParameter());
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        TypeCache typeCache = new ClientTypeCacheImpl(repositoryId, this);

        return JSONConverter.convertObject(json, typeCache);
    }

    @Override
    public Properties getProperties(String repositoryId, String objectId, String filter, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_PROPERTIES);
        url.addParameter(Constants.PARAM_FILTER, filter);
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
    public List<RenditionData> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_RENDITIONS);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);

        // read and parse
        Response resp = read(url);
        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        return JSONConverter.convertRenditions(json);
    }

    @Override
    public ContentStream getContentStream(String repositoryId, String objectId, String streamId, BigInteger offset,
            BigInteger length, ExtensionsData extension) {

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId, Constants.SELECTOR_CONTENT);
        url.addParameter(Constants.PARAM_STREAM_ID, streamId);

        // get the content
        Response resp = getHttpInvoker().invokeGET(url, getSession(), offset, length);

        // check response code
        if ((resp.getResponseCode() != 200) && (resp.getResponseCode() != 206)) {
            throw convertStatusCode(resp.getResponseCode(), resp.getResponseMessage(), resp.getErrorContent(), null);
        }

        // get filename from Content-Disposition header
        String filename = null;
        String contentDisposition = resp.getContentDisposition();
        if (contentDisposition != null) {
            filename = MimeHelper.decodeContentDispositionFilename(contentDisposition);
        }

        // build result object
        ContentStreamImpl result;
        if (resp.getResponseCode() == 206) {
            result = new PartialContentStreamImpl();
        } else {
            result = new ContentStreamImpl();
        }

        result.setFileName(filename);
        result.setLength(resp.getContentLength());
        result.setMimeType(resp.getContentTypeHeader());
        result.setStream(resp.getStream());

        return result;
    }

    @Override
    public void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            Properties properties, ExtensionsData extension) {
        // we need an object ID
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object ID must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_UPDATE_PROPERTIES);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
        formData.addParameter(Constants.PARAM_CHANGE_TOKEN,
                (changeToken == null || getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                        : changeToken.getValue()));
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

        setChangeToken(changeToken, newObj);
    }

    @Override
    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
        // we need object ids
        if (isNullOrEmpty(objectIdAndChangeToken)) {
            throw new CmisInvalidArgumentException("Object ids must be set!");
        }

        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_BULK_UPDATE);
        formData.addObjectIdsAndChangeTokens(objectIdAndChangeToken);
        formData.addPropertiesParameters(properties, getDateTimeFormat());
        formData.addSecondaryTypeIds(addSecondaryTypeIds);
        formData.removeSecondaryTypeIds(removeSecondaryTypeIds);

        // send and parse
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        return JSONConverter.convertBulkUpdate(json);
    }

    @Override
    public void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            ExtensionsData extension) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object ID must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_MOVE);
        formData.addParameter(Constants.PARAM_TARGET_FOLDER_ID, targetFolderId);
        formData.addParameter(Constants.PARAM_SOURCE_FOLDER_ID, sourceFolderId);
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
    public void deleteObject(String repositoryId, String objectId, Boolean allVersions, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_DELETE);
        formData.addParameter(Constants.PARAM_ALL_VERSIONS, allVersions);

        // send
        postAndConsume(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });
    }

    @Override
    public FailedToDeleteData deleteTree(String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, folderId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_DELETE_TREE);
        formData.addParameter(Constants.PARAM_ALL_VERSIONS, allVersions);
        formData.addParameter(Constants.PARAM_UNFILE_OBJECTS, unfileObjects);
        formData.addParameter(Constants.PARAM_CONTINUE_ON_FAILURE, continueOnFailure);

        // send
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        if (resp.hasResponseStream()) {
            try {
                InputStream responseStream = IOUtils.checkForBytes(resp.getStream(), 8192);
                if (responseStream != null) {
                    Map<String, Object> json = parseObject(responseStream, resp.getCharset());
                    return JSONConverter.convertFailedToDelete(json);
                }
            } catch (IOException e) {
                throw new CmisConnectionException("Cannot read response!", e);
            }
        }

        return new FailedToDeleteDataImpl();
    }

    @Override
    public void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object ID must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_SET_CONTENT, contentStream);
        formData.addParameter(Constants.PARAM_OVERWRITE_FLAG, overwriteFlag);
        formData.addParameter(Constants.PARAM_CHANGE_TOKEN,
                (changeToken == null || getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                        : changeToken.getValue()));
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

        setChangeToken(changeToken, newObj);
    }

    @Override
    public void appendContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object ID must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_APPEND_CONTENT, contentStream);
        formData.addParameter(Constants.CONTROL_IS_LAST_CHUNK, isLastChunk);
        formData.addParameter(Constants.PARAM_CHANGE_TOKEN,
                (changeToken == null || getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                        : changeToken.getValue()));
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

        setChangeToken(changeToken, newObj);
    }

    @Override
    public void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ExtensionsData extension) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object ID must be set!");
        }

        // build URL
        UrlBuilder url = getObjectUrl(repositoryId, objectId.getValue());

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_DELETE_CONTENT);
        formData.addParameter(Constants.PARAM_CHANGE_TOKEN,
                (changeToken == null || getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                        : changeToken.getValue()));
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

        setChangeToken(changeToken, newObj);
    }
}
