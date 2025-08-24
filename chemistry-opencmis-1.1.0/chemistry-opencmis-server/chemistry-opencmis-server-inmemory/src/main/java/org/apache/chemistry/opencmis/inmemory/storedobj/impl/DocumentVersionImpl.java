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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.ConfigConstants;
import org.apache.chemistry.opencmis.inmemory.ConfigurationSettings;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.MultiFiling;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;

/**
 * A class representing a single version of a document
 */
public class DocumentVersionImpl extends StoredObjectImpl implements DocumentVersion, MultiFiling {

    private static final Long MAX_CONTENT_SIZE_KB = ConfigurationSettings
            .getConfigurationValueAsLong(ConfigConstants.MAX_CONTENT_SIZE_KB);

    private ContentStream fContent;
    private final VersionedDocumentImpl fContainer; // the document this version
                                                    // belongs to
    private String fComment; // checkin comment
    private boolean fIsMajor;
    private boolean fIsPwc; // true if this is the PWC

    public DocumentVersionImpl(String repositoryId, VersionedDocument container, VersioningState verState) {
        super();
        setRepositoryId(repositoryId);
        fContainer = (VersionedDocumentImpl) container;
        fIsMajor = verState == VersioningState.MAJOR || verState == null;
        fIsPwc = verState == VersioningState.CHECKEDOUT;
        fProperties = new HashMap<String, PropertyData<?>>();
        // copy user properties from latest version
        DocumentVersionImpl src = (DocumentVersionImpl) container.getLatestVersion(false);
        if (null != src && null != src.fProperties) {
            for (Entry<String, PropertyData<?>> prop : src.fProperties.entrySet()) {
                fProperties.put(prop.getKey(), prop.getValue());
            }
        }
    }

    @Override
    public void setContent(ContentStream content) {
        setContentIntern(content);
    }

    private void setContentIntern(ContentStream content) {
        fContent = content;
    }

    @Override
    public void setCheckinComment(String comment) {
        fComment = comment;
    }

    @Override
    public String getCheckinComment() {
        return fComment;
    }

    private String createVersionLabel() {
        int majorNo = 0;
        int minorNo = 0;
        List<DocumentVersion> allVersions = fContainer.getAllVersions();
        for (DocumentVersion ver : allVersions) {
            if (ver.isMajor()) {
                ++majorNo;
                minorNo = 0;
            } else {
                ++minorNo;
            }
            if (ver == this) {
                break;
            }
        }
        String label = majorNo + "." + minorNo;
        return label;
    }

    @Override
    public boolean isMajor() {
        return fIsMajor && !isPwc();
    }

    @Override
    public boolean isPwc() {
        return fIsPwc;
    }

    @Override
    public void commit(boolean isMajor) {
        fIsPwc = false; // unset working copy flag
        fIsMajor = isMajor;
    }

    @Override
    public ContentStream getContent() {
        return fContent;
    }

    @Override
    public VersionedDocument getParentDocument() {
        return fContainer;
    }

    private boolean isLatestVersion() {
        List<DocumentVersion> allVers = fContainer.getAllVersions();
        boolean hasPwc = null != fContainer.getPwc();
        boolean isLatestVersion;

        if (hasPwc) {
            // CMIS 1.1 forbids it for PWC
            isLatestVersion = allVers.size() > 1 && allVers.get(allVers.size() - 2) == this;
        } else {
            isLatestVersion = allVers.get(allVers.size() - 1) == this;
        }

        return isLatestVersion;
    }

    private boolean isLatestMajorVersion() {
        if (!fIsMajor) {
            return false;
        }

        List<DocumentVersion> allVersions = fContainer.getAllVersions();
        DocumentVersion latestMajor = null;

        for (DocumentVersion ver : allVersions) {
            if (ver.isMajor() && !ver.isPwc()) {
                latestMajor = ver;
            }
        }

        boolean isLatestMajorVersion = latestMajor == this;
        return isLatestMajorVersion;
    }

    @Override
    public void fillProperties(Map<String, PropertyData<?>> properties, BindingsObjectFactory objFactory,
            List<String> requestedIds) {

        DocumentVersion pwc = fContainer.getPwc();

        // First get the properties of the container (like custom type
        // properties, etc)
        fContainer.fillProperties(properties, objFactory, requestedIds);

        // overwrite the version specific properties (like modification date,
        // user, etc.)
        // and set some properties specific to the version
        super.fillProperties(properties, objFactory, requestedIds);

        if (FilterParser.isContainedInFilter(PropertyIds.IS_IMMUTABLE, requestedIds)) {
            properties.put(PropertyIds.IS_IMMUTABLE,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_IMMUTABLE, false));
        }

