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

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InMemory Stored Document A document is a stored object that has a path and
 * (optional) content.
 */

public class DocumentImpl extends FilingImpl implements Document {
    private ContentStream fContent;

    private static final Logger LOG = LoggerFactory.getLogger(DocumentImpl.class.getName());

    public DocumentImpl() { // visibility should be package
        super();
    }

    @Override
    public ContentStream getContent() {
        return fContent;
    }

    @Override
    public void setContent(ContentStream content) {
        fContent = content;
    }

    @Override
    public void fillProperties(Map<String, PropertyData<?>> properties, BindingsObjectFactory objFactory,
            List<String> requestedIds) {

        super.fillProperties(properties, objFactory, requestedIds);

        // fill the version related properties (versions should override this
        // but the spec requires some
        // properties always to be set

        if (FilterParser.isContainedInFilter(PropertyIds.IS_IMMUTABLE, requestedIds)) {
            properties.put(PropertyIds.IS_IMMUTABLE,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_IMMUTABLE, false));
        }

        // Set the content related properties
        if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_FILE_NAME, requestedIds)) {
            properties.put(PropertyIds.CONTENT_STREAM_FILE_NAME, objFactory.createPropertyStringData(
                    PropertyIds.CONTENT_STREAM_FILE_NAME, null != fContent ? fContent.getFileName() : (String) null));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_ID, requestedIds)) {
            properties.put(PropertyIds.CONTENT_STREAM_ID,
                    objFactory.createPropertyStringData(PropertyIds.CONTENT_STREAM_ID, (String) null));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_LENGTH, requestedIds)) {
            properties.put(
                    PropertyIds.CONTENT_STREAM_LENGTH,
                    objFactory.createPropertyIntegerData(PropertyIds.CONTENT_STREAM_LENGTH,
                            null != fContent ? fContent.getBigLength() : null));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CONTENT_STREAM_MIME_TYPE, requestedIds)) {
            properties.put(PropertyIds.CONTENT_STREAM_MIME_TYPE, objFactory.createPropertyStringData(
                    PropertyIds.CONTENT_STREAM_MIME_TYPE, null != fContent ? fContent.getMimeType() : (String) null));
        }

        // Spec requires versioning properties even for unversioned documents
        // overwrite the version related properties
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_ID, requestedIds)) {
            properties.put(PropertyIds.VERSION_SERIES_ID,
                    objFactory.createPropertyIdData(PropertyIds.VERSION_SERIES_ID, getId()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, requestedIds)) {
            properties.put(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, false));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, requestedIds)) {
            properties.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
                    objFactory.createPropertyStringData(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, (String) null));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, requestedIds)) {
            properties.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
                    objFactory.createPropertyIdData(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, (String) null));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_LATEST_VERSION, requestedIds)) {
            properties.put(PropertyIds.IS_LATEST_VERSION,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_LATEST_VERSION, true));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_MAJOR_VERSION, requestedIds)) {
            properties.put(PropertyIds.IS_MAJOR_VERSION,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_MAJOR_VERSION, true));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_LATEST_MAJOR_VERSION, requestedIds)) {
            properties.put(PropertyIds.IS_LATEST_MAJOR_VERSION,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_LATEST_MAJOR_VERSION, true));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CHECKIN_COMMENT, requestedIds)) {
            properties.put(PropertyIds.CHECKIN_COMMENT,
                    objFactory.createPropertyStringData(PropertyIds.CHECKIN_COMMENT, (String) null));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_LABEL, requestedIds)) {
            properties.put(PropertyIds.VERSION_LABEL,
                    objFactory.createPropertyStringData(PropertyIds.VERSION_LABEL, (String) null));
        }

        // CMIS 1.1
        if (FilterParser.isContainedInFilter(PropertyIds.IS_PRIVATE_WORKING_COPY, requestedIds)) {
            properties.put(PropertyIds.IS_PRIVATE_WORKING_COPY,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_PRIVATE_WORKING_COPY, false));
        }

    }

    @Override
    public boolean hasContent() {
        return null != fContent;
    }
    
    @Override
    public boolean hasRendition(String user) {
        return RenditionUtil.hasRendition(this, user);
    }


}
