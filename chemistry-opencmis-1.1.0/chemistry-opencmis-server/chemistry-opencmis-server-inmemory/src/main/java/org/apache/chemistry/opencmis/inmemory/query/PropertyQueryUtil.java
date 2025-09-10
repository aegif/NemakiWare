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
package org.apache.chemistry.opencmis.inmemory.query;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Content;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Document;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Policy;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Relationship;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;

public final class PropertyQueryUtil {

    private PropertyQueryUtil() {
    }

    public static Object getProperty(StoredObject so, String propertyId, PropertyDefinition<?> pd, boolean cmis11) {
        ContentStream content = null;
        DocumentVersion ver = null;
        VersionedDocument verDoc = null;
        Folder folder = null;
        Document doc = null;
        Relationship rel = null;
        Policy pol = null;

        if (so instanceof Content) {
            content = ((Content) so).getContent();
        }
        if (so instanceof DocumentVersion) {
            ver = (DocumentVersion) so;
        }
        if (so instanceof VersionedDocument) {
            verDoc = (VersionedDocument) so;
        }
        if (so instanceof Folder) {
            folder = (Folder) so;
        }
        if (so instanceof Document) {
            doc = (Document) so;
        }
        if (so instanceof Relationship) {
            rel = (Relationship) so;
        }
        if (so instanceof Policy) {
            pol = (Policy) so;
        }

        // generic properties:
        if (propertyId.equals(PropertyIds.NAME)) {
            return so.getName();
        }
        if (propertyId.equals(PropertyIds.OBJECT_ID)) {
            return so.getId();
        }
        if (propertyId.equals(PropertyIds.OBJECT_TYPE_ID)) {
            return so.getTypeId();
        }
        if (propertyId.equals(PropertyIds.BASE_TYPE_ID)) {
            return null; // base type id not available from so
        }
        if (propertyId.equals(PropertyIds.CREATED_BY)) {
            return so.getCreatedBy();
        }
        if (propertyId.equals(PropertyIds.CREATION_DATE)) {
            return so.getCreatedAt();
        }
        if (propertyId.equals(PropertyIds.LAST_MODIFIED_BY)) {
            return so.getModifiedBy();
        }
        if (propertyId.equals(PropertyIds.LAST_MODIFICATION_DATE)) {
            return so.getModifiedAt();
        }
        if (propertyId.equals(PropertyIds.CHANGE_TOKEN)) {
            return so.getChangeToken();
        }
        if (cmis11 && propertyId.equals(PropertyIds.DESCRIPTION)) {
            return so.getDescription();
        }

        if (ver != null) {
            // get version related properties
            // not support on a version, only on a versioned document:
            // VERSION_SERIES_ID, IS_VERSION_SERIES_CHECKED_OUT,
            // VERSION_SERIES_CHECKED_OUT_BY,
            // VERSION_SERIES_CHECKED_OUT_ID, IS_LATEST_MAJOR_VERSION,
            // IS_LATEST_VERSION
            if (propertyId.equals(PropertyIds.IS_MAJOR_VERSION)) {
                return ver.isMajor();
            }

            if (propertyId.equals(PropertyIds.CHECKIN_COMMENT)) {
                return ver.getCheckinComment();
            }
            if (propertyId.equals(PropertyIds.VERSION_LABEL)) {
                return ver.getVersionLabel();
            }
            if (propertyId.equals(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID)) {
                return ver.isPwc() ? ver.getId() : null;
            }
        }

        // get versioned document related properties
        if (verDoc != null) {
            if (propertyId.equals(PropertyIds.VERSION_SERIES_ID)) {
                return verDoc.getId();
            }
            if (propertyId.equals(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT)) {
                return verDoc.isCheckedOut();
            }
            if (propertyId.equals(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY)) {
                return verDoc.getCheckedOutBy();
            }
        }

        // Set the content related properties
        if (null != content) {
            // omit: PropertyIds.CMIS_CONTENT_STREAM_ID
            if (propertyId.equals(PropertyIds.CONTENT_STREAM_FILE_NAME)) {
                return content.getFileName();
            }

            if (propertyId.equals(PropertyIds.CONTENT_STREAM_LENGTH)) {
                return content.getBigLength();
            }
            if (propertyId.equals(PropertyIds.CONTENT_STREAM_MIME_TYPE)) {
                return content.getMimeType();
            }
        }

        if (folder != null && propertyId.equals(PropertyIds.PARENT_ID)) {
            return folder.getParentId();
        }

        if (doc != null && propertyId.equals(PropertyIds.IS_IMMUTABLE)) {
            return false;
        }

        if (rel != null) {
            if (propertyId.equals(PropertyIds.SOURCE_ID)) {
                return rel.getSourceObjectId();
            }
            if (propertyId.equals(PropertyIds.TARGET_ID)) {
                return rel.getTargetObjectId();
            }
        }

        if (pol != null && propertyId.equals(PropertyIds.POLICY_TEXT)) {
            return pol.getPolicyText();
        }

        // try custom property:
        PropertyData<?> lVal = so.getProperties().get(propertyId);
        if (null == lVal) {
            return null;
        } else if (pd.getCardinality() == Cardinality.SINGLE) {
            return lVal.getFirstValue();
        } else {
            return lVal.getValues();
        }
    }

}