        // fill the version related properties
        if (FilterParser.isContainedInFilter(PropertyIds.IS_LATEST_VERSION, requestedIds)) {
            properties.put(PropertyIds.IS_LATEST_VERSION,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_LATEST_VERSION, isLatestVersion()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_MAJOR_VERSION, requestedIds)) {
            properties.put(PropertyIds.IS_MAJOR_VERSION,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_MAJOR_VERSION, fIsMajor));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_LATEST_MAJOR_VERSION, requestedIds)) {
            properties.put(PropertyIds.IS_LATEST_MAJOR_VERSION,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_LATEST_MAJOR_VERSION, isLatestMajorVersion()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_ID, requestedIds)) {
            properties.put(PropertyIds.VERSION_SERIES_ID,
                    objFactory.createPropertyIdData(PropertyIds.VERSION_SERIES_ID, fContainer.getId()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, requestedIds)) {
            properties.put(
                    PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
                            fContainer.isCheckedOut()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, requestedIds)) {
            properties.put(
                    PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
                    objFactory.createPropertyStringData(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
                            fContainer.getCheckedOutBy()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, requestedIds)) {
            properties.put(
                    PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
                    objFactory.createPropertyIdData(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
                            pwc == null ? null : pwc.getId()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CHECKIN_COMMENT, requestedIds)) {
            properties.put(PropertyIds.CHECKIN_COMMENT,
                    objFactory.createPropertyStringData(PropertyIds.CHECKIN_COMMENT, fComment));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_LABEL, requestedIds)) {
            properties.put(PropertyIds.VERSION_LABEL,
                    objFactory.createPropertyStringData(PropertyIds.VERSION_LABEL, getVersionLabel()));
        }

        // Set the content related properties
        if (null != fContent) {
            if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_FILE_NAME, requestedIds)) {
                properties.put(
                        PropertyIds.CONTENT_STREAM_FILE_NAME,
                        objFactory.createPropertyStringData(PropertyIds.CONTENT_STREAM_FILE_NAME,
                                fContent.getFileName()));
            }
            if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_ID, requestedIds)) {
                properties.put(PropertyIds.CONTENT_STREAM_ID,
                        objFactory.createPropertyStringData(PropertyIds.CONTENT_STREAM_ID, (String) null));
            }
            if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_LENGTH, requestedIds)) {
                properties.put(PropertyIds.CONTENT_STREAM_LENGTH, objFactory.createPropertyIntegerData(
                        PropertyIds.CONTENT_STREAM_LENGTH, fContent.getBigLength()));
            }
            if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_MIME_TYPE, requestedIds)) {
                properties.put(
                        PropertyIds.CONTENT_STREAM_MIME_TYPE,
                        objFactory.createPropertyStringData(PropertyIds.CONTENT_STREAM_MIME_TYPE,
                                fContent.getMimeType()));
            }
        }

        // CMIS 1.1
        if (FilterParser.isContainedInFilter(PropertyIds.IS_PRIVATE_WORKING_COPY, requestedIds)) {
            properties.put(PropertyIds.IS_PRIVATE_WORKING_COPY,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_PRIVATE_WORKING_COPY, isPwc()));
        }

    }

    @Override
    public int getAclId() {
        return ((StoredObjectImpl) fContainer).getAclId();
    }

    @Override
    public void setAclId(int id) {
        ((StoredObjectImpl) fContainer).setAclId(id);
    }

    @Override
    public List<String> getParentIds() {
        return fContainer.getParentIds();
    }

    @Override
    public String getPathSegment() {
        return fContainer.getPathSegment();
    }

    @Override
    public boolean hasContent() {
        return null != fContent;
    }

    @Override
    public boolean hasParent() {
        return fContainer.hasParent();
    }

    @Override
    public String getVersionLabel() {
        return createVersionLabel();
    }

    @Override
    public void addParentId(String parentId) {
        fContainer.addParentId(parentId);
    }

    @Override
    public void removeParentId(String parentId) {
        fContainer.removeParentId(parentId);
    }

}
