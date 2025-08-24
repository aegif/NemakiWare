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
package org.apache.chemistry.opencmis.commons;

/**
 * CMIS property id constants.
 */
public final class PropertyIds {

    // ---- base ----
    /**
     * CMIS property {@code cmis:name}: name of the object.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String NAME = "cmis:name";
    /**
     * CMIS property {@code cmis:objectId}: ID of the object.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String OBJECT_ID = "cmis:objectId";
    /**
     * CMIS property {@code cmis:objectTypeId}: ID of primary type of the
     * object.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String OBJECT_TYPE_ID = "cmis:objectTypeId";
    /**
     * CMIS property {@code cmis:baseTypeId}: ID of the base type of the object.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String BASE_TYPE_ID = "cmis:baseTypeId";
    /**
     * CMIS property {@code cmis:createdBy}: creator of the object.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String CREATED_BY = "cmis:createdBy";
    /**
     * CMIS property {@code cmis:creationDate}: creation date.
     * <p>
     * CMIS data type: datetime<br>
     * Java type: GregorianCalendar
     * 
     * @cmis 1.0
     */
    public static final String CREATION_DATE = "cmis:creationDate";
    /**
     * CMIS property {@code cmis:lastModifiedBy}: last modifier of the object.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String LAST_MODIFIED_BY = "cmis:lastModifiedBy";
    /**
     * CMIS property {@code cmis:lastModificationDate}: last modification date.
     * <p>
     * CMIS data type: datetime<br>
     * Java type: GregorianCalendar
     * 
     * @cmis 1.0
     */
    public static final String LAST_MODIFICATION_DATE = "cmis:lastModificationDate";
    /**
     * CMIS property {@code cmis:changeToken}: change token of the object.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String CHANGE_TOKEN = "cmis:changeToken";
    /**
     * CMIS property {@code cmis:description}: description of the object.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.1
     */
    public static final String DESCRIPTION = "cmis:description";
    /**
     * CMIS property {@code cmis:secondaryObjectTypeIds} (multivalue): list of
     * IDs of the secondary types of the object.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.1
     */
    public static final String SECONDARY_OBJECT_TYPE_IDS = "cmis:secondaryObjectTypeIds";

    // ---- document ----
    /**
     * CMIS document property {@code cmis:isImmutable}: flag the indicates if
     * the document is immutable.
     * <p>
     * CMIS data type: boolean<br>
     * Java type: Boolean
     * 
     * @cmis 1.0
     */
    public static final String IS_IMMUTABLE = "cmis:isImmutable";
    /**
     * CMIS document property {@code cmis:isLatestVersion}: flag the indicates
     * if the document is the latest version.
     * <p>
     * CMIS data type: boolean<br>
     * Java type: Boolean
     * 
     * @cmis 1.0
     */
    public static final String IS_LATEST_VERSION = "cmis:isLatestVersion";
    /**
     * CMIS document property {@code cmis:isMajorVersion}: flag the indicates if
     * the document is a major version.
     * <p>
     * CMIS data type: boolean<br>
     * Java type: Boolean
     * 
     * @cmis 1.0
     */
    public static final String IS_MAJOR_VERSION = "cmis:isMajorVersion";
    /**
     * CMIS document property {@code cmis:isLatestMajorVersion}: flag the
     * indicates if the document is the latest major version.
     * <p>
     * CMIS data type: boolean<br>
     * Java type: Boolean
     * 
     * @cmis 1.0
     */
    public static final String IS_LATEST_MAJOR_VERSION = "cmis:isLatestMajorVersion";
    /**
     * CMIS document property {@code cmis:versionLabel}: version label of the
     * document.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String VERSION_LABEL = "cmis:versionLabel";
    /**
     * CMIS document property {@code cmis:versionSeriesId}: ID of the version
     * series.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String VERSION_SERIES_ID = "cmis:versionSeriesId";
    /**
     * CMIS document property {@code cmis:isVersionSeriesCheckedOut}: flag the
     * indicates if the document is checked out.
     * <p>
     * CMIS data type: boolean<br>
     * Java type: Boolean
     * 
     * @cmis 1.0
     */
    public static final String IS_VERSION_SERIES_CHECKED_OUT = "cmis:isVersionSeriesCheckedOut";
    /**
     * CMIS document property {@code cmis:versionSeriesCheckedOutBy}: user who
     * checked out the document, if the document is checked out.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String VERSION_SERIES_CHECKED_OUT_BY = "cmis:versionSeriesCheckedOutBy";
    /**
     * CMIS document property {@code cmis:versionSeriesCheckedOutId}: ID of the
     * PWC, if the document is checked out.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String VERSION_SERIES_CHECKED_OUT_ID = "cmis:versionSeriesCheckedOutId";
    /**
     * CMIS document property {@code cmis:checkinComment}: check-in comment for
     * the document version.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String CHECKIN_COMMENT = "cmis:checkinComment";
    /**
     * CMIS document property {@code cmis:contentStreamLength}: length of the
     * content stream, if the document has content.
     * <p>
     * CMIS data type: integer<br>
     * Java type: BigInteger
     * 
     * @cmis 1.0
     */
    public static final String CONTENT_STREAM_LENGTH = "cmis:contentStreamLength";
    /**
     * CMIS document property {@code cmis:contentStreamMimeType}: MIME type of
     * the content stream, if the document has content.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String CONTENT_STREAM_MIME_TYPE = "cmis:contentStreamMimeType";
    /**
     * CMIS document property {@code cmis:contentStreamFileName}: file name, if
     * the document has content.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String CONTENT_STREAM_FILE_NAME = "cmis:contentStreamFileName";
    /**
     * CMIS document property {@code cmis:contentStreamId}: content stream ID.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String CONTENT_STREAM_ID = "cmis:contentStreamId";
    /**
     * CMIS document property {@code cmis:isPrivateWorkingCopy}: flag the
     * indicates if the document is a PWC.
     * <p>
     * CMIS data type: boolean<br>
     * Java type: Boolean
     * 
     * @cmis 1.1
     */
    public static final String IS_PRIVATE_WORKING_COPY = "cmis:isPrivateWorkingCopy";

    // ---- folder ----
    /**
     * CMIS folder property {@code cmis:parentId}: ID of the parent folder.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String PARENT_ID = "cmis:parentId";
    /**
     * CMIS folder property {@code cmis:allowedChildObjectTypeIds} (multivalue):
     * IDs of the types that can be filed in the folder.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String ALLOWED_CHILD_OBJECT_TYPE_IDS = "cmis:allowedChildObjectTypeIds";
    /**
     * CMIS folder property {@code cmis:path}: folder path.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String PATH = "cmis:path";

    // ---- relationship ----
    /**
     * CMIS relationship property {@code cmis:sourceId}: ID of the source
     * object.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String SOURCE_ID = "cmis:sourceId";
    /**
     * CMIS relationship property {@code cmis:targetId}: ID of the target
     * object.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String TARGET_ID = "cmis:targetId";

    // ---- policy ----
    /**
     * CMIS policy property {@code cmis:policyText}: policy text.
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis 1.0
     */
    public static final String POLICY_TEXT = "cmis:policyText";

    // ---- retention ----
    /**
     * CMIS retention property {@code cmis:rm_expirationDate}: expiration date.
     * <p>
     * CMIS data type: datetime<br>
     * Java type: GregorianCalendar
     * 
     * @cmis 1.1
     */
    public static final String EXPIRATION_DATE = "cmis:rm_expirationDate";
    /**
     * CMIS retention property {@code cmis:rm_startOfRetention}: start date.
     * <p>
     * CMIS data type: datetime<br>
     * Java type: GregorianCalendar
     * 
     * @cmis 1.1
     */
    public static final String START_OF_RETENTION = "cmis:rm_startOfRetention";
    /**
     * CMIS retention property {@code cmis:rm_destructionDate}: destruction
     * date.
     * <p>
     * CMIS data type: datetime<br>
     * Java type: GregorianCalendar
     * 
     * @cmis 1.1
     */
    public static final String DESTRUCTION_DATE = "cmis:rm_destructionDate";
    /**
     * CMIS retention property {@code cmis:rm_holdIds} (multivalue): IDs of the
     * holds that are applied.
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis 1.1
     */
    public static final String HOLD_IDS = "cmis:rm_holdIds";

    // ---- extensions ----
    /**
     * Content Hash property {@code cmis:contentStreamHash} (multivalue): hashes
     * of the content stream
     * <p>
     * CMIS data type: string<br>
     * Java type: String
     * 
     * @cmis Extension
     */
    public static final String CONTENT_STREAM_HASH = "cmis:contentStreamHash";

    /**
     * Latest accessible state property {@code cmis:latestAccessibleStateId}: ID
     * of the latest accessible version of a document
     * <p>
     * CMIS data type: id<br>
     * Java type: String
     * 
     * @cmis Extension
     */
    public static final String LATEST_ACCESSIBLE_STATE_ID = "cmis:latestAccessibleStateId";

    private PropertyIds() {
    }
}
